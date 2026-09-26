package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func startPlayerCurrentTrack(t *testing.T, server *Server, code, token string) Track {
	t.Helper()
	mux := newTestMux(server)
	response := addTrack(t, mux, code, "https://example.test/player")
	if response.Code != http.StatusCreated {
		t.Fatalf("add track status = %d; body=%s", response.Code, response.Body.String())
	}
	var track Track
	decodeBody(t, response, &track)
	response = doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if response.Code != http.StatusOK {
		t.Fatalf("skip status = %d; body=%s", response.Code, response.Body.String())
	}
	return track
}

func reportPlayer(t *testing.T, mux *http.ServeMux, code, token, trackID, state string, pos int) *playerResponse {
	t.Helper()
	body, err := json.Marshal(map[string]any{"track_id": trackID, "state": state, "pos_sec": pos})
	if err != nil {
		t.Fatal(err)
	}
	response := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/player", string(body), token)
	if response.Code != http.StatusOK {
		t.Fatalf("player report status = %d; body=%s", response.Code, response.Body.String())
	}
	var decoded playerResponse
	decodeBody(t, response, &decoded)
	return &decoded
}

func TestPlayerReportUpdatesCurrentAndPublishesExactState(t *testing.T) {
	server, _ := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	track := startPlayerCurrentTrack(t, server, code, token)
	events, cancel := subscribeTestEvents(t, server.store, server.hub, code)
	defer cancel()

	response := reportPlayer(t, mux, code, token, track.ID, "paused", 17)
	if response.Current == nil || response.Current.TrackID != track.ID || response.Current.State != "paused" || response.Current.PosSec != 17 {
		t.Fatalf("response current = %+v", response.Current)
	}
	event := receiveWithin(t, events)
	if event.Name != "player_state" {
		t.Fatalf("event name = %q, want player_state", event.Name)
	}
	payload := decodeEventPayload[map[string]interface{}](t, event)
	if len(payload) != 3 || payload["track_id"] != track.ID || payload["state"] != "paused" || payload["pos_sec"] != float64(17) {
		t.Fatalf("event payload = %#v", event.Data)
	}

	get := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view roomView
	decodeBody(t, get, &view)
	if view.Current == nil || view.Current.State != "paused" || view.Current.PosSec != 17 {
		t.Fatalf("GET current = %+v", view.Current)
	}
	snapshot, err := server.store.EventSnapshot(code)
	if err != nil {
		t.Fatal(err)
	}
	current := snapshot.Data["current"].(map[string]interface{})
	if current["track_id"] != track.ID || current["state"] != "paused" || current["pos_sec"] != 17 {
		t.Fatalf("snapshot current = %#v", current)
	}
}

func TestPlayerReportErrorIsGuestSafe(t *testing.T) {
	server, _ := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	track := startPlayerCurrentTrack(t, server, code, token)
	secret := "decoder-secret-upstream-url"
	body := `{"track_id":` + strconvQuote(track.ID) + `,"state":"error","pos_sec":9,"detail":` + strconvQuote(secret) + `}`
	bad := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/player", body, token)
	if bad.Code != http.StatusBadRequest || strings.Contains(bad.Body.String(), secret) || strings.Contains(bad.Body.String(), token) {
		t.Fatalf("unsafe invalid response: status=%d body=%s", bad.Code, bad.Body.String())
	}

	response := reportPlayer(t, mux, code, token, track.ID, "error", 9)
	encoded, err := json.Marshal(response)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), secret) || strings.Contains(string(encoded), token) || response.Current.State != "error" {
		t.Fatalf("unsafe error current: %s", encoded)
	}
}

func strconvQuote(value string) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}

