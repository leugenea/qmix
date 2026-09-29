package stream

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// qmix#290: an older failed GET's lookup may complete while a newer failed
// generation owns a parked status lookup. Its own response is usable, but it
// cannot become the URL served to another request or replace the newer result.
func TestStatusLaterRefreshPublicationIsObservable(t *testing.T) {
	f := newStatusOrderingFixture(t, false)
	f.startOlderAndPublishNewer(t)
	f.startOlderRefresh(t)
	newerDone := f.startRequest()
	awaitDisplaced(t, f.newerLookup, "newer generation's status lookup")
	closeIfOpen(f.releaseOlderLookup)
	requireProxyOutcome(t, awaitDisplaced(t, f.oldDone, "older completed proxy response"), http.StatusPartialContent, "ol", "bytes 1-2/5")

	// The ordinary caller must have entered the wait while the new lookup is
	// still parked; a goroutine launch alone would not prove this ordering.
	observed := observeCacheWait(context.Background())
	ordinaryDone := make(chan proxyOutcome, 1)
	go func() { ordinaryDone <- runStatusProxyWithContext(f.backend, observed, f.track) }()
	select {
	case <-observed.waiting:
	case got := <-ordinaryDone:
		t.Fatalf("ordinary request bypassed parked newer lookup: %+v", got)
	case <-time.After(3 * time.Second):
		t.Fatal("ordinary request did not reach the newer flight")
	}
	select {
	case got := <-ordinaryDone:
		t.Fatalf("ordinary request returned before newer lookup: %+v", got)
	default:
	}
	if got := f.runner.calls.Load(); got != 4 {
		t.Fatalf("searches while newer lookup parked = %d, want 4", got)
	}
	closeIfOpen(f.releaseNewerLookup)
	requireProxyOutcome(t, awaitDisplaced(t, newerDone, "newer completed proxy response"), http.StatusPartialContent, "nw", "bytes 1-2/5")
	requireProxyOutcome(t, awaitDisplaced(t, ordinaryDone, "ordinary request's newer URL"), http.StatusPartialContent, "nw", "bytes 1-2/5")
	requireProxyOutcome(t, awaitDisplaced(t, f.startRequest(), "reused newer URL"), http.StatusPartialContent, "nw", "bytes 1-2/5")
	f.assertCalls(t, []string{statusOrderOld, statusOrderNewer, statusOrderNewer, statusOrderOlderRefresh, statusOrderNewerRefresh, statusOrderNewerRefresh, statusOrderNewerRefresh}, 4)
}

// qmix#290: terminal rejection of the newer generation must also retire an
// older lookup which finishes only AFTER the safe 502 has reached its caller.
// The older caller can use its own result, but the next request has to search.
func TestStatusTerminalRetiresOlderFlightObservable(t *testing.T) {
	f := newStatusOrderingFixture(t, true)
	f.startOlderAndPublishNewer(t)
	f.startOlderRefresh(t)
	newerDone := f.startRequest()
	requireProxyFailure(t, awaitDisplaced(t, newerDone, "newer terminal rejection"))
	if got := f.runner.calls.Load(); got != 4 {
		t.Fatalf("searches through terminal rejection = %d, want 4", got)
	}
	closeIfOpen(f.releaseOlderLookup)
	requireProxyOutcome(t, awaitDisplaced(t, f.oldDone, "older completed proxy response after terminal rejection"), http.StatusPartialContent, "ol", "bytes 1-2/5")
	requireProxyOutcome(t, awaitDisplaced(t, f.startRequest(), "ordinary recovery after terminal failure"), http.StatusPartialContent, "rc", "bytes 1-2/5")
	requireProxyOutcome(t, awaitDisplaced(t, f.startRequest(), "reused recovery URL"), http.StatusPartialContent, "rc", "bytes 1-2/5")
	f.assertCalls(t, []string{statusOrderOld, statusOrderNewer, statusOrderNewer, statusOrderNewerRefresh, statusOrderOlderRefresh, statusOrderRecovered, statusOrderRecovered}, 5)
}

func runStatusProxyWithContext(b *YTDLP, ctx context.Context, track *Track) proxyOutcome {
	req := httptest.NewRequest(http.MethodGet, "/stream", nil).WithContext(ctx)
	req.Header.Set("Range", statusOrderRange)
	rec := httptest.NewRecorder()
	err := ServeStream(rec, req, b, track)
	return proxyOutcome{code: rec.Code, body: rec.Body.String(), contentRange: rec.Header().Get("Content-Range"), err: err}
}

const (
	statusOrderRange        = "bytes=1-2"
	statusOrderOld          = "https://media.example/order-old"
	statusOrderNewer        = "https://media.example/order-newer"
	statusOrderOlderRefresh = "https://media.example/order-older-refresh"
	statusOrderNewerRefresh = "https://media.example/order-newer-refresh"
	statusOrderRecovered    = "https://media.example/order-recovered"
)

type statusOrderingFixture struct {
	backend            *YTDLP
	track              *Track
	runner             *lookupSequence
	oldDone            chan proxyOutcome
	oldGET             chan struct{}
	olderLookup        chan struct{}
	newerLookup        chan struct{}
	releaseOldGET      chan struct{}
	releaseOlderLookup chan struct{}
	releaseNewerLookup chan struct{}
	bad                []*closeProbe
	mu                 sync.Mutex
	gets               []string
	newerGETs          atomic.Int32
	terminal           bool
}

