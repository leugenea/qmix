package resolver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
)

// spotifyOEmbedEndpoint is the anonymous oEmbed endpoint. A track link is
// passed via the "url" query parameter; no credentials are required.
const spotifyOEmbedEndpoint = "https://open.spotify.com/oembed"

// Spotify resolves Spotify track/album/playlist links via the public oEmbed
// API. It reports title and, when present, author_name as the artist. oEmbed
// does not expose duration, so DurationSec stays 0 (best effort, never
// guessed).
type Spotify struct {
	// Client is used for the HTTPS request. Tests inject a fake server client.
	Client *http.Client
	// Endpoint overrides the oEmbed endpoint (tests point this at a mock).
	Endpoint string
	// Name is the value reported in Track.ResolvedBy. Defaults to "spotify".
	Name string
	// Config carries service credentials for the authenticated path (#8). Empty
	// values keep the anonymous oEmbed behaviour.
	Config Config
}

func (s *Spotify) client() *http.Client {
	if s.Client != nil {
		return s.Client
	}
	return http.DefaultClient
}

func (s *Spotify) endpoint() string {
	if s.Endpoint != "" {
		return s.Endpoint
	}
	return spotifyOEmbedEndpoint
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

// Resolve fetches oEmbed metadata for url.
func (s *Spotify) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	u := s.endpoint() + "?url=" + url.QueryEscape(rawurl) + "&format=json"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %v", ErrService, err)
	}
	req.Header.Set("User-Agent", "QMix resolver/1.0")
	resp, err := s.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %v", ErrService, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("spotify oembed: %w: status %d", ErrService, resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("spotify: %w: %v", ErrService, err)
	}
	var oe oembedResponse
	if err := json.Unmarshal(body, &oe); err != nil {
		return nil, fmt.Errorf("spotify oembed: %w: %v", ErrService, err)
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
