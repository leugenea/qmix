package stream

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// The ordinary lookup begins before the rejected GET completes. A status
// retry must start a different, post-failure lookup, not join that old call.
func TestStatusRetryDoesNotJoinPreFailureOrdinaryLookup(t *testing.T) {
	oldGET, releaseGET := make(chan struct{}), make(chan struct{})
	ordinaryLookup, releaseOrdinary := make(chan struct{}), make(chan struct{})
	refreshLookup, releaseRefresh := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseGET)
	defer closeIfOpen(releaseOrdinary)
	defer closeIfOpen(releaseRefresh)
	const old = "https://media.example/old"
	const fresh = "https://media.example/fresh"
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			close(ordinaryLookup)
			select {
			case <-releaseOrdinary:
				return old, nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		case 3:
			close(refreshLookup)
			select {
			case <-releaseRefresh:
				return fresh, nil
			case <-ctx.Done():
				return "", ctx.Err()
			}
		default:
			return "", fmt.Errorf("unexpected lookup %d", n)
		}
	}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.String() == old {
			select {
			case <-oldGET:
			default:
				close(oldGET)
				<-releaseGET
			}
			return &http.Response{StatusCode: 403, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("rejected")), Request: req}, nil
		}
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("audio")), Request: req}, nil
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	result := make(chan error, 1)
	go func() {
		res, err := backend.Stream(context.Background(), track, "")
		if err == nil {
			if res.Body != nil {
				res.Body.Close()
			}
		}
		result <- err
	}()
	<-oldGET
	cache := backend.cacheRef()
	cache.mu.Lock()
	cache.state(cacheKey(track)).entry.expiry = time.Time{}
	cache.mu.Unlock()
	ordinaryDone := make(chan error, 1)
	go func() { _, err := backend.resolveURL(context.Background(), track); ordinaryDone <- err }()
	<-ordinaryLookup
	close(releaseGET)
	select {
	case <-refreshLookup:
	case err := <-result:
		t.Fatalf("status retry finished without a post-failure lookup: %v", err)
	case <-time.After(time.Second):
		t.Fatal("status retry joined the ordinary pre-failure lookup")
	}
	close(releaseOrdinary)
	if err := <-ordinaryDone; err != nil {
		t.Fatal(err)
	}
	close(releaseRefresh)
	if err := <-result; err != nil {
		t.Fatal(err)
	}
	if runner.calls.Load() != 3 {
		t.Fatalf("lookups = %d, want 3", runner.calls.Load())
	}
	if v, ok := cache.Get(cacheKey(track)); !ok || v != fresh {
		t.Fatalf("cached URL = %v, %v, want %s", v, ok, fresh)
	}
}

// B's response is delayed until A has successfully republished the same URL.
// B may retry but must not remove A's newer cache generation.
func TestLateInvalidStatusCannotDeleteRepublishedSameURL(t *testing.T) {
	const url = "https://media.example/audio"
	secondGET, releaseSecond := make(chan struct{}), make(chan struct{})
	refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseSecond)
	defer closeIfOpen(releaseRefresh)
	var hits atomic.Int32
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		n := hits.Add(1)
		if n == 1 {
			close(secondGET)
			<-releaseSecond
		}
		status := http.StatusOK
		if n == 1 || n == 2 {
			status = http.StatusForbidden
		}
		return &http.Response{StatusCode: status, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("audio")), Request: req}, nil
	})}
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		if n == 3 {
			close(refreshStarted)
			select {
			case <-releaseRefresh:
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		return url, nil
	}}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	if _, err := backend.resolveURL(context.Background(), track); err != nil {
		t.Fatal(err)
	}
	cache := backend.cacheRef()
	firstEntry := cache.GetEntry(cacheKey(track))
	// Start B first and hold its GET. A then fails, refreshes to the same
	// URL, and succeeds before B observes its own stale response.
	bDone := make(chan error, 1)
	go func() {
		res, err := backend.Stream(context.Background(), track, "")
		if err == nil {
			res.Body.Close()
		}
		bDone <- err
	}()
	// The first GET belongs to B, and must be delayed. Use a separate gate
	// below to ensure A completes before B's failed response is released.
	<-secondGET
	aDone := make(chan error, 1)
	go func() {
		res, err := backend.Stream(context.Background(), track, "")
		if err == nil {
			res.Body.Close()
		}
		aDone <- err
	}()
	if err := <-aDone; err != nil {
		t.Fatal(err)
	}
	secondEntry := cache.GetEntry(cacheKey(track))
	if secondEntry == firstEntry || secondEntry == nil || secondEntry.gen <= firstEntry.gen {
		t.Fatalf("same URL was not republished with a newer entry identity: first=%v second=%v", firstEntry, secondEntry)
	}
	if v, ok := cache.Get(cacheKey(track)); !ok || v != url {
		t.Fatalf("A did not republish %s: %v, %v", url, v, ok)
	}
	close(releaseSecond)
	<-refreshStarted
	if got := cache.GetEntry(cacheKey(track)); got != secondEntry {
		t.Fatalf("late E0 failure mutated newer identity while its retry was held: got=%v want=%v", got, secondEntry)
	}
	if v, ok := cache.Get(cacheKey(track)); !ok || v != url {
		t.Fatalf("B deleted A's newer same-URL entry: %v, %v", v, ok)
	}
	close(releaseRefresh)
	if err := <-bDone; err != nil {
		t.Fatal(err)
	}
}

// An unrelated expired entry at refresh start is no longer a live owner.
func TestCacheRefreshPublishesWhenDifferentEntryAlreadyExpired(t *testing.T) {
	cache := NewCache(time.Minute)
	cache.mu.Lock()
	cache.state("track").entry = &entry{value: "unrelated", expiry: time.Time{}}
	cache.mu.Unlock()
	entered, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	finished := make(chan *entry, 1)
	errors := make(chan error, 1)
	go func() {
		e, err := cache.DoContextFresh(context.Background(), "track", nil, func(context.Context) (interface{}, error) {
			close(entered)
			<-release
			return "fresh", nil
		})
		finished <- e
		errors <- err
	}()
	<-entered
	close(release)
	e, err := <-finished, <-errors
	if err != nil || e.value != "fresh" {
		t.Fatalf("refresh = %v, %v", e, err)
	}
	if got, ok := cache.Get("track"); !ok || got != "fresh" {
		t.Fatalf("expired entry suppressed publication: %v, %v", got, ok)
	}
}

type lookupSequence struct {
	calls  atomic.Int32
	onCall func(context.Context, int) (string, error)
}

func closeIfOpen(ch chan struct{}) {
	select {
	case <-ch:
	default:
		close(ch)
	}
}

func (r *lookupSequence) Search(ctx context.Context, _ string) ([]byte, error) {
	u, err := r.onCall(ctx, int(r.calls.Add(1)))
	if err != nil {
		return nil, err
	}
	return []byte(fmt.Sprintf(`{"url":%q}`, u)), nil
}
