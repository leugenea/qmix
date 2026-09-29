package stream

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"time"
)

// TestCacheGetSet verifies basic Get after a store-via-Do and expiration.
func TestCacheGetSet(t *testing.T) {
	c := NewCache(50 * time.Millisecond)
	v, ok := c.Get("k")
	if ok {
		t.Fatalf("Get on empty cache returned %v", v)
	}

	got, err := c.Do("k", func() (interface{}, error) { return "val", nil })
	if err != nil || got != "val" {
		t.Fatalf("Do = (%v, %v), want (val, nil)", got, err)
	}
	v, ok = c.Get("k")
	if !ok || v != "val" {
		t.Fatalf("Get = (%v, %v), want (val, true)", v, ok)
	}

	time.Sleep(70 * time.Millisecond)
	if _, ok := c.Get("k"); ok {
		t.Fatal("entry should have expired")
	}
	if c.Len() != 0 {
		t.Fatalf("Len = %d, want 0 after expiry", c.Len())
	}
}

// TestCacheLoadErrorNotStored verifies a failed loader is not cached.
func TestCacheLoadErrorNotStored(t *testing.T) {
	c := NewCache(time.Minute)
	_, err := c.Do("k", func() (interface{}, error) { return nil, errors.New("boom") })
	if err == nil {
		t.Fatal("expected loader error")
	}
	if _, ok := c.Get("k"); ok {
		t.Fatal("failed load must not be cached")
	}
}

func TestCachePreCanceledCallerDoesNotStartLoad(t *testing.T) {
	c := NewCache(time.Minute)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	loaded := false
	_, err := c.DoContext(ctx, "k", func(context.Context) (interface{}, error) {
		loaded = true
		return "unexpected", nil
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("DoContext error = %v, want context.Canceled", err)
	}
	if loaded {
		t.Fatal("loader started for a pre-canceled caller")
	}
}

func TestCacheAbandonedLoadCannotOverwriteReplacement(t *testing.T) {
	c := NewCache(time.Minute)
	oldStarted := make(chan struct{})
	oldRelease := make(chan struct{})
	oldCtx, cancelOld := context.WithCancel(context.Background())
	oldCallerDone := make(chan error, 1)
	go func() {
		_, err := c.DoContext(oldCtx, "k", func(context.Context) (interface{}, error) {
			close(oldStarted)
			<-oldRelease
			return "stale", nil
		})
		oldCallerDone <- err
	}()
	<-oldStarted
	c.mu.Lock()
	oldCall := c.state("k").ordinary
	c.mu.Unlock()
	cancelOld()
	if err := <-oldCallerDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("old caller error = %v, want context.Canceled", err)
	}

	replacementStarted := make(chan struct{})
	replacementRelease := make(chan struct{})
	replacementDone := make(chan error, 1)
	go func() {
		v, err := c.DoContext(context.Background(), "k", func(context.Context) (interface{}, error) {
			close(replacementStarted)
			<-replacementRelease
			return "fresh", nil
		})
		if err == nil && v != "fresh" {
			err = fmt.Errorf("replacement value = %v, want fresh", v)
		}
		replacementDone <- err
	}()
	<-replacementStarted

	close(oldRelease)
	select {
	case <-oldCall.done:
	case <-time.After(time.Second):
		t.Fatal("abandoned loader did not finish")
	}
	c.mu.Lock()
	replacementCall := c.state("k").ordinary
	c.mu.Unlock()
	if replacementCall == nil || replacementCall == oldCall {
		t.Fatal("abandoned load removed the replacement call")
	}

	close(replacementRelease)
	if err := <-replacementDone; err != nil {
		t.Fatal(err)
	}
	if v, ok := c.Get("k"); !ok || v != "fresh" {
		t.Fatalf("cached value = (%v, %v), want (fresh, true)", v, ok)
	}
}

