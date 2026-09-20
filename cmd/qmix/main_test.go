package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/buildinfo"
	"github.com/leugenea/qmix/internal/config"
	"github.com/leugenea/qmix/internal/logging"
	"github.com/leugenea/qmix/internal/server"
)

func TestProcessContextHandlesSIGTERM(t *testing.T) {
	ctx, stop := notifyProcessContext()
	stop()
	select {
	case <-ctx.Done():
	default:
		t.Fatal("stopping process notifications did not cancel context")
	}

	if os.Getenv("QMIX_SIGNAL_HELPER") == "1" {
		ctx, stop := notifyProcessContext()
		defer stop()
		fmt.Println("ready")
		<-ctx.Done()
		return
	}

	cmd := exec.Command(os.Args[0], "-test.run=^TestProcessContextHandlesSIGTERM$")
	cmd.Env = append(os.Environ(), "QMIX_SIGNAL_HELPER=1")
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}

	waitDone := make(chan struct{})
	var waitErr error
	go func() {
		waitErr = cmd.Wait()
		close(waitDone)
	}()
	t.Cleanup(func() {
		select {
		case <-waitDone:
			return
		default:
			_ = cmd.Process.Kill()
		}
		select {
		case <-waitDone:
		case <-time.After(2 * time.Second):
		}
	})

	watchdog := time.NewTimer(5 * time.Second)
	defer watchdog.Stop()
	ready := make(chan struct {
		line string
		err  error
	}, 1)
	go func() {
		line, readErr := bufio.NewReader(stdout).ReadString('\n')
		ready <- struct {
			line string
			err  error
		}{line: line, err: readErr}
	}()
	select {
	case result := <-ready:
		if result.err != nil || result.line != "ready\n" {
			t.Fatalf("helper readiness = %q, %v", result.line, result.err)
		}
	case <-waitDone:
		t.Fatalf("helper exited before readiness: %v", waitErr)
	case <-watchdog.C:
		t.Fatal("timed out waiting for helper readiness")
	}

	if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	select {
	case <-waitDone:
		if waitErr != nil {
			t.Fatalf("SIGTERM helper exited non-zero: %v", waitErr)
		}
	case <-watchdog.C:
		t.Fatal("timed out waiting for SIGTERM helper exit")
	}
}

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

