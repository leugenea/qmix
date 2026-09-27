package server

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

type acceptingListener struct {
	net.Listener
	accepting chan struct{}
	once      sync.Once
}

func (l *acceptingListener) Accept() (net.Conn, error) {
	l.once.Do(func() { close(l.accepting) })
	return l.Listener.Accept()
}

// qmix#207: a connected TV must not consume the entire 11-second HTTP grace.
func TestRunShutdownWithSSEFinishesBeforeHTTPGrace(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	observed := &acceptingListener{Listener: listener, accepting: make(chan struct{})}
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	cfg := testConfig(t)
	finished := make(chan error, 1)
	go func() {
		finished <- run(ctx, cfg, nil, Dependencies{
			CheckExecutable: func(string) bool { return true },
			Listen:          func(string, string) (net.Listener, error) { return observed, nil },
		})
	}()
	select {
	case <-observed.accepting:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not start accepting")
	}

	client := &http.Client{Timeout: 30 * time.Second}
	response, err := client.Post("http://"+listener.Addr().String()+"/rooms", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	var room struct {
		Code string `json:"code"`
	}
	err = json.NewDecoder(response.Body).Decode(&room)
	_ = response.Body.Close()
	if err != nil || response.StatusCode != http.StatusCreated {
		t.Fatalf("create room status = %d, decode error = %v", response.StatusCode, err)
	}

	events, err := client.Get(fmt.Sprintf("http://%s/rooms/%s/events", listener.Addr(), room.Code))
	if err != nil {
		t.Fatal(err)
	}
	defer events.Body.Close()
	if events.StatusCode != http.StatusOK {
		t.Fatalf("SSE status = %d", events.StatusCode)
	}
	line, err := bufio.NewReader(events.Body).ReadString('\n')
	if err != nil || !strings.HasPrefix(line, "id: ") {
		t.Fatalf("SSE snapshot line = %q, error = %v", line, err)
	}

	stop()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatalf("shutdown error = %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("qmix#207: SSE connection held shutdown past the early bound")
	}
}

// qmix#207: contention in the SSE hub must not postpone listener close or
// extend the shutdown budget. The lock stays held past the injected deadline.
func TestServeHTTPBoundsContendedSSEDrainAfterClosingListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	observed := &closeObservedListener{Listener: listener, closed: make(chan struct{})}
	accepting := &acceptingListener{Listener: observed, accepting: make(chan struct{})}
	hub := NewHub()
	entered, drained := make(chan struct{}), make(chan struct{})
	hub.mu.Lock()
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(hub.mu.Unlock) }
	defer release()

	server := newHTTPServer(observed.Addr().String(), http.NotFoundHandler())
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	done := make(chan error, 1)
	go func() {
		done <- serveHTTP(ctx, server, accepting, func() {}, func() {
			close(entered)
			hub.StopSubscriptions()
			close(drained)
		}, 40*time.Millisecond)
	}()

	select {
	case <-accepting.accepting:
	case <-time.After(2 * time.Second):
		t.Fatal("server did not begin accepting")
	}
	stop()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("SSE drain did not start")
	}
	select {
	case <-observed.closed:
	case <-time.After(400 * time.Millisecond):
		t.Fatal("SSE lock contention postponed listener close")
	}
	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("shutdown before drain finished = %v, want deadline exceeded", err)
		}
	case <-time.After(400 * time.Millisecond):
		t.Fatal("SSE lock contention extended the shutdown deadline")
	}
	release()
	select {
	case <-drained:
	case <-time.After(2 * time.Second):
		t.Fatal("SSE drain did not complete after lock release")
	}
}

// The Compose stop allowance must exceed the entire process deadline, not
// merely its HTTP grace. The margin also covers container teardown (qmix#207).
func TestComposeStopGraceExceedsProcessDeadline(t *testing.T) {
	data, err := os.ReadFile("../../docker-compose.yml")
	if err != nil {
		t.Fatal(err)
	}
	if err := checkBackendStopGrace(string(data)); err != nil {
		t.Fatal(err)
	}
}