// TestCacheConditionalInvalidation never removes a newer value or another key.
func TestCacheConditionalInvalidation(t *testing.T) {
	c := NewCache(time.Minute)
	for key, value := range map[string]string{"track": "old", "other": "independent"} {
		if _, err := c.Do(key, func() (interface{}, error) { return value, nil }); err != nil {
			t.Fatal(err)
		}
	}
	oldEntry := c.GetEntry("track")
	if c.DeleteIfEntry("track", c.GetEntry("other")) || c.DeleteIfEntry("missing", oldEntry) {
		t.Fatal("deleted an unmatched value")
	}
	if v, ok := c.Get("track"); !ok || v != "old" {
		t.Fatalf("unmatched value removed: %v, %v", v, ok)
	}
	if !c.DeleteIfEntry("track", oldEntry) || c.DeleteIfEntry("track", oldEntry) {
		t.Fatal("matched value not removed exactly once")
	}
	if v, ok := c.Get("other"); !ok || v != "independent" {
		t.Fatalf("unrelated key removed: %v, %v", v, ok)
	}
	// Rejection reserves the publication slot for the post-failure lookup;
	// an ordinary miss must not steal it.
	if _, err := c.DoContextFresh(context.Background(), "track", oldEntry, func(context.Context) (interface{}, error) { return "new", nil }); err != nil {
		t.Fatal(err)
	}
	if c.DeleteIfEntry("track", oldEntry) {
		t.Fatal("old failure removed a newer value")
	}
	if v, ok := c.Get("track"); !ok || v != "new" {
		t.Fatalf("newer value missing: %v, %v", v, ok)
	}
}

func TestCacheFreshLoadBypassesValueAndSharesInflight(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	oldEntry := c.GetEntry("track")
	entered, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	var loads atomic.Int32
	loader := func(context.Context) (interface{}, error) {
		loads.Add(1)
		close(entered)
		<-release
		return "new", nil
	}
	first := make(chan error, 1)
	go func() { _, err := c.DoContextFresh(context.Background(), "track", oldEntry, loader); first <- err }()
	awaitDisplaced(t, entered, "fresh cache loader")
	second := make(chan error, 1)
	go func() { _, err := c.DoContextFresh(context.Background(), "track", oldEntry, loader); second <- err }()
	waitForCacheWaiters(t, c, "track", 2)
	closeIfOpen(release)
	if err := awaitDisplaced(t, first, "first fresh cache caller"); err != nil {
		t.Fatal(err)
	}
	if err := awaitDisplaced(t, second, "second fresh cache caller"); err != nil {
		t.Fatal(err)
	}
	if loads.Load() != 1 {
		t.Fatalf("loads = %d, want 1", loads.Load())
	}
	if v, ok := c.Get("track"); !ok || v != "new" {
		t.Fatalf("fresh cache = %v, %v", v, ok)
	}
}

func TestCacheFreshLoadDoesNotReplaceNewerConcurrentEntry(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	oldEntry := c.GetEntry("track")
	entered, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	finished := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", oldEntry, func(context.Context) (interface{}, error) {
			close(entered)
			<-release
			return "retry", nil
		})
		finished <- err
	}()
	awaitDisplaced(t, entered, "late refresh loader")
	// Simulate a newer cache publication while the old attempt is waiting on
	// yt-dlp. The cache method must compare publication identity at completion.
	c.mu.Lock()
	c.state("track").entry = &entry{value: "newer", expiry: time.Now().Add(time.Minute)}
	c.mu.Unlock()
	closeIfOpen(release)
	if err := awaitDisplaced(t, finished, "late refresh caller"); err != nil {
		t.Fatal(err)
	}
	if v, ok := c.Get("track"); !ok || v != "newer" {
		t.Fatalf("late refresh replaced newer cached URL: %v, %v", v, ok)
	}
}

func waitForCacheWaiters(t *testing.T, c *Cache, key string, want int) {
	t.Helper()
	watchdog := time.NewTimer(time.Second)
	defer watchdog.Stop()
	for {
		c.mu.Lock()
		var pending *call
		if state := c.states[key]; state != nil {
			pending = state.ordinary
			for _, refresh := range state.refreshes {
				pending = refresh
				break
			}
		}
		got := 0
		if pending != nil {
			got = pending.waiters
		}
		c.mu.Unlock()
		if got == want {
			return
		}
		select {
		case <-c.waiterChanged:
		case <-watchdog.C:
			t.Fatalf("cache waiters for %q = %d, want %d", key, got, want)
		}
	}
}
