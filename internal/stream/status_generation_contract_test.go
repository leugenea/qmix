package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// qmix#289: a late failure from B's old GET must still retry, but cannot
// invalidate A's newer publication, even if yt-dlp returns identical URL text.
func TestStatusLateFailurePreservesPublishedGeneration(t *testing.T) {
	for _, sameURL := range []bool{false, true} {
		name := "different_URL"
		if sameURL {
			name = "same_URL_new_generation"
		}
		t.Run(name, func(t *testing.T) { testLateFailurePublication(t, sameURL) })
	}
}

func testLateFailurePublication(t *testing.T, sameURL bool) {
	const old = "https://media.example/old"
	newer, retry := "https://media.example/new", "https://media.example/obsolete-retry"
	if sameURL {
		newer, retry = old, old
	}
	bEntered, releaseB := make(chan struct{}), make(chan struct{})
	retryEntered, releaseRetry := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseB); closeIfOpen(releaseRetry) })
	var promoted atomic.Bool
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			promoted.Store(true)
			return newer, nil
		case 3:
			close(retryEntered)
			select {
			case <-releaseRetry:
				return retry, nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		return "", fmt.Errorf("unexpected search %d", n)
	}}
	var mu sync.Mutex
	var gets []string
	bad := []*closeProbe{{text: "old B rejected"}, {text: "old A rejected"}, {text: "B retry rejected"}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		identity := req.URL.String() + " " + req.Header.Get("Range")
		mu.Lock()
		gets = append(gets, identity)
		mu.Unlock()
		switch req.Header.Get("Range") {
		case "bytes=3-4":
			if !promoted.Load() {
				close(bEntered)
				if err := waitCacheGate(releaseB, "old B GET"); err != nil {
					return nil, err
				}
				return statusContractBad(req, bad[0]), nil
			}
			return statusContractBad(req, bad[2]), nil
		case "bytes=1-2":
			if !promoted.Load() {
				return statusContractBad(req, bad[1]), nil
			}
			return statusContractMedia(t, req, http.StatusPartialContent, "12"), nil
		case "bytes=99-":
			return statusContractMedia(t, req, http.StatusRequestedRangeNotSatisfiable, ""), nil
		case "":
			return statusContractMedia(t, req, http.StatusOK, req.URL.String()), nil
		}
		return nil, fmt.Errorf("unexpected Range %q", req.Header.Get("Range"))
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	bDone := make(chan proxyOutcome, 1)
	go func() { bDone <- runStatusProxy(backend, track, "bytes=3-4") }()
	awaitDisplaced(t, bEntered, "B's old GET")
	a := runStatusProxy(backend, track, "bytes=1-2")
	requireProxyOutcome(t, a, http.StatusPartialContent, "12", "bytes 1-2/5")
	closeIfOpen(releaseB)
	awaitDisplaced(t, retryEntered, "B's independent late-failure lookup")
	midDone := make(chan proxyOutcome, 1)
	go func() { midDone <- runStatusProxy(backend, track, "bytes=99-") }()
	mid := awaitDisplaced(t, midDone, "newer generation while old retry blocked")
	requireProxyOutcome(t, mid, http.StatusRequestedRangeNotSatisfiable, "", "bytes */5")
	closeIfOpen(releaseRetry)
	b := awaitDisplaced(t, bDone, "B's terminal retry")
	requireProxyFailure(t, b)
	last := runStatusProxy(backend, track, "")
	requireProxyOutcome(t, last, http.StatusOK, newer, "")
	want := []string{old + " bytes=3-4", old + " bytes=1-2", newer + " bytes=1-2", newer + " bytes=99-", retry + " bytes=3-4", newer + " "}
	mu.Lock()
	observed := append([]string(nil), gets...)
	mu.Unlock()
	requireStatusCalls(t, observed, want, runner.calls.Load(), 3)
	requireRejectedBodies(t, bad)
}

type proxyOutcome struct {
	code         int
	body         string
	contentRange string
	err          error
}

func requireProxyOutcome(t *testing.T, got proxyOutcome, status int, body, contentRange string) {
	t.Helper()
	if got.err != nil || got.code != status || got.body != body || got.contentRange != contentRange {
		t.Fatalf("proxy = %+v, want status %d body %q content-range %q", got, status, body, contentRange)
	}
}

