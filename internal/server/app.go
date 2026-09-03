package server

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
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
	NewServer(a.Store, a.Hub).Routes(mux)
	return mux
}

// healthz reports service liveness.
func healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

// Run wires the backend and serves until the listener fails. It is the
// production entry point; addr comes from QMIX_ADDR (see cmd/qmix).
func Run(addr string) error {
	a := NewApp()
	defer a.Close()
	log.Printf("qmix: listening on %s", addr)
	return http.ListenAndServe(addr, a.Handler())
}
