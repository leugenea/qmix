package ratelimit

import (
	"math"
	"testing"
	"time"
)

func TestBucketRefillsAndRoundsRetryUp(t *testing.T) {
	now := time.Unix(100, 0)
	bucket := NewBucket(40, 1, func() time.Time { return now })
	if allowed, retry := bucket.Allow(); !allowed || retry != 0 {
		t.Fatalf("initial admission = %v, %d", allowed, retry)
	}
	if allowed, retry := bucket.Allow(); allowed || retry != 2 {
		t.Fatalf("empty bucket = %v, %d; want retry 2", allowed, retry)
	}
	now = now.Add(time.Second)
	if allowed, retry := bucket.Allow(); allowed || retry != 1 {
		t.Fatalf("partly refilled bucket = %v, %d; want retry 1", allowed, retry)
	}
	now = now.Add(500 * time.Millisecond)
	if allowed, retry := bucket.Allow(); !allowed || retry != 0 {
		t.Fatalf("refilled bucket = %v, %d", allowed, retry)
	}
}

func TestBucketCapsBurstOnRefill(t *testing.T) {
	now := time.Unix(100, 0)
	bucket := NewBucket(60, 2, func() time.Time { return now })
	for range 2 {
		if allowed, _ := bucket.Allow(); !allowed {
			t.Fatal("burst denied")
		}
	}
	now = now.Add(time.Hour)
	for range 2 {
		if allowed, _ := bucket.Allow(); !allowed {
			t.Fatal("refilled burst denied")
		}
	}
	if allowed, retry := bucket.Allow(); allowed || retry != 1 {
		t.Fatalf("exceeded burst = %v, %d", allowed, retry)
	}
}

func TestBucketInvalidArgumentsFailClosed(t *testing.T) {
	for _, tc := range []struct{ rate, burst int }{
		{0, 1}, {-1, 1}, {MaxRatePerMinute + 1, 1}, {math.MaxInt, 1},
		{1, 0}, {1, -1}, {1, MaxBurst + 1}, {1, math.MaxInt},
	} {
		bucket := NewBucket(tc.rate, tc.burst, nil)
		if allowed, retry := bucket.Allow(); allowed || retry != 1 {
			t.Fatalf("NewBucket(%d, %d) = %v, %d; want denial with retry 1", tc.rate, tc.burst, allowed, retry)
		}
	}
	var zero Bucket
	if allowed, retry := zero.Allow(); allowed || retry != 1 {
		t.Fatalf("zero bucket = %v, %d", allowed, retry)
	}
}

func TestBucketBackwardClockDoesNotAddTokens(t *testing.T) {
	now := time.Unix(100, 0)
	bucket := NewBucket(60, 1, func() time.Time { return now })
	bucket.Allow()
	now = now.Add(-time.Second)
	if allowed, retry := bucket.Allow(); allowed || retry != 1 {
		t.Fatalf("backward clock = %v, %d", allowed, retry)
	}
	now = now.Add(time.Second)
	if allowed, retry := bucket.Allow(); allowed || retry != 1 {
		t.Fatalf("return to previous time = %v, %d", allowed, retry)
	}
	now = now.Add(time.Second)
	if allowed, retry := bucket.Allow(); !allowed || retry != 0 {
		t.Fatalf("elapsed second = %v, %d", allowed, retry)
	}
}
