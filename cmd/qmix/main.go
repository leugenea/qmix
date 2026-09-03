// Command qmix runs the QMix backend: rooms, shared queue and streaming.
// M0 skeleton: only the /healthz probe is implemented so far.
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
)

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)

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
