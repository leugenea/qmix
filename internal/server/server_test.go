package server

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// newTestServer returns a Server with a deterministic code generator and a
// fresh hub, plus the underlying store for direct manipulation.
func newTestServer() (*Server, *Store) {
	gen := &seqCodeGen{}
	store := NewStore(time.Hour, time.Hour, gen)
	hub := NewHub()
	return NewServer(store, hub), store
}

// seqCodeGen produces codes "aaaaaa", "aaaaab", ... deterministically.
type seqCodeGen struct {
	n int
}

func (g *seqCodeGen) Generate() string {
	const alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
	n := g.n
	g.n++
	var b [codeLength]byte
	for i := codeLength - 1; i >= 0; i-- {
		b[i] = alphabet[n%len(alphabet)]
		n /= len(alphabet)
	}
	return string(b[:])
}

// newTestMux builds a mux with the server routes registered.
func newTestMux(s *Server) *http.ServeMux {
	mux := http.NewServeMux()
	s.Routes(mux)
	return mux
}

func doReq(t *testing.T, mux *http.ServeMux, method, path, body, token string) *httptest.ResponseRecorder {
	t.Helper()
	var reader *strings.Reader
	if body == "" {
		reader = strings.NewReader("")
	} else {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, path, reader)
	if token != "" {
		req.Header.Set("X-Host-Token", token)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func decodeBody(t *testing.T, rec *httptest.ResponseRecorder, v interface{}) {
	t.Helper()
	if err := json.Unmarshal(rec.Body.Bytes(), v); err != nil {
		t.Fatalf("invalid json: %v; body=%s", err, rec.Body.String())
	}
}

func createRoom(t *testing.T, mux *http.ServeMux) (code, token string) {
	t.Helper()
	rec := doReq(t, mux, http.MethodPost, "/rooms", "", "")
	if rec.Code != http.StatusCreated {
		t.Fatalf("create room status = %d, want %d", rec.Code, http.StatusCreated)
	}
	var resp struct {
		Code      string `json:"code"`
		HostToken string `json:"host_token"`
		URL       string `json:"url"`
	}
	decodeBody(t, rec, &resp)
	if resp.Code == "" || resp.HostToken == "" || resp.URL == "" {
		t.Fatalf("create room missing fields: %+v", resp)
	}
	return resp.Code, resp.HostToken
}

func addTrack(t *testing.T, mux *http.ServeMux, code, url string) *httptest.ResponseRecorder {
	t.Helper()
	body := fmt.Sprintf(`{"url":%q}`, url)
	return doReq(t, mux, http.MethodPost, "/rooms/"+code+"/queue", body, "")
}

func TestCreateAndGetRoom(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	code, _ := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("get room status = %d, want %d", rec.Code, http.StatusOK)
	}
	var view struct {
		Code    string   `json:"code"`
		Current *curView `json:"current"`
		Queue   []Track  `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if view.Code != code {
		t.Fatalf("code = %q, want %q", view.Code, code)
	}
	if view.Current != nil {
		t.Fatalf("current = %+v, want nil", view.Current)
	}
	if len(view.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty", view.Queue)
	}
	if strings.Contains(rec.Body.String(), "host_token") {
		t.Fatalf("host_token leaked in GET response: %s", rec.Body.String())
	}
}

func TestGetUnknownRoom(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	rec := doReq(t, mux, http.MethodGet, "/rooms/nope42", "", "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestAddTrack(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://example.com/song")
	if rec.Code != http.StatusCreated {
		t.Fatalf("add status = %d, want %d; body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var track Track
	decodeBody(t, rec, &track)
	if track.ID == "" || track.URL != "https://example.com/song" {
		t.Fatalf("track = %+v", track)
	}

	// Queue grew.
	rec = doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 1 {
		t.Fatalf("queue len = %d, want 1", len(view.Queue))
	}
}

func TestAppendTrackDoesNotExposeMutationBeforePublication(t *testing.T) {
	s, store := newTestServer()
	room := store.CreateRoom()
	ch, cancel := s.hub.Subscribe(room.Code)
	defer cancel()

	// Stall publication. The room mutation must remain protected by store.mu
	// until Hub.Publish has assigned its event ID and accepted the event.
	s.hub.mu.Lock()
	started := make(chan struct{})
	done := make(chan struct{})
	go func() {
		close(started)
		s.appendTrack(room, trackFromURL("https://example.com/concurrent"))
		close(done)
	}()
	<-started

	deadline := time.Now().Add(200 * time.Millisecond)
	for time.Now().Before(deadline) {
		if store.mu.TryLock() {
			queueLen := len(room.Queue)
			store.mu.Unlock()
			if queueLen != 0 {
				s.hub.mu.Unlock()
				<-done
				t.Fatal("queue mutation became visible while its SSE publication was blocked")
			}
		}
		time.Sleep(time.Millisecond)
	}

	s.hub.mu.Unlock()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("append did not finish after publication was unblocked")
	}
	event := <-ch
	if event.Name != "queue_updated" {
		t.Fatalf("event name = %q, want queue_updated", event.Name)
	}
}

func TestAddTrackBadJSON(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/queue", "{not json", "")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusBadRequest)
	}
}

func TestAddTrackEmptyURL(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusBadRequest)
	}
}

func TestSkip(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)
	addTrack(t, mux, code, "https://a.example/1")
	addTrack(t, mux, code, "https://a.example/2")

	// No token -> 403.
	rec := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", "")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("no token status = %d, want %d", rec.Code, http.StatusForbidden)
	}

	// Wrong token -> 403.
	rec = doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", "wrong")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("wrong token status = %d, want %d", rec.Code, http.StatusForbidden)
	}

	// Correct token -> current advances, state playing.
	rec = doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if rec.Code != http.StatusOK {
		t.Fatalf("skip status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp struct {
		Current curView `json:"current"`
	}
	decodeBody(t, rec, &resp)
	if resp.Current.State != "playing" || resp.Current.PosSec != 0 {
		t.Fatalf("current = %+v", resp.Current)
	}

	// Second skip moves to next track.
	rec = doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if rec.Code != http.StatusOK {
		t.Fatalf("second skip status = %d, want %d", rec.Code, http.StatusOK)
	}
	decodeBody(t, rec, &resp)
	if resp.Current.TrackID == "" {
		t.Fatalf("current track id empty after second skip")
	}
}

func TestCurrentPayloadIncludesTrackMetadata(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://a.example/song")
	var queued Track
	decodeBody(t, rec, &queued)

	s.store.mu.Lock()
	room := s.store.rooms[code]
	room.Queue[0].Title = "Song title"
	room.Queue[0].Artist = "Song artist"
	s.store.mu.Unlock()

	rec = doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if rec.Code != http.StatusOK {
		t.Fatalf("skip status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var skipped struct {
		Current struct {
			TrackID string `json:"track_id"`
			Title   string `json:"title"`
			Artist  string `json:"artist"`
		} `json:"current"`
	}
	decodeBody(t, rec, &skipped)
	if skipped.Current.TrackID != queued.ID || skipped.Current.Title != "Song title" || skipped.Current.Artist != "Song artist" {
		t.Fatalf("skip current = %+v", skipped.Current)
	}

	rec = doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Current struct {
			Title  string `json:"title"`
			Artist string `json:"artist"`
		} `json:"current"`
	}
	decodeBody(t, rec, &view)
	if view.Current.Title != "Song title" || view.Current.Artist != "Song artist" {
		t.Fatalf("room current = %+v", view.Current)
	}
}

func TestSkipPublishesUpdatedQueue(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)
	addTrack(t, mux, code, "https://a.example/song")
	ch, cancel := s.hub.Subscribe(code)
	defer cancel()

	rec := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if rec.Code != http.StatusOK {
		t.Fatalf("skip status = %d, want %d", rec.Code, http.StatusOK)
	}

	var queueEvent *Event
	for len(ch) > 0 {
		event := <-ch
		if event.Name == "queue_updated" {
			queueEvent = &event
		}
	}
	if queueEvent == nil {
		t.Fatal("skip did not publish queue_updated")
	}
	data, err := json.Marshal(queueEvent.Data)
	if err != nil {
		t.Fatalf("marshal queue event: %v", err)
	}
	var payload struct {
		Queue []Track `json:"queue"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		t.Fatalf("decode queue event: %v", err)
	}
	if len(payload.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty after skip", payload.Queue)
	}
}

func TestSkipEmptyQueue(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusBadRequest, rec.Body.String())
	}
}

