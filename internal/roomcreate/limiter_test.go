package roomcreate

import (
	"math"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeClock struct{ now time.Time }

func (c *fakeClock) Now() time.Time          { return c.now }
func (c *fakeClock) Advance(d time.Duration) { c.now = c.now.Add(d) }

func TestLimiterDenialReturnsTypedPublicAdmissionError(t *testing.T) {
	limiter := NewLimiter(10, 1, 1, func() time.Time { return time.Unix(100, 0) })
	if err := limiter.Allow("198.51.100.1"); err != nil {
		t.Fatalf("first admission error = %v, want nil", err)
	}
	err := limiter.Allow("198.51.100.1")
	if err == nil {
		t.Fatal("exhausted bucket returned nil admission error")
	}
	if err.HTTPStatus() != http.StatusTooManyRequests || err.Code() != "rate_limited" || err.Message() != "room creation rate limit exceeded" || err.RetryAfterSeconds() != 6 {
		t.Fatalf("admission error = %v", err)
	}
}

func TestLimiterRejectsUnsafeConfigurationInsteadOfLosingTokens(t *testing.T) {
	tests := []struct {
		name                  string
		rate, burst, identity int
	}{
		{name: "rate", rate: math.MaxInt, burst: 1, identity: 1},
		{name: "burst", rate: 1, burst: math.MaxInt, identity: 1},
		{name: "identity limit", rate: 1, burst: 1, identity: math.MaxInt},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			limiter := NewLimiter(tc.rate, tc.burst, tc.identity, func() time.Time { return time.Unix(100, 0) })
			if err := limiter.Allow("198.51.100.1"); err == nil {
				t.Fatal("unsafe configuration admitted a request")
			}
			if got := limiter.len(); got != 0 {
				t.Fatalf("unsafe configuration allocated %d buckets, want 0", got)
			}
		})
	}
}

func TestLimiterEnforcesMaximumSafeBurstExactly(t *testing.T) {
	limiter := NewLimiter(1, MaxBurst, 1, func() time.Time { return time.Unix(100, 0) })
	for request := 1; request <= MaxBurst; request++ {
		if err := limiter.Allow("198.51.100.1"); err != nil {
			t.Fatalf("request %d rejected within maximum safe burst", request)
		}
	}
	if err := limiter.Allow("198.51.100.1"); err == nil || err.RetryAfterSeconds() < 1 {
		t.Fatalf("request beyond maximum safe burst error = %#v, want rejection", err)
	}
}

func TestLimiterBurstAndDeterministicReplenishment(t *testing.T) {
	clock := &fakeClock{now: time.Unix(100, 0)}
	limiter := NewLimiter(10, 5, 4096, clock.Now)
	for i := 0; i < 5; i++ {
		if err := limiter.Allow("198.51.100.1"); err != nil {
			t.Fatalf("request %d rejected inside burst", i+1)
		}
	}
	if err := limiter.Allow("198.51.100.1"); err == nil || err.RetryAfterSeconds() != 6 {
		t.Fatalf("request beyond burst error = %#v, want retry 6", err)
	}
	clock.Advance(6 * time.Second)
	if err := limiter.Allow("198.51.100.1"); err != nil {
		t.Fatal("one token was not replenished after six seconds")
	}
}

func TestLimiterIsolatesIdentities(t *testing.T) {
	clock := &fakeClock{now: time.Unix(100, 0)}
	limiter := NewLimiter(1, 1, 2, clock.Now)
	if err := limiter.Allow("first"); err != nil {
		t.Fatal("first identity rejected")
	}
	if err := limiter.Allow("first"); err == nil {
		t.Fatal("exhausted identity allowed")
	}
	if err := limiter.Allow("second"); err != nil {
		t.Fatal("independent identity inherited another bucket's exhaustion")
	}
}

func TestLimiterReclaimsEmptyIdentityWhenItWinsATie(t *testing.T) {
	clock := &fakeClock{now: time.Unix(100, 0)}
	limiter := NewLimiter(60, 1, 2, clock.Now)
	for _, identity := range []string{"", "other"} {
		if err := limiter.Allow(identity); err != nil {
			t.Fatalf("initial admission for %q rejected", identity)
		}
	}
	clock.Advance(time.Second)

	if err := limiter.Allow("new"); err != nil {
		t.Fatalf("new identity rejected although tied full buckets were reclaimable: %v", err)
	}
	if limiter.has("") {
		t.Fatal("lexically first tied empty identity was not reclaimed")
	}
	if !limiter.has("other") || !limiter.has("new") {
		t.Fatal("reclamation removed the wrong tied candidate")
	}
}

