//go:build integration

// Mandatory integration level (qmix#17): core scenarios over HTTP against a
// real socket (net.Listen + http.Serve on 127.0.0.1:0, not httptest), with
// the full App handler. Everything is hermetic — no network, no secrets:
// yt-dlp is faked with a shell script (found via PATH by the resolver and via
// QMIX_YTDLP_BIN by the stream backend) that points stream searches at a
// local mock upstream serving audio with Range support.
//
// Run with: make test-integration

package server

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
)

// integServe serves h on an ephemeral real 127.0.0.1 socket and returns its
// base URL. Integration scenarios must run over a real listener (net.Listen +
// http.Serve), not httptest.NewServer.
func integServe(t *testing.T, h http.Handler) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := &http.Server{Handler: h}
	go func() { _ = srv.Serve(ln) }() // ErrServerClosed on Close is expected
	t.Cleanup(func() { _ = srv.Close() })
	return "http://" + ln.Addr().String()
}

// integResp is a fully-read HTTP response from the server under test.
type integResp struct {
	status int
	header http.Header
	body   string
}

// integDo performs one request against the integration server and reads the
// whole body. hostToken, when non-empty, is sent as X-Host-Token.
func integDo(t *testing.T, method, url, body, hostToken string) integResp {
	t.Helper()
	var rdr io.Reader
	if body != "" {
		rdr = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, url, rdr)
	if err != nil {
		t.Fatalf("%s %s: %v", method, url, err)
	}
	if hostToken != "" {
		req.Header.Set("X-Host-Token", hostToken)
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, url, err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("%s %s: read body: %v", method, url, err)
	}
	return integResp{status: resp.StatusCode, header: resp.Header, body: string(b)}
}

// integJSON decodes a JSON response body, failing the test on malformed output.
func integJSON(t *testing.T, r integResp, v interface{}) {
	t.Helper()
	if err := json.Unmarshal([]byte(r.body), v); err != nil {
		t.Fatalf("invalid json %q: %v", r.body, err)
	}
}

// integRoomView mirrors the public room representation (see handlers.go).
type integRoomView struct {
	Code    string   `json:"code"`
	Current *curView `json:"current"`
	Queue   []Track  `json:"queue"`
}

// integCreateRoom creates a room over HTTP and returns its code and host token.
func integCreateRoom(t *testing.T, base string) (code, token string) {
	t.Helper()
	r := integDo(t, http.MethodPost, base+"/rooms", "", "")
	if r.status != http.StatusCreated {
		t.Fatalf("create room status = %d, want %d; body=%s", r.status, http.StatusCreated, r.body)
	}
	var resp struct {
		Code      string `json:"code"`
		HostToken string `json:"host_token"`
		URL       string `json:"url"`
	}
	integJSON(t, r, &resp)
	if resp.Code == "" || resp.HostToken == "" || resp.URL != "/r/"+resp.Code {
		t.Fatalf("create room response: %+v", resp)
	}
	return resp.Code, resp.HostToken
}

// integAddTrack adds a track by URL over HTTP and returns the parsed track.
func integAddTrack(t *testing.T, base, code, url string) Track {
	t.Helper()
	r := integDo(t, http.MethodPost, base+"/rooms/"+code+"/queue", fmt.Sprintf(`{"url":%q}`, url), "")
	if r.status != http.StatusCreated {
		t.Fatalf("add track status = %d, want %d; body=%s", r.status, http.StatusCreated, r.body)
	}
	var tr Track
	integJSON(t, r, &tr)
	return tr
}

// integGetRoom fetches the room view over HTTP. The view is only decoded on
// 200, so the helper also works for polling a room towards 404.
func integGetRoom(t *testing.T, base, code string) (integResp, integRoomView) {
	t.Helper()
	r := integDo(t, http.MethodGet, base+"/rooms/"+code, "", "")
	var v integRoomView
	if r.status == http.StatusOK {
		integJSON(t, r, &v)
	}
	return r, v
}

// integSkip advances the room over HTTP with the given host token.
func integSkip(t *testing.T, base, code, token string) integResp {
	t.Helper()
	return integDo(t, http.MethodPost, base+"/rooms/"+code+"/skip", "", token)
}

