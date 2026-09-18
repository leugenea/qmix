package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeRunner feeds canned yt-dlp --dump-json output to the YTDLP backend and
// records the query it was asked for.
type fakeRunner struct {
	out []byte
	err error
	got []string
}

func (f *fakeRunner) Search(_ context.Context, query string) ([]byte, error) {
	f.got = append(f.got, query)
	return f.out, f.err
}

type blockingRunner struct {
	started chan struct{}
	release chan struct{}
	calls   atomic.Int32
}

func (r *blockingRunner) Search(ctx context.Context, _ string) ([]byte, error) {
	if r.calls.Add(1) == 1 {
		close(r.started)
	}
	select {
	case <-r.release:
		return []byte(searchFixture), nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

const searchFixture = `{"title":"Some Song","artist":"Some Artist","url":"https://media.example/audio.webm"}`

// TestYtdlpYouTubeSourceUsesOriginalURL guards qmix#128: a resolved YouTube
// source must be passed back to yt-dlp unchanged, not replaced by a metadata
// search that could select a different video.
func TestYtdlpYouTubeSourceUsesOriginalURL(t *testing.T) {
	const source = "https://www.youtube.com/watch?v=exact-video-id"
	r := &fakeRunner{out: []byte(searchFixture)}
	b := &YTDLP{Runner: r, CacheTTL: -1}

	_, err := b.resolveURL(context.Background(), &Track{
		ID:         "1",
		URL:        source,
		Title:      "Colliding Song",
		Artist:     "Colliding Artist",
		ResolvedBy: "youtube",
	})
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if len(r.got) != 1 || r.got[0] != source {
		t.Fatalf("yt-dlp input = %q, want original source %q", r.got, source)
	}
}

// TestYtdlpWatchURLExecUsesSingleVideoMode proves the stream exec runner keeps
// the selected watch URL intact and prevents playlist traversal (qmix#129).
func TestYtdlpWatchURLExecUsesSingleVideoMode(t *testing.T) {
	const source = "https://www.youtube.com/watch?v=selected-video&list=playlist-id"
	dir := t.TempDir()
	argsFile := filepath.Join(dir, "args")
	bin := filepath.Join(dir, "yt-dlp")
	script := fmt.Sprintf("#!/bin/sh\nprintf '%%s\\n' \"$@\" > %q\nprintf '%%s\\n' '{\"url\":\"https://media.example/selected.webm\"}'\n", argsFile)
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}

	backend := &YTDLP{Bin: bin, CacheTTL: -1}
	gotURL, err := backend.resolveURL(context.Background(), &Track{URL: source, ResolvedBy: "youtube"})
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if gotURL != "https://media.example/selected.webm" {
		t.Fatalf("url = %q, want selected video stream", gotURL)
	}
	got, err := os.ReadFile(argsFile)
	if err != nil {
		t.Fatal(err)
	}
	want := "--skip-download\n--dump-json\n--no-warnings\n--no-playlist\n-f\nbestaudio\n" + source + "\n"
	if string(got) != want {
		t.Fatalf("yt-dlp args = %q, want %q", got, want)
	}
}

// TestYtdlpResolveURL verifies non-YouTube sources keep the metadata-search
// fallback and that the resolved audio URL comes straight from the fixture.
func TestYtdlpResolveURL(t *testing.T) {
	for _, tc := range []struct {
		resolvedBy string
		url        string
	}{
		{resolvedBy: "spotify", url: "https://open.spotify.com/track/x"},
		{resolvedBy: "vk", url: "https://vk.com/audio1_2"},
		{resolvedBy: "yandex", url: "https://music.yandex.ru/track/3"},
		{resolvedBy: "vkyandex", url: "https://music.yandex.ru/track/4"},
	} {
		t.Run(tc.resolvedBy, func(t *testing.T) {
			r := &fakeRunner{out: []byte(searchFixture)}
			b := &YTDLP{Runner: r, CacheTTL: -1}
			url, err := b.resolveURL(context.Background(), &Track{
				ID: "1", URL: tc.url,
				Title: "Some Song", Artist: "Some Artist", ResolvedBy: tc.resolvedBy,
			})
			if err != nil {
				t.Fatalf("resolve: %v", err)
			}
			if url != "https://media.example/audio.webm" {
				t.Fatalf("url = %q", url)
			}
			if len(r.got) != 1 || r.got[0] != "ytsearch:Some Artist - Some Song" {
				t.Fatalf("input = %q, want %q", r.got, "ytsearch:Some Artist - Some Song")
			}
		})
	}
}

// TestYtdlpQueryFallbackTitle verifies the query is just the title when there
// is no artist.
func TestYtdlpQueryFallbackTitle(t *testing.T) {
	r := &fakeRunner{out: []byte(`{"title":"Instrumental","url":"https://m.example/a"}`)}
	b := &YTDLP{Runner: r, CacheTTL: -1}
	_, err := b.resolveURL(context.Background(), &Track{Title: "Instrumental"})
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if len(r.got) != 1 || r.got[0] != "ytsearch:Instrumental" {
		t.Fatalf("input = %q, want ytsearch:Instrumental", r.got)
	}
}

// TestYtdlpRunnerError maps exec failure to ErrService.
func TestYtdlpRunnerError(t *testing.T) {
	wantErr := errors.New("exec failed")
	b := &YTDLP{Runner: &fakeRunner{err: wantErr}, CacheTTL: -1}
	_, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if !errors.Is(err, ErrService) || !errors.Is(err, wantErr) {
		t.Fatalf("err = %v, want ErrService and wrapped runner cause", err)
	}
}

// TestYtdlpSearchDeadline bounds a blocking search independently of the
// incoming request context (qmix#116).
func TestYtdlpSearchDeadline(t *testing.T) {
	r := &blockingRunner{started: make(chan struct{}), release: make(chan struct{})}
	b := &YTDLP{Runner: r, CacheTTL: -1, SearchTimeout: time.Millisecond}
	_, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if !errors.Is(err, ErrService) || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want ErrService and context.DeadlineExceeded", err)
	}
	if calls := r.calls.Load(); calls != 1 {
		t.Fatalf("runner calls = %d, want 1 terminated search", calls)
	}
}

