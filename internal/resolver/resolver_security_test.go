package resolver

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

type recordingResolver struct {
	called bool
}

func (r *recordingResolver) Resolve(context.Context, string) (*Track, error) {
	r.called = true
	return &Track{Title: "unexpected"}, nil
}

type securityRoundTripFunc func(*http.Request) (*http.Response, error)

func (f securityRoundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func redirectResponse(req *http.Request, location string) *http.Response {
	return &http.Response{
		StatusCode: http.StatusFound,
		Header:     http.Header{"Location": []string{location}},
		Body:       io.NopCloser(strings.NewReader("")),
		Request:    req,
	}
}

func htmlResponse(req *http.Request, body string) *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(body)),
		Request:    req,
	}
}

func TestResolver_MuxRejectsLookalikeDomainsBeforeDispatch(t *testing.T) {
	for _, rawURL := range []string{
		"https://vk.com.attacker.example/audio1_2",
		"https://attacker-vk.com/audio1_2",
		"https://spotify.com.attacker.example/track/abc",
		"https://youtube.com.attacker.example/watch?v=abc",
		"https://music.yandex.ru.attacker.example/track/1",
	} {
		t.Run(rawURL, func(t *testing.T) {
			resolver := &recordingResolver{}
			mux := NewMux(Matcher{
				Domains:  []string{"vk.com", "spotify.com", "youtube.com", "music.yandex.ru"},
				Resolver: resolver,
			})

			_, err := mux.Resolve(context.Background(), rawURL)
			if !errors.Is(err, ErrUnsupported) {
				t.Fatalf("err = %v, want ErrUnsupported", err)
			}
			if resolver.called {
				t.Fatal("resolver was called for a lookalike host")
			}
		})
	}
}

func TestResolver_MuxAcceptsDNSSubdomainsAndPorts(t *testing.T) {
	resolver := &recordingResolver{}
	mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: resolver})

	_, err := mux.Resolve(context.Background(), "https://OPEN.SPOTIFY.COM:443/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if !resolver.called {
		t.Fatal("resolver was not called for an allowed subdomain")
	}
}

func TestResolver_MuxRejectsMalformedAuthorities(t *testing.T) {
	for _, rawURL := range []string{
		"https://.spotify.com/track/abc",
		"https://foo..spotify.com/track/abc",
		"https://-open.spotify.com/track/abc",
		"https://open.spotify.com-/track/abc",
		"https://open.spotify.com:/track/abc",
		"https://open.spotify.com:0/track/abc",
		"https://open.spotify.com:65536/track/abc",
	} {
		t.Run(rawURL, func(t *testing.T) {
			resolver := &recordingResolver{}
			mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: resolver})

			_, err := mux.Resolve(context.Background(), rawURL)
			if !errors.Is(err, ErrInvalid) {
				t.Fatalf("err = %v, want ErrInvalid", err)
			}
			if resolver.called {
				t.Fatal("resolver was called for a malformed authority")
			}
		})
	}
}

func TestResolver_MuxAcceptsMaximumPort(t *testing.T) {
	resolver := &recordingResolver{}
	mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: resolver})

	_, err := mux.Resolve(context.Background(), "https://open.spotify.com:65535/track/abc")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if !resolver.called {
		t.Fatal("resolver was not called for a valid authority")
	}
}

func TestResolver_MuxRejectsUnsupportedSchemesAndUserinfo(t *testing.T) {
	for _, rawURL := range []string{
		"file://spotify.com/etc/passwd",
		"ftp://open.spotify.com/track/abc",
		"https://spotify.com@127.0.0.1/track/abc",
		"https://user:password@open.spotify.com/track/abc",
	} {
		t.Run(rawURL, func(t *testing.T) {
			resolver := &recordingResolver{}
			mux := NewMux(Matcher{Domains: []string{"spotify.com"}, Resolver: resolver})

			_, err := mux.Resolve(context.Background(), rawURL)
			if !errors.Is(err, ErrInvalid) {
				t.Fatalf("err = %v, want ErrInvalid", err)
			}
			if resolver.called {
				t.Fatal("resolver was called for an invalid URL")
			}
		})
	}
}