// This is deliberately a parser for the repository's canonical, unquoted
// Compose path, not YAML. Unsupported target shapes fail closed.
type composeScope uint8

const (
	outsideServices composeScope = iota
	insideServices
	insideBackend
	insideOtherService
)

type composeStopGraceState struct {
	scope        composeScope
	servicesSeen bool
	backendSeen  bool
	graceSeen    bool
	graceValue   string
}

func checkBackendStopGrace(compose string) error {
	var state composeStopGraceState
	for number, line := range strings.Split(compose, "\n") {
		if err := state.consume(line); err != nil {
			return fmt.Errorf("Compose line %d: %w", number+1, err)
		}
	}
	if !state.servicesSeen || !state.backendSeen || !state.graceSeen {
		return fmt.Errorf("missing services.backend.stop_grace_period")
	}
	duration, err := time.ParseDuration(state.graceValue)
	if err != nil || duration < shutdownTimeout+time.Second {
		return fmt.Errorf("backend stop_grace_period = %q (parse error %v), want >= %v", state.graceValue, err, shutdownTimeout+time.Second)
	}
	return nil
}

func canonicalComposeLine(line string) (indent int, key, value string, ok bool, err error) {
	trimmed := strings.TrimSpace(line)
	if trimmed == "" || strings.HasPrefix(trimmed, "#") {
		return
	}
	if strings.ContainsRune(line, '	') {
		err = fmt.Errorf("tabs are not canonical indentation")
		return
	}
	indent = len(line) - len(strings.TrimLeft(line, " "))
	key, value, ok = strings.Cut(trimmed, ":")
	if ok {
		err = validateComposeTargetKey(key, indent)
	}
	return
}

func validateComposeTargetKey(rawKey string, indent int) error {
	key := strings.Trim(rawKey, " '\"")
	if key != "services" && key != "backend" && key != "stop_grace_period" {
		return nil
	}
	if key != rawKey || wrongComposeIndent(key, indent) {
		return fmt.Errorf("noncanonical %q key or indentation", key)
	}
	return nil
}

func wrongComposeIndent(key string, indent int) bool {
	switch key {
	case "services":
		return indent != 0
	case "backend":
		return indent != 2
	case "stop_grace_period":
		return indent != 4
	}
	return false
}

func (s *composeStopGraceState) consume(line string) error {
	indent, key, value, ok, err := canonicalComposeLine(line)
	if err != nil {
		return err
	}
	if !ok {
		return nil
	}
	switch indent {
	case 0:
		return s.topLevel(key, value)
	case 2:
		return s.service(key, value)
	case 4:
		return s.serviceField(key, value)
	}
	return nil
}

func (s *composeStopGraceState) topLevel(key, value string) error {
	s.scope = outsideServices
	if key != "services" {
		return nil
	}
	if s.servicesSeen || strings.TrimSpace(value) != "" {
		return fmt.Errorf("duplicate or non-mapping services")
	}
	s.servicesSeen = true
	s.scope = insideServices
	return nil
}

func (s *composeStopGraceState) service(key, value string) error {
	if s.scope == outsideServices {
		return nil
	}
	s.scope = insideOtherService
	if key != "backend" {
		return nil
	}
	if s.backendSeen || strings.TrimSpace(value) != "" {
		return fmt.Errorf("duplicate or non-mapping services.backend")
	}
	s.backendSeen = true
	s.scope = insideBackend
	return nil
}

func (s *composeStopGraceState) serviceField(key, value string) error {
	if s.scope != insideBackend || key != "stop_grace_period" {
		return nil
	}
	if s.graceSeen {
		return fmt.Errorf("duplicate services.backend.stop_grace_period")
	}
	s.graceSeen = true
	s.graceValue = strings.TrimSpace(value)
	return nil
}

