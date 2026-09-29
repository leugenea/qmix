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

// qmix#288: cancellation after the bad GET has begun but before status
// admission must not evict the still-current URL or start a retry lookup.
func TestStatusCanceledBeforeAdmissionKeepsCurrentURL(t *testing.T) {
	const old = "https://media.example/old"
	badEntered, releaseBad := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseBad) })
	var reject atomic.Bool
	var hits atomic.Int32
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		if n != 1 {
			return "", fmt.Errorf("unexpected search %d", n)
		}
		return old, nil
	}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.String() != old {
			return nil, fmt.Errorf("unexpected media URL %s", req.URL)
		}
		hits.Add(1)
		if reject.Load() {
			close(badEntered)
			if err := waitCacheGate(releaseBad, "rejected GET release"); err != nil {
				return nil, err
			}
			return statusContractResponse(req, http.StatusForbidden), nil
		}
		return statusContractResponse(req, http.StatusOK), nil
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	if got := awaitDisplaced(t, startCacheLookup(backend, context.Background(), track), "primed URL"); got.err != nil || got.url != old {
		t.Fatalf("prime = (%q, %v), want old URL", got.url, got.err)
	}
	reject.Store(true)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	canceled := startCacheLookup(backend, ctx, track)
	awaitDisplaced(t, badEntered, "bad GET before cancellation")
	cancel()
	closeIfOpen(releaseBad)
	if got := awaitDisplaced(t, canceled, "canceled status request"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("canceled status = (%q, %v), want context.Canceled", got.url, got.err)
	}
	reject.Store(false)
	if got := awaitDisplaced(t, startCacheLookup(backend, context.Background(), track), "unchanged cached URL"); got.err != nil || got.url != old {
		t.Fatalf("cache after canceled status = (%q, %v), want old URL", got.url, got.err)
	}
	if runner.calls.Load() != 1 || hits.Load() != 3 {
		t.Fatalf("searches %d, media GETs %d; want 1 and 3", runner.calls.Load(), hits.Load())
	}
}

// qmix#288 / qmix#89: two failed GETs of one generation join one
// status lookup; canceling either participant leaves its survivor able to
// publish and reuse the replacement. Done is queried by waitForFlight only
// after flightLocked has attached the caller, while the detached loader uses
// context.WithoutCancel(ctx), not this wrapper (cache.go).
func TestStatusSharedRefreshSurvivesOneCancellation(t *testing.T) {
	for _, cancelFirstRequest := range []bool{false, true} {
		name := "second_request"
		if cancelFirstRequest {
			name = "first_request"
		}
		t.Run(name, func(t *testing.T) {
			const old = "https://media.example/old"
			const fresh = "https://media.example/fresh"
			badGETs := make(chan struct{}, 2)
			releaseBad, refreshStarted, releaseRefresh := make(chan struct{}), make(chan struct{}), make(chan struct{})
			t.Cleanup(func() { closeIfOpen(releaseBad); closeIfOpen(releaseRefresh) })
			var reject atomic.Bool
			var hits atomic.Int32
			runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
				switch n {
				case 1:
					return old, nil
				case 2:
					close(refreshStarted)
					select {
					case <-releaseRefresh:
						return fresh, nil
					case <-ctx.Done():
						return "", ctx.Err()
					case <-time.After(3 * time.Second):
						return "", errors.New("status runner release timed out")
					}
				default:
					return "", fmt.Errorf("unexpected search %d", n)
				}
			}}
			backend := newStatusWaiterBackend(t, fresh, runner, badGETs, releaseBad, &reject, &hits)
			track := testTrack()
			requireStatusURL(t, awaitDisplaced(t, startCacheLookup(backend, context.Background(), track), "primed status generation"), old)
			reject.Store(true)
			firstCtx, cancelFirst := context.WithCancel(context.Background())
			secondCtx, cancelSecond := context.WithCancel(context.Background())
			t.Cleanup(cancelFirst)
			t.Cleanup(cancelSecond)
			firstObserved, secondObserved := observeCacheWait(firstCtx), observeCacheWait(secondCtx)
			first := startCacheLookup(backend, firstObserved, track)
			second := startCacheLookup(backend, secondObserved, track)
			awaitDisplaced(t, badGETs, "first failed media GET")
			awaitDisplaced(t, badGETs, "second failed media GET")
			closeIfOpen(releaseBad)
			awaitDisplaced(t, refreshStarted, "shared status runner")
			awaitDisplaced(t, firstObserved.waiting, "first request admitted to status flight")
			awaitDisplaced(t, secondObserved.waiting, "second request admitted to status flight")
			canceled, survivor := second, first
			if cancelFirstRequest {
				cancelFirst()
				canceled, survivor = first, second
			} else {
				cancelSecond()
			}
			if got := awaitDisplaced(t, canceled, "canceled status waiter"); !errors.Is(got.err, context.Canceled) {
				t.Fatalf("canceled waiter = (%q, %v)", got.url, got.err)
			}
			if runner.calls.Load() != 2 {
				t.Fatalf("searches while survivor waits = %d, want 2", runner.calls.Load())
			}
			closeIfOpen(releaseRefresh)
			requireStatusURL(t, awaitDisplaced(t, survivor, "surviving status waiter"), fresh)
			requireStatusURL(t, awaitDisplaced(t, startCacheLookup(backend, context.Background(), track), "reused survivor URL"), fresh)
			if runner.calls.Load() != 2 || hits.Load() != 5 {
				t.Fatalf("searches %d, media GETs %d; want 2 and 5", runner.calls.Load(), hits.Load())
			}
		})
	}
}

