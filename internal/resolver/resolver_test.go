package resolver

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// fakeRunner feeds canned yt-dlp JSON to the YouTube resolver.
type fakeRunner struct {
	out []byte
	err error
}

func (f fakeRunner) Run(_ context.Context, _ string) ([]byte, error) {
	return f.out, f.err
}

type blockingYouTubeRunner struct {
	stopped chan struct{}
}

func (r blockingYouTubeRunner) Run(ctx context.Context, _ string) ([]byte, error) {
	<-ctx.Done()
	close(r.stopped)
	return nil, ctx.Err()
}

// TestResolver_SpotifyOEmbed checks the anonymous Spotify path: title is
// parsed, artist comes from author_name when present, duration stays 0.
func TestResolver_SpotifyOEmbed(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if q := r.URL.Query().Get("url"); q != "https://open.spotify.com/track/abc" {
			http.Error(w, "unexpected url", http.StatusBadRequest)
			return
		}
		w.Write([]byte(`{"title":"Some Song","author_name":"Some Artist","provider_name":"Spotify"}`))
	}))
	defer ts.Close()

	s := &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Some Song" || tr.Artist != "Some Artist" {
		t.Fatalf("track = %+v", tr)
	}
	if tr.DurationSec != 0 {
		t.Fatalf("duration = %d, want 0 (oEmbed has none)", tr.DurationSec)
	}
	if tr.ResolvedBy != "spotify" || tr.Source != "https://open.spotify.com/track/abc" {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_SpotifyNoAuthor verifies author_name is optional (best effort).
func TestResolver_SpotifyNoAuthor(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"Instrumental"}`))
	}))
	defer ts.Close()
	s := &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/x")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Artist != "" {
		t.Fatalf("artist = %q, want empty", tr.Artist)
	}
}

// TestResolver_SpotifyNonOK verifies a non-200 upstream becomes ErrService.
func TestResolver_SpotifyNonOK(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusServiceUnavailable)
	}))
	defer ts.Close()
	s := &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/x")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyEmptyTitle verifies the empty-title failure.
func TestResolver_SpotifyEmptyTitle(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"author_name":"A"}`))
	}))
	defer ts.Close()
	s := &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/x")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyBadJSON covers a malformed upstream body.
func TestResolver_SpotifyBadJSON(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`not json`))
	}))
	defer ts.Close()
	s := &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/x")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyRequestError covers transport failure (unreachable host).
