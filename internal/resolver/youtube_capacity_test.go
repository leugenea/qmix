package resolver

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// gateRunner blocks every metadata subprocess until the test opens the gate,
// and reports each concurrent entry so the limiter bound is observable.
type gateRunner struct {
	entered     chan struct{}
	release     chan struct{}
	releaseOnce sync.Once
	cur         atomic.Int32
	peak        atomic.Int32
	calls       atomic.Int32
}

func newGateRunner(entries int) *gateRunner {
	return &gateRunner{entered: make(chan struct{}, entries), release: make(chan struct{})}
}

func (r *gateRunner) Run(ctx context.Context, _ string) ([]byte, error) {
	r.calls.Add(1)
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
		return []byte(`{"title":"Capacity Song","artist":"Capacity Artist","duration":42}`), nil
	case <-ctx.Done():
		r.cur.Add(-1)
		return nil, ctx.Err()
	}
}

func (r *gateRunner) releaseAll() {
	r.releaseOnce.Do(func() { close(r.release) })
}

// resolveWithin runs y.Resolve in a goroutine and returns the result channel.
func resolveWithin(y *YouTube, rawurl string) chan error {
	done := make(chan error, 1)
	go func() {
		_, err := y.Resolve(context.Background(), rawurl)
		done <- err
	}()
	return done
}

// receiveOne receives one value from ch within a bounded window, failing the
// test instead of hanging. A lost wakeup surfaces as a fast timeout rather
// than a hung binary.
func receiveOne[T any](t *testing.T, ch <-chan T) T {
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

// TestYouTubeResolveRespectsSharedCapacity proves the resolver's yt-dlp call
// holds exactly one shared capacity slot for the subprocess duration: with
// max=1, a second concurrent Resolve waits, and both complete in FIFO order
// after the gate opens (qmix#130).
func TestYouTubeResolveRespectsSharedCapacity(t *testing.T) {
	l := ytdlpcap.New(1, 8)
	r := newGateRunner(4)
	y := &YouTube{Runner: r, Limiter: l}

	first := resolveWithin(y, "https://www.youtube.com/watch?v=cap-1")
	receiveOne(t, r.entered)

	second := resolveWithin(y, "https://www.youtube.com/watch?v=cap-2")
	select {
	case <-r.entered:
		l.Release()
		close(r.release)
		<-first
		<-second
		t.Fatal("second metadata subprocess ran without a free capacity slot (qmix#130)")
	case <-time.After(300 * time.Millisecond):
	}

	r.releaseAll()
	if err := <-first; err != nil {
		t.Fatalf("first resolve: %v", err)
	}
	if err := <-second; err != nil {
		t.Fatalf("second resolve: %v", err)
	}
	if got := r.peak.Load(); got != 1 {
		t.Fatalf("peak concurrent subprocesses = %d, want 1 (qmix#130)", got)
	}
	if calls := r.calls.Load(); calls != 2 {
		t.Fatalf("runner calls = %d, want 2", calls)
	}
}

// TestYouTubeResolveOverloadedRejectsWithoutSubprocess pins the qmix#130
// overload contract for metadata resolution: when the shared limiter's queue
// is full, Resolve fails fast with the typed capacity error, and no
// subprocess starts.
func TestYouTubeResolveOverloadedRejectsWithoutSubprocess(t *testing.T) {
	l := ytdlpcap.New(1, 1)
	r := newGateRunner(4)
	y := &YouTube{Runner: r, Limiter: l}

	running := resolveWithin(y, "https://www.youtube.com/watch?v=cap-1")
	receiveOne(t, r.entered)

	// Fill the single wait-queue slot deterministically.
	queued := resolveWithin(y, "https://www.youtube.com/watch?v=cap-2")
	deadline := time.Now().Add(2 * time.Second)
	for {
		_, queuedCount := l.Stats()
		if queuedCount == 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("second resolve never queued for capacity")
		}
		time.Sleep(time.Millisecond)
	}

	_, err := y.Resolve(context.Background(), "https://www.youtube.com/watch?v=cap-3")
	if !errors.Is(err, ytdlpcap.ErrOverloaded) {
		t.Fatalf("overloaded resolve error = %v, want ytdlpcap.ErrOverloaded (qmix#130)", err)
	}
	if calls := r.calls.Load(); calls != 1 {
		t.Fatalf("runner calls = %d, want 1; overload must not start a subprocess", calls)
	}

	r.releaseAll()
	if err := <-running; err != nil {
		t.Fatalf("running resolve: %v", err)
	}
	if err := <-queued; err != nil {
		t.Fatalf("queued resolve: %v", err)
	}
}

// TestYouTubeResolveCanceledWhileQueuedReleasesSlot proves a canceled request
// leaves the capacity wait queue promptly and the slot accounting stays leak
// free: subsequent resolves still acquire capacity (qmix#130).
func TestYouTubeResolveCanceledWhileQueuedReleasesSlot(t *testing.T) {
	l := ytdlpcap.New(1, 8)
	r := newGateRunner(4)
	y := &YouTube{Runner: r, Limiter: l}

	running := resolveWithin(y, "https://www.youtube.com/watch?v=cap-1")
	receiveOne(t, r.entered)

	ctx, cancel := context.WithCancel(context.Background())
	canceled := make(chan error, 1)
	go func() {
		_, err := y.Resolve(ctx, "https://www.youtube.com/watch?v=cap-2")
		canceled <- err
	}()
	deadline := time.Now().Add(2 * time.Second)
	for {
		_, queued := l.Stats()
		if queued == 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("canceled resolve never queued for capacity")
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	if err := receiveOne(t, canceled); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled resolve error = %v, want context.Canceled", err)
	}

	// Capacity accounting must not leak: the queued caller is gone but the
	// running slot still belongs to the first resolve.
	runningCount, queuedCount := l.Stats()
	if runningCount != 1 || queuedCount != 0 {
		t.Fatalf("stats after canceled waiter = running %d, queued %d, want 1, 0", runningCount, queuedCount)
	}

	r.releaseAll()
	if err := <-running; err != nil {
		t.Fatalf("running resolve: %v", err)
	}
	next := resolveWithin(y, "https://www.youtube.com/watch?v=cap-3")
	receiveOne(t, r.entered)
	r.releaseAll()
	if err := <-next; err != nil {
		t.Fatalf("resolve after canceled waiter: %v (capacity slot leaked?)", err)
	}
}

// TestYouTubeNilLimiterKeepsUnboundedBehavior pins the backwards-compatibility
// contract: a nil limiter keeps the pre-#130 unbounded behavior so existing
// direct-construction tests keep passing.
func TestYouTubeNilLimiterKeepsUnboundedBehavior(t *testing.T) {
	r := newGateRunner(4)
	y := &YouTube{Runner: r}
	if y.Limiter != nil {
		t.Fatalf("default Limiter = %v, want nil", y.Limiter)
	}
	first := resolveWithin(y, "https://www.youtube.com/watch?v=cap-1")
	second := resolveWithin(y, "https://www.youtube.com/watch?v=cap-2")
	receiveOne(t, r.entered)
	receiveOne(t, r.entered) // both run concurrently without a limiter
	r.releaseAll()
	if err := <-first; err != nil {
		t.Fatalf("first resolve: %v", err)
	}
	if err := <-second; err != nil {
		t.Fatalf("second resolve: %v", err)
	}
	if got := r.peak.Load(); got != 2 {
		t.Fatalf("peak concurrent subprocesses = %d, want 2 without a limiter", got)
	}
}
