package stream

import (
	"context"
	"errors"
	"fmt"
	"sync"
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

// TestCacheSingleflight verifies concurrent misses for the same key run the
// loader once and all waiters share the result.
func TestCacheSingleflight(t *testing.T) {
	c := NewCache(time.Minute)
	var loads int32
	loader := func() (interface{}, error) {
		atomic.AddInt32(&loads, 1)
		time.Sleep(20 * time.Millisecond)
		return "shared", nil
	}

	var wg sync.WaitGroup
	errs := make(chan error, 50)
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			v, err := c.Do("k", loader)
			if err != nil {
				errs <- err
				return
			}
			if v != "shared" {
				errs <- fmt.Errorf("got %v, want shared", v)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if n := atomic.LoadInt32(&loads); n != 1 {
		t.Fatalf("loader ran %d times, want 1", n)
	}
}

// TestCacheKeyIsolation verifies different keys load independently and a fresh
// cached value is returned without re-running the loader.
func TestCacheKeyIsolation(t *testing.T) {
	c := NewCache(time.Minute)
	var loads int32
	loader := func() (interface{}, error) {
		atomic.AddInt32(&loads, 1)
		return "v", nil
	}
	if _, err := c.Do("a", loader); err != nil {
		t.Fatal(err)
	}
	// Cached key reuse does not re-run.
	if _, err := c.Do("a", loader); err != nil {
		t.Fatal(err)
	}
	if n := atomic.LoadInt32(&loads); n != 1 {
		t.Fatalf("loads = %d, want 1 (cached reuse)", n)
	}
	// Different key triggers its own load.
	if _, err := c.Do("b", loader); err != nil {
		t.Fatal(err)
	}
	if n := atomic.LoadInt32(&loads); n != 2 {
		t.Fatalf("loads = %d, want 2 (separate key)", n)
	}
	if c.Len() != 2 {
		t.Fatalf("Len = %d, want 2", c.Len())
	}
}

// TestCacheExpiryRunsLoaderAgain verifies a reload after TTL elapses.
func TestCacheExpiryRunsLoaderAgain(t *testing.T) {
	c := NewCache(20 * time.Millisecond)
	var loads int32
	loader := func() (interface{}, error) {
		atomic.AddInt32(&loads, 1)
		return "v", nil
	}
	c.Do("k", loader)
	c.Do("k", loader)
	if n := atomic.LoadInt32(&loads); n != 1 {
		t.Fatalf("loads before expiry = %d, want 1", n)
	}
	time.Sleep(30 * time.Millisecond)
	c.Do("k", loader)
	if n := atomic.LoadInt32(&loads); n != 2 {
		t.Fatalf("loads after expiry = %d, want 2", n)
	}
}

// TestCacheCanceledWaiterReturnsPromptly verifies a caller waiting on an
// existing load can leave without waiting for that unrelated load to finish.
func TestCacheCanceledWaiterReturnsPromptly(t *testing.T) {
	c := NewCache(time.Minute)
	started := make(chan struct{})
	release := make(chan struct{})
	loader := func(context.Context) (interface{}, error) {
		close(started)
		<-release
		return "shared", nil
	}
	leaderDone := make(chan error, 1)
	go func() {
		_, err := c.DoContext(context.Background(), "k", loader)
		leaderDone <- err
	}()
	<-started

	ctx, cancel := context.WithCancel(context.Background())
	waiterDone := make(chan error, 1)
	go func() {
		_, err := c.DoContext(ctx, "k", loader)
		waiterDone <- err
	}()
	waitForCacheWaiters(t, c, "k", 2)
	cancel()
	var err error
	select {
	case err = <-waiterDone:
	case <-time.After(time.Second):
		t.Fatal("canceled waiter remained blocked")
	}
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("DoContext error = %v, want context.Canceled", err)
	}

	close(release)
	if err := <-leaderDone; err != nil {
		t.Fatalf("leader error = %v", err)
	}
}

// TestCacheLeaderCancellationDoesNotPoisonWaiters defines shared-load lifetime:
// one caller leaving does not stop a load that another caller still needs.
func TestCacheLeaderCancellationDoesNotPoisonWaiters(t *testing.T) {
	c := NewCache(time.Minute)
	started := make(chan struct{})
	release := make(chan struct{})
	loader := func(context.Context) (interface{}, error) {
		close(started)
		<-release
		return "shared", nil
	}

	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	leaderDone := make(chan error, 1)
	go func() {
		_, err := c.DoContext(leaderCtx, "k", loader)
		leaderDone <- err
	}()
	<-started

	waiterDone := make(chan error, 1)
	go func() {
		v, err := c.DoContext(context.Background(), "k", loader)
		if err == nil && v != "shared" {
			err = fmt.Errorf("value = %v, want shared", v)
		}
		waiterDone <- err
	}()
	waitForCacheWaiters(t, c, "k", 2)
	cancelLeader()
	if err := <-leaderDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("leader error = %v, want context.Canceled", err)
	}

	close(release)
	if err := <-waiterDone; err != nil {
		t.Fatalf("waiter error = %v", err)
	}
}

func TestCacheAllWaitersCanceledStopsLoad(t *testing.T) {
	c := NewCache(time.Minute)
	started := make(chan struct{})
	loaderDone := make(chan struct{})
	loader := func(ctx context.Context) (interface{}, error) {
		close(started)
		<-ctx.Done()
		close(loaderDone)
		return nil, ctx.Err()
	}

	ctx, cancel := context.WithCancel(context.Background())
	callerDone := make(chan error, 1)
	go func() {
		_, err := c.DoContext(ctx, "k", loader)
		callerDone <- err
	}()
	<-started
	cancel()
	if err := <-callerDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("caller error = %v, want context.Canceled", err)
	}
	select {
	case <-loaderDone:
	case <-time.After(time.Second):
		t.Fatal("shared loader was not canceled after its last waiter left")
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
	oldCall := c.inflight["k"]
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
	replacementCall := c.inflight["k"]
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

func waitForCacheWaiters(t *testing.T, c *Cache, key string, want int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		pending := c.inflight[key]
		got := 0
		if pending != nil {
			got = pending.waiters
		}
		c.mu.Unlock()
		if got == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("cache waiters for %q did not reach %d", key, want)
}
