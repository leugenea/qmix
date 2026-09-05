package stream

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
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

const searchFixture = `{"title":"Some Song","artist":"Some Artist","url":"https://media.example/audio.webm"}`

// TestYtdlpResolveURL verifies a resolved URL comes straight from the fixture.
func TestYtdlpResolveURL(t *testing.T) {
	r := &fakeRunner{out: []byte(searchFixture)}
	b := &YTDLP{Runner: r, CacheTTL: -1}
	url, err := b.resolveURL(context.Background(), &Track{ID: "1", Title: "Some Song", Artist: "Some Artist"})
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if url != "https://media.example/audio.webm" {
		t.Fatalf("url = %q", url)
	}
	if len(r.got) != 1 || r.got[0] != "Some Artist - Some Song" {
		t.Fatalf("query = %q, want %q", r.got, "Some Artist - Some Song")
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
	if len(r.got) != 1 || r.got[0] != "Instrumental" {
		t.Fatalf("query = %q, want Instrumental", r.got)
	}
}

// TestYtdlpRunnerError maps exec failure to ErrService.
func TestYtdlpRunnerError(t *testing.T) {
	b := &YTDLP{Runner: &fakeRunner{err: errors.New("exec failed")}, CacheTTL: -1}
	_, err := b.resolveURL(context.Background(), &Track{Title: "x"})
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
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
	if b.client() != http.DefaultClient {
		t.Fatal("default client should be http.DefaultClient")
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
	out, err := r.Search(context.Background(), "Artist - Song")
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
	c.entries["\x00x"] = &entry{value: 42, expiry: time.Now().Add(time.Minute)}
	b := &YTDLP{Runner: &fakeRunner{out: []byte(searchFixture)}, CacheTTL: time.Minute}
	b.SetCache(c)
	_, err := b.resolveURL(context.Background(), &Track{ID: "1", Title: "x"})
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
