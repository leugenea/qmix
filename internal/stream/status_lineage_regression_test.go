package stream

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// Both GETs retain their entry identities, but there is no parked cache
// flight to keep the state alive after E1 expires.
func TestExpiredStatePruningCannotPromoteOlderFailedGET(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "e0", nil }); err != nil {
		t.Fatal(err)
	}
	e0 := c.GetEntry("track")
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	newGET, releaseNew := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOld)
	defer closeIfOpen(releaseNew)
	oldDone, newDone := make(chan bool, 1), make(chan bool, 1)
	go func() { close(oldGET); <-releaseOld; oldDone <- c.DeleteIfEntry("track", e0) }()
	<-oldGET
	c.mu.Lock()
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	if _, err := c.Do("track", func() (interface{}, error) { return "e1", nil }); err != nil {
		t.Fatal(err)
	}
	e1 := c.GetEntry("track")
	if e0 == e1 || e1.gen <= e0.gen {
		t.Fatal("E1 did not have a distinct, newer identity")
	}
	go func() { close(newGET); <-releaseNew; newDone <- c.DeleteIfEntry("track", e1) }()
	<-newGET
	c.mu.Lock()
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	if got := c.GetEntry("track"); got != nil {
		t.Fatalf("expired E1 still live: %v", got)
	}
	c.mu.Lock()
	_, retained := c.states["track"]
	c.mu.Unlock()
	if retained {
		t.Fatal("idle expired state was not pruned")
	}
	close(releaseOld)
	<-oldDone
	c.mu.Lock()
	owner := c.states["track"]
	if owner != nil && owner.owner == e0 {
		c.mu.Unlock()
		t.Fatal("E0 took E1's publication reservation after pruning")
	}
	c.mu.Unlock()
	close(releaseNew)
	<-newDone
	c.mu.Lock()
	owner = c.states["track"]
	if owner == nil || owner.owner != e1 {
		c.mu.Unlock()
		t.Fatal("newest expired GET could not reserve its refresh")
	}
	c.mu.Unlock()
	if _, err := c.DoContextFresh(context.Background(), "track", e1, func(context.Context) (interface{}, error) {
		return nil, errors.New("refresh failed")
	}); err == nil {
		t.Fatal("expected refresh error")
	}
	c.mu.Lock()
	_, retained = c.states["track"]
	c.mu.Unlock()
	if retained {
		t.Fatal("failed refresh left idle lineage retained")
	}
}

func TestPreCanceledDuplicateCannotReleaseAnotherRefreshOwner(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "e0", nil }); err != nil {
		t.Fatal(err)
	}
	e0 := c.GetEntry("track")
	if !c.DeleteIfEntry("track", e0) {
		t.Fatal("A did not reserve E0")
	}
	// A is suspended in the gap between acquiring ownership and attaching its
	// lookup. B must not release A's still-held reservation in that gap.
	started, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	if c.DeleteIfEntry("track", e0) {
		t.Fatal("duplicate failure acquired a reservation")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var canceledLookupCalls atomic.Int32
	if _, err := c.DoContextFresh(ctx, "track", e0, func(context.Context) (interface{}, error) {
		canceledLookupCalls.Add(1)
		return nil, errors.New("B's canceled lookup ran")
	}); !errors.Is(err, context.Canceled) {
		t.Fatalf("B error = %v", err)
	}
	if canceledLookupCalls.Load() != 0 {
		t.Fatalf("B's canceled lookup ran %d times", canceledLookupCalls.Load())
	}
	c.mu.Lock()
	if s := c.states["track"]; s == nil || s.owner != e0 {
		c.mu.Unlock()
		t.Fatal("B released A's reservation before A attached its flight")
	}
	c.mu.Unlock()
	aDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", e0, func(context.Context) (interface{}, error) {
			close(started)
			<-release
			return "A's refresh", nil
		})
		aDone <- err
	}()
	<-started
	ordinaryRan := make(chan struct{}, 1)
	ordinaryDone := make(chan *entry, 1)
	go func() {
		e, _ := c.DoContextEntry(context.Background(), "track", func(context.Context) (interface{}, error) {
			ordinaryRan <- struct{}{}
			return "rival", nil
		})
		ordinaryDone <- e
	}()
	waitForCacheWaiters(t, c, "track", 2)
	select {
	case <-ordinaryRan:
		t.Fatal("ordinary miss displaced owner while refresh was blocked")
	default:
	}
	close(release)
	if err := <-aDone; err != nil {
		t.Fatal(err)
	}
	if e := <-ordinaryDone; e == nil || e.value != "A's refresh" {
		t.Fatalf("ordinary miss received %v, want A's refresh", e)
	}
	if got := c.GetEntry("track"); got == nil || got.value != "A's refresh" || got == e0 {
		t.Fatalf("A did not publish its refresh: %v", got)
	}
}

