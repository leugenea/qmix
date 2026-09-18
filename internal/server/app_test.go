package server

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/leugenea/qmix/internal/config"
	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
)

// TestApp_NewHTTPServerReadHeaderTimeout pins the audit fix (qmix#40): the
// production server bounds slowloris-style header reads, while WriteTimeout
// stays unset so long-lived SSE responses are not cut.
func TestApp_NewHTTPServerReadHeaderTimeout(t *testing.T) {
	s := newHTTPServer("127.0.0.1:0", http.NotFoundHandler())
	if s.ReadHeaderTimeout != readHeaderTimeout {
		t.Fatalf("ReadHeaderTimeout = %v, want %v", s.ReadHeaderTimeout, readHeaderTimeout)
	}
	if s.WriteTimeout != 0 {
		t.Fatalf("WriteTimeout = %v, want 0 (SSE streams stay open)", s.WriteTimeout)
	}
}

func TestNewStreamBackendUsesTypedConfig(t *testing.T) {
	cfg := stream.Config{YtdlpBin: "/configured/yt-dlp", CacheTTL: -1, YTDLPSearchTimeout: 41}
	backend := NewStreamBackend(cfg)
	b, ok := backend.(*stream.YTDLP)
	if !ok {
		t.Fatalf("backend = %T, want *stream.YTDLP", backend)
	}
	if b.Bin != cfg.YtdlpBin || b.CacheTTL != cfg.CacheTTL || b.SearchTimeout != cfg.YTDLPSearchTimeout {
		t.Fatalf("backend = %+v, want config %+v", b, cfg)
	}
}

func TestAppReadinessTracksConfiguredExecutable(t *testing.T) {
	tests := []struct {
		name       string
		executable bool
		wantStatus int
		wantBody   string
	}{
		{name: "available", executable: true, wantStatus: http.StatusOK, wantBody: "{\"status\":\"ready\"}\n"},
		{name: "missing", executable: false, wantStatus: http.StatusServiceUnavailable, wantBody: "{\"status\":\"unavailable\"}\n"},
		{name: "not executable", executable: false, wantStatus: http.StatusServiceUnavailable, wantBody: "{\"status\":\"unavailable\"}\n"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			available := true
			cfg := testConfig(t)
			cfg.YTDLP.Binary = "/configured/yt-dlp"
			app, err := NewApp(cfg, nil, Dependencies{
				CheckExecutable: func(name string) bool {
					if name != "/configured/yt-dlp" {
						t.Fatalf("checked %q, want configured binary", name)
					}
					return available
				},
			})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(app.Close)
			available = tc.executable

			rec := httptest.NewRecorder()
			app.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d; body=%s", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if got := rec.Header().Get("Content-Type"); got != "application/json" {
				t.Fatalf("content type = %q, want %q", got, "application/json")
			}
			if got := rec.Body.String(); got != tc.wantBody {
				t.Fatalf("body = %q, want %q", got, tc.wantBody)
			}
		})
	}
}

func TestAppHealthzRemainsLiveWhenReadinessFails(t *testing.T) {
	available := true
	app, err := NewApp(testConfig(t), nil, Dependencies{CheckExecutable: func(string) bool { return available }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(app.Close)
	available = false

	rec := httptest.NewRecorder()
	app.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != http.StatusOK || rec.Body.String() != "{\"status\":\"ok\"}\n" {
		t.Fatalf("health response = %d %q", rec.Code, rec.Body.String())
	}
}

func TestNewAppRejectsUnavailableExecutableSafely(t *testing.T) {
	secretPath := "/secret/token=SENTINEL_DO_NOT_LOG/yt-dlp"
	cfg := testConfig(t)
	cfg.YTDLP.Binary = secretPath
	_, err := NewApp(cfg, nil, Dependencies{CheckExecutable: func(string) bool { return false }})
	if err == nil || !IsYTDLPUnavailable(err) {
		t.Fatalf("error = %v, want unavailable executable category", err)
	}
	if strings.Contains(err.Error(), secretPath) {
		t.Fatalf("startup error leaked configured path: %q", err)
	}
}

func TestAppBuildsDependenciesOnceAndIgnoresLaterEnvironmentChanges(t *testing.T) {
	cfg := testConfig(t)
	cfg.YTDLP.Binary = "/initial/yt-dlp"
	resolverBuilds, streamBuilds := 0, 0
	var resolverConfig resolver.Config
	var streamConfig stream.Config
	app, err := NewApp(cfg, slog.Default(), Dependencies{
		CheckExecutable: func(string) bool { return true },
		BuildResolver: func(got resolver.Config) resolver.Resolver {
			resolverBuilds++
			resolverConfig = got
			return resolver.DefaultMuxWithConfig(got)
		},
		BuildStreamBackend: func(got stream.Config) stream.StreamBackend {
			streamBuilds++
			streamConfig = got
			return NewStreamBackend(got)
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(app.Close)

	t.Setenv("QMIX_YTDLP_BIN", "/changed/yt-dlp")
	first, second := app.Handler(), app.Handler()
	if first != second {
		t.Fatal("Handler returned a reconstructed handler")
	}
	if resolverBuilds != 1 || streamBuilds != 1 {
		t.Fatalf("dependency builds = resolver:%d stream:%d, want one each", resolverBuilds, streamBuilds)
	}
	if resolverConfig.YTDLPBin != "/initial/yt-dlp" || streamConfig.YtdlpBin != "/initial/yt-dlp" {
		t.Fatalf("dependencies changed after environment mutation: resolver=%q stream=%q", resolverConfig.YTDLPBin, streamConfig.YtdlpBin)
	}
}

func TestExecutableCheckRequiresAResolvableExecutable(t *testing.T) {
	dir := t.TempDir()
	executable := filepath.Join(dir, "available")
	if err := os.WriteFile(executable, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	notExecutable := filepath.Join(dir, "not-executable")
	if err := os.WriteFile(notExecutable, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		path string
		want bool
	}{
		{name: "executable", path: executable, want: true},
		{name: "not executable", path: notExecutable, want: false},
		{name: "missing", path: filepath.Join(dir, "missing"), want: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := executableAvailable(tc.path); got != tc.want {
				t.Fatalf("available = %t, want %t", got, tc.want)
			}
		})
	}
}

func TestExecutableCheckResolvesBasenameThroughPATHWithoutRunningIt(t *testing.T) {
	const name = "qmix-test-executable"
	marker := filepath.Join(t.TempDir(), "executed")
	t.Setenv("QMIX_EXECUTED_MARKER", marker)

	executableDir := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(executableDir, name),
		[]byte("#!/bin/sh\n: > \"$QMIX_EXECUTED_MARKER\"\n"),
		0o755,
	); err != nil {
		t.Fatal(err)
	}
	notExecutableDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(notExecutableDir, name), nil, 0o644); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct {
		name string
		path string
		want bool
	}{
		{name: "executable", path: executableDir, want: true},
		{name: "not executable", path: notExecutableDir, want: false},
		{name: "missing", path: t.TempDir(), want: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("PATH", tc.path)
			if got := executableAvailable(name); got != tc.want {
				t.Fatalf("available = %t, want %t", got, tc.want)
			}
			if _, err := os.Stat(marker); !os.IsNotExist(err) {
				t.Fatalf("executable checker ran %q: marker error = %v", name, err)
			}
		})
	}
}

func testConfig(t *testing.T) config.Config {
	t.Helper()
	cfg, err := config.Parse(func(string) (string, bool) { return "", false })
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}
