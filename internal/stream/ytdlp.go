package stream

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ytdlpBin is the yt-dlp executable used when no Bin is configured.
const ytdlpBin = "yt-dlp"

// ytdlpDefaultCacheTTL is how long a resolved audio URL is reused before the
// next request searches YouTube again.
const ytdlpDefaultCacheTTL = 5 * time.Minute

// ytdlpSearchTimeout bounds a YouTube search independently of the client
// request lifetime. Search has a larger budget than direct metadata lookup.
const ytdlpSearchTimeout = 60 * time.Second

// Runner executes yt-dlp and returns its raw stdout. It isolates the stream
// backend behind an injectable seam so tests feed fixture JSON instead of
// invoking a real binary (same pattern as the resolver's YouTube runner).
type Runner interface {
	// Search returns yt-dlp's --dump-json output for an input URL or search.
	Search(ctx context.Context, input string) ([]byte, error)
}

// ytdlpRunner shells out to yt-dlp requesting best audio only (no download).
type ytdlpRunner struct {
	bin string
}

// Search runs yt-dlp for input, which is either an original YouTube URL or a
// ytsearch query. The --dump-json document includes the direct audio URL.
func (r ytdlpRunner) Search(ctx context.Context, input string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, r.bin,
		"--skip-download", "--dump-json", "--no-warnings",
		"-f", "bestaudio",
		input,
	)
	return cmd.Output()
}

// searchResult is the subset of yt-dlp's --dump-json document we consume to
// locate a direct audio URL. Only the resolved stream URL is needed.
type searchResult struct {
	URL string `json:"url"`
}

// YTDLP locates and streams audio via yt-dlp. YouTube-resolved tracks use their
// source URL; other tracks search by metadata. Resolved direct audio URLs are
// cached for ~5 minutes so repeated streams avoid another lookup. The HTTP
// fetch itself is done by the Client.
type YTDLP struct {
	// Runner drives the YouTube search. If nil, the default yt-dlp exec runner
	// is used. Tests inject a fake runner returning fixture JSON.
	Runner Runner
	// Client performs the audio GET. If nil, streamClient is used: the
	// response-header timeout bounds hung upstreams, but the audio body is
	// never cut (a whole-request timeout would kill long streams).
	Client *http.Client
	// Bin overrides the yt-dlp executable path for the default runner.
	Bin string
	// CacheTTL is how long a resolved URL is cached. If zero,
	// ytdlpDefaultCacheTTL is used. A negative value disables the cache.
	CacheTTL time.Duration
	// SearchTimeout bounds one yt-dlp search. Zero uses ytdlpSearchTimeout.
	SearchTimeout time.Duration
	// cache is the lazily created TTL cache; mu guards its initialization and
	// injection so concurrent Stream calls are safe under -race.
	mu    sync.Mutex
	cache *Cache
}

// runner returns the configured Runner or the default exec runner.
func (b *YTDLP) runner() Runner {
	if b.Runner != nil {
		return b.Runner
	}
	bin := b.Bin
	if bin == "" {
		bin = ytdlpBin
	}
	return ytdlpRunner{bin: bin}
}

// streamHeaderTimeout bounds only the upstream handshake and response headers
// (qmix#40). The audio body streams for the length of the track, so a
// whole-request Client.Timeout would cut long streams mid-playback.
const streamHeaderTimeout = 10 * time.Second

// streamClient is the fallback HTTP client for the audio GET. Its transport
// is a clone of http.DefaultTransport with only the response-header timeout
// added, so streaming connections keep the standard library's pooling and
// TLS defaults.
var streamClient = &http.Client{
	Transport: func() http.RoundTripper {
		tr := http.DefaultTransport.(*http.Transport).Clone()
		tr.ResponseHeaderTimeout = streamHeaderTimeout
		return tr
	}(),
}

// client returns the configured HTTP client or the streaming default.
func (b *YTDLP) client() *http.Client {
	if b.Client != nil {
		return b.Client
	}
	return streamClient
}

// ttl returns the effective cache TTL.
func (b *YTDLP) ttl() time.Duration {
	if b.CacheTTL == 0 {
		return ytdlpDefaultCacheTTL
	}
	return b.CacheTTL
}

func (b *YTDLP) searchTimeout() time.Duration {
	if b.SearchTimeout <= 0 {
		return ytdlpSearchTimeout
	}
	return b.SearchTimeout
}

