// Capacity tests pin the qmix#130 stream-side contract: the yt-dlp search
// holds one shared capacity slot for exactly the subprocess duration,
// overload fails fast with the typed error, canceled requests leave the wait
// queue promptly without leaking slots (including through the singleflight
// cache's shared load), and the audio HTTP fetch is never gated.
package stream

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// gateSearchRunner blocks every search subprocess until the test opens the
// gate and reports each concurrent entry. When blockInput is set, only that
// yt-dlp input parks; every other input resolves immediately, so one runner
// can serve both a cached-prime lookup and a parked blocking search.
type gateSearchRunner struct {
	entered     chan struct{}
	release     chan struct{}
	releaseOnce sync.Once
	blockInput  string
	audioURL    string
	cur         atomic.Int32
	peak        atomic.Int32
	calls       atomic.Int32
}

func newGateSearchRunner(entries int) *gateSearchRunner {
	return &gateSearchRunner{entered: make(chan struct{}, entries), release: make(chan struct{}), audioURL: "https://media.example/audio.webm"}
}

func (r *gateSearchRunner) Search(ctx context.Context, input string) ([]byte, error) {
	r.calls.Add(1)
	if r.blockInput != "" && input != r.blockInput {
		return []byte(`{"title":"Some Song","url":"` + r.audioURL + `"}`), nil
	}
	cur := r.cur.Add(1)
	for {
		peak := r.peak.Load()
		if cur <= peak || r.peak.CompareAndSwap(peak, cur) {
			break
		}
	}
	r.entered <- struct{}{}
	select {
	case <-r.release:
		r.cur.Add(-1)
		return []byte(`{"title":"Some Song","url":"` + r.audioURL + `"}`), nil
	case <-ctx.Done():
		r.cur.Add(-1)
		return nil, ctx.Err()
	}
}

func (r *gateSearchRunner) releaseAll() {
	r.releaseOnce.Do(func() { close(r.release) })
}

// capReceive receives one value from ch within a bounded window, failing the
// test instead of hanging.
func capReceive[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for test synchronization")
		var zero T
		return zero
	}
}

// capWaitForQueue polls (bounded) until the limiter reports want queued
// waiters, so goroutine start order never decides the test outcome.
func capWaitForQueue(t *testing.T, l *ytdlpcap.Limiter, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		_, queued := l.Stats()
		if queued == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("queued waiters never reached %d", want)
		}
		time.Sleep(time.Millisecond)
	}
}

// TestYtdlpSearchRespectsSharedCapacity proves the stream search holds one
// shared capacity slot for the subprocess duration: with max=1, a second
// concurrent search waits, and both complete in FIFO order after the gate
// opens (qmix#130).
func TestYtdlpSearchRespectsSharedCapacity(t *testing.T) {
	l := ytdlpcap.New(1, 8)
	r := newGateSearchRunner(4)
	b := &YTDLP{Runner: r, CacheTTL: -1, Limiter: l}

	first := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "1", Title: "first"})
		first <- err
	}()
	capReceive(t, r.entered)

	second := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "2", Title: "second"})
		second <- err
	}()
	select {
	case <-r.entered:
		r.releaseAll()
		<-first
		<-second
		t.Fatal("second search subprocess ran without a free capacity slot (qmix#130)")
	case <-time.After(300 * time.Millisecond):
	}

	r.releaseAll()
	if err := <-first; err != nil {
		t.Fatalf("first search: %v", err)
	}
	if err := <-second; err != nil {
		t.Fatalf("second search: %v", err)
	}
	if got := r.peak.Load(); got != 1 {
		t.Fatalf("peak concurrent subprocesses = %d, want 1 (qmix#130)", got)
	}
	if calls := r.calls.Load(); calls != 2 {
		t.Fatalf("runner calls = %d, want 2", calls)
	}
}

// TestYtdlpSearchOverloadedRejectsWithoutSubprocess pins the qmix#130 overload
// contract for streaming: when the shared limiter's queue is full, resolveURL
// fails fast with the typed capacity error and no subprocess starts.
func TestYtdlpSearchOverloadedRejectsWithoutSubprocess(t *testing.T) {
	l := ytdlpcap.New(1, 1)
	r := newGateSearchRunner(4)
	b := &YTDLP{Runner: r, CacheTTL: -1, Limiter: l}

	running := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "1", Title: "running"})
		running <- err
	}()
	capReceive(t, r.entered)

	queued := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "2", Title: "queued"})
		queued <- err
	}()
	capWaitForQueue(t, l, 1)

	_, err := b.resolveURL(context.Background(), &Track{ID: "3", Title: "overflow"})
	if !errors.Is(err, ytdlpcap.ErrOverloaded) {
		t.Fatalf("overloaded search error = %v, want ytdlpcap.ErrOverloaded (qmix#130)", err)
	}
	if calls := r.calls.Load(); calls != 1 {
		t.Fatalf("runner calls = %d, want 1; overload must not start a subprocess", calls)
	}

	r.releaseAll()
	if err := <-running; err != nil {
		t.Fatalf("running search: %v", err)
	}
	if err := <-queued; err != nil {
		t.Fatalf("queued search: %v", err)
	}
}