func TestPlayerReportEndedClearsOnlyCurrentAndPreservesQueue(t *testing.T) {
	server, _ := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	first := startPlayerCurrentTrack(t, server, code, token)
	secondResponse := addTrack(t, mux, code, "https://example.test/next")
	var second Track
	decodeBody(t, secondResponse, &second)
	events, cancel := subscribeTestEvents(t, server.store, server.hub, code)
	defer cancel()

	response := reportPlayer(t, mux, code, token, first.ID, "ended", 21)
	if response.Current != nil {
		t.Fatalf("ended response current = %+v, want nil", response.Current)
	}
	event := receiveWithin(t, events)
	payload := decodeEventPayload[map[string]interface{}](t, event)
	if event.Name != "player_state" || len(payload) != 3 || payload["track_id"] != first.ID || payload["state"] != "ended" || payload["pos_sec"] != float64(21) {
		t.Fatalf("ended event = %+v", event)
	}
	view, err := server.store.View(code)
	if err != nil {
		t.Fatal(err)
	}
	if view.Current != nil || len(view.Queue) != 1 || view.Queue[0].ID != second.ID {
		t.Fatalf("ended room view = %+v", view)
	}
}

func TestPlayerReportStatusAndExactBodyContracts(t *testing.T) {
	server, _ := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	track := startPlayerCurrentTrack(t, server, code, token)
	valid := `{"track_id":` + strconvQuote(track.ID) + `,"state":"playing","pos_sec":0}`

	for _, test := range []struct {
		name, code, auth, body string
		status                 int
		wantCode               string
	}{
		{"unknown room", "nope42", token, valid, http.StatusNotFound, "room_not_found"},
		{"missing token", code, "", valid, http.StatusForbidden, "invalid_host_token"},
		{"wrong token", code, "wrong-secret", valid, http.StatusForbidden, "invalid_host_token"},
		{"malformed", code, token, `{`, http.StatusBadRequest, "bad_request"},
		{"invalid state", code, token, `{"track_id":` + strconvQuote(track.ID) + `,"state":"buffering","pos_sec":0}`, http.StatusBadRequest, "invalid_player_state"},
		{"negative position", code, token, `{"track_id":` + strconvQuote(track.ID) + `,"state":"playing","pos_sec":-1}`, http.StatusBadRequest, "invalid_player_position"},
		{"missing key", code, token, `{"track_id":` + strconvQuote(track.ID) + `,"state":"playing"}`, http.StatusBadRequest, "bad_request"},
		{"unknown key", code, token, `{"track_id":` + strconvQuote(track.ID) + `,"state":"playing","pos_sec":0,"detail":"secret"}`, http.StatusBadRequest, "bad_request"},
		{"duplicate key", code, token, `{"track_id":` + strconvQuote(track.ID) + `,"track_id":` + strconvQuote(track.ID) + `,"state":"playing","pos_sec":0}`, http.StatusBadRequest, "bad_request"},
		{"null position", code, token, `{"track_id":` + strconvQuote(track.ID) + `,"state":"playing","pos_sec":null}`, http.StatusBadRequest, "bad_request"},
		{"second value", code, token, valid + `{}`, http.StatusBadRequest, "bad_request"},
		{"oversized", code, token, valid + strings.Repeat(" ", int(maxPlayerBodyBytes)), http.StatusRequestEntityTooLarge, "request_too_large"},
	} {
		t.Run(test.name, func(t *testing.T) {
			before, err := server.store.View(code)
			if err != nil {
				t.Fatal(err)
			}
			response := doReq(t, mux, http.MethodPatch, "/rooms/"+test.code+"/player", test.body, test.auth)
			if response.Code != test.status {
				t.Fatalf("status = %d, want %d; body=%s", response.Code, test.status, response.Body.String())
			}
			var envelope errorEnvelope
			decodeBody(t, response, &envelope)
			if envelope.Error != test.wantCode || (envelope.Error == "bad_request" && envelope.Message != "invalid json body") || (test.auth != "" && strings.Contains(response.Body.String(), test.auth)) || strings.Contains(response.Body.String(), "secret") {
				t.Fatalf("envelope = %+v; body=%s", envelope, response.Body.String())
			}
			after, err := server.store.View(code)
			if err != nil {
				t.Fatal(err)
			}
			if *before.Current != *after.Current {
				t.Fatalf("rejected report mutated current: before=%+v after=%+v", before.Current, after.Current)
			}
		})
	}
}

