package server

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
)

// App wires the store, hub and routes into one runnable backend.
type App struct {
	Store *Store
	Hub   *Hub
	stop  chan struct{}
}

// NewApp creates the app with production defaults and starts the janitor
// in the background.
func NewApp() *App {
	a := &App{
		Store: NewStore(12*time.Hour, time.Minute, nil),
		Hub:   NewHub(),
		stop:  make(chan struct{}),
	}
	go a.Store.Janitor(a.stop)
	return a
}

// Close stops the janitor.
func (a *App) Close() {
	close(a.stop)
}

// Handler builds the full HTTP mux: health plus room REST/SSE routes.
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	s := NewServer(a.Store, a.Hub)
	s.Resolver = resolver.DefaultMuxWithConfig(resolver.ConfigFromEnv())
	s.StreamBackend = NewStreamBackend()
	s.Routes(mux)
	return mux
}

// NewStreamBackend returns the production yt-dlp StreamBackend configured from
// the environment (path to yt-dlp, cache TTL).
func NewStreamBackend() stream.StreamBackend {
	cfg := stream.ConfigFromEnv()
	return &stream.YTDLP{Bin: cfg.YtdlpBin, CacheTTL: cfg.CacheTTL}
}

// healthz reports service liveness.
func healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

// readHeaderTimeout bounds reading request headers only (qmix#40): a
// slowloris-style client cannot hold sockets indefinitely, while a full
// WriteTimeout would cut long-lived SSE responses.
const readHeaderTimeout = 10 * time.Second

// newHTTPServer builds the production HTTP server for addr. It is split out
// of Run so the timeout configuration is testable.
func newHTTPServer(addr string, h http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: readHeaderTimeout,
	}
}

// Run wires the backend and serves until the listener fails. It is the
// production entry point; addr comes from QMIX_ADDR (see cmd/qmix).
func Run(addr string) error {
	a := NewApp()
	defer a.Close()
	log.Printf("qmix: listening on %s", addr)
	return newHTTPServer(addr, a.Handler()).ListenAndServe()
}