// qmix#288 / qmix#89: the last departing status waiter cancels the
// shared search and a later request starts a new cacheable lookup.
func TestStatusLastWaiterCancellationAllowsFreshLookup(t *testing.T) {
	const old = "https://media.example/old"
	const recovered = "https://media.example/recovered"
	badGETs := make(chan struct{}, 2)
	releaseBad, refreshStarted, stopped, releaseRefresh := make(chan struct{}), make(chan struct{}), make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(releaseBad); closeIfOpen(releaseRefresh) })
	var reject atomic.Bool
	var hits atomic.Int32
	runner := &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			close(refreshStarted)
			select {
			case <-ctx.Done():
				close(stopped)
				return "", ctx.Err()
			case <-releaseRefresh:
				return "https://media.example/abandoned", nil
			case <-time.After(3 * time.Second):
				return "", errors.New("status runner cancellation timed out")
			}
		case 3:
			return recovered, nil
		default:
			return "", fmt.Errorf("unexpected search %d", n)
		}
	}}
	backend := newStatusWaiterBackend(t, recovered, runner, badGETs, releaseBad, &reject, &hits)
	track := testTrack()
	if got := awaitDisplaced(t, startCacheLookup(backend, context.Background(), track), "primed URL"); got.err != nil || got.url != old {
		t.Fatalf("prime = (%q, %v)", got.url, got.err)
	}
	reject.Store(true)
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	secondCtx, cancelSecond := context.WithCancel(context.Background())
	t.Cleanup(cancelFirst)
	t.Cleanup(cancelSecond)
	firstObserved, secondObserved := observeCacheWait(firstCtx), observeCacheWait(secondCtx)
	first := startCacheLookup(backend, firstObserved, track)
	second := startCacheLookup(backend, secondObserved, track)
	awaitDisplaced(t, badGETs, "first bad GET")
	awaitDisplaced(t, badGETs, "second bad GET")
	closeIfOpen(releaseBad)
	awaitDisplaced(t, refreshStarted, "shared status lookup")
	awaitDisplaced(t, firstObserved.waiting, "first status flight admission")
	awaitDisplaced(t, secondObserved.waiting, "second status flight admission")
	cancelFirst()
	if got := awaitDisplaced(t, first, "first cancellation"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("first cancellation = (%q, %v)", got.url, got.err)
	}
	cancelSecond()
	if got := awaitDisplaced(t, second, "final cancellation"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("final cancellation = (%q, %v)", got.url, got.err)
	}
	awaitDisplaced(t, stopped, "status runner stopped by last waiter")
	for i := 0; i < 2; i++ {
		got := awaitDisplaced(t, startCacheLookup(backend, context.Background(), track), "fresh lookup and reuse after cancellation")
		if got.err != nil || got.url != recovered {
			t.Fatalf("recovery %d = (%q, %v), want recovered", i, got.url, got.err)
		}
	}
	if runner.calls.Load() != 3 || hits.Load() != 5 {
		t.Fatalf("searches %d, media GETs %d; want 3 and 5", runner.calls.Load(), hits.Load())
	}
}

