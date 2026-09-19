// Package ytdlpcap bounds how many yt-dlp subprocesses qmix runs at once
// (qmix#130). yt-dlp is launched from both metadata resolution and stream
// search, and the Compose service is capped at 512 MiB while a single
// yt-dlp process can spike to 100–200 MiB RSS, so unbounded concurrency can
// exhaust container memory. One shared Limiter spans both launch sites:
// callers acquire a slot before starting the subprocess, wait in a bounded
// FIFO queue when all slots are busy, and are rejected fast with
// ErrOverloaded once the queue is full so the HTTP layer can answer 503
// with Retry-After instead of piling work onto an overloaded process.
package ytdlpcap

import (
	"context"
	"errors"
	"sync"
)

// ErrOverloaded reports that all capacity slots are busy and the bounded wait
// queue is full. The HTTP layer maps it to 503 plus Retry-After.
var ErrOverloaded = errors.New("yt-dlp capacity exceeded; wait queue full")

// waiter is one queued caller. A single grant is buffered into ready when a
// slot frees; the receiving waiter accepts it in Acquire.
type waiter struct {
	ready chan struct{}
}

// Limiter is a counting semaphore with a bounded FIFO wait queue. The zero
// value is unusable; use New. A nil *Limiter imposes no capacity bound, which
// keeps pre-#130 direct construction in tests working.
type Limiter struct {
	mu       sync.Mutex
	max      int
	queueLen int
	running  int
	waiters  []*waiter
}

// New returns a Limiter allowing max concurrent holders plus a FIFO wait
// queue of at most queueLen callers. Non-positive max returns nil (no
// capacity bound); a negative queueLen fails closed with an empty queue so
// overload is detected immediately.
func New(max, queueLen int) *Limiter {
	if max <= 0 {
		return nil
	}
	if queueLen < 0 {
		queueLen = 0
	}
	return &Limiter{max: max, queueLen: queueLen}
}

// MaxConcurrent reports the configured concurrent slot count (0 for nil).
func (l *Limiter) MaxConcurrent() int {
	if l == nil {
		return 0
	}
	return l.max
}

// QueueLimit reports the configured wait queue depth (0 for nil).
func (l *Limiter) QueueLimit() int {
	if l == nil {
		return 0
	}
	return l.queueLen
}

// Acquire blocks until a capacity slot is available for ctx, or fails fast
// with ErrOverloaded when all slots are busy and the wait queue is full. A
// nil limiter never blocks or fails. Exactly one Release must follow every
// successful Acquire. Callers must not hold a room/store mutex while waiting:
// the store lock protects room state, not subprocess capacity, and waiting
// here can outlast any reasonable lock hold.
func (l *Limiter) Acquire(ctx context.Context) error {
	if l == nil {
		return nil
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	l.mu.Lock()
	if l.running < l.max {
		l.running++
		l.mu.Unlock()
		return nil
	}
	if len(l.waiters) >= l.queueLen {
		l.mu.Unlock()
		return ErrOverloaded
	}
	w := &waiter{ready: make(chan struct{}, 1)}
	l.waiters = append(l.waiters, w)
	l.mu.Unlock()

	select {
	case <-w.ready:
		// The grant may race with ctx expiring; a dead caller must not keep
		// the slot, so hand it back before reporting the cancellation.
		if err := ctx.Err(); err != nil {
			l.abandon(w)
			return err
		}
		return nil
	case <-ctx.Done():
		l.abandon(w)
		return ctx.Err()
	}
}

// Release returns a capacity slot and admits waiters in FIFO order. It must
// be called exactly once per successful Acquire; it tolerates a stray extra
// call without ever admitting more than max concurrent holders, because a
// slot is only created when a holder gives one up.
func (l *Limiter) Release() {
	if l == nil {
		return
	}
	l.mu.Lock()
	l.releaseLocked()
	l.mu.Unlock()
}

// releaseLocked returns one held slot and admits queued waiters in FIFO
// order while capacity remains. The caller holds mu. Admitted waiters are
// counted as running at grant time, so a waiter that abandons its grant
// hands the slot back through the same path.
func (l *Limiter) releaseLocked() {
	if l.running > 0 {
		l.running--
	}
	for l.running < l.max && len(l.waiters) > 0 {
		w := l.waiters[0]
		l.waiters = l.waiters[1:]
		select {
		case w.ready <- struct{}{}:
			l.running++
		default:
			// Unreachable in practice: each waiter is granted at most once
			// and is removed from the queue first. Kept defensive.
		}
	}
}

// abandon removes a waiter that stopped waiting. If its slot was granted at
// the same instant its context expired, the grant is returned so capacity is
// never leaked.
func (l *Limiter) abandon(w *waiter) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for i, candidate := range l.waiters {
		if candidate == w {
			l.waiters = append(l.waiters[:i], l.waiters[i+1:]...)
			return
		}
	}
	// The waiter already received a grant it will never consume; give that
	// slot back.
	l.releaseLocked()
}

// Stats returns the current number of running holders and queued waiters. It
// exists for tests and diagnostics; production code does not branch on it.
func (l *Limiter) Stats() (running, queued int) {
	if l == nil {
		return 0, 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.running, len(l.waiters)
}
