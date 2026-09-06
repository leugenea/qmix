package resolver

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// spotifyAPITestServer builds an httptest server pair for the authenticated
// path: a token endpoint and a tracks endpoint, both injectable into Spotify.
// tokenHits and apiHits count requests; the caller decides responses via the
// returned setters.
type spotifyTestRig struct {
	ts        *httptest.Server // serves both /token and /v1/tracks/...
	tokenHits atomic.Int64
	apiHits   atomic.Int64

	tokenStatus int
	tokenBody   string
	apiStatus   int
	apiBody     string
}

func newSpotifyTestRig() *spotifyTestRig {
	r := &spotifyTestRig{tokenStatus: 200, tokenBody: `{"access_token":"tok1","expires_in":3600}`, apiStatus: 200}
	r.apiBody = `{"name":"Never Gonna Give You Up","duration_ms":212500,"artists":[{"name":"Rick Astley"}]}`
	r.ts = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		switch req.URL.Path {
		case "/token":
			r.tokenHits.Add(1)
			if err := req.ParseForm(); err != nil || req.PostForm.Get("grant_type") != "client_credentials" {
				http.Error(w, "bad form", http.StatusBadRequest)
				return
			}
			w.WriteHeader(r.tokenStatus)
			w.Write([]byte(r.tokenBody))
		case "/v1/tracks/abc":
			r.apiHits.Add(1)
			if got := req.Header.Get("Authorization"); got != "Bearer tok1" {
				http.Error(w, "missing bearer", http.StatusUnauthorized)
				return
			}
			w.WriteHeader(r.apiStatus)
			w.Write([]byte(r.apiBody))
		default:
			http.Error(w, "unexpected path", http.StatusNotFound)
		}
	}))
	return r
}

func (r *spotifyTestRig) resolver() *Spotify {
	return &Spotify{
		Client:   r.ts.Client(),
		TokenURL: r.ts.URL + "/token",
		APIBase:  r.ts.URL,
		Config:   Config{SpotifyClientID: "test-id", SpotifyClientSecret: "test-secret"},
	}
}

// TestResolver_SpotifyAPISuccess verifies the full Client Credentials path:
// token fetched with Basic auth, track fetched with Bearer, fields mapped.
func TestResolver_SpotifyAPISuccess(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		switch req.URL.Path {
		case "/token":
			user, pass, ok := req.BasicAuth()
			if !ok || user != "test-id" || pass != "test-secret" {
				http.Error(w, "bad basic auth", http.StatusUnauthorized)
				return
			}
			if err := req.ParseForm(); err != nil || req.PostForm.Get("grant_type") != "client_credentials" {
				http.Error(w, "bad form", http.StatusBadRequest)
				return
			}
			w.Write([]byte(`{"access_token":"tok1","expires_in":3600}`))
		case "/v1/tracks/abc":
			if req.Header.Get("Authorization") != "Bearer tok1" {
				http.Error(w, "missing bearer", http.StatusUnauthorized)
				return
			}
			w.Write([]byte(`{"name":"Never Gonna Give You Up","duration_ms":212500,"artists":[{"name":"Rick Astley"},{"name":"Ft"}]}`))
		default:
			http.Error(w, "unexpected path", http.StatusNotFound)
		}
	}))
	defer ts.Close()

	s := &Spotify{
		Client:   ts.Client(),
		TokenURL: ts.URL + "/token",
		APIBase:  ts.URL,
		Config:   Config{SpotifyClientID: "test-id", SpotifyClientSecret: "test-secret"},
	}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Never Gonna Give You Up" {
		t.Fatalf("title = %q", tr.Title)
	}
	if tr.Artist != "Rick Astley, Ft" {
		t.Fatalf("artist = %q, want comma-joined list", tr.Artist)
	}
	if tr.DurationSec != 213 {
		t.Fatalf("duration = %d, want 213 (rounding)", tr.DurationSec)
	}
	if tr.ResolvedBy != "spotify" || tr.Source != "https://open.spotify.com/track/abc" {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_SpotifyAPITokenCached verifies a second resolve reuses the
// cached token: the token endpoint is hit exactly once.
func TestResolver_SpotifyAPITokenCached(t *testing.T) {
	rig := newSpotifyTestRig()
	defer rig.ts.Close()
	s := rig.resolver()
	for i := 0; i < 2; i++ {
		tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
		if err != nil {
			t.Fatalf("resolve %d: %v", i, err)
		}
		if tr.Title == "" {
			t.Fatal("empty title")
		}
	}
	if n := rig.tokenHits.Load(); n != 1 {
		t.Fatalf("token endpoint hits = %d, want 1 (cache)", n)
	}
	if n := rig.apiHits.Load(); n != 2 {
		t.Fatalf("api hits = %d, want 2", n)
	}
}

// TestResolver_SpotifyTokenExpiredRefetches verifies an expired token
// (expires_in <= 0) triggers a fresh token request on the next resolve.
func TestResolver_SpotifyTokenExpiredRefetches(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.tokenBody = `{"access_token":"tok1","expires_in":0}`
	defer rig.ts.Close()
	s := rig.resolver()
	for i := 0; i < 2; i++ {
		if _, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc"); err != nil {
			t.Fatalf("resolve %d: %v", i, err)
		}
	}
	if n := rig.tokenHits.Load(); n != 2 {
		t.Fatalf("token endpoint hits = %d, want 2 (expired -> refetch)", n)
	}
}

