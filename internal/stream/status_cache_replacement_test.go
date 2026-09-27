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
)

func awaitCacheSwap[T any](t *testing.T, ch <-chan T, event string) T {
	t.Helper()
	select {
	case value := <-ch:
		return value
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", event)
		var zero T
		return zero
	}
}

// A request's status handling belongs to the same cache as its first URL,
// even when SetCache installs a populated, unrelated backend during the GET.
func TestStreamCacheReplacementKeepsSuccessfulRefreshInOriginalCache(t *testing.T) {
	testStreamCacheReplacement(t, false)
}

func TestStreamCacheReplacementKeepsTerminalRejectionInOriginalCache(t *testing.T) {
	testStreamCacheReplacement(t, true)
}

func TestStreamInitiallyDisabledCacheRemainsDisabledAfterReplacement(t *testing.T) {
	track := testTrack()
	key := cacheKey(track)
	c2 := NewCache(time.Minute)
	if _, err := c2.Do(key, func() (interface{}, error) { return "sentinel", nil }); err != nil {
		t.Fatal(err)
	}
	sentinel := c2.GetEntry(key)
	firstGET, releaseGET := make(chan struct{}), make(chan struct{})
	refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseGET)
	defer closeIfOpen(releaseRefresh)
	var hits atomic.Int32
	runner := &gatedSequenceRunner{urls: []string{"https://media.example/old", "https://media.example/fresh"}, gateCall: 2, entered: refreshStarted, release: releaseRefresh}
	backend := &YTDLP{Runner: runner, CacheTTL: -1, Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if hits.Add(1) == 1 {
			close(firstGET)
			<-releaseGET
			return &http.Response{StatusCode: 403, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("bad")), Request: req}, nil
		}
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("audio")), Request: req}, nil
	})}}
	done := make(chan error, 1)
	go func() {
		res, err := backend.Stream(context.Background(), track, "")
		if err == nil {
			res.Body.Close()
		}
		done <- err
	}()
	awaitCacheSwap(t, firstGET, "cache-disabled first GET")
	backend.SetCache(c2)
	close(releaseGET)
	awaitCacheSwap(t, refreshStarted, "cache-disabled retry lookup")
	c2.mu.Lock()
	s := c2.states[key]
	untouched := s != nil && s.entry == sentinel && s.owner == nil && s.ordinary == nil && len(s.refreshes) == 0
	c2.mu.Unlock()
	close(releaseRefresh)
	if err := awaitCacheSwap(t, done, "cache-disabled retry response"); err != nil || !untouched || hits.Load() != 2 || runner.calls.Load() != 2 {
		t.Fatalf("disabled retry error %v, replacement cache untouched %v, GETs %d, lookups %d", err, untouched, hits.Load(), runner.calls.Load())
	}
}

func testStreamCacheReplacement(t *testing.T, terminal bool) {
	t.Helper()
	track := testTrack()
	key := cacheKey(track)
	c1, c2 := NewCache(time.Minute), NewCache(time.Minute)
	if _, err := c1.Do(key, func() (interface{}, error) { return "https://media.example/old", nil }); err != nil {
		t.Fatal(err)
	}
	old := c1.GetEntry(key)
	if _, err := c2.Do(key, func() (interface{}, error) { return "https://media.example/sentinel", nil }); err != nil {
		t.Fatal(err)
	}
	sentinel := c2.GetEntry(key)
	c2.mu.Lock()
	beforeState := c2.states[key]
	beforeEpoch, beforeOwner, beforeNext, beforeLatest := beforeState.epoch, beforeState.owner, c2.next, beforeState.lineage.latest
	c2.mu.Unlock()

	entered := make(chan struct{})
	release := make(chan struct{})
	defer closeIfOpen(release)
	var hits atomic.Int32
	oldBody, retryBody := &closeProbe{text: "old-secret"}, &closeProbe{text: "retry-secret"}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		n := hits.Add(1)
		if n == 1 {
			close(entered)
			<-release
		}
		if n > 2 {
			return nil, fmt.Errorf("unexpected GET %d", n)
		}
		status := http.StatusForbidden
		var body io.ReadCloser = oldBody
		if n == 2 {
			status, body = http.StatusOK, io.NopCloser(strings.NewReader("audio"))
			if terminal {
				status, body = http.StatusServiceUnavailable, retryBody
			}
		}
		return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": {"text/plain; upstream-secret"}}, Body: body, Request: req}, nil
	})}
	runner := &sequenceRunner{urls: []string{"https://media.example/refreshed"}}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	backend.SetCache(c1)
	type outcome struct {
		code int
		body string
		err  error
	}
	done := make(chan outcome, 1)
	go func() {
		rec := httptest.NewRecorder()
		err := ServeStream(rec, httptest.NewRequest(http.MethodGet, "/stream", nil), backend, track)
		done <- outcome{code: rec.Code, body: rec.Body.String(), err: err}
	}()
	awaitCacheSwap(t, entered, "first GET")
	if replaced := backend.SetCache(c2); replaced != c1 {
		t.Fatalf("SetCache replaced %p, want %p", replaced, c1)
	}
	close(release)
	got := awaitCacheSwap(t, done, "stream response")
	if hits.Load() != 2 || runner.calls.Load() != 1 {
		t.Fatalf("GETs %d, status lookups %d; want 2 and 1", hits.Load(), runner.calls.Load())
	}
	if oldBody.closed.Load() != 1 || oldBody.read.Load() != 0 {
		t.Fatalf("first rejected body closed %d read %d", oldBody.closed.Load(), oldBody.read.Load())
	}
	c2.mu.Lock()
	s := c2.states[key]
	untouched := s != nil && s == beforeState && s.entry == sentinel && s.owner == beforeOwner && s.epoch == beforeEpoch && s.lineage.latest == beforeLatest && c2.next == beforeNext && len(s.refreshes) == 0 && s.ordinary == nil
	c2.mu.Unlock()
	if !untouched {
		t.Fatal("replacement cache's sentinel identity, epoch, owner, or lineage was modified")
	}
	if terminal {
		if !errors.Is(got.err, ErrService) || got.code != http.StatusBadGateway || got.body != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` || strings.Contains(got.body, "secret") {
			t.Fatalf("terminal result: status %d, body %q, error %v", got.code, got.body, got.err)
		}
		if retryBody.closed.Load() != 1 || retryBody.read.Load() != 0 {
			t.Fatalf("retry rejected body closed %d read %d", retryBody.closed.Load(), retryBody.read.Load())
		}
		if e := c1.GetEntry(key); e != nil {
			t.Fatalf("terminal rejection left C1 entry %p (%v)", e, e.value)
		}
		if _, err := c1.Do(key, func() (interface{}, error) { return "recovered", nil }); err != nil {
			t.Fatalf("C1 was unavailable after terminal failure: %v", err)
		}
	} else {
		if got.err != nil || got.code != http.StatusOK || got.body != "audio" {
			t.Fatalf("successful retry: status %d, body %q, error %v", got.code, got.body, got.err)
		}
		if e := c1.GetEntry(key); e == nil || e == old || e.value != "https://media.example/refreshed" {
			t.Fatalf("C1 did not replace failed identity: %v", e)
		}
	}
	backend.SetCache(c1)
	want := interface{}("https://media.example/refreshed")
	if terminal {
		want = "recovered"
	}
	if e := c1.GetEntry(key); e == nil || e == old || e.value != want {
		t.Fatalf("reinstalled C1 entry = %v, want %v distinct from old", e, want)
	}
}
