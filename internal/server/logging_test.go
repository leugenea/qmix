package server

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
)

type loggingResolver struct{ err error }

func (r loggingResolver) Resolve(context.Context, string) (*resolver.Track, error) {
	return nil, r.err
}

type loggingStreamBackend struct{ err error }

func (b loggingStreamBackend) Stream(context.Context, *stream.Track, string) (*stream.Result, error) {
	return nil, b.err
}

func TestSubsystemWarningsIdentifyComponentsWithoutSecrets(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelDebug}))
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hub := NewHubWithLogger(logger)
	s := NewServerWithLogger(store, hub, logger)
	mux := newTestMux(s)

	// Missing room exercises the store/rooms boundary and HTTP status logging.
	doReq(t, mux, http.MethodGet, "/rooms/missing", "", "")

	code, _ := createRoom(t, mux)
	secret := "must-not-appear"
	s.Resolver = loggingResolver{err: errors.New("upstream rejected https://service.invalid/path?token=" + secret)}
	addTrack(t, mux, code, "https://service.invalid/path?token="+secret)

	store.mu.Lock()
	store.rooms[code].Current = &Current{TrackID: "safe-track-id", Title: "Safe title"}
	store.mu.Unlock()
	s.StreamBackend = loggingStreamBackend{err: errors.New("GET https://cdn.invalid/audio?signature=" + secret)}
	doReq(t, mux, http.MethodGet, "/rooms/"+code+"/current/stream", "", "")

	// A transport that cannot flush is an attributable SSE failure.
	logHub := NewHubWithLogger(logger)
	logSub, cancelLog := logHub.subscribeRef(roomRef{code: code, generation: 1})
	logHub.serveSubscriptionHTTP(context.Background(), &noFlushWriter{header: http.Header{}}, logSub, cancelLog, eventSnapshot{Data: map[string]interface{}{}})

	logs := output.String()
	for _, component := range []string{"server/http", "store/rooms", "resolver", "stream", "sse"} {
		if !strings.Contains(logs, `"component":"`+component+`"`) {
			t.Errorf("logs missing component %q:\n%s", component, logs)
		}
	}
	if strings.Contains(logs, secret) || strings.Contains(logs, code) || strings.Contains(logs, "signature=") || strings.Contains(logs, "token=") {
		t.Fatalf("logs expose secret-bearing input or a room access code:\n%s", logs)
	}
}

func TestHTTPRecordsFixedRouteWithoutSecretBearingPathOrQuery(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelDebug}))
	s := NewServerWithLogger(NewStore(time.Hour, time.Hour, &seqCodeGen{}), NewHub(), logger)
	mux := newTestMux(s)

	req := httptest.NewRequest(http.MethodGet, "/rooms/token%3Dmust-not-appear?authorization=secret", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	logs := output.String()
	if !strings.Contains(logs, `"method":"GET"`) || !strings.Contains(logs, `"route":"GET /rooms/{code}"`) || !strings.Contains(logs, `"status":404`) {
		t.Fatalf("logs missing safe request context: %s", logs)
	}
	if strings.Contains(logs, "authorization") || strings.Contains(logs, "must-not-appear") || strings.Contains(logs, "token=") {
		t.Fatalf("logs include path or query parameters: %s", logs)
	}
}

func TestNewAppWiresProductionHandlerComponents(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelDebug}))
	app, err := NewApp(testConfig(t), logger, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()

	rec := httptest.NewRecorder()
	app.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/rooms/missing", nil))

	logs := output.String()
	if !strings.Contains(logs, `"component":"store/rooms"`) || !strings.Contains(logs, `"component":"server/http"`) {
		t.Fatalf("production app did not use configured logger: %s", logs)
	}
}

func TestHTTPObservationPreservesMissingFlusherBehavior(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	s := NewServerWithLogger(store, NewHub(), discardLogger())
	mux := newTestMux(s)
	room := mustCreateRoom(t, store)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	req := httptest.NewRequest(http.MethodGet, "/rooms/"+room.Code+"/events", nil).WithContext(ctx)
	writer := &noFlushWriter{header: http.Header{}}
	mux.ServeHTTP(writer, req)

	if writer.code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want %d", writer.code, http.StatusInternalServerError)
	}
}

func TestRunLogsServerLifecycleFailure(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelDebug}))

	secret := "token=SENTINEL_DO_NOT_LOG"
	cfg := testConfig(t)
	cfg.Address = "127.0.0.1:" + secret
	err := run(context.Background(), cfg, logger, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err == nil {
		t.Fatal("Run returned nil for invalid listen address")
	}
	logs := output.String()
	if strings.Contains(logs, "server listening") || !strings.Contains(logs, `"error_kind":"listen_failed"`) || strings.Contains(logs, secret) {
		t.Fatalf("lifecycle logs misreported listener state or exposed the address: %s", logs)
	}
}

