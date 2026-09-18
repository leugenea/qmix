package logging

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestOpenWritesWarnToConsoleAndPersistentFile(t *testing.T) {
	var console bytes.Buffer
	path := filepath.Join(t.TempDir(), "qmix.log")
	logger, closeLog, err := Open(Config{Level: slog.LevelWarn, File: path}, &console)
	if err != nil {
		t.Fatal(err)
	}

	logger.With("component", "server/http").Info("hidden")
	logger.With("component", "server/http").Warn("request failed", "status", 502)
	if err := closeLog(); err != nil {
		t.Fatal(err)
	}

	fileData, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for name, data := range map[string]string{"console": console.String(), "file": string(fileData)} {
		if strings.Contains(data, "hidden") {
			t.Fatalf("%s contains filtered INFO record: %s", name, data)
		}
		if !strings.Contains(data, `"level":"WARN"`) || !strings.Contains(data, `"component":"server/http"`) || !strings.Contains(data, `"status":502`) {
			t.Fatalf("%s missing structured warning fields: %s", name, data)
		}
	}
}

func TestOpenFailsWhenPersistentFileCannotBeOpened(t *testing.T) {
	secret := "token=SENTINEL_DO_NOT_LOG"
	path := filepath.Join(t.TempDir(), secret, "qmix.log")
	if _, _, err := Open(Config{Level: slog.LevelWarn, File: path}, io.Discard); err == nil || !errors.Is(err, ErrPersistentOpen) || strings.Contains(err.Error(), secret) || strings.Contains(err.Error(), path) {
		t.Fatalf("error = %v, want secret-safe persistent-open failure", err)
	}
}

func TestOpenRejectsSymlinkAndRestrictsExistingFilePermissions(t *testing.T) {
	dir := t.TempDir()
	if _, err := openPersistentFile(dir); err == nil || !strings.Contains(err.Error(), "regular file") {
		t.Fatalf("directory open error = %v", err)
	}
	target := filepath.Join(dir, "target.log")
	if err := os.WriteFile(target, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "qmix.log")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if _, err := openPersistentFile(link); err == nil || !strings.Contains(err.Error(), "symlink") {
		t.Fatalf("symlink open error = %v", err)
	}

	existing := filepath.Join(dir, "existing.log")
	if err := os.WriteFile(existing, nil, 0o666); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(existing, 0o666); err != nil {
		t.Fatal(err)
	}
	_, closeLog, err := Open(Config{Level: slog.LevelWarn, File: existing}, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if err := closeLog(); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(existing)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("permissions = %o, want 600", got)
	}
}

func TestOpenRejectsFIFOWithoutBlocking(t *testing.T) {
	path := filepath.Join(t.TempDir(), "qmix.log")
	if err := syscall.Mkfifo(path, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := openPersistentFile(path); err == nil {
		t.Fatal("FIFO log target was accepted")
	}
}

type failingWriteCloser struct{ err error }

func (w failingWriteCloser) Write([]byte) (int, error) { return 0, w.err }
func (failingWriteCloser) Close() error                { return nil }

func TestPersistentWriteFailureIsReportedToConsole(t *testing.T) {
	var console bytes.Buffer
	secret := "token=SENTINEL_DO_NOT_LOG"
	logger := newLogger(slog.LevelWarn, &console, failingWriteCloser{err: errors.New("disk full: " + secret + "\nforged record")})
	logger.Error("cannot continue", "component", "stream")

	got := console.String()
	if !strings.Contains(got, "cannot continue") || !strings.Contains(got, "persistent log write failed") || !strings.Contains(got, `"error_kind":"write_failed"`) || strings.Contains(got, secret) || strings.Contains(got, "disk full") {
		t.Fatalf("console output = %q", got)
	}
	for _, line := range strings.Split(strings.TrimSpace(got), "\n") {
		var record map[string]interface{}
		if err := json.Unmarshal([]byte(line), &record); err != nil {
			t.Fatalf("fallback output is not one JSON record per line: %q: %v", line, err)
		}
	}
}

func TestPersistentShortWriteIsReportedAndDisabled(t *testing.T) {
	var console bytes.Buffer
	logger := newLogger(slog.LevelWarn, &console, failingWriteCloser{})
	logger.Warn("first")
	logger.Warn("second")
	if got := strings.Count(console.String(), "persistent log write failed"); got != 1 {
		t.Fatalf("fallback count = %d, want 1; output=%q", got, console.String())
	}
	if !strings.Contains(console.String(), `"error_kind":"write_failed"`) {
		t.Fatalf("short write category was not reported: %q", console.String())
	}
}

type bufferWriteCloser struct{ bytes.Buffer }

func (*bufferWriteCloser) Close() error { return nil }

func TestSizeLimitedWriterBoundsPersistentGrowth(t *testing.T) {
	underlying := &bufferWriteCloser{}
	writer := &sizeLimitedWriteCloser{writer: underlying, size: 4, max: 5}
	if _, err := writer.Write([]byte("xx")); !errors.Is(err, errPersistentLogFull) {
		t.Fatalf("error = %v, want errPersistentLogFull", err)
	}
	if underlying.Len() != 0 {
		t.Fatalf("underlying received bytes past limit: %q", underlying.String())
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestOpenEnforcesPersistentSizeLimit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "qmix.log")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Truncate(path, maxPersistentLogSize); err != nil {
		t.Fatal(err)
	}
	var console bytes.Buffer
	logger, closeLog, err := Open(Config{Level: slog.LevelWarn, File: path}, &console)
	if err != nil {
		t.Fatal(err)
	}
	logger.Warn("bounded record")
	if err := closeLog(); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() != maxPersistentLogSize || !strings.Contains(console.String(), "persistent log write failed") {
		t.Fatalf("size=%d console=%q", info.Size(), console.String())
	}
}

type errorWriter struct{ err error }

func (w errorWriter) Write([]byte) (int, error) { return 0, w.err }

func TestFanoutWriterReturnsConsoleFailureAfterPersisting(t *testing.T) {
	persistent := &bufferWriteCloser{}
	wantErr := errors.New("console unavailable")
	writer := &fanoutWriter{console: errorWriter{err: wantErr}, persistent: persistent}

	if _, err := writer.Write([]byte("record\n")); !errors.Is(err, wantErr) {
		t.Fatalf("error = %v, want %v", err, wantErr)
	}
	if persistent.String() != "record\n" {
		t.Fatalf("persistent output = %q", persistent.String())
	}
}
