package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// A redirect is the response to the resolved URL, not an acceptable media GET.
// In particular the client must not follow it and consume an unrelated 200.
func TestYtdlpInitialRedirectRefreshesWithoutFollowing(t *testing.T) {
	for _, status := range []int{http.StatusMovedPermanently, http.StatusFound} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			const secret = "redirect-secret"
			const old = "https://media.example/old"
			const fresh = "https://media.example/fresh"
			const leak = "https://media.example/leak?private=1"
			redirectBody := &closeProbe{text: secret}
			var oldHits, freshHits, leakHits atomic.Int32
			client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
				if got := req.Header.Get("Range"); got != "bytes=12-" {
					t.Errorf("Range = %q", got)
				}
				switch req.URL.String() {
				case old:
					oldHits.Add(1)
					return &http.Response{StatusCode: status, Header: http.Header{"Location": {leak}, "Content-Type": {secret}}, Body: redirectBody, Request: req}, nil
				case fresh:
					freshHits.Add(1)
					return &http.Response{StatusCode: http.StatusPartialContent, Header: http.Header{"Content-Type": {"audio/webm"}, "Content-Range": {"bytes 12-16/17"}}, Body: io.NopCloser(strings.NewReader("fresh")), ContentLength: 5, Request: req}, nil
				case leak:
					leakHits.Add(1)
					return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {secret}}, Body: io.NopCloser(strings.NewReader(secret)), Request: req}, nil
				default:
					return nil, fmt.Errorf("unexpected URL %s", req.URL)
				}
			})}

			runner := &sequenceRunner{urls: []string{old, fresh}}
			backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
			rec := serveProxy(t, backend, testTrack(), "bytes=12-")
			if rec.Code != http.StatusPartialContent || rec.Body.String() != "fresh" || rec.Header().Get("Content-Type") != "audio/webm" {
				t.Fatalf("status %d, body %q, headers %v", rec.Code, rec.Body.String(), rec.Header())
			}
			if oldHits.Load() != 1 || freshHits.Load() != 1 || leakHits.Load() != 0 || runner.calls.Load() != 2 {
				t.Fatalf("old %d, fresh %d, leak %d, lookups %d", oldHits.Load(), freshHits.Load(), leakHits.Load(), runner.calls.Load())
			}
			if redirectBody.read.Load() != 0 || redirectBody.closed.Load() != 1 {
				t.Fatalf("redirect body: read %d, closed %d", redirectBody.read.Load(), redirectBody.closed.Load())
			}
			if client.CheckRedirect != nil {
				t.Fatal("injected client's redirect policy was mutated")
			}
		})
	}
}

func TestYtdlpRetryRedirectReturnsRedacted502WithoutFollowing(t *testing.T) {
	const detail = "redirect-secret-credential"
	const old = "https://media.example/old"
	const retry = "https://media.example/retry"
	const leak = "https://media.example/leak?private=1"
	firstBody, redirectBody := &closeProbe{text: detail}, &closeProbe{text: detail}
	var firstHits, retryHits, leakHits atomic.Int32
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.Header.Get("Range") != "bytes=20-" {
			t.Errorf("Range = %q", req.Header.Get("Range"))
		}
		switch req.URL.String() {
		case old:
			firstHits.Add(1)
			return &http.Response{StatusCode: 403, Header: http.Header{"Content-Type": {detail}}, Body: firstBody, Request: req}, nil
		case retry:
			retryHits.Add(1)
			return &http.Response{StatusCode: 302, Header: http.Header{"Location": {leak}, "Content-Type": {detail}, "Content-Range": {detail}, "Accept-Ranges": {detail}}, Body: redirectBody, Request: req}, nil
		case leak:
			leakHits.Add(1)
			return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {detail}}, Body: io.NopCloser(strings.NewReader(detail)), Request: req}, nil
		default:
			return nil, fmt.Errorf("unexpected URL %s", req.URL)
		}
	})}
	runner := &sequenceRunner{urls: []string{old, retry}}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	req.Header.Set("Range", "bytes=20-")
	rec := httptest.NewRecorder()
	err := ServeStream(rec, req, backend, testTrack())
	if !errors.Is(err, ErrService) || rec.Code != http.StatusBadGateway || rec.Body.String() != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` {
		t.Fatalf("ServeStream = status %d body %q err %v", rec.Code, rec.Body.String(), err)
	}
	if firstHits.Load() != 1 || retryHits.Load() != 1 || leakHits.Load() != 0 || runner.calls.Load() != 2 {
		t.Fatalf("first %d, retry %d, leak %d, lookups %d", firstHits.Load(), retryHits.Load(), leakHits.Load(), runner.calls.Load())
	}
	for _, body := range []*closeProbe{firstBody, redirectBody} {
		if body.read.Load() != 0 || body.closed.Load() != 1 {
			t.Errorf("rejected body: read %d, closed %d", body.read.Load(), body.closed.Load())
		}
	}
	if strings.Contains(rec.Body.String(), detail) || strings.Contains(fmt.Sprint(rec.Header()), detail) {
		t.Fatal("upstream detail leaked")
	}
	if backend.cacheRef().GetEntry(cacheKey(testTrack())) != nil {
		t.Fatal("redirect retry remained cached")
	}
}

func TestYtdlpInjectedClientRedirectPolicyUntouched(t *testing.T) {
	const old = "https://media.example/old"
	const fresh = "https://media.example/fresh"
	var redirectCalls atomic.Int32
	check := func(*http.Request, []*http.Request) error { redirectCalls.Add(1); return nil }
	transport := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.String() == old {
			return &http.Response{StatusCode: 302, Header: http.Header{"Location": {fresh}}, Body: io.NopCloser(strings.NewReader("redirect")), Request: req}, nil
		}
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("audio")), Request: req}, nil
	})
	jar := &testRedirectJar{}
	client := &http.Client{Transport: transport, Jar: jar, Timeout: 2 * time.Second, CheckRedirect: check}
	backend := &YTDLP{Runner: &sequenceRunner{urls: []string{old, fresh}}, Client: client, CacheTTL: -1}
	res, err := backend.Stream(context.Background(), testTrack(), "")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if client.CheckRedirect == nil || client.Timeout != 2*time.Second || client.Jar != jar || client.Transport == nil || redirectCalls.Load() != 0 {
		t.Fatal("stream changed injected client or invoked its redirect policy")
	}
	originalResp, err := client.Get(old)
	if err != nil {
		t.Fatal(err)
	}
	originalResp.Body.Close()
	if redirectCalls.Load() != 1 || originalResp.Request.URL.String() != fresh {
		t.Fatal("original client stopped following redirects")
	}
}

type testRedirectJar struct{}

func (*testRedirectJar) SetCookies(*url.URL, []*http.Cookie) {}
func (*testRedirectJar) Cookies(*url.URL) []*http.Cookie     { return nil }
