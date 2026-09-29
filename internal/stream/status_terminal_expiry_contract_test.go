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

// qmix#292: E1's second GET rejects after expiry and ordinary admission has
// lazily removed E1. The terminal failure still fences the parked ordinary
// lookup: its caller can use the result, but recovery must search and cache.
func TestStatusExpiredTerminalRetiresOrdinaryFlight(t *testing.T) {
	f := newTerminalExpiryFixture(t, false)
	terminal := f.parkTerminal(t)
	ordinary := f.startExpiredOrdinary(t)
	closeIfOpen(f.releaseTerminal)
	requireProxyFailure(t, awaitDisplaced(t, terminal, "expired terminal failure before ordinary completion"))
	closeIfOpen(f.releaseOrdinary)
	requireProxyOutcome(t, awaitDisplaced(t, ordinary, "preterminal ordinary caller's local result"), http.StatusPartialContent, "pf", "bytes 1-2/5")
	for i := 0; i < 2; i++ {
		requireProxyOutcome(t, awaitDisplaced(t, f.startRequest(), "post-terminal recovery and cache reuse"), http.StatusPartialContent, "rc", "bytes 1-2/5")
	}
	f.assertCalls(t, []string{terminalExpiryOld, terminalExpiryRejected, terminalExpiryOrdinary, terminalExpiryRecovered, terminalExpiryRecovered}, 4)
}

// The same expired E1 must not fence a status owner admitted after the
// ordinary flight. Terminal retirement is conditional, not a blanket epoch
// mutation when the entry is nil (nor a new owner reservation).
func TestStatusExpiredTerminalPreservesNewerStatusOwner(t *testing.T) {
	f := newTerminalExpiryFixture(t, true)
	terminal := f.parkTerminal(t)
	newer := f.startRequest()
	awaitDisplaced(t, f.ownerGET, "second caller holding E1 before expiry")
	ordinary := f.startExpiredOrdinary(t)
	closeIfOpen(f.releaseOwnerGET)
	awaitDisplaced(t, f.ownerLookup, "newer status owner parked before terminal rejection")
	closeIfOpen(f.releaseTerminal)
	requireProxyFailure(t, awaitDisplaced(t, terminal, "expired terminal failure with newer owner"))
	closeIfOpen(f.releaseOwnerLookup)
	requireProxyOutcome(t, awaitDisplaced(t, newer, "newer status owner's publication"), http.StatusPartialContent, "nw", "bytes 1-2/5")
	closeIfOpen(f.releaseOrdinary)
	requireProxyOutcome(t, awaitDisplaced(t, ordinary, "displaced ordinary caller's local result"), http.StatusPartialContent, "pf", "bytes 1-2/5")
	for i := 0; i < 2; i++ {
		requireProxyOutcome(t, awaitDisplaced(t, f.startRequest(), "newer owner reused after terminal and ordinary completion"), http.StatusPartialContent, "nw", "bytes 1-2/5")
	}
	f.assertCalls(t, []string{terminalExpiryOld, terminalExpiryRejected, terminalExpiryRejected, terminalExpiryOwner, terminalExpiryOrdinary, terminalExpiryOwner, terminalExpiryOwner}, 4)
}

const (
	terminalExpiryTTL       = 200 * time.Millisecond
	terminalExpiryOld       = "https://media.example/terminal-old"
	terminalExpiryRejected  = "https://media.example/terminal-e1"
	terminalExpiryOrdinary  = "https://media.example/preterminal"
	terminalExpiryRecovered = "https://media.example/terminal-recovered"
	terminalExpiryOwner     = "https://media.example/terminal-owner"
)

type terminalExpiryFixture struct {
	backend            *YTDLP
	runner             *lookupSequence
	terminalGET        chan struct{}
	releaseTerminal    chan struct{}
	ordinaryLookup     chan struct{}
	releaseOrdinary    chan struct{}
	ownerGET           chan struct{}
	releaseOwnerGET    chan struct{}
	ownerLookup        chan struct{}
	releaseOwnerLookup chan struct{}
	newerOwner         bool
	e1GETs             atomic.Int32
	bad                []*closeProbe
	mu                 sync.Mutex
	gets               []string
}

