package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
)

// guestAdd posts a URL to the guest endpoint POST /r/{code}/queue. rawBody,
// when set, overrides the generated {"url":...} body.
func guestAdd(t *testing.T, mux *http.ServeMux, code, url, rawBody string) *httptest.ResponseRecorder {
	t.Helper()
	body := rawBody
	if body == "" {
		body = fmt.Sprintf(`{"url":%q}`, url)
	}
	return doReq(t, mux, http.MethodPost, "/r/"+code+"/queue", body, "")
}

// guestResp decodes a guest success response.
type guestResp struct {
	Status string `json:"status"`
	Track  Track  `json:"track"`
}

// guestErrResp decodes a guest error response.
type guestErrResp struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// TestGuestAddTrackAccepted verifies the happy path: 201, status accepted, full
// track metadata, track persisted in the queue.
func TestGuestAddTrackAccepted(t *testing.T) {
	r := stubResolver{meta: &resolver.Track{
		Title: "Some Song", Artist: "Some Artist", DurationSec: 199, Source: "https://open.spotify.com/track/x", ResolvedBy: "spotify",
	}}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := guestAdd(t, mux, code, "https://open.spotify.com/track/x", "")
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var resp guestResp
	decodeBody(t, rec, &resp)
	if resp.Status != "accepted" {
		t.Fatalf("status field = %q, want accepted", resp.Status)
	}
	tr := resp.Track
	if tr.ID == "" || tr.URL != "https://open.spotify.com/track/x" ||
		tr.Title != "Some Song" || tr.Artist != "Some Artist" ||
		tr.DurationSec != 199 || tr.ResolvedBy != "spotify" {
		t.Fatalf("track = %+v", tr)
	}

	// Track persisted in the queue.
	rec = doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 1 || view.Queue[0].ID != tr.ID || view.Queue[0].Title != "Some Song" {
		t.Fatalf("view = %+v, want one track id=%q", view.Queue, tr.ID)
	}
}

// TestGuestAddTrackRejectsRoomExpiredDuringResolution covers qmix#90 for the
// guest API, including its machine-readable room_not_found response.
func TestGuestAddTrackRejectsRoomExpiredDuringResolution(t *testing.T) {
	r := blockingResolver{started: make(chan struct{}), release: make(chan struct{})}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	ch, cancel := s.hub.Subscribe(code)
	defer cancel()

	responses := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		responses <- guestAdd(t, mux, code, "https://example.com/song", "")
	}()
	receiveWithin(t, r.started)

	store.mu.Lock()
	store.rooms[code].LastActivity = time.Now().Add(-2 * store.TTL)
	store.mu.Unlock()
	store.sweep()
	close(r.release)

	rec := receiveWithin(t, responses)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
	var resp guestErrResp
	decodeBody(t, rec, &resp)
	if resp.Error != "room_not_found" {
		t.Fatalf("error = %q, want room_not_found", resp.Error)
	}
	if len(ch) != 0 {
		t.Fatalf("published %d events for expired room, want none", len(ch))
	}
}

// TestGuestAddTrackAcceptedNoResolver verifies the stub-title path when no
// resolver is wired in.
func TestGuestAddTrackAcceptedNoResolver(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := guestAdd(t, mux, code, "https://example.com/song", "")
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var resp guestResp
	decodeBody(t, rec, &resp)
	if resp.Status != "accepted" || resp.Track.Title != "https://example.com/song" {
		t.Fatalf("resp = %+v", resp)
	}
}

func TestGuestAddTrackRejectsOversizedBody(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	body := fmt.Sprintf(`{"url":%q}`, "https://example.com/"+strings.Repeat("x", 4096))

	rec := guestAdd(t, mux, code, "", body)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusRequestEntityTooLarge, rec.Body.String())
	}
	var resp guestErrResp
	decodeBody(t, rec, &resp)
	if resp.Error != "request_too_large" {
		t.Fatalf("error = %q, want request_too_large", resp.Error)
	}
}

func TestGuestAddTrackRejectsFullQueue(t *testing.T) {
	s, store := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	store.mu.Lock()
	store.rooms[code].Queue = make([]Track, 100)
	store.mu.Unlock()

	rec := guestAdd(t, mux, code, "https://example.com/overflow", "")
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}
	var resp guestErrResp
	decodeBody(t, rec, &resp)
	if resp.Error != "queue_full" {
		t.Fatalf("error = %q, want queue_full", resp.Error)
	}
}

func TestGuestAddTrackDoesNotResolveWhenQueueAlreadyFull(t *testing.T) {
	r := &countingResolver{}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	store.mu.Lock()
	store.rooms[code].Queue = make([]Track, 100)
	store.mu.Unlock()

	rec := guestAdd(t, mux, code, "https://example.com/overflow", "")
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}
	if r.calls != 0 {
		t.Fatalf("resolver calls = %d, want 0 for an already-full queue", r.calls)
	}
}