// fakeYtdlp writes a fake yt-dlp executable and wires both consumers to it:
// the resolver finds "yt-dlp" on PATH, the stream backend reads QMIX_YTDLP_BIN
// from the environment (when App.Handler is built). Metadata requests get
// canned track JSON; ytsearch:* requests (stream backend) get the mock
// upstream URL.
func fakeYtdlp(t *testing.T, upstreamURL string) {
	t.Helper()
	dir := t.TempDir()
	script := "#!/bin/sh\n" +
		"# fake yt-dlp for integration tests (qmix#17)\n" +
		"for a in \"$@\"; do\n" +
		"  case \"$a\" in\n" +
		"    ytsearch:*) echo '{\"url\":\"" + upstreamURL + "\"}'; exit 0 ;;\n" +
		"  esac\n" +
		"done\n" +
		"echo '{\"title\":\"Fake Song\",\"artist\":\"Fake Artist\",\"duration\":123}'\n"
	bin := filepath.Join(dir, "yt-dlp")
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatalf("write fake yt-dlp: %v", err)
	}
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("QMIX_YTDLP_BIN", bin)
}

// mockAudioUpstream serves a fixed audio payload with single-byte-range
// support, returning its base URL and the payload bytes.
func mockAudioUpstream(t *testing.T) (string, []byte) {
	t.Helper()
	audio := bytes.Repeat([]byte("qmix-audio-"), 30) // 300 bytes
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/webm")
		w.Header().Set("Accept-Ranges", "bytes")
		if rng := r.Header.Get("Range"); rng != "" {
			var start, end int64
			if _, err := fmt.Sscanf(rng, "bytes=%d-%d", &start, &end); err != nil {
				http.Error(w, "bad range", http.StatusBadRequest)
				return
			}
			if start < 0 || end < start || end >= int64(len(audio)) {
				w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", len(audio)))
				w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
				return
			}
			w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(audio)))
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(audio[start : end+1])
			return
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(audio)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(audio)
	})
	return integServe(t, h), audio
}

// Scenario 1: GET /healthz answers 200 on the full App handler.
func TestIntegrationHealthz(t *testing.T) {
	app := NewApp()
	t.Cleanup(app.Close)
	base := integServe(t, app.Handler())

	r := integDo(t, http.MethodGet, base+"/healthz", "", "")
	if r.status != http.StatusOK {
		t.Fatalf("healthz status = %d, want %d; body=%s", r.status, http.StatusOK, r.body)
	}
	var body map[string]string
	integJSON(t, r, &body)
	if body["status"] != "ok" {
		t.Fatalf("healthz body = %q, want status ok", r.body)
	}
}

