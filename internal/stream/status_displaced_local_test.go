package stream

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// B's ordinary lookup starts while E0 is expired, but finishes after A has
// rejected E0 and published E1. B must still refresh its own failed URL.
func TestDisplacedOrdinaryLookupRetriesWithoutTouchingNewerPublication(t *testing.T) {
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	ordinaryLookup, releaseOrdinary := make(chan struct{}), make(chan struct{})
	retryLookup, releaseRetry := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(releaseOld)
	defer closeIfOpen(releaseOrdinary)
	defer closeIfOpen(releaseRetry)
	const rangeHeader = "bytes=23-"
	badBodies := []*closeProbe{{text: "E0 invalid"}, {text: "B invalid"}}
	var hits atomic.Int32
	var mu sync.Mutex
	paths := make(map[string]int)
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return "https://media.example/e0", nil
		case 2:
			close(ordinaryLookup)
			select {
			case <-releaseOrdinary:
			case <-ctx.Done():
				return "", ctx.Err()
			}
			return "https://media.example/b0", nil
		case 3:
			return "https://media.example/e1", nil
		case 4:
			close(retryLookup)
			select {
			case <-releaseRetry:
			case <-ctx.Done():
				return "", ctx.Err()
			}
			return "https://media.example/b1", nil
		default:
			return "", fmt.Errorf("unexpected lookup %d", n)
		}
	}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		if req.Header.Get("Range") != rangeHeader {
			t.Errorf("GET %s Range = %q", req.URL.Path, req.Header.Get("Range"))
		}
		mu.Lock()
		paths[req.URL.Path]++
		mu.Unlock()
		switch req.URL.Path {
		case "/e0":
			close(oldGET)
			select {
			case <-releaseOld:
			case <-req.Context().Done():
				return nil, req.Context().Err()
			}
			return &http.Response{StatusCode: 403, Header: http.Header{}, Body: badBodies[0], Request: req}, nil
		case "/b0":
			return &http.Response{StatusCode: 403, Header: http.Header{}, Body: badBodies[1], Request: req}, nil
		case "/e1", "/b1":
			return &http.Response{StatusCode: 206, Header: http.Header{"Content-Range": {"bytes 23-27/28"}}, Body: http.NoBody, Request: req}, nil
		default:
			return nil, fmt.Errorf("unexpected GET %s", req.URL.Path)
		}
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	cache := backend.cacheRef()
	key := cacheKey(track)
	if _, err := backend.resolveURL(context.Background(), track); err != nil {
		t.Fatal(err)
	}
	e0 := cache.GetEntry(key)
	if e0 == nil {
		t.Fatal("E0 was not published")
	}
	stream := func() error {
		result, err := backend.Stream(context.Background(), track, rangeHeader)
		if err == nil {
			result.Body.Close()
		}
		return err
	}
	aDone, bDone := make(chan error, 1), make(chan error, 1)
	go func() { aDone <- stream() }()
	awaitDisplaced(t, oldGET, "A's E0 GET")
	cache.mu.Lock()
	cache.state(key).entry.expiry = time.Time{}
	cache.mu.Unlock()
	go func() { bDone <- stream() }()
	awaitDisplaced(t, ordinaryLookup, "B's ordinary lookup")
	close(releaseOld)
	if err := awaitDisplaced(t, aDone, "A's successful refresh"); err != nil {
		t.Fatal(err)
	}
	e1 := cache.GetEntry(key)
	if e1 == nil || e1 == e0 || e1.value != "https://media.example/e1" {
		t.Fatalf("A did not publish E1: %v", e1)
	}
	close(releaseOrdinary)
	// The old code returns ErrService instead of entering B's own lookup.
	awaitDisplaced(t, retryLookup, "B's distinct post-failure lookup")
	if got := cache.GetEntry(key); got != e1 {
		t.Fatalf("B's rejection touched live E1: got %p, want %p", got, e1)
	}
	close(releaseRetry)
	if err := awaitDisplaced(t, bDone, "B's same-Range retry"); err != nil {
		t.Fatal(err)
	}
	if got := cache.GetEntry(key); got != e1 || got.value != "https://media.example/e1" {
		t.Fatalf("B's transient retry replaced E1: %v", got)
	}
	if runner.calls.Load() != 4 || hits.Load() != 4 {
		t.Fatalf("lookups %d GETs %d; want 4 each", runner.calls.Load(), hits.Load())
	}
	mu.Lock()
	for _, path := range []string{"/e0", "/e1", "/b0", "/b1"} {
		if paths[path] != 1 {
			t.Errorf("GET %s count = %d; want 1", path, paths[path])
		}
	}
	mu.Unlock()
	for i, body := range badBodies {
		if body.closed.Load() != 1 || body.read.Load() != 0 {
			t.Errorf("invalid body %d: closed %d, read %d", i, body.closed.Load(), body.read.Load())
		}
	}
}