func TestPlayerReportRevalidatesIncarnationAndTokenAfterDecode(t *testing.T) {
	for _, test := range []struct {
		name       string
		mutate     func(*Store, string, *Room)
		wantStatus int
	}{
		{
			name: "replacement room",
			mutate: func(store *Store, code string, original *Room) {
				delete(store.rooms, code)
				store.nextGen++
				store.rooms[code] = &Room{Code: code, HostToken: original.HostToken, Current: &Current{TrackID: "replacement", State: "playing"}, LastActivity: time.Now(), generation: store.nextGen}
			},
			wantStatus: http.StatusNotFound,
		},
		{
			name: "changed token",
			mutate: func(_ *Store, _ string, original *Room) {
				original.HostToken = "replacement-host-token"
			},
			wantStatus: http.StatusForbidden,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			server, store := newTestServer()
			mux := newTestMux(server)
			code, token := createRoom(t, mux)
			track := startPlayerCurrentTrack(t, server, code, token)
			store.mu.Lock()
			original := store.rooms[code]
			store.mu.Unlock()
			body := &onReadReader{
				onRead: func() {
					store.mu.Lock()
					test.mutate(store, code, original)
					store.mu.Unlock()
				},
				reader: strings.NewReader(`{"track_id":` + strconvQuote(track.ID) + `,"state":"paused","pos_sec":12}`),
			}
			request := httptest.NewRequest(http.MethodPatch, "/rooms/"+code+"/player", body)
			request.Header.Set("X-Host-Token", token)
			recorder := httptest.NewRecorder()
			mux.ServeHTTP(recorder, request)
			if recorder.Code != test.wantStatus || strings.Contains(recorder.Body.String(), token) {
				t.Fatalf("response = status %d body %s", recorder.Code, recorder.Body.String())
			}
			store.mu.Lock()
			current := store.rooms[code].Current
			store.mu.Unlock()
			if test.name == "replacement room" && (current == nil || current.TrackID != "replacement") {
				t.Fatalf("replacement current mutated: %+v", current)
			}
			if test.name == "changed token" && (current == nil || current.State != "playing" || current.PosSec != 0) {
				t.Fatalf("unauthorized report mutated current: %+v", current)
			}
		})
	}
}

func TestPlayerReportConflictDoesNotMutateOrPublish(t *testing.T) {
	server, _ := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	track := startPlayerCurrentTrack(t, server, code, token)
	events, cancel := subscribeTestEvents(t, server.store, server.hub, code)
	defer cancel()

	for _, trackID := range []string{"old-track", track.ID} {
		if trackID == track.ID {
			server.store.mu.Lock()
			server.store.rooms[code].Current = nil
			server.store.mu.Unlock()
		}
		body := `{"track_id":` + strconvQuote(trackID) + `,"state":"paused","pos_sec":44}`
		response := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/player", body, token)
		if response.Code != http.StatusConflict {
			t.Fatalf("track %q status = %d, want 409; body=%s", trackID, response.Code, response.Body.String())
		}
		var envelope errorEnvelope
		decodeBody(t, response, &envelope)
		if envelope != (errorEnvelope{Error: "player_conflict", Message: "player report does not match current track"}) {
			t.Fatalf("envelope = %+v", envelope)
		}
		select {
		case event := <-events:
			t.Fatalf("rejected report published %+v", event)
		default:
		}
	}
}

