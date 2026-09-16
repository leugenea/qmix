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
	"strconv"
	"strings"
	"sync"

	"github.com/oklookat/goym"
	"github.com/oklookat/goym/schema"
	"github.com/oklookat/vantuz"
)

// VK and Yandex Music have no public, unauthenticated metadata API. This
// resolver makes a best-effort attempt to read og:title/og:description from a
// publicly served page. With a VK token configured (qmix#9) VK audio links
// take an authenticated path instead: audio.getById on api.vk.com. With a
// Yandex Music token configured (qmix#10) Yandex track links take an
// authenticated path instead: the goym client against api.music.yandex.net.
// VK audio pages require a login (they render a login wall to anonymous
// clients) and Yandex Music requires an authenticated playback session, so
// in practice the anonymous path reports ErrNoAnonymous. It never fabricates
// metadata — if no og:title is present, it fails honestly.

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
// (qmix#9) VK audio links are resolved via the authenticated VK API instead;
// with Config.YMToken set (qmix#10) Yandex Music track links are resolved via
// the authenticated goym client instead.
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
	// YMFetcher overrides the Yandex Music track fetcher (tests inject a
	// mock). When nil the goym client is constructed lazily from the token.
	YMFetcher ymTrackFetcher

	// ymMu guards the lazy goym client construction below; the server
	// resolves concurrently.
	ymMu sync.Mutex
	// ymClient caches the constructed goym fetcher, if any.
	ymClient ymTrackFetcher
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
	targetURL, err := url.Parse(strings.TrimSpace(rawurl))
	if err != nil {
		return nil, errors.Join(ErrInvalid, errors.New("invalid service URL"))
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

	if svc == "yandex music" && v.Config.YMToken != "" {
		id, ok, perr := ymTrackID(rawurl)
		if perr != nil {
			return nil, perr
		}
		if ok {
			return v.resolveViaYM(ctx, rawurl, id)
		}
		// No track segment: fall through to the anonymous og-meta path.
	}

	target := v.Endpoint
	if target == "" {
		host := strings.TrimSuffix(strings.ToLower(targetURL.Hostname()), ".")
		// Keep the slash in each constant prefix: besides making the authority
		// boundary explicit, it ensures that even a path beginning with "//"
		// cannot be interpreted as a new authority.
		suffix := strings.TrimPrefix(targetURL.RequestURI(), "/")
		switch {
		case svc == "vk" && hostMatchesDomain(host, "vk.com"):
			target = "https://vk.com/" + suffix
		case svc == "vk" && hostMatchesDomain(host, "vk.ru"):
			target = "https://vk.ru/" + suffix
		case svc == "vk" && hostMatchesDomain(host, "vk.cc"):
			target = "https://vk.cc/" + suffix
		case svc == "yandex music" && hostMatchesDomain(host, "music.yandex.ru"):
			target = "https://music.yandex.ru/" + suffix
		case svc == "yandex music" && hostMatchesDomain(host, "music.yandex.com"):
			target = "https://music.yandex.com/" + suffix
		case svc == "yandex music" && hostMatchesDomain(host, "yandex.ru"):
			target = "https://yandex.ru/" + suffix
		default:
			return nil, errors.Join(ErrUnsupported, errors.New("unsupported service URL"))
		}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, fmt.Errorf("%s: %w: %w", svc, ErrService, err)
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (QMix resolver)")
	resp, err := v.clientForService(svc).Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s: %w: %w", svc, ErrService, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("%s: %w: %w", svc, ErrService, err)
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

// ymTrackFetcher is the narrow seam over the goym client (qmix#10): fetch a
// single track by numeric id. Tests inject a mock; production wires an
// adapter on top of github.com/oklookat/goym.
type ymTrackFetcher interface {
	// track returns the track with the given id, or an error from the API /
	// transport.
	track(ctx context.Context, trackID int64) (ymTrackInfo, error)
}

// ymTrackInfo is the track subset the resolver maps onto Track.
type ymTrackInfo struct {
	Title      string
	Artists    []string
	DurationMs int
}

// ymRealFetcher adapts the goym client to ymTrackFetcher.
type ymRealFetcher struct {
	cl *goym.Client
}

// track fetches the track via GET /tracks/{id} (qmix#10).
func (f ymRealFetcher) track(ctx context.Context, trackID int64) (ymTrackInfo, error) {
	resp, err := f.cl.Track(ctx, schema.ID(strconv.FormatInt(trackID, 10)))
	if err != nil {
		return ymTrackInfo{}, err
	}
	tracks := resp.Result
	if len(tracks) == 0 {
		return ymTrackInfo{}, errors.New("goym: empty result")
	}
	tr := tracks[0]
	artists := make([]string, 0, len(tr.Artists))
	for _, a := range tr.Artists {
		artists = append(artists, a.Name)
	}
	return ymTrackInfo{
		Title:      tr.Title,
		Artists:    artists,
		DurationMs: tr.DurationMs,
	}, nil
}

// ymTrackID extracts the numeric track id from a Yandex Music link: either
// /album/{album}/track/{id} or a bare /track/{id} (qmix#10; both are valid
// share formats, the API needs no album). ok=false means no track segment
// exists and the caller falls back to the anonymous og-meta path; a track
// segment with a non-numeric id reports ErrInvalid. Query parameters
// (utm_*, ref_id) are ignored.
func ymTrackID(rawurl string) (id int64, ok bool, err error) {
	u, perr := url.Parse(strings.TrimSpace(rawurl))
	if perr != nil || u.Host == "" {
		return 0, false, nil // the Mux already reports ErrInvalid for garbage
	}
	segments := strings.Split(strings.Trim(u.Path, "/"), "/")
	for i := len(segments) - 1; i >= 0; i-- {
		if segments[i] != "track" {
			continue
		}
		if i+1 >= len(segments) {
			return 0, false, errors.Join(ErrInvalid, errors.New("malformed yandex music track link"))
		}
		id, aerr := strconv.ParseInt(segments[i+1], 10, 64)
		if aerr != nil || id <= 0 {
			return 0, false, errors.Join(ErrInvalid, errors.New("malformed yandex music track link"))
		}
		return id, true, nil
	}
	return 0, false, nil
}

// ymFetcher returns the track fetcher for the authenticated Yandex path
// (qmix#10): the injected mock, or a goym client constructed lazily (once,
// cached for the lifetime of the resolver). The client is assembled from the
// exported goym.Client/vantuz pieces instead of goym.New because the latter
// performs an eager /account/status handshake (needed only for user-scoped
// endpoints like likes) — a wasted network round-trip before the first
// resolve. The token is sent solely as the Authorization header.
func (v *VKYandex) ymFetcher() ymTrackFetcher {
	if v.YMFetcher != nil {
		return v.YMFetcher
	}
	v.ymMu.Lock()
	defer v.ymMu.Unlock()
	if v.ymClient != nil {
		return v.ymClient
	}
	hc := vantuz.C().SetAuthorization("OAuth " + v.Config.YMToken)
	hc.SetClient(&http.Client{Timeout: httpTimeout})
	cl := &goym.Client{Http: hc}
	cl.SetUserAgent("goym")
	v.ymClient = ymRealFetcher{cl: cl}
	return v.ymClient
}

// resolveViaYM resolves a Yandex Music track link via the authenticated goym
// client (qmix#10). The token is sent only in the Authorization header by the
// goym client and is never echoed into returned errors.
func (v *VKYandex) resolveViaYM(ctx context.Context, rawurl string, trackID int64) (*Track, error) {
	f := v.ymFetcher()
	info, err := f.track(ctx, trackID)
	if err != nil {
		var apiErr schema.Error
		if errors.As(err, &apiErr) {
			return nil, fmt.Errorf("yandex music api: %w: %s", ErrService, apiErr.Error())
		}
		// Anything else (transport failure, bad JSON) is reported with a fixed
		// message: transport errors embed the request URL, which must not leak.
		return nil, errors.Join(ErrService, errors.New("yandex music api: request failed"))
	}
	if info.Title == "" {
		return nil, errors.Join(ErrService, errors.New("yandex music api: empty title"))
	}
	return &Track{
		Title:       info.Title,
		Artist:      strings.Join(info.Artists, ", "),
		DurationSec: int((info.DurationMs + 500) / 1000),
		Source:      rawurl,
		ResolvedBy:  "yandex",
	}, nil
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
		return nil, fmt.Errorf("vk api: %w: %w", ErrService, err)
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
		if hostMatchesDomain(host, d) {
			return "vk"
		}
	}
	for _, d := range yandexDomains {
		if hostMatchesDomain(host, d) {
			return "yandex music"
		}
	}
	return ""
}

// clientForService clones the configured client and prevents an approved
// public URL from redirecting the anonymous metadata fetch to another service
// or an arbitrary network target.
func (v *VKYandex) clientForService(service string) *http.Client {
	client := *v.client()
	previous := client.CheckRedirect
	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 {
			return errors.New("stopped after 10 redirects")
		}
		if serviceFor(req.URL.String()) != service {
			return errors.New("redirect target is outside the approved service")
		}
		if previous != nil {
			return previous(req, via)
		}
		return nil
	}
	return &client
}
