package stream

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// qmix#270/#292: two ordinary misses share a lookup displaced by an old
// GET's failure. Their invalid statuses straddle the old refresh failure.
// Neither a late join nor a displaced result may strand the key uncacheable.
func TestOwnerReservationReleasedAfterJoiningOlderRefresh(t *testing.T) {
	const (
		old       = "https://media.example/old"
		transient = "https://media.example/transient"
		refresh   = "https://media.example/refresh"
		recovered = "https://media.example/recovered"
	)
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	ordinary, releaseOrdinary := make(chan struct{}), make(chan struct{})
	oldRefresh, releaseOldRefresh := make(chan struct{}), make(chan struct{})
	trGET := make(chan struct{}, 2)
	releaseA, releaseB := make(chan struct{}), make(chan struct{})
	newRefresh, releaseNewRefresh := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() {
		for _, gate := range []chan struct{}{releaseOld, releaseOrdinary, releaseOldRefresh, releaseA, releaseB, releaseNewRefresh} {
			closeIfOpen(gate)
		}
	})
	var transientHits atomic.Int32
	statusWait := &phaseWaitContext{waiting: make(chan struct{})}
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			close(ordinary)
			if err := waitCacheGate(releaseOrdinary, "ordinary lookup release"); err != nil {
				return "", err
			}
			return transient, nil
		case 3:
			close(oldRefresh)
			if err := waitCacheGate(releaseOldRefresh, "old refresh release"); err != nil {
				return "", err
			}
			return "", errors.New("old refresh failed")
		case 4:
			close(newRefresh)
			if err := waitCacheGate(releaseNewRefresh, "new refresh release"); err != nil {
				return "", err
			}
			return refresh, nil
		case 5:
			return recovered, nil
		}
		return "", fmt.Errorf("unexpected lookup %d", n)
	}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		switch req.URL.String() {
		case old:
			close(oldGET)
			if err := waitCacheGate(releaseOld, "old media release"); err != nil {
				return nil, err
			}
			return statusContractResponse(req, http.StatusForbidden), nil
		case transient:
			transientHits.Add(1)
			trGET <- struct{}{}
			gate := releaseA
			isB := req.Context() == statusWait
			if isB {
				gate = releaseB
			}
			if err := waitCacheGate(gate, "transient media release"); err != nil {
				return nil, err
			}
			if isB {
				statusWait.phase.Store(true)
			}
			return statusContractResponse(req, http.StatusForbidden), nil
		case refresh, recovered:
			return statusContractResponse(req, http.StatusOK), nil
		}
		return nil, fmt.Errorf("unexpected media URL %s", req.URL)
	})}
	b := &YTDLP{Runner: runner, Client: client, CacheTTL: 100 * time.Millisecond}
	track := testTrack()
	first := startCacheLookup(b, context.Background(), track)
	awaitDisplaced(t, oldGET, "old GET")
	// Expire through the configured public TTL while the GET remains parked.
	timer := time.NewTimer(130 * time.Millisecond)
	defer timer.Stop()
	awaitDisplaced(t, timer.C, "old cache TTL")
	a, second := startCacheLookup(b, context.Background(), track), observeCacheWait(context.Background())
	awaitDisplaced(t, ordinary, "ordinary flight")
	statusWait.Context = second
	bb := startCacheLookup(b, statusWait, track)
	awaitDisplaced(t, second.waiting, "second ordinary waiter admitted")
	closeIfOpen(releaseOld)
	awaitDisplaced(t, oldRefresh, "old status refresh")
	closeIfOpen(releaseOrdinary)
	awaitDisplaced(t, trGET, "first transient GET")
	awaitDisplaced(t, trGET, "second transient GET")
	closeIfOpen(releaseA)
	awaitDisplaced(t, newRefresh, "transient status refresh")
	closeIfOpen(releaseOldRefresh)
	if got := awaitDisplaced(t, first, "old failed refresh"); !errors.Is(got.err, ErrService) {
		t.Fatalf("old request = %+v, want service failure", got)
	}
	closeIfOpen(releaseB)
	// The second reject may join the existing lookup, but cannot strand future
	// publication when that lookup completes.
	awaitDisplaced(t, statusWait.waiting, "second rejected status attached to refresh")
	closeIfOpen(releaseNewRefresh)
	for i, done := range []<-chan cacheLookup{a, bb} {
		got := awaitDisplaced(t, done, "transient request")
		if got.err != nil || got.url != refresh {
			t.Fatalf("transient request %d = %+v, want refresh", i, got)
		}
	}
	for i := 0; i < 3; i++ {
		got := awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "recovery and reuse")
		if got.err != nil || got.url != recovered {
			t.Fatalf("recovery %d = %+v, want cached recovered URL", i, got)
		}
	}
	if got := runner.calls.Load(); got != 5 {
		t.Fatalf("lookups = %d, want five including one recovered lookup", got)
	}
}

type phaseWaitContext struct {
	context.Context
	phase   atomic.Bool
	waiting chan struct{}
	once    sync.Once
}

func (c *phaseWaitContext) Done() <-chan struct{} {
	if c.phase.Load() {
		c.once.Do(func() { close(c.waiting) })
	}
	return c.Context.Done()
}
