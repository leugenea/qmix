package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// closeProbe proves that rejected upstream bodies are closed without a read.
type closeProbe struct {
	closed atomic.Int32
	read   atomic.Int32
	text   string
}

func (b *closeProbe) Read(p []byte) (int, error) {
	b.read.Add(1)
	return strings.NewReader(b.text).Read(p)
}
func (b *closeProbe) Close() error { b.closed.Add(1); return nil }

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestYtdlpRepeatedInvalidStatusReturnsSafe502(t *testing.T) {
	const detail = "upstream-secret-credential"
	bodies := []*closeProbe{{text: detail}, {text: detail}}
	var hits atomic.Int32
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		n := int(hits.Add(1))
		if req.Header.Get("Range") != "bytes=30-" {
			t.Errorf("retry Range = %q", req.Header.Get("Range"))
		}
		if n <= len(bodies) {
			want := []string{"https://media.example/stale?private=1", "https://media.example/again?private=2"}[n-1]
			if req.URL.String() != want {
				t.Errorf("upstream GET %d fetched %s, want %s", n, req.URL, want)
			}
		}
		if n == 3 || n == 4 {
			if req.URL.String() != "https://media.example/recovered" {
				t.Errorf("recovery fetched %s", req.URL)
			}
			return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"audio/webm"}}, Body: io.NopCloser(strings.NewReader("audio")), ContentLength: 5, Request: req}, nil
		}
		if n > len(bodies) {
			return nil, fmt.Errorf("unexpected upstream hit %d", n)
		}
		return &http.Response{StatusCode: []int{403, 503}[n-1], Body: bodies[n-1],
			Header:        http.Header{"Content-Type": {"text/html; " + detail}, "Content-Range": {"bytes 0-1/999"}, "Accept-Ranges": {detail}},
			ContentLength: 999, Request: req}, nil
	})}
	runner := &sequenceRunner{urls: []string{"https://media.example/stale?private=1", "https://media.example/again?private=2", "https://media.example/recovered"}}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	req.Header.Set("Range", "bytes=30-")
	rec := httptest.NewRecorder()
	err := ServeStream(rec, req, backend, track)
	if !errors.Is(err, ErrService) || rec.Code != http.StatusBadGateway || rec.Body.String() != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` {
		t.Fatalf("ServeStream = status %d body %q err %v", rec.Code, rec.Body.String(), err)
	}
	if hits.Load() != 2 || runner.calls.Load() != 2 {
		t.Fatalf("hits = %d, searches = %d; want 2 each", hits.Load(), runner.calls.Load())
	}
	for i, body := range bodies {
		if body.closed.Load() != 1 || body.read.Load() != 0 {
			t.Errorf("rejected body %d: closed %d, read %d", i, body.closed.Load(), body.read.Load())
		}
	}
	for _, h := range []string{"Content-Range", "Accept-Ranges", "Content-Length"} {
		if rec.Header().Get(h) != "" {
			t.Errorf("upstream header %s leaked: %q", h, rec.Header().Get(h))
		}
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Errorf("error Content-Type = %q, want application/json", got)
	}
	if strings.Contains(rec.Body.String(), detail) || strings.Contains(rec.Header().Get("Content-Type"), detail) {
		t.Fatal("upstream error detail leaked")
	}
	// A later public request must look up a new URL, not revisit either rejected URL.
	recovered := serveProxy(t, backend, track, "bytes=30-")
	if recovered.Code != 200 || recovered.Body.String() != "audio" || hits.Load() != 3 || runner.calls.Load() != 3 {
		t.Fatalf("recovery: status %d, body %q, hits %d, searches %d", recovered.Code, recovered.Body.String(), hits.Load(), runner.calls.Load())
	}
	reused := serveProxy(t, backend, track, "bytes=30-")
	if reused.Code != 200 || reused.Body.String() != "audio" || hits.Load() != 4 || runner.calls.Load() != 3 {
		t.Fatalf("reuse: status %d, body %q, hits %d, searches %d; want cached recovery", reused.Code, reused.Body.String(), hits.Load(), runner.calls.Load())
	}
}

func TestYtdlpAcceptedStatusesDoNotRefresh(t *testing.T) {
	for _, tc := range []struct {
		name                            string
		status                          int
		rangeHeader, contentRange, body string
	}{
		{"complete", 200, "", "", "audio"},
		{"partial", 206, "bytes=1-2", "bytes 1-2/5", "ud"},
		{"unsatisfiable", 416, "bytes=99-", "bytes */5", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var hits atomic.Int32
			client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
				hits.Add(1)
				if req.URL.String() != "https://media.example/audio" {
					t.Errorf("media GET fetched %s, want https://media.example/audio", req.URL)
				}
				if req.Header.Get("Range") != tc.rangeHeader {
					t.Errorf("Range = %q, want %q", req.Header.Get("Range"), tc.rangeHeader)
				}
				return &http.Response{StatusCode: tc.status, Header: http.Header{"Content-Type": {"audio/webm"}, "Content-Range": {tc.contentRange}, "Accept-Ranges": {"bytes"}},
					ContentLength: int64(len(tc.body)), Body: io.NopCloser(strings.NewReader(tc.body)), Request: req}, nil
			})}
			runner := &sequenceRunner{urls: []string{"https://media.example/audio"}}
			backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
			track := testTrack()
			rec := serveProxy(t, backend, track, tc.rangeHeader)
			if rec.Code != tc.status || rec.Body.String() != tc.body || rec.Header().Get("Content-Range") != tc.contentRange || rec.Header().Get("Accept-Ranges") != "bytes" || rec.Header().Get("Content-Type") != "audio/webm" || rec.Header().Get("Content-Length") != fmt.Sprint(len(tc.body)) {
				t.Fatalf("status %d, body %q, headers %v", rec.Code, rec.Body.String(), rec.Header())
			}
			second := serveProxy(t, backend, track, tc.rangeHeader)
			if second.Code != tc.status || second.Body.String() != tc.body || hits.Load() != 2 || runner.calls.Load() != 1 {
				t.Fatalf("reuse: status %d, body %q, hits %d, searches %d", second.Code, second.Body.String(), hits.Load(), runner.calls.Load())
			}
		})
	}
}

func TestYtdlpInvalidStatusRetriesWithoutCache(t *testing.T) {
	var hits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.URL.Path == "/old" {
			w.WriteHeader(404)
			_, _ = io.WriteString(w, "bad")
			return
		}
		w.Header().Set("Content-Type", "audio/webm")
		_, _ = io.WriteString(w, "audio")
	}))
	defer upstream.Close()
	runner := &sequenceRunner{urls: []string{upstream.URL + "/old", upstream.URL + "/new", upstream.URL + "/new"}}
	backend := &YTDLP{Runner: runner, Client: upstream.Client(), CacheTTL: -1}
	rec := serveProxy(t, backend, testTrack(), "")
	if rec.Code != 200 || rec.Body.String() != "audio" || hits.Load() != 2 || runner.calls.Load() != 2 {
		t.Fatalf("status %d body %q hits %d searches %d", rec.Code, rec.Body.String(), hits.Load(), runner.calls.Load())
	}
	second := serveProxy(t, backend, testTrack(), "")
	if second.Code != 200 || second.Body.String() != "audio" || hits.Load() != 3 || runner.calls.Load() != 3 {
		t.Fatalf("uncached next request: status %d body %q hits %d searches %d", second.Code, second.Body.String(), hits.Load(), runner.calls.Load())
	}
}

func TestYtdlpRefreshOverloadKeepsPublic503(t *testing.T) {
	limiter := ytdlpcap.New(1, 0)
	runner := &sequenceRunner{urls: []string{"https://media.example/old", "https://media.example/new"}}
	backend := &YTDLP{Runner: runner, CacheTTL: time.Minute, Limiter: limiter}
	track := testTrack()
	var hits atomic.Int32
	var reject atomic.Bool
	backend.Client = &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		n := hits.Add(1)
		want := "https://media.example/old"
		if n == 3 {
			want = "https://media.example/new"
		}
		if req.URL.String() != want {
			t.Errorf("upstream GET %d fetched %s, want %s", n, req.URL, want)
		}
		status := http.StatusOK
		body := "audio"
		if reject.Load() && req.URL.Path == "/old" {
			status, body = http.StatusForbidden, "bad"
		}
		return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": {"audio/webm"}}, Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body)), Request: req}, nil
	})}
	if first := serveProxy(t, backend, track, ""); first.Code != 200 || first.Body.String() != "audio" || runner.calls.Load() != 1 {
		t.Fatalf("prime: status %d body %q lookups %d", first.Code, first.Body.String(), runner.calls.Load())
	}
	reject.Store(true)
	if err := limiter.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	held := true
	defer func() {
		if held {
			limiter.Release()
		}
	}()
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	rec := httptest.NewRecorder()
	err := ServeStream(rec, req, backend, track)
	if !errors.Is(err, ytdlpcap.ErrOverloaded) || rec.Code != 503 || rec.Header().Get("Retry-After") != RetryAfterOverloaded || rec.Body.String() != `{"error":"overloaded","message":"stream resolver is temporarily overloaded"}` || hits.Load() != 2 || runner.calls.Load() != 1 {
		t.Fatalf("status %d, body %q, retry-after %q, hits %d, lookups %d, err %v", rec.Code, rec.Body.String(), rec.Header().Get("Retry-After"), hits.Load(), runner.calls.Load(), err)
	}
	limiter.Release()
	held = false
	reject.Store(false)
	if next := serveProxy(t, backend, track, ""); next.Code != 200 || next.Body.String() != "audio" || hits.Load() != 3 || runner.calls.Load() != 2 {
		t.Fatalf("after overload: status %d body %q hits %d lookups %d", next.Code, next.Body.String(), hits.Load(), runner.calls.Load())
	}
}

func TestYtdlpTransportFailureDoesNotRefresh(t *testing.T) {
	var hits atomic.Int32
	runner := &sequenceRunner{urls: []string{"https://media.example/audio"}}
	backend := &YTDLP{Runner: runner, CacheTTL: time.Minute, Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.String() != runner.urls[0] {
			t.Errorf("media URL = %s", req.URL)
		}
		if hits.Add(1) == 1 {
			return nil, errors.New("transport unavailable")
		}
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"audio/webm"}}, Body: io.NopCloser(strings.NewReader("audio")), ContentLength: 5, Request: req}, nil
	})}}
	track := testTrack()
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	rec := httptest.NewRecorder()
	if err := ServeStream(rec, req, backend, track); !errors.Is(err, ErrService) || rec.Code != 502 || rec.Body.String() != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` {
		t.Fatalf("status %d body %q error = %v, want safe 502 and ErrService", rec.Code, rec.Body.String(), err)
	}
	if hits.Load() != 1 || runner.calls.Load() != 1 {
		t.Fatalf("hits %d, searches %d; want 1 each", hits.Load(), runner.calls.Load())
	}
	second := serveProxy(t, backend, track, "")
	if second.Code != 200 || second.Body.String() != "audio" || hits.Load() != 2 || runner.calls.Load() != 1 {
		t.Fatalf("transport recovery: status %d body %q hits %d searches %d", second.Code, second.Body.String(), hits.Load(), runner.calls.Load())
	}
}