// TestYtdlpEmptyOutput maps an empty runner response to ErrService.
func TestYtdlpEmptyOutput(t *testing.T) {
	b := &YTDLP{Runner: &fakeRunner{}, CacheTTL: -1}
	_, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestYtdlpBadJSON maps unparseable output to ErrService.
func TestYtdlpBadJSON(t *testing.T) {
	b := &YTDLP{Runner: &fakeRunner{out: []byte("garbage")}, CacheTTL: -1}
	_, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestYtdlpNoURL maps a result without a url to ErrNotFound.
func TestYtdlpNoURL(t *testing.T) {
	b := &YTDLP{Runner: &fakeRunner{out: []byte(`{"title":"T"}`)}, CacheTTL: -1}
	_, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
}

// TestYtdlpFirstLineOnly verifies multi-line search output uses the top result.
func TestYtdlpFirstLineOnly(t *testing.T) {
	out := []byte("{\"title\":\"First\",\"url\":\"https://m.example/1\"}\n{\"title\":\"Second\",\"url\":\"https://m.example/2\"}\n")
	b := &YTDLP{Runner: &fakeRunner{out: out}, CacheTTL: -1}
	url, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if url != "https://m.example/1" {
		t.Fatalf("url = %q, want first result url", url)
	}
}

// TestYtdlpDirectCacheKeyUsesSourceURL guards qmix#128: metadata collisions
// must not share a direct-path entry, while metadata changes for the same
// source URL must continue to reuse it.
func TestYtdlpDirectCacheKeyUsesSourceURL(t *testing.T) {
	const (
		first  = "https://www.youtube.com/watch?v=first-id"
		second = "https://www.youtube.com/watch?v=second-id"
	)
	r := &fakeRunner{out: []byte(searchFixture)}
	b := &YTDLP{Runner: r, CacheTTL: time.Minute}

	tracks := []*Track{
		{URL: first, Title: "Collision", Artist: "Artist", ResolvedBy: "youtube"},
		{URL: second, Title: "Collision", Artist: "Artist", ResolvedBy: "youtube"},
		{URL: first, Title: "Changed metadata", Artist: "Other", ResolvedBy: "youtube"},
	}
	for _, track := range tracks {
		if _, err := b.resolveURL(context.Background(), track); err != nil {
			t.Fatalf("resolve %q: %v", track.URL, err)
		}
	}
	if len(r.got) != 2 || r.got[0] != first || r.got[1] != second {
		t.Fatalf("yt-dlp inputs = %q, want [%q %q]", r.got, first, second)
	}
}

// TestYtdlpCacheReuse verifies a second resolve of the same track uses the
// cache and does not re-run the runner.
func TestYtdlpCacheReuse(t *testing.T) {
	r := &fakeRunner{out: []byte(searchFixture)}
	c := NewCache(time.Minute)
	b := &YTDLP{Runner: r, CacheTTL: time.Minute}
	b.SetCache(c)

	tr := &Track{ID: "1", Title: "Some Song", Artist: "Some Artist"}
	if _, err := b.resolveURL(context.Background(), tr); err != nil {
		t.Fatal(err)
	}
	if _, err := b.resolveURL(context.Background(), tr); err != nil {
		t.Fatal(err)
	}
	if len(r.got) != 1 {
		t.Fatalf("runner invoked %d times, want 1 (cached)", len(r.got))
	}
}

// TestYtdlpConcurrentResolve verifies concurrent resolves for the same track
// run the runner once under -race.
func TestYtdlpConcurrentResolve(t *testing.T) {
	r := &fakeRunner{out: []byte(searchFixture)}
	b := &YTDLP{Runner: r, CacheTTL: time.Minute}

	var wg sync.WaitGroup
	errs := make(chan error, 20)
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := b.resolveURL(context.Background(), &Track{ID: "1", Title: "Some Song", Artist: "Some Artist"})
			if err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if len(r.got) != 1 {
		t.Fatalf("runner invoked %d times, want 1 under concurrency", len(r.got))
	}
}

// TestYtdlpLeaderCancellationDoesNotPoisonWaiter verifies the shared search is
// detached from its initiating request while every caller can stop waiting.
func TestYtdlpLeaderCancellationDoesNotPoisonWaiter(t *testing.T) {
	r := &blockingRunner{started: make(chan struct{}), release: make(chan struct{})}
	b := &YTDLP{Runner: r, CacheTTL: time.Minute}
	track := &Track{ID: "1", Title: "Some Song", Artist: "Some Artist"}

	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	leaderDone := make(chan error, 1)
	go func() {
		_, err := b.resolveURL(leaderCtx, track)
		leaderDone <- err
	}()
	<-r.started

	waiterDone := make(chan error, 1)
	go func() {
		url, err := b.resolveURL(context.Background(), track)
		if err == nil && url != "https://media.example/audio.webm" {
			err = fmt.Errorf("url = %q, want fixture URL", url)
		}
		waiterDone <- err
	}()
	waitForCacheWaiters(t, b.cacheRef(), cacheKey(track), 2)
	cancelLeader()
	if err := <-leaderDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("leader error = %v, want context.Canceled", err)
	}

	close(r.release)
	if err := <-waiterDone; err != nil {
		t.Fatalf("waiter error = %v", err)
	}
	if calls := r.calls.Load(); calls != 1 {
		t.Fatalf("runner calls = %d, want 1 shared search", calls)
	}
}

// TestYtdlpStream verifies Stream performs the upstream audio GET and captures
// content headers and status.
func TestYtdlpStream(t *testing.T) {
	var sawRange string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawRange = r.Header.Get("Range")
		w.Header().Set("Content-Type", "audio/webm")
		w.Header().Set("Content-Length", "11")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "helloworld")
	}))
	defer upstream.Close()

	// The resolved URL comes from the runner and points at our fake upstream.
	fixture := `{"title":"T","url":"` + upstream.URL + `/stream"}`
	b := &YTDLP{Runner: &fakeRunner{out: []byte(fixture)}, Client: upstream.Client(), CacheTTL: -1}
	res, err := b.Stream(context.Background(), &Track{Title: "T"}, "")
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	defer res.Body.Close()
	if res.Status != http.StatusOK || res.ContentType != "audio/webm" {
		t.Fatalf("result = %+v", res)
	}
	body, _ := io.ReadAll(res.Body)
	if string(body) != "helloworld" {
		t.Fatalf("body = %q", body)
	}
	if sawRange != "" {
		t.Fatalf("unexpected Range forwarded upstream: %q", sawRange)
	}
}

