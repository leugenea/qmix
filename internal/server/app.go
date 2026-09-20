package server

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os/exec"
	"sync"
	"time"

	"github.com/leugenea/qmix/internal/config"
	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/roomcreate"
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
	Listen             func(string, string) (net.Listener, error)
}

// App wires the store, hub, routes, and process dependencies into one runnable
// backend. Every field used by Handler is constructed once in NewApp.
type App struct {
	Store       *Store
	Hub         *Hub
	handler     http.Handler
	stop        chan struct{}
	janitorDone chan struct{}
	closeOnce   sync.Once
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

	// One shared capacity limiter spans metadata resolution and stream search
	// so no more than the configured number of yt-dlp subprocesses run at
	// once (qmix#130). Both builders receive the same instance.
	ytdlpLimiter := cfg.YTDLPCapacityLimiter()

	store, err := NewStoreWithLimits(
		cfg.Rooms.EmptyTTL,
		cfg.Rooms.JanitorInterval,
		nil,
		cfg.Rooms.MaxLiveRooms,
		cfg.RoomSubmission.RatePerMinute,
		cfg.RoomSubmission.Burst,
		time.Now,
	)
	if err != nil {
		return nil, err
	}
	store.NonEmptyTTL = cfg.Rooms.NonEmptyTTL
	hub := NewHubWithLogger(logger)
	server := NewServerWithLogger(store, hub, logger)
	server.roomCreateLimiter = roomcreate.NewLimiter(
		cfg.RoomCreation.RatePerMinute,
		cfg.RoomCreation.Burst,
		cfg.RoomCreation.IdentityLimit,
		time.Now,
	)
	server.clientIdentities = roomcreate.NewIdentityResolver(cfg.RoomCreation.TrustedProxyCIDRs)
	server.Resolver = deps.BuildResolver(cfg.ResolverConfig(ytdlpLimiter))
	server.StreamBackend = deps.BuildStreamBackend(cfg.StreamConfig(ytdlpLimiter))

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	mux.HandleFunc("GET /readyz", readyz(cfg.YTDLP.Binary, deps.CheckExecutable))
	server.Routes(mux)

	a := &App{
		Store:       store,
		Hub:         hub,
		handler:     mux,
		stop:        make(chan struct{}),
		janitorDone: make(chan struct{}),
	}
	go func() {
		defer close(a.janitorDone)
		a.Store.Janitor(a.stop)
	}()
	return a, nil
}

// Close stops the janitor and waits for it to exit. It is safe to call
// repeatedly or concurrently.
func (a *App) Close() {
	a.closeOnce.Do(func() { close(a.stop) })
	<-a.janitorDone
}

// Handler returns the prebuilt process handler without reading environment,
// resolving executables, or reconstructing dependencies.
func (a *App) Handler() http.Handler {
	return a.handler
}