// TestResolver_SpotifyToken4xx maps a token-endpoint failure to ErrService.
func TestResolver_SpotifyToken4xx(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.tokenStatus = http.StatusBadRequest
	rig.tokenBody = `{"error":"invalid_client"}`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyTokenBadJSON covers a malformed token payload.
func TestResolver_SpotifyTokenBadJSON(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.tokenBody = `not json`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyTokenEmpty covers a token response without access_token.
func TestResolver_SpotifyTokenEmpty(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.tokenBody = `{}`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyTokenTransportError covers an unreachable token endpoint.
func TestResolver_SpotifyTokenTransportError(t *testing.T) {
	s := &Spotify{
		TokenURL: "http://127.0.0.1:1/token", // closed port
		Config:   Config{SpotifyClientID: "id", SpotifyClientSecret: "secret"},
	}
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyAPI401 maps an API auth failure to ErrService.
func TestResolver_SpotifyAPI401(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.apiStatus = http.StatusUnauthorized
	rig.apiBody = `{"error":{"message":"Invalid access token"}}`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyAPIBadJSON covers a malformed track payload.
func TestResolver_SpotifyAPIBadJSON(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.apiBody = `not json`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyAPIEmptyName covers a track without a name.
func TestResolver_SpotifyAPIEmptyName(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.apiBody = `{"duration_ms":1000,"artists":[]}`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyAPITransportError covers an unreachable API endpoint.
func TestResolver_SpotifyAPITransportError(t *testing.T) {
	rig := newSpotifyTestRig()
	defer rig.ts.Close()
	s := rig.resolver()
	// Valid token from the rig, then an API base pointing at a closed port.
	s.APIBase = "http://127.0.0.1:1"
	if _, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc"); !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_SpotifyAlbumFallsBackToOEmbed verifies album links take the
// oEmbed path even with credentials configured.
func TestResolver_SpotifyAlbumFallsBackToOEmbed(t *testing.T) {
	token := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("token endpoint must not be hit for album links")
	}))
	defer token.Close()
	oembed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"Album Title","author_name":"Someone"}`))
	}))
	defer oembed.Close()

	s := &Spotify{
		Client:   token.Client(),
		TokenURL: token.URL,
		APIBase:  token.URL,
		Endpoint: oembed.URL,
		Config:   Config{SpotifyClientID: "id", SpotifyClientSecret: "secret"},
	}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/album/xyz?si=abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Album Title" || tr.DurationSec != 0 {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_SpotifyIntlTrackUsesAPI verifies locale-prefixed track links
// (e.g. /intl-ru/) still take the API path.
func TestResolver_SpotifyIntlTrackUsesAPI(t *testing.T) {
	rig := newSpotifyTestRig()
	defer rig.ts.Close()
	s := rig.resolver()
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/intl-ru/track/abc?si=xyz")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Never Gonna Give You Up" || tr.DurationSec != 213 {
		t.Fatalf("track = %+v", tr)
	}
	if n := rig.tokenHits.Load(); n != 1 {
		t.Fatalf("token hits = %d, want 1", n)
	}
}

// TestResolver_SpotifyOnlyClientID verifies that a partial credential set
// (Client ID without secret) keeps the anonymous oEmbed behaviour.
func TestResolver_SpotifyOnlyClientID(t *testing.T) {
	token := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("token endpoint must not be hit without full credentials")
	}))
	defer token.Close()
	oembed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"Anon"}`))
	}))
	defer oembed.Close()

	s := &Spotify{
		Client:   token.Client(),
		TokenURL: token.URL,
		Endpoint: oembed.URL,
		Config:   Config{SpotifyClientID: "id-only"},
	}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Anon" || tr.DurationSec != 0 {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_SpotifyCredsWithOEmbedURL verifies the oEmbed path is still
