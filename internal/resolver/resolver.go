// Package resolver turns source URLs (VK, Yandex Music, Spotify, YouTube)
// into track metadata via public, unauthenticated endpoints. No credentials
// are required or stored. Selection of the concrete implementation is driven
// by the URL domain (see Mux).
package resolver

import (
	"context"
	"errors"
	"net/url"
	"strings"
)

// Track is the metadata a resolver extracts from a source URL. The zero value
// for a field means the field could not be determined anonymously — resolvers
// never invent metadata.
type Track struct {
	Title       string
	Artist      string
	DurationSec int
	Source      string // the original source URL, echoed through unchanged
	ResolvedBy  string // which resolver produced the metadata (e.g. "spotify")
}

// Resolver resolves a source URL into track metadata.
type Resolver interface {
	// Resolve returns metadata for url. It returns ErrInvalid, ErrUnsupported
	// or ErrNoAnonymous for URLs that cannot be resolved; transient upstream
	// failures are returned wrapped in ErrService.
	Resolve(ctx context.Context, url string) (*Track, error)
}

// Sentinel errors returned (or wrapped) by resolvers. The HTTP layer maps
// them to status codes and human-readable messages.
var (
	// ErrInvalid is returned for links that are not parseable URLs.
	ErrInvalid = errors.New("invalid url")
	// ErrUnsupported is returned for a well-formed link from an unknown or
	// unsupported service.
	ErrUnsupported = errors.New("unsupported service")
	// ErrNoAnonymous is returned when a known service cannot be resolved
	// without credentials. Metadata is never fabricated.
	ErrNoAnonymous = errors.New("anonymous resolution unavailable")
	// ErrService wraps any upstream failure (HTTP, exec, parse).
	ErrService = errors.New("service error")
)

// Matcher binds a resolver to a set of host substrings. A URL is routed to the
// first matcher whose host contains any of the Domains (case-insensitive).
type Matcher struct {
	Domains  []string
	Resolver Resolver
}

// Mux is a Resolver that routes a URL to the resolver matching its domain.
// It implements selection-by-domain from ARCHITECTURE §7.
type Mux struct {
	matchers []Matcher
}

// NewMux returns a Mux routing to the given resolvers by domain. Later-added
// matchers are tried in order, so place the most specific first.
func NewMux(matchers ...Matcher) *Mux {
	return &Mux{matchers: matchers}
}

// Resolve routes url to the first matcher whose domain matches the host. It
// returns ErrInvalid for malformed URLs and ErrUnsupported when no matcher
// matches.
func (m *Mux) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	u, err := url.Parse(strings.TrimSpace(rawurl))
	if err != nil || u.Host == "" {
		return nil, fmtInvalid(rawurl)
	}
	host := strings.ToLower(u.Host)
	for _, matcher := range m.matchers {
		for _, domain := range matcher.Domains {
			if strings.Contains(host, strings.ToLower(domain)) {
				return matcher.Resolver.Resolve(ctx, rawurl)
			}
		}
	}
	return nil, ErrUnsupported
}

// DomainOf returns the normalized lowercase host of rawurl. It is a helper for
// resolvers that need to double-check their service.
func DomainOf(rawurl string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(rawurl))
	if err != nil || u.Host == "" {
		return "", ErrInvalid
	}
	return strings.ToLower(u.Host), nil
}

func fmtInvalid(rawurl string) error {
	return errors.Join(ErrInvalid, errors.New("could not parse url"))
}