// NewStreamBackend returns a production yt-dlp StreamBackend from typed config.
func NewStreamBackend(cfg stream.Config) stream.StreamBackend {
	return &stream.YTDLP{Bin: cfg.YtdlpBin, CacheTTL: cfg.CacheTTL, SearchTimeout: cfg.YTDLPSearchTimeout, Limiter: cfg.YTDLPLimiter}
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

// shutdownTimeout is the documented 12-second process deadline (qmix#132).
// The listener closes immediately. Active HTTP, SSE, and stream handlers get
// an 11-second grace period; the final second cancels request contexts and
// force-closes connections without allowing a stuck close to deadlock exit.
const (
	shutdownTimeout  = 12 * time.Second
	forceCloseWindow = time.Second
)

type httpServer interface {
	Serve(net.Listener) error
	Shutdown(context.Context) error
	Close() error
}

func serveHTTP(ctx context.Context, server httpServer, listener net.Listener, cancelRequests context.CancelFunc, deadline time.Duration) error {
	serveErr := make(chan error, 1)
	go func() { serveErr <- server.Serve(listener) }()

	select {
	case err := <-serveErr:
		cancelRequests()
		return err
	case <-ctx.Done():
	}

	if deadline < 0 {
		deadline = 0
	}
	forceWindow := min(forceCloseWindow, deadline/5)
	graceTimer := time.NewTimer(deadline - forceWindow)
	defer graceTimer.Stop()
	grace := graceTimer.C
	totalTimer := time.NewTimer(deadline)
	defer totalTimer.Stop()

	shutdownCtx, cancelShutdown := context.WithCancel(context.Background())
	defer cancelShutdown()
	shutdownErr := make(chan error, 1)
	go func() { shutdownErr <- server.Shutdown(shutdownCtx) }()

	var closeErr chan error
	closeStarted := false
	startClose := func() {
		if closeStarted {
			return
		}
		closeStarted = true
		closeErr = make(chan error, 1)
		go func(result chan<- error) { result <- server.Close() }(closeErr)
	}

	var shutdownResult, closeResult, serveResult error
	shutdownDone, closeDone, serveDone := false, false, false
	shutdownCanceled := false
	for {
		if shutdownDone && serveDone && (!closeStarted || closeDone) {
			return errors.Join(shutdownResult, closeResult, serveResult)
		}

		select {
		case err := <-shutdownErr:
			shutdownErr = nil
			shutdownDone = true
			if !(shutdownCanceled && errors.Is(err, context.Canceled)) {
				shutdownResult = err
			}
			cancelRequests()
			if err != nil && !errors.Is(err, context.Canceled) {
				startClose()
			}
		case err := <-closeErr:
			closeErr = nil
			closeDone = true
			closeResult = err
		case err := <-serveErr:
			serveErr = nil
			serveDone = true
			serveResult = intentionalServeError(err)
		case <-grace:
			grace = nil
			cancelRequests()
			shutdownCanceled = true
			cancelShutdown()
			startClose()
		case <-totalTimer.C:
			cancelRequests()
			shutdownCanceled = true
			cancelShutdown()
			startClose()
			for {
				select {
				case err := <-shutdownErr:
					shutdownErr = nil
					if !(shutdownCanceled && errors.Is(err, context.Canceled)) {
						shutdownResult = err
					}
				case err := <-closeErr:
					closeErr = nil
					closeResult = err
				case err := <-serveErr:
					serveErr = nil
					serveResult = intentionalServeError(err)
				default:
					return errors.Join(context.DeadlineExceeded, shutdownResult, closeResult, serveResult)
				}
			}
		}
	}
}

func intentionalServeError(err error) error {
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Run validates and constructs the backend before listening, then serves until
// the process context is canceled or serving fails.
func Run(ctx context.Context, cfg config.Config, logger *slog.Logger) error {
	return run(ctx, cfg, logger, Dependencies{})
}

func run(ctx context.Context, cfg config.Config, logger *slog.Logger, deps Dependencies) error {
	if logger == nil {
		logger = discardLogger()
	}
	if deps.Listen == nil {
		deps.Listen = net.Listen
	}
	a, err := NewApp(cfg, logger, deps)
	if err != nil {
		return err
	}
	defer a.Close()
	serverLogger := logger.With("component", "server/http")
	listener, err := deps.Listen("tcp", cfg.Address)
	if err != nil {
		serverLogger.Error("server stopped", "error_kind", "listen_failed")
		return err
	}
	serverLogger.Info("server listening", "address", listener.Addr().String())
	requestCtx, cancelRequests := context.WithCancel(context.Background())
	httpServer := newHTTPServer(cfg.Address, a.Handler())
	httpServer.BaseContext = func(net.Listener) context.Context { return requestCtx }
	err = serveHTTP(ctx, httpServer, listener, cancelRequests, shutdownTimeout)
	if err != nil {
		serverLogger.Error("server stopped", "error_kind", "serve_failed")
	}
	return err
}