func TestReorder(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)
	addTrack(t, mux, code, "https://a.example/1")
	addTrack(t, mux, code, "https://a.example/2")
	addTrack(t, mux, code, "https://a.example/3")

	// Collect current ids.
	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	ids := make([]string, len(view.Queue))
	for i, tr := range view.Queue {
		ids[i] = tr.ID
	}

	// Reverse order.
	rev := make([]string, len(ids))
	for i, id := range ids {
		rev[len(ids)-1-i] = id
	}
	order, _ := json.Marshal(map[string][]string{"order": rev})
	rec = doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", string(order), token)
	if rec.Code != http.StatusOK {
		t.Fatalf("reorder status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &resp)
	if len(resp.Queue) != len(ids) {
		t.Fatalf("queue len = %d, want %d", len(resp.Queue), len(ids))
	}
	for i, tr := range resp.Queue {
		if tr.ID != rev[i] {
			t.Fatalf("queue[%d].id = %q, want %q", i, tr.ID, rev[i])
		}
	}
}

func TestReorderNotPermutation(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)
	addTrack(t, mux, code, "https://a.example/1")
	addTrack(t, mux, code, "https://a.example/2")

	// Duplicate id, missing one -> 400.
	rec := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{"order":["x","x"]}`, token)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusBadRequest, rec.Body.String())
	}
}

func TestReorderNoToken(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	addTrack(t, mux, code, "https://a.example/1")

	rec := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{"order":[]}`, "")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusForbidden)
	}
}