// Scenario 2: room lifecycle over the full App: create → get by code → add
// track → skip (host token enforced) → reorder.
func TestIntegrationRoomLifecycle(t *testing.T) {
	fakeYtdlp(t, "http://127.0.0.1:1/never-called")
	app := NewApp()
	t.Cleanup(app.Close)
	base := integServe(t, app.Handler())

	code, token := integCreateRoom(t, base)

	// Get by code: empty room, no host token leak.
	r, view := integGetRoom(t, base, code)
	if r.status != http.StatusOK {
		t.Fatalf("get room status = %d, want %d", r.status, http.StatusOK)
	}
	if view.Code != code || view.Current != nil || len(view.Queue) != 0 {
		t.Fatalf("fresh room view = %+v", view)
	}
	if strings.Contains(r.body, "host_token") {
		t.Fatalf("host_token leaked in GET response: %s", r.body)
	}

	// Add tracks: resolved by the fake yt-dlp through the YouTube resolver.
	tr1 := integAddTrack(t, base, code, "https://www.youtube.com/watch?v=fake1")
	if tr1.ID == "" || tr1.Title != "Fake Song" || tr1.Artist != "Fake Artist" ||
		tr1.DurationSec != 123 || tr1.ResolvedBy != "youtube" {
		t.Fatalf("track = %+v", tr1)
	}
	tr2 := integAddTrack(t, base, code, "https://www.youtube.com/watch?v=fake2")

	// Skip is host-only: missing and wrong tokens are rejected with 403.
	if r := integSkip(t, base, code, ""); r.status != http.StatusForbidden {
		t.Fatalf("skip without token status = %d, want %d", r.status, http.StatusForbidden)
	}
	if r := integSkip(t, base, code, "wrong-token"); r.status != http.StatusForbidden {
		t.Fatalf("skip with wrong token status = %d, want %d", r.status, http.StatusForbidden)
	}

	// Skip with the real token: tr1 becomes current, queue keeps tr2.
	r = integSkip(t, base, code, token)
	if r.status != http.StatusOK {
		t.Fatalf("skip status = %d, want %d; body=%s", r.status, http.StatusOK, r.body)
	}
	var skipResp struct {
		Current curView `json:"current"`
	}
	integJSON(t, r, &skipResp)
	if skipResp.Current.TrackID != tr1.ID || skipResp.Current.State != "playing" || skipResp.Current.PosSec != 0 {
		t.Fatalf("current after skip = %+v", skipResp.Current)
	}

	_, view = integGetRoom(t, base, code)
	if len(view.Queue) != 1 || view.Queue[0].ID != tr2.ID {
		t.Fatalf("queue after skip = %+v", view.Queue)
	}
	if view.Current == nil || view.Current.TrackID != tr1.ID {
		t.Fatalf("current in view = %+v", view.Current)
	}

	// Reorder is host-only too; with the token it applies an exact permutation.
	tr3 := integAddTrack(t, base, code, "https://www.youtube.com/watch?v=fake3") // queue: [tr2, tr3]
	order := fmt.Sprintf(`{"order":[%q,%q]}`, tr3.ID, tr2.ID)
	if r := integDo(t, http.MethodPatch, base+"/rooms/"+code+"/queue", order, ""); r.status != http.StatusForbidden {
		t.Fatalf("reorder without token status = %d, want %d", r.status, http.StatusForbidden)
	}
	r = integDo(t, http.MethodPatch, base+"/rooms/"+code+"/queue", order, token)
	if r.status != http.StatusOK {
		t.Fatalf("reorder status = %d, want %d; body=%s", r.status, http.StatusOK, r.body)
	}
	var reorderResp struct {
		Queue []Track `json:"queue"`
	}
	integJSON(t, r, &reorderResp)
	if len(reorderResp.Queue) != 2 || reorderResp.Queue[0].ID != tr3.ID || reorderResp.Queue[1].ID != tr2.ID {
		t.Fatalf("reordered queue = %+v", reorderResp.Queue)
	}
	_, view = integGetRoom(t, base, code)
	if len(view.Queue) != 2 || view.Queue[0].ID != tr3.ID || view.Queue[1].ID != tr2.ID {
		t.Fatalf("persisted queue = %+v", view.Queue)
	}
}

