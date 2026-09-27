package stream

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestForeignCacheEntryRejectionIsNoOp(t *testing.T) {
	c1, c2 := NewCache(time.Minute), NewCache(time.Minute)
	if _, err := c1.Do("track", func() (interface{}, error) { return "C1", nil }); err != nil {
		t.Fatal(err)
	}
	foreign := c1.GetEntry("track")
	if _, err := c2.Do("track", func() (interface{}, error) { return "C2", nil }); err != nil {
		t.Fatal(err)
	}
	sentinel := c2.GetEntry("track")
	c2.mu.Lock()
	before := c2.states["track"]
	epoch, latest, next := before.epoch, before.lineage.latest, c2.next
	c2.mu.Unlock()
	if c2.DeleteIfEntry("track", foreign) || c2.DeleteIfEntryFinal("track", foreign) || c2.DeleteIfEntry("absent", foreign) || c2.DeleteIfEntryFinal("absent", foreign) {
		t.Fatal("foreign entry acquired a rejection")
	}
	var loads atomic.Int32
	if e, err := c2.DoContextRejected(context.Background(), "track", foreign, func(context.Context) (interface{}, error) {
		loads.Add(1)
		return "unexpected", nil
	}); e != nil || !errors.Is(err, ErrService) {
		t.Fatalf("foreign atomic rejection = %v, %v; want service error and no entry", e, err)
	}
	if e, err := c2.DoContextRejected(context.Background(), "absent", foreign, func(context.Context) (interface{}, error) {
		loads.Add(1)
		return "unexpected", nil
	}); e != nil || !errors.Is(err, ErrService) {
		t.Fatalf("foreign rejection for absent key = %v, %v; want service error and no state", e, err)
	}
	c2.mu.Lock()
	s := c2.states["track"]
	_, absent := c2.states["absent"]
	untouched := s == before && s.entry == sentinel && s.owner == nil && s.epoch == epoch && s.lineage.latest == latest && c2.next == next && s.ordinary == nil && len(s.refreshes) == 0 && !absent
	c2.mu.Unlock()
	if !untouched || loads.Load() != 0 {
		t.Fatalf("foreign rejection changed C2 or ran loader: unchanged=%v loads=%d", untouched, loads.Load())
	}
}

// Each round publishes on C1 while C2 rejects the prior generation. The
// barrier makes these operations concurrent without relying on timed sleeps;
// -race must never see C2 access a lineage protected only by C1.mu.
func TestForeignCacheRejectionConcurrentWithOriginPublication(t *testing.T) {
	c1, c2 := NewCache(time.Minute), NewCache(time.Minute)
	if _, err := c1.Do("track", func() (interface{}, error) { return "seed", nil }); err != nil {
		t.Fatal(err)
	}
	foreign := c1.GetEntry("track")
	for i := 0; i < 100; i++ {
		c1.mu.Lock()
		c1.states["track"].entry.expiry = time.Time{}
		c1.mu.Unlock()
		start := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			<-start
			_, _ = c1.Do("track", func() (interface{}, error) { return "next", nil })
		}()
		go func(foreign *entry) {
			defer wg.Done()
			<-start
			c2.DeleteIfEntry("track", foreign)
			c2.DeleteIfEntryFinal("track", foreign)
		}(foreign)
		close(start)
		wg.Wait()
		foreign = c1.GetEntry("track")
		if foreign == nil {
			t.Fatalf("C1 publication missing in round %d", i)
		}
	}
	c2.mu.Lock()
	count := len(c2.states)
	next := c2.next
	c2.mu.Unlock()
	if count != 0 || next != 0 {
		t.Fatalf("foreign rejections left %d C2 states and next generation %d", count, next)
	}
}
