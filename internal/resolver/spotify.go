package resolver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// spotifyOEmbedEndpoint is the anonymous oEmbed endpoint. A track link is
// passed via the "url" query parameter; no credentials are required.
const spotifyOEmbedEndpoint = "https://open.spotify.com/oembed"

// spotifyDefaultTokenURL is the Client Credentials token endpoint (#8).
const spotifyDefaultTokenURL = "https://accounts.spotify.com/api/token"

// spotifyDefaultAPIBase is the root of the Spotify Web API (#8).
const spotifyDefaultAPIBase = "https://api.spotify.com"

// Spotify resolves Spotify links. Without credentials it uses the public
// oEmbed API: title and, when present, author_name as the artist; no duration.
// With Client Credentials in Config it resolves track links via the Web API:
// title, full artist list and duration (#8). Non-track links (album, playlist)
// and unparseable URLs always fall back to oEmbed.
type Spotify struct {
	// Client is used for all HTTP requests. Tests inject a fake server client.
	Client *http.Client
	// Endpoint overrides the oEmbed endpoint (tests point this at a mock).
	Endpoint string
	// Name is the value reported in Track.ResolvedBy. Defaults to "spotify".
	Name string
	// Config carries service credentials for the authenticated path (#8). Empty
	// values keep the anonymous oEmbed behaviour.
	Config Config
	// TokenURL overrides the Client Credentials token endpoint (tests).
	TokenURL string
	// APIBase overrides the Web API root (tests).
	APIBase string

	// tokenMu guards the token cache below; the server resolves concurrently.
	tokenMu sync.Mutex
	// accessToken is the cached app-level bearer token, if any.
	accessToken string
	// expiresAt is when accessToken stops being valid.
	expiresAt time.Time
}

func (s *Spotify) client() *http.Client {
	if s.Client != nil {
		return s.Client
	}
	return defaultClient
}

func (s *Spotify) endpoint() string {
	if s.Endpoint != "" {
		return s.Endpoint
	}
	return spotifyOEmbedEndpoint
}

func (s *Spotify) tokenURL() string {
	if s.TokenURL != "" {
		return s.TokenURL
	}
	return spotifyDefaultTokenURL
}

func (s *Spotify) apiBase() string {
	if s.APIBase != "" {
		return s.APIBase
	}
	return spotifyDefaultAPIBase
}

func (s *Spotify) name() string {
	if s.Name != "" {
		return s.Name
	}
	return "spotify"
}

// oembedResponse is the subset of the oEmbed payload Spotify returns.
type oembedResponse struct {
	Title      string `json:"title"`
	AuthorName string `json:"author_name"`
	Provider   string `json:"provider_name"`
}

// tokenResponse is the subset of the token endpoint payload (#8).
type tokenResponse struct {
	AccessToken string `json:"access_token"`
	ExpiresIn   int64  `json:"expires_in"`
}

// apiTrack is the subset of the GET /v1/tracks/{id} payload (#8).
type apiTrack struct {
	Name       string `json:"name"`
	DurationMs int64  `json:"duration_ms"`
	Artists    []struct {
		Name string `json:"name"`
	} `json:"artists"`
}

// trackID extracts the track id from a Spotify link. It returns ok=false for
// album/playlist links and anything that is not a /track/{id} path (including
// locale prefixes like /intl-ru/); callers fall back to oEmbed.
func trackID(rawurl string) (string, bool) {
	u, err := url.Parse(strings.TrimSpace(rawurl))
	if err != nil || u.Host == "" {
		return "", false
	}
	segments := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(segments) < 2 || segments[len(segments)-2] != "track" {
		return "", false
	}
	id := segments[len(segments)-1]
	if id == "" {
		return "", false
	}
	return id, true
}

// hasCredentials reports whether both Client Credentials values are set (#8).
func (s *Spotify) hasCredentials() bool {
	return s.Config.SpotifyClientID != "" && s.Config.SpotifyClientSecret != ""
}

