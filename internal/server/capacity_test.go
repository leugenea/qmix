// End-to-end capacity tests pin the qmix#130 contract through the production
// wiring: metadata resolution and stream search subprocesses share one bounded
// capacity limiter, requests waiting for a slot do not hold the room store
// mutex, and queue overflow fails fast with 503 plus Retry-After.
//
// The yt-dlp "subprocess" is a fake binary script. It reports every process
// start on a FIFO, blocks until the test opens a release gate, and prints one
// --dump-json document satisfying both the metadata and the search paths. The
// running process count is therefore observable exactly, and every
// synchronization is channel-driven rather than sleep-based. The tests only
// use APIs that exist before the fix, so they fail on the unfixed tree and
// pass once the shared limiter is wired through config and NewApp.
package server

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/config"
)

// capacityGateWindow is the bounded window used to prove absence: after the
// configured concurrent subprocesses are observed, no further start may occur
// within the window. In the fixed tree the limiter makes that an invariant, so
// the window can only expire; in the unfixed tree the extra start arrives in
// microseconds and is caught.
const capacityGateWindow = 500 * time.Millisecond

// capacityReleaseTokens bounds how many parked scripts one release may wake.
// Leftover tokens only let later scripts finish without parking; they cannot
// raise the observed concurrency because a script process exists only while
// its caller holds a capacity slot.
const capacityReleaseTokens = 64

// capacityGate is a fake yt-dlp binary plus the probe counting how many script
// processes run at once. starts signals every process start; cur/peak track
// how many processes are live simultaneously.
type capacityGate struct {
	bin     string
	starts  chan struct{}
	cur     atomic.Int32
	peak    atomic.Int32
	release *os.File
}

