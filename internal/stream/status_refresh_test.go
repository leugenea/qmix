package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
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
		if n > len(bodies) {
			return nil, fmt.Errorf("unexpected upstream hit %d", n)
		}
		return &http.Response{StatusCode: []int{403, 503}[n-1], Body: bodies[n-1],
			Header:        http.Header{"Content-Type": {"text/html; " + detail}, "Content-Range": {"bytes 0-1/999"}, "Accept-Ranges": {detail}},
			ContentLength: 999, Request: req}, nil
	})}
	runner := &sequenceRunner{urls: []string{"https://media.example/stale?private=1", "https://media.example/again?private=2"}}
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
	if strings.Contains(rec.Body.String(), detail) || strings.Contains(rec.Header().Get("Content-Type"), detail) {
		t.Fatal("upstream error detail leaked")
	}
	if _, ok := backend.cacheRef().Get(cacheKey(track)); ok {
		t.Fatal("second rejected URL remains cached")
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
			if rec.Code != tc.status || rec.Body.String() != tc.body || rec.Header().Get("Content-Range") != tc.contentRange || rec.Header().Get("Accept-Ranges") != "bytes" || rec.Header().Get("Content-Type") != "audio/webm" {
				t.Fatalf("status %d, body %q, headers %v", rec.Code, rec.Body.String(), rec.Header())
			}
			if hits.Load() != 1 || runner.calls.Load() != 1 {
				t.Fatalf("hits %d, searches %d; want 1", hits.Load(), runner.calls.Load())
			}
			if url, ok := backend.cacheRef().Get(cacheKey(track)); !ok || url != "https://media.example/audio" {
				t.Fatalf("cached URL = %v, %v", url, ok)
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
	runner := &sequenceRunner{urls: []string{upstream.URL + "/old", upstream.URL + "/new"}}
	backend := &YTDLP{Runner: runner, Client: upstream.Client(), CacheTTL: -1}
	rec := serveProxy(t, backend, testTrack(), "")
	if rec.Code != 200 || rec.Body.String() != "audio" || hits.Load() != 2 || runner.calls.Load() != 2 || backend.cacheRef() != nil {
		t.Fatalf("status %d body %q hits %d searches %d", rec.Code, rec.Body.String(), hits.Load(), runner.calls.Load())
	}
}

func TestYtdlpRefreshOverloadKeepsPublic503(t *testing.T) {
	limiter := ytdlpcap.New(1, 0)
	backend := &YTDLP{CacheTTL: time.Minute, Limiter: limiter}
	track := testTrack()
	cache := backend.cacheRef()
	if _, err := cache.Do(cacheKey(track), func() (interface{}, error) { return "https://media.example/old", nil }); err != nil {
		t.Fatal(err)
	}
	var hits atomic.Int32
	backend.Client = &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		return &http.Response{StatusCode: 403, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("bad")), Request: req}, nil
	})}
	if err := limiter.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer limiter.Release()
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	rec := httptest.NewRecorder()
	err := ServeStream(rec, req, backend, track)
	if !errors.Is(err, ytdlpcap.ErrOverloaded) || rec.Code != 503 || rec.Header().Get("Retry-After") != RetryAfterOverloaded || hits.Load() != 1 {
		t.Fatalf("status %d, retry-after %q, hits %d, err %v", rec.Code, rec.Header().Get("Retry-After"), hits.Load(), err)
	}
}

func TestYtdlpTransportFailureDoesNotRefresh(t *testing.T) {
	var hits atomic.Int32
	runner := &sequenceRunner{urls: []string{"https://media.example/audio"}}
	backend := &YTDLP{Runner: runner, CacheTTL: time.Minute, Client: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		hits.Add(1)
		return nil, errors.New("transport unavailable")
	})}}
	track := testTrack()
	if _, err := backend.Stream(context.Background(), track, ""); !errors.Is(err, ErrService) {
		t.Fatalf("error = %v, want ErrService", err)
	}
	if hits.Load() != 1 || runner.calls.Load() != 1 {
		t.Fatalf("hits %d, searches %d; want 1 each", hits.Load(), runner.calls.Load())
	}
	if v, ok := backend.cacheRef().Get(cacheKey(track)); !ok || v != runner.urls[0] {
		t.Fatalf("transport failure evicted URL: %v, %v", v, ok)
	}
}

// The old GET is parked until a newer URL has been cached. Its late failure
// cannot delete that URL; the forced retry is held to inspect the cache.
func TestYtdlpLateInvalidStatusPreservesNewerCacheValue(t *testing.T) {
	oldEntered, releaseOld := make(chan struct{}), make(chan struct{})
	retryEntered, releaseRetry := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOld)
	defer closeIfOpen(releaseRetry)
	runner := &gatedSequenceRunner{urls: []string{"https://media.example/old", "https://media.example/new", "https://media.example/retry"}, gateCall: 3, entered: retryEntered, release: releaseRetry}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.Path == "/old" {
			close(oldEntered)
			<-releaseOld
		}
		return &http.Response{StatusCode: 403, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("bad")), Request: req}, nil
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	result := make(chan error, 1)
	go func() { _, err := backend.Stream(context.Background(), track, ""); result <- err }()
	awaitDisplaced(t, oldEntered, "old GET")
	cache := backend.cacheRef()
	// Expire the original entry so a subsequent ordinary lookup can publish
	// the newer generation without first reserving a status-refresh owner.
	cache.mu.Lock()
	cache.state(cacheKey(track)).entry.expiry = time.Time{}
	cache.mu.Unlock()
	if _, err := backend.resolveURL(context.Background(), track); err != nil {
		t.Fatal(err)
	}
	close(releaseOld)
	awaitDisplaced(t, retryEntered, "late failure's retry lookup")
	if v, ok := cache.Get(cacheKey(track)); !ok || v != "https://media.example/new" {
		t.Fatalf("late failure erased newer URL: %v, %v", v, ok)
	}
	close(releaseRetry)
	if err := awaitDisplaced(t, result, "late failure result"); !errors.Is(err, ErrService) {
		t.Fatalf("retry error = %v", err)
	}
	if runner.calls.Load() != 3 {
		t.Fatalf("searches = %d, want 3", runner.calls.Load())
	}
	if v, ok := cache.Get(cacheKey(track)); !ok || v != "https://media.example/new" {
		t.Fatalf("late failed attempt erased newer cached URL: %v, %v", v, ok)
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
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res, err := backend.Stream(context.Background(), track, "")
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
	waitForCacheWaiters(t, backend.cacheRef(), cacheKey(track), 2)
	close(releaseRefresh)
	wg.Wait()
	for i := 0; i < 2; i++ {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	if runner.calls.Load() != 2 || hits.Load() != 4 {
		t.Fatalf("searches %d, upstream hits %d, want 2 and 4", runner.calls.Load(), hits.Load())
	}
}
