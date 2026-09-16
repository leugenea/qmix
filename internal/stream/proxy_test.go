package stream

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// mockBackend is a configurable StreamBackend for proxy tests.
type mockBackend struct {
	res   *Result
	err   error
	got   string // captured rangeHeader
	calls int
}

func (m *mockBackend) Stream(_ context.Context, _ *Track, rangeHeader string) (*Result, error) {
	m.calls++
	m.got = rangeHeader
	return m.res, m.err
}

// bodyResult is a helper to build a Result backed by a string body.
func bodyResult(body, ctype string, status int, length int64, cr, ar string) *Result {
	return &Result{
		Body:          io.NopCloser(strings.NewReader(body)),
		ContentType:   ctype,
		Status:        status,
		ContentLength: length,
		ContentRange:  cr,
		AcceptRanges:  ar,
	}
}

// serveProxy runs ServeStream against the mock backend and returns the recorder.
func serveProxy(t *testing.T, backend StreamBackend, track *Track, rangeHeader string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	if rangeHeader != "" {
		req.Header.Set("Range", rangeHeader)
	}
	rec := httptest.NewRecorder()
	ServeStream(rec, req, backend, track)
	return rec
}

func testTrack() *Track { return &Track{ID: "1", Title: "Song", Artist: "Artist"} }

// TestProxyFullStream verifies a no-range request streams the whole body as 200.
func TestProxyFullStream(t *testing.T) {
	mb := &mockBackend{res: bodyResult("audio", "audio/webm", http.StatusOK, 5, "", "bytes")}
	rec := serveProxy(t, mb, testTrack(), "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if rec.Header().Get("Content-Type") != "audio/webm" {
		t.Fatalf("content-type = %q", rec.Header().Get("Content-Type"))
	}
	if rec.Header().Get("Content-Length") != "5" {
		t.Fatalf("content-length = %q", rec.Header().Get("Content-Length"))
	}
	if rec.Body.String() != "audio" {
		t.Fatalf("body = %q", rec.Body.String())
	}
	if mb.got != "" {
		t.Fatalf("backend got range %q, want empty", mb.got)
	}
}

// TestProxyRange206 verifies a valid range produces 206 with Content-Range.
func TestProxyRange206(t *testing.T) {
	mb := &mockBackend{res: bodyResult("he", "audio/webm", http.StatusPartialContent, 11, "bytes 0-1/11", "bytes")}
	rec := serveProxy(t, mb, testTrack(), "bytes=0-1")
	if rec.Code != http.StatusPartialContent {
		t.Fatalf("status = %d, want 206", rec.Code)
	}
	if rec.Header().Get("Content-Range") != "bytes 0-1/11" {
		t.Fatalf("content-range = %q", rec.Header().Get("Content-Range"))
	}
	if rec.Body.String() != "he" {
		t.Fatalf("body = %q", rec.Body.String())
	}
	if mb.got != "bytes=0-1" {
		t.Fatalf("backend got range %q, want bytes=0-1", mb.got)
	}
}

// TestProxyInvalidRange416 verifies a malformed range is answered 416 before
// reaching the backend.
func TestProxyInvalidRange416(t *testing.T) {
	mb := &mockBackend{res: bodyResult("x", "audio/webm", http.StatusOK, 1, "", "")}
	for _, bad := range []string{"items=0-1", "bytes=", "bytes=0-1,3-5", "bytes=a-b", "bytes=-"} {
		mb.calls = 0
		rec := serveProxy(t, mb, testTrack(), bad)
		if rec.Code != http.StatusRequestedRangeNotSatisfiable {
			t.Fatalf("range %q: status = %d, want 416", bad, rec.Code)
		}
		if rec.Header().Get("Content-Range") != "bytes */*" {
			t.Fatalf("range %q: content-range = %q, want bytes */*", bad, rec.Header().Get("Content-Range"))
		}
		if mb.calls != 0 {
			t.Fatalf("range %q: backend was called, want 0", bad)
		}
	}
}

// TestProxyValidRanges verifies valid single ranges are passed through.
func TestProxyValidRanges(t *testing.T) {
	for _, good := range []string{"bytes=0-10", "bytes=50-", "bytes=-100"} {
		mb := &mockBackend{res: bodyResult("x", "audio/webm", http.StatusPartialContent, 100, "", "bytes")}
		rec := serveProxy(t, mb, testTrack(), good)
		if rec.Code != http.StatusPartialContent {
			t.Fatalf("range %q: status = %d, want %d", good, rec.Code, http.StatusPartialContent)
		}
		if mb.got != good {
			t.Fatalf("range %q: backend got %q", good, mb.got)
		}
	}
}

// TestProxyUpstream416 verifies a backend-reported unsatisfiable range is
// forwarded as 416 with its Content-Range.
func TestProxyUpstream416(t *testing.T) {
	mb := &mockBackend{res: bodyResult("", "audio/webm", http.StatusRequestedRangeNotSatisfiable, 11, "bytes */11", "bytes")}
	rec := serveProxy(t, mb, testTrack(), "bytes=999999-")
	if rec.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Fatalf("status = %d, want 416", rec.Code)
	}
	if rec.Header().Get("Content-Range") != "bytes */11" {
		t.Fatalf("content-range = %q", rec.Header().Get("Content-Range"))
	}
}

// TestProxyNotFound404 maps ErrNotFound to 404.
func TestProxyNotFound404(t *testing.T) {
	mb := &mockBackend{err: ErrNotFound}
	rec := serveProxy(t, mb, testTrack(), "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

// TestProxyServiceError502 maps upstream failure to 502.
func TestProxyServiceError502(t *testing.T) {
	mb := &mockBackend{err: errors.New("ytdlp: service error: upstream down")}
	rec := serveProxy(t, mb, testTrack(), "")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "error") {
		t.Fatalf("body = %q, want error json", rec.Body.String())
	}
}

// TestProxyDefaultStatusZero verifies a zero Status is treated as 200.
func TestProxyDefaultStatusZero(t *testing.T) {
	mb := &mockBackend{res: bodyResult("x", "audio/webm", 0, 1, "", "")}
	rec := serveProxy(t, mb, testTrack(), "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
}

type failingResponseWriter struct {
	header http.Header
	err    error
}

func (w *failingResponseWriter) Header() http.Header       { return w.header }
func (*failingResponseWriter) WriteHeader(int)             {}
func (w *failingResponseWriter) Write([]byte) (int, error) { return 0, w.err }

func TestProxyClassifiesClientWriteFailure(t *testing.T) {
	wantErr := errors.New("broken pipe")
	w := &failingResponseWriter{header: http.Header{}, err: wantErr}
	backend := &mockBackend{res: bodyResult("audio", "audio/webm", http.StatusOK, 5, "", "bytes")}
	err := ServeStream(w, httptest.NewRequest(http.MethodGet, "/stream", nil), backend, testTrack())
	if !errors.Is(err, ErrClientWrite) || !errors.Is(err, wantErr) {
		t.Fatalf("err = %v, want ErrClientWrite and wrapped writer error", err)
	}
}

func TestProxyClassifiesClientShortWrite(t *testing.T) {
	w := &failingResponseWriter{header: http.Header{}}
	backend := &mockBackend{res: bodyResult("audio", "audio/webm", http.StatusOK, 5, "", "bytes")}
	err := ServeStream(w, httptest.NewRequest(http.MethodGet, "/stream", nil), backend, testTrack())
	if !errors.Is(err, ErrClientWrite) || !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("err = %v, want ErrClientWrite and io.ErrShortWrite", err)
	}
}
