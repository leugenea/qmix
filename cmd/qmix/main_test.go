package main

import (
	"bytes"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/leugenea/qmix/internal/buildinfo"
	"github.com/leugenea/qmix/internal/logging"
)

func TestExecuteDispatchesVersionOrServer(t *testing.T) {
	oldVersion, oldCommit := buildinfo.Version, buildinfo.Commit
	oldDirty, oldCode := buildinfo.Dirty, buildinfo.AndroidVersionCode
	buildinfo.Version = "1.2.3"
	buildinfo.Commit = "0123456789abcdef0123456789abcdef01234567"
	buildinfo.Dirty = "false"
	buildinfo.AndroidVersionCode = "102039999"
	t.Cleanup(func() {
		buildinfo.Version, buildinfo.Commit = oldVersion, oldCommit
		buildinfo.Dirty, buildinfo.AndroidVersionCode = oldDirty, oldCode
	})

	var out bytes.Buffer
	served := false
	serve := func() error {
		served = true
		return errors.New("server stopped")
	}
	if err := execute([]string{"--version"}, &out, serve); err != nil {
		t.Fatal(err)
	}
	want := `{"version":"1.2.3","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":102039999}` + "\n"
	if out.String() != want || served {
		t.Fatalf("version dispatch: output=%q served=%t", out.String(), served)
	}

	out.Reset()
	if err := execute(nil, &out, serve); err == nil || err.Error() != "server stopped" {
		t.Fatalf("server dispatch error = %v", err)
	}
	if !served || out.Len() != 0 {
		t.Fatalf("server dispatch: served=%t output=%q", served, out.String())
	}
}

func TestListenAddrDefault(t *testing.T) {
	t.Setenv("QMIX_ADDR", "")
	if got := listenAddr(); got != ":8080" {
		t.Fatalf("addr = %q, want :8080", got)
	}
}

func TestListenAddrFromEnv(t *testing.T) {
	t.Setenv("QMIX_ADDR", ":9000")
	if got := listenAddr(); got != ":9000" {
		t.Fatalf("addr = %q, want :9000", got)
	}
}

func TestStartServerConfiguresSharedConsoleAndFileLogger(t *testing.T) {
	path := filepath.Join(t.TempDir(), "backend.log")
	t.Setenv("QMIX_LOG_LEVEL", "warn")
	t.Setenv("QMIX_LOG_FILE", path)
	var console bytes.Buffer

	err := startServer(&console, func(logger *slog.Logger) error {
		logger.Warn("test record", "component", "server/http")
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	fileData, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for name, data := range map[string]string{"console": console.String(), "file": string(fileData)} {
		if !strings.Contains(data, `"level":"WARN"`) || !strings.Contains(data, `"component":"server/http"`) {
			t.Fatalf("%s missing configured log record: %s", name, data)
		}
	}
}

func TestStartServerRejectsInvalidLevelBeforeServing(t *testing.T) {
	t.Setenv("QMIX_LOG_LEVEL", "verbose")
	served := false
	err := startServer(&bytes.Buffer{}, func(*slog.Logger) error {
		served = true
		return nil
	})
	if err == nil || !strings.Contains(err.Error(), "QMIX_LOG_LEVEL") || served {
		t.Fatalf("error=%v served=%t", err, served)
	}
}

func TestWriteStartupErrorEmitsOneEscapedJSONRecord(t *testing.T) {
	var output bytes.Buffer
	secret := "token=SENTINEL_DO_NOT_LOG"
	writeStartupError(&output, errors.New("open failed: "+secret+"\nforged record"))
	if strings.Count(strings.TrimSpace(output.String()), "\n") != 0 || !strings.Contains(output.String(), `"error_kind":"startup_failed"`) || strings.Contains(output.String(), secret) || strings.Contains(output.String(), "open failed") {
		t.Fatalf("startup error was not a secret-safe JSON record: %q", output.String())
	}
}

func TestStartupErrorKindUsesStableCategories(t *testing.T) {
	for name, tc := range map[string]struct {
		err  error
		want string
	}{
		"level":   {err: logging.ErrInvalidLevel, want: "invalid_log_level"},
		"file":    {err: logging.ErrPersistentOpen, want: "persistent_log_open_failed"},
		"generic": {err: errors.New("secret detail"), want: "startup_failed"},
	} {
		t.Run(name, func(t *testing.T) {
			if got := startupErrorKind(tc.err); got != tc.want {
				t.Fatalf("kind = %q, want %q", got, tc.want)
			}
		})
	}
}