func TestRunAcceptsNilLogger(t *testing.T) {
	cfg := testConfig(t)
	cfg.Address = "127.0.0.1:99999"
	if err := run(context.Background(), cfg, nil, Dependencies{CheckExecutable: func(string) bool { return true }}); err == nil {
		t.Fatal("Run returned nil for invalid listen address")
	}
}

func TestStreamErrorKindUsesStableSafeCategories(t *testing.T) {
	for name, tc := range map[string]struct {
		err  error
		want string
	}{
		"client disconnect": {err: stream.ErrClientWrite, want: "client_disconnected"},
		"not found":         {err: stream.ErrNotFound, want: "not_found"},
		"invalid range":     {err: stream.ErrInvalidRange, want: "invalid_range"},
		"upstream":          {err: errors.New("secret-bearing upstream detail"), want: "upstream_failure"},
	} {
		t.Run(name, func(t *testing.T) {
			if got := streamErrorKind(tc.err); got != tc.want {
				t.Fatalf("kind = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestSafeDiagnosticCauseDistinguishesOperationalFailures(t *testing.T) {
	for name, tc := range map[string]struct {
		err  error
		want string
	}{
		"deadline": {err: context.DeadlineExceeded, want: "deadline_exceeded"},
		"canceled": {err: context.Canceled, want: "canceled"},
		"process":  {err: &exec.ExitError{}, want: "process_exit"},
		"other":    {err: errors.New("secret-bearing detail"), want: "internal_error"},
	} {
		t.Run(name, func(t *testing.T) {
			if got := safeDiagnosticCause(tc.err); got != tc.want {
				t.Fatalf("cause = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestExpectedClientFailuresStayBelowDefaultWarnLevel(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelWarn}))
	s := NewServerWithLogger(NewStore(time.Hour, time.Hour, &seqCodeGen{}), NewHub(), logger)

	for _, err := range []error{resolver.ErrInvalid, resolver.ErrUnsupported, resolver.ErrNoAnonymous} {
		s.logResolverFailure(err)
	}
	for _, err := range []error{stream.ErrNotFound, stream.ErrInvalidRange, stream.ErrClientWrite} {
		s.logStreamFailure(err)
	}
	for _, status := range []int{http.StatusRequestTimeout, http.StatusTooManyRequests, http.StatusBadGateway} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/limited", nil)
		s.observeHTTP(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(status) })(rec, req)
	}
	if output.Len() != 0 {
		t.Fatalf("generic request observations reached WARN logs: %s", output.String())
	}

	s.logResolverFailure(errors.Join(resolver.ErrService, &exec.ExitError{}))
	s.logStreamFailure(errors.New("upstream failed"))
	logs := output.String()
	if !strings.Contains(logs, `"component":"resolver"`) ||
		!strings.Contains(logs, `"cause":"process_exit"`) ||
		!strings.Contains(logs, `"component":"stream"`) {
		t.Fatalf("operational failures missing WARN diagnostics: %s", logs)
	}
}

func TestExpectedMissingRoomDoesNotWriteAtDefaultWarnLevel(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelWarn}))
	s := NewServerWithLogger(NewStore(time.Hour, time.Hour, &seqCodeGen{}), NewHub(), logger)

	rec := httptest.NewRecorder()
	newTestMux(s).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/rooms/missing", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	if output.Len() != 0 {
		t.Fatalf("expected 4xx outcome flooded WARN logs: %s", output.String())
	}
}

type headerCountingWriter struct {
	header http.Header
	calls  int
}

func (w *headerCountingWriter) Header() http.Header       { return w.header }
func (w *headerCountingWriter) Write([]byte) (int, error) { return 0, nil }
func (w *headerCountingWriter) WriteHeader(int)           { w.calls++ }

func TestResponseStatusWriterIgnoresRepeatedWriteHeader(t *testing.T) {
	underlying := &headerCountingWriter{header: http.Header{}}
	writer := &responseStatusWriter{ResponseWriter: underlying}
	writer.WriteHeader(http.StatusNotFound)
	writer.WriteHeader(http.StatusInternalServerError)
	if underlying.calls != 1 || writer.status != http.StatusNotFound {
		t.Fatalf("calls=%d status=%d", underlying.calls, writer.status)
	}
}

func TestNilLoggerConstructorsUseSafeFallback(t *testing.T) {
	app, err := NewApp(testConfig(t), nil, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err != nil {
		t.Fatal(err)
	}
	app.Close()

	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	server := NewServerWithLogger(store, NewHubWithLogger(nil), nil)
	rec := httptest.NewRecorder()
	newTestMux(server).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/rooms/missing", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}
