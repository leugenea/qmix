package server

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestHostTokenRejectionAndDisclosureSurfaces(t *testing.T) {
	var logs bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	store.tokenRandom = bytes.NewReader(bytes.Repeat([]byte{0xa5}, hostTokenBytes))
	server := NewServerWithLogger(store, NewHub(), logger)
	mux := newTestMux(server)

	create := doReq(t, mux, http.MethodPost, "/rooms", "", "")
	if create.Code != http.StatusCreated {
		t.Fatalf("create status = %d, want %d; body=%s", create.Code, http.StatusCreated, create.Body.String())
	}
	var credentials struct {
		Code      string `json:"code"`
		HostToken string `json:"host_token"`
		URL       string `json:"url"`
	}
	decodeBody(t, create, &credentials)
	if credentials.HostToken == "" {
		t.Fatal("create response omitted the intentional host token")
	}
	if strings.Contains(credentials.URL, credentials.HostToken) || strings.Contains(credentials.URL, "host_token") {
		t.Fatalf("guest/QR URL exposes host token: %q", credentials.URL)
	}

	room := store.Get(credentials.Code)
	store.mu.Lock()
	snapshot, err := json.Marshal(snapshotPayload(room))
	store.mu.Unlock()
	if err != nil {
		t.Fatalf("marshal snapshot: %v", err)
	}
	public := doReq(t, mux, http.MethodGet, "/rooms/"+credentials.Code, "", "")

	wrongToken := credentials.HostToken[:len(credentials.HostToken)-1]
	for _, tc := range []struct {
		name   string
		method string
		path   string
		body   string
		token  string
	}{
		{name: "skip missing token", method: http.MethodPost, path: "/rooms/" + credentials.Code + "/skip"},
		{name: "skip wrong token", method: http.MethodPost, path: "/rooms/" + credentials.Code + "/skip", token: wrongToken},
		{name: "reorder missing token", method: http.MethodPatch, path: "/rooms/" + credentials.Code + "/queue", body: `{"order":[]}`},
		{name: "reorder wrong token", method: http.MethodPatch, path: "/rooms/" + credentials.Code + "/queue", body: `{"order":[]}`, token: wrongToken},
	} {
		t.Run(tc.name, func(t *testing.T) {
			response := doReq(t, mux, tc.method, tc.path, tc.body, tc.token)
			if response.Code != http.StatusForbidden {
				t.Fatalf("status = %d, want %d; body=%s", response.Code, http.StatusForbidden, response.Body.String())
			}
			assertDoesNotContainToken(t, "authentication error", response.Body.String(), credentials.HostToken, wrongToken)
		})
	}

	assertDoesNotContainToken(t, "public room", public.Body.String(), credentials.HostToken)
	assertDoesNotContainToken(t, "SSE snapshot", string(snapshot), credentials.HostToken)
	assertDoesNotContainToken(t, "logs", logs.String(), credentials.HostToken, wrongToken)
}

func TestCreateRoomEntropyFailureIsGeneric(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	secretFailure := "entropy-provider-secret"
	store.tokenRandom = errorReader{err: &sentinelError{text: secretFailure}}
	mux := newTestMux(NewServer(store, NewHub()))

	response := doReq(t, mux, http.MethodPost, "/rooms", "", "")
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want %d; body=%s", response.Code, http.StatusInternalServerError, response.Body.String())
	}
	assertDoesNotContainToken(t, "creation error", response.Body.String(), secretFailure)
}

type sentinelError struct{ text string }

func (e *sentinelError) Error() string { return e.text }

func assertDoesNotContainToken(t *testing.T, surface string, value string, tokens ...string) {
	t.Helper()
	for _, token := range tokens {
		if token != "" && strings.Contains(value, token) {
			t.Fatalf("%s exposes host token %q in %q", surface, token, value)
		}
	}
}
