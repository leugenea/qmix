// Tests pin the shared yt-dlp capacity limiter contract (qmix#130): a bounded
// FIFO wait queue with context-aware cancellation and typed overload rejection.
// Waits are channel-controlled and bounded, never sleep-synchronized.
package ytdlpcap

import (
	"context"
	"errors"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// within receives one value from ch, failing the test instead of hanging. A
// lost wakeup surfaces as a fast, reproducible timeout rather than a hung
// binary, keeping the tests deterministic.
func within[T any](t *testing.T, what string, ch <-chan T) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		var zero T
		return zero
	}
}

// waitForStats waits until a goroutine has made the expected limiter state
// visible. It yields instead of sleeping, so synchronization is based on the
// state transition itself; the deadline only turns a missing transition into a
// bounded test failure.
func waitForStats(t *testing.T, l *Limiter, wantRunning, wantQueued int) {
	t.Helper()
	deadline := time.NewTimer(2 * time.Second)
	defer deadline.Stop()
	for {
		running, queued := l.Stats()
		if running == wantRunning && queued == wantQueued {
			return
		}
		select {
		case <-deadline.C:
			t.Fatalf("limiter state = running %d, queued %d; want %d, %d", running, queued, wantRunning, wantQueued)
		default:
			runtime.Gosched()
		}
	}
}

// parkedWaiter appends one synthetic waiting caller to l. In-package tests use
// it to arrange a known queue state deterministically instead of racing
// goroutine start order. The ready channel matches the real Acquire waiter:
// one buffered grant.
func parkedWaiter(t *testing.T, l *Limiter) *waiter {
	t.Helper()
	w := &waiter{ready: make(chan struct{}, 1)}
	l.mu.Lock()
	l.waiters = append(l.waiters, w)
	l.mu.Unlock()
	return w
}

// granted reports whether w's slot was handed over already.
func granted(w *waiter) bool {
	select {
	case <-w.ready:
		return true
	default:
		return false
	}
}

// criticalSection records how many holders are inside a fake subprocess at once
// and blocks them on a release gate. It is the deterministic probe for the
// concurrency bound: entries and exits are channel-signaled.
type criticalSection struct {
	entered chan struct{}
	release chan struct{}
	cur     atomic.Int32
	peak    atomic.Int32
}

func newCriticalSection(capacity int) *criticalSection {
	return &criticalSection{entered: make(chan struct{}, capacity), release: make(chan struct{})}
}

func (c *criticalSection) run(ctx context.Context) error {
	cur := c.cur.Add(1)
	for {
		peak := c.peak.Load()
		if cur <= peak || c.peak.CompareAndSwap(peak, cur) {
			break
		}
	}
	c.entered <- struct{}{}
	select {
	case <-c.release:
		c.cur.Add(-1)
		return nil
	case <-ctx.Done():
		c.cur.Add(-1)
		return ctx.Err()
	}
}

func TestNewClampsInvalidArguments(t *testing.T) {
	if l := New(0, 8); l != nil {
		t.Fatalf("New(0, 8) = %v, want nil (no capacity bound)", l)
	}
	if l := New(-1, 8); l != nil {
		t.Fatalf("New(-1, 8) = %v, want nil", l)
	}
	l := New(2, -1)
	if l == nil {
		t.Fatal("New(2, -1) returned nil for a positive max")
	}
	if got := l.QueueLimit(); got != 0 {
		t.Fatalf("QueueLimit() = %d, want 0 (negative queue depth fails closed)", got)
	}
	if got := l.MaxConcurrent(); got != 2 {
		t.Fatalf("MaxConcurrent() = %d, want 2", got)
	}
}

func TestNilLimiterImposesNoCapacityBound(t *testing.T) {
	var l *Limiter
	if got := l.MaxConcurrent(); got != 0 {
		t.Fatalf("nil MaxConcurrent() = %d, want 0", got)
	}
	if got := l.QueueLimit(); got != 0 {
		t.Fatalf("nil QueueLimit() = %d, want 0", got)
	}
	// Holding far more slots than any real limiter would allow proves the nil
	// limiter never blocks or rejects: the pre-#130 behavior tests rely on.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for i := 0; i < 100; i++ {
		if err := l.Acquire(ctx); err != nil {
			t.Fatalf("acquire %d: %v", i, err)
		}
	}
	l.Release()
}

