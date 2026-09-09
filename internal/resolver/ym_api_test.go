package resolver

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/oklookat/goym/schema"
)

// ymMockFetcher is the test double for ymTrackFetcher (qmix#10). It records
// the requested id and returns the configured info or error.
type ymMockFetcher struct {
	hits   int
	lastID int64
	info   ymTrackInfo
	err    error // returned as-is (a generic error: transport, JSON, ...)
	apiErr error // when set, returned instead of err (a schema.Error from the API)
}

func (m *ymMockFetcher) track(_ context.Context, trackID int64) (ymTrackInfo, error) {
	m.hits++
	m.lastID = trackID
	if m.apiErr != nil {
		return ymTrackInfo{}, m.apiErr
	}
	if m.err != nil {
		return ymTrackInfo{}, m.err
	}
	return m.info, nil
}

// newYMTestAPIError builds a schema.Error value for the mock fetcher.
func newYMTestAPIError(name, message string) error {
	return schema.Error{Name: name, Message: message}
}

// newYMResolver builds a resolver with a token and an injected mock fetcher
// (the og-meta endpoint is pointed at a server that fails the test if hit,
// guarding the routing between the authenticated and anonymous paths).
func newYMResolver(t *testing.T, m ymTrackFetcher) *VKYandex {
	t.Helper()
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("og-meta path must not be hit for a yandex track link with a token")
	}))
	t.Cleanup(og.Close)
	return &VKYandex{
		Client:    og.Client(),
		Endpoint:  og.URL,
		YMFetcher: m,
		Config:    Config{YMToken: "test-token"},
	}
}