func TestResolver_SpotifyRequestError(t *testing.T) {
	s := &Spotify{Endpoint: "http://127.0.0.1:1/oembed"} // closed port
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/x")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

const ytFixture = `{
  "title": "Never Gonna Give You Up",
  "uploader": "Rick Astley",
  "duration": 213,
  "channel": "Rick AstleyVEVO"
}`

// TestResolver_YouTube parses yt-dlp fixture JSON.
func TestResolver_YouTube(t *testing.T) {
	y := &YouTube{Runner: fakeRunner{out: []byte(ytFixture)}}
	tr, err := y.Resolve(context.Background(), "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Never Gonna Give You Up" || tr.Artist != "Rick Astley" || tr.DurationSec != 213 {
		t.Fatalf("track = %+v", tr)
	}
	if tr.ResolvedBy != "youtube" {
		t.Fatalf("resolvedBy = %q, want youtube", tr.ResolvedBy)
	}
}

// TestResolver_YouTubePrefersEmbeddedSongMetadata verifies artist/track win
// over uploader when present.
func TestResolver_YouTubePrefersEmbeddedSongMetadata(t *testing.T) {
	out := `{"title":"T","artist":"Embedded Artist","track":"E","uploader":"Uploader","duration":10}`
	y := &YouTube{Runner: fakeRunner{out: []byte(out)}}
	tr, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Artist != "Embedded Artist" {
		t.Fatalf("artist = %q, want Embedded Artist", tr.Artist)
	}
}

// TestResolver_YouTubeChannelFallback uses channel when no uploader.
func TestResolver_YouTubeChannelFallback(t *testing.T) {
	out := `{"title":"T","channel":"SomeChannel","duration":5}`
	y := &YouTube{Runner: fakeRunner{out: []byte(out)}}
	tr, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Artist != "SomeChannel" {
		t.Fatalf("artist = %q, want SomeChannel", tr.Artist)
	}
}

// TestResolver_YouTubeRunnerError maps exec failure to ErrService.
func TestResolver_YouTubeRunnerError(t *testing.T) {
	wantErr := errors.New("exec failed")
	y := &YouTube{Runner: fakeRunner{err: wantErr}}
	_, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if !errors.Is(err, ErrService) || !errors.Is(err, wantErr) {
		t.Fatalf("err = %v, want ErrService and wrapped runner cause", err)
	}
}

// TestResolver_YouTubeDeadline bounds a runner even when the request context
// itself has no deadline (qmix#116).
func TestResolver_YouTubeDeadline(t *testing.T) {
	stopped := make(chan struct{})
	y := &YouTube{
		Runner:  blockingYouTubeRunner{stopped: stopped},
		Timeout: time.Millisecond,
	}
	_, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if !errors.Is(err, ErrService) || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want ErrService and context.DeadlineExceeded", err)
	}
	select {
	case <-stopped:
	default:
		t.Fatal("runner still active after deadline")
	}
}

// TestResolver_YouTubeEmptyOutput covers empty stdout.
func TestResolver_YouTubeEmptyOutput(t *testing.T) {
	y := &YouTube{Runner: fakeRunner{}}
	_, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_YouTubeBadJSON covers unparseable metadata.
func TestResolver_YouTubeBadJSON(t *testing.T) {
	y := &YouTube{Runner: fakeRunner{out: []byte("garbage")}}
	_, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_YouTubeEmptyTitle covers metadata without a title.
func TestResolver_YouTubeEmptyTitle(t *testing.T) {
	y := &YouTube{Runner: fakeRunner{out: []byte(`{"uploader":"X"}`)}}
	_, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_YouTubeDefaultRunner verifies the default runner is an exec
// runner (zero value) and the name default.
func TestResolver_YouTubeDefaultRunner(t *testing.T) {
	y := &YouTube{}
	if r, ok := y.runner().(ytdlpRunner); !ok {
		t.Fatalf("default runner = %T, want ytdlpRunner", y.runner())
	} else if r.bin != ytdlpBin {
		t.Fatalf("bin = %q, want %q", r.bin, ytdlpBin)
	}
	if y.name() != "youtube" {
		t.Fatalf("name = %q, want youtube", y.name())
	}
	if y.timeout() != ytdlpMetadataTimeout {
		t.Fatalf("timeout = %v, want %v", y.timeout(), ytdlpMetadataTimeout)
	}
}

// TestResolver_YouTubeExecRunnerKillsTimedOutProcess proves the production
// CommandContext path waits for a blocked subprocess to be terminated.
func TestResolver_YouTubeExecRunnerKillsTimedOutProcess(t *testing.T) {
	dir := t.TempDir()
	pidFile := filepath.Join(dir, "pid")
	bin := filepath.Join(dir, "yt-dlp")
	script := "#!/bin/sh\necho $$ > " + pidFile + "\nwhile :; do :; done\n"
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	runDone := make(chan error, 1)
	go func() {
		_, err := (ytdlpRunner{bin: bin}).Run(ctx, "https://youtu.be/abc")
		runDone <- err
	}()

	watchdog := time.NewTimer(5 * time.Second)
	defer watchdog.Stop()
	poll := time.NewTicker(time.Millisecond)
	defer poll.Stop()

	var rawPID []byte
	for len(strings.TrimSpace(string(rawPID))) == 0 {
		var err error
		rawPID, err = os.ReadFile(pidFile)
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			t.Fatal(err)
		}
		select {
		case err := <-runDone:
			t.Fatalf("subprocess exited before writing its PID: %v", err)
		case <-watchdog.C:
			t.Fatal("timed out waiting for subprocess startup")
		case <-poll.C:
		}
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(rawPID)))
	if err != nil {
		t.Fatal(err)
	}

	cancel()
	select {
	case err := <-runDone:
		if err == nil {
			t.Fatal("expected canceled subprocess error")
		}
	case <-watchdog.C:
		t.Fatal("timed out waiting for canceled subprocess")
	}
	if err := syscall.Kill(pid, 0); !errors.Is(err, syscall.ESRCH) {
		t.Fatalf("process %d remains after cancellation: %v", pid, err)
	}
}

// TestResolver_YouTubeCustomName covers a custom resolvedBy label.
func TestResolver_YouTubeCustomName(t *testing.T) {
	y := &YouTube{Runner: fakeRunner{out: []byte(ytFixture)}, Name: "yt"}
	tr, err := y.Resolve(context.Background(), "https://youtu.be/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.ResolvedBy != "yt" {
		t.Fatalf("resolvedBy = %q, want yt", tr.ResolvedBy)
	}
}

// TestResolver_VKYandexOGTitle resolves a page that exposes og:title.
func TestResolver_VKYandexOGTitle(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><head><meta property="og:title" content="My Track"></head></html>`))
	}))
	defer ts.Close()
	v := &VKYandex{Client: ts.Client(), Endpoint: ts.URL}
	tr, err := v.Resolve(context.Background(), "https://vk.com/audio123_456")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "My Track" {
		t.Fatalf("title = %q, want My Track", tr.Title)
	}
	if tr.ResolvedBy != "vkyandex" {
		t.Fatalf("resolvedBy = %q, want vkyandex", tr.ResolvedBy)
	}
}

// TestResolver_VKYandexNoOGTitle maps a page without og:title to ErrNoAnonymous.
func TestResolver_VKYandexNoOGTitle(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><head><title>login wall</title></head></html>`))
	}))
	defer ts.Close()
	v := &VKYandex{Client: ts.Client(), Endpoint: ts.URL}
	_, err := v.Resolve(context.Background(), "https://vk.com/audio123_456")
	if !errors.Is(err, ErrNoAnonymous) {
		t.Fatalf("err = %v, want ErrNoAnonymous", err)
	}
}

