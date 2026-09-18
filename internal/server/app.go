package server

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os/exec"
	"time"

	"github.com/leugenea/qmix/internal/config"
	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
)

// ErrYTDLPUnavailable is the stable, secret-safe startup validation error.
var ErrYTDLPUnavailable = errors.New("required yt-dlp executable is unavailable or not executable")

// Dependencies contains hermetic seams for process composition and readiness.
// Nil fields select the production implementations.
type Dependencies struct {
	CheckExecutable    func(string) bool
	BuildResolver      func(resolver.Config) resolver.Resolver
	BuildStreamBackend func(stream.Config) stream.StreamBackend
}

// App wires the store, hub, routes, and process dependencies into one runnable
// backend. Every field used by Handler is constructed once in NewApp.
type App struct {
	Store   *Store
	Hub     *Hub
	handler http.Handler
	stop    chan struct{}
}

// NewApp validates the required executable, constructs process dependencies
// once, and starts the room janitor. It does not read the environment.
func NewApp(cfg config.Config, logger *slog.Logger, deps Dependencies) (*App, error) {
	if logger == nil {
		logger = discardLogger()
	}
	if deps.CheckExecutable == nil {
		deps.CheckExecutable = executableAvailable
	}
	if deps.BuildResolver == nil {
		deps.BuildResolver = func(cfg resolver.Config) resolver.Resolver {
			return resolver.DefaultMuxWithConfig(cfg)
		}
	}
	if deps.BuildStreamBackend == nil {
		deps.BuildStreamBackend = NewStreamBackend
	}
	if !deps.CheckExecutable(cfg.YTDLP.Binary) {
		return nil, ErrYTDLPUnavailable
	}

	store := NewStore(cfg.Rooms.EmptyTTL, cfg.Rooms.JanitorInterval, nil)
	store.NonEmptyTTL = cfg.Rooms.NonEmptyTTL
	hub := NewHubWithLogger(logger)
	server := NewServerWithLogger(store, hub, logger)
	server.Resolver = deps.BuildResolver(cfg.ResolverConfig())
	server.StreamBackend = deps.BuildStreamBackend(cfg.StreamConfig())

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	mux.HandleFunc("GET /readyz", readyz(cfg.YTDLP.Binary, deps.CheckExecutable))
	server.Routes(mux)

	a := &App{Store: store, Hub: hub, handler: mux, stop: make(chan struct{})}
	go a.Store.Janitor(a.stop)
	return a, nil
}

// Close stops the janitor.
func (a *App) Close() {
	close(a.stop)
}

// Handler returns the prebuilt process handler without reading environment,
// resolving executables, or reconstructing dependencies.
func (a *App) Handler() http.Handler {
	return a.handler
}

// NewStreamBackend returns a production yt-dlp StreamBackend from typed config.
func NewStreamBackend(cfg stream.Config) stream.StreamBackend {
	return &stream.YTDLP{Bin: cfg.YtdlpBin, CacheTTL: cfg.CacheTTL, SearchTimeout: cfg.YTDLPSearchTimeout}
}

// IsYTDLPUnavailable reports the stable startup validation category.
func IsYTDLPUnavailable(err error) bool {
	return errors.Is(err, ErrYTDLPUnavailable)
}

func executableAvailable(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

// healthz reports unconditional process liveness.
func healthz(w http.ResponseWriter, _ *http.Request) {
	writeStatus(w, http.StatusOK, "ok")
}

func readyz(binary string, checkExecutable func(string) bool) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		if !checkExecutable(binary) {
			writeStatus(w, http.StatusServiceUnavailable, "unavailable")
			return
		}
		writeStatus(w, http.StatusOK, "ready")
	}
}

func writeStatus(w http.ResponseWriter, status int, value string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": value})
}

// readHeaderTimeout bounds reading request headers only (qmix#40): a
// slowloris-style client cannot hold sockets indefinitely, while a full
// WriteTimeout would cut long-lived SSE responses.
const readHeaderTimeout = 10 * time.Second

// newHTTPServer builds the production HTTP server for addr. It is split out
// of Run so the timeout configuration is testable.
func newHTTPServer(addr string, h http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: readHeaderTimeout,
	}
}

// Run validates and constructs the backend before listening, then serves until
// the listener fails. The caller supplies the already-parsed process config.
func Run(cfg config.Config, logger *slog.Logger) error {
	return run(cfg, logger, Dependencies{})
}

func run(cfg config.Config, logger *slog.Logger, deps Dependencies) error {
	if logger == nil {
		logger = discardLogger()
	}
	a, err := NewApp(cfg, logger, deps)
	if err != nil {
		return err
	}
	defer a.Close()
	serverLogger := logger.With("component", "server/http")
	listener, err := net.Listen("tcp", cfg.Address)
	if err != nil {
		serverLogger.Error("server stopped", "error_kind", "listen_failed")
		return err
	}
	serverLogger.Info("server listening", "address", listener.Addr().String())
	err = newHTTPServer(cfg.Address, a.Handler()).Serve(listener)
	serverLogger.Error("server stopped", "error_kind", "serve_failed")
	return err
}
