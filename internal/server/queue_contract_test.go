package server

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// TestQueueSubmissionErrorContract pins qmix#136: both public queue routes are
// aliases for one operation and return the same safe, machine-readable error.
func TestQueueSubmissionErrorContract(t *testing.T) {
	const secret = "https://user:host-secret@example.test/private?token=credential"
	cases := []struct {
		name        string
		resolver    resolver.Resolver
		body        string
		unknownRoom bool
		fullQueue   bool
		wantStatus  int
		wantCode    string
		wantMessage string
		wantRetry   string
	}{
		{name: "room not found", body: `{"url":"https://example.test/track"}`, unknownRoom: true, wantStatus: http.StatusNotFound, wantCode: "room_not_found", wantMessage: "room not found"},
		{name: "invalid json", body: `{`, wantStatus: http.StatusBadRequest, wantCode: "bad_request", wantMessage: "invalid json body"},
		{name: "request too large", body: fmt.Sprintf(`{"url":%q}`, "https://example.test/"+strings.Repeat("x", 4096)), wantStatus: http.StatusRequestEntityTooLarge, wantCode: "request_too_large", wantMessage: "request body too large"},
		{name: "empty url", body: `{"url":" "}`, wantStatus: http.StatusBadRequest, wantCode: "invalid_url", wantMessage: "url must not be empty"},
		{name: "queue full", body: `{"url":"https://example.test/track"}`, fullQueue: true, wantStatus: http.StatusConflict, wantCode: "queue_full", wantMessage: "queue is full"},
		{name: "invalid url", resolver: stubResolver{err: fmt.Errorf("%w: %s", resolver.ErrInvalid, secret)}, body: `{"url":"https://example.test/track"}`, wantStatus: http.StatusBadRequest, wantCode: "invalid_url", wantMessage: "invalid track url"},
		{name: "unsupported service", resolver: stubResolver{err: fmt.Errorf("%w: %s", resolver.ErrUnsupported, secret)}, body: `{"url":"https://example.test/track"}`, wantStatus: http.StatusUnprocessableEntity, wantCode: "unsupported_service", wantMessage: "unsupported track service"},
		{name: "anonymous resolution unavailable", resolver: stubResolver{err: fmt.Errorf("%w: %s", resolver.ErrNoAnonymous, secret)}, body: `{"url":"https://example.test/track"}`, wantStatus: http.StatusUnprocessableEntity, wantCode: "unsupported_service", wantMessage: "track is not publicly available"},
		{name: "resolver overloaded", resolver: stubResolver{err: fmt.Errorf("%w: %s", ytdlpcap.ErrOverloaded, secret)}, body: `{"url":"https://example.test/track"}`, wantStatus: http.StatusServiceUnavailable, wantCode: "overloaded", wantMessage: "track resolver is temporarily overloaded", wantRetry: retryAfterOverloaded},
		{name: "upstream failure", resolver: stubResolver{err: errors.New(secret)}, body: `{"url":"https://example.test/track"}`, wantStatus: http.StatusBadGateway, wantCode: "upstream_failure", wantMessage: "track service is temporarily unavailable"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var bodies []string
			for _, route := range []string{"host", "guest"} {
				t.Run(route, func(t *testing.T) {
					s, store := newResolverTestServer(tc.resolver)
					mux := newTestMux(s)
					code, _ := createRoom(t, mux)
					if tc.unknownRoom {
						code = "nope42"
					}
					if tc.fullQueue {
						store.mu.Lock()
						store.rooms[code].Queue = make([]Track, maxQueueLength)
						store.mu.Unlock()
					}
					path := "/rooms/" + code + "/queue"
					if route == "guest" {
						path = "/r/" + code + "/queue"
					}

					rec := doReq(t, mux, http.MethodPost, path, tc.body, "")
					bodies = append(bodies, rec.Body.String())
					if rec.Code != tc.wantStatus {
						t.Fatalf("status = %d, want %d; body=%s", rec.Code, tc.wantStatus, rec.Body.String())
					}
					var got guestErrResp
					decodeBody(t, rec, &got)
					if got.Error != tc.wantCode || got.Message != tc.wantMessage {
						t.Fatalf("error = %+v, want error=%q message=%q", got, tc.wantCode, tc.wantMessage)
					}
					if strings.Contains(rec.Body.String(), secret) || strings.Contains(rec.Body.String(), "host-secret") || strings.Contains(rec.Body.String(), "credential") {
						t.Fatalf("secret-bearing resolver detail leaked: %s", rec.Body.String())
					}
					if got := rec.Header().Get("Retry-After"); got != tc.wantRetry {
						t.Fatalf("Retry-After = %q, want %q", got, tc.wantRetry)
					}
				})
			}
			if bodies[0] != bodies[1] {
				t.Fatalf("route aliases returned different bodies: host=%q guest=%q", bodies[0], bodies[1])
			}
		})
	}
}

// TestQueueSubmissionSuccessBodiesRemainCompatible pins the two existing
// success representations while their shared operation is unified.
func TestQueueSubmissionSuccessBodiesRemainCompatible(t *testing.T) {
	for _, route := range []string{"host", "guest"} {
		t.Run(route, func(t *testing.T) {
			s, _ := newTestServer()
			mux := newTestMux(s)
			code, _ := createRoom(t, mux)
			path := "/rooms/" + code + "/queue"
			if route == "guest" {
				path = "/r/" + code + "/queue"
			}
			rec := doReq(t, mux, http.MethodPost, path, `{"url":"https://example.test/track"}`, "")
			if rec.Code != http.StatusCreated {
				t.Fatalf("status = %d, want 201; body=%s", rec.Code, rec.Body.String())
			}
			if route == "host" {
				var track Track
				decodeBody(t, rec, &track)
				if track.URL != "https://example.test/track" {
					t.Fatalf("host body = %+v", track)
				}
				return
			}
			var response guestResp
			decodeBody(t, rec, &response)
			if response.Status != "accepted" || response.Track.URL != "https://example.test/track" {
				t.Fatalf("guest body = %+v", response)
			}
		})
	}
}
