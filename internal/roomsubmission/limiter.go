// Package roomsubmission enforces per-room queue-submission admission.
package roomsubmission

import (
	"math"
	"sync"
	"time"

	"github.com/leugenea/qmix/internal/admission"
)

// Safe maxima bound configuration and keep token arithmetic well-defined.
const (
	MaxRatePerMinute = 60_000
	MaxBurst         = 10_000
)

type clockFunc func() time.Time

// Limiter is one non-blocking token bucket owned by one room incarnation.
type Limiter struct {
	mu            sync.Mutex
	ratePerSecond float64
	burst         float64
	tokens        float64
	updated       time.Time
	now           clockFunc
}

// NewLimiter constructs one room-incarnation bucket. Invalid direct arguments
// fail closed; startup configuration rejects them before production use.
func NewLimiter(ratePerMinute, burst int, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	rate := validRate(ratePerMinute)
	capacity := validBurst(burst)
	created := now()
	return &Limiter{
		ratePerSecond: rate,
		burst:         capacity,
		tokens:        capacity,
		updated:       created,
		now:           now,
	}
}

func validRate(value int) float64 {
	if value < 1 || value > MaxRatePerMinute {
		return 0
	}
	return float64(value) / 60
}

func validBurst(value int) float64 {
	if value < 1 || value > MaxBurst {
		return 0
	}
	return float64(value)
}

// Allow consumes one token without waiting. Exhaustion returns the shared
// opaque room-submission denial and a positive rounded-up retry delay.
func (l *Limiter) Allow() *admission.Error {
	if l == nil {
		return admission.NewRoomQueueRateLimit(1)
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.now == nil || l.ratePerSecond <= 0 || l.burst < 1 {
		return admission.NewRoomQueueRateLimit(1)
	}
	now := l.now()
	if elapsed := now.Sub(l.updated).Seconds(); elapsed > 0 {
		l.tokens = min(l.burst, l.tokens+elapsed*l.ratePerSecond)
		l.updated = now
	}
	if l.tokens >= 1 {
		l.tokens--
		return nil
	}
	retry := int(math.Ceil((1 - l.tokens) / l.ratePerSecond))
	return admission.NewRoomQueueRateLimit(retry)
}