// readSSE reads one SSE event from a stream, returning its id, name and data.
func readSSE(t *testing.T, r *bufio.Reader) (id, name, data string) {
	t.Helper()
	var idv, namev string
	var datav []string
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("read sse: %v", err)
		}
		line = strings.TrimRight(line, "\n")
		if line == "" {
			break
		}
		switch {
		case strings.HasPrefix(line, "id: "):
			idv = strings.TrimPrefix(line, "id: ")
		case strings.HasPrefix(line, "event: "):
			namev = strings.TrimPrefix(line, "event: ")
		case strings.HasPrefix(line, "data: "):
			datav = append(datav, strings.TrimPrefix(line, "data: "))
		}
	}
	return idv, namev, strings.Join(datav, "\n")
}

func TestSSESnapshotAndEvents(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)

	// Real server: the SSE handler streams until the client disconnects, so it
	// must be exercised over HTTP, not with a ResponseRecorder.
	ts := httptest.NewServer(mux)
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, ts.URL+"/rooms/"+code+"/events", nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("sse connect: %v", err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("content-type = %q, want text/event-stream", ct)
	}
	br := bufio.NewReader(resp.Body)

	// First event must be queue_snapshot.
	_, name, data := readSSE(t, br)
	if name != "queue_snapshot" {
		t.Fatalf("first event = %q, want queue_snapshot", name)
	}
	var snap struct {
		Current *curView `json:"current"`
		Queue   []Track  `json:"queue"`
	}
	if err := json.Unmarshal([]byte(data), &snap); err != nil {
		t.Fatalf("snapshot json: %v", err)
	}
	if snap.Current != nil || len(snap.Queue) != 0 {
		t.Fatalf("snapshot = %+v", snap)
	}

	// Add a track -> queue_updated.
	addTrack(t, mux, code, "https://a.example/1")
	_, name, data = readSSE(t, br)
	if name != "queue_updated" {
		t.Fatalf("event = %q, want queue_updated", name)
	}
	var qp struct {
		Queue []Track `json:"queue"`
	}
	if err := json.Unmarshal([]byte(data), &qp); err != nil {
		t.Fatalf("queue_updated json: %v", err)
	}
	if len(qp.Queue) != 1 {
		t.Fatalf("queue len = %d, want 1", len(qp.Queue))
	}

	// Skip -> track_changed then player_state.
	doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	_, name, _ = readSSE(t, br)
	if name != "track_changed" {
		t.Fatalf("event = %q, want track_changed", name)
	}
	_, name, _ = readSSE(t, br)
	if name != "player_state" {
		t.Fatalf("event = %q, want player_state", name)
	}
}