// TestResolver_YMAPISuccess verifies the authenticated Yandex path for the
// share-link format from qmix#10 (album prefix + utm/ref_id tail).
func TestResolver_YMAPISuccess(t *testing.T) {
	m := &ymMockFetcher{info: ymTrackInfo{
		Title:      "Never Gonna Give You Up",
		Artists:    []string{"Rick Astley"},
		DurationMs: 212740,
	}}
	s := newYMResolver(t, m)
	const link = "https://music.yandex.ru/album/14599266/track/609676?utm_medium=copy_link&ref_id=3469c57a-0a9c-44ba-abeb-8293f2bb2fa7"
	tr, err := s.Resolve(context.Background(), link)
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if m.hits != 1 || m.lastID != 609676 {
		t.Fatalf("fetcher hits = %d, id = %d, want 1, 609676", m.hits, m.lastID)
	}
	if tr.Title != "Never Gonna Give You Up" || tr.Artist != "Rick Astley" || tr.DurationSec != 213 {
		t.Fatalf("track = %+v", tr)
	}
	if tr.ResolvedBy != "yandex" || tr.Source != link {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_YMAPISuccessBareTrack verifies a bare /track/{id} link (no
// album prefix) — the album id is not needed by the API.
func TestResolver_YMAPISuccessBareTrack(t *testing.T) {
	m := &ymMockFetcher{info: ymTrackInfo{Title: "T", Artists: []string{"A", "B"}, DurationMs: 1000}}
	s := newYMResolver(t, m)
	tr, err := s.Resolve(context.Background(), "https://music.yandex.com/track/609676")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if m.lastID != 609676 {
		t.Fatalf("id = %d, want 609676", m.lastID)
	}
	if tr.Artist != "A, B" || tr.DurationSec != 1 { // Spotify-style artist join + rounding
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_YMAPIError maps a fetcher error to ErrService with a fixed
// message that leaks neither the token nor any URL.
func TestResolver_YMAPIError(t *testing.T) {
	m := &ymMockFetcher{err: errors.New(`Get "https://api.music.yandex.net/tracks/609676": dial tcp: no route`)}
	s := newYMResolver(t, m)
	_, err := s.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	msg := err.Error()
	if strings.Contains(msg, "yandex.net") || strings.Contains(msg, "no route") || strings.Contains(msg, "test-token") {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
	if !strings.Contains(msg, "yandex music api: request failed") {
		t.Fatalf("err = %q, want fixed request failed message", msg)
	}
}

// TestResolver_YMAPIErrorPayload verifies a schema.Error (Yandex API error
// object) surfaces only the API name/message.
func TestResolver_YMAPIErrorPayload(t *testing.T) {
	m := &ymMockFetcher{}
	// Construct a schema.Error the way goym returns it (Error implements error
	// via a value method, so a value works with errors.As in resolveViaYM).
	apiErr := newYMTestAPIError("403", "Forbidden")
	m.apiErr = apiErr
	s := newYMResolver(t, m)
	_, err := s.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	msg := err.Error()
	if !strings.Contains(msg, "403") || !strings.Contains(msg, "Forbidden") {
		t.Fatalf("err = %q, want api name and message", msg)
	}
	if strings.Contains(msg, "test-token") || strings.Contains(msg, "music.yandex") || strings.Contains(msg, "yandex.net") {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
}

// TestResolver_YMAPIEmptyTitle covers a track payload without a title.
func TestResolver_YMAPIEmptyTitle(t *testing.T) {
	m := &ymMockFetcher{info: ymTrackInfo{Artists: []string{"A"}, DurationMs: 100}}
	s := newYMResolver(t, m)
	_, err := s.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	if !strings.Contains(err.Error(), "empty title") {
		t.Fatalf("err = %q, want empty title", err.Error())
	}
}

// TestResolver_YMAPIEmptyResult covers an empty result list.
func TestResolver_YMAPIEmptyResult(t *testing.T) {
	m := &ymMockFetcher{}
	s := newYMResolver(t, m)
	_, err := s.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_YMNoTokenFallsBackToOG verifies a Yandex track link without a
// token keeps the M2 anonymous behaviour.
func TestResolver_YMNoTokenFallsBackToOG(t *testing.T) {
	m := &ymMockFetcher{info: ymTrackInfo{Title: "T"}}
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><head><meta property="og:title" content="Anon Title"></head></html>`))
	}))
	defer og.Close()

	v := &VKYandex{Client: og.Client(), Endpoint: og.URL, YMFetcher: m, Config: Config{}}
	tr, err := v.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Anon Title" || tr.ResolvedBy != "vkyandex" {
		t.Fatalf("track = %+v", tr)
	}
	if m.hits != 0 {
		t.Fatalf("ym fetcher hits = %d, want 0 without a token", m.hits)
	}
}

// TestResolver_YMNonTrackLinkFallsBackToOG verifies a Yandex link without a
// track segment goes to the og-meta path even with a token.
func TestResolver_YMNonTrackLinkFallsBackToOG(t *testing.T) {
	m := &ymMockFetcher{info: ymTrackInfo{Title: "T"}}
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><head><meta property="og:title" content="Album Page"></head></html>`))
	}))
	defer og.Close()

	v := &VKYandex{Client: og.Client(), Endpoint: og.URL, YMFetcher: m, Config: Config{YMToken: "test-token"}}
	tr, err := v.Resolve(context.Background(), "https://music.yandex.ru/album/14599266")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Album Page" || tr.ResolvedBy != "vkyandex" {
		t.Fatalf("track = %+v", tr)
	}
	if m.hits != 0 {
		t.Fatalf("ym fetcher hits = %d, want 0 for a non-track link", m.hits)
	}
}

// TestResolver_YMMalformedTrackID verifies a track segment with a non-numeric
// id reports ErrInvalid and never reaches the fetcher.
func TestResolver_YMMalformedTrackID(t *testing.T) {
	m := &ymMockFetcher{info: ymTrackInfo{Title: "T"}}
	s := newYMResolver(t, m)
	_, err := s.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/notanumber")
	if !errors.Is(err, ErrInvalid) {
		t.Fatalf("err = %v, want ErrInvalid", err)
	}
	if m.hits != 0 {
		t.Fatalf("ym fetcher hits = %d, want 0", m.hits)
	}
	msg := err.Error()
	if strings.Contains(msg, "test-token") || strings.Contains(msg, "music.yandex") {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
}

// TestYMTrackID covers the link parser table: share format with utm tail,
// bare track, non-numeric id, missing id, non-track paths, garbage.
func TestYMTrackID(t *testing.T) {
	share := "https://music.yandex.ru/album/14599266/track/609676?utm_medium=copy_link&ref_id=3469c57a-0a9c-44ba-abeb-8293f2bb2fa7"
	if id, ok, err := ymTrackID(share); err != nil || !ok || id != 609676 {
		t.Errorf("ymTrackID(share) = %d,%v,%v", id, ok, err)
	}
	if id, ok, err := ymTrackID("https://music.yandex.com/track/42"); err != nil || !ok || id != 42 {
		t.Errorf("ymTrackID(bare) = %d,%v,%v", id, ok, err)
	}
	if id, ok, err := ymTrackID("https://music.yandex.ru/album/14599266"); err != nil || ok || id != 0 {
		t.Errorf("ymTrackID(album) = %d,%v,%v", id, ok, err)
	}
	if id, ok, err := ymTrackID("https://music.yandex.ru/users/foo/playlists/3"); err != nil || ok || id != 0 {
		t.Errorf("ymTrackID(playlist) = %d,%v,%v", id, ok, err)
	}
	if _, _, err := ymTrackID("https://music.yandex.ru/track/abc"); !errors.Is(err, ErrInvalid) {
		t.Errorf("ymTrackID(nonnumeric) err = %v, want ErrInvalid", err)
	}
	if _, _, err := ymTrackID("https://music.yandex.ru/track/-5"); !errors.Is(err, ErrInvalid) {
		t.Errorf("ymTrackID(negative) err = %v, want ErrInvalid", err)
	}
	if _, _, err := ymTrackID("https://music.yandex.ru/album/1/track"); !errors.Is(err, ErrInvalid) {
		t.Errorf("ymTrackID(no id) err = %v, want ErrInvalid", err)
	}
	if id, ok, err := ymTrackID("https://music.yandex.ru/track/609676/extra"); err != nil || !ok || id != 609676 {
		t.Errorf("ymTrackID(trailing) = %d,%v,%v", id, ok, err)
	}
	// Garbage that parses but has no host: ok=false, no error (the Mux
	// reports ErrInvalid upstream); a garbage string that does not parse at
	// all is rejected by the Mux before the resolver sees it.
	if id, ok, err := ymTrackID("http://%zz"); err != nil || ok || id != 0 {
		t.Errorf("ymTrackID(unparseable) = %d,%v,%v", id, ok, err)
	}
}

// newYMFakeAPI starts a fake api.music.yandex.net serving /tracks/{id}
// with a minimal track payload. It asserts on every call that the token
// rides only in the Authorization header and never appears in a URL.
func newYMFakeAPI(t *testing.T) *httptest.Server {
	t.Helper()
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if got := req.Header.Get("Authorization"); got != "OAuth test-token" {
			http.Error(w, "missing oauth header", http.StatusUnauthorized)
			return
		}
		if strings.Contains(req.URL.String(), "test-token") {
			http.Error(w, "token in url", http.StatusBadRequest)
			return
		}
		if strings.HasPrefix(req.URL.Path, "/tracks/") {
			fmt.Fprint(w, `{"result":[{"id":"609676","title":"Never Gonna Give You Up","durationMs":212740,"artists":[{"name":"Rick Astley"}],"albums":[{"id":"14599266"}]}]}`)
			return
		}
		http.Error(w, "unexpected path "+req.URL.Path, http.StatusNotFound)
	}))
	t.Cleanup(ts.Close)
	return ts
}

