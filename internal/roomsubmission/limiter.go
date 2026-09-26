// Package roomsubmission maps a per-room bucket's denials to room queue admission.
package roomsubmission

import (
	"time"

	"github.com/leugenea/qmix/internal/admission"
	"github.com/leugenea/qmix/internal/ratelimit"
)

// Existing callers and tests can keep referring to the room-specific bounds.
const (
	MaxRatePerMinute = ratelimit.MaxRatePerMinute
	MaxBurst         = ratelimit.MaxBurst
)

// Limiter is one non-blocking token bucket owned by one room incarnation.
type Limiter struct {
	bucket *ratelimit.Bucket
	// Preserve invalid direct struct literals in existing package tests. These
	// fields cannot grant admission: only an initialized bucket can do that.
	ratePerSecond, burst, tokens float64
}

// NewLimiter constructs one room-incarnation bucket. Invalid direct arguments
// fail closed; startup configuration rejects them before production use.
func NewLimiter(ratePerMinute, burst int, now func() time.Time) *Limiter {
	return &Limiter{bucket: ratelimit.NewBucket(ratePerMinute, burst, now)}
}

// Allow consumes one token without waiting. Exhaustion returns the shared
// opaque room-submission denial and a positive rounded-up retry delay.
func (l *Limiter) Allow() *admission.Error {
	if l == nil {
		return admission.NewRoomQueueRateLimit(1)
	}
	allowed, retry := l.bucket.Allow()
	if allowed {
		return nil
	}
	return admission.NewRoomQueueRateLimit(retry)
}