func newStatusOrderingFixture(t *testing.T, terminal bool) *statusOrderingFixture {
	f := &statusOrderingFixture{
		track: testTrack(), oldDone: make(chan proxyOutcome, 1), oldGET: make(chan struct{}),
		olderLookup: make(chan struct{}), newerLookup: make(chan struct{}),
		releaseOldGET: make(chan struct{}), releaseOlderLookup: make(chan struct{}), releaseNewerLookup: make(chan struct{}),
		bad: []*closeProbe{{text: "old rejected"}, {text: "newer rejected"}, {text: "terminal retry rejected"}}, terminal: terminal,
	}
	t.Cleanup(func() {
		closeIfOpen(f.releaseOldGET)
		closeIfOpen(f.releaseOlderLookup)
		closeIfOpen(f.releaseNewerLookup)
	})
	f.runner = &lookupSequence{onCall: func(ctx context.Context, n int) (string, error) {
		switch n {
		case 1:
			return statusOrderOld, nil
		case 2:
			return statusOrderNewer, nil
		case 3:
			close(f.olderLookup)
			if err := waitCacheGate(f.releaseOlderLookup, "older status lookup"); err != nil {
				return "", err
			}
			return statusOrderOlderRefresh, nil
		case 4:
			close(f.newerLookup)
			if !terminal {
				if err := waitCacheGate(f.releaseNewerLookup, "newer status lookup"); err != nil {
					return "", err
				}
			}
			return statusOrderNewerRefresh, nil
		case 5:
			return statusOrderRecovered, nil
		}
		return "", fmt.Errorf("unexpected search %d", n)
	}}
	f.backend = &YTDLP{Runner: f.runner, CacheTTL: 500 * time.Millisecond,
		Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) { return f.media(t, req) })}}
	return f
}

func (f *statusOrderingFixture) media(t *testing.T, req *http.Request) (*http.Response, error) {
	url := req.URL.String()
	if req.Method != http.MethodGet || req.Header.Get("Range") != statusOrderRange {
		return nil, fmt.Errorf("unexpected GET %s Range %q for %s", req.Method, req.Header.Get("Range"), url)
	}
	f.mu.Lock()
	f.gets = append(f.gets, url)
	f.mu.Unlock()
	switch url {
	case statusOrderOld:
		close(f.oldGET)
		if err := waitCacheGate(f.releaseOldGET, "old media GET"); err != nil {
			return nil, err
		}
		return statusContractBad(req, f.bad[0]), nil
	case statusOrderNewer:
		if f.newerGETs.Add(1) == 2 {
			return statusContractBad(req, f.bad[1]), nil
		}
		return statusContractMedia(t, req, http.StatusPartialContent, "e1"), nil
	case statusOrderOlderRefresh:
		return statusContractMedia(t, req, http.StatusPartialContent, "ol"), nil
	case statusOrderNewerRefresh:
		if f.terminal {
			return statusContractBad(req, f.bad[2]), nil
		}
		return statusContractMedia(t, req, http.StatusPartialContent, "nw"), nil
	case statusOrderRecovered:
		return statusContractMedia(t, req, http.StatusPartialContent, "rc"), nil
	}
	return nil, fmt.Errorf("unexpected media URL %s", url)
}

func (f *statusOrderingFixture) startOlderAndPublishNewer(t *testing.T) {
	t.Helper()
	go func() { f.oldDone <- runStatusProxy(f.backend, f.track, statusOrderRange) }()
	awaitDisplaced(t, f.oldGET, "old GET before expiration")
	// TTL expiration is the only timed bound: a public request cannot expire
	// the URL explicitly while its older media GET is parked.
	timer := time.NewTimer(600 * time.Millisecond)
	defer timer.Stop()
	awaitDisplaced(t, timer.C, "old URL TTL")
	requireProxyOutcome(t, awaitDisplaced(t, f.startRequest(), "published newer generation"), http.StatusPartialContent, "e1", "bytes 1-2/5")
}

func (f *statusOrderingFixture) startOlderRefresh(t *testing.T) {
	t.Helper()
	closeIfOpen(f.releaseOldGET)
	awaitDisplaced(t, f.olderLookup, "old failed GET's independent lookup")
}

func (f *statusOrderingFixture) startRequest() <-chan proxyOutcome {
	done := make(chan proxyOutcome, 1)
	go func() { done <- runStatusProxy(f.backend, f.track, statusOrderRange) }()
	return done
}

func (f *statusOrderingFixture) assertCalls(t *testing.T, want []string, searches int32) {
	t.Helper()
	f.mu.Lock()
	got := append([]string(nil), f.gets...)
	f.mu.Unlock()
	if !reflect.DeepEqual(got, want) || f.runner.calls.Load() != searches {
		t.Fatalf("upstream GET URLs = %q, want %q; searches %d, want %d", got, want, f.runner.calls.Load(), searches)
	}
	requireRejectedBodies(t, f.bad[:2])
	if f.terminal {
		requireRejectedBodies(t, f.bad[2:])
	}
}
