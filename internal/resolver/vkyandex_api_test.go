package resolver

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// vkTestRig builds an httptest server standing in for api.vk.com/method and a
// resolver pointed at it. It asserts the request shape (POST, form fields,
// VK API version, required mobile User-Agent) on every call.
type vkTestRig struct {
	ts      *httptest.Server
	apiHits int

	lastAudios string
	status     int
	body       string
}

func newVKTestRig(t *testing.T) *vkTestRig {
	t.Helper()
	r := &vkTestRig{status: 200}
	r.body = `{"response":[{"title":"Ready To Go","artist":"Limp Bizkit","duration":361,"url":"https://x/audio.mp3","access_key":"bb5b9b9640eed9638f"}]}`
	r.ts = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Method != http.MethodPost {
			http.Error(w, "want POST", http.StatusMethodNotAllowed)
			return
		}
		if req.URL.Path != "/audio.getById" {
			http.Error(w, "unexpected path "+req.URL.Path, http.StatusNotFound)
			return
		}
		if err := req.ParseForm(); err != nil {
			http.Error(w, "bad form", http.StatusBadRequest)
			return
		}
		if got := req.PostForm.Get("v"); got != vkAPIVersion {
			http.Error(w, "bad api version", http.StatusBadRequest)
			return
		}
		if got := req.PostForm.Get("access_token"); got != "test-token" {
			http.Error(w, "bad token", http.StatusUnauthorized)
			return
		}
		if got := req.Header.Get("User-Agent"); got != vkMobileUA {
			http.Error(w, "missing mobile UA", http.StatusForbidden)
			return
		}
		if got := req.URL.RawQuery; got != "" {
			http.Error(w, "token must not be in the URL", http.StatusBadRequest)
			return
		}
		r.apiHits++
		r.lastAudios = req.PostForm.Get("audios")
		w.WriteHeader(r.status)
		w.Write([]byte(r.body))
	}))
	t.Cleanup(r.ts.Close)
	return r
}

func (r *vkTestRig) resolver() *VKYandex {
	return &VKYandex{
		Client:    r.ts.Client(),
		VKAPIBase: r.ts.URL,
		Config:    Config{VKToken: "test-token"},
	}
}

// TestResolver_VKAPISuccessWithKey verifies the authenticated path for a link
// with an access key: form fields, UA, and field mapping.
func TestResolver_VKAPISuccessWithKey(t *testing.T) {
	rig := newVKTestRig(t)
	s := rig.resolver()
	tr, err := s.Resolve(context.Background(), "https://vk.ru/audio1132822_456240773_bb5b9b9640eed9638f")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if rig.lastAudios != "1132822_456240773_bb5b9b9640eed9638f" {
		t.Fatalf("audios = %q, want owner_id_key", rig.lastAudios)
	}
	if tr.Title != "Ready To Go" || tr.Artist != "Limp Bizkit" || tr.DurationSec != 361 {
		t.Fatalf("track = %+v", tr)
	}
	if tr.ResolvedBy != "vk" || tr.Source != "https://vk.ru/audio1132822_456240773_bb5b9b9640eed9638f" {
		t.Fatalf("track = %+v", tr)
	}
	if rig.apiHits != 1 {
		t.Fatalf("api hits = %d, want 1", rig.apiHits)
	}
}

// TestResolver_VKAPISuccessNoKey verifies the audios value without an access key.
func TestResolver_VKAPISuccessNoKey(t *testing.T) {
	rig := newVKTestRig(t)
	s := rig.resolver()
	tr, err := s.Resolve(context.Background(), "https://vk.com/audio123_456")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if rig.lastAudios != "123_456" {
		t.Fatalf("audios = %q, want 123_456", rig.lastAudios)
	}
	if tr.Title != "Ready To Go" {
		t.Fatalf("title = %q", tr.Title)
	}
}

// TestResolver_VKAPINoTokenFallsBackToOG verifies a VK audio link without a
// token keeps the M2 anonymous behaviour.
func TestResolver_VKAPINoTokenFallsBackToOG(t *testing.T) {
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Error("vk api must not be hit without a token")
	}))
	defer api.Close()
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><head><meta property="og:title" content="Anon Title"></head></html>`))
	}))
	defer og.Close()

	v := &VKYandex{Client: og.Client(), Endpoint: og.URL, VKAPIBase: api.URL, Config: Config{}}
	tr, err := v.Resolve(context.Background(), "https://vk.com/audio123_456")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Anon Title" || tr.ResolvedBy != "vkyandex" {
		t.Fatalf("track = %+v", tr)
	}
}

// TestResolver_VKAPINoAudioMarkerFallsBackToOG verifies a VK link without an
// /audio path segment goes to the og-meta path even with a token.
func TestResolver_VKAPINoAudioMarkerFallsBackToOG(t *testing.T) {
	rig := newVKTestRig(t)
	og := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<html><head><meta property="og:title" content="Wall Post"></head></html>`))
	}))
	defer og.Close()

	v := rig.resolver()
	v.Endpoint = og.URL
	tr, err := v.Resolve(context.Background(), "https://vk.com/wall123_456")
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if tr.Title != "Wall Post" || tr.ResolvedBy != "vkyandex" {
		t.Fatalf("track = %+v", tr)
	}
	if rig.apiHits != 0 {
		t.Fatalf("api hits = %d, want 0", rig.apiHits)
	}
}

