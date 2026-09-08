package server

import (
	"net/http"
	"testing"
)

// TestApp_NewHTTPServerReadHeaderTimeout pins the audit fix (qmix#40): the
// production server bounds slowloris-style header reads, while WriteTimeout
// stays unset so long-lived SSE responses are not cut.
func TestApp_NewHTTPServerReadHeaderTimeout(t *testing.T) {
	s := newHTTPServer("127.0.0.1:0", http.NotFoundHandler())
	if s.ReadHeaderTimeout != readHeaderTimeout {
		t.Fatalf("ReadHeaderTimeout = %v, want %v", s.ReadHeaderTimeout, readHeaderTimeout)
	}
	if s.WriteTimeout != 0 {
		t.Fatalf("WriteTimeout = %v, want 0 (SSE streams stay open)", s.WriteTimeout)
	}
}