type gatedSequenceRunner struct {
	urls             []string
	calls            atomic.Int32
	gateCall         int
	entered, release chan struct{}
}

func (r *gatedSequenceRunner) Search(ctx context.Context, _ string) ([]byte, error) {
	n := int(r.calls.Add(1))
	if n == r.gateCall {
		close(r.entered)
		select {
		case <-r.release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if n > len(r.urls) {
		return nil, fmt.Errorf("unexpected search %d", n)
	}
	return []byte(fmt.Sprintf(`{"url":%q}`, r.urls[n-1])), nil
}

func TestYtdlpConcurrentInvalidStatusesShareRefresh(t *testing.T) {
	firstTwo := make(chan struct{}, 2)
	releaseFirstTwo := make(chan struct{})
	refreshEntered, releaseRefresh := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseFirstTwo)
	defer closeIfOpen(releaseRefresh)
	var hits atomic.Int32
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		if req.URL.Path == "/old" {
			firstTwo <- struct{}{}
			<-releaseFirstTwo
		}
		status := 200
		if req.URL.Path == "/old" {
			status = 403
		}
		return &http.Response{StatusCode: status, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("audio")), Request: req}, nil
	})}
	runner := &gatedSequenceRunner{urls: []string{"https://media.example/old", "https://media.example/new"}, gateCall: 2, entered: refreshEntered, release: releaseRefresh}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	if _, err := backend.resolveURL(context.Background(), track); err != nil {
		t.Fatal(err)
	}
	results := make(chan error, 2)
	observed := []*cacheWaitContext{observeCacheWait(context.Background()), observeCacheWait(context.Background())}
	for _, ctx := range observed {
		go func() {
			res, err := backend.Stream(ctx, track, "")
			if err == nil {
				res.Body.Close()
			}
			results <- err
		}()
	}
	awaitDisplaced(t, firstTwo, "first invalid GET")
	awaitDisplaced(t, firstTwo, "second invalid GET")
	close(releaseFirstTwo)
	awaitDisplaced(t, refreshEntered, "shared status lookup")
	for _, ctx := range observed {
		awaitDisplaced(t, ctx.waiting, "joined status refresh")
	}
	close(releaseRefresh)
	for i := 0; i < 2; i++ {
		if err := awaitDisplaced(t, results, "status result"); err != nil {
			t.Fatal(err)
		}
	}
	if runner.calls.Load() != 2 || hits.Load() != 4 {
		t.Fatalf("searches %d, upstream hits %d, want 2 and 4", runner.calls.Load(), hits.Load())
	}
}