func TestResolver_VKYandexCanonicalizesInitialRequestOrigin(t *testing.T) {
	tests := []struct {
		name       string
		rawURL     string
		wantOrigin string
	}{
		{name: "vk com", rawURL: "http://m.vk.com:65535/audio1_2?from=share", wantOrigin: "https://vk.com"},
		{name: "vk ru", rawURL: "https://www.vk.ru/audio1_2?from=share", wantOrigin: "https://vk.ru"},
		{name: "vk cc", rawURL: "https://go.vk.cc/audio1_2?from=share", wantOrigin: "https://vk.cc"},
		{name: "music yandex ru", rawURL: "https://cdn.music.yandex.ru/audio1_2?from=share", wantOrigin: "https://music.yandex.ru"},
		{name: "music yandex com", rawURL: "https://cdn.music.yandex.com/audio1_2?from=share", wantOrigin: "https://music.yandex.com"},
		{name: "yandex ru", rawURL: "https://www.yandex.ru/audio1_2?from=share", wantOrigin: "https://yandex.ru"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			client := &http.Client{Transport: securityRoundTripFunc(func(req *http.Request) (*http.Response, error) {
				origin := req.URL.Scheme + "://" + req.URL.Host
				if origin != tt.wantOrigin {
					t.Fatalf("request origin = %s, want %s", origin, tt.wantOrigin)
				}
				if req.URL.Path != "/audio1_2" || req.URL.RawQuery != "from=share" {
					t.Fatalf("request target = %s, want original path and query", req.URL.String())
				}
				return htmlResponse(req, `<meta property="og:title" content="Track">`), nil
			})}
			resolver := &VKYandex{Client: client}

			track, err := resolver.Resolve(context.Background(), tt.rawURL)
			if err != nil {
				t.Fatalf("resolve: %v", err)
			}
			if track.Title != "Track" {
				t.Fatalf("title = %q, want Track", track.Title)
			}
		})
	}
}

func TestResolver_VKYandexRejectsLookalikeHostBeforeRequest(t *testing.T) {
	requests := 0
	client := &http.Client{Transport: securityRoundTripFunc(func(req *http.Request) (*http.Response, error) {
		requests++
		return htmlResponse(req, `<meta property="og:title" content="unexpected">`), nil
	})}
	resolver := &VKYandex{Client: client}

	_, err := resolver.Resolve(context.Background(), "https://vk.com.attacker.example/audio1_2")
	if !errors.Is(err, ErrUnsupported) {
		t.Fatalf("err = %v, want ErrUnsupported", err)
	}
	if requests != 0 {
		t.Fatalf("requests = %d, want 0", requests)
	}
}

func TestResolver_VKYandexRejectsRedirectOutsideServiceFamily(t *testing.T) {
	requests := 0
	client := &http.Client{Transport: securityRoundTripFunc(func(req *http.Request) (*http.Response, error) {
		requests++
		return redirectResponse(req, "http://127.0.0.1/admin"), nil
	})}
	resolver := &VKYandex{Client: client}

	_, err := resolver.Resolve(context.Background(), "https://vk.com/audio1_2")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	if requests != 1 {
		t.Fatalf("requests = %d, want 1; redirect target must not be requested", requests)
	}
}

func TestResolver_VKYandexAllowsRedirectWithinServiceFamily(t *testing.T) {
	requests := 0
	client := &http.Client{Transport: securityRoundTripFunc(func(req *http.Request) (*http.Response, error) {
		requests++
		if requests == 1 {
			return redirectResponse(req, "https://m.vk.com/audio1_2"), nil
		}
		return htmlResponse(req, `<meta property="og:title" content="Track">`), nil
	})}
	resolver := &VKYandex{Client: client}

	track, err := resolver.Resolve(context.Background(), "https://vk.com/audio1_2")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if track.Title != "Track" {
		t.Fatalf("title = %q, want Track", track.Title)
	}
	if requests != 2 {
		t.Fatalf("requests = %d, want 2", requests)
	}
}
