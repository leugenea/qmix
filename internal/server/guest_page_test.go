package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

func TestGuestPageServesRoomUIWithoutAuthentication(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodGet, "/r/"+code, "", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Fatalf("content-type = %q, want text/html", ct)
	}
	body := rec.Body.String()
	for _, want := range []string{
		`id="current-track"`,
		`id="queue"`,
		`id="queue-form"`,
		`name="url"`,
		`/assets/guest.js`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("body does not contain %q", want)
		}
	}
	if strings.Contains(strings.ToLower(body), "login") {
		t.Fatal("guest page unexpectedly contains a login requirement")
	}
}

func TestGuestPageReturnsNotFoundForUnknownRoom(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	rec := doReq(t, mux, http.MethodGet, "/r/nope42", "", "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
}

func TestGuestScriptStreamsRoomState(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	rec := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/javascript") {
		t.Fatalf("content-type = %q, want text/javascript", ct)
	}
	if cacheControl := rec.Header().Get("Cache-Control"); cacheControl != "no-cache" {
		t.Fatalf("cache-control = %q, want no-cache", cacheControl)
	}
	body := rec.Body.String()
	for _, want := range []string{
		"new EventSource",
		"/rooms/",
		"/events",
		`"queue_snapshot"`,
		`"queue_updated"`,
		`"track_changed"`,
		`"player_state"`,
		"textContent",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("script does not contain %q", want)
		}
	}
}

func TestGuestQueueRendersEachTrackAsMaterialCard(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	page := doReq(t, mux, http.MethodGet, "/r/"+code, "", "").Body.String()
	for _, want := range []string{
		`.queue li.queue-track-card {`,
		`padding: 12px 16px`,
		`background: var(--md-sys-color-surface-container-high)`,
		`border-radius: 16px`,
	} {
		if !strings.Contains(page, want) {
			t.Errorf("guest page does not style queued tracks as individual Material cards; missing %q", want)
		}
	}

	script := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "").Body.String()
	if !strings.Contains(script, `item.className = "queue-track-card"`) {
		t.Error("queue renderer does not mark each queued track as an individual Material card")
	}
}

func TestGuestQueueScopesEmptyStateSeparatelyFromSingleTrack(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	page := doReq(t, mux, http.MethodGet, "/r/"+code, "", "").Body.String()
	for _, want := range []string{
		`<li class="queue-empty-state">The queue is empty</li>`,
		`.queue li.queue-empty-state::before {`,
	} {
		if !strings.Contains(page, want) {
			t.Errorf("guest page does not explicitly scope the empty queue state; missing %q", want)
		}
	}
	if strings.Contains(page, `.queue li:only-child::before`) {
		t.Error("single queued tracks must keep their 01 counter instead of using empty-state styling")
	}

	script := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "").Body.String()
	if !strings.Contains(script, `empty.className = "queue-empty-state"`) {
		t.Error("queue renderer does not distinguish the empty placeholder from real track cards")
	}
}

func TestGuestSnackbarAutoDismissesAndResetsItsTimer(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	script := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "").Body.String()
	for _, want := range []string{
		"let messageTimer = null;",
		"function showMessage(message, kind, dismissAfter = 5000)",
		"clearTimeout(messageTimer);",
		"messageTimer = setTimeout(() => {",
		`messageElement.textContent = "";`,
		"delete messageElement.dataset.kind;",
	} {
		if !strings.Contains(script, want) {
			t.Errorf("snackbar does not safely reset and auto-dismiss; missing %q", want)
		}
	}
}

func TestGuestScriptReconnectsWithBackoffAndAfterVisibilityChange(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	rec := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "")
	body := rec.Body.String()
	for _, want := range []string{
		"setTimeout",
		"clearTimeout",
		"source.close()",
		"Math.min(retryDelay * 2, maxRetryDelay)",
		"if (source !== eventSource) return;",
		`addEventListener("visibilitychange"`,
		`document.visibilityState === "visible"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("script does not contain %q", want)
		}
	}
}

func TestGuestScriptIgnoresDataEventsFromObsoleteConnections(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	rec := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "")
	body := rec.Body.String()
	for _, event := range []string{"queue_snapshot", "queue_updated", "track_changed", "player_state"} {
		want := `addEventListener("` + event + `", (event) => {` + "\n" +
			"      if (source !== eventSource) return;"
		if !strings.Contains(body, want) {
			t.Errorf("%s listener does not immediately ignore events from obsolete connections", event)
		}
	}
}

func TestGuestScriptSubmitsLinksAndReportsResult(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	rec := doReq(t, mux, http.MethodGet, "/assets/guest.js", "", "")
	body := rec.Body.String()
	for _, want := range []string{
		`getElementById("queue-form")`,
		`addEventListener("submit"`,
		"fetch(`/r/${encodeURIComponent(code)}/queue`",
		`method: "POST"`,
		`"Content-Type": "application/json"`,
		"JSON.stringify({ url })",
		"response.ok",
		"data.message",
		`form.reset()`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("script does not contain %q", want)
		}
	}
}

func TestGuestPageUsesMobileFirstMaterialYouShell(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodGet, "/r/"+code, "", "")
	body := rec.Body.String()
	for _, want := range []string{
		`rel="manifest" href="/assets/guest.webmanifest"`,
		`name="theme-color"`,
		`class="top-app-bar"`,
		`class="track-form"`,
		`class="snackbar"`,
		`--md-sys-color-primary:`,
		`@media (prefers-color-scheme: light)`,
		`min-height: 48px`,
		`<svg`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("body does not contain Material You requirement %q", want)
		}
	}
	if add, current := strings.Index(body, `id="add-heading"`), strings.Index(body, `id="current-heading"`); add < 0 || current < 0 || add > current {
		t.Errorf("add-track operation must precede monitoring content")
	}
	if strings.Contains(body, "fonts.googleapis.com") {
		t.Error("page must not depend on a remote Material Symbols font")
	}
}

func TestGuestManifestDescribesInstallableApp(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	rec := doReq(t, mux, http.MethodGet, "/assets/guest.webmanifest", "", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/manifest+json") {
		t.Fatalf("content-type = %q, want application/manifest+json", ct)
	}
	var manifest struct {
		Name       string `json:"name"`
		ThemeColor string `json:"theme_color"`
		Icons      []struct {
			Src string `json:"src"`
		} `json:"icons"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &manifest); err != nil {
		t.Fatalf("manifest is not valid JSON: %v", err)
	}
	if manifest.Name == "" || manifest.ThemeColor == "" || len(manifest.Icons) < 2 {
		t.Fatalf("manifest lacks name, theme color, or icons: %+v", manifest)
	}
}

func TestGuestAppIconsAreServedAsSVG(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)

	for _, path := range []string{"/assets/qmix-192.svg", "/assets/qmix-512.svg"} {
		rec := doReq(t, mux, http.MethodGet, path, "", "")
		if rec.Code != http.StatusOK {
			t.Errorf("GET %s: status = %d, want %d", path, rec.Code, http.StatusOK)
			continue
		}
		if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "image/svg+xml") {
			t.Errorf("GET %s: content-type = %q, want image/svg+xml", path, ct)
		}
		if !strings.Contains(rec.Body.String(), "<svg") {
			t.Errorf("GET %s: body is not SVG", path)
		}
	}
}