func TestOlderUnownedRefreshAfterNewerExpiry(t *testing.T) {
	for _, tc := range []struct {
		name            string
		interveningE2   bool
		wantPublication bool
	}{
		{"E1 expires during lookup", false, true},
		{"E2 publishes and expires during lookup", true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := NewCache(time.Minute)
			if _, err := c.Do("track", func() (interface{}, error) { return "e0", nil }); err != nil {
				t.Fatal(err)
			}
			e0 := c.GetEntry("track")
			c.mu.Lock()
			c.state("track").entry.expiry = time.Time{}
			c.mu.Unlock()
			if _, err := c.Do("track", func() (interface{}, error) { return "e1", nil }); err != nil {
				t.Fatal(err)
			}
			e1 := c.GetEntry("track")
			if c.DeleteIfEntry("track", e0) {
				t.Fatal("E0 removed live E1")
			}
			started, release := make(chan struct{}), make(chan struct{})
			defer closeIfOpen(release)
			finished := make(chan *entry, 1)
			go func() {
				e, _ := c.DoContextFresh(context.Background(), "track", e0, func(context.Context) (interface{}, error) {
					close(started)
					<-release
					return "late refresh", nil
				})
				finished <- e
			}()
			<-started
			c.mu.Lock()
			if s := c.states["track"]; s == nil || s.entry != e1 || s.owner != nil {
				c.mu.Unlock()
				t.Fatal("lookup did not start with live E1 and no owner")
			}
			c.state("track").entry.expiry = time.Time{}
			c.mu.Unlock()
			if tc.interveningE2 {
				if _, err := c.Do("track", func() (interface{}, error) { return "e2", nil }); err != nil {
					t.Fatal(err)
				}
				c.mu.Lock()
				c.state("track").entry.expiry = time.Time{}
				c.mu.Unlock()
			}
			close(release)
			e := <-finished
			if e == nil || e.value != "late refresh" {
				t.Fatalf("lookup result = %v", e)
			}
			got := c.GetEntry("track")
			if tc.wantPublication && (got != e || got.gen <= e1.gen) {
				t.Fatalf("expired E1 blocked E0's successful refresh: got=%v result=%v", got, e)
			}
			if !tc.wantPublication && got != nil {
				t.Fatalf("expired E2 allowed obsolete E0 publication: %v", got)
			}
		})
	}
}

func TestLeasedGETsRetainOnlyOutstandingLineage(t *testing.T) {
	c := NewCache(time.Minute)
	e0, release0, err := c.DoContextEntryLeased(context.Background(), "track", func(context.Context) (interface{}, error) { return "e0", nil })
	if err != nil {
		t.Fatal(err)
	}
	defer release0()
	c.mu.Lock()
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	e1, release1, err := c.DoContextEntryLeased(context.Background(), "track", func(context.Context) (interface{}, error) { return "e1", nil })
	if err != nil {
		t.Fatal(err)
	}
	defer release1()
	c.mu.Lock()
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	if c.GetEntry("track") != nil {
		t.Fatal("E1 remained live after expiration")
	}
	c.mu.Lock()
	s := c.states["track"]
	if s == nil || s.pins != 2 || s.lineage.latest != e1.gen {
		c.mu.Unlock()
		t.Fatal("outstanding GETs did not retain their shared latest generation")
	}
	c.mu.Unlock()
	if c.DeleteIfEntry("track", e0) {
		t.Fatal("older leased GET took the newer generation's claim")
	}
	release0()
	c.mu.Lock()
	s = c.states["track"]
	if s == nil || s.pins != 1 {
		c.mu.Unlock()
		t.Fatal("E1's lease was not retained")
	}
	c.mu.Unlock()
	release1()
	c.mu.Lock()
	_, exists := c.states["track"]
	c.mu.Unlock()
	if exists {
		t.Fatal("idle per-key history was not freed after final GET")
	}
}

func TestAtomicDuplicatePreCancellationCannotStealPublication(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	e0 := c.GetEntry("track")
	started, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	aDone := make(chan *entry, 1)
	go func() {
		e, _ := c.DoContextRejected(context.Background(), "track", e0, func(context.Context) (interface{}, error) {
			close(started)
			<-release
			return "A", nil
		})
		aDone <- e
	}()
	<-started
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var canceledDuplicateCalls atomic.Int32
	if _, err := c.DoContextRejected(ctx, "track", e0, func(context.Context) (interface{}, error) {
		canceledDuplicateCalls.Add(1)
		return nil, errors.New("canceled duplicate began a second lookup")
	}); !errors.Is(err, context.Canceled) {
		t.Fatalf("duplicate error = %v", err)
	}
	if canceledDuplicateCalls.Load() != 0 {
		t.Fatalf("canceled duplicate began %d second lookups", canceledDuplicateCalls.Load())
	}
	c.mu.Lock()
	s := c.states["track"]
	if s == nil || s.owner != e0 || s.refreshes[e0] == nil || s.entry != nil {
		c.mu.Unlock()
		t.Fatal("duplicate cancellation mutated A's owner or published a stale entry")
	}
	c.mu.Unlock()
	ordinaryDone := make(chan *entry, 1)
	go func() {
		e, _ := c.DoContextEntry(context.Background(), "track", func(context.Context) (interface{}, error) {
			t.Error("ordinary miss launched rival lookup")
			return "rival", nil
		})
		ordinaryDone <- e
	}()
	waitForCacheWaiters(t, c, "track", 2)
	close(release)
	if result := <-aDone; result == nil || result.value != "A" || c.GetEntry("track") != result {
		t.Fatalf("A lost publication: %v", result)
	}
	if result := <-ordinaryDone; result == nil || result.value != "A" {
		t.Fatalf("ordinary miss did not join owner refresh: %v", result)
	}
}
