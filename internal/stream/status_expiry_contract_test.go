package stream

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"testing"
	"time"
)

// qmix#292: unlike the retry admitted while E1 is live below, E1 has already
// expired and ordinary F is parked before the old GET rejects. The old retry
// must finish independently and publish; F serves its own local URL afterward
// without replacing that retry's reusable publication.
func TestOlderRetryAfterExpiredNewerSupersedesParkedOrdinary(t *testing.T) {
	const (
		ttl      = 200 * time.Millisecond
		old      = "https://media.example/expiry-e0?generation=0"
		newer    = "https://media.example/expiry-e1?generation=1"
		ordinary = "https://media.example/expiry-ordinary?attempt=f"
		retry    = "https://media.example/expiry-retry?attempt=older"
	)
	oldGET, releaseOld := make(chan struct{}), make(chan struct{})
	ordinaryLookup, releaseOrdinary := make(chan struct{}), make(chan struct{})
	olderLookup, releaseOlder := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() {
		closeIfOpen(releaseOld)
		closeIfOpen(releaseOrdinary)
		closeIfOpen(releaseOlder)
	})
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			return newer, nil
		case 3:
			close(ordinaryLookup)
			if err := waitCacheGate(releaseOrdinary, "ordinary lookup after E1 expiry"); err != nil {
				return "", err
			}
			return ordinary, nil
		case 4:
			close(olderLookup)
			if err := waitCacheGate(releaseOlder, "older independent retry after E1 expiry"); err != nil {
				return "", err
			}
			return retry, nil
		}
		return "", fmt.Errorf("unexpected expired-newer lookup %d", n)
	}}
	var mu sync.Mutex
	var gets []string
	bad := &closeProbe{text: "old expired URL rejected"}
	b := &YTDLP{Runner: runner, CacheTTL: ttl,
		Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			url := req.URL.String()
			if req.Method != http.MethodGet || req.Header.Get("Range") != statusOrderRange {
				return nil, fmt.Errorf("unexpected %s %s Range %q", req.Method, url, req.Header.Get("Range"))
			}
			mu.Lock()
			gets = append(gets, url)
			mu.Unlock()
			switch url {
			case old:
				close(oldGET)
				if err := waitCacheGate(releaseOld, "E0 GET held through E1 expiry"); err != nil {
					return nil, err
				}
				return statusContractBad(req, bad), nil
			case newer:
				return statusContractMedia(t, req, http.StatusPartialContent, "e1"), nil
			case ordinary:
				return statusContractMedia(t, req, http.StatusPartialContent, "of"), nil
			case retry:
				return statusContractMedia(t, req, http.StatusPartialContent, "lr"), nil
			}
			return nil, fmt.Errorf("unexpected expired-newer GET %s", url)
		})}}
	start := func() <-chan proxyOutcome {
		done := make(chan proxyOutcome, 1)
		go func() { done <- runStatusProxy(b, testTrack(), statusOrderRange) }()
		return done
	}
	waitTTL := func() {
		t.Helper()
		timer := time.NewTimer(ttl + 50*time.Millisecond)
		defer timer.Stop()
		awaitDisplaced(t, timer.C, "published URL TTL expiry")
	}
	oldDone := start()
	awaitDisplaced(t, oldGET, "E0 GET before expiry")
	waitTTL()
	requireProxyOutcome(t, awaitDisplaced(t, start(), "E1 publication while E0 GET held"), http.StatusPartialContent, "e1", "bytes 1-2/5")
	waitTTL()
	ordinaryDone := start()
	awaitDisplaced(t, ordinaryLookup, "ordinary F parked after E1 expiry")
	closeIfOpen(releaseOld)
	awaitDisplaced(t, olderLookup, "E0's independent post-expiry status lookup")
	closeIfOpen(releaseOlder)
	// Await the public retry response, not merely runner return: publication
	// has been processed before this successful GET and F is still parked.
	requireProxyOutcome(t, awaitDisplaced(t, oldDone, "older retry finishes before ordinary F release"), http.StatusPartialContent, "lr", "bytes 1-2/5")
	select {
	case got := <-ordinaryDone:
		t.Fatalf("ordinary F returned before release: %+v", got)
	default:
	}
	closeIfOpen(releaseOrdinary)
	requireProxyOutcome(t, awaitDisplaced(t, ordinaryDone, "ordinary F's own local result"), http.StatusPartialContent, "of", "bytes 1-2/5")
	for i := 0; i < 2; i++ {
		requireProxyOutcome(t, awaitDisplaced(t, start(), "older retry reused after ordinary F completion"), http.StatusPartialContent, "lr", "bytes 1-2/5")
	}
	mu.Lock()
	observed := append([]string(nil), gets...)
	mu.Unlock()
	requireStatusCalls(t, observed, []string{old, newer, retry, ordinary, retry, retry}, runner.calls.Load(), 4)
	requireRejectedBodies(t, []*closeProbe{bad})
}

// qmix#292: an older GET may fail after E1 publishes. Its read-only retry
// can become the next cached URL only if E1 expires with no intervening
// publication or status owner while the older lookup is in progress.
func TestOlderRetryAfterNewerExpiryWithoutInterveningPublication(t *testing.T) {
	for _, intervening := range []bool{false, true} {
		name := "newer_expires"
		if intervening {
			name = "another_publication_then_expiry"
		}
		t.Run(name, func(t *testing.T) {
			const ttl = 140 * time.Millisecond
			urls := []string{
				"https://media.example/e0", "https://media.example/e1",
				"https://media.example/older-retry", "https://media.example/e2",
				"https://media.example/e3",
			}
			oldGET, releaseOld := make(chan struct{}), make(chan struct{})
			olderLookup, releaseOlder := make(chan struct{}), make(chan struct{})
			t.Cleanup(func() { closeIfOpen(releaseOld); closeIfOpen(releaseOlder) })
			runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
				if n > len(urls) {
					return "", fmt.Errorf("unexpected lookup %d", n)
				}
				if n == 3 {
					close(olderLookup)
					if err := waitCacheGate(releaseOlder, "older retry"); err != nil {
						return "", err
					}
				}
				return urls[n-1], nil
			}}
			b := &YTDLP{Runner: runner, CacheTTL: ttl,
				Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
					if req.URL.String() == urls[0] {
						close(oldGET)
						if err := waitCacheGate(releaseOld, "old GET"); err != nil {
							return nil, err
						}
						return statusContractResponse(req, http.StatusForbidden), nil
					}
					return statusContractResponse(req, http.StatusOK), nil
				})}}
			track := testTrack()
			oldDone := startCacheLookup(b, context.Background(), track)
			awaitDisplaced(t, oldGET, "old media GET")
			waitTTL := func() {
				t.Helper()
				timer := time.NewTimer(ttl + 30*time.Millisecond)
				defer timer.Stop()
				awaitDisplaced(t, timer.C, "URL expiration")
			}
			waitTTL()
			requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "E1 publication"), urls[1])
			closeIfOpen(releaseOld)
			awaitDisplaced(t, olderLookup, "old GET's lookup")
			waitTTL()
			if intervening {
				requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "E2 publication"), urls[3])
				waitTTL()
			}
			closeIfOpen(releaseOlder)
			requireStatusURL(t, awaitDisplaced(t, oldDone, "old retry response"), urls[2])
			want, searches := urls[2], int32(3)
			if intervening {
				want, searches = urls[4], 5
			}
			for i := 0; i < 2; i++ {
				requireStatusURL(t, awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "next cached publication"), want)
			}
			if got := runner.calls.Load(); got != searches {
				t.Fatalf("searches = %d, want %d", got, searches)
			}
		})
	}
}