// newCapacityGate writes the fake yt-dlp script and starts the FIFO reader.
// audioURL is the direct audio URL the script reports; it must already be
// listening so admitted stream requests can complete locally.
func newCapacityGate(t *testing.T, audioURL string) *capacityGate {
	t.Helper()
	t.Setenv("QMIX_TEST_CAPACITY_PATH_FRAGMENT", "misrouted")
	dir := filepath.Join(t.TempDir(), "capacity gate's $QMIX_TEST_CAPACITY_PATH_FRAGMENT")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	enter := filepath.Join(dir, "enter.fifo")
	release := filepath.Join(dir, "release.fifo")
	for _, path := range []string{enter, release} {
		if err := syscall.Mkfifo(path, 0o600); err != nil {
			t.Fatalf("mkfifo %s: %v", path, err)
		}
	}
	// O_RDWR on a FIFO never blocks and never sees EOF, so the reader and the
	// scripts can come and go freely.
	enterFD, err := os.OpenFile(enter, os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	releaseFD, err := os.OpenFile(release, os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	g := &capacityGate{
		bin:     filepath.Join(dir, "yt-dlp"),
		starts:  make(chan struct{}, 64),
		release: releaseFD,
	}
	fixture, err := json.Marshal(struct {
		Title    string `json:"title"`
		Artist   string `json:"artist"`
		Duration int    `json:"duration"`
		URL      string `json:"url"`
	}{
		Title:    "Capacity Song",
		Artist:   "Capacity Artist",
		Duration: 42,
		URL:      audioURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("QMIX_TEST_CAPACITY_ENTER_FIFO", enter)
	t.Setenv("QMIX_TEST_CAPACITY_RELEASE_FIFO", release)
	t.Setenv("QMIX_TEST_CAPACITY_JSON_FIXTURE", string(fixture))
	script := "#!/bin/sh\n" +
		"printf 'enter\\n' > \"$QMIX_TEST_CAPACITY_ENTER_FIFO\"\n" +
		"read -r released < \"$QMIX_TEST_CAPACITY_RELEASE_FIFO\"\n" +
		"printf 'exit\\n' > \"$QMIX_TEST_CAPACITY_ENTER_FIFO\"\n" +
		"printf '%s\\n' \"$QMIX_TEST_CAPACITY_JSON_FIXTURE\"\n"
	if err := os.WriteFile(g.bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	go func() {
		sc := bufio.NewScanner(enterFD)
		for sc.Scan() {
			switch strings.TrimSpace(sc.Text()) {
			case "enter":
				cur := g.cur.Add(1)
				for {
					peak := g.peak.Load()
					if cur <= peak || g.peak.CompareAndSwap(peak, cur) {
						break
					}
				}
				select {
				case g.starts <- struct{}{}:
				default:
				}
			case "exit":
				g.cur.Add(-1)
			}
		}
	}()
	t.Cleanup(func() {
		g.releaseAll()
		_ = enterFD.Close()
		_ = releaseFD.Close()
	})
	return g
}

// TestCapacityGatePreservesShellSensitiveValues proves the fake executable
// treats fixture values and FIFO paths as data rather than shell source.
func TestCapacityGatePreservesShellSensitiveValues(t *testing.T) {
	t.Setenv("QMIX_TEST_CAPACITY_VALUE_FRAGMENT", "misquoted")
	wantURL := "https://audio.example.test/it's here/$QMIX_TEST_CAPACITY_VALUE_FRAGMENT"
	g := newCapacityGate(t, wantURL)
	g.releaseAll()

	out, err := exec.Command(g.bin).Output()
	if err != nil {
		t.Fatalf("run capacity gate: %v", err)
	}
	var fixture struct {
		URL string `json:"url"`
	}
	if err := json.Unmarshal(out, &fixture); err != nil {
		t.Fatalf("decode capacity fixture %q: %v", out, err)
	}
	if fixture.URL != wantURL {
		t.Fatalf("capacity fixture URL = %q, want %q", fixture.URL, wantURL)
	}
}

// releaseAll opens the gate: every parked script may proceed and any script
// started later proceeds without parking.
func (g *capacityGate) releaseAll() {
	for i := 0; i < capacityReleaseTokens; i++ {
		if _, err := g.release.WriteString("go\n"); err != nil {
			return
		}
	}
}

// newCapacityAudioUpstream serves the direct audio URL the fake yt-dlp
// reports, so admitted stream requests complete with a local 200.
func newCapacityAudioUpstream(t *testing.T) *httptest.Server {
	t.Helper()
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "audio/webm")
		_, _ = w.Write([]byte("capacity-audio"))
	}))
	t.Cleanup(upstream.Close)
	return upstream
}

// newCapacityApp builds the production App around the fake yt-dlp binary with
// default runtime settings, so the limiter is wired exactly as in production.
// The stream cache is disabled so every stream lookup starts a subprocess.
func newCapacityApp(t *testing.T, g *capacityGate) *App {
	t.Helper()
	cfg, err := config.Parse(func(string) (string, bool) { return "", false })
	if err != nil {
		t.Fatal(err)
	}
	cfg.YTDLP.Binary = g.bin
	cfg.Stream.CacheTTL = -time.Second
	app, err := NewApp(cfg, nil, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(app.Close)
	return app
}

// capacityRoom returns a room code; withCurrent also sets a playing track so
// the stream endpoint resolves through the backend.
func capacityRoom(t *testing.T, store *Store, withCurrent bool) string {
	t.Helper()
	return capacityStreamRoom(t, store, 0, withCurrent)
}

// capacityStreamRoom returns a room whose current track is unique per index,
// so simultaneous stream requests resolve distinct cache keys and each one
// needs its own subprocess slot.
func capacityStreamRoom(t *testing.T, store *Store, index int, withCurrent bool) string {
	t.Helper()
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	if withCurrent {
		store.mu.Lock()
		store.rooms[credentials.Code].Current = &Current{
			TrackID:    fmt.Sprintf("capacity-current-%d", index),
			State:      "playing",
			URL:        fmt.Sprintf("https://www.youtube.com/watch?v=capacity-current-%d", index),
			Title:      "Capacity Song",
			Artist:     "Capacity Artist",
			ResolvedBy: "youtube",
		}
		store.mu.Unlock()
	}
	return credentials.Code
}

// capacityOutcome records one endpoint response.
type capacityOutcome struct {
	kind       string
	status     int
	retryAfter string
}

// capacityPost posts one add-track request to path (host or guest endpoint).
func capacityPost(t *testing.T, client *http.Client, kind, url, body string) capacityOutcome {
	t.Helper()
	res, err := client.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		t.Errorf("%s request: %v", kind, err)
		return capacityOutcome{kind: kind, status: -1}
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, res.Body)
	return capacityOutcome{kind: kind, status: res.StatusCode, retryAfter: res.Header.Get("Retry-After")}
}

// capacityGetStream performs one stream request.
func capacityGetStream(t *testing.T, client *http.Client, url string) capacityOutcome {
	t.Helper()
	res, err := client.Get(url)
	if err != nil {
		t.Errorf("stream request: %v", err)
		return capacityOutcome{kind: "stream", status: -1}
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, res.Body)
	return capacityOutcome{kind: "stream", status: res.StatusCode, retryAfter: res.Header.Get("Retry-After")}
}

// TestYTDLPSharedCapacityBoundsConcurrentSubprocesses proves the qmix#130
// concurrency bound over HTTP: with the documented default of 2, five
// simultaneous lookups across the host add-track and stream endpoints never
// run more than two yt-dlp subprocesses at once, because metadata resolution
// and streaming share one limiter. Separate per-path limiters would peak at 4
// or more; no limiter at all peaks at the full request count.
func TestYTDLPSharedCapacityBoundsConcurrentSubprocesses(t *testing.T) {
	const requests = 5 // more than the default limit of 2
	upstream := newCapacityAudioUpstream(t)
	g := newCapacityGate(t, upstream.URL)
	app := newCapacityApp(t, g)
	ts := httptest.NewServer(app.Handler())
	t.Cleanup(ts.Close)
	t.Cleanup(g.releaseAll) // LIFO: open the gate before the server closes

	addRoom := capacityRoom(t, app.Store, false)
	streamRoom := capacityRoom(t, app.Store, true)
	client := ts.Client()
	outcomes := make(chan capacityOutcome, requests)
	for i := 0; i < requests; i++ {
		go func(i int) {
			if i < 3 {
				outcomes <- capacityPost(t, client, "host-add",
					ts.URL+"/rooms/"+addRoom+"/queue",
					fmt.Sprintf(`{"url":"https://www.youtube.com/watch?v=cap-%d"}`, i))
				return
			}
			outcomes <- capacityGetStream(t, client, ts.URL+"/rooms/"+streamRoom+"/current/stream")
		}(i)
	}

	// While the gate is closed, exactly the configured number of subprocesses
	// can be running; every further request must be parked, not running.
	receiveWithin(t, g.starts)
	receiveWithin(t, g.starts)
	select {
	case <-g.starts:
		g.releaseAll()
		for i := 0; i < requests; i++ {
			receiveWithin(t, outcomes)
		}
		t.Fatalf("more than the configured 2 concurrent yt-dlp subprocesses ran (peak %d, qmix#130)", g.peak.Load())
	case <-time.After(capacityGateWindow):
	}

	g.releaseAll()
	for i := 0; i < requests; i++ {
		outcome := receiveWithin(t, outcomes)
		if outcome.status < 200 || outcome.status > 299 {
			t.Errorf("%s status = %d, want 2xx", outcome.kind, outcome.status)
		}
	}
	if got := g.peak.Load(); got != 2 {
		t.Fatalf("peak concurrent yt-dlp subprocesses = %d, want exactly 2 (qmix#130)", got)
	}
}

// TestYTDLPQueueOverflowReturns503WithRetryAfter proves the qmix#130 overload
// contract end-to-end: with the documented defaults (2 running, 8 waiting),
// the 11th simultaneous lookup across the host add-track, guest add-track and
// stream endpoints is rejected fast with 503 plus Retry-After, while the ten
// admitted lookups eventually succeed.
func TestYTDLPQueueOverflowReturns503WithRetryAfter(t *testing.T) {
	const requests = 11 // 2 running + 8 queued + 1 rejected
	upstream := newCapacityAudioUpstream(t)
	g := newCapacityGate(t, upstream.URL)
	app := newCapacityApp(t, g)
	ts := httptest.NewServer(app.Handler())
	t.Cleanup(ts.Close)
	t.Cleanup(g.releaseAll) // LIFO: open the gate before the server closes

	addRoom := capacityRoom(t, app.Store, false)
	guestRoom := capacityRoom(t, app.Store, false)
	streamRooms := []string{
		capacityStreamRoom(t, app.Store, 1, true),
		capacityStreamRoom(t, app.Store, 2, true),
		capacityStreamRoom(t, app.Store, 3, true),
		capacityStreamRoom(t, app.Store, 4, true),
	}
	client := ts.Client()
	outcomes := make(chan capacityOutcome, requests)
	for i := 0; i < requests; i++ {
		go func(i int) {
			switch {
			case i < 4:
				outcomes <- capacityPost(t, client, "host-add",
					ts.URL+"/rooms/"+addRoom+"/queue",
					fmt.Sprintf(`{"url":"https://www.youtube.com/watch?v=cap-%d"}`, i))
			case i < 7:
				outcomes <- capacityPost(t, client, "guest-add",
					ts.URL+"/r/"+guestRoom+"/queue",
					fmt.Sprintf(`{"url":"https://www.youtube.com/watch?v=cap-%d"}`, i))
			default:
				outcomes <- capacityGetStream(t, client, ts.URL+"/rooms/"+streamRooms[i-7]+"/current/stream")
			}
		}(i)
	}

	// Admitted lookups cannot complete while the gate is closed, so the only
	// outcome that can arrive first is the fast overload rejection.
	var rejected capacityOutcome
	select {
	case outcome := <-outcomes:
		if outcome.status != http.StatusServiceUnavailable {
			g.releaseAll()
			for i := 0; i < requests-1; i++ {
				receiveWithin(t, outcomes)
			}
			t.Fatalf("first outcome = %s status %d, want 503 overload rejection (qmix#130)", outcome.kind, outcome.status)
		}
		rejected = outcome
	case <-time.After(2 * time.Second):
		g.releaseAll()
		t.Fatalf("no 503 arrived while %d lookups saturated the 2 running + 8 queued slots; peak concurrent subprocesses = %d (qmix#130 has no capacity bound or overload rejection)", requests, g.peak.Load())
	}
	if rejected.retryAfter == "" {
		t.Errorf("503 overload response omitted Retry-After (qmix#130)")
	}

	g.releaseAll()
	overloaded := 1
	for i := 0; i < requests-1; i++ {
		outcome := receiveWithin(t, outcomes)
		switch {
		case outcome.status == http.StatusServiceUnavailable:
			overloaded++
			if outcome.retryAfter == "" {
				t.Errorf("overloaded %s response omitted Retry-After", outcome.kind)
			}
		case outcome.status < 200 || outcome.status > 299:
			t.Errorf("%s status = %d, want 2xx", outcome.kind, outcome.status)
		}
	}
	if overloaded != 1 {
		t.Errorf("overload rejections = %d, want exactly 1 of %d simultaneous lookups (qmix#130)", overloaded, requests)
	}
	// The bound invariant: concurrency never exceeded the configured 2. The
	// exact-2 peak is pinned by the dedicated bounds test with the gate
	// closed; here the gate opens before the queued lookups run, so their
	// completions may serialize.
	if got := g.peak.Load(); got > 2 {
		t.Fatalf("peak concurrent yt-dlp subprocesses = %d, want at most 2 (qmix#130)", got)
	}
}

// TestYTDLPWaitingRequestsDoNotHoldStoreMutex pins the qmix#130 scope rule
// that no room/store mutex is held while a request waits for capacity: while
// both subprocess slots are taken by parked lookups, unrelated store
// operations must keep succeeding.
func TestYTDLPWaitingRequestsDoNotHoldStoreMutex(t *testing.T) {
	upstream := newCapacityAudioUpstream(t)
	g := newCapacityGate(t, upstream.URL)
	app := newCapacityApp(t, g)
	ts := httptest.NewServer(app.Handler())
	t.Cleanup(ts.Close)
	t.Cleanup(g.releaseAll) // LIFO: open the gate before the server closes

	room := capacityRoom(t, app.Store, false)
	client := ts.Client()
	client.Timeout = 2 * time.Second // bounded: a held mutex would time out
	const parked = 2
	outcomes := make(chan capacityOutcome, parked)
	for i := 0; i < parked; i++ {
		go func(i int) {
			outcomes <- capacityPost(t, client, "host-add",
				ts.URL+"/rooms/"+room+"/queue",
				fmt.Sprintf(`{"url":"https://www.youtube.com/watch?v=cap-%d"}`, i))
		}(i)
	}
	receiveWithin(t, g.starts)
	receiveWithin(t, g.starts)

	// Store operations that need the room mutex must succeed while the two
	// lookups are parked waiting for their subprocess to finish.
	res, err := client.Post(ts.URL+"/rooms", "application/json", nil)
	if err != nil {
		t.Fatalf("create room while lookups wait: %v (store mutex held during capacity wait?)", err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("create room status = %d, want 201", res.StatusCode)
	}
	res, err = client.Get(ts.URL + "/rooms/" + room)
	if err != nil {
		t.Fatalf("get room while lookups wait: %v (store mutex held during capacity wait?)", err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("get room status = %d, want 200", res.StatusCode)
	}

	g.releaseAll()
	for i := 0; i < parked; i++ {
		outcome := receiveWithin(t, outcomes)
		if outcome.status < 200 || outcome.status > 299 {
			t.Errorf("%s status = %d, want 2xx", outcome.kind, outcome.status)
		}
	}
}
