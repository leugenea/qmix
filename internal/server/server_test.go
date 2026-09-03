package server

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
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
