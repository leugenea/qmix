package server

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

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

func TestAppCloseIsConcurrentAndWaitsForJanitor(t *testing.T) {
	app, err := NewApp(testConfig(t), nil, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err != nil {
		t.Fatal(err)
	}

	const callers = 32
	start := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(callers)
	for range callers {
		go func() {
			defer wg.Done()
			<-start
			app.Close()
		}()
	}
	close(start)
	wg.Wait()

	select {
	case <-app.janitorDone:
	default:
		t.Fatal("Close returned before the janitor stopped")
	}
}

type closeObservedListener struct {
	net.Listener
	closed chan struct{}
	once   sync.Once
}

func (l *closeObservedListener) Close() error {
	err := l.Listener.Close()
	l.once.Do(func() { close(l.closed) })
	return err
}

func TestServeHTTPStopsAcceptingThenCancelsActiveRequestAtBound(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	observed := &closeObservedListener{Listener: listener, closed: make(chan struct{})}

	requestStarted := make(chan struct{})
	requestCanceled := make(chan struct{})
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(requestStarted)
		w.WriteHeader(http.StatusOK)
		if err := http.NewResponseController(w).Flush(); err != nil {
			return
		}
		<-r.Context().Done()
		close(requestCanceled)
	})
	requestCtx, cancelRequests := context.WithCancel(context.Background())
	httpServer := newHTTPServer(observed.Addr().String(), handler)
	httpServer.BaseContext = func(net.Listener) context.Context { return requestCtx }

	shutdownCtx, stop := context.WithCancel(context.Background())
	serveDone := make(chan error, 1)
	go func() {
		serveDone <- serveHTTP(shutdownCtx, httpServer, observed, cancelRequests, 250*time.Millisecond)
	}()

	responseDone := make(chan error, 1)
	go func() {
		response, requestErr := http.Get("http://" + observed.Addr().String())
		if requestErr == nil {
			_, requestErr = io.Copy(io.Discard, response.Body)
			requestErr = errors.Join(requestErr, response.Body.Close())
		}
		responseDone <- requestErr
	}()
	select {
	case <-requestStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("active request did not start")
	}

	stop()
	select {
	case <-observed.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("listener was not closed when shutdown began")
	}
	if connection, dialErr := net.DialTimeout("tcp", observed.Addr().String(), 25*time.Millisecond); dialErr == nil {
		connection.Close()
		t.Fatal("accepted a new connection after shutdown began")
	}
	select {
	case <-requestCanceled:
		t.Fatal("active request was canceled before the grace bound")
	default:
	}

	select {
	case <-requestCanceled:
	case <-time.After(2 * time.Second):
		t.Fatal("active request context was not canceled at the grace bound")
	}
	select {
	case serveErr := <-serveDone:
		if serveErr != nil {
			t.Fatalf("intentional bounded shutdown error = %v", serveErr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("bounded shutdown did not return")
	}
	select {
	case <-responseDone:
	case <-time.After(2 * time.Second):
		t.Fatal("client request did not finish")
	}
}

type cancelAwareStreamBackend struct {
	started  chan struct{}
	canceled chan struct{}
}

func (b *cancelAwareStreamBackend) Stream(ctx context.Context, _ *stream.Track, _ string) (*stream.Result, error) {
	close(b.started)
	<-ctx.Done()
	close(b.canceled)
	return nil, ctx.Err()
}

func TestServeHTTPCancelsActiveStreamBackendAtBound(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	backend := &cancelAwareStreamBackend{started: make(chan struct{}), canceled: make(chan struct{})}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = stream.ServeStream(w, r, backend, &stream.Track{Title: "active"})
	})
	requestCtx, cancelRequests := context.WithCancel(context.Background())
	httpServer := newHTTPServer(listener.Addr().String(), handler)
	httpServer.BaseContext = func(net.Listener) context.Context { return requestCtx }
	shutdownCtx, stop := context.WithCancel(context.Background())
	serveDone := make(chan error, 1)
	go func() { serveDone <- serveHTTP(shutdownCtx, httpServer, listener, cancelRequests, 50*time.Millisecond) }()
	requestDone := make(chan struct{})
	go func() {
		response, requestErr := http.Get("http://" + listener.Addr().String())
		if requestErr == nil {
			_ = response.Body.Close()
		}
		close(requestDone)
	}()

	select {
	case <-backend.started:
	case <-time.After(2 * time.Second):
		t.Fatal("stream backend did not start")
	}
	stop()
	select {
	case <-backend.canceled:
	case <-time.After(2 * time.Second):
		t.Fatal("stream backend did not receive request cancellation")
	}
	select {
	case err := <-serveDone:
		if err != nil {
			t.Fatalf("bounded stream shutdown error = %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("bounded stream shutdown did not return")
	}
	select {
	case <-requestDone:
	case <-time.After(2 * time.Second):
		t.Fatal("stream request did not return")
	}
}

type failingListener struct {
	err error
}

func (l failingListener) Accept() (net.Conn, error) { return nil, l.err }
func (failingListener) Close() error                { return nil }
func (failingListener) Addr() net.Addr              { return testAddr("failing") }

type testAddr string

func (a testAddr) Network() string { return "test" }
func (a testAddr) String() string  { return string(a) }

func TestServeHTTPReturnsSpontaneousServeFailure(t *testing.T) {
	want := errors.New("accept failed")
	server := newHTTPServer("", http.NotFoundHandler())
	err := serveHTTP(context.Background(), server, failingListener{err: want}, func() {}, time.Second)
	if !errors.Is(err, want) {
		t.Fatalf("serve error = %v, want %v", err, want)
	}
}

type shutdownFailListener struct {
	accepted chan struct{}
	closed   chan struct{}
	once     sync.Once
	err      error
	unblock  bool
}

func (l *shutdownFailListener) Accept() (net.Conn, error) {
	l.once.Do(func() { close(l.accepted) })
	<-l.closed
	return nil, net.ErrClosed
}

func (l *shutdownFailListener) Close() error {
	if l.unblock {
		select {
		case <-l.closed:
		default:
			close(l.closed)
		}
	}
	return l.err
}

func (*shutdownFailListener) Addr() net.Addr { return testAddr("shutdown-failure") }

type blockingFallbackCloseServer struct {
	*http.Server
	closeStarted  chan struct{}
	allowClose    chan struct{}
	closeReturned chan struct{}
	closeErr      error
	closeOnce     sync.Once
}

func (s *blockingFallbackCloseServer) Close() error {
	s.closeOnce.Do(func() {
		close(s.closeStarted)
		<-s.allowClose
		close(s.closeReturned)
	})
	return s.closeErr
}

type controlledCloseListener struct {
	accepted chan struct{}
	closed   chan struct{}
	closeErr error
	once     sync.Once
}

func (l *controlledCloseListener) Accept() (net.Conn, error) {
	l.once.Do(func() { close(l.accepted) })
	<-l.closed
	return nil, net.ErrClosed
}

func (l *controlledCloseListener) Close() error {
	select {
	case <-l.closed:
	default:
		close(l.closed)
	}
	return l.closeErr
}

func (*controlledCloseListener) Addr() net.Addr { return testAddr("controlled-close") }

func TestServeHTTPBoundsFallbackCloseAfterShutdownError(t *testing.T) {
	shutdownErr := errors.New("initial listener close failed")
	fallbackCloseErr := errors.New("fallback server close failed")
	listener := &controlledCloseListener{
		accepted: make(chan struct{}),
		closed:   make(chan struct{}),
		closeErr: shutdownErr,
	}
	server := &blockingFallbackCloseServer{
		Server:        newHTTPServer("", http.NotFoundHandler()),
		closeStarted:  make(chan struct{}),
		allowClose:    make(chan struct{}),
		closeReturned: make(chan struct{}),
		closeErr:      fallbackCloseErr,
	}
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(server.allowClose) }) }
	t.Cleanup(release)

	ctx, stop := context.WithCancel(context.Background())
	const deadline = 40 * time.Millisecond
	done := make(chan error, 1)
	go func() {
		done <- serveHTTP(ctx, server, listener, func() {}, deadline)
	}()
	select {
	case <-listener.accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not begin accepting")
	}

	stop()
	select {
	case <-server.closeStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("fallback close did not start after Shutdown failed")
	}

	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) || !errors.Is(err, shutdownErr) {
			t.Fatalf("bounded fallback error = %v, want deadline and shutdown errors", err)
		}
	case <-time.After(300 * time.Millisecond):
		t.Fatal("fallback close exceeded the injected total deadline")
	}

	release()
	select {
	case <-server.closeReturned:
	case <-time.After(2 * time.Second):
		t.Fatal("released fallback close did not return")
	}
}