// TestResolver_YMRealAdapter wires the real goym client (the production
// assembly: vantuz + Authorization header + GET /tracks/{id}) against a
// local fake API and resolves a share link end-to-end: field mapping, the
// token only in the Authorization header, and — across two resolves — a
// single cached fetcher. Requests to api.music.yandex.net are redirected to
// the fake server via a wrapping RoundTripper so no real traffic happens.
func TestResolver_YMRealAdapter(t *testing.T) {
	api := newYMFakeAPI(t)
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("og-meta path must not be hit for a yandex track link with a token")
	}))
	t.Cleanup(og.Close)
	v := &VKYandex{Config: Config{YMToken: "test-token"}}

	// The goym client is constructed lazily on the first resolve; after the
	// first ymFetcher call, wrap its transport to redirect api.music.yandex.net
	// at the fake API. The URL seen by the fake server keeps the real path.
	redirect := func() {
		f := v.ymFetcher()
		real, ok := f.(ymRealFetcher)
		if !ok {
			t.Fatalf("fetcher = %T, want ymRealFetcher", f)
		}
		apiClient := api.Client()
		real.cl.Http.SetClient(&http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			r.URL.Scheme = "http"
			r.URL.Host = api.URL[len("http://"):]
			return apiClient.Transport.RoundTrip(r)
		})})
	}
	redirect()

	resolve := func() *Track {
		t.Helper()
		tr, err := v.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676?utm_medium=copy_link")
		if err != nil {
			t.Fatalf("resolve: %v", err)
		}
		return tr
	}

	tr := resolve()
	if tr.Title != "Never Gonna Give You Up" || tr.Artist != "Rick Astley" || tr.DurationSec != 213 {
		t.Fatalf("track = %+v", tr)
	}
	if tr.ResolvedBy != "yandex" {
		t.Fatalf("resolvedBy = %q, want yandex", tr.ResolvedBy)
	}
	// Same cached fetcher: the redirect wrapper above is still in place.
	tr2 := resolve()
	if tr2.Title != tr.Title {
		t.Fatalf("second resolve title = %q, want %q", tr2.Title, tr.Title)
	}
	// The og-meta endpoint was never hit (asserted by its handler).
}

