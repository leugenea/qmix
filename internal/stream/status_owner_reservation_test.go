package stream

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"time"
)

// An expired entry's old GET may fail while two ordinary misses share a
// displaced lookup. Their subsequent failures can straddle the old refresh's
// failure: the second reject reserves ownership of the first caller's already
// running (older-epoch) refresh.
func TestOwnerReservationReleasedAfterJoiningOlderRefresh(t *testing.T) {
	const key = "track"
	c := NewCache(time.Minute)
	if _, err := c.Do(key, func() (interface{}, error) { return "e1", nil }); err != nil {
		t.Fatal(err)
	}
	e1 := c.GetEntry(key)

	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	f1Started, releaseF1 := make(chan struct{}), make(chan struct{})
	f2Started, releaseF2 := make(chan struct{}), make(chan struct{})
	f3Started, releaseF3 := make(chan struct{}), make(chan struct{})
	releaseA, releaseB := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOld)
	defer closeIfOpen(releaseF1)
	defer closeIfOpen(releaseF2)
	defer closeIfOpen(releaseF3)
	defer closeIfOpen(releaseA)
	defer closeIfOpen(releaseB)

	oldDone := make(chan error, 1)
	go func() {
		close(oldGET) // Old GET has begun with e1, before its expiry.
		<-releaseOld
		_, err := c.DoContextRejected(context.Background(), key, e1, func(context.Context) (interface{}, error) {
			close(f2Started)
			<-releaseF2
			return nil, errors.New("old refresh failed")
		})
		oldDone <- err
	}()
	awaitDisplaced(t, oldGET, "old e1 GET")
	c.mu.Lock()
	c.state(key).entry.expiry = time.Time{}
	c.mu.Unlock()

	type outcome struct {
		e   *entry
		err error
	}
	firstA, firstB := make(chan outcome, 1), make(chan outcome, 1)
	doneA, doneB := make(chan outcome, 1), make(chan outcome, 1)
	go func() {
		tr, err := c.DoContextEntry(context.Background(), key, func(context.Context) (interface{}, error) {
			close(f1Started)
			<-releaseF1
			return "tr", nil
		})
		firstA <- outcome{tr, err}
		<-releaseA // A's GET of tr fails before F2 finishes.
		if err != nil {
			doneA <- outcome{nil, err}
			return
		}
		e, err := c.DoContextRejected(context.Background(), key, tr, func(context.Context) (interface{}, error) {
			close(f3Started)
			<-releaseF3
			return "f3", nil
		})
		doneA <- outcome{e, err}
	}()
	awaitDisplaced(t, f1Started, "ordinary F1")
	go func() {
		tr, err := c.DoContextEntry(context.Background(), key, func(context.Context) (interface{}, error) {
			return nil, fmt.Errorf("B unexpectedly started an ordinary lookup")
		})
		firstB <- outcome{tr, err}
		<-releaseB // B's GET of tr fails after F2 clears e1's owner.
		if err != nil {
			doneB <- outcome{nil, err}
			return
		}
		e, err := c.DoContextRejected(context.Background(), key, tr, func(context.Context) (interface{}, error) {
			return nil, fmt.Errorf("B unexpectedly started a second refresh")
		})
		doneB <- outcome{e, err}
	}()
	waitForCacheWaiters(t, c, key, 2)

	close(releaseOld)
	awaitDisplaced(t, f2Started, "old e1 refresh F2")
	close(releaseF1)
	a, b := awaitDisplaced(t, firstA, "A's transient result"), awaitDisplaced(t, firstB, "B's transient result")
	if a.err != nil || b.err != nil || a.e == nil || a.e != b.e || !a.e.transient {
		t.Fatalf("F1 should serve the same displaced transient to A/B: A=%+v B=%+v", a, b)
	}
	close(releaseA)
	awaitDisplaced(t, f3Started, "A's F3 refresh")
	close(releaseF2)
	if err := awaitDisplaced(t, oldDone, "failed F2"); err == nil {
		t.Fatal("F2 unexpectedly succeeded")
	}
	close(releaseB)
	waitForCacheWaiters(t, c, key, 2) // B reserved tr but joined A's older F3.
	close(releaseF3)
	for label, ch := range map[string]<-chan outcome{"A": doneA, "B": doneB} {
		got := awaitDisplaced(t, ch, label+"'s F3 result")
		if got.err != nil || got.e == nil || got.e.value != "f3" {
			t.Fatalf("%s's F3 result = %+v", label, got)
		}
	}

	// F3 cannot publish across B's intervening rejection, but it must retire
	// the owner so the next ordinary load can publish and serve later hits.
	var loads atomic.Int32
	for i := 0; i < 3; i++ {
		value, err := c.Do(key, func() (interface{}, error) {
			loads.Add(1)
			return "recovered", nil
		})
		if err != nil || value != "recovered" {
			t.Fatalf("sequential request %d = %v, %v", i, value, err)
		}
	}
	if got := loads.Load(); got != 1 {
		t.Fatalf("sequential requests loaded %d times, want 1 (later requests must hit the cache)", got)
	}
}
