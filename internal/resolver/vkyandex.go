package resolver

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
)

// VK and Yandex Music have no public, unauthenticated metadata API. This
// resolver makes a best-effort attempt to read og:title/og:description from a
// publicly served page. VK audio pages require a login (they render a login
// wall to anonymous clients) and Yandex Music requires an authenticated
// playback session, so in practice this resolver reports ErrNoAnonymous. It
// never fabricates metadata — if no og:title is present, it fails honestly.

// vkDomains and yandexDomains drive the domain check and the human-readable
// error message.
var vkDomains = []string{"vk.com", "vk.cc"}
var yandexDomains = []string{"music.yandex.ru", "music.yandex.com", "yandex.ru"}

// ogTitlePattern matches `<meta property="og:title" content="...">`.
var ogTitlePattern = regexp.MustCompile(`<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']`)

// VKYandex attempts anonymous resolution for VK and Yandex Music links by
// fetching the page and reading Open Graph metadata.
type VKYandex struct {
	// Client is used for the page fetch. Tests inject a fake server client.
	Client *http.Client
	// Endpoint overrides the fetch target (tests point this at a mock).
	Endpoint string
	// Config carries service credentials for the authenticated path (#9/#10).
	// Empty values keep the anonymous best-effort behaviour.
	Config Config
}

func (v *VKYandex) client() *http.Client {
	if v.Client != nil {
		return v.Client
	}
	return http.DefaultClient
}

// Resolve fetches the page and tries to extract a title.
func (v *VKYandex) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	svc := serviceFor(rawurl)
	if svc == "" {
		return nil, errors.Join(ErrUnsupported, errors.New("unsupported service for vk/yandex resolver"))
	}

	target := rawurl
	if v.Endpoint != "" {
		target = v.Endpoint
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, fmt.Errorf("%s: %w: %v", svc, ErrService, err)
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (QMix resolver)")
	resp, err := v.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s: %w: %v", svc, ErrService, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("%s: %w: %v", svc, ErrService, err)
	}
	m := ogTitlePattern.FindSubmatch(body)
	if m == nil || len(m) < 2 {
		// No public metadata reachable anonymously for this link.
		return nil, fmt.Errorf(
			"%s: %w: requires an authenticated account; public page exposes no anonymous metadata",
			svc, ErrNoAnonymous)
	}
	title := strings.TrimSpace(string(m[1]))
	if title == "" {
		return nil, fmt.Errorf("%s: %w: empty og:title", svc, ErrNoAnonymous)
	}
	return &Track{
		Title:       title,
		Artist:      "",
		DurationSec: 0,
		Source:      rawurl,
		ResolvedBy:  "vkyandex",
	}, nil
}

// serviceFor returns a short human-readable service name ("vk", "yandex music")
// if the URL belongs to one of the supported hosts, or "" otherwise.
func serviceFor(rawurl string) string {
	host, err := DomainOf(rawurl)
	if err != nil {
		return ""
	}
	for _, d := range vkDomains {
		if strings.Contains(host, d) {
			return "vk"
		}
	}
	for _, d := range yandexDomains {
		if strings.Contains(host, d) {
			return "yandex music"
		}
	}
	return ""
}
