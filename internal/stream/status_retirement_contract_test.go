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

// qmix#292: unlike the cancellation-aware runner test, this refresh succeeds
// after cancellation AND replacement publication. Its completed cache work
// must not overwrite the replacement or force a fourth lookup.
func TestStatusAbandonedSuccessAfterReplacementIsNotPublished(t *testing.T) {
	const recovered = "https://media.example/recovered"
	badGETs := make(chan struct{}, 1)
	releaseBad, refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseBad); closeIfOpen(releaseRefresh) })
	var reject atomic.Bool
	var hits atomic.Int32
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		switch n {
		case 1:
			return "https://media.example/old", nil
		case 2:
			close(refreshStarted)
			if err := waitCacheGate(releaseRefresh, "cancellation-ignoring status success"); err != nil {
				return "", err
			}
			return "https://media.example/abandoned", nil
		case 3:
			return recovered, nil
		}
		return "", fmt.Errorf("unexpected search %d", n)
	}}
	b := newStatusWaiterBackend(t, recovered, runner, badGETs, releaseBad, &reject, &hits)
	track := testTrack()
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "prime"), "https://media.example/old")
	reject.Store(true)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	observed := observeCacheWait(ctx)
	abandoned := startCacheLookup(b, observed, track)
	awaitDisplaced(t, badGETs, "bad GET")
	closeIfOpen(releaseBad)
	awaitDisplaced(t, refreshStarted, "status refresh")
	awaitDisplaced(t, observed.waiting, "last status waiter admitted")
	completed := cacheFlightDone(t, b, track)
	cancel()
	if got := awaitDisplaced(t, abandoned, "last status waiter canceled"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("abandoned request = %+v, want cancellation", got)
	}
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "published replacement before old success"), recovered)
	closeIfOpen(releaseRefresh)
	awaitDisplaced(t, completed, "cache completion of late abandoned status success")
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "replacement reused after completed old success"), recovered)
	if runner.calls.Load() != 3 || hits.Load() != 4 {
		t.Fatalf("searches %d, GETs %d; want 3 and 4", runner.calls.Load(), hits.Load())
	}
}

// qmix#292: a duplicate GET already holds the rejected generation when its
// caller cancels before status admission. It cannot release a parked survivor's
// publisher; an ordinary miss still joins that publisher and reuses its URL.
func TestStatusCanceledDuplicateKeepsParkedRefresh(t *testing.T) {
	const old = "https://media.example/old"
	const fresh = "https://media.example/fresh"
	duplicateGET, releaseDuplicate := make(chan struct{}), make(chan struct{})
	refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseDuplicate); closeIfOpen(releaseRefresh) })
	var reject atomic.Bool
	var badGETs, hits atomic.Int32
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			close(refreshStarted)
			if err := waitCacheGate(releaseRefresh, "survivor refresh"); err != nil {
				return "", err
			}
			return fresh, nil
		}
		return "", fmt.Errorf("unexpected search %d", n)
	}}
	b := &YTDLP{Runner: runner, CacheTTL: time.Minute,
		Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			hits.Add(1)
			switch req.URL.String() {
			case old:
				if !reject.Load() {
					return statusContractResponse(req, http.StatusOK), nil
				}
				switch badGETs.Add(1) {
				case 1:
					close(duplicateGET)
					if err := waitCacheGate(releaseDuplicate, "canceled duplicate GET"); err != nil {
						return nil, err
					}
				case 2:
				default:
					return nil, errors.New("stale URL exposed during survivor refresh")
				}
				return statusContractResponse(req, http.StatusForbidden), nil
			case fresh:
				return statusContractResponse(req, http.StatusOK), nil
			}
			return nil, fmt.Errorf("unexpected URL %s", req.URL)
		})}}
	track := testTrack()
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "prime"), old)
	reject.Store(true)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	duplicate := startCacheLookup(b, ctx, track)
	awaitDisplaced(t, duplicateGET, "duplicate retaining old generation")
	survivor := startCacheLookup(b, context.Background(), track)
	awaitDisplaced(t, refreshStarted, "parked survivor refresh before duplicate cancellation")
	cancel()
	closeIfOpen(releaseDuplicate)
	if got := awaitDisplaced(t, duplicate, "pre-admission canceled duplicate"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("duplicate = %+v, want cancellation", got)
	}
	observed := observeCacheWait(context.Background())
	ordinary := startCacheLookup(b, observed, track)
	awaitCacheLookupWaiting(t, observed, ordinary, "ordinary request after canceled duplicate")
	if runner.calls.Load() != 2 || hits.Load() != 3 {
		t.Fatalf("parked searches %d, GETs %d; want 2 and 3", runner.calls.Load(), hits.Load())
	}
	closeIfOpen(releaseRefresh)
	requireStatusURL(t, awaitDisplaced(t, survivor, "survivor publication"), fresh)
	requireStatusURL(t, awaitDisplaced(t, ordinary, "ordinary shared fresh result"), fresh)
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "survivor reuse"), fresh)
	if runner.calls.Load() != 2 || hits.Load() != 6 {
		t.Fatalf("searches %d, GETs %d; want 2 and 6", runner.calls.Load(), hits.Load())
	}
}

// qmix#292: invalidation is scoped to its key, including while that key's
// status publisher is parked. The unrelated live entry needs no new search.
func TestStatusInvalidationLeavesUnrelatedKeyReusable(t *testing.T) {
	const old = "https://media.example/old"
	const fresh = "https://media.example/fresh"
	const other = "https://media.example/other"
	refreshStarted, release := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(release) })
	var searches, otherSearches, otherGETs atomic.Int32
	var reject atomic.Bool
	b := &YTDLP{CacheTTL: time.Minute,
		Runner: cacheContractRunner(func(_ context.Context, input string) ([]byte, error) {
			switch input {
			case "ytsearch:Artist - first":
				switch searches.Add(1) {
				case 1:
					return cacheFixture(old), nil
				case 2:
					close(refreshStarted)
					if err := waitCacheGate(release, "key-scoped refresh"); err != nil {
						return nil, err
					}
					return cacheFixture(fresh), nil
				}
			case "ytsearch:Artist - second":
				if otherSearches.Add(1) == 1 {
					return cacheFixture(other), nil
				}
			}
			return nil, fmt.Errorf("unexpected search %q", input)
		}),
		Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			switch req.URL.String() {
			case old:
				if reject.Load() {
					return statusContractResponse(req, http.StatusForbidden), nil
				}
			case fresh:
			case other:
				otherGETs.Add(1)
			default:
				return nil, fmt.Errorf("unexpected URL %s", req.URL)
			}
			return statusContractResponse(req, http.StatusOK), nil
		})}}
	first, second := &Track{Artist: "Artist", Title: "first"}, &Track{Artist: "Artist", Title: "second"}
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), first), "target prime"), old)
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), second), "unrelated prime"), other)
	reject.Store(true)
	refresh := startCacheLookup(b, context.Background(), first)
	awaitDisplaced(t, refreshStarted, "target invalidation and parked refresh")
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), second), "unrelated during invalidation"), other)
	closeIfOpen(release)
	requireStatusURL(t, awaitDisplaced(t, refresh, "target refresh result"), fresh)
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), first), "target reuse"), fresh)
	requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), second), "unrelated after invalidation"), other)
	if searches.Load() != 2 || otherSearches.Load() != 1 || otherGETs.Load() != 3 {
		t.Fatalf("target searches %d, unrelated searches %d / GETs %d; want 2, 1 / 3", searches.Load(), otherSearches.Load(), otherGETs.Load())
	}
}
