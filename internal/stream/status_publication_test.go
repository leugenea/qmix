package stream

import (
	"context"
	"testing"
	"time"
)

// A newer rejected generation starts a later refresh; the older flight must
// not publish while that later refresh is still running.
func TestCacheLaterStatusRefreshOwnsPublication(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "first", nil }); err != nil {
		t.Fatal(err)
	}
	first := c.GetEntry("track")
	if !c.DeleteIfEntry("track", first) {
		t.Fatal("first entry was not invalidated")
	}
	firstStarted, releaseFirst := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseFirst)
	firstDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", first, func(context.Context) (interface{}, error) {
			close(firstStarted)
			<-releaseFirst
			return "older refresh", nil
		})
		firstDone <- err
	}()
	<-firstStarted
	// Another cache generation is observed and rejected while the old lookup
	// remains parked. The newer status refresh must own final publication.
	c.mu.Lock()
	c.state("track").entry = &entry{value: "second", expiry: time.Now().Add(time.Minute)}
	c.mu.Unlock()
	second := c.GetEntry("track")
	if !c.DeleteIfEntry("track", second) {
		t.Fatal("second entry was not invalidated")
	}
	secondStarted, releaseSecond := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseSecond)
	secondDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", second, func(context.Context) (interface{}, error) {
			close(secondStarted)
			<-releaseSecond
			return "newer refresh", nil
		})
		secondDone <- err
	}()
	<-secondStarted
	c.mu.Lock()
	if s := c.states["track"]; s == nil || s.owner != second || s.refreshes[second] == nil {
		c.mu.Unlock()
		t.Fatal("newer refresh does not hold the reservation while blocked")
	}
	c.mu.Unlock()
	close(releaseFirst)
	if err := <-firstDone; err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	if s := c.states["track"]; s == nil || s.entry != nil || s.owner != second {
		c.mu.Unlock()
		t.Fatal("older refresh transiently published while newer owner was blocked")
	}
	c.mu.Unlock()
	close(releaseSecond)
	if err := <-secondDone; err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); !ok || got != "newer refresh" {
		t.Fatalf("cache = %v, %v; older refresh overtook newer status refresh", got, ok)
	}
}