func TestServeHTTPSkipsEventsCoveredBySnapshot(t *testing.T) {
	hub := NewHub()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /events", func(w http.ResponseWriter, r *http.Request) {
		hub.ServeHTTP(r.Context(), w, "room", func() (int64, interface{}) {
			hub.Publish("room", "queue_updated", map[string]int{"version": 1})
			hub.Publish("room", "queue_updated", map[string]int{"version": 2})
			return hub.currentID("room"), map[string]int{"version": 2}
		})
	})

	ts := httptest.NewServer(mux)
	defer ts.Close()
	resp, err := ts.Client().Get(ts.URL + "/events")
	if err != nil {
		t.Fatalf("sse connect: %v", err)
	}
	defer resp.Body.Close()
	br := bufio.NewReader(resp.Body)

	id, name, data := readSSE(t, br)
	if id != "2" || name != "queue_snapshot" || data != `{"version":2}` {
		t.Fatalf("snapshot = id %q, event %q, data %s", id, name, data)
	}

	hub.Publish("room", "queue_updated", map[string]int{"version": 3})
	id, name, data = readSSE(t, br)
	if id != "3" || name != "queue_updated" || data != `{"version":3}` {
		t.Fatalf("first live event = id %q, event %q, data %s; want id 3", id, name, data)
	}
}

func TestPublishOverflowTerminatesOnlySlowSubscriber(t *testing.T) {
	hub := NewHub()
	slow, cancelSlow := hub.Subscribe("room")
	defer cancelSlow()

	hub.mu.Lock()
	var slowDone <-chan struct{}
	for sub := range hub.rooms["room"].subs {
		if sub.ch == slow {
			slowDone = sub.done
			break
		}
	}
	hub.mu.Unlock()
	if slowDone == nil {
		t.Fatal("slow subscriber not registered")
	}

	healthy, cancelHealthy := hub.Subscribe("room")
	defer cancelHealthy()
	for wantID := int64(1); wantID <= 17; wantID++ {
		hub.Publish("room", "queue_updated", wantID)
		if event := <-healthy; event.ID != wantID {
			t.Fatalf("healthy subscriber event ID = %d, want %d", event.ID, wantID)
		}
	}

	select {
	case <-slowDone:
	default:
		t.Fatal("overflow did not terminate slow subscriber")
	}

	hub.Publish("room", "queue_updated", int64(18))
	if event := <-healthy; event.ID != 18 {
		t.Fatalf("healthy subscriber stopped after peer overflow: ID = %d", event.ID)
	}
}

func TestConcurrentPublishPreservesEventIDOrder(t *testing.T) {
	for attempt := 0; attempt < 10000; attempt++ {
		hub := NewHub()
		events, cancel := hub.Subscribe("room")
		start := make(chan struct{})
		var publishers sync.WaitGroup
		publishers.Add(2)
		for value := 1; value <= 2; value++ {
			go func() {
				defer publishers.Done()
				<-start
				hub.Publish("room", "queue_updated", value)
			}()
		}
		close(start)
		publishers.Wait()
		first, second := <-events, <-events
		cancel()
		if first.ID >= second.ID {
			t.Fatalf("events delivered out of order on attempt %d: %d then %d", attempt, first.ID, second.ID)
		}
	}
}

type blockingEventWriter struct {
	header  http.Header
	blocked chan struct{}
	release chan struct{}
	mu      sync.Mutex
	writeBy <-chan time.Time
	once    sync.Once
}

func newBlockingEventWriter() *blockingEventWriter {
	return &blockingEventWriter{
		header:  make(http.Header),
		blocked: make(chan struct{}),
		release: make(chan struct{}),
	}
}