func TestLimiterReclaimsLexicallyFirstEqualLastSeenFullBucket(t *testing.T) {
	identities := []string{"hotel", "delta", "alpha", "golf", "charlie", "foxtrot", "bravo", "echo"}
	// Multiple fresh maps make this black-box test robust against Go's randomized
	// map traversal: removing the explicit tie-break cannot hide behind one lucky
	// iteration order.
	for trial := 0; trial < 64; trial++ {
		clock := &fakeClock{now: time.Unix(100, 0)}
		limiter := NewLimiter(60, 1, len(identities), clock.Now)
		for _, identity := range identities {
			if err := limiter.Allow(identity); err != nil {
				t.Fatalf("trial %d initial admission for %q rejected", trial, identity)
			}
		}
		clock.Advance(time.Second)

		if err := limiter.Allow("new"); err != nil {
			t.Fatalf("trial %d unseen identity was rejected although equal-lastSeen buckets were fully replenished", trial)
		}
		if limiter.has("alpha") {
			t.Fatalf("trial %d lexically first equal-lastSeen full bucket was not reclaimed", trial)
		}
		for _, identity := range identities {
			if identity != "alpha" && !limiter.has(identity) {
				t.Fatalf("trial %d wrong equal-lastSeen bucket %q was reclaimed", trial, identity)
			}
		}
		if !limiter.has("new") {
			t.Fatalf("trial %d new identity was not retained after reclaim", trial)
		}
	}
}

func TestLimiterReclaimsOldestInactiveFullBucketDeterministically(t *testing.T) {
	clock := &fakeClock{now: time.Unix(100, 0)}
	limiter := NewLimiter(60, 1, 2, clock.Now)
	limiter.Allow("z-oldest")
	clock.Advance(time.Second)
	limiter.Allow("a-newer")
	clock.Advance(time.Second)

	if err := limiter.Allow("new"); err != nil {
		t.Fatal("unseen identity was rejected although inactive full buckets were reclaimable")
	}
	if limiter.has("z-oldest") || !limiter.has("a-newer") || !limiter.has("new") {
		t.Fatalf("registry identities after cleanup are wrong")
	}
}

func TestLimiterSaturationRejectsUnseenButKeepsKnownBuckets(t *testing.T) {
	clock := &fakeClock{now: time.Unix(100, 0)}
	limiter := NewLimiter(1, 2, 1, clock.Now)
	if err := limiter.Allow("known"); err != nil {
		t.Fatal("known first request rejected")
	}
	if err := limiter.Allow("unseen"); err == nil || err.RetryAfterSeconds() < 1 {
		t.Fatalf("unseen saturated admission error = %#v, want rejection with positive retry", err)
	}
	if err := limiter.Allow("known"); err != nil {
		t.Fatal("known identity stopped according to its existing bucket")
	}
	if limiter.len() != 1 {
		t.Fatalf("registry length = %d, want hard bound 1", limiter.len())
	}
}

func TestLimiterCleanupDoesNotReclaimPartiallyReplenishedBucket(t *testing.T) {
	clock := &fakeClock{now: time.Unix(100, 0)}
	limiter := NewLimiter(60, 2, 1, clock.Now)
	limiter.Allow("known")
	limiter.Allow("known")
	clock.Advance(time.Second)
	if err := limiter.Allow("unseen"); err == nil {
		t.Fatal("partially replenished bucket was reclaimed")
	}
	if !limiter.has("known") {
		t.Fatal("known bucket disappeared")
	}
}

func TestLimiterConcurrentUnseenIdentitiesNeverExceedRegistryBound(t *testing.T) {
	const limit = 16
	const callers = 128
	limiter := NewLimiter(1, 1, limit, func() time.Time { return time.Unix(100, 0) })
	start := make(chan struct{})
	var accepted atomic.Int32
	var calls sync.WaitGroup
	calls.Add(callers)
	for i := 0; i < callers; i++ {
		identity := string(rune(i + 1))
		go func() {
			defer calls.Done()
			<-start
			if err := limiter.Allow(identity); err == nil {
				accepted.Add(1)
			}
		}()
	}
	close(start)
	calls.Wait()
	if got := limiter.len(); got != limit {
		t.Fatalf("registry length = %d, want %d", got, limit)
	}
	if got := accepted.Load(); got != limit {
		t.Fatalf("accepted identities = %d, want %d", got, limit)
	}
}