func TestPlayerReportTrackMatchIsAtomicWithConcurrentSkip(t *testing.T) {
	server, store := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	oldTrack := startPlayerCurrentTrack(t, server, code, token)
	var replacement Track
	decodeBody(t, addTrack(t, mux, code, "https://example.test/replacement"), &replacement)

	bodyRead := make(chan struct{})
	releaseBody := make(chan struct{})
	body := &onReadReader{
		onRead: func() {
			close(bodyRead)
			<-releaseBody
		},
		reader: strings.NewReader(`{"track_id":` + strconvQuote(oldTrack.ID) + `,"state":"ended","pos_sec":99}`),
	}
	request := httptest.NewRequest(http.MethodPatch, "/rooms/"+code+"/player", body)
	request.Header.Set("X-Host-Token", token)
	recorder := httptest.NewRecorder()
	reportDone := make(chan struct{})
	go func() {
		mux.ServeHTTP(recorder, request)
		close(reportDone)
	}()
	<-bodyRead // PlayerPreflight completed; report commit is held before Store mutation.

	skip := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token)
	if skip.Code != http.StatusOK {
		close(releaseBody)
		<-reportDone
		t.Fatalf("skip status = %d; body=%s", skip.Code, skip.Body.String())
	}
	close(releaseBody)
	select {
	case <-reportDone:
	case <-time.After(time.Second):
		t.Fatal("old-track report did not complete")
	}
	if recorder.Code != http.StatusConflict {
		t.Fatalf("late report status = %d, want 409; body=%s", recorder.Code, recorder.Body.String())
	}
	view, err := store.View(code)
	if err != nil {
		t.Fatal(err)
	}
	if view.Current == nil || view.Current.TrackID != replacement.ID || view.Current.State != "playing" || view.Current.PosSec != 0 {
		t.Fatalf("late old-track report corrupted replacement: %+v", view.Current)
	}
}

func TestAcceptedPlayerReportsRefreshActivityAndNormalExpiryResumes(t *testing.T) {
	server, store := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	track := startPlayerCurrentTrack(t, server, code, token)
	store.mu.Lock()
	oldActivity := time.Unix(100, 0)
	store.rooms[code].LastActivity = oldActivity
	store.mu.Unlock()

	reportPlayer(t, mux, code, token, track.ID, "paused", 2)
	store.mu.Lock()
	refreshed := store.rooms[code].LastActivity
	store.mu.Unlock()
	if !refreshed.After(oldActivity) {
		t.Fatalf("LastActivity = %v, want after %v", refreshed, oldActivity)
	}
	store.sweepAt(refreshed.Add(store.NonEmptyTTL))
	if !store.Exists(code) {
		t.Fatal("room expired at exact activity TTL boundary")
	}
	store.sweepAt(refreshed.Add(store.NonEmptyTTL + time.Nanosecond))
	if store.Exists(code) {
		t.Fatal("room did not expire after player activity stopped")
	}
}

func TestPlayerReportPositionIsNotServerClock(t *testing.T) {
	server, _ := newTestServer()
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	track := startPlayerCurrentTrack(t, server, code, token)
	reportPlayer(t, mux, code, token, track.ID, "playing", 31)
	first, err := server.store.View(code)
	if err != nil {
		t.Fatal(err)
	}
	second, err := server.store.View(code)
	if err != nil {
		t.Fatal(err)
	}
	if first.Current.PosSec != 31 || second.Current.PosSec != 31 {
		t.Fatalf("reported position changed: first=%d second=%d", first.Current.PosSec, second.Current.PosSec)
	}
}

func TestPlayerReportConcurrentConsumersRemainIsolated(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hubs := []*Hub{NewHub(), NewHub()}
	NewServer(store, hubs[0])
	NewServer(store, hubs[1])
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	store.rooms[credentials.Code].Current = &Current{TrackID: "track", State: "playing"}
	store.mu.Unlock()
	channels := make([]<-chan Event, 2)
	cancels := make([]func(), 2)
	for i, hub := range hubs {
		channels[i], cancels[i] = subscribeTestEvents(t, store, hub, credentials.Code)
		defer cancels[i]()
	}
	ref, err := store.PlayerPreflight(credentials.Code, credentials.HostToken)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.ReportPlayer(ref, credentials.HostToken, playerReport{TrackID: "track", State: "paused", PosSec: 4}); err != nil {
		t.Fatal(err)
	}
	first, second := receiveWithin(t, channels[0]), receiveWithin(t, channels[1])
	firstPayload := decodeEventPayload[map[string]interface{}](t, first)
	firstPayload["state"] = "mutated"
	if decodeEventPayload[map[string]interface{}](t, second)["state"] != "paused" {
		t.Fatal("player event payload shared across hubs")
	}
}