func requireProxyFailure(t *testing.T, got proxyOutcome) {
	t.Helper()
	if !errors.Is(got.err, ErrService) || got.code != http.StatusBadGateway || got.body != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` {
		t.Fatalf("failed retry = %+v, want safe 502", got)
	}
}

func requireStatusCalls(t *testing.T, got, want []string, searches, expected int32) {
	t.Helper()
	if !reflect.DeepEqual(got, want) || searches != expected {
		t.Fatalf("GET identities = %q, want %q; searches %d, want %d", got, want, searches, expected)
	}
}

func requireRejectedBodies(t *testing.T, bodies []*closeProbe) {
	t.Helper()
	for i, body := range bodies {
		if body.closed.Load() != 1 || body.read.Load() != 0 {
			t.Errorf("rejected body %d closed %d read %d", i, body.closed.Load(), body.read.Load())
		}
	}
}

func runStatusProxy(backend *YTDLP, track *Track, rangeHeader string) proxyOutcome {
	req := httptest.NewRequest(http.MethodGet, "/stream", nil)
	req.Header.Set("Range", rangeHeader)
	rec := httptest.NewRecorder()
	err := ServeStream(rec, req, backend, track)
	return proxyOutcome{code: rec.Code, body: rec.Body.String(), contentRange: rec.Header().Get("Content-Range"), err: err}
}

func statusContractBad(req *http.Request, body *closeProbe) *http.Response {
	return &http.Response{StatusCode: http.StatusForbidden, Header: http.Header{"Content-Type": {"text/plain"}}, Body: body, Request: req}
}

func statusContractMedia(t *testing.T, req *http.Request, status int, body string) *http.Response {
	header := http.Header{"Content-Type": {"audio/webm"}, "Accept-Ranges": {"bytes"}}
	if status == http.StatusPartialContent {
		header.Set("Content-Range", "bytes 1-2/5")
	} else if status == http.StatusRequestedRangeNotSatisfiable {
		header.Set("Content-Range", "bytes */5")
	}
	resp := &http.Response{StatusCode: status, Header: header, Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body)), Request: req}
	if status == http.StatusPartialContent {
		var start, end, total int64
		n, err := fmt.Sscanf(header.Get("Content-Range"), "bytes %d-%d/%d", &start, &end, &total)
		if err != nil || n != 3 || start < 0 || end < start || end >= total || resp.ContentLength != end-start+1 {
			t.Errorf("invalid 206 fixture: Content-Range %q, body length %d", header.Get("Content-Range"), resp.ContentLength)
		}
	}
	return resp
}

// qmix#289: B's ordinary lookup starts after the old URL expires, but A's
// rejected GET publishes a newer URL before B's old lookup returns. B must
// serve its own attempt and retry its bad GET without publishing over A.
func TestStatusDisplacedOrdinaryLoadKeepsNewerPublication(t *testing.T) {
	const old = "https://media.example/old"
	const displaced = "https://media.example/displaced"
	const newer = "https://media.example/new"
	const localRetry = "https://media.example/local-retry"
	const rangeHeader = "bytes=1-2"
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	ordinary, releaseOrdinary := make(chan struct{}), make(chan struct{})
	retry, releaseRetry := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseOld); closeIfOpen(releaseOrdinary); closeIfOpen(releaseRetry) })
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			close(ordinary)
			select {
			case <-releaseOrdinary:
				return displaced, nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		case 3:
			return newer, nil
		case 4:
			close(retry)
			select {
			case <-releaseRetry:
				return localRetry, nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		return "", fmt.Errorf("unexpected lookup %d", n)
	}}
	var mu sync.Mutex
	var gets []string
	bad := []*closeProbe{{text: "old rejected"}, {text: "displaced rejected"}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		mu.Lock()
		gets = append(gets, req.URL.String()+" "+req.Header.Get("Range"))
		mu.Unlock()
		if req.URL.String() == old {
			close(oldGET)
			if err := waitCacheGate(releaseOld, "old displaced GET"); err != nil {
				return nil, err
			}
			return statusContractBad(req, bad[0]), nil
		}
		if req.URL.String() == displaced {
			return statusContractBad(req, bad[1]), nil
		}
		if req.URL.String() != newer && req.URL.String() != localRetry {
			return nil, fmt.Errorf("unexpected media URL %s", req.URL)
		}
		body := "nw"
		if req.URL.String() == localRetry {
			body = "lr"
		}
		return statusContractMedia(t, req, http.StatusPartialContent, body), nil
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: 500 * time.Millisecond}
	track := testTrack()
	aDone := make(chan proxyOutcome, 1)
	go func() { aDone <- runStatusProxy(backend, track, rangeHeader) }()
	awaitDisplaced(t, oldGET, "A's old GET")
	// Wait for the configured public TTL to elapse, rather than modifying the
	// cache entry's private expiry. A's GET remains parked throughout.
	timer := time.NewTimer(600 * time.Millisecond)
	defer timer.Stop()
	awaitDisplaced(t, timer.C, "old URL TTL")
	bDone := make(chan proxyOutcome, 1)
	go func() { bDone <- runStatusProxy(backend, track, rangeHeader) }()
	awaitDisplaced(t, ordinary, "B's ordinary lookup")
	closeIfOpen(releaseOld)
	a := awaitDisplaced(t, aDone, "A's published refresh")
	requireProxyOutcome(t, a, http.StatusPartialContent, "nw", "bytes 1-2/5")
	closeIfOpen(releaseOrdinary)
	awaitDisplaced(t, retry, "B's own post-failure lookup")
	midDone := make(chan proxyOutcome, 1)
	go func() { midDone <- runStatusProxy(backend, track, rangeHeader) }()
	mid := awaitDisplaced(t, midDone, "newer generation during displaced retry")
	requireProxyOutcome(t, mid, http.StatusPartialContent, "nw", "bytes 1-2/5")
	closeIfOpen(releaseRetry)
	b := awaitDisplaced(t, bDone, "B's displaced retry result")
	requireProxyOutcome(t, b, http.StatusPartialContent, "lr", "bytes 1-2/5")
	last := runStatusProxy(backend, track, rangeHeader)
	requireProxyOutcome(t, last, http.StatusPartialContent, "nw", "bytes 1-2/5")
	mu.Lock()
	observed := append([]string(nil), gets...)
	mu.Unlock()
	want := []string{old + " " + rangeHeader, newer + " " + rangeHeader, displaced + " " + rangeHeader, newer + " " + rangeHeader, localRetry + " " + rangeHeader, newer + " " + rangeHeader}
	requireStatusCalls(t, observed, want, runner.calls.Load(), 4)
	requireRejectedBodies(t, bad)
}