func TestAcquireReturnsImmediatelyWhenCapacityIsFree(t *testing.T) {
	l := New(3, 0)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for i := 0; i < 3; i++ {
		if err := l.Acquire(ctx); err != nil {
			t.Fatalf("acquire %d: %v", i, err)
		}
	}
	if l.MaxConcurrent() != 3 {
		t.Fatalf("MaxConcurrent() = %d, want 3", l.MaxConcurrent())
	}
}

// TestAcquireRejectsBeyondMaxWithoutQueue is the core bound proof (qmix#130):
// synchronous calls have a well-defined order, so the (max+1)th holder can
// only be rejected, never admitted.
func TestAcquireRejectsBeyondMaxWithoutQueue(t *testing.T) {
	l := New(2, 0)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := l.Acquire(ctx); err != nil {
		t.Fatal(err)
	}
	if err := l.Acquire(ctx); err != nil {
		t.Fatal(err)
	}
	err := l.Acquire(ctx)
	if !errors.Is(err, ErrOverloaded) {
		t.Fatalf("third acquire error = %v, want ErrOverloaded", err)
	}
	l.Release()
	if err := l.Acquire(ctx); err != nil {
		t.Fatalf("acquire after release: %v", err)
	}
}

func TestAcquireRejectsWhenWaitQueueIsFull(t *testing.T) {
	l := New(1, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := l.Acquire(ctx); err != nil {
		t.Fatal(err)
	}
	parkedWaiter(t, l) // one caller is already waiting
	err := l.Acquire(ctx)
	if !errors.Is(err, ErrOverloaded) {
		t.Fatalf("acquire over full queue = %v, want ErrOverloaded", err)
	}
}

func TestReleaseAdmitsWaitersInFIFOOrder(t *testing.T) {
	l := New(1, 8)
	if err := l.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	first := parkedWaiter(t, l)
	second := parkedWaiter(t, l)
	third := parkedWaiter(t, l)

	l.Release() // the slot must go to the head of the queue, in order
	if !granted(first) {
		t.Fatal("first waiter was not granted")
	}
	if granted(second) || granted(third) {
		t.Fatal("release admitted a later waiter out of FIFO order")
	}
	l.Release()
	if !granted(second) || granted(third) {
		t.Fatal("second waiter was not granted in FIFO order")
	}
	l.Release()
	if !granted(third) {
		t.Fatal("third waiter was not granted")
	}
	// The granted waiter holds the slot; returning it frees capacity again.
	l.Release()
	if err := l.Acquire(context.Background()); err != nil {
		t.Fatalf("acquire after full drain: %v", err)
	}
}

func TestAcquireContextCancellationLeavesQueuePromptly(t *testing.T) {
	l := New(1, 8)
	if err := l.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	waitCtx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- l.Acquire(waitCtx)
	}()
	// Do not cancel until the caller has joined the queue behind the held slot.
	waitForStats(t, l, 1, 1)
	cancel()
	if err := within(t, "canceled acquire", done); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled acquire error = %v, want context.Canceled", err)
	}
	running, queued := l.Stats()
	if running != 1 || queued != 0 {
		t.Fatalf("state after cancellation = running %d, queued %d; want 1, 0", running, queued)
	}

	// Cancellation removed only the queued caller: the original slot remains
	// held. Once that holder releases, capacity is immediately reusable.
	l.Release()
	ctx, cancelTwo := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancelTwo()
	if err := l.Acquire(ctx); err != nil {
		t.Fatalf("acquire after holder release: %v", err)
	}
	l.Release()
}

