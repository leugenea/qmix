// Command qmix runs the QMix backend: rooms, shared queue and streaming.
// M1: in-memory rooms, REST + SSE, no audio (resolver/streaming land in M2/M3).
package main

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"

	"github.com/leugenea/qmix/internal/buildinfo"
	"github.com/leugenea/qmix/internal/logging"
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

func execute(args []string, out io.Writer, serve func() error) error {
	return buildinfo.Run(args, out, serve)
}

func startServer(console io.Writer, serve func(*slog.Logger) error) (err error) {
	cfg, err := logging.ConfigFromEnv()
	if err != nil {
		return err
	}
	logger, closeLog, err := logging.Open(cfg, console)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, closeLog()) }()
	return serve(logger)
}

func writeStartupError(out io.Writer, err error) {
	_ = json.NewEncoder(out).Encode(map[string]string{
		"level":      "ERROR",
		"component":  "server/http",
		"msg":        "backend startup failed",
		"error_kind": startupErrorKind(err),
	})
}

func startupErrorKind(err error) string {
	switch {
	case errors.Is(err, logging.ErrInvalidLevel):
		return "invalid_log_level"
	case errors.Is(err, logging.ErrPersistentOpen):
		return "persistent_log_open_failed"
	default:
		return "startup_failed"
	}
}

func main() {
	if err := execute(os.Args[1:], os.Stdout, func() error {
		return startServer(os.Stderr, func(logger *slog.Logger) error {
			return server.Run(listenAddr(), logger)
		})
	}); err != nil {
		writeStartupError(os.Stderr, err)
		os.Exit(1)
	}
}
