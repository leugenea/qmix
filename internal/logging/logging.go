// Package logging configures QMix's structured backend logs.
package logging

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"syscall"
)

const (
	defaultLogFile       = "qmix.log"
	maxPersistentLogSize = 10 << 20
)

var (
	// ErrInvalidLevel reports an unsupported logging threshold without echoing
	// the environment value, which may itself contain secret material.
	ErrInvalidLevel = errors.New("invalid QMIX_LOG_LEVEL: use debug, info, warn, or error")
	// ErrPersistentOpen reports a startup-blocking sink failure without exposing
	// the configured path or an underlying PathError.
	ErrPersistentOpen    = errors.New("persistent log file unavailable")
	errPersistentLogFull = errors.New("persistent log size limit reached")
)

// Config is the process-wide backend logging configuration.
type Config struct {
	Level slog.Level
	File  string
}

// ConfigFromEnv reads QMIX_LOG_LEVEL and QMIX_LOG_FILE. The effective default
// level is WARN and the default persistent file is qmix.log.
func ConfigFromEnv() (Config, error) {
	cfg := Config{Level: slog.LevelWarn, File: defaultLogFile}
	if path := strings.TrimSpace(os.Getenv("QMIX_LOG_FILE")); path != "" {
		cfg.File = path
	}
	if value := strings.TrimSpace(os.Getenv("QMIX_LOG_LEVEL")); value != "" {
		switch strings.ToLower(value) {
		case "debug":
			cfg.Level = slog.LevelDebug
		case "info":
			cfg.Level = slog.LevelInfo
		case "warn", "warning":
			cfg.Level = slog.LevelWarn
		case "error":
			cfg.Level = slog.LevelError
		default:
			return Config{}, ErrInvalidLevel
		}
	}
	return cfg, nil
}

// Open creates one JSON logger that fans every accepted record out to console
// and the persistent file. File-open failures are fatal to startup; later file
// write failures are reported to the console and disable only the broken sink.
func Open(cfg Config, console io.Writer) (*slog.Logger, func() error, error) {
	file, err := openPersistentFile(cfg.File)
	if err != nil {
		return nil, nil, ErrPersistentOpen
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, nil, ErrPersistentOpen
	}
	persistent := &sizeLimitedWriteCloser{writer: file, size: info.Size(), max: maxPersistentLogSize}
	return newLogger(cfg.Level, console, persistent), persistent.Close, nil
}

func openPersistentFile(path string) (*os.File, error) {
	file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err == nil {
		return file, nil
	}
	if !errors.Is(err, os.ErrExist) {
		return nil, err
	}

	file, err = os.OpenFile(path, os.O_APPEND|os.O_WRONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		if errors.Is(err, syscall.ELOOP) {
			return nil, errors.New("refusing symlink log file")
		}
		if errors.Is(err, syscall.EISDIR) {
			return nil, errors.New("log file is not a regular file")
		}
		return nil, err
	}
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		_ = file.Close()
		if err != nil {
			return nil, err
		}
		return nil, errors.New("log file is not a regular file")
	}
	if err := file.Chmod(0o600); err != nil {
		_ = file.Close()
		return nil, fmt.Errorf("restrict log file permissions: %w", err)
	}
	return file, nil
}

func newLogger(level slog.Level, console io.Writer, persistent io.WriteCloser) *slog.Logger {
	writer := &fanoutWriter{console: console, persistent: persistent}
	return slog.New(slog.NewJSONHandler(writer, &slog.HandlerOptions{Level: level}))
}

type sizeLimitedWriteCloser struct {
	writer    io.WriteCloser
	size      int64
	max       int64
	closeOnce sync.Once
	closeErr  error
}

func (w *sizeLimitedWriteCloser) Write(p []byte) (int, error) {
	if int64(len(p)) > w.max-w.size {
		return 0, errPersistentLogFull
	}
	n, err := w.writer.Write(p)
	w.size += int64(n)
	return n, err
}

func (w *sizeLimitedWriteCloser) Close() error {
	w.closeOnce.Do(func() { w.closeErr = w.writer.Close() })
	return w.closeErr
}

type fanoutWriter struct {
	mu         sync.Mutex
	console    io.Writer
	persistent io.WriteCloser
	failed     bool
}

func (w *fanoutWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	consoleN, consoleErr := w.console.Write(p)
	if !w.failed {
		persistentN, err := w.persistent.Write(p)
		if err == nil && persistentN != len(p) {
			err = io.ErrShortWrite
		}
		if err != nil {
			w.failed = true
			_ = w.persistent.Close()
			fallback, _ := json.Marshal(map[string]string{
				"level":      "ERROR",
				"component":  "logging",
				"msg":        "persistent log write failed",
				"error_kind": "write_failed",
			})
			fallback = append(fallback, '\n')
			_, _ = w.console.Write(fallback)
		}
	}
	if consoleErr != nil {
		return consoleN, consoleErr
	}
	return len(p), nil
}
