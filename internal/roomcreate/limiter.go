// Package roomcreate enforces bounded per-client admission for POST /rooms.
package roomcreate

import (
	"math"
	"sync"
	"time"

	"github.com/leugenea/qmix/internal/admission"
)

// Safe configuration maxima keep token arithmetic exact and bound registry
// memory. Startup validation and direct construction enforce this same domain.
const (
	MaxRatePerMinute = 60_000
	MaxBurst         = 10_000
	MaxIdentityLimit = 65_536
)

type clockFunc func() time.Time

type bucket struct {
	tokens   float64
	updated  time.Time
	lastSeen time.Time
}

// Limiter is a non-blocking token-bucket registry keyed by client identity.
type Limiter struct {
	mu            sync.Mutex
	ratePerSecond float64
	burst         float64
	identityLimit int
	now           clockFunc
	buckets       map[string]*bucket
}

// NewLimiter constructs a bounded limiter. Production configuration validation
// enforces the exported safe domain; invalid direct arguments fail closed.
func NewLimiter(ratePerMinute, burst, identityLimit int, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{
		ratePerSecond: validRate(ratePerMinute),
		burst:         validBurst(burst),
		identityLimit: validIdentityLimit(identityLimit),
		now:           now,
		buckets:       make(map[string]*bucket),
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

func validIdentityLimit(value int) int {
	if value < 1 || value > MaxIdentityLimit {
		return 0
	}
	return value
}

// Allow consumes one token without waiting. A denial returns reusable, safe
// public HTTP admission metadata with a positive retry delay.
func (l *Limiter) Allow(identity string) *admission.Error {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b := l.buckets[identity]
	if b == nil {
		if l.ratePerSecond <= 0 || l.burst < 1 || l.identityLimit <= 0 {
			return roomCreationDenied(1)
		}
		if len(l.buckets) >= l.identityLimit && !l.reclaimOne(now) {
			return roomCreationDenied(int(math.Ceil(1 / l.ratePerSecond)))
		}
		b = &bucket{tokens: l.burst, updated: now, lastSeen: now}
		l.buckets[identity] = b
	}
	l.replenish(b, now)
	b.lastSeen = now
	if b.tokens >= 1 {
		b.tokens--
		return nil
	}
	retry := int(math.Ceil((1 - b.tokens) / l.ratePerSecond))
	return roomCreationDenied(retry)
}

func roomCreationDenied(retryAfterSeconds int) *admission.Error {
	return admission.NewRoomCreationRateLimit(retryAfterSeconds)
}

func (l *Limiter) replenish(b *bucket, now time.Time) {
	elapsed := now.Sub(b.updated).Seconds()
	if elapsed > 0 {
		b.tokens = min(l.burst, b.tokens+elapsed*l.ratePerSecond)
		b.updated = now
	}
}

func (l *Limiter) reclaimOne(now time.Time) bool {
	candidate := ""
	candidateFound := false
	var oldest time.Time
	for identity, b := range l.buckets {
		l.replenish(b, now)
		if b.tokens < l.burst {
			continue
		}
		if preferReclaimCandidate(identity, b.lastSeen, candidate, oldest, candidateFound) {
			candidate, oldest, candidateFound = identity, b.lastSeen, true
		}
	}
	if !candidateFound {
		return false
	}
	delete(l.buckets, candidate)
	return true
}

func preferReclaimCandidate(identity string, lastSeen time.Time, candidate string, oldest time.Time, candidateFound bool) bool {
	return !candidateFound || lastSeen.Before(oldest) || (lastSeen.Equal(oldest) && identity < candidate)
}

func (l *Limiter) len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}

func (l *Limiter) has(identity string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	_, ok := l.buckets[identity]
	return ok
}