// TestResolver_VKAPIMalformedAudioSegment verifies an audio segment with
// non-numeric owner/id reports ErrInvalid.
func TestResolver_VKAPIMalformedAudioSegment(t *testing.T) {
	rig := newVKTestRig(t)
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://vk.com/audioabc_def")
	if !errors.Is(err, ErrInvalid) {
		t.Fatalf("err = %v, want ErrInvalid", err)
	}
	if rig.apiHits != 0 {
		t.Fatalf("api hits = %d, want 0", rig.apiHits)
	}
}

// TestResolver_VKAPIErrorPayload verifies a VK error response maps to
// ErrService with only the VK error code/message (no token, no URL).
func TestResolver_VKAPIErrorPayload(t *testing.T) {
	rig := newVKTestRig(t)
	rig.body = `{"error":{"error_code":5,"error_msg":"User authorization failed: invalid access_token."}}`
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://vk.com/audio123_456")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	msg := err.Error()
	if !strings.Contains(msg, "5") || !strings.Contains(msg, "User authorization failed") {
		t.Fatalf("err = %q, want vk error code and message", msg)
	}
	if strings.Contains(msg, "test-token") || strings.Contains(msg, "vk.com") || strings.Contains(msg, rig.ts.URL) {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
}

// TestResolver_VKAPIEmptyResponse covers a missing response array.
func TestResolver_VKAPIEmptyResponse(t *testing.T) {
	rig := newVKTestRig(t)
	rig.body = `{"response":[]}`
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://vk.com/audio123_456")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_VKAPIBadJSON covers a malformed API payload.
func TestResolver_VKAPIBadJSON(t *testing.T) {
	rig := newVKTestRig(t)
	rig.body = `not json`
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://vk.com/audio123_456")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_VKAPIEmptyTitle covers an audio item without a title.
func TestResolver_VKAPIEmptyTitle(t *testing.T) {
	rig := newVKTestRig(t)
	rig.body = `{"response":[{"artist":"X","duration":100}]}`
	s := rig.resolver()
	_, err := s.Resolve(context.Background(), "https://vk.com/audio123_456")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
}

// TestResolver_VKAPITransportError verifies a network failure maps to
// ErrService and the message contains neither the token nor any URL.
func TestResolver_VKAPITransportError(t *testing.T) {
	s := &VKYandex{
		VKAPIBase: "http://127.0.0.1:1", // closed port
		Config:    Config{VKToken: "test-token"},
	}
	_, err := s.Resolve(context.Background(), "https://vk.com/audio123_456")
	if !errors.Is(err, ErrService) {
		t.Fatalf("err = %v, want ErrService", err)
	}
	msg := err.Error()
	if strings.Contains(msg, "test-token") || strings.Contains(msg, "vk.com") || strings.Contains(msg, "127.0.0.1") {
		t.Fatalf("error leaks token or URL: %q", msg)
	}
}

// TestVKAudioID covers the audios parser: keys, keyless, negatives, multiple
// segments, non-audio paths, malformed segments.
func TestVKAudioID(t *testing.T) {
	withKey := "https://vk.ru/audio1132822_456240773_bb5b9b9640eed9638f"
	if id, ok, err := vkAudioID(withKey); err != nil || !ok || id != "1132822_456240773_bb5b9b9640eed9638f" {
		t.Errorf("vkAudioID(key) = %q,%v,%v", id, ok, err)
	}
	if id, ok, err := vkAudioID("https://vk.com/audio123_456"); err != nil || !ok || id != "123_456" {
		t.Errorf("vkAudioID(nokey) = %q,%v,%v", id, ok, err)
	}
	if id, ok, err := vkAudioID("https://m.vk.com/audio-123_456?x=1"); err != nil || !ok || id != "-123_456" {
		t.Errorf("vkAudioID(negative) = %q,%v,%v", id, ok, err)
	}
	if id, ok, err := vkAudioID("https://vk.com/wall123_456"); err != nil || ok || id != "" {
		t.Errorf("vkAudioID(wall) = %q,%v,%v", id, ok, err)
	}
	if _, _, err := vkAudioID("https://vk.com/audioabc_def"); !errors.Is(err, ErrInvalid) {
		t.Errorf("vkAudioID(malformed) err = %v, want ErrInvalid", err)
	}
	if _, _, err := vkAudioID("https://vk.com/audio123_"); !errors.Is(err, ErrInvalid) {
		t.Errorf("vkAudioID(trailing) err = %v, want ErrInvalid", err)
	}
}

// TestResolver_VKAPIDefaults pins the default API base.
func TestResolver_VKAPIDefaults(t *testing.T) {
	if (&VKYandex{}).apiBase() != vkDefaultAPIBase {
		t.Fatal("default VK api base changed")
	}
}