// TestYtdlpCanceledSearchLeavesQueueWithoutLeak proves a canceled request
// leaves the capacity wait queue promptly and the accounting stays leak free
// even though the cache may cancel a shared load (qmix#130 + qmix#89).
func TestYtdlpCanceledSearchLeavesQueueWithoutLeak(t *testing.T) {
	l := ytdlpcap.New(1, 8)
	r := newGateSearchRunner(4)
	b := &YTDLP{Runner: r, CacheTTL: -1, Limiter: l}

	running := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "1", Title: "running"})
		running <- err
	}()
	capReceive(t, r.entered)

	ctx, cancel := context.WithCancel(context.Background())
	canceled := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(ctx, &Track{ID: "2", Title: "canceled"})
		canceled <- err
	}()
	capWaitForQueue(t, l, 1)
	cancel()
	if err := capReceive(t, canceled); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled search error = %v, want context.Canceled", err)
	}

	runningCount, queuedCount := l.Stats()
	if runningCount != 1 || queuedCount != 0 {
		t.Fatalf("stats after canceled waiter = running %d, queued %d, want 1, 0", runningCount, queuedCount)
	}

	r.releaseAll()
	if err := <-running; err != nil {
		t.Fatalf("running search: %v", err)
	}
	next := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "3", Title: "next"})
		next <- err
	}()
	capReceive(t, r.entered)
	r.releaseAll()
	if err := <-next; err != nil {
		t.Fatalf("search after canceled waiter: %v (capacity slot leaked?)", err)
	}
}

// TestYtdlpSingleflightCancelDoesNotLeakCapacitySlot pins the qmix#130
// interaction with the #89 cache: when a shared in-flight load holds a
// capacity slot and its final waiter leaves, the load is canceled and the
// slot must be released, so subsequent acquires succeed deterministically.
func TestYtdlpSingleflightCancelDoesNotLeakCapacitySlot(t *testing.T) {
	l := ytdlpcap.New(1, 8)
	r := newGateSearchRunner(4)
	b := &YTDLP{Runner: r, CacheTTL: time.Minute, Limiter: l}
	track := &Track{ID: "1", Title: "Shared Song", Artist: "Shared Artist"}

	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	leaderDone := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(leaderCtx, track)
		leaderDone <- err
	}()
	capReceive(t, r.entered) // the shared load now holds the single slot

	waiterCtx, cancelWaiter := context.WithCancel(context.Background())
	waiterDone := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(waiterCtx, track)
		waiterDone <- err
	}()
	waitForCacheWaiters(t, b.cacheRef(), cacheKey(track), 2)

	// The final waiter leaving cancels the shared load; its capacity slot
	// must come back. With only the leader left, canceling it drops the
	// waiter count to zero and cancels the shared load.
	cancelLeader()
	cancelWaiter() // belt-and-braces: both callers of the shared load leave
	if err := capReceive(t, waiterDone); !errors.Is(err, context.Canceled) {
		t.Fatalf("waiter error = %v, want context.Canceled", err)
	}
	if err := capReceive(t, leaderDone); !errors.Is(err, context.Canceled) {
		t.Fatalf("leader error = %v, want context.Canceled", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for {
		runningCount, queuedCount := l.Stats()
		if runningCount == 0 && queuedCount == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("shared load leaked its capacity slot: running %d, queued %d", runningCount, queuedCount)
		}
		time.Sleep(time.Millisecond)
	}

	// Subsequent acquires succeed: no slot was lost with the canceled load.
	next := make(chan error, 1)
	go func() {
		url, err := b.resolveURL(context.Background(), &Track{ID: "2", Title: "Next Song"})
		if err == nil && url != "https://media.example/audio.webm" {
			err = fmt.Errorf("url = %q, want fixture URL", url)
		}
		next <- err
	}()
	capReceive(t, r.entered)
	r.releaseAll()
	if err := <-next; err != nil {
		t.Fatalf("resolve after canceled shared load: %v (capacity slot leaked?)", err)
	}
}

// TestYtdlpAudioFetchIsNotCapacityGated pins the qmix#130 scope rule: only
// the yt-dlp search subprocess consumes capacity; the audio HTTP GET never
// waits for a slot. With the single slot held by a parked search of another
// track, a cached track must still stream.
func TestYtdlpAudioFetchIsNotCapacityGated(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "audio/webm")
		_, _ = w.Write([]byte("audio-bytes"))
	}))
	t.Cleanup(upstream.Close)

	l := ytdlpcap.New(1, 8)
	r := newGateSearchRunner(4)
	r.blockInput = "https://www.youtube.com/watch?v=other" // only this search parks
	r.audioURL = upstream.URL
	b := &YTDLP{Runner: r, CacheTTL: time.Minute, Limiter: l, Client: upstream.Client()}

	// Prime the cache for the first track while capacity is free. Its search
	// does not park, so it completes immediately.
	cached := &Track{ID: "parked", Title: "Parked Song", URL: "https://www.youtube.com/watch?v=parked", ResolvedBy: "youtube"}
	parkedURL, err := b.resolveURL(context.Background(), cached)
	if err != nil {
		t.Fatalf("prime parked track: %v", err)
	}
	if parkedURL != upstream.URL {
		t.Fatalf("parked url = %q, want %q", parkedURL, upstream.URL)
	}

	// Occupy the single capacity slot with a parked search of another track.
	blocking := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(context.Background(), &Track{ID: "other", Title: "Other Song", URL: r.blockInput, ResolvedBy: "youtube"})
		blocking <- err
	}()
	capReceive(t, r.entered)

	// The cached track's audio GET must not wait for the occupied slot.
	res, err := b.Stream(context.Background(), cached, "")
	if err != nil {
		t.Fatalf("stream of cached track while slot is held: %v", err)
	}
	res.Body.Close()

	r.releaseAll()
	if err := <-blocking; err != nil {
		t.Fatalf("blocked search: %v", err)
	}
}
