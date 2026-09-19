package server

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) { return 0, errors.New("secret token source failed") }

func TestPublicAPIErrorEnvelope(t *testing.T) {
	tests := []struct {
		name    string
		request func(*testing.T) *httptest.ResponseRecorder
		status  int
		code    string
		message string
	}{
		{
			name: "room creation",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, store := newTestServer()
				store.tokenRandom = failingReader{}
				return doReq(t, newTestMux(s), http.MethodPost, "/rooms", "", "")
			},
			status: http.StatusInternalServerError, code: "internal_error", message: "failed to create room",
		},
		{
			name: "room lookup",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newTestServer()
				return doReq(t, newTestMux(s), http.MethodGet, "/rooms/nope42", "", "")
			},
			status: http.StatusNotFound, code: "room_not_found", message: "room not found",
		},
		{
			name: "host authorization",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newTestServer()
				mux := newTestMux(s)
				code, _ := createRoom(t, mux)
				return doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", "wrong")
			},
			status: http.StatusForbidden, code: "invalid_host_token", message: "invalid host token",
		},
		{
			name: "empty queue",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newTestServer()
				mux := newTestMux(s)
				code, token := createRoom(t, mux)
				return doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
			},
			status: http.StatusBadRequest, code: "queue_empty", message: "queue is empty",
		},
		{
			name: "invalid reorder json",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newTestServer()
				mux := newTestMux(s)
				code, token := createRoom(t, mux)
				return doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{`, token)
			},
			status: http.StatusBadRequest, code: "bad_request", message: "invalid json body",
		},
		{
			name: "invalid reorder",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newTestServer()
				mux := newTestMux(s)
				code, token := createRoom(t, mux)
				addTrack(t, mux, code, "https://example.test/track")
				return doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{"order":[]}`, token)
			},
			status: http.StatusBadRequest, code: "invalid_order", message: "order must be a permutation of current track ids",
		},
		{
			name: "stream backend unavailable",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newTestServer()
				mux := newTestMux(s)
				code, _ := createRoom(t, mux)
				return doReq(t, mux, http.MethodGet, "/rooms/"+code+"/current/stream", "", "")
			},
			status: http.StatusNotFound, code: "stream_unavailable", message: "no stream backend configured",
		},
		{
			name: "no current track",
			request: func(t *testing.T) *httptest.ResponseRecorder {
				s, _ := newStreamServer(&stubStreamBackend{})
				mux := newTestMux(s)
				code, _ := createRoom(t, mux)
				return doReq(t, mux, http.MethodGet, "/rooms/"+code+"/current/stream", "", "")
			},
			status: http.StatusNotFound, code: "no_current_track", message: "no current track",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rec := tc.request(t)
			if rec.Code != tc.status {
				t.Fatalf("status = %d, want %d; body=%s", rec.Code, tc.status, rec.Body.String())
			}
			var got errorEnvelope
			decodeBody(t, rec, &got)
			if got.Error != tc.code || got.Message != tc.message {
				t.Fatalf("error = %+v, want error=%q message=%q", got, tc.code, tc.message)
			}
			if rec.Header().Get("Content-Type") != "application/json" {
				t.Fatalf("Content-Type = %q", rec.Header().Get("Content-Type"))
			}
			if got.Message == "secret token source failed" {
				t.Fatal("internal error detail leaked")
			}
		})
	}
}
