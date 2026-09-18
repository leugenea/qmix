// Package stream finds and serves the audio stream for a tracked song. A
// StreamBackend maps an explicitly supported source URL or track metadata to a
// direct audio source (the first backend is yt-dlp); the proxy handler serves
// that source to clients with HTTP Range/seek support.
//
// The audio itself always flows through a single StreamBackend, regardless of
// which resolver produced the track metadata (see ARCHITECTURE §7).
package stream

import (
	"context"
	"errors"
	"io"
)

// ErrNotFound is returned when no playable audio source could be found for a
// track (e.g. yt-dlp returned no results). The HTTP layer maps it to a 404.
var ErrNotFound = errors.New("no audio source found")

// ErrService wraps any upstream failure while locating or opening the audio
// source (exec, HTTP, transport). The HTTP layer maps it to a 502.
var ErrService = errors.New("stream service error")

// Track is the subset of source identity and metadata a StreamBackend needs to
// locate audio.
// The server adapts its own Track into this value before calling the backend,
// keeping the stream package decoupled from the server.
type Track struct {
	ID         string
	URL        string
	Title      string
	Artist     string
	ResolvedBy string
}

// Result is the outcome of Stream: an open audio stream plus the metadata the
// proxy needs to reproduce the correct HTTP response.
type Result struct {
	// Body is the audio stream for the caller to consume and close.
	Body io.ReadCloser

	// ContentType is the audio MIME type (e.g. "audio/webm").
	ContentType string

	// Status is the HTTP status the proxy should return to the client:
	// StatusOK (200) for a full response, StatusPartialContent (206) when the
	// body is a range, or StatusRequestedRangeNotSatisfiable (416) when the
	// requested range cannot be satisfied.
	Status int

	// ContentLength is the total length of the resource in bytes, or -1 if
	// unknown. For a range response this is the full resource length.
	ContentLength int64

	// ContentRange is the "bytes start-end/total" header for a 206 response,
	// or "bytes */total" for a 416, or "" when not applicable.
	ContentRange string

	// AcceptRanges advertises range support ("bytes"); the proxy only writes it
	// when non-empty.
	AcceptRanges string
}

// StreamBackend locates and opens the audio stream for a track, honoring an
// optional HTTP Range header. Implementations may cache resolved sources so
// repeated streams for the same track do not re-search the upstream service.
type StreamBackend interface {
	// Stream returns the audio stream for track. rangeHeader is the client's
	// raw HTTP Range header, or "" to stream the whole resource. The returned
	// body must be closed by the caller.
	Stream(ctx context.Context, track *Track, rangeHeader string) (*Result, error)
}