// TestResolver_VKYandexYandex verifies the yandex domain route (no anon path in
// practice -> ErrNoAnonymous for a wall page).
func TestResolver_VKYandexYandex(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><body>needs auth</body></html>`))
	}))
	defer ts.Close()
	v := &VKYandex{Client: ts.Client(), Endpoint: ts.URL}
	_, err := v.Resolve(context.Background(), "https://music.yandex.ru/album/1/track/2")
	if !errors.Is(err, ErrNoAnonymous) {
		t.Fatalf("err = %v, want ErrNoAnonymous", err)
	}
}

// TestResolver_VKYandexUnsupportedService covers a domain this resolver cannot
// serve.
func TestResolver_VKYandexUnsupportedService(t *testing.T) {
	v := &VKYandex{}
	_, err := v.Resolve(context.Background(), "https://example.com/x")
	if !errors.Is(err, ErrUnsupported) {
		t.Fatalf("err = %v, want ErrUnsupported", err)
	}
}

// TestResolver_VKYandexRequestError covers transport failure.
func TestResolver_VKYandexRequestError(t *testing.T) {
	v := &VKYandex{Endpoint: "http://127.0.0.1:1/x"}
	_, err := v.Resolve(context.Background(), "https://vk.com/audio1")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_MuxSpotifyRouting verifies dispatch by domain.
func TestResolver_MuxSpotifyRouting(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"Routed","author_name":"A"}`))
	}))
	defer ts.Close()
	mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}})
	tr, err := mux.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Routed" {
		t.Fatalf("title = %q, want Routed", tr.Title)
	}
}

// TestResolver_MuxYouTubeRouting verifies dispatch to YouTube.
func TestResolver_MuxYouTubeRouting(t *testing.T) {
	mux := NewMux(Matcher{Domains: []string{"youtube.com", "youtu.be"}, Resolver: &YouTube{Runner: fakeRunner{out: []byte(ytFixture)}}})
	tr, err := mux.Resolve(context.Background(), "https://youtu.be/dQw4w9WgXcQ")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.DurationSec != 213 {
		t.Fatalf("duration = %d, want 213", tr.DurationSec)
	}
}

// TestResolver_MuxUnknownDomain verifies an unmatched domain -> ErrUnsupported.
func TestResolver_MuxUnknownDomain(t *testing.T) {
	mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: &Spotify{}})
	_, err := mux.Resolve(context.Background(), "https://example.com/song")
	if !errors.Is(err, ErrUnsupported) {
		t.Fatalf("err = %v, want ErrUnsupported", err)
	}
}

