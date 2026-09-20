package admission

import (
	"net/http"
	"testing"
)

func TestConstructorsExposeOnlyAllowlistedPublicMetadata(t *testing.T) {
	tests := []struct {
		name           string
		denial         *Error
		wantStatus     int
		wantCode       string
		wantMessage    string
		wantRetryAfter int
	}{
		{
			name:           "room creation rate limit",
			denial:         NewRoomCreationRateLimit(6),
			wantStatus:     http.StatusTooManyRequests,
			wantCode:       "rate_limited",
			wantMessage:    "room creation rate limit exceeded",
			wantRetryAfter: 6,
		},
		{
			name:           "room queue rate limit extension",
			denial:         NewRoomQueueRateLimit(3),
			wantStatus:     http.StatusTooManyRequests,
			wantCode:       "rate_limited",
			wantMessage:    "room submission rate limit exceeded",
			wantRetryAfter: 3,
		},
		{
			name:           "live room capacity extension",
			denial:         NewRoomCapacity(9),
			wantStatus:     http.StatusServiceUnavailable,
			wantCode:       "room_capacity_exhausted",
			wantMessage:    "room capacity is temporarily exhausted",
			wantRetryAfter: 9,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := tc.denial.HTTPStatus(); got != tc.wantStatus {
				t.Fatalf("HTTPStatus() = %d, want %d", got, tc.wantStatus)
			}
			if got := tc.denial.Code(); got != tc.wantCode {
				t.Fatalf("Code() = %q, want %q", got, tc.wantCode)
			}
			if got := tc.denial.Message(); got != tc.wantMessage {
				t.Fatalf("Message() = %q, want %q", got, tc.wantMessage)
			}
			if got := tc.denial.RetryAfterSeconds(); got != tc.wantRetryAfter {
				t.Fatalf("RetryAfterSeconds() = %d, want %d", got, tc.wantRetryAfter)
			}
			if got := tc.denial.Error(); got != tc.wantCode {
				t.Fatalf("Error() = %q, want safe code %q", got, tc.wantCode)
			}
		})
	}
}

func TestInvalidStateMapsToFixedSafeFallback(t *testing.T) {
	for _, denial := range []*Error{nil, {}, {kind: denialKind(255), retryAfterSeconds: -20}} {
		if got := denial.HTTPStatus(); got != http.StatusServiceUnavailable {
			t.Fatalf("HTTPStatus() = %d, want 503", got)
		}
		if got := denial.Code(); got != "admission_denied" {
			t.Fatalf("Code() = %q, want admission_denied", got)
		}
		if got := denial.Message(); got != "request temporarily unavailable" {
			t.Fatalf("Message() = %q, want fixed fallback", got)
		}
		if got := denial.RetryAfterSeconds(); got != 1 {
			t.Fatalf("RetryAfterSeconds() = %d, want 1", got)
		}
		if got := denial.Error(); got != "admission_denied" {
			t.Fatalf("Error() = %q, want safe fallback code", got)
		}
	}
}

func TestRetryAfterIsNormalizedAtConstruction(t *testing.T) {
	for _, seconds := range []int{-9, 0, 1, 73} {
		want := seconds
		if want < 1 {
			want = 1
		}
		if got := NewRoomCreationRateLimit(seconds).RetryAfterSeconds(); got != want {
			t.Fatalf("retry for %d = %d, want %d", seconds, got, want)
		}
	}
}

func TestRetryAfterDeltaFormatsPositiveIntegerCentrally(t *testing.T) {
	for _, tc := range []struct {
		seconds int
		want    string
	}{
		{seconds: -9, want: "1"},
		{seconds: 0, want: "1"},
		{seconds: 1, want: "1"},
		{seconds: 73, want: "73"},
	} {
		if got := RetryAfterDelta(tc.seconds); got != tc.want {
			t.Fatalf("RetryAfterDelta(%d) = %q, want %q", tc.seconds, got, tc.want)
		}
	}
}