func TestServeHTTPPreservesShutdownAndFallbackCloseErrors(t *testing.T) {
	shutdownErr := errors.New("initial listener close failed")
	fallbackCloseErr := errors.New("fallback server close failed")
	listener := &controlledCloseListener{
		accepted: make(chan struct{}),
		closed:   make(chan struct{}),
		closeErr: shutdownErr,
	}
	server := &blockingFallbackCloseServer{
		Server:        newHTTPServer("", http.NotFoundHandler()),
		closeStarted:  make(chan struct{}),
		allowClose:    make(chan struct{}),
		closeReturned: make(chan struct{}),
		closeErr:      fallbackCloseErr,
	}
	close(server.allowClose)

	ctx, stop := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- serveHTTP(ctx, server, listener, func() {}, time.Second)
	}()
	select {
	case <-listener.accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not begin accepting")
	}
	stop()

	select {
	case err := <-done:
		if !errors.Is(err, shutdownErr) || !errors.Is(err, fallbackCloseErr) {
			t.Fatalf("shutdown result = %v, want shutdown and fallback close errors", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("shutdown errors did not return")
	}
}

func TestServeHTTPReturnsShutdownFailure(t *testing.T) {
	want := errors.New("listener close failed")
	listener := &shutdownFailListener{accepted: make(chan struct{}), closed: make(chan struct{}), err: want, unblock: true}
	ctx, stop := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- serveHTTP(ctx, newHTTPServer("", http.NotFoundHandler()), listener, func() {}, time.Second)
	}()
	select {
	case <-listener.accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not begin accepting")
	}
	stop()
	select {
	case err := <-done:
		if !errors.Is(err, want) {
			t.Fatalf("shutdown error = %v, want %v", err, want)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("shutdown failure did not return")
	}
}

func TestServeHTTPBoundsStuckShutdown(t *testing.T) {
	listener := &shutdownFailListener{
		accepted: make(chan struct{}),
		closed:   make(chan struct{}),
		err:      errors.New("listener cannot close"),
	}
	ctx, stop := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- serveHTTP(ctx, newHTTPServer("", http.NotFoundHandler()), listener, func() {}, 250*time.Millisecond)
	}()
	select {
	case <-listener.accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not begin accepting")
	}
	stop()
	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("stuck shutdown error = %v, want deadline exceeded", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stuck shutdown exceeded its bound")
	}
}

func TestRunReturnsNilForIntentionalContextShutdown(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg := testConfig(t)
	ctx, stop := context.WithCancel(context.Background())
	stop()
	err = run(ctx, cfg, nil, Dependencies{
		CheckExecutable: func(string) bool { return true },
		Listen: func(network, address string) (net.Listener, error) {
			if network != "tcp" || address != cfg.Address {
				t.Fatalf("listen = %q %q", network, address)
			}
			return listener, nil
		},
	})
	if err != nil {
		t.Fatalf("intentional shutdown error = %v", err)
	}
	if connection, dialErr := net.DialTimeout("tcp", listener.Addr().String(), 25*time.Millisecond); dialErr == nil {
		connection.Close()
		t.Fatal("listener remained open after Run returned")
	}
}

func TestRunClosesBackgroundWorkersOnListenFailure(t *testing.T) {
	cfg := testConfig(t)
	cfg.YTDLP.Binary = os.Args[0]
	cfg.Address = "127.0.0.1:99999"
	if err := Run(context.Background(), cfg, nil); err == nil {
		t.Fatal("Run returned nil for invalid listen address")
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
