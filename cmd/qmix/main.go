// Command qmix runs the QMix backend: rooms, shared queue and streaming.
// M1: in-memory rooms, REST + SSE, no audio (resolver/streaming land in M2/M3).
package main

import (
	"log"
	"os"

	"github.com/leugenea/qmix/internal/buildinfo"
	"github.com/leugenea/qmix/internal/server"
)

// listenAddr returns the HTTP listen address from QMIX_ADDR, defaulting
// to :8080.
func listenAddr() string {
	if addr := os.Getenv("QMIX_ADDR"); addr != "" {
		return addr
	}
	return ":8080"
}

func main() {
	if err := buildinfo.Run(os.Args[1:], os.Stdout, func() error {
		return server.Run(listenAddr())
	}); err != nil {
		log.Fatal(err)
	}
}