func (w *blockingEventWriter) Header() http.Header { return w.header }
func (w *blockingEventWriter) WriteHeader(int)     {}
func (w *blockingEventWriter) Flush()              {}
func (w *blockingEventWriter) SetWriteDeadline(deadline time.Time) error {
	w.mu.Lock()
	w.writeBy = time.After(time.Until(deadline))
	w.mu.Unlock()
	return nil
}
func (w *blockingEventWriter) Write(p []byte) (int, error) {
	if strings.Contains(string(p), "event: queue_updated") {
		w.mu.Lock()
		writeBy := w.writeBy
		w.mu.Unlock()
		timedOut := false
		w.once.Do(func() {
			close(w.blocked)
			select {
			case <-w.release:
			case <-writeBy:
				timedOut = true
			}
		})
		if timedOut {
			return 0, context.DeadlineExceeded
		}
	}
	return len(p), nil
}

func TestServeHTTPReturnsAfterSubscriberOverflow(t *testing.T) {
	hub := NewHub()
	hub.WriteTimeout = 20 * time.Millisecond
	w := newBlockingEventWriter()
	defer close(w.release)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	subscribed := make(chan struct{})
	handlerDone := make(chan struct{})
	go func() {
		defer close(handlerDone)
		hub.ServeHTTP(ctx, w, "room", func() (int64, interface{}) {
			close(subscribed)
			return hub.currentID("room"), map[string]int{"version": 0}
		})
	}()
	<-subscribed

	hub.Publish("room", "queue_updated", 1)
	<-w.blocked
	for version := 2; version <= 18; version++ {
		hub.Publish("room", "queue_updated", version)
	}

	select {
	case <-handlerDone:
	case <-time.After(time.Second):
		t.Fatal("SSE handler stayed connected after subscriber overflow")
	}
}

type failingEventWriter struct {
	header http.Header
	failed chan struct{}
	once   sync.Once
}

func (w *failingEventWriter) Header() http.Header              { return w.header }
func (w *failingEventWriter) WriteHeader(int)                  {}
func (w *failingEventWriter) Flush()                           {}
func (w *failingEventWriter) SetWriteDeadline(time.Time) error { return nil }
func (w *failingEventWriter) Write(p []byte) (int, error) {
	if strings.Contains(string(p), "event: queue_updated") {
		w.once.Do(func() { close(w.failed) })
		return 0, fmt.Errorf("client write failed")
	}
	return len(p), nil
}