// cache returns the lazily created Cache (disabled when TTL is negative). It is
// thread-safe: concurrent first calls create the cache exactly once.
func (b *YTDLP) cacheRef() *Cache {
	if b.CacheTTL < 0 {
		return nil
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cache == nil {
		b.cache = NewCache(b.ttl())
	}
	return b.cache
}

// SetCache injects a Cache (used by tests to reset or to observe state). It
// returns the previous cache.
func (b *YTDLP) SetCache(c *Cache) *Cache {
	b.mu.Lock()
	defer b.mu.Unlock()
	prev := b.cache
	b.cache = c
	return prev
}

// cacheKey builds a stable key for the selected yt-dlp input. Direct YouTube
// paths use the source URL, preventing metadata collisions from sharing audio.
func cacheKey(t *Track) string {
	if source, ok := directSource(t); ok {
		return "source\x00" + source
	}
	return "search\x00" + t.Artist + "\x00" + t.Title
}

// searchQuery is the YouTube search string for a track: "Artist - Title".
func searchQuery(t *Track) string {
	if strings.TrimSpace(t.Artist) != "" {
		return strings.TrimSpace(t.Artist) + " - " + strings.TrimSpace(t.Title)
	}
	return strings.TrimSpace(t.Title)
}

// ytdlpInput preserves an original URL only when the resolver explicitly
// identifies it as YouTube. Other sources continue to use metadata search.
func ytdlpInput(t *Track) string {
	if source, ok := directSource(t); ok {
		return source
	}
	return "ytsearch:" + searchQuery(t)
}

func directSource(t *Track) (string, bool) {
	return t.URL, t.ResolvedBy == "youtube" && strings.TrimSpace(t.URL) != ""
}

// resolveURL finds the direct audio URL for track, honoring the URL cache.
func (b *YTDLP) resolveURL(ctx context.Context, t *Track) (string, error) {
	key := cacheKey(t)
	c := b.cacheRef()
	if c == nil {
		return b.searchURL(ctx, t)
	}
	if v, ok := c.Get(key); ok {
		if u, ok := v.(string); ok {
			return u, nil
		}
	}
	// The shared lookup survives one caller leaving while other callers still
	// need it, and is canceled when its final waiter disconnects.
	v, err := c.DoContext(ctx, key, func(loadCtx context.Context) (interface{}, error) {
		return b.searchURL(loadCtx, t)
	})
	if err != nil {
		return "", err
	}
	u, ok := v.(string)
	if !ok {
		return "", fmt.Errorf("ytdlp: %w: cached value is not a url", ErrService)
	}
	return u, nil
}

// searchURL runs the YouTube search via the runner and extracts the top audio
// URL. It maps no-results to ErrNotFound and exec/parse failures to ErrService.
func (b *YTDLP) searchURL(ctx context.Context, t *Track) (string, error) {
	searchCtx, cancel := context.WithTimeout(ctx, b.searchTimeout())
	defer cancel()
	out, err := b.runner().Search(searchCtx, ytdlpInput(t))
	if err != nil {
		return "", fmt.Errorf("ytdlp: %w: %w", ErrService, err)
	}
	r, err := parseFirstSearchResult(out)
	if err != nil {
		return "", err
	}
	if r.URL == "" {
		return "", errors.Join(ErrNotFound, errors.New("ytdlp: no audio url in search result"))
	}
	return r.URL, nil
}

// parseFirstSearchResult reads the first JSON document from yt-dlp output. A
// search may emit multiple lines (one per result); we take the top result.
func parseFirstSearchResult(out []byte) (*searchResult, error) {
	line, err := firstJSONLine(out)
	if err != nil {
		return nil, errors.Join(ErrService, err)
	}
	var r searchResult
	if err := json.Unmarshal(line, &r); err != nil {
		return nil, fmt.Errorf("ytdlp: %w: %w", ErrService, err)
	}
	return &r, nil
}

// firstJSONLine returns the first non-empty line of out. yt-dlp writes one
// JSON document per line even for multi-result searches.
func firstJSONLine(out []byte) ([]byte, error) {
	s := bufio.NewScanner(bytes.NewReader(out))
	// Real yt-dlp dump-json output is a single line carrying the full format
	// list of the result, which exceeds the scanner's default 64KB token cap
	// (qmix#19). 4MB is far above any plausible document.
	s.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for s.Scan() {
		line := bytes.TrimSpace(s.Bytes())
		if len(line) > 0 {
			return line, nil
		}
	}
	if err := s.Err(); err != nil {
		return nil, err
	}
	return nil, errors.New("ytdlp: empty search output")
}

// Stream resolves the audio URL for track and opens it with the optional Range
// header. The upstream response status and range headers are captured into the
// returned Result so the proxy can reproduce them for the client.
func (b *YTDLP) Stream(ctx context.Context, t *Track, rangeHeader string) (*Result, error) {
	url, err := b.resolveURL(ctx, t)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("ytdlp: %w: %w", ErrService, err)
	}
	if rangeHeader != "" {
		req.Header.Set("Range", rangeHeader)
	}

	resp, err := b.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("ytdlp: %w: %w", ErrService, err)
	}

	res := &Result{
		Body:          resp.Body,
		ContentType:   resp.Header.Get("Content-Type"),
		Status:        resp.StatusCode,
		ContentLength: resp.ContentLength,
		ContentRange:  resp.Header.Get("Content-Range"),
		AcceptRanges:  resp.Header.Get("Accept-Ranges"),
	}
	if res.Status == http.StatusPartialContent && res.ContentRange == "" {
		// Upstream applied the range but did not echo a Content-Range; derive
		// one from the body length so the client can compute the seek window.
		res.ContentRange = "bytes 0-" + formatInt(resp.ContentLength-1) + "/" + formatInt(resp.ContentLength)
	}
	return res, nil
}

// formatInt formats n as a decimal string (-1 yields "-1", which the caller
// only passes when it genuinely has a length).
func formatInt(n int64) string {
	return strconv.FormatInt(n, 10)
}
