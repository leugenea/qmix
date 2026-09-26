// Package ratelimit owns the bounded token-bucket arithmetic shared by room admission.
package ratelimit

import (
	"math"
	"sync"
	"time"
)

// Safe bounds keep token arithmetic well-defined for both room limiters.
const (
	MaxRatePerMinute = 60_000
	MaxBurst         = 10_000
)

// Valid reports whether both bucket settings are in their safe domain.
func Valid(ratePerMinute, burst int) bool {
	return ratePerMinute >= 1 && ratePerMinute <= MaxRatePerMinute && burst >= 1 && burst <= MaxBurst
}

// Bucket is one non-blocking, synchronized token bucket. Invalid or zero-value
// buckets deny every admission with a one-second retry delay.
type Bucket struct {
	mu            sync.Mutex
	ratePerSecond float64
	burst         float64
	tokens        float64
	updated       time.Time
	now           func() time.Time
}

// NewBucket creates a bucket at the clock's current time. A nil clock uses time.Now.
func NewBucket(ratePerMinute, burst int, now func() time.Time) *Bucket {
	if now == nil {
		now = time.Now
	}
	created := now()
	return NewBucketAt(ratePerMinute, burst, created, now)
}

// NewBucketAt uses a previously sampled time, so a keyed registry can reuse
// its single clock reading for lookup, reclamation, and token admission.
func NewBucketAt(ratePerMinute, burst int, created time.Time, now func() time.Time) *Bucket {
	b := &Bucket{updated: created, now: now}
	if Valid(ratePerMinute, burst) {
		b.ratePerSecond = float64(ratePerMinute) / 60
		b.burst = float64(burst)
		b.tokens = b.burst
	}
	return b
}

// Allow consumes one token; a denial includes the positive rounded-up retry
// delay in seconds. It invokes the clock while holding the bucket mutex.
func (b *Bucket) Allow() (bool, int) {
	if b == nil {
		return false, 1
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.now == nil || b.ratePerSecond <= 0 || b.burst < 1 {
		return false, 1
	}
	return b.allowAt(b.now())
}

// AllowAt admits using an already sampled time, preserving the keyed registry's
// single clock read under its lock.
func (b *Bucket) AllowAt(now time.Time) (bool, int) {
	if b == nil {
		return false, 1
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.ratePerSecond <= 0 || b.burst < 1 {
		return false, 1
	}
	return b.allowAt(now)
}

func (b *Bucket) allowAt(now time.Time) (bool, int) {
	b.replenish(now)
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, int(math.Ceil((1 - b.tokens) / b.ratePerSecond))
}

// FullAt replenishes before testing whether the registry may reclaim a bucket.
func (b *Bucket) FullAt(now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.replenish(now)
	return b.burst >= 1 && b.tokens >= b.burst
}

func (b *Bucket) replenish(now time.Time) {
	if elapsed := now.Sub(b.updated).Seconds(); elapsed > 0 {
		b.tokens = min(b.burst, b.tokens+elapsed*b.ratePerSecond)
		b.updated = now
	}
}

// RetryAfterNew is the denial delay when a registry is full and cannot create
// a bucket for an unseen identity.
func RetryAfterNew(ratePerMinute int) int {
	if !Valid(ratePerMinute, 1) {
		return 1
	}
	return int(math.Ceil(1 / (float64(ratePerMinute) / 60)))
}
