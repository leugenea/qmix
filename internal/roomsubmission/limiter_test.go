package roomsubmission

import (
	"net/http"
	"sync"
	"testing"
	"time"
)

func TestLimiterBurstRefillAndRetry(t *testing.T) {
	now := time.Unix(100, 0)
	limiter := NewLimiter(30, 10, func() time.Time { return now })
	for i := 0; i < 10; i++ {
		if denial := limiter.Allow(); denial != nil {
			t.Fatalf("burst admission %d denied: %v", i+1, denial)
		}
	}
	denial := limiter.Allow()
	if denial == nil {
		t.Fatal("request beyond burst admitted")
	}
	if denial.HTTPStatus() != http.StatusTooManyRequests || denial.Code() != "rate_limited" || denial.Message() != "room submission rate limit exceeded" || denial.RetryAfterSeconds() != 2 {
		t.Fatalf("denial = status %d code %q message %q retry %d", denial.HTTPStatus(), denial.Code(), denial.Message(), denial.RetryAfterSeconds())
	}

	now = now.Add(2 * time.Second)
	if denial := limiter.Allow(); denial != nil {
		t.Fatalf("one replenished token denied: %v", denial)
	}
	if denial := limiter.Allow(); denial == nil || denial.RetryAfterSeconds() != 2 {
		t.Fatalf("second request after one-token refill denial = %v", denial)
	}
}

func TestLimiterConcurrentAdmissionDoesNotExceedBurst(t *testing.T) {
	const burst = 10
	limiter := NewLimiter(30, burst, func() time.Time { return time.Unix(100, 0) })
	start := make(chan struct{})
	results := make(chan bool, 64)
	var callers sync.WaitGroup
	for range cap(results) {
		callers.Add(1)
		go func() {
			defer callers.Done()
			<-start
			results <- limiter.Allow() == nil
		}()
	}
	close(start)
	callers.Wait()
	close(results)
	admitted := 0
	for allowed := range results {
		if allowed {
			admitted++
		}
	}
	if admitted != burst {
		t.Fatalf("admitted = %d, want burst %d", admitted, burst)
	}
}

func TestLimiterInvalidDirectConfigurationFailsClosed(t *testing.T) {
	for _, tc := range []struct {
		name  string
		rate  int
		burst int
	}{
		{name: "zero rate", rate: 0, burst: 1},
		{name: "rate above maximum", rate: MaxRatePerMinute + 1, burst: 1},
		{name: "zero burst", rate: 1, burst: 0},
		{name: "burst above maximum", rate: 1, burst: MaxBurst + 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			denial := NewLimiter(tc.rate, tc.burst, nil).Allow()
			if denial == nil || denial.RetryAfterSeconds() < 1 {
				t.Fatalf("invalid limiter denial = %v", denial)
			}
		})
	}
}

// qmix#156: absence and malformed direct values must never imply admission.
func TestLimiterNilAndInvalidValuesFailClosed(t *testing.T) {
	for name, limiter := range map[string]*Limiter{
		"nil":           nil,
		"zero":          {},
		"missing clock": {ratePerSecond: 1, burst: 1, tokens: 1},
	} {
		t.Run(name, func(t *testing.T) {
			denial := limiter.Allow()
			if denial == nil {
				t.Fatal("invalid limiter admitted request")
			}
			if denial.HTTPStatus() != 429 || denial.Code() != "rate_limited" || denial.Message() != "room submission rate limit exceeded" || denial.RetryAfterSeconds() != 1 {
				t.Fatalf("unsafe denial: %#v", denial)
			}
		})
	}
}