// TestYtdlpStreamForwardsRange verifies the client Range is forwarded upstream
// and a 206 is captured with its Content-Range.
func TestYtdlpStreamForwardsRange(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") != "bytes=0-4" {
			http.Error(w, "bad range", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "audio/webm")
		w.Header().Set("Content-Range", "bytes 0-4/11")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.WriteString(w, "hello")
	}))
	defer upstream.Close()

	fixture := `{"title":"T","url":"` + upstream.URL + `/s"}`
	b := &YTDLP{Runner: &fakeRunner{out: []byte(fixture)}, Client: upstream.Client(), CacheTTL: -1}
	res, err := b.Stream(context.Background(), &Track{Title: "T"}, "bytes=0-4")
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	defer res.Body.Close()
	if res.Status != http.StatusPartialContent || res.ContentRange != "bytes 0-4/11" {
		t.Fatalf("result = %+v", res)
	}
}

// TestYtdlpStreamUpstreamError maps a transport failure to ErrService.
func TestYtdlpStreamUpstreamError(t *testing.T) {
	// A URL pointing at a closed port forces a transport error.
	fixture := `{"title":"T","url":"http://127.0.0.1:1/s"}`
	b := &YTDLP{Runner: &fakeRunner{out: []byte(fixture)}, CacheTTL: -1}
	_, err := b.Stream(context.Background(), &Track{Title: "T"}, "")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestYtdlpDefaultRunner verifies the zero-value uses the exec runner.
func TestYtdlpDefaultRunner(t *testing.T) {
	b := &YTDLP{}
	if r, ok := b.runner().(ytdlpRunner); !ok {
		t.Fatalf("default runner = %T, want ytdlpRunner", b.runner())
	} else if r.bin != ytdlpBin {
		t.Fatalf("bin = %q, want %q", r.bin, ytdlpBin)
	}
	if b.client() != streamClient {
		t.Fatal("default client should be streamClient (response-header timeout only, qmix#40)")
	}
}

// TestYtdlpConfiguredBin verifies Bin is honored by the default runner and TTL
// defaults to 5 minutes.
func TestYtdlpConfiguredBin(t *testing.T) {
	b := &YTDLP{Bin: "/custom/yt-dlp"}
	r := b.runner().(ytdlpRunner)
	if r.bin != "/custom/yt-dlp" {
		t.Fatalf("bin = %q", r.bin)
	}
	if b.ttl() != ytdlpDefaultCacheTTL {
		t.Fatalf("ttl = %v, want %v", b.ttl(), ytdlpDefaultCacheTTL)
	}
	if b.searchTimeout() != ytdlpSearchTimeout {
		t.Fatalf("search timeout = %v, want %v", b.searchTimeout(), ytdlpSearchTimeout)
	}
}

// TestYtdlpNegativeTTLDisablesCache verifies a negative TTL disables caching.
func TestYtdlpNegativeTTLDisablesCache(t *testing.T) {
	b := &YTDLP{CacheTTL: -1}
	if b.cacheRef() != nil {
		t.Fatal("negative TTL should disable the cache")
	}
}

// TestSearchQuerySpace verifies the query builder trims surrounding whitespace.
func TestSearchQuerySpace(t *testing.T) {
	if q := searchQuery(&Track{Title: "  Song ", Artist: " Artist "}); q != "Artist - Song" {
		t.Fatalf("query = %q", q)
	}
	if q := searchQuery(&Track{Title: " Only Title "}); q != "Only Title" {
		t.Fatalf("query = %q", q)
	}
}

// TestYtdlpRunnerSearch verifies the exec runner invokes yt-dlp with a search
// query and returns the raw stdout. A fake executable is placed on PATH so the
// test never touches the real tool or network.
func TestYtdlpRunnerSearch(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "yt-dlp")
	err := os.WriteFile(bin, []byte("#!/bin/sh\necho '{\"title\":\"fixture\",\"url\":\"https://m.example/a\"}'\n"), 0o755)
	if err != nil {
		t.Fatal(err)
	}
	r := ytdlpRunner{bin: bin}
	out, err := r.Search(context.Background(), "ytsearch:Artist - Song")
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if !strings.Contains(string(out), "fixture") {
		t.Fatalf("out = %q, want fixture", out)
	}
}

