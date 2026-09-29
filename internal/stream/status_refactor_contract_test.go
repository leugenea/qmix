package stream

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync/atomic"
	"testing"
	"time"
)

// qmix#292: an ordinary loader error is not cached; the next request can
// search successfully, and later requests reuse only that successful URL.
func TestOrdinaryLookupErrorDoesNotPoisonCache(t *testing.T) {
	const recovered = "https://media.example/recovered"
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		if n == 1 {
			return "", errors.New("lookup unavailable")
		}
		if n == 2 {
			return recovered, nil
		}
		return "", fmt.Errorf("unexpected lookup %d", n)
	}}
	b := &YTDLP{Runner: runner, CacheTTL: time.Minute, Client: &http.Client{Transport: cacheContractTransport{}}}
	for i := 0; i < 3; i++ {
		got := awaitDisplaced(t, startCacheLookup(b, context.Background(), testTrack()), "ordinary lookup")
		if i == 0 && !errors.Is(got.err, ErrService) {
			t.Fatalf("first lookup = %+v, want failure", got)
		}
		if i > 0 && (got.err != nil || got.url != recovered) {
			t.Fatalf("recovery %d = %+v", i, got)
		}
	}
	if got := runner.calls.Load(); got != 2 {
		t.Fatalf("searches = %d, want failed search plus one reusable success", got)
	}
}

// qmix#292: an expired URL's old GET can fail while an ordinary lookup is
// underway. If the old status refresh fails, the displaced ordinary caller's
// own invalid GET must still retry and its success become cacheable.
func TestDisplacedAttemptCanRecoverAfterOldRefreshFailure(t *testing.T) {
	testDisplacedAttemptRecovery(t, false)
}

// qmix#292: a displaced ordinary attempt keeps its retry entitlement after a
// newer status publication has both completed and expired before its return.
func TestDisplacedAttemptCanRecoverAfterNewerPublicationExpires(t *testing.T) {
	testDisplacedAttemptRecovery(t, true)
}

func testDisplacedAttemptRecovery(t *testing.T, newerExpires bool) {
	t.Helper()
	const (
		old       = "https://media.example/old"
		displaced = "https://media.example/displaced"
		newer     = "https://media.example/newer"
		recovered = "https://media.example/recovered"
	)
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	ordinary, releaseOrdinary := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseOld); closeIfOpen(releaseOrdinary) })
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			close(ordinary)
			if err := waitCacheGate(releaseOrdinary, "displaced lookup"); err != nil {
				return "", err
			}
			return displaced, nil
		case 3:
			if newerExpires {
				return newer, nil
			}
			return "", errors.New("old status lookup failed")
		case 4:
			return recovered, nil
		}
		return "", fmt.Errorf("unexpected lookup %d", n)
	}}
	b := &YTDLP{Runner: runner, CacheTTL: 90 * time.Millisecond,
		Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			switch req.URL.String() {
			case old:
				close(oldGET)
				if err := waitCacheGate(releaseOld, "old GET"); err != nil {
					return nil, err
				}
				return statusContractResponse(req, http.StatusForbidden), nil
			case displaced:
				return statusContractResponse(req, http.StatusForbidden), nil
			case recovered, newer:
				return statusContractResponse(req, http.StatusOK), nil
			}
			return nil, fmt.Errorf("unexpected media URL %s", req.URL)
		})}}
	oldDone := startCacheLookup(b, context.Background(), testTrack())
	awaitDisplaced(t, oldGET, "old media GET")
	timer := time.NewTimer(120 * time.Millisecond)
	defer timer.Stop()
	awaitDisplaced(t, timer.C, "old TTL")
	other := startCacheLookup(b, context.Background(), testTrack())
	awaitDisplaced(t, ordinary, "ordinary lookup before failure")
	closeIfOpen(releaseOld)
	got := awaitDisplaced(t, oldDone, "old status refresh")
	if newerExpires {
		requireStatusURL(t, got, newer)
		expiry := time.NewTimer(120 * time.Millisecond)
		defer expiry.Stop()
		awaitDisplaced(t, expiry.C, "newer publication expires before displaced attempt returns")
	} else if !errors.Is(got.err, ErrService) {
		t.Fatalf("old status = %+v", got)
	}
	closeIfOpen(releaseOrdinary)
	for i := 0; i < 2; i++ {
		done := other
		if i == 1 {
			done = startCacheLookup(b, context.Background(), testTrack())
		}
		got := awaitDisplaced(t, done, "displaced retry and reuse")
		if got.err != nil || got.url != recovered {
			t.Fatalf("retry/reuse = %+v", got)
		}
	}
	if got := runner.calls.Load(); got != 4 {
		t.Fatalf("searches = %d, want 4 with cached recovery", got)
	}
}

// qmix#292: cancellation before the status admission lock is granted must not
// invalidate the still-live URL or attach a post-failure lookup.
func TestCanceledStatusWaitingForLockKeepsCurrentURL(t *testing.T) {
	const old = "https://media.example/old"
	badGET, releaseBad := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseBad) })
	var bad atomic.Bool
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		if n == 1 {
			return old, nil
		}
		return "", fmt.Errorf("unexpected lookup %d", n)
	}}
	b := &YTDLP{Runner: runner, CacheTTL: time.Minute, Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if bad.Load() {
			close(badGET)
			if err := waitCacheGate(releaseBad, "bad media GET"); err != nil {
				return nil, err
			}
			return statusContractResponse(req, http.StatusForbidden), nil
		}
		return statusContractResponse(req, http.StatusOK), nil
	})}}
	track := testTrack()
	if got := awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "primed URL"); got.url != old || got.err != nil {
		t.Fatalf("prime = %+v", got)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	bad.Store(true)
	probe := &cacheAdmissionContext{Context: ctx, goroutine: make(chan string, 1)}
	failed := startCacheLookup(b, probe, track)
	awaitDisplaced(t, badGET, "failed media GET")
	goroutine := awaitDisplaced(t, probe.goroutine, "request identity at initial admission")
	cache := b.cacheRef()
	var held atomic.Bool
	unlock := func() {
		if held.Swap(false) {
			cache.mu.Unlock()
		}
	}
	t.Cleanup(unlock)
	cache.mu.Lock()
	held.Store(true)
	closeIfOpen(releaseBad)
	awaitCacheAdmissionQueued(t, goroutine)
	cancel()
	unlock()
	if got := awaitDisplaced(t, failed, "canceled status admission"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("canceled request = %+v", got)
	}
	bad.Store(false)
	if got := awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "unchanged cached URL"); got.url != old || got.err != nil {
		t.Fatalf("cached URL after cancellation = %+v", got)
	}
	if got := runner.calls.Load(); got != 1 {
		t.Fatalf("searches = %d, want original only", got)
	}
}
