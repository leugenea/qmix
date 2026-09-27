package stream

import (
	"context"
	"testing"
	"time"
)

// A newer publication that has expired no longer owns the cache key. An old
// failed GET must still make a fresh lookup and can publish if no newer live
// entry or refresh exists. A parked ordinary miss retains the key state here.
func TestCacheExpiredNewerGenerationCannotBlockLateRefresh(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "e0", nil }); err != nil {
		t.Fatal(err)
	}
	old := c.GetEntry("track")
	c.mu.Lock()
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	if _, err := c.Do("track", func() (interface{}, error) { return "e1", nil }); err != nil {
		t.Fatal(err)
	}
	c.mu.Lock()
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	ordinaryStarted, releaseOrdinary := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOrdinary)
	ordinaryDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextEntry(context.Background(), "track", func(context.Context) (interface{}, error) {
			close(ordinaryStarted)
			<-releaseOrdinary
			return "ordinary", nil
		})
		ordinaryDone <- err
	}()
	awaitDisplaced(t, ordinaryStarted, "expired generation ordinary loader")
	if c.DeleteIfEntry("track", old) {
		t.Fatal("expired older entry removed a live value")
	}
	freshDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", old, func(context.Context) (interface{}, error) {
			return "late fresh", nil
		})
		freshDone <- err
	}()
	if err := awaitDisplaced(t, freshDone, "expired generation late refresh"); err != nil {
		t.Fatal(err)
	}
	closeIfOpen(releaseOrdinary)
	if err := awaitDisplaced(t, ordinaryDone, "expired generation ordinary caller"); err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); !ok || got != "late fresh" {
		t.Fatalf("expired newer entry blocked late refresh: %v, %v", got, ok)
	}
}
