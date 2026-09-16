package server

import (
	"io"
	"log/slog"
	"net/http"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

type responseStatusWriter struct {
	http.ResponseWriter
	status int
}

func (w *responseStatusWriter) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *responseStatusWriter) Write(p []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(p)
}

// Unwrap lets http.ResponseController reach optional interfaces such as
// Flusher on the underlying writer, which is required by SSE responses.
func (w *responseStatusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

type flushingResponseStatusWriter struct {
	*responseStatusWriter
	flusher http.Flusher
}

func (w *flushingResponseStatusWriter) Flush() { w.flusher.Flush() }

func (s *Server) observeHTTP(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		observed := &responseStatusWriter{ResponseWriter: w}
		var target http.ResponseWriter = observed
		if flusher, ok := w.(http.Flusher); ok {
			target = &flushingResponseStatusWriter{responseStatusWriter: observed, flusher: flusher}
		}
		next(target, r)
		status := observed.status
		if status == 0 {
			status = http.StatusOK
		}
		s.httpLogger.Debug("request completed", "method", r.Method, "route", r.Pattern, "status", status)
	}
}