// Scenario 3: SSE over a real socket: queue_snapshot on connect, live events
// on mutations (queue_updated / track_changed / player_state), snapshot again
// on reconnect.
func TestIntegrationSSE(t *testing.T) {
	fakeYtdlp(t, "http://127.0.0.1:1/never-called")
	app := NewApp()
	t.Cleanup(app.Close)
	base := integServe(t, app.Handler())
	code, token := integCreateRoom(t, base)

	// Connect.
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/rooms/"+code+"/events", nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	resp, err := (&http.Client{}).Do(req)
	if err != nil {
		t.Fatalf("sse connect: %v", err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("content-type = %q, want text/event-stream", ct)
	}
	br := bufio.NewReader(resp.Body)

	// queue_snapshot on connect: empty room.
	_, name, data := readSSE(t, br)
	if name != "queue_snapshot" {
		t.Fatalf("first event = %q, want queue_snapshot", name)
	}
	var snap integRoomView
	if err := json.Unmarshal([]byte(data), &snap); err != nil {
		t.Fatalf("snapshot json: %v", err)
	}
	if snap.Current != nil || len(snap.Queue) != 0 {
		t.Fatalf("snapshot = %+v", snap)
	}

	// Add a track → queue_updated carrying the new track.
	tr := integAddTrack(t, base, code, "https://www.youtube.com/watch?v=fake1")
	_, name, data = readSSE(t, br)
	if name != "queue_updated" {
		t.Fatalf("event = %q, want queue_updated", name)
	}
	var upd struct {
		Queue []Track `json:"queue"`
	}
	if err := json.Unmarshal([]byte(data), &upd); err != nil {
		t.Fatalf("queue_updated json: %v", err)
	}
	if len(upd.Queue) != 1 || upd.Queue[0].ID != tr.ID {
		t.Fatalf("queue_updated queue = %+v", upd.Queue)
	}

	// Skip → track_changed then player_state.
	if r := integSkip(t, base, code, token); r.status != http.StatusOK {
		t.Fatalf("skip status = %d; body=%s", r.status, r.body)
	}
	_, name, data = readSSE(t, br)
	if name != "track_changed" {
		t.Fatalf("event = %q, want track_changed", name)
	}
	var cur struct {
		TrackID string `json:"track_id"`
		PosSec  int    `json:"pos_sec"`
		State   string `json:"state"`
	}
	if err := json.Unmarshal([]byte(data), &cur); err != nil {
		t.Fatalf("track_changed json: %v", err)
	}
	if cur.TrackID != tr.ID || cur.State != "playing" || cur.PosSec != 0 {
		t.Fatalf("track_changed payload = %+v", cur)
	}
	_, name, _ = readSSE(t, br)
	if name != "player_state" {
		t.Fatalf("event = %q, want player_state", name)
	}

	// Reconnect: the fresh snapshot reflects the mutated state.
	resp.Body.Close()
	ctx2, cancel2 := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel2()
	req2, err := http.NewRequestWithContext(ctx2, http.MethodGet, base+"/rooms/"+code+"/events", nil)
	if err != nil {
		t.Fatalf("new reconnect request: %v", err)
	}
	req2.Header.Set("Last-Event-ID", "1")
	resp2, err := (&http.Client{}).Do(req2)
	if err != nil {
		t.Fatalf("sse reconnect: %v", err)
	}
	defer resp2.Body.Close()
	br2 := bufio.NewReader(resp2.Body)
	_, name, data = readSSE(t, br2)
	if name != "queue_snapshot" {
		t.Fatalf("reconnect first event = %q, want queue_snapshot", name)
	}
	var snap2 integRoomView
	if err := json.Unmarshal([]byte(data), &snap2); err != nil {
		t.Fatalf("reconnect snapshot json: %v", err)
	}
	if snap2.Current == nil || snap2.Current.TrackID != tr.ID || len(snap2.Queue) != 0 {
		t.Fatalf("reconnect snapshot = %+v", snap2)
	}
}

// Scenario 4: adding a track through a mock resolver (no network): metadata
// lands in the queue.
func TestIntegrationResolverMock(t *testing.T) {
	s := NewServer(NewStore(time.Hour, time.Hour, &seqCodeGen{}), NewHub())
	s.Resolver = stubResolver{meta: &resolver.Track{
		Title: "Mock Song", Artist: "Mock Artist", DurationSec: 42,
		Source: "https://example.com/x", ResolvedBy: "mock",
	}}
	mux := http.NewServeMux()
	s.Routes(mux)
	base := integServe(t, mux)
	code, _ := integCreateRoom(t, base)

	tr := integAddTrack(t, base, code, "https://example.com/x")
	if tr.Title != "Mock Song" || tr.Artist != "Mock Artist" || tr.DurationSec != 42 || tr.ResolvedBy != "mock" {
		t.Fatalf("track = %+v", tr)
	}
	_, view := integGetRoom(t, base, code)
	if len(view.Queue) != 1 || view.Queue[0].Title != "Mock Song" || view.Queue[0].Artist != "Mock Artist" {
		t.Fatalf("view = %+v", view)
	}
}

// Scenario 4 (negative): an unknown link maps to 422 and does not grow the queue.
func TestIntegrationResolverMockUnknownLink(t *testing.T) {
	s := NewServer(NewStore(time.Hour, time.Hour, &seqCodeGen{}), NewHub())
	s.Resolver = stubResolver{err: resolver.ErrUnsupported}
	mux := http.NewServeMux()
	s.Routes(mux)
	base := integServe(t, mux)
	code, _ := integCreateRoom(t, base)

	r := integDo(t, http.MethodPost, base+"/rooms/"+code+"/queue", `{"url":"https://unknown.example/song"}`, "")
	if r.status != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", r.status, http.StatusUnprocessableEntity, r.body)
	}
	if !strings.Contains(r.body, "unsupported") {
		t.Fatalf("body = %q, want human-readable message", r.body)
	}
	_, view := integGetRoom(t, base, code)
	if len(view.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty on failed resolution", view.Queue)
	}
}