// qmix#288: a failed status lookup releases the key for a later ordinary
// search, and that later success is actually cached rather than retried again.
func TestStatusLookupFailureAllowsCachedRecovery(t *testing.T) {
	const old = "https://media.example/old"
	const recovered = "https://media.example/recovered"
	var reject atomic.Bool
	var hits atomic.Int32
	runner := &lookupSequence{onCall: func(_ context.Context, n int) (string, error) {
		switch n {
		case 1:
			return old, nil
		case 2:
			return "", errors.New("status lookup failed")
		case 3:
			return recovered, nil
		default:
			return "", fmt.Errorf("unexpected search %d", n)
		}
	}}
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		switch req.URL.String() {
		case old:
			if reject.Load() {
				return statusContractResponse(req, http.StatusForbidden), nil
			}
			return statusContractResponse(req, http.StatusOK), nil
		case recovered:
			return statusContractResponse(req, http.StatusOK), nil
		default:
			return nil, fmt.Errorf("unexpected media URL %s", req.URL)
		}
	})}
	backend := &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
	track := testTrack()
	if got := serveProxy(t, backend, track, ""); got.Code != http.StatusOK || got.Body.String() != old {
		t.Fatalf("prime = %d %q", got.Code, got.Body.String())
	}
	reject.Store(true)
	failed := serveProxy(t, backend, track, "")
	if failed.Code != http.StatusBadGateway || failed.Body.String() != `{"error":"upstream_failure","message":"audio service is temporarily unavailable"}` {
		t.Fatalf("failed lookup response = %d %q", failed.Code, failed.Body.String())
	}
	for i := 0; i < 2; i++ {
		got := serveProxy(t, backend, track, "")
		if got.Code != http.StatusOK || got.Body.String() != recovered {
			t.Fatalf("recovery %d = %d %q", i, got.Code, got.Body.String())
		}
	}
	if runner.calls.Load() != 3 || hits.Load() != 4 {
		t.Fatalf("searches %d, media GETs %d; want 3 and 4", runner.calls.Load(), hits.Load())
	}
}

// Only the known old and replacement URLs are valid for the two waiter tests.
// Both rejected GETs must enter their gate before either status decision runs.
func newStatusWaiterBackend(t *testing.T, resultURL string, runner Runner, badGETs chan struct{}, releaseBad <-chan struct{}, reject *atomic.Bool, hits *atomic.Int32) *YTDLP {
	t.Helper()
	const old = "https://media.example/old"
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		hits.Add(1)
		switch req.URL.String() {
		case old:
			if reject.Load() {
				select {
				case badGETs <- struct{}{}:
				case <-time.After(3 * time.Second):
					return nil, errors.New("bad GET admission timed out")
				}
				if err := waitCacheGate(releaseBad, "rejected GET release"); err != nil {
					return nil, err
				}
				return statusContractResponse(req, http.StatusForbidden), nil
			}
			return statusContractResponse(req, http.StatusOK), nil
		case resultURL:
			return statusContractResponse(req, http.StatusOK), nil
		default:
			return nil, fmt.Errorf("unexpected media URL %s", req.URL)
		}
	})}
	return &YTDLP{Runner: runner, Client: client, CacheTTL: time.Minute}
}

func requireStatusURL(t *testing.T, got cacheLookup, want string) {
	t.Helper()
	if got.err != nil || got.url != want {
		t.Fatalf("stream = (%q, %v), want URL %q", got.url, got.err, want)
	}
}

func statusContractResponse(req *http.Request, status int) *http.Response {
	body := req.URL.String()
	if status != http.StatusOK {
		body = "rejected"
	}
	return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": {"audio/webm"}},
		Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body)), Request: req}
}