func TestServeHTTPReturnsAfterWriteFailureWithoutOverflow(t *testing.T) {
	hub := NewHub()
	w := &failingEventWriter{header: make(http.Header), failed: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	subscribed := make(chan struct{})
	handlerDone := make(chan struct{})
	go func() {
		defer close(handlerDone)
		hub.ServeHTTP(ctx, w, "room", func() (int64, interface{}) {
			close(subscribed)
			return 0, map[string]int{"version": 0}
		})
	}()
	<-subscribed
	hub.Publish("room", "queue_updated", 1)
	<-w.failed

	select {
	case <-handlerDone:
	case <-time.After(time.Second):
		t.Fatal("SSE handler stayed connected after a write failure")
	}
}

func TestTTLJanitor(t *testing.T) {
	gen := &seqCodeGen{}
	store := NewStore(50*time.Millisecond, 20*time.Millisecond, gen)
	hub := NewHub()
	s := NewServer(store, hub)
	mux := newTestMux(s)

	stop := make(chan struct{})
	go store.Janitor(stop)
	defer close(stop)

	// Empty room: created, then removed by janitor.
	code, _ := createRoom(t, mux)
	time.Sleep(150 * time.Millisecond)
	if store.Get(code) != nil {
		t.Fatalf("empty room %q not removed by janitor", code)
	}

	// Room with tracks: survives.
	code2, _ := createRoom(t, mux)
	addTrack(t, mux, code2, "https://a.example/1")
	time.Sleep(150 * time.Millisecond)
	if store.Get(code2) == nil {
		t.Fatalf("room with tracks %q was removed", code2)
	}
}

func TestConcurrentMutationsAndSSE(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)

	ts := httptest.NewServer(mux)
	defer ts.Close()

	// SSE subscribers over a real server; their contexts are canceled at the
	// end so the handlers return and the goroutines exit.
	var sseWG sync.WaitGroup
	cancels := make([]context.CancelFunc, 0, 4)
	for i := 0; i < 4; i++ {
		ctx, cancel := context.WithCancel(context.Background())
		cancels = append(cancels, cancel)
		sseWG.Add(1)
		go func() {
			defer sseWG.Done()
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, ts.URL+"/rooms/"+code+"/events", nil)
			if err != nil {
				return
			}
			resp, err := ts.Client().Do(req)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			_, _ = io.Copy(io.Discard, resp.Body)
		}()
	}

	// Concurrent mutations. Goroutines must not use t: collect failures.
	var mutWG sync.WaitGroup
	errs := make(chan error, 20)
	for i := 0; i < 20; i++ {
		mutWG.Add(1)
		go func(n int) {
			defer mutWG.Done()
			body := fmt.Sprintf(`{"url":"https://a.example/%d"}`, n)
			req := httptest.NewRequest(http.MethodPost, "/rooms/"+code+"/queue", strings.NewReader(body))
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			if rec.Code != http.StatusCreated {
				errs <- fmt.Errorf("add %d: status %d", n, rec.Code)
			}
		}(i)
	}
	// Concurrent skips: any status is legal under the race (empty queue -> 400),
	// but successful ones are counted for the final accounting.
	var skipsOK int32
	for i := 0; i < 5; i++ {
		mutWG.Add(1)
		go func() {
			defer mutWG.Done()
			req := httptest.NewRequest(http.MethodPost, "/rooms/"+code+"/skip", nil)
			req.Header.Set("X-Host-Token", token)
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			if rec.Code == http.StatusOK {
				atomic.AddInt32(&skipsOK, 1)
			}
		}()
	}
	mutWG.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}

	// Final state consistent: every track is either queued or current. Each
	// successful skip drops the previously playing track, so with k successful
	// skips total = 20 - (k-1).
	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("final get status = %d", rec.Code)
	}
	var view struct {
		Current *curView `json:"current"`
		Queue   []Track  `json:"queue"`
	}
	decodeBody(t, rec, &view)
	k := int(atomic.LoadInt32(&skipsOK))
	want := 20
	if k >= 1 {
		want = 20 - (k - 1)
	}
	total := len(view.Queue)
	if view.Current != nil {
		total++
	}
	if total != want {
		t.Fatalf("tracks = %d (queue=%d + current=%v), want %d (skips ok: %d)", total, len(view.Queue), view.Current, want, k)
	}

	// Shut the SSE subscribers down and wait for them.
	for _, cancel := range cancels {
		cancel()
	}
	sseWG.Wait()
}

func TestHealthzAndRoomFlowViaApp(t *testing.T) {
	app := NewApp()
	defer app.Close()

	ts := httptest.NewServer(app.Handler())
	defer ts.Close()

	resp, err := ts.Client().Get(ts.URL + "/healthz")
	if err != nil {
		t.Fatalf("healthz: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz status = %d, want %d", resp.StatusCode, http.StatusOK)
	}
	var body map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("healthz json: %v", err)
	}
	if body["status"] != "ok" {
		t.Fatalf("healthz status = %q, want %q", body["status"], "ok")
	}

	res, err := ts.Client().Post(ts.URL+"/rooms", "application/json", nil)
	if err != nil {
		t.Fatalf("create room: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("create room status = %d, want %d", res.StatusCode, http.StatusCreated)
	}
}

func TestRandomCodeGenerator(t *testing.T) {
	g := NewRandomCodeGenerator(rand.NewSource(1))
	for i := 0; i < 10; i++ {
		code := g.Generate()
		if len(code) != codeLength {
			t.Fatalf("code = %q, want %d chars", code, codeLength)
		}
		for _, c := range code {
			if !strings.ContainsRune(codeAlphabet, c) {
				t.Fatalf("code = %q has invalid char %q", code, c)
			}
		}
	}

	g2 := NewRandomCodeGenerator(nil)
	if len(g2.Generate()) != codeLength {
		t.Fatal("nil-source generator produced wrong length")
	}
}