func TestStartServerConfiguresSharedConsoleAndFileLogger(t *testing.T) {
	path := filepath.Join(t.TempDir(), "backend.log")
	t.Setenv("QMIX_LOG_LEVEL", "warn")
	t.Setenv("QMIX_LOG_FILE", path)
	var console bytes.Buffer

	err := startServer(&console, func(cfg config.Config, logger *slog.Logger) error {
		if cfg.Address != ":8080" {
			t.Fatalf("address = %q, want :8080", cfg.Address)
		}
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
	err := startServer(&bytes.Buffer{}, func(config.Config, *slog.Logger) error {
		served = true
		return nil
	})
	if err == nil || !strings.Contains(err.Error(), "QMIX_LOG_LEVEL") || served {
		t.Fatalf("error=%v served=%t", err, served)
	}
}

func TestStartServerRejectsInvalidDurationBeforeServing(t *testing.T) {
	secret := "token=SENTINEL_DO_NOT_LOG"
	t.Setenv("QMIX_YTDLP_SEARCH_TIMEOUT", secret)
	served := false
	err := startServer(&bytes.Buffer{}, func(config.Config, *slog.Logger) error {
		served = true
		return nil
	})
	if err == nil || !errors.Is(err, config.ErrInvalid) || served || strings.Contains(err.Error(), secret) {
		t.Fatalf("error=%v served=%t", err, served)
	}
}

func TestWriteStartupErrorKeepsNonConfigFailuresDetailFree(t *testing.T) {
	for name, tc := range map[string]struct {
		err       error
		kind      string
		forbidden []string
	}{
		"plain invalid sentinel": {
			err:       fmt.Errorf("credential=SECRET\nforged record: %w", config.ErrInvalid),
			kind:      "invalid_configuration",
			forbidden: []string{"credential=SECRET", "forged record"},
		},
		"persistent log": {
			err:       fmt.Errorf("log path /secret/qmix.log: disk full: %w", logging.ErrPersistentOpen),
			kind:      "persistent_log_open_failed",
			forbidden: []string{"/secret/qmix.log", "disk full"},
		},
		"yt-dlp unavailable": {
			err:       fmt.Errorf("executable /secret/bin/yt-dlp: permission denied: %w", server.ErrYTDLPUnavailable),
			kind:      "ytdlp_unavailable",
			forbidden: []string{"/secret/bin/yt-dlp", "permission denied"},
		},
		"unexpected": {
			err:       errors.New("listen 10.0.0.8:8080: arbitrary failure"),
			kind:      "startup_failed",
			forbidden: []string{"10.0.0.8:8080", "arbitrary failure"},
		},
	} {
		t.Run(name, func(t *testing.T) {
			var output bytes.Buffer
			writeStartupError(&output, tc.err)
			if strings.Count(output.String(), "\n") != 1 || !strings.HasSuffix(output.String(), "\n") {
				t.Fatalf("startup error was not one JSON record: %q", output.String())
			}
			for _, forbidden := range tc.forbidden {
				if strings.Contains(output.String(), forbidden) {
					t.Fatalf("startup record leaked %q: %q", forbidden, output.String())
				}
			}
			var record map[string]string
			if err := json.Unmarshal(output.Bytes(), &record); err != nil {
				t.Fatal(err)
			}
			if len(record) != 4 || record["error_kind"] != tc.kind {
				t.Fatalf("startup record = %#v", record)
			}
		})
	}
}

func TestWriteStartupErrorReportsOnlySafeInvalidConfigDetails(t *testing.T) {
	secret := "token=SENTINEL_DO_NOT_LOG\nforged record"
	tests := []struct {
		name     string
		variable string
		value    string
		guidance string
	}{
		{name: "invalid log level", variable: "QMIX_LOG_LEVEL", value: secret, guidance: "use debug, info, warn, or error"},
		{name: "malformed duration", variable: "QMIX_YTDLP_METADATA_TIMEOUT", value: secret, guidance: "use Go duration syntax"},
		{name: "zero timeout", variable: "QMIX_YTDLP_SEARCH_TIMEOUT", value: "0s", guidance: "must be greater than zero"},
		{name: "negative timeout", variable: "QMIX_YTDLP_SEARCH_TIMEOUT", value: "-1s", guidance: "must be greater than zero"},
		{name: "zero cache TTL", variable: "QMIX_STREAM_CACHE_TTL", value: "0s", guidance: "must be non-zero; negative disables caching"},
		{name: "room create burst", variable: "QMIX_ROOM_CREATE_BURST", value: "token=SENTINEL", guidance: "must be a positive integer"},
		{name: "room submission burst", variable: "QMIX_ROOM_SUBMISSION_BURST", value: "token=SENTINEL", guidance: "must be a positive integer"},
		{name: "trusted proxy CIDRs", variable: "QMIX_TRUSTED_PROXY_CIDRS", value: "token=SENTINEL", guidance: "use a comma-separated list of IPv4 or IPv6 CIDR prefixes"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, parseErr := config.Parse(func(name string) (string, bool) {
				if name == tc.variable {
					return tc.value, true
				}
				return "", false
			})
			if parseErr == nil || !errors.Is(parseErr, config.ErrInvalid) {
				t.Fatalf("parse error = %v, want ErrInvalid", parseErr)
			}

			wrappedDetail := "arbitrary wrapped detail\nforged wrapper record"
			var output bytes.Buffer
			writeStartupError(&output, fmt.Errorf("%s: %w", wrappedDetail, parseErr))

			if strings.Count(output.String(), "\n") != 1 || !strings.HasSuffix(output.String(), "\n") {
				t.Fatalf("startup output is not one JSON record: %q", output.String())
			}
			var record map[string]string
			if err := json.Unmarshal(output.Bytes(), &record); err != nil {
				t.Fatalf("decode startup record: %v; output=%q", err, output.String())
			}
			if record["error_kind"] != "invalid_configuration" || record["config_variable"] != tc.variable || record["guidance"] != tc.guidance {
				t.Fatalf("startup record = %#v", record)
			}
			if len(record) != 6 {
				t.Fatalf("startup record has unexpected fields: %#v", record)
			}
			for _, forbidden := range []string{tc.value, secret, wrappedDetail, "forged record", "forged wrapper record"} {
				if forbidden != "" && strings.Contains(output.String(), forbidden) {
					t.Fatalf("startup record leaked %q: %q", forbidden, output.String())
				}
			}
		})
	}
}

func TestStartupErrorKindUsesStableCategories(t *testing.T) {
	for name, tc := range map[string]struct {
		err  error
		want string
	}{
		"config":  {err: config.ErrInvalid, want: "invalid_configuration"},
		"file":    {err: logging.ErrPersistentOpen, want: "persistent_log_open_failed"},
		"yt-dlp":  {err: server.ErrYTDLPUnavailable, want: "ytdlp_unavailable"},
		"generic": {err: errors.New("secret detail"), want: "startup_failed"},
	} {
		t.Run(name, func(t *testing.T) {
			if got := startupErrorKind(tc.err); got != tc.want {
				t.Fatalf("kind = %q, want %q", got, tc.want)
			}
		})
	}
}
