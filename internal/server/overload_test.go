// Overload mapping tests pin the qmix#130 HTTP contract: the typed capacity
// error surfaces as 503 with Retry-After on the host add-track, guest
// add-track and stream endpoints, with stable JSON error bodies.
package server

import (
	"context"
	"errors"
	"net/http"
	"testing"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// wantRetryAfter is the fixed, documented Retry-After value the server sends
// with 503 overload responses (qmix#130).
const wantRetryAfter = "3"

// overloadedResolver fails with the typed capacity error, simulating an
// add-track whose metadata lookup was rejected by the shared limiter.
type overloadedResolver struct{}

func (overloadedResolver) Resolve(_ context.Context, _ string) (*resolver.Track, error) {
	return nil, ytdlpcap.ErrOverloaded
}

// overloadedBackend fails with the typed capacity error, simulating a stream
// whose search was rejected by the shared limiter.
type overloadedBackend struct{}

func (overloadedBackend) Stream(_ context.Context, _ *stream.Track, _ string) (*stream.Result, error) {
	return nil, ytdlpcap.ErrOverloaded
}

// TestAddTrackOverloadedReturns503WithRetryAfter pins the host add-track
// overload contract (qmix#130).
func TestAddTrackOverloadedReturns503WithRetryAfter(t *testing.T) {
	s, _ := newResolverTestServer(overloadedResolver{})
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/queue", `{"url":"https://www.youtube.com/watch?v=cap"}`, "")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (qmix#130 overload)", rec.Code)
	}
	if got := rec.Header().Get("Retry-After"); got != wantRetryAfter {
		t.Fatalf("Retry-After = %q, want %q", got, wantRetryAfter)
	}
	wantBody := `{"error":"overloaded","message":"track resolver is temporarily overloaded"}` + "\n"
	if rec.Body.String() != wantBody {
		t.Fatalf("body = %q, want %q", rec.Body.String(), wantBody)
	}
}

// TestGuestAddTrackOverloadedReturns503WithRetryAfter pins the guest
// add-track overload contract (qmix#130): 503, Retry-After and the stable
// guest error body.
func TestGuestAddTrackOverloadedReturns503WithRetryAfter(t *testing.T) {
	s, _ := newResolverTestServer(overloadedResolver{})
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := doReq(t, mux, http.MethodPost, "/r/"+code+"/queue", `{"url":"https://www.youtube.com/watch?v=cap"}`, "")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (qmix#130 overload)", rec.Code)
	}
	if got := rec.Header().Get("Retry-After"); got != wantRetryAfter {
		t.Fatalf("Retry-After = %q, want %q", got, wantRetryAfter)
	}
	wantBody := `{"error":"overloaded","message":"track resolver is temporarily overloaded"}` + "\n"
	if rec.Body.String() != wantBody {
		t.Fatalf("body = %q, want %q", rec.Body.String(), wantBody)
	}
}

// TestStreamOverloadedReturns503WithRetryAfter pins the stream endpoint
// overload contract (qmix#130): 503 plus Retry-After from the typed error.
func TestStreamOverloadedReturns503WithRetryAfter(t *testing.T) {
	s, _ := newStreamServer(overloadedBackend{})
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	s.store.mu.Lock()
	room := s.store.rooms[code]
	room.Current = &Current{TrackID: "1", State: "playing", URL: "https://www.youtube.com/watch?v=cap", Title: "T", ResolvedBy: "youtube"}
	s.store.mu.Unlock()

	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code+"/current/stream", "", "")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (qmix#130 overload)", rec.Code)
	}
	if got := rec.Header().Get("Retry-After"); got != wantRetryAfter {
		t.Fatalf("Retry-After = %q, want %q", got, wantRetryAfter)
	}
	wantBody := `{"error":"overloaded","message":"stream resolver is temporarily overloaded"}`
	if rec.Body.String() != wantBody {
		t.Fatalf("body = %q, want %q", rec.Body.String(), wantBody)
	}
}

// TestQueueSubmissionErrorMapping covers the centralized resolver mapping.
func TestQueueSubmissionErrorMapping(t *testing.T) {
	cases := []struct {
		name       string
		err        error
		wantStatus int
		wantCode   string
	}{
		{name: "invalid", err: resolver.ErrInvalid, wantStatus: http.StatusBadRequest, wantCode: "invalid_url"},
		{name: "unsupported", err: resolver.ErrUnsupported, wantStatus: http.StatusUnprocessableEntity, wantCode: "unsupported_service"},
		{name: "no anonymous", err: resolver.ErrNoAnonymous, wantStatus: http.StatusUnprocessableEntity, wantCode: "unsupported_service"},
		{name: "overloaded", err: ytdlpcap.ErrOverloaded, wantStatus: http.StatusServiceUnavailable, wantCode: "overloaded"},
		{name: "upstream", err: errors.New("upstream down"), wantStatus: http.StatusBadGateway, wantCode: "upstream_failure"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := queueSubmissionError(tc.err)
			if got.Status != tc.wantStatus || got.Code != tc.wantCode {
				t.Fatalf("mapping = %+v, want status=%d code=%q", got, tc.wantStatus, tc.wantCode)
			}
		})
	}
}

// TestStreamErrorStatusMapsOverloadedTo503 pins the proxy-level mapping: the
// typed capacity error must surface as 503 plus Retry-After (qmix#130).
func TestStreamErrorStatusMapsOverloadedTo503(t *testing.T) {
	s, _ := newStreamServer(overloadedBackend{})
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)
	s.store.mu.Lock()
	room := s.store.rooms[code]
	room.Current = &Current{TrackID: "1", State: "playing", URL: "https://www.youtube.com/watch?v=cap", Title: "T", ResolvedBy: "youtube"}
	s.store.mu.Unlock()

	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code+"/current/stream", "", "")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (qmix#130 overload)", rec.Code)
	}
	if got := rec.Header().Get("Retry-After"); got != wantRetryAfter {
		t.Fatalf("Retry-After = %q, want %q", got, wantRetryAfter)
	}
}
