package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// The ordinary miss starts only after invalidation; it cannot take the
// publication claim reserved for the failed GET's post-failure lookup.
func TestCachePostInvalidationOrdinaryMissCannotDisplaceRefresh(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "stale", nil }); err != nil {
		t.Fatal(err)
	}
	failed := c.GetEntry("track")
	if !c.DeleteIfEntry("track", failed) {
		t.Fatal("failed generation was not invalidated")
	}
	ordinaryStarted, releaseOrdinary := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOrdinary)
	ordinaryDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextEntry(context.Background(), "track", func(context.Context) (interface{}, error) {
			close(ordinaryStarted)
			<-releaseOrdinary
			return "old ordinary result", nil
		})
		ordinaryDone <- err
	}()
	<-ordinaryStarted
	refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseRefresh)
	refreshDone := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", failed, func(context.Context) (interface{}, error) {
			close(refreshStarted)
			<-releaseRefresh
			return "refreshed", nil
		})
		refreshDone <- err
	}()
	<-refreshStarted
	close(releaseOrdinary)
	if err := <-ordinaryDone; err != nil {
		t.Fatal(err)
	}
	close(releaseRefresh)
	if err := <-refreshDone; err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); !ok || got != "refreshed" {
		t.Fatalf("cached URL = %v, %v; post-invalidation ordinary miss displaced refresh", got, ok)
	}
}

// E0 is attempted before expiration. E1 is subsequently published, rejected,
// and starts its refresh. E0's late failed GET has no claim on E1's refresh.
func TestOlderLateFailureDoesNotObsoleteNewerGenerationRefresh(t *testing.T) {
	const old = "https://media.example/e0"
	const newer = "https://media.example/e1"
	const refreshed = "https://media.example/e2"
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	newerGET, releaseNewer := make(chan struct{}), make(chan struct{})
	refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOld)
	defer closeIfOpen(releaseNewer)
	defer closeIfOpen(releaseRefresh)
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			return newer, nil
		case 3:
			close(refreshStarted)
			select {
			case <-releaseRefresh:
				return refreshed, nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		case 4:
			return "https://media.example/old-retry", nil
		default:
			return "", fmt.Errorf("unexpected lookup %d", n)
		}
	}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		switch req.URL.String() {
		case old:
			select {
			case <-oldGET:
			default:
				close(oldGET)
				<-releaseOld
			}
		case newer:
			close(newerGET)
			<-releaseNewer
		}
		status := http.StatusOK
		if req.URL.String() == old || req.URL.String() == newer {
			status = http.StatusForbidden
		}
		return &http.Response{StatusCode: status, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("audio")), Request: req}, nil
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	stream := func() <-chan error {
		done := make(chan error, 1)
		go func() {
			res, err := backend.Stream(context.Background(), track, "")
			if err == nil {
				res.Body.Close()
			}
			done <- err
		}()
		return done
	}
	oldDone := stream()
	<-oldGET
	c := backend.cacheRef()
	c.mu.Lock()
	c.state(cacheKey(track)).entry.expiry = time.Time{}
	c.mu.Unlock()
	newerDone := stream()
	<-newerGET
	close(releaseNewer)
	awaitDisplaced(t, refreshStarted, "E1's owner refresh")
	// Once E1 owns the refresh, no live or transient stale value is
	// available to ordinary callers while its lookup is parked.
	c.mu.Lock()
	s := c.states[cacheKey(track)]
	if s == nil || s.entry != nil || s.owner == nil || s.refreshes[s.owner] == nil {
		c.mu.Unlock()
		t.Fatal("owner refresh exposed a stale entry or lost its reservation")
	}
	c.mu.Unlock()
	if c.GetEntry(cacheKey(track)) != nil || c.Len() != 0 {
		t.Fatal("owner refresh exposed a live stale entry")
	}
	close(releaseOld)
	// E0's post-failure search must have started before E1 publishes, so
	// the newest refresh can be released only after E0 has finished.
	if err := <-oldDone; err != nil {
		t.Fatal(err)
	}
	if c.GetEntry(cacheKey(track)) != nil {
		t.Fatal("older failure restored stale entry during owner refresh")
	}
	close(releaseRefresh)
	if err := <-newerDone; err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get(cacheKey(track)); !ok || got != refreshed {
		t.Fatalf("cached URL = %v, %v; older late failure obsoleted E1 refresh", got, ok)
	}
}

// Canceling one of two status-flight waiters must not abandon the surviving
// waiter's reservation or allow the rejected generation back into the cache.
func TestCacheStatusRefreshSurvivesOneWaiterCancellation(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "stale", nil }); err != nil {
		t.Fatal(err)
	}
	stale := c.GetEntry("track")
	started, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	var loads atomic.Int32
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	type outcome struct {
		e   *entry
		err error
	}
	first, survivor := make(chan outcome, 1), make(chan outcome, 1)
	go func() {
		e, err := c.DoContextRejected(ctx, "track", stale, func(context.Context) (interface{}, error) {
			loads.Add(1)
			close(started)
			<-release
			return "fresh", nil
		})
		first <- outcome{e, err}
	}()
	<-started
	c.mu.Lock()
	flight := c.states["track"].refreshes[stale]
	c.mu.Unlock()
	go func() {
		e, err := c.DoContextRejected(context.Background(), "track", stale, func(context.Context) (interface{}, error) {
			loads.Add(1)
			return "rival", nil
		})
		survivor <- outcome{e, err}
	}()
	waitForCacheWaiters(t, c, "track", 2)
	cancel()
	if r := <-first; !errors.Is(r.err, context.Canceled) || r.e != nil {
		t.Fatalf("canceled waiter = %+v", r)
	}
	c.mu.Lock()
	s := c.states["track"]
	intact := s != nil && s.entry == nil && s.owner == stale && s.refreshes[stale] == flight && flight.waiters == 1
	c.mu.Unlock()
	if !intact || c.GetEntry("track") != nil || loads.Load() != 1 {
		t.Fatalf("survivor lost reservation or stale entry reappeared; intact %v, loads %d", intact, loads.Load())
	}
	close(release)
	r := <-survivor
	if r.err != nil || r.e == nil || r.e == stale || r.e.value != "fresh" || c.GetEntry("track") != r.e || loads.Load() != 1 {
		t.Fatalf("survivor = %+v, cached %p, loads %d", r, c.GetEntry("track"), loads.Load())
	}
}
