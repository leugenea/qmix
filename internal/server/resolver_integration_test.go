package server

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
)

// stubResolver resolves configured URLs to fixed metadata and returns a canned
// error otherwise, so HTTP integration tests don't touch the network.
type stubResolver struct {
	meta *resolver.Track
	err  error
}

func (s stubResolver) Resolve(_ context.Context, _ string) (*resolver.Track, error) {
	return s.meta, s.err
}

type blockingResolver struct {
	started chan struct{}
	release chan struct{}
}

type deadlineMetadataRunner struct {
	stopped chan struct{}
}

func (r deadlineMetadataRunner) Run(ctx context.Context, _ string) ([]byte, error) {
	<-ctx.Done()
	close(r.stopped)
	return nil, ctx.Err()
}

func (r blockingResolver) Resolve(_ context.Context, _ string) (*resolver.Track, error) {
	close(r.started)
	<-r.release
	return &resolver.Track{Title: "Resolved track", ResolvedBy: "test"}, nil
}

type countingResolver struct {
	calls int
}

func (r *countingResolver) Resolve(_ context.Context, _ string) (*resolver.Track, error) {
	r.calls++
	return &resolver.Track{Title: "Resolved track", ResolvedBy: "test"}, nil
}

type onReadReader struct {
	once   sync.Once
	onRead func()
	reader io.Reader
}

func (r *onReadReader) Read(p []byte) (int, error) {
	r.once.Do(r.onRead)
	return r.reader.Read(p)
}

func receiveWithin[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case value := <-ch:
		return value
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for test synchronization")
		var zero T
		return zero
	}
}

// newResolverTestServer returns a Server with the given resolver wired in.
func newResolverTestServer(r resolver.Resolver) (*Server, *Store) {
	gen := &seqCodeGen{}
	store := NewStore(time.Hour, time.Hour, gen)
	hub := NewHub()
	s := NewServer(store, hub)
	s.Resolver = r
	return s, store
}

// TestAddTrackResolvesMetadata verifies a resolved track carries metadata and
// resolvedBy in the queue.
func TestAddTrackResolvesMetadata(t *testing.T) {
	r := stubResolver{meta: &resolver.Track{
		Title: "Some Song", Artist: "Some Artist", DurationSec: 199, Source: "https://open.spotify.com/track/x", ResolvedBy: "spotify",
	}}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://open.spotify.com/track/x")
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var track Track
	decodeBody(t, rec, &track)
	if track.Title != "Some Song" || track.Artist != "Some Artist" || track.DurationSec != 199 || track.ResolvedBy != "spotify" {
		t.Fatalf("track = %+v", track)
	}

	// Persisted in the room view too (stream_url untouched in M2).
	rec = doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 1 || view.Queue[0].Title != "Some Song" || view.Queue[0].Artist != "Some Artist" {
		t.Fatalf("view = %+v", view)
	}
}

// TestAddTrackRejectsRoomExpiredDuringResolution covers qmix#90: resolution
// must not return success after the janitor removes the room.
func TestAddTrackRejectsRoomExpiredDuringResolution(t *testing.T) {
	r := blockingResolver{started: make(chan struct{}), release: make(chan struct{})}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	responses := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		responses <- addTrack(t, mux, code, "https://example.com/song")
	}()
	receiveWithin(t, r.started)

	store.mu.Lock()
	store.rooms[code].LastActivity = time.Now().Add(-2 * store.TTL)
	store.mu.Unlock()
	store.sweep()
	close(r.release)

	rec := receiveWithin(t, responses)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
	if store.Exists(code) {
		t.Fatalf("room %q still exists, want expired room to remain absent", code)
	}
}

// TestAddTrackRejectsReplacementRoomDuringResolution covers room-code reuse:
// an add started for an expired room must not mutate a newer room with the same
// code.
func TestAddTrackRejectsReplacementRoomDuringResolution(t *testing.T) {
	r := blockingResolver{started: make(chan struct{}), release: make(chan struct{})}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	responses := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		responses <- addTrack(t, mux, code, "https://example.com/song")
	}()
	receiveWithin(t, r.started)

	store.mu.Lock()
	delete(store.rooms, code)
	replacement := &Room{Code: code, HostToken: newToken(), LastActivity: time.Now()}
	store.rooms[code] = replacement
	store.mu.Unlock()
	ch, cancel := s.hub.Subscribe(code)
	defer cancel()
	close(r.release)

	rec := receiveWithin(t, responses)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
	store.mu.Lock()
	queueLen := len(replacement.Queue)
	store.mu.Unlock()
	if queueLen != 0 {
		t.Fatalf("replacement queue length = %d, want 0", queueLen)
	}
	if len(ch) != 0 {
		t.Fatalf("published %d events for replacement room, want none", len(ch))
	}
}

