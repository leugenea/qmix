// Package admission defines reusable public denial metadata for bounded HTTP work.
package admission

import (
	"net/http"
	"strconv"
)

// Error is an opaque admission denial. Its public HTTP metadata is selected
// only by package-owned constructors and cannot be changed by callers.
type Error struct {
	kind              denialKind
	retryAfterSeconds int
}

type denialKind uint8

const (
	roomCreationRateLimit denialKind = iota + 1
	roomQueueRateLimit
	roomCapacity
)

type publicMetadata struct {
	status  int
	code    string
	message string
}

func fallbackMetadata() publicMetadata {
	return publicMetadata{
		status:  http.StatusServiceUnavailable,
		code:    "admission_denied",
		message: "request temporarily unavailable",
	}
}

// NewRoomCreationRateLimit constructs the denial used by POST /rooms when its
// per-client token bucket is exhausted.
func NewRoomCreationRateLimit(retryAfterSeconds int) *Error {
	return newError(roomCreationRateLimit, retryAfterSeconds)
}

// NewRoomQueueRateLimit constructs the shared denial reserved for the two room
// queue-submission routes.
func NewRoomQueueRateLimit(retryAfterSeconds int) *Error {
	return newError(roomQueueRateLimit, retryAfterSeconds)
}

// NewRoomCapacity constructs the denial reserved for process-wide live-room
// capacity exhaustion.
func NewRoomCapacity(retryAfterSeconds int) *Error {
	return newError(roomCapacity, retryAfterSeconds)
}

func newError(kind denialKind, retryAfterSeconds int) *Error {
	if retryAfterSeconds < 1 {
		retryAfterSeconds = 1
	}
	return &Error{kind: kind, retryAfterSeconds: retryAfterSeconds}
}

func (e *Error) metadata() publicMetadata {
	if e == nil {
		return fallbackMetadata()
	}
	switch e.kind {
	case roomCreationRateLimit:
		return publicMetadata{
			status:  http.StatusTooManyRequests,
			code:    "rate_limited",
			message: "room creation rate limit exceeded",
		}
	case roomQueueRateLimit:
		return publicMetadata{
			status:  http.StatusTooManyRequests,
			code:    "rate_limited",
			message: "room submission rate limit exceeded",
		}
	case roomCapacity:
		return publicMetadata{
			status:  http.StatusServiceUnavailable,
			code:    "room_capacity_exhausted",
			message: "room capacity is temporarily exhausted",
		}
	default:
		return fallbackMetadata()
	}
}

// HTTPStatus returns a package-owned valid HTTP error status.
func (e *Error) HTTPStatus() int { return e.metadata().status }

// Code returns a package-owned public API error code.
func (e *Error) Code() string { return e.metadata().code }

// Message returns a package-owned public API error message.
func (e *Error) Message() string { return e.metadata().message }

// RetryAfterSeconds returns a positive Retry-After delta in seconds.
func (e *Error) RetryAfterSeconds() int {
	if e == nil || e.retryAfterSeconds < 1 {
		return 1
	}
	return e.retryAfterSeconds
}

// Error returns only the fixed public code so logs cannot accidentally gain
// additional details through error formatting.
func (e *Error) Error() string { return e.Code() }

// RetryAfterDelta formats an RFC 9110 Retry-After delta-seconds value. Values
// below one are clamped so every denial gives clients actionable retry metadata.
func RetryAfterDelta(seconds int) string {
	if seconds < 1 {
		seconds = 1
	}
	return strconv.Itoa(seconds)
}