func TestAcquireFailsFastForCanceledContext(t *testing.T) {
	l := New(1, 8)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := l.Acquire(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("acquire error = %v, want context.Canceled", err)
	}
	l.mu.Lock()
	queued, running := len(l.waiters), l.running
	l.mu.Unlock()
	if queued != 0 || running != 0 {
		t.Fatalf("state after fail-fast = queued %d, running %d, want 0, 0", queued, running)
	}
}

// TestAbandonedGrantReturnsCapacitySlot pins the grant/cancel race (qmix#130):
// if Release hands a slot to a waiter whose context is canceled at the same
// instant, the waiter that gives up must hand the slot back or the process
// permanently loses capacity.
func TestAbandonedGrantReturnsCapacitySlot(t *testing.T) {
	l := New(1, 8)
	if err := l.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	w := parkedWaiter(t, l)
	l.mu.Lock()
	l.releaseLocked() // grant to w exactly as a concurrent Release would
	l.mu.Unlock()
	if !granted(w) {
		t.Fatal("waiter was not granted")
	}
	l.abandon(w) // the waiter's select observed cancellation instead
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := l.Acquire(ctx); err != nil {
		t.Fatalf("abandoned grant leaked capacity: %v", err)
	}
}

// TestLimiterBoundsPeakConcurrency is the channel-controlled bound proof: with
// max slots and more callers, exactly max enter the critical section while the
// gate is closed, and everyone else waits then succeeds after the gate opens.
func TestLimiterBoundsPeakConcurrency(t *testing.T) {
	const max = 2
	const callers = 6
	l := New(max, callers)
	section := newCriticalSection(callers)
	done := make(chan error, callers)
	for i := 0; i < callers; i++ {
		go func() {
			if err := l.Acquire(context.Background()); err != nil {
				done <- err
				return
			}
			defer l.Release()
			done <- section.run(context.Background())
		}()
	}
	for i := 0; i < max; i++ {
		within(t, "subprocess entry", section.entered)
	}
	close(section.release)
	for i := 0; i < callers; i++ {
		if err := within(t, "caller completion", done); err != nil {
			t.Fatalf("caller failed: %v", err)
		}
	}
	if got := section.peak.Load(); got != max {
		t.Fatalf("peak concurrent subprocesses = %d, want exactly %d (qmix#130)", got, max)
	}
}

func TestLimiterRejectsBeyondCapacityPlusQueue(t *testing.T) {
	l := New(1, 1)
	if err := l.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	parkedWaiter(t, l) // the single queue slot is taken
	err := l.Acquire(context.Background())
	if !errors.Is(err, ErrOverloaded) {
		t.Fatalf("acquire beyond max+queue = %v, want ErrOverloaded", err)
	}
}

// TestReleaseWithoutAcquireDoesNotUnderflow guards the accounting: a stray
// Release must not make the limiter admit more holders than configured.
func TestReleaseWithoutAcquireDoesNotUnderflow(t *testing.T) {
	l := New(1, 0)
	l.Release()
	if err := l.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	err := l.Acquire(context.Background())
	if !errors.Is(err, ErrOverloaded) {
		t.Fatalf("second acquire = %v, want ErrOverloaded after a stray release", err)
	}
}

// TestConcurrentUsageStaysBounded exercises the limiter under the race
// detector: many holders must never exceed max, and a deep queue must absorb
// every caller without rejections or lost slots.
func TestConcurrentUsageStaysBounded(t *testing.T) {
	const max = 4
	l := New(max, 64)
	const goroutines = 24
	const iterations = 20
	var wg sync.WaitGroup
	fail := make(chan error, goroutines)
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < iterations; j++ {
				if err := l.Acquire(context.Background()); err != nil {
					fail <- err
					return
				}
				l.Release()
			}
		}()
	}
	wg.Wait()
	select {
	case err := <-fail:
		t.Fatalf("acquire failed under load: %v", err)
	default:
	}
	l.mu.Lock()
	running, queued := l.running, len(l.waiters)
	l.mu.Unlock()
	if running != 0 || queued != 0 {
		t.Fatalf("final state = running %d, queued %d, want 0, 0", running, queued)
	}
}