// TestYtdlpRunnerSearchExecError verifies a missing binary becomes an exec
// error surfaced by Search.
func TestYtdlpRunnerSearchExecError(t *testing.T) {
	r := ytdlpRunner{bin: "/nonexistent/qmix-yt-dlp"}
	if _, err := r.Search(context.Background(), "q"); err == nil {
		t.Fatal("expected exec error")
	}
}

// TestYtdlpStreamDerivesContentRange covers the 206-without-Content-Range path
// (and formatInt) where the backend synthesizes a Content-Range from the body
// length.
func TestYtdlpStreamDerivesContentRange(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/webm")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.WriteString(w, "hello") // 5 bytes; no Content-Range echoed
	}))
	defer upstream.Close()

	fixture := `{"title":"T","url":"` + upstream.URL + `/s"}`
	b := &YTDLP{Runner: &fakeRunner{out: []byte(fixture)}, Client: upstream.Client(), CacheTTL: -1}
	res, err := b.Stream(context.Background(), &Track{Title: "T"}, "bytes=0-4")
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	defer res.Body.Close()
	if res.Status != http.StatusPartialContent {
		t.Fatalf("status = %d, want 206", res.Status)
	}
	if res.ContentRange != "bytes 0-4/5" {
		t.Fatalf("content-range = %q, want bytes 0-4/5", res.ContentRange)
	}
}