// roundTripFunc adapts a function into an http.RoundTripper.
type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

// TestResolver_YMFetcherCached verifies the fetcher is constructed once and
// reused across calls (a second ymFetcher call returns the identical value).
func TestResolver_YMFetcherCached(t *testing.T) {
	v := &VKYandex{Config: Config{YMToken: "test-token"}}
	f1 := v.ymFetcher()
	f2 := v.ymFetcher()
	if f1 != f2 {
		t.Fatal("ymFetcher did not return the same fetcher")
	}
	if _, ok := f1.(ymRealFetcher); !ok {
		t.Fatalf("fetcher = %T, want ymRealFetcher (no network was made, construction is offline)", f1)
	}
}

// TestResolver_YMFetcherInitFailed verifies an error from the fetcher maps
// to ErrService with a fixed message: transport errors embed the request URL
// (and could embed anything), so only the fixed text is surfaced — no token,
// no URL. The fetcher cache is pre-seeded with a failing stub.
func TestResolver_YMFetcherInitFailed(t *testing.T) {
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("og-meta path must not be hit for a track link with a token")
	}))
	defer og.Close()
	v := &VKYandex{Client: og.Client(), Endpoint: og.URL, Config: Config{YMToken: "test-token"}}
	// Pre-seed a fetcher whose track call fails with a transport-style error
	// containing a URL; the mapping must not leak it.
	v.ymClient = failingInitFetcher{}
	_, err := v.Resolve(context.Background(), "https://music.yandex.ru/track/609676")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	msg := err.Error()
	if strings.Contains(msg, "test-token") || strings.Contains(msg, "yandex.net") || strings.Contains(msg, "music.yandex") {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
}

// failingInitFetcher simulates a cached fetcher whose track call fails with a
// transport-style error containing a URL; resolveViaYM must not leak it.
type failingInitFetcher struct{}

func (failingInitFetcher) track(context.Context, int64) (ymTrackInfo, error) {
	return ymTrackInfo{}, errors.New(`Get "https://api.music.yandex.net/tracks/1": connection refused`)
}

// TestResolver_YMTransportErrorNoLeak double-checks a transport error from
// the fetcher carries no token or URL in the mapped message.
func TestResolver_YMTransportErrorNoLeak(t *testing.T) {
	m := &ymMockFetcher{err: errors.New(`Get "https://api.music.yandex.net/tracks/609676": connection refused — token=test-token`)}
	s := newYMResolver(t, m)
	_, err := s.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	msg := err.Error()
	if strings.Contains(msg, "test-token") || strings.Contains(msg, "yandex.net") || strings.Contains(msg, "music.yandex") {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
}
