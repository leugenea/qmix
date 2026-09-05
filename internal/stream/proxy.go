package stream

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
)

// ErrInvalidRange marks a client Range header that is syntactically invalid or
// uses a unit other than bytes. The proxy answers with 416 before consulting
// the backend.
var ErrInvalidRange = errors.New("invalid range header")

// ServeStream streams track's audio to the client through backend, honoring the
// Range header for seek. It writes the correct status codes:
//
//	200  full stream (no Range, or Range ignored)
//	206  partial content (Range satisfied by the backend)
//	416  invalid or unsatisfiable Range (invalid header, or backend says 416)
//	404  no audio source found (ErrNotFound)
//	502  upstream failure while locating/opening the source
//
// It passes the client's Range header through to the backend so Range handling
// is delegated to the underlying source, and reproduces the backend's content
// headers for the client.
func ServeStream(w http.ResponseWriter, r *http.Request, backend StreamBackend, track *Track) {
	rangeHeader := r.Header.Get("Range")
	if rangeHeader != "" {
		if err := validateRangeHeader(rangeHeader); err != nil {
			// Refuse before touching the upstream: non-bytes unit, multiple
			// ranges or a malformed spec is a client error, not a backend one.
			w.Header().Set("Accept-Ranges", "bytes")
			w.Header().Set("Content-Range", "bytes */*")
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
	}

	res, err := backend.Stream(r.Context(), track, rangeHeader)
	if err != nil {
		writeError(w, errorStatus(err), err.Error())
		return
	}
	defer res.Body.Close()

	if res.AcceptRanges != "" {
		w.Header().Set("Accept-Ranges", res.AcceptRanges)
	}
	if res.ContentType != "" {
		w.Header().Set("Content-Type", res.ContentType)
	}
	if res.ContentRange != "" {
		w.Header().Set("Content-Range", res.ContentRange)
	}
	status := res.Status
	if status == 0 {
		status = http.StatusOK
	}
	if res.ContentLength >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(res.ContentLength, 10))
	}
	w.WriteHeader(status)
	_, _ = io.Copy(w, res.Body)
}

// validateRangeHeader reports whether rangeHeader is a syntactically valid
// single byte range. Empty is valid (means "whole resource"); the caller only
// passes non-empty values here. It rejects non-bytes units, multiple ranges and
// malformed specs so the client gets a clean 416.
func validateRangeHeader(rangeHeader string) error {
	unit, spec, ok := strings.Cut(rangeHeader, "=")
	if !ok {
		return ErrInvalidRange
	}
	if strings.TrimSpace(unit) != "bytes" {
		return ErrInvalidRange
	}
	if strings.Contains(spec, ",") {
		// Multiple ranges are refused rather than merged.
		return ErrInvalidRange
	}
	spec = strings.TrimSpace(spec)
	if spec == "" {
		return ErrInvalidRange
	}
	// Single range: "start-end", "start-", or "-suffix".
	if strings.HasPrefix(spec, "-") {
		suffix := strings.TrimPrefix(spec, "-")
		if suffix == "" {
			return ErrInvalidRange
		}
		if _, err := strconv.ParseInt(suffix, 10, 64); err != nil {
			return ErrInvalidRange
		}
		return nil
	}
	parts := strings.SplitN(spec, "-", 2)
	if len(parts) != 2 {
		return ErrInvalidRange
	}
	if _, err := strconv.ParseInt(parts[0], 10, 64); err != nil || parts[0] == "" {
		return ErrInvalidRange
	}
	if parts[1] != "" {
		if _, err := strconv.ParseInt(parts[1], 10, 64); err != nil {
			return ErrInvalidRange
		}
	}
	return nil
}

// errorStatus maps a backend error to an HTTP status: ErrNotFound -> 404,
// ErrInvalidRange -> 416, anything else (upstream failure) -> 502.
func errorStatus(err error) int {
	switch {
	case errors.Is(err, ErrNotFound):
		return http.StatusNotFound
	case errors.Is(err, ErrInvalidRange):
		return http.StatusRequestedRangeNotSatisfiable
	default:
		return http.StatusBadGateway
	}
}

// writeError writes a human-readable error body.
func writeError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, `{"error":`+strconv.Quote(msg)+`}`)
}