// TestYtdlpCachedNonString verifies a cached non-string value (impossible via
// the normal code path, but guarded defensively) surfaces as a service error
// rather than a panic or type confusion.
func TestYtdlpCachedNonString(t *testing.T) {
	c := NewCache(time.Minute)
	track := &Track{ID: "1", Title: "x"}
	c.entries[cacheKey(track)] = &entry{value: 42, expiry: time.Now().Add(time.Minute)}
	b := &YTDLP{Runner: &fakeRunner{out: []byte(searchFixture)}, CacheTTL: time.Minute}
	b.SetCache(c)
	_, err := b.resolveURL(context.Background(), track)
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestFirstJSONLineLongToken guards against bufio.Scanner's default 64KB
// token cap: real yt-dlp dump-json output is a single line carrying the full
// format list of the result, which exceeds 64KB (qmix#19).
func TestFirstJSONLineLongToken(t *testing.T) {
	url := `https://example.com/audio?u=` + strings.Repeat("a", 128*1024)
	line := []byte(`{"url": "` + url + `"}` + "\n")
	r, err := parseFirstSearchResult(line)
	if err != nil {
		t.Fatalf("parse long line: %v", err)
	}
	if r.URL != url {
		t.Fatal("long-line url mismatch")
	}
}

// TestYTDLP_DefaultClientHeaderTimeoutOnly pins the audit fix (qmix#40): the
// streaming fallback must time out hung upstreams on response headers but
// never cut the audio body (Client.Timeout stays zero).
func TestYTDLP_DefaultClientHeaderTimeoutOnly(t *testing.T) {
	c := (&YTDLP{}).client()
	if c == http.DefaultClient {
		t.Fatal("fallback client is http.DefaultClient")
	}
	if c.Timeout != 0 {
		t.Fatalf("Client.Timeout = %v, want 0 (streaming bodies)", c.Timeout)
	}
	tr, ok := c.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Transport is %T, want *http.Transport", c.Transport)
	}
	if tr.ResponseHeaderTimeout != streamHeaderTimeout {
		t.Fatalf("ResponseHeaderTimeout = %v, want %v", tr.ResponseHeaderTimeout, streamHeaderTimeout)
	}
}
