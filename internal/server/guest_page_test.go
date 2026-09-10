package server

import (
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