func TestComposeStopGraceCanonicalPaths(t *testing.T) {
	const valid = "services:\n  backend:\n    stop_grace_period: 15s\n"
	for _, tc := range []struct {
		name    string
		compose string
		valid   bool
	}{
		{"valid canonical shape", valid, true},
		{"minimum accepted margin", "services:\n  backend:\n    stop_grace_period: 13s\n", true},
		{"duration below margin", "services:\n  backend:\n    stop_grace_period: 12s\n", false},
		{"invalid duration", "services:\n  backend:\n    stop_grace_period: someday\n", false},
		{"another service only", "services:\n  other:\n    stop_grace_period: 15s\n", false},
		{"another service cannot supply backend value", "services:\n  backend:\n    image: qmix:local\n  other:\n    stop_grace_period: 15s\n", false},
		{"missing services", "name: qmix\n  backend:\n    stop_grace_period: 15s\n", false},
		{"missing backend", "services:\n  other:\n    image: busybox\n", false},
		{"missing grace key", "services:\n  backend:\n    image: qmix:local\n", false},
		{"missing grace value", "services:\n  backend:\n    stop_grace_period:\n", false},
		{"duplicate services with backend", valid + valid, false},
		{"duplicate services without backend", valid + "services:\n  other:\n    image: busybox\n", false},
		{"duplicate services with whitespace before colon", valid + "services :\n  other:\n    image: busybox\n", false},
		{"duplicate services with quoted key", valid + "'services':\n  other:\n    image: busybox\n", false},
		{"duplicate backend", valid + "  backend:\n    image: qmix:local\n", false},
		{"duplicate backend grace", valid + "    stop_grace_period: 14s\n", false},
		{"unrelated top level before", "name: qmix\n" + valid, true},
		{"unrelated top level after", valid + "networks:\n  default:\n", true},
		{"other service before backend", "services:\n  other:\n    stop_grace_period: 1s\n  backend:\n    stop_grace_period: 15s\n", true},
		{"other service after backend", valid + "  other:\n    stop_grace_period: 1s\n", true},
		{"blank and comment lines preserve backend", "services:\n  backend:\n\n    # shutdown margin\n    stop_grace_period: 15s\n", true},
		{"misindented services", valid + " services:\n", false},
		{"misindented backend", valid + "   backend:\n", false},
		{"misindented grace", valid + "     stop_grace_period: 14s\n", false},
		{"tab indentation", valid + "	stop_grace_period: 14s\n", false},
		{"backend scalar not mapping", "services:\n  backend: wrong\n    stop_grace_period: 15s\n", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			err := checkBackendStopGrace(tc.compose)
			if (err == nil) != tc.valid {
				t.Fatalf("checkBackendStopGrace() = %v, want valid=%t", err, tc.valid)
			}
		})
	}
}

type shutdownBlockedWriter struct {
	header           http.Header
	blockOnWrite     string
	blockOnFlush     int
	flushes          int
	entered          chan struct{}
	snapshotFlushed  chan struct{}
	deadlineAdvanced chan struct{}
	release          chan struct{}
	deadlineOnce     sync.Once
}

func (w *shutdownBlockedWriter) Header() http.Header { return w.header }
func (w *shutdownBlockedWriter) WriteHeader(int)     {}
func (w *shutdownBlockedWriter) Flush()              {}
func (w *shutdownBlockedWriter) SetWriteDeadline(deadline time.Time) error {
	if !deadline.After(time.Now()) {
		w.deadlineOnce.Do(func() { close(w.deadlineAdvanced) })
	}
	return nil
}
func (w *shutdownBlockedWriter) waitForDeadline() error {
	close(w.entered)
	select {
	case <-w.deadlineAdvanced:
		return context.DeadlineExceeded
	case <-w.release:
		return nil
	}
}
func (w *shutdownBlockedWriter) Write(p []byte) (int, error) {
	if strings.Contains(string(p), w.blockOnWrite) && w.blockOnWrite != "" {
		if err := w.waitForDeadline(); err != nil {
			return 0, err
		}
	}
	return len(p), nil
}
func (w *shutdownBlockedWriter) FlushError() error {
	w.flushes++
	if w.flushes == w.blockOnFlush {
		return w.waitForDeadline()
	}
	if w.flushes == 1 {
		close(w.snapshotFlushed)
	}
	return nil
}

