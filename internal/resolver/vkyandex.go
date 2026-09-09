package resolver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

// VK and Yandex Music have no public, unauthenticated metadata API. This resolver makes a best-effort attempt to read
// og:title/og:description from a publicly served page. With a VK token
// configured (qmix#9) VK audio links take an authenticated path instead:// audio.getById on api.vk.com. VK audio pages require a login (they render
// a login wall to anonymous clients) and Yandex Music requires an
// authenticated playback session, so in practice the anonymous path reports
// ErrNoAnonymous. It never fabricates metadata — if no og:title is present,
// it fails honestly.

// vkDomains and yandexDomains drive the domain check and the human-readable
// error message.
var vkDomains = []string{"vk.com", "vk.ru", "vk.cc"}
var yandexDomains = []string{"music.yandex.ru", "music.yandex.com", "yandex.ru"}

// ogTitlePattern matches `<meta property="og:title" content="...">`.
var ogTitlePattern = regexp.MustCompile(`<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']`)

// vkAPIVersion is the VK API version used for audio.getById (qmix#9).
const vkAPIVersion = "5.131"

// vkDefaultAPIBase is the root of the VK API method endpoints (qmix#9).
const vkDefaultAPIBase = "https://api.vk.com/method"

// vkMobileUA is the User-Agent VK requires to serve real audio metadata:
// without it the API answers with a placeholder audio_api_unavailable.mp3
// instead of the track. It mirrors the client constants in cmd/token-vk
// (clientID 2274003).
const vkMobileUA = "VKAndroidApp/4.13.1-1206 (Android 4.4.3; SDK 19; armeabi; ; ru)"

// vkAudioSegmentPattern matches a path segment audio{owner}_{id}(_{key})?
// (qmix#9). owner/id are integers; the access key is optional alphanumeric.
var vkAudioSegmentPattern = regexp.MustCompile(`^audio(-?\d+)_(\d+)(?:_([A-Za-z0-9]+))?$`)

// VKYandex attempts anonymous resolution for VK and Yandex Music links by
// fetching the page and reading Open Graph metadata. With Config.VKToken set
// (qmix#9) VK audio links are resolved via the authenticated VK API instead.
type VKYandex struct {
	// Client is used for the page fetch and API calls. Tests inject a fake
	// server client.
	Client *http.Client
	// Endpoint overrides the fetch target (tests point this at a mock).
	Endpoint string
	// VKAPIBase overrides the VK API method root (tests point this at a mock).
	VKAPIBase string
	// Config carries service credentials for the authenticated path (#9/#10).
	// Empty values keep the anonymous best-effort behaviour.
	Config Config
}

func (v *VKYandex) client() *http.Client {
	if v.Client != nil {
		return v.Client
	}
	return defaultClient
}

func (v *VKYandex) apiBase() string {
	if v.VKAPIBase != "" {
		return v.VKAPIBase
	}
	return vkDefaultAPIBase
}

// Resolve fetches the page and tries to extract a title. With a VK token and
// an audio link (qmix#9) it calls audio.getById instead; everything else keeps
// the anonymous M2 behaviour.
func (v *VKYandex) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	svc := serviceFor(rawurl)
	if svc == "" {
		return nil, errors.Join(ErrUnsupported, errors.New("unsupported service for vk/yandex resolver"))
	}

	if svc == "vk" && v.Config.VKToken != "" {
		audios, ok, err := vkAudioID(rawurl)
		if err != nil {
			return nil, err
		}
		if ok {
			return v.resolveViaAPI(ctx, rawurl, audios)
		}
		// No /audio segment: fall through to the anonymous og-meta path.
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

// vkAudio is the subset of an audio.getById item payload (qmix#9).
type vkAudio struct {
	Title    string `json:"title"`
	Artist   string `json:"artist"`
	Duration int    `json:"duration"`
}

// vkAPIPayload covers both a successful response and an API error object.
// Only the error code and message are ever surfaced (never the token or URL).
type vkAPIPayload struct {
	Response []vkAudio `json:"response"`
	Error    *struct {
		Code int    `json:"error_code"`
		Msg  string `json:"error_msg"`
	} `json:"error"`
}

// vkAudioID extracts the audios= value ("owner_id" or "owner_id_access_key")
// for audio.getById from a VK link. ok=false means no /audio path segment
// exists and the caller falls back to the anonymous og-meta path; a segment
// that starts with "audio" but does not parse reports ErrInvalid.
func vkAudioID(rawurl string) (audios string, ok bool, err error) {
	u, perr := url.Parse(strings.TrimSpace(rawurl))
	if perr != nil || u.Host == "" {
		return "", false, nil // the Mux already reports ErrInvalid for garbage
	}
	for _, seg := range strings.Split(strings.Trim(u.Path, "/"), "/") {
		if !strings.HasPrefix(seg, "audio") {
			continue
		}
		m := vkAudioSegmentPattern.FindStringSubmatch(seg)
		if m == nil {
			return "", false, errors.Join(ErrInvalid, errors.New("malformed vk audio link"))
		}
		audios := m[1] + "_" + m[2]
		if m[3] != "" {
			audios += "_" + m[3]
		}
		return audios, true, nil
	}
	return "", false, nil
}

// resolveViaAPI resolves a VK audio link via the authenticated audio.getById
// call (qmix#9). The token is sent in the POST body so it never appears in
// URLs, error messages or logs.
func (v *VKYandex) resolveViaAPI(ctx context.Context, rawurl, audios string) (*Track, error) {
	form := url.Values{
		"audios":       {audios},
		"access_token": {v.Config.VKToken},
		"v":            {vkAPIVersion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, v.apiBase()+"/audio.getById", strings.NewReader(form.Encode()))
	if err != nil {
		return nil, fmt.Errorf("vk api: %w: %v", ErrService, err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", vkMobileUA)
	resp, err := v.client().Do(req)
	if err != nil {
		// Transport errors embed the request URL; report a fixed message so
		// neither the URL nor anything from the request can leak.
		return nil, errors.Join(ErrService, errors.New("vk api: request failed"))
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, errors.Join(ErrService, errors.New("vk api: read failed"))
	}
	var p vkAPIPayload
	if err := json.Unmarshal(body, &p); err != nil {
		return nil, fmt.Errorf("vk api: %w: bad json", ErrService)
	}
	if p.Error != nil {
		return nil, fmt.Errorf("vk api: %w: error %d: %s", ErrService, p.Error.Code, p.Error.Msg)
	}
	if len(p.Response) == 0 {
		return nil, errors.Join(ErrService, errors.New("vk api: empty response"))
	}
	a := p.Response[0]
	if a.Title == "" {
		return nil, errors.Join(ErrService, errors.New("vk api: empty title"))
	}
	return &Track{
		Title:       a.Title,
		Artist:      a.Artist,
		DurationSec: a.Duration,
		Source:      rawurl,
		ResolvedBy:  "vk",
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