// used with credentials present for a link the track parser rejects.
func TestResolver_SpotifyInvalidURLFallsBackToOEmbed(t *testing.T) {
	token := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("token endpoint must not be hit for unparseable urls")
	}))
	defer token.Close()
	oembed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"Whatever"}`))
	}))
	defer oembed.Close()

	s := &Spotify{
		Client:   token.Client(),
		TokenURL: token.URL,
		Endpoint: oembed.URL,
		Config:   Config{SpotifyClientID: "id", SpotifyClientSecret: "secret"},
	}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/garbage")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Whatever" {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_SpotifyCredsOEmbedStillWorked ensures the plain oEmbed path
// with credentials configured and a non-track URL keeps M2 behaviour when the
// config is entirely empty (the production default).
func TestResolver_SpotifyCredsOEmbedStillWorked(t *testing.T) {
	oembed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"title":"No Creds","author_name":"Anon Artist"}`))
	}))
	defer oembed.Close()

	s := &Spotify{Client: oembed.Client(), Endpoint: oembed.URL, Config: Config{}}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "No Creds" || tr.Artist != "Anon Artist" || tr.DurationSec != 0 {
		t.Fatalf("track = %+v", tr)
	}
}

// TestSpotifyTrackID covers the track-id parser: ids, locale prefixes,
// trailing junk, non-track paths, and garbage.
func TestSpotifyTrackID(t *testing.T) {
	valid := map[string]string{
		"https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC":                "4uLU6hMCjMI75M1A2tKUQC",
		"https://open.spotify.com/intl-ru/track/4uLU6hMCjMI75M1A2tKUQC?si=abc": "4uLU6hMCjMI75M1A2tKUQC",
		"http://open.spotify.com/track/x/":                                     "x",
		"https://spotify.com/track/abc":                                        "abc",
	}
	for in, want := range valid {
		id, ok := trackID(in)
		if !ok || id != want {
			t.Errorf("trackID(%q) = %q,%v want %q,true", in, id, ok, want)
		}
	}
	invalid := []string{
		"",
		"not a url",
		"https://open.spotify.com/",
		"https://open.spotify.com/album/xyz",
		"https://open.spotify.com/playlist/xyz",
		"https://open.spotify.com/track/",
		"https://open.spotify.com/track",
		"javascript:alert(1)",
	}
	for _, in := range invalid {
		if id, ok := trackID(in); ok {
			t.Errorf("trackID(%q) = %q,true, want not ok", in, id)
		}
	}
}

// TestSpotifyErrorsHideCredentials asserts credentials never leak into error
// text from the token and API paths.
func TestSpotifyErrorsHideCredentials(t *testing.T) {
	rig := newSpotifyTestRig()
	rig.tokenStatus = http.StatusInternalServerError
	rig.tokenBody = `{"error":"server_error"}`
	defer rig.ts.Close()
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err == nil {
		t.Fatal("expected error")
	}
	msg := err.Error()
	if strings.Contains(msg, "test-id") || strings.Contains(msg, "test-secret") {
		t.Fatalf("error leaks credentials: %q", msg)
	}

	rig.tokenStatus = 200
	rig.tokenBody = `{"access_token":"tok1","expires_in":3600}`
	rig.apiStatus = http.StatusForbidden
	s2 := rig.resolver()
	_, err = s2.Resolve(context.Background(), "https://open.spotify.com/track/abc")
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), "tok1") {
		t.Fatalf("error leaks token: %q", err.Error())
	}
}