func awaitDisplaced[T any](t *testing.T, ch <-chan T, what string) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		var zero T
		return zero
	}
}

func TestTransientAttemptCanPublishWhenNoNewerEntryOrOwner(t *testing.T) {
	for _, newerExpires := range []bool{false, true} {
		name := "no_newer_entry"
		if newerExpires {
			name = "newer_entry_expired"
		}
		t.Run(name, func(t *testing.T) {
			c := NewCache(time.Minute)
			if _, err := c.Do("track", func() (interface{}, error) { return "E0", nil }); err != nil {
				t.Fatal(err)
			}
			e0 := c.GetEntry("track")
			c.mu.Lock()
			c.state("track").entry.expiry = time.Time{}
			c.mu.Unlock()
			entered, release := make(chan struct{}), make(chan struct{})
			defer closeIfOpen(release)
			result := make(chan *entry, 1)
			go func() {
				e, _ := c.DoContextEntry(context.Background(), "track", func(context.Context) (interface{}, error) {
					close(entered)
					<-release
					return "B first URL", nil
				})
				result <- e
			}()
			awaitDisplaced(t, entered, "ordinary loader")
			if newerExpires {
				// E1 is published by an independent status flight, then expires.
				if _, err := c.DoContextRejected(context.Background(), "track", e0, func(context.Context) (interface{}, error) {
					return "E1", nil
				}); err != nil {
					t.Fatal(err)
				}
				c.mu.Lock()
				c.state("track").entry.expiry = time.Time{}
				c.mu.Unlock()
			} else {
				// A terminal failure advances the epoch without owning a lookup.
				c.DeleteIfEntryFinal("track", e0)
			}
			close(release)
			attempt := awaitDisplaced(t, result, "displaced local attempt")
			if attempt == nil || !attempt.transient || attempt.cache != c || attempt.key != "track" || attempt.value != "B first URL" {
				t.Fatalf("displaced result lost local identity: %v", attempt)
			}
			if got := c.GetEntry("track"); got != nil {
				t.Fatalf("displaced attempt was published: %v", got)
			}
			// Locality is exact to both cache and key, even at generation zero.
			foreign := NewCache(time.Minute)
			var foreignLoads atomic.Int32
			noLoad := func(context.Context) (interface{}, error) {
				foreignLoads.Add(1)
				return "unexpected", nil
			}
			if c.DeleteIfEntry("other", attempt) || foreign.DeleteIfEntry("track", attempt) {
				t.Fatal("transient identity rejected in another cache/key")
			}
			if _, err := c.DoContextRejected(context.Background(), "other", attempt, noLoad); err != ErrService {
				t.Fatalf("wrong-key rejection = %v, want ErrService", err)
			}
			if _, err := foreign.DoContextRejected(context.Background(), "track", attempt, noLoad); err != ErrService {
				t.Fatalf("foreign-cache rejection = %v, want ErrService", err)
			}
			c.mu.Lock()
			_, wrongKeyState := c.states["other"]
			c.mu.Unlock()
			foreign.mu.Lock()
			foreignStates := len(foreign.states)
			foreign.mu.Unlock()
			if wrongKeyState || foreignStates != 0 || foreignLoads.Load() != 0 {
				t.Fatal("foreign transient rejection created state or ran loader")
			}
			var lookups atomic.Int32
			retry, err := c.DoContextRejected(context.Background(), "track", attempt, func(context.Context) (interface{}, error) {
				lookups.Add(1)
				return "B retry", nil
			})
			if err != nil || retry == nil || retry.value != "B retry" || retry.transient || c.GetEntry("track") != retry || lookups.Load() != 1 {
				t.Fatalf("transient retry = %v, %v; cached %v; lookups %d", retry, err, c.GetEntry("track"), lookups.Load())
			}
		})
	}
}