// Scenario 5: streaming through a fake yt-dlp binary. The stream backend is
// configured via QMIX_YTDLP_BIN (read from the environment when App.Handler is
// built) and streams from a local mock upstream with Range support.
func TestIntegrationStreamFakeYtdlp(t *testing.T) {
	upstream, audio := mockAudioUpstream(t)
	fakeYtdlp(t, upstream+"/audio")

	app := NewApp()
	t.Cleanup(app.Close)
	base := integServe(t, app.Handler()) // Handler reads QMIX_YTDLP_BIN here.
	code, token := integCreateRoom(t, base)

	tr := integAddTrack(t, base, code, "https://www.youtube.com/watch?v=fake1")
	if tr.Title != "Fake Song" || tr.Artist != "Fake Artist" {
		t.Fatalf("track = %+v", tr)
	}
	if r := integSkip(t, base, code, token); r.status != http.StatusOK {
		t.Fatalf("skip status = %d; body=%s", r.status, r.body)
	}

	// Full stream: 200 with the upstream audio.
	r := integDo(t, http.MethodGet, base+"/rooms/"+code+"/current/stream", "", "")
	if r.status != http.StatusOK {
		t.Fatalf("stream status = %d, want %d; body=%q", r.status, http.StatusOK, r.body)
	}
	if ct := r.header.Get("Content-Type"); ct != "audio/webm" {
		t.Fatalf("content-type = %q, want audio/webm", ct)
	}
	if ar := r.header.Get("Accept-Ranges"); ar != "bytes" {
		t.Fatalf("accept-ranges = %q, want bytes", ar)
	}
	if r.body != string(audio) {
		t.Fatalf("stream body len = %d, want %d", len(r.body), len(audio))
	}

	// Range request: 206 with the correct slice of the audio.
	req, err := http.NewRequest(http.MethodGet, base+"/rooms/"+code+"/current/stream", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Range", "bytes=0-99")
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("range request: %v", err)
	}
	b, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("range status = %d, want %d", resp.StatusCode, http.StatusPartialContent)
	}
	if cr := resp.Header.Get("Content-Range"); cr != fmt.Sprintf("bytes 0-99/%d", len(audio)) {
		t.Fatalf("content-range = %q, want bytes 0-99/%d", cr, len(audio))
	}
	if string(b) != string(audio[:100]) {
		t.Fatalf("partial body len = %d, want 100", len(b))
	}
}

// Scenario 6: an idle empty room expires on the short TTL; a room with a queue
// survives that window but expires on the longer non-empty TTL.
func TestIntegrationRoomTTL(t *testing.T) {
	fakeYtdlp(t, "http://127.0.0.1:1/never-called")
	app := &App{
		Store: NewStore(80*time.Millisecond, 25*time.Millisecond, nil),
		Hub:   NewHub(),
		stop:  make(chan struct{}),
	}
	app.Store.NonEmptyTTL = 240 * time.Millisecond
	base := integServe(t, app.Handler())

	code, _ := integCreateRoom(t, base)
	if r, _ := integGetRoom(t, base, code); r.status != http.StatusOK {
		t.Fatalf("fresh room status = %d, want %d", r.status, http.StatusOK)
	}

	// A room with a track must survive the same window.
	code2, _ := integCreateRoom(t, base)
	integAddTrack(t, base, code2, "https://www.youtube.com/watch?v=fake1")

	app.Store.mu.Lock()
	app.Store.rooms[code].LastActivity = time.Now().Add(-2 * app.Store.TTL)
	app.Store.mu.Unlock()
	go app.Store.Janitor(app.stop)
	t.Cleanup(app.Close)

	// Poll until the empty room is swept: GET by code → 404.
	deadline := time.Now().Add(5 * time.Second)
	for {
		r, _ := integGetRoom(t, base, code)
		if r.status == http.StatusNotFound {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("empty room %q still alive (status %d) after TTL", code, r.status)
		}
		time.Sleep(20 * time.Millisecond)
	}

	// The tracked room is still there.
	r, view := integGetRoom(t, base, code2)
	if r.status != http.StatusOK || len(view.Queue) != 1 {
		t.Fatalf("tracked room status = %d, view = %+v", r.status, view)
	}

	for {
		r, _ := integGetRoom(t, base, code2)
		if r.status == http.StatusNotFound {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("non-empty room %q still alive (status %d) after TTL", code2, r.status)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
