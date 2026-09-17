package server

import (
	"net/http"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/stream"
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

func TestApp_NewStreamBackendWiresSearchTimeout(t *testing.T) {
	t.Setenv("QMIX_YTDLP_SEARCH_TIMEOUT", "41s")
	backend := NewStreamBackend()
	b, ok := backend.(*stream.YTDLP)
	if !ok {
		t.Fatalf("backend = %T, want *stream.YTDLP", backend)
	}
	if b.SearchTimeout != 41*time.Second {
		t.Fatalf("SearchTimeout = %v, want 41s", b.SearchTimeout)
	}
}