// Resolve returns metadata for url: the Web API for track links when Client
// Credentials are configured (#8), oEmbed otherwise. Unparseable or non-track
// URLs never fail fast here — they go to oEmbed (Mux already reports
// ErrInvalid for garbage).
func (s *Spotify) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	if s.hasCredentials() {
		if id, ok := trackID(rawurl); ok {
			return s.resolveViaAPI(ctx, id, rawurl)
		}
	}
	return s.resolveViaOEmbed(ctx, rawurl)
}

// resolveViaOEmbed fetches oEmbed metadata for url (the M2 path).
func (s *Spotify) resolveViaOEmbed(ctx context.Context, rawurl string) (*Track, error) {
	u := s.endpoint() + "?url=" + url.QueryEscape(rawurl) + "&format=json"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %w", ErrService, err)
	}
	req.Header.Set("User-Agent", "QMix resolver/1.0")
	resp, err := s.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %w", ErrService, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("spotify oembed: %w: status %d", ErrService, resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %w", ErrService, err)
	}
	var oe oembedResponse
	if err := json.Unmarshal(body, &oe); err != nil {
		return nil, fmt.Errorf("spotify oembed: %w: %w", ErrService, err)
	}
	if oe.Title == "" {
		return nil, errors.Join(ErrService, errors.New("spotify oembed returned empty title"))
	}
	return &Track{
		Title:       oe.Title,
		Artist:      oe.AuthorName,
		DurationSec: 0, // oEmbed does not expose duration
		Source:      rawurl,
		ResolvedBy:  s.name(),
	}, nil
}

// token returns a cached app-level bearer token, fetching a fresh one via
// Client Credentials when the cache is empty or expired (#8). Credentials and
// tokens never appear in returned errors.
func (s *Spotify) token(ctx context.Context) (string, error) {
	s.tokenMu.Lock()
	defer s.tokenMu.Unlock()
	if s.accessToken != "" && time.Now().Before(s.expiresAt) {
		return s.accessToken, nil
	}
	form := url.Values{"grant_type": {"client_credentials"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.tokenURL(), strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("spotify token: %w: %w", ErrService, err)
	}
	req.SetBasicAuth(s.Config.SpotifyClientID, s.Config.SpotifyClientSecret)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := s.client().Do(req)
	if err != nil {
		return "", fmt.Errorf("spotify token: %w: %w", ErrService, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("spotify token: %w: status %d", ErrService, resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("spotify token: %w: %w", ErrService, err)
	}
	var tr tokenResponse
	if err := json.Unmarshal(body, &tr); err != nil {
		return "", fmt.Errorf("spotify token: %w: %w", ErrService, err)
	}
	if tr.AccessToken == "" {
		return "", errors.Join(ErrService, errors.New("spotify token endpoint returned empty access_token"))
	}
	s.accessToken = tr.AccessToken
	s.expiresAt = time.Now().Add(time.Duration(tr.ExpiresIn) * time.Second)
	return s.accessToken, nil
}

// resolveViaAPI fetches full track metadata from the Web API (#8).
func (s *Spotify) resolveViaAPI(ctx context.Context, id, rawurl string) (*Track, error) {
	tok, err := s.token(ctx)
	if err != nil {
		return nil, err
	}
	u := s.apiBase() + "/v1/tracks/" + url.PathEscape(id)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %w", ErrService, err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := s.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("spotify api: %w: %w", ErrService, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("spotify api: %w: status %d", ErrService, resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("spotify api: %w: %w", ErrService, err)
	}
	var at apiTrack
	if err := json.Unmarshal(body, &at); err != nil {
		return nil, fmt.Errorf("spotify api: %w: %w", ErrService, err)
	}
	if at.Name == "" {
		return nil, errors.Join(ErrService, errors.New("spotify api returned empty name"))
	}
	artists := make([]string, 0, len(at.Artists))
	for _, a := range at.Artists {
		artists = append(artists, a.Name)
	}
	return &Track{
		Title:       at.Name,
		Artist:      strings.Join(artists, ", "),
		DurationSec: int((at.DurationMs + 500) / 1000),
		Source:      rawurl,
		ResolvedBy:  s.name(),
	}, nil
}
