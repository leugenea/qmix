package stream

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestCachePreCanceledStatusRefreshReleasesReservation(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "old", nil }); err != nil {
		t.Fatal(err)
	}
	attempted := c.GetEntry("track")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var canceledLookupCalls atomic.Int32
	if _, err := c.DoContextRejected(ctx, "track", attempted, func(context.Context) (interface{}, error) {
		canceledLookupCalls.Add(1)
		return nil, errors.New("canceled caller started lookup")
	}); !errors.Is(err, context.Canceled) {
		t.Fatalf("refresh error = %v", err)
	}
	if canceledLookupCalls.Load() != 0 {
		t.Fatalf("canceled caller started %d lookups", canceledLookupCalls.Load())
	}
	c.mu.Lock()
	if s := c.states["track"]; s == nil || s.owner != nil || s.entry != attempted {
		c.mu.Unlock()
		t.Fatal("pre-canceled rejection mutated ownership or the cached entry")
	}
	c.state("track").entry.expiry = time.Time{}
	c.mu.Unlock()
	if _, err := c.Do("track", func() (interface{}, error) { return "recovered", nil }); err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); !ok || got != "recovered" {
		t.Fatalf("pre-canceled refresh stranded owner: %v, %v", got, ok)
	}
}

func TestSecondInvalidStatusLeavesKeyAvailableForOrdinaryRecovery(t *testing.T) {
	runner := &sequenceRunner{urls: []string{"https://media.example/old", "https://media.example/retry"}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusForbidden, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("bad")), Request: req}, nil
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	if _, err := backend.Stream(context.Background(), track, ""); !errors.Is(err, ErrService) {
		t.Fatalf("stream error = %v", err)
	}
	c := backend.cacheRef()
	if _, err := c.Do(cacheKey(track), func() (interface{}, error) { return "recovered", nil }); err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get(cacheKey(track)); !ok || got != "recovered" {
		t.Fatalf("second failure stranded owner: %v, %v", got, ok)
	}
}
