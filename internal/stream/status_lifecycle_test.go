package stream

import (
	"context"
	"errors"
	"testing"
	"time"
)

// The owner refresh is already running when an ordinary miss arrives. The
// ordinary caller consumes its result, rather than starting a competing load.
func TestCacheOrdinaryMissJoinsRunningOwnerRefresh(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	failed := c.GetEntry("track")
	c.DeleteIfEntry("track", failed)
	started, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	refreshDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", failed, func(context.Context) (interface{}, error) {
			close(started)
			<-release
			return "fresh", nil
		})
		refreshDone <- err
	}()
	<-started
	ordinaryLoaderRan := make(chan struct{}, 1)
	ordinaryDone := make(chan interface{}, 1)
	go func() {
		value, _ := c.DoContext(context.Background(), "track", func(context.Context) (interface{}, error) {
			ordinaryLoaderRan <- struct{}{}
			return "rival", nil
		})
		ordinaryDone <- value
	}()
	waitForCacheWaiters(t, c, "track", 2)
	close(release)
	if err := <-refreshDone; err != nil {
		t.Fatal(err)
	}
	if value := <-ordinaryDone; value != "fresh" {
		t.Fatalf("ordinary result = %v, want fresh", value)
	}
	select {
	case <-ordinaryLoaderRan:
		t.Fatal("ordinary miss started a rival lookup")
	default:
	}
}

func TestCacheRefreshErrorReleasesOwner(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	failed := c.GetEntry("track")
	c.DeleteIfEntry("track", failed)
	wantErr := errors.New("lookup failed")
	if _, err := c.DoContextFresh(context.Background(), "track", failed, func(context.Context) (interface{}, error) {
		return nil, wantErr
	}); !errors.Is(err, wantErr) {
		t.Fatalf("refresh error = %v", err)
	}
	if _, err := c.Do("track", func() (interface{}, error) { return "ordinary recovery", nil }); err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); !ok || got != "ordinary recovery" {
		t.Fatalf("owner remained reserved after error: %v, %v", got, ok)
	}
}

func TestCacheAbandonedRefreshReleasesOwnerWithoutPublishing(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	failed := c.GetEntry("track")
	c.DeleteIfEntry("track", failed)
	started, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	ctx, cancel := context.WithCancel(context.Background())
	refreshDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(ctx, "track", failed, func(context.Context) (interface{}, error) {
			close(started)
			<-release // deliberately ignores cancellation; must not publish
			return "abandoned", nil
		})
		refreshDone <- err
	}()
	<-started
	c.mu.Lock()
	old := c.state("track").refreshes[failed]
	c.mu.Unlock()
	cancel()
	if err := <-refreshDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("refresh error = %v", err)
	}
	if _, err := c.Do("track", func() (interface{}, error) { return "replacement", nil }); err != nil {
		t.Fatal(err)
	}
	close(release)
	<-old.done
	if got, ok := c.Get("track"); !ok || got != "replacement" {
		t.Fatalf("abandoned refresh published after replacement: %v, %v", got, ok)
	}
}