func newTerminalExpiryFixture(t *testing.T, newerOwner bool) *terminalExpiryFixture {
	f := &terminalExpiryFixture{
		terminalGET: make(chan struct{}), releaseTerminal: make(chan struct{}),
		ordinaryLookup: make(chan struct{}), releaseOrdinary: make(chan struct{}),
		ownerGET: make(chan struct{}), releaseOwnerGET: make(chan struct{}),
		ownerLookup: make(chan struct{}), releaseOwnerLookup: make(chan struct{}),
		newerOwner: newerOwner, bad: []*closeProbe{{text: "secret old URL rejected"}, {text: "secret terminal URL rejected"}, {text: "owner GET rejected"}},
	}
	t.Cleanup(func() {
		for _, gate := range []chan struct{}{f.releaseTerminal, f.releaseOrdinary, f.releaseOwnerGET, f.releaseOwnerLookup} {
			closeIfOpen(gate)
		}
	})
	f.runner = &lookupSequence{onCall: f.lookup}
	f.backend = &YTDLP{Runner: f.runner, CacheTTL: terminalExpiryTTL,
		Client: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) { return f.media(t, req) })}}
	return f
}

func (f *terminalExpiryFixture) lookup(_ context.Context, n int) (string, error) {
	switch n {
	case 1:
		return terminalExpiryOld, nil
	case 2:
		return terminalExpiryRejected, nil
	case 3:
		close(f.ordinaryLookup)
		if err := waitCacheGate(f.releaseOrdinary, "preterminal ordinary lookup"); err != nil {
			return "", err
		}
		return terminalExpiryOrdinary, nil
	case 4:
		if f.newerOwner {
			close(f.ownerLookup)
			if err := waitCacheGate(f.releaseOwnerLookup, "newer status owner's lookup"); err != nil {
				return "", err
			}
			return terminalExpiryOwner, nil
		}
		return terminalExpiryRecovered, nil
	}
	return "", fmt.Errorf("unexpected terminal-expiry lookup %d", n)
}

func (f *terminalExpiryFixture) media(t *testing.T, req *http.Request) (*http.Response, error) {
	url := req.URL.String()
	if req.Method != http.MethodGet || req.Header.Get("Range") != statusOrderRange {
		return nil, fmt.Errorf("unexpected %s %s Range %q", req.Method, url, req.Header.Get("Range"))
	}
	f.mu.Lock()
	f.gets = append(f.gets, url)
	f.mu.Unlock()
	switch url {
	case terminalExpiryOld:
		return statusContractBad(req, f.bad[0]), nil
	case terminalExpiryRejected:
		return f.rejectedGET(req)
	case terminalExpiryOrdinary:
		return statusContractMedia(t, req, http.StatusPartialContent, "pf"), nil
	case terminalExpiryRecovered:
		return statusContractMedia(t, req, http.StatusPartialContent, "rc"), nil
	case terminalExpiryOwner:
		return statusContractMedia(t, req, http.StatusPartialContent, "nw"), nil
	}
	return nil, fmt.Errorf("unexpected terminal-expiry URL %s", url)
}

func (f *terminalExpiryFixture) rejectedGET(req *http.Request) (*http.Response, error) {
	n := f.e1GETs.Add(1)
	gate := f.releaseTerminal
	switch n {
	case 1:
		close(f.terminalGET)
	case 2:
		close(f.ownerGET)
		gate = f.releaseOwnerGET
	default:
		return nil, fmt.Errorf("unexpected rejected E1 GET %d", n)
	}
	if err := waitCacheGate(gate, "E1 media GET"); err != nil {
		return nil, err
	}
	return statusContractBad(req, f.bad[n]), nil
}

func (f *terminalExpiryFixture) startRequest() <-chan proxyOutcome {
	done := make(chan proxyOutcome, 1)
	go func() { done <- runStatusProxy(f.backend, testTrack(), statusOrderRange) }()
	return done
}

func (f *terminalExpiryFixture) parkTerminal(t *testing.T) <-chan proxyOutcome {
	t.Helper()
	done := f.startRequest()
	awaitDisplaced(t, f.terminalGET, "published E1's parked second GET")
	return done
}

func (f *terminalExpiryFixture) startExpiredOrdinary(t *testing.T) <-chan proxyOutcome {
	t.Helper()
	// Expiry uses the configured public TTL; all subsequent ordering is gated.
	timer := time.NewTimer(terminalExpiryTTL + 50*time.Millisecond)
	defer timer.Stop()
	awaitDisplaced(t, timer.C, "E1 TTL expiry while terminal GET remains parked")
	done := f.startRequest()
	awaitDisplaced(t, f.ordinaryLookup, "ordinary admission after E1 expiry")
	return done
}

func (f *terminalExpiryFixture) assertCalls(t *testing.T, want []string, searches int32) {
	t.Helper()
	f.mu.Lock()
	got := append([]string(nil), f.gets...)
	f.mu.Unlock()
	requireStatusCalls(t, got, want, f.runner.calls.Load(), searches)
	bodies := f.bad[:2]
	if f.newerOwner {
		bodies = f.bad
	}
	requireRejectedBodies(t, bodies)
}
