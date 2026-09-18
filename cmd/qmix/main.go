// Command qmix runs the QMix backend: rooms, shared queue and streaming.
package main

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"

	"github.com/leugenea/qmix/internal/buildinfo"
	"github.com/leugenea/qmix/internal/config"
	"github.com/leugenea/qmix/internal/logging"
	"github.com/leugenea/qmix/internal/server"
)

func execute(args []string, out io.Writer, serve func() error) error {
	return buildinfo.Run(args, out, serve)
}

func startServer(console io.Writer, serve func(config.Config, *slog.Logger) error) (err error) {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	logger, closeLog, err := logging.Open(cfg.LoggingConfig(), console)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, closeLog()) }()
	return serve(cfg, logger)
}

func writeStartupError(out io.Writer, err error) {
	record := map[string]string{
		"level":      "ERROR",
		"component":  "server/http",
		"msg":        "backend startup failed",
		"error_kind": startupErrorKind(err),
	}
	if detail, ok := config.InvalidDetail(err); ok {
		record["config_variable"] = detail.Variable
		record["guidance"] = detail.Guidance
	}
	_ = json.NewEncoder(out).Encode(record)
}

func startupErrorKind(err error) string {
	switch {
	case errors.Is(err, config.ErrInvalid):
		return "invalid_configuration"
	case errors.Is(err, logging.ErrPersistentOpen):
		return "persistent_log_open_failed"
	case errors.Is(err, server.ErrYTDLPUnavailable):
		return "ytdlp_unavailable"
	default:
		return "startup_failed"
	}
}

func main() {
	if err := execute(os.Args[1:], os.Stdout, func() error {
		return startServer(os.Stderr, func(cfg config.Config, logger *slog.Logger) error {
			return server.Run(cfg, logger)
		})
	}); err != nil {
		writeStartupError(os.Stderr, err)
		os.Exit(1)
	}
}
