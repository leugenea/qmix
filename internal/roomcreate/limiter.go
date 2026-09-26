// Package roomcreate enforces bounded per-client admission for POST /rooms.
package roomcreate

import (
	"sync"
	"time"

	"github.com/leugenea/qmix/internal/admission"
	"github.com/leugenea/qmix/internal/ratelimit"
)

// Keep the identity bound here; token-bucket bounds are shared by ratelimit.
const (
	MaxRatePerMinute = ratelimit.MaxRatePerMinute
	MaxBurst         = ratelimit.MaxBurst
	MaxIdentityLimit = 65_536
)

type bucket struct {
	limit    *ratelimit.Bucket
	lastSeen time.Time
}

// Limiter is a non-blocking token-bucket registry keyed by client identity.
type Limiter struct {
	mu            sync.Mutex
	ratePerMinute int
	burst         int
	identityLimit int
	now           func() time.Time
	buckets       map[string]*bucket
}

// NewLimiter constructs a bounded limiter. Production configuration validation
// enforces the exported safe domain; invalid direct arguments fail closed.
func NewLimiter(ratePerMinute, burst, identityLimit int, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{
		ratePerMinute: ratePerMinute,
		burst:         burst,
		identityLimit: validIdentityLimit(identityLimit),
		now:           now,
		buckets:       make(map[string]*bucket),
	}
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
		if !ratelimit.Valid(l.ratePerMinute, l.burst) || l.identityLimit <= 0 {
			return roomCreationDenied(1)
		}
		if len(l.buckets) >= l.identityLimit && !l.reclaimOne(now) {
			return roomCreationDenied(ratelimit.RetryAfterNew(l.ratePerMinute))
		}
		b = &bucket{limit: ratelimit.NewBucketAt(l.ratePerMinute, l.burst, now, nil), lastSeen: now}
		l.buckets[identity] = b
	}
	allowed, retry := b.limit.AllowAt(now)
	b.lastSeen = now
	if allowed {
		return nil
	}
	return roomCreationDenied(retry)
}

func roomCreationDenied(retryAfterSeconds int) *admission.Error {
	return admission.NewRoomCreationRateLimit(retryAfterSeconds)
}

func (l *Limiter) reclaimOne(now time.Time) bool {
	candidate := ""
	candidateFound := false
	var oldest time.Time
	for identity, b := range l.buckets {
		if !b.limit.FullAt(now) {
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