// TestResolver_MuxInvalidURL verifies malformed/unempty hosts -> ErrInvalid.
func TestResolver_MuxInvalidURL(t *testing.T) {
	mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: &Spotify{}})
	for _, u := range []string{"", "not a url", "javascript:alert(1)", "  "} {
		_, err := mux.Resolve(context.Background(), u)
		if !errors.Is(err, ErrInvalid) {
			t.Fatalf("url %q: err = %v, want ErrInvalid", u, err)
		}
	}
}

// TestResolver_MuxRoutingCaseInsensitive verifies host matching ignores case.
func TestResolver_MuxRoutingCaseInsensitive(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"CI"}`))
	}))
	defer ts.Close()
	mux := NewMux(Matcher{Domains: []string{"Spotify.com"}, Resolver: &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/oembed"}})
	tr, err := mux.Resolve(context.Background(), "http://open.spotify.com/track/x")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "CI" {
		t.Fatalf("title = %q, want CI", tr.Title)
	}
}

// TestResolver_DefaultMux wires all production resolvers without network access
// in this test (dispatch only; upstream calls are exercised elsewhere).
func TestResolver_DefaultMux(t *testing.T) {
	m := DefaultMux()
	if m == nil {
		t.Fatal("DefaultMux returned nil")
	}
	if len(m.matchers) != 3 {
		t.Fatalf("DefaultMux matchers = %d, want 3", len(m.matchers))
	}
}

// TestResolver_DomainOf covers the helper.
func TestResolver_DomainOf(t *testing.T) {
	if h, err := DomainOf("https://VK.com/a"); err != nil || h != "vk.com" {
		t.Fatalf("domain = %q err = %v", h, err)
	}
	if _, err := DomainOf(""); !errors.Is(err, ErrInvalid) {
		t.Fatalf("err = %v, want ErrInvalid", err)
	}
}

// TestResolver_SpotifyCustomNameAndDefaults covers default client/endpoint/name.
func TestResolver_SpotifyCustomNameAndDefaults(t *testing.T) {
	s := &Spotify{Name: "sp"}
	if s.client() != defaultClient {
		t.Fatal("default client should be defaultClient (bounded by httpTimeout, qmix#40)")
	}
	if s.endpoint() != spotifyOEmbedEndpoint {
		t.Fatalf("endpoint = %q", s.endpoint())
	}
	if s.name() != "sp" {
		t.Fatalf("name = %q, want sp", s.name())
	}
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"T"}`))
	}))
	defer ts.Close()
	s2 := &Spotify{Client: ts.Client(), Endpoint: ts.URL + "/o", Name: "sp"}
	tr, err := s2.Resolve(context.Background(), "https://open.spotify.com/track/x")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.ResolvedBy != "sp" {
		t.Fatalf("resolvedBy = %q, want sp", tr.ResolvedBy)
	}
}

// TestResolver_VKYandexClientDefault verifies the default http client.
func TestResolver_VKYandexClientDefault(t *testing.T) {
	if (&VKYandex{}).client() != defaultClient {
		t.Fatal("default client should be defaultClient (bounded by httpTimeout, qmix#40)")
	}
}

// TestResolver_ErrorMessages human-readable and mention the service.
func TestResolver_ErrorMessages(t *testing.T) {
	v := &VKYandex{}
	_, err := v.Resolve(context.Background(), "https://vk.com/audio1")
	if !strings.Contains(err.Error(), "vk") {
		t.Fatalf("err = %q, want mention of vk", err)
	}
}

// TestResolver_DefaultClientHasTimeout pins the audit fix (qmix#40): the
// resolver fallback clients must not be http.DefaultClient and must carry a
// whole-request timeout, so a hung upstream releases the request.
func TestResolver_DefaultClientHasTimeout(t *testing.T) {
	cases := []struct {
		name   string
		client func() *http.Client
	}{
		{"spotify", (&Spotify{}).client},
		{"vkyandex", (&VKYandex{}).client},
	}
	for _, tc := range cases {
		c := tc.client()
		if c == http.DefaultClient {
			t.Fatalf("%s: fallback client is http.DefaultClient", tc.name)
		}
		if c.Timeout != httpTimeout {
			t.Fatalf("%s: Timeout = %v, want %v", tc.name, c.Timeout, httpTimeout)
		}
	}
}