func TestNewStoreDefaultGen(t *testing.T) {
	s := NewStore(time.Hour, time.Hour, nil)
	room := s.CreateRoom()
	if len(room.Code) != codeLength {
		t.Fatalf("code = %q, want %d chars", room.Code, codeLength)
	}
	if room.HostToken == "" {
		t.Fatal("empty host token")
	}
	if s.Get(room.Code) == nil {
		t.Fatal("room not stored")
	}
}

func TestUnknownRoomHandlers(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	cases := []struct {
		name   string
		method string
		path   string
		body   string
	}{
		{"get", http.MethodGet, "/rooms/nope42", ""},
		{"add", http.MethodPost, "/rooms/nope42/queue", `{"url":"https://a.example/1"}`},
		{"skip", http.MethodPost, "/rooms/nope42/skip", ""},
		{"reorder", http.MethodPatch, "/rooms/nope42/queue", `{"order":[]}`},
		{"events", http.MethodGet, "/rooms/nope42/events", ""},
	}
	for _, tc := range cases {
		rec := doReq(t, mux, tc.method, tc.path, tc.body, "")
		if rec.Code != http.StatusNotFound {
			t.Fatalf("%s: status = %d, want %d", tc.name, rec.Code, http.StatusNotFound)
		}
	}
}

func TestReorderEmptyQueue(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, token := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{"order":[]}`, token)
	if rec.Code != http.StatusOK {
		t.Fatalf("empty order status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var resp struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &resp)
	if len(resp.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty", resp.Queue)
	}

	rec = doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{"order":["x"]}`, token)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("mismatch status = %d, want %d", rec.Code, http.StatusBadRequest)
	}
}

func TestSSEHeartbeat(t *testing.T) {
	_, store := newTestServer()
	hub := NewHub()
	hub.Heartbeat = 20 * time.Millisecond
	mux := newTestMux(NewServer(store, hub))
	code, _ := createRoom(t, mux)

	ts := httptest.NewServer(mux)
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, ts.URL+"/rooms/"+code+"/events", nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("sse connect: %v", err)
	}
	defer resp.Body.Close()

	br := bufio.NewReader(resp.Body)
	_, name, _ := readSSE(t, br)
	if name != "queue_snapshot" {
		t.Fatalf("first event = %q, want queue_snapshot", name)
	}
	id, name, _ := readSSE(t, br)
	if name != "" || id != "" {
		t.Fatalf("expected heartbeat comment, got id=%q name=%q", id, name)
	}
}

func TestHeartbeatInterval(t *testing.T) {
	h := &Hub{}
	if got := h.heartbeatInterval(); got != 15*time.Second {
		t.Fatalf("default interval = %v, want 15s", got)
	}
	h.Heartbeat = 20 * time.Millisecond
	if got := h.heartbeatInterval(); got != 20*time.Millisecond {
		t.Fatalf("interval = %v, want 20ms", got)
	}
}

// noFlushWriter is a ResponseWriter without http.Flusher, to exercise the
// streaming-unsupported error path.
type noFlushWriter struct {
	header http.Header
	code   int
}

func (w *noFlushWriter) Header() http.Header         { return w.header }
func (w *noFlushWriter) WriteHeader(code int)        { w.code = code }
func (w *noFlushWriter) Write(b []byte) (int, error) { return len(b), nil }

func TestServeHTTPNoFlusher(t *testing.T) {
	hub := NewHub()
	w := &noFlushWriter{header: http.Header{}}
	hub.ServeHTTP(context.Background(), w, "somecode", func() (int64, interface{}) { return 0, map[string]string{} })
	if w.code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want %d", w.code, http.StatusInternalServerError)
	}
}

func TestCurrentIDUnknownRoom(t *testing.T) {
	hub := NewHub()
	if got := hub.currentID("nope"); got != 0 {
		t.Fatalf("currentID = %d, want 0", got)
	}
}

func TestWriteEventUnmarshalableData(t *testing.T) {
	rec := httptest.NewRecorder()
	writeEvent(rec, Event{ID: 1, Name: "boom", Data: make(chan int)})
	if !strings.Contains(rec.Body.String(), "data: {}\n") {
		t.Fatalf("body = %q, want fallback {}", rec.Body.String())
	}
}