func TestAddTrackDoesNotResolveWhenQueueAlreadyFull(t *testing.T) {
	r := &countingResolver{}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	store.mu.Lock()
	store.rooms[code].Queue = make([]Track, 100)
	store.mu.Unlock()

	rec := addTrack(t, mux, code, "https://example.com/overflow")
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}
	if r.calls != 0 {
		t.Fatalf("resolver calls = %d, want 0 for an already-full queue", r.calls)
	}
}

func TestAddTrackDoesNotResolveWhenRoomDisappearsDuringDecode(t *testing.T) {
	r := &countingResolver{}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, token := createRoom(t, mux)
	body := &onReadReader{
		onRead: func() {
			store.mu.Lock()
			delete(store.rooms, code)
			store.mu.Unlock()
		},
		reader: strings.NewReader(`{"url":"https://example.com/song"}`),
	}
	req := httptest.NewRequest(http.MethodPost, "/rooms/"+code+"/queue", body)
	req.Header.Set("X-Host-Token", token)
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
	if r.calls != 0 {
		t.Fatalf("resolver calls = %d, want 0", r.calls)
	}
}

func TestAddTrackRejectsQueueFilledDuringResolution(t *testing.T) {
	r := blockingResolver{started: make(chan struct{}), release: make(chan struct{})}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	store.mu.Lock()
	store.rooms[code].Queue = make([]Track, 99)
	store.mu.Unlock()

	responses := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		responses <- addTrack(t, mux, code, "https://example.com/overflow")
	}()
	receiveWithin(t, r.started)
	store.mu.Lock()
	store.rooms[code].Queue = append(store.rooms[code].Queue, Track{ID: "concurrent"})
	store.mu.Unlock()
	close(r.release)

	rec := receiveWithin(t, responses)
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}
}

// TestAddTrackUnknownDomain422 verifies unknown domains map to 422 (not 500).
func TestAddTrackUnknownDomain422(t *testing.T) {
	r := stubResolver{err: resolver.ErrUnsupported}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://example.com/song")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
	if !containsStr(rec.Body.String(), "unsupported") {
		t.Fatalf("body = %q, want human-readable message", rec.Body.String())
	}
}

// TestAddTrackInvalidLink422 verifies an invalid URL maps to 422.
func TestAddTrackInvalidLink422(t *testing.T) {
	r := stubResolver{err: resolver.ErrInvalid}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "::::not a url")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
}

// TestAddTrackNoAnonymous422 maps no-anonymous-path to 422.
func TestAddTrackNoAnonymous422(t *testing.T) {
	r := stubResolver{err: resolver.ErrNoAnonymous}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://vk.com/audio1")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
}

// TestAddTrackServiceError502 maps an upstream failure to 502.
func TestAddTrackServiceError502(t *testing.T) {
	r := stubResolver{err: errors.New("spotify: service error: upstream down")}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://open.spotify.com/track/x")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusBadGateway, rec.Body.String())
	}
}

// TestAddTrackMetadataDeadline verifies a hung yt-dlp metadata lookup is
// terminated and exposed as a bounded 502 API response (qmix#116).
func TestAddTrackMetadataDeadline(t *testing.T) {
	stopped := make(chan struct{})
	r := resolver.NewMux(resolver.Matcher{
		Domains: []string{"youtube.com", "youtu.be"},
		Resolver: &resolver.YouTube{
			Runner:  deadlineMetadataRunner{stopped: stopped},
			Timeout: time.Millisecond,
		},
	})
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://youtu.be/abc")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502; body=%s", rec.Code, rec.Body.String())
	}
	select {
	case <-stopped:
	default:
		t.Fatal("yt-dlp metadata lookup still active after API response")
	}
}

// TestAddTrackResolvedQueueNotGrown verifies failed resolution does not append.
func TestAddTrackResolvedQueueNotGrown(t *testing.T) {
	r := stubResolver{err: resolver.ErrUnsupported}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	addTrack(t, mux, code, "https://example.com/x")
	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty on failed resolution", view.Queue)
	}
}

func containsStr(s, sub string) bool {
	return strings.Contains(s, sub)
}
