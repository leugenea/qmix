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

func TestCachePreCanceledStatusRefreshReleasesReservation(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	attempted := c.GetEntry("track")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var canceledLookupCalls atomic.Int32
	if _, err := c.DoContextRejected(ctx, "track", attempted, func(context.Context) (interface{}, error) {
		canceledLookupCalls.Add(1)
		return nil, errors.New("canceled caller started lookup")
	}); !errors.Is(err, context.Canceled) {
		t.Fatalf("refresh error = %v", err)
	}
	if canceledLookupCalls.Load() != 0 {
		t.Fatalf("canceled caller started %d lookups", canceledLookupCalls.Load())
	}
	c.mu.Lock()
	if s := c.states["track"]; s == nil || s.owner != nil || s.entry != attempted {
		c.mu.Unlock()
		t.Fatal("pre-canceled rejection mutated ownership or the cached entry")
	}
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	if _, err := c.Do("track", func() (interface{}, error) { return "recovered", nil }); err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); !ok || got != "recovered" {
		t.Fatalf("pre-canceled refresh stranded owner: %v, %v", got, ok)
	}
}

// qmix#288: the second invalid media status ends the retry budget, then a
// later public request can resolve, publish, and reuse a third URL.
func TestSecondInvalidStatusLeavesKeyAvailableForOrdinaryRecovery(t *testing.T) {
	const old = "https://media.example/old"
	const retry = "https://media.example/retry"
	const recovered = "https://media.example/recovered"
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			return retry, nil
		case 3:
			return recovered, nil
		default:
			return "", fmt.Errorf("unexpected search %d", n)
		}
	}}
	var hits atomic.Int32
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		switch req.URL.String() {
		case old, retry:
			return statusContractResponse(req, http.StatusForbidden), nil
		case recovered:
			return statusContractResponse(req, http.StatusOK), nil
		default:
			return nil, fmt.Errorf("unexpected media URL %s", req.URL)
		}
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	first := serveProxy(t, backend, track, "")
	if first.Code != http.StatusBadGateway || first.Body.String() != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` {
		t.Fatalf("second invalid status = %d %q; want safe 502", first.Code, first.Body.String())
	}
	for i := 0; i < 2; i++ {
		got := serveProxy(t, backend, track, "")
		if got.Code != http.StatusOK || got.Body.String() != recovered {
			t.Fatalf("recovery %d = %d %q; want recovered URL", i, got.Code, got.Body.String())
		}
	}
	if runner.calls.Load() != 3 || hits.Load() != 4 {
		t.Fatalf("searches %d, media GETs %d; want 3 and 4", runner.calls.Load(), hits.Load())
	}
}
