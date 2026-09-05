package stream

import (
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