// qmix#207: closing done must interrupt a writer already blocked in snapshot,
// event, or flush output, without canceling the ordinary request context.
func TestHubStopSubscriptionsInterruptsBlockedSSEOutput(t *testing.T) {
	for _, tc := range []struct {
		name         string
		blockWrite   string
		blockFlushAt int
		publish      bool
	}{
		{name: "snapshot write", blockWrite: "id: 0"},
		{name: "snapshot flush", blockFlushAt: 1},
		{name: "event write", blockWrite: "event: queue_updated", publish: true},
		{name: "event flush", blockFlushAt: 2, publish: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			hub := NewHub()
			ref := roomRef{code: "blocked"}
			sub, cancelSub := hub.subscribeRef(ref)
			writer := &shutdownBlockedWriter{
				header: make(http.Header), blockOnWrite: tc.blockWrite,
				blockOnFlush: tc.blockFlushAt, entered: make(chan struct{}),
				snapshotFlushed: make(chan struct{}), deadlineAdvanced: make(chan struct{}),
				release: make(chan struct{}),
			}
			ctx, cancelRequest := context.WithCancel(context.Background())
			finished := make(chan struct{})
			go func() {
				defer close(finished)
				hub.serveSubscriptionHTTP(ctx, writer, sub, cancelSub, eventSnapshot{Data: map[string]interface{}{"version": 0}})
			}()
			t.Cleanup(func() {
				close(writer.release)
				cancelRequest()
				select {
				case <-finished:
				case <-time.After(2 * time.Second):
					t.Error("SSE handler did not finish after cleanup")
				}
			})
			if tc.publish {
				select {
				case <-writer.snapshotFlushed:
				case <-time.After(2 * time.Second):
					t.Fatal("snapshot did not flush before event")
				}
				hub.publishRef(ref, "queue_updated", 1)
			}
			select {
			case <-writer.entered:
			case <-time.After(2 * time.Second):
				t.Fatal("SSE output did not block")
			}
			hub.StopSubscriptions()
			select {
			case <-finished:
			case <-time.After(300 * time.Millisecond):
				t.Fatal("stopped subscription did not interrupt blocked SSE output")
			}
			if ctx.Err() != nil {
				t.Fatal("SSE shutdown canceled the ordinary request context")
			}
		})
	}
}

// qmix#207: a registration racing with the stop cannot escape the SSE drain.
func TestHubStopSubscriptionsIncludesConcurrentAndLateSubscribers(t *testing.T) {
	hub := NewHub()
	ref := roomRef{code: "room"}
	previous, cancelPrevious := hub.subscribeRef(ref)
	defer cancelPrevious()

	hub.mu.Lock()
	attached := make(chan struct {
		sub    *subscriber
		cancel func()
	}, 1)
	go func() {
		sub, cancel := hub.subscribeRef(ref)
		attached <- struct {
			sub    *subscriber
			cancel func()
		}{sub, cancel}
	}()
	stopped := make(chan struct{})
	go func() {
		hub.StopSubscriptions()
		close(stopped)
	}()
	hub.mu.Unlock()

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("SSE drain blocked")
	}
	select {
	case <-previous.done:
	default:
		t.Fatal("preexisting subscriber remains attached")
	}
	select {
	case registration := <-attached:
		defer registration.cancel()
		select {
		case <-registration.sub.done:
		default:
			t.Fatal("concurrent subscriber remains attached")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("concurrent subscription blocked")
	}
	hub.StopSubscriptions()
	late, cancelLate := hub.subscribeRef(ref)
	defer cancelLate()
	select {
	case <-late.done:
	default:
		t.Fatal("late subscriber remains attached")
	}
	if len(hub.incarnations) != 0 {
		t.Fatal("stopped hub retained SSE subscribers")
	}
}