func TestGuestAddTrackRejectsQueueFilledDuringResolution(t *testing.T) {
	r := blockingResolver{started: make(chan struct{}), release: make(chan struct{})}
	s, store := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	store.mu.Lock()
	store.rooms[code].Queue = make([]Track, 99)
	store.mu.Unlock()

	responses := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		responses <- guestAdd(t, mux, code, "https://example.com/overflow", "")
	}()
	receiveWithin(t, r.started)
	store.mu.Lock()
	store.rooms[code].Queue = append(store.rooms[code].Queue, Track{ID: "concurrent"})
	store.mu.Unlock()
	close(r.release)

	rec := receiveWithin(t, responses)
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusConflict, rec.Body.String())
	}
	var resp guestErrResp
	decodeBody(t, rec, &resp)
	if resp.Error != "queue_full" {
		t.Fatalf("error = %q, want queue_full", resp.Error)
	}
}

// TestGuestAddTrackPublishesQueueUpdated verifies the add publishes a
// queue_updated event to the room hub.
func TestGuestAddTrackPublishesQueueUpdated(t *testing.T) {
	s, _ := newTestServer()
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	ch, cancel := s.hub.Subscribe(code)
	defer cancel()

	rec := guestAdd(t, mux, code, "https://example.com/song", "")
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusCreated)
	}

	ev := <-ch
	if ev.Name != "queue_updated" {
		t.Fatalf("event = %q, want queue_updated", ev.Name)
	}
	data, err := json.Marshal(ev.Data)
	if err != nil {
		t.Fatalf("marshal payload: %v", err)
	}
	var payload struct {
		Queue []Track `json:"queue"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		t.Fatalf("payload json: %v", err)
	}
	if len(payload.Queue) != 1 {
		t.Fatalf("payload queue = %+v, want 1 track", payload.Queue)
	}
}

// TestGuestAddTrackErrors verifies each guest error mapping.
func TestGuestAddTrackErrors(t *testing.T) {
	cases := []struct {
		name       string
		resolver   resolver.Resolver
		url        string
		rawBody    string
		wantStatus int
		wantCode   string
	}{
		{
			name:       "unknown room",
			resolver:   stubResolver{},
			url:        "https://example.com/x",
			wantStatus: http.StatusNotFound,
			wantCode:   "room_not_found",
		},
		{
			name:       "bad json",
			resolver:   stubResolver{},
			rawBody:    "{not json",
			wantStatus: http.StatusBadRequest,
			wantCode:   "bad_request",
		},
		{
			name:       "empty url",
			resolver:   stubResolver{},
			url:        "",
			wantStatus: http.StatusBadRequest,
			wantCode:   "invalid_url",
		},
		{
			name:       "whitespace url",
			resolver:   stubResolver{},
			url:        "   ",
			wantStatus: http.StatusBadRequest,
			wantCode:   "invalid_url",
		},
		{
			name:       "resolver invalid",
			resolver:   stubResolver{err: resolver.ErrInvalid},
			url:        "::::not a url",
			wantStatus: http.StatusBadRequest,
			wantCode:   "invalid_url",
		},
		{
			name:       "resolver unsupported",
			resolver:   stubResolver{err: resolver.ErrUnsupported},
			url:        "https://example.com/x",
			wantStatus: http.StatusUnprocessableEntity,
			wantCode:   "unsupported_service",
		},
		{
			name:       "resolver no anonymous",
			resolver:   stubResolver{err: resolver.ErrNoAnonymous},
			url:        "https://vk.com/audio1",
			wantStatus: http.StatusUnprocessableEntity,
			wantCode:   "unsupported_service",
		},
		{
			name:       "resolver upstream failure",
			resolver:   stubResolver{err: errors.New("spotify: service error: upstream down")},
			url:        "https://open.spotify.com/track/x",
			wantStatus: http.StatusBadGateway,
			wantCode:   "upstream_failure",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s, _ := newResolverTestServer(tc.resolver)
			mux := newTestMux(s)
			code, _ := createRoom(t, mux)

			path := "/r/" + code + "/queue"
			if tc.name == "unknown room" {
				path = "/r/nope42/queue"
			}
			body := tc.rawBody
			if body == "" {
				body = fmt.Sprintf(`{"url":%q}`, tc.url)
			}
			rec := doReq(t, mux, http.MethodPost, path, body, "")
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d; body=%s", rec.Code, tc.wantStatus, rec.Body.String())
			}
			var resp guestErrResp
			decodeBody(t, rec, &resp)
			if resp.Error != tc.wantCode {
				t.Fatalf("error code = %q, want %q", resp.Error, tc.wantCode)
			}
			if resp.Message == "" {
				t.Fatalf("message is empty, want human-readable message")
			}
		})
	}
}

// TestGuestAddTrackFailedResolutionNoAppend verifies failed resolution does
// not append to the queue.
func TestGuestAddTrackFailedResolutionNoAppend(t *testing.T) {
	s, _ := newResolverTestServer(stubResolver{err: resolver.ErrUnsupported})
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	guestAdd(t, mux, code, "https://example.com/x", "")
	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty on failed resolution", view.Queue)
	}
}
