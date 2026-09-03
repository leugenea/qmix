// Command qmix runs the QMix backend: rooms, shared queue and streaming.
// M1: in-memory rooms, REST + SSE, no audio (resolver/streaming land in M2/M3).
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/leugenea/qmix/internal/server"
)

func main() {
	store := server.NewStore(12*time.Hour, time.Minute, nil)
	hub := server.NewHub()
	srv := server.NewServer(store, hub)

	// Janitor: sweep expired empty rooms in the background.
	stop := make(chan struct{})
	defer close(stop)
	go store.Janitor(stop)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	srv.Routes(mux)

	addr := os.Getenv("QMIX_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("qmix: listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}
