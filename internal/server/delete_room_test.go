package server

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestDeleteRoomReleasesCapacityAndRejectsBadCredentials(t *testing.T) {
	store := capacityStore(t, 1, &countingCodeGenerator{})
	mux := newTestMux(NewServer(store, NewHub()))
	code, token := createRoom(t, mux)
	path := "/rooms/" + code
	for _, bad := range []string{"", "wrong"} {
		rec := doReq(t, mux, http.MethodDelete, path, "", bad)
		if rec.Code != http.StatusForbidden || rec.Body.String() != "{\"error\":\"invalid_host_token\",\"message\":\"invalid host token\"}\n" {
			t.Fatalf("bad token %q: status=%d body=%q", bad, rec.Code, rec.Body.String())
		}
		if !store.Exists(code) {
			t.Fatal("bad token removed room")
		}
	}
	requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
	if rec := doReq(t, mux, http.MethodDelete, path, "", token); rec.Code != http.StatusNoContent || rec.Body.Len() != 0 {
		t.Fatalf("delete: status=%d body=%q", rec.Code, rec.Body.String())
	}
	for _, request := range []struct{ method, path, body string }{
		{http.MethodGet, path, ""},
		{http.MethodGet, "/r/" + code, ""},
		{http.MethodDelete, path, ""},
		{http.MethodGet, path + "/events", ""},
		{http.MethodPost, path + "/queue", `{"url":"https://example.com/song"}`},
		{http.MethodPost, "/r/" + code + "/queue", `{"url":"https://example.com/song"}`},
		{http.MethodPost, path + "/skip", ""},
		{http.MethodPatch, path + "/queue", `{"order":[]}`},
		{http.MethodPatch, path + "/player", `{}`},
		{http.MethodGet, path + "/current/stream", ""},
	} {
		rec := doReq(t, mux, request.method, request.path, request.body, token)
		if rec.Code != http.StatusNotFound || rec.Body.String() != "{\"error\":\"room_not_found\",\"message\":\"room not found\"}\n" {
			t.Fatalf("%s %s after delete: status=%d body=%q", request.method, request.path, rec.Code, rec.Body.String())
		}
	}
	if rec := doReq(t, mux, http.MethodPost, "/rooms", "", ""); rec.Code != http.StatusCreated {
		t.Fatalf("capacity not released: status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestStoreDeleteRoomIsolatedAcrossReusedCodeAndHubs(t *testing.T) {
	store := capacityStore(t, 1, reusedCodeGenerator{code: "reuse1"})
	hubs := []*Hub{NewHub(), NewHub()}
	for _, hub := range hubs {
		NewServer(store, hub)
	}
	old, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	oldRef, err := store.AppendPreflight(old.Code)
	if err != nil {
		t.Fatal(err)
	}
	var subscribers []*subscriber
	for _, hub := range hubs {
		sub, _, cancel, err := store.subscribeEvents(old.Code, hub)
		if err != nil {
			t.Fatal(err)
		}
		defer cancel()
		subscribers = append(subscribers, sub)
	}
	if err := store.DeleteRoom(old.Code, old.HostToken); err != nil {
		t.Fatal(err)
	}
	for _, sub := range subscribers {
		select {
		case <-sub.done:
		default:
			t.Fatal("subscriber not closed")
		}
		if sub.terminal == nil || sub.terminal.Name != "room_closed" || sub.terminal.Data != eventJSON(`{"code":"reuse1"}`) {
			t.Fatalf("hub terminal event = %+v", sub.terminal)
		}
	}
	if _, err := store.CreateRoom(); err != nil {
		t.Fatalf("slot not released: %v", err)
	}
	if _, err := store.Append(oldRef, Track{ID: "stale"}); !errors.Is(err, errRoomNotFound) {
		t.Fatalf("stale append = %v", err)
	}
	if err := store.DeleteRoom(old.Code, old.HostToken); !errors.Is(err, errInvalidHostToken) {
		t.Fatalf("old token against replacement = %v", err)
	}
	view, err := store.View(old.Code)
	if err != nil || len(view.Queue) != 0 {
		t.Fatalf("replacement = %+v, %v", view, err)
	}
}

func TestDeleteRoomDeliversTerminalSSEBeforeEOF(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, reusedCodeGenerator{code: "reuse2"})
	mux := newTestMux(NewServer(store, NewHub()))
	code, token := createRoom(t, mux)
	ts := httptest.NewServer(mux)
	defer ts.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, ts.URL+"/rooms/"+code+"/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := ts.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	reader := bufio.NewReader(response.Body)
	if _, name, _ := readSSE(t, reader); name != "queue_snapshot" {
		t.Fatalf("first event = %q", name)
	}
	if rec := doReq(t, mux, http.MethodDelete, "/rooms/"+code, "", token); rec.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d", rec.Code)
	}
	id, name, data := readSSE(t, reader)
	if id != "1" || name != "room_closed" || data != `{"code":"reuse2"}` {
		t.Fatalf("terminal = id=%q name=%q data=%q", id, name, data)
	}
	if _, err := reader.ReadByte(); err != io.EOF {
		t.Fatalf("after terminal = %v, want EOF", err)
	}
}

type recordingSSEWriter struct {
	header http.Header
	body   bytes.Buffer
}

func (w *recordingSSEWriter) Header() http.Header              { return w.header }
func (w *recordingSSEWriter) WriteHeader(int)                  {}
func (w *recordingSSEWriter) Write(p []byte) (int, error)      { return w.body.Write(p) }
func (w *recordingSSEWriter) Flush()                           {}
func (w *recordingSSEWriter) FlushError() error                { return nil }
func (w *recordingSSEWriter) SetWriteDeadline(time.Time) error { return nil }

func TestDeleteRoomDrainsQueuedEventsBeforeTerminal(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hub := NewHub()
	NewServer(store, hub)
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	sub, snapshot, cancel, err := store.subscribeEvents(credentials.Code, hub)
	if err != nil {
		t.Fatal(err)
	}
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Append(ref, Track{ID: "queued"}); err != nil {
		t.Fatal(err)
	}
	if err := store.DeleteRoom(credentials.Code, credentials.HostToken); err != nil {
		t.Fatal(err)
	}
	writer := &recordingSSEWriter{header: make(http.Header)}
	hub.serveSubscriptionHTTP(context.Background(), writer, sub, cancel, snapshot)
	if writer.body.String() != "id: 0\nevent: queue_snapshot\ndata: {\"current\":null,\"queue\":[]}\n\nid: 1\nevent: queue_updated\ndata: {\"queue\":[{\"id\":\"queued\",\"url\":\"\",\"title\":\"\",\"artist\":\"\",\"duration_sec\":0,\"resolved_by\":\"\"}]}\n\nid: 2\nevent: room_closed\ndata: {\"code\":\""+credentials.Code+"\"}\n\n" {
		t.Fatalf("SSE output before handler returned = %q", writer.body.String())
	}
}

func TestDeleteRoomDeliversTerminalAfterFullSubscriberQueue(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hub := NewHub()
	NewServer(store, hub)
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	sub, snapshot, cancel, err := store.subscribeEvents(credentials.Code, hub)
	if err != nil {
		t.Fatal(err)
	}
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatal(err)
	}
	for range cap(sub.ch) {
		if _, err := store.Append(ref, Track{ID: "queued"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.DeleteRoom(credentials.Code, credentials.HostToken); err != nil {
		t.Fatal(err)
	}
	writer := &recordingSSEWriter{header: make(http.Header)}
	hub.serveSubscriptionHTTP(context.Background(), writer, sub, cancel, snapshot)
	if got := bytes.Count(writer.body.Bytes(), []byte("event: queue_updated")); got != cap(sub.ch) {
		t.Fatalf("queued events = %d, want %d", got, cap(sub.ch))
	}
	if !bytes.HasSuffix(writer.body.Bytes(), []byte("event: room_closed\ndata: {\"code\":\""+credentials.Code+"\"}\n\n")) {
		t.Fatalf("full queue displaced terminal event: %q", writer.body.String())
	}
}

// qmix#258: deletion may already be writing a terminal event, or may
// interrupt a previous write. In either case shutdown must still unblock it.
func TestDeleteRoomShutdownInterruptsBlockedSSEWriter(t *testing.T) {
	for _, tc := range []struct {
		name, blockedEvent string
		publish            bool
	}{
		{name: "blocked queued event", blockedEvent: "event: queue_updated", publish: true},
		{name: "blocked terminal event", blockedEvent: "event: room_closed"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			hub := NewHub()
			store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
			NewServer(store, hub)
			room, err := store.CreateRoom()
			if err != nil {
				t.Fatal(err)
			}
			sub, snapshot, cancelSub, err := store.subscribeEvents(room.Code, hub)
			if err != nil {
				t.Fatal(err)
			}
			writer := &shutdownBlockedWriter{
				header: make(http.Header), blockOnWrite: tc.blockedEvent,
				entered: make(chan struct{}), snapshotFlushed: make(chan struct{}),
				deadlineAdvanced: make(chan struct{}), release: make(chan struct{}),
			}
			ctx, cancelRequest := context.WithCancel(context.Background())
			finished := make(chan struct{})
			go func() {
				defer close(finished)
				hub.serveSubscriptionHTTP(ctx, writer, sub, cancelSub, snapshot)
			}()
			t.Cleanup(func() {
				close(writer.release)
				cancelRequest()
				select {
				case <-finished:
				case <-time.After(2 * time.Second):
					t.Error("blocked SSE handler did not finish after cleanup")
				}
			})
			select {
			case <-writer.snapshotFlushed:
			case <-time.After(2 * time.Second):
				t.Fatal("snapshot did not flush")
			}
			if tc.publish {
				ref, err := store.AppendPreflight(room.Code)
				if err != nil {
					t.Fatal(err)
				}
				if _, err := store.Append(ref, Track{ID: "queued"}); err != nil {
					t.Fatal(err)
				}
				select {
				case <-writer.entered:
				case <-time.After(2 * time.Second):
					t.Fatal("queued event write did not block")
				}
			}
			if err := store.DeleteRoom(room.Code, room.HostToken); err != nil {
				t.Fatal(err)
			}
			if !tc.publish {
				select {
				case <-writer.entered:
				case <-time.After(2 * time.Second):
					t.Fatal("terminal event write did not block")
				}
			}
			hub.StopSubscriptions()
			select {
			case <-finished:
			case <-time.After(300 * time.Millisecond):
				t.Fatal("deleted-room SSE write outlived shutdown")
			}
			hub.mu.Lock()
			closing := len(hub.closing)
			hub.mu.Unlock()
			if closing != 0 {
				t.Fatalf("shutdown retained %d terminal writers", closing)
			}
		})
	}
}

func TestDeleteRoomHTTPObservationUsesSafeRouteAndStatus(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, &slog.HandlerOptions{Level: slog.LevelDebug}))
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	mux := newTestMux(NewServerWithLogger(store, NewHub(), logger))
	code, token := createRoom(t, mux)
	request := httptest.NewRequest(http.MethodDelete, "/rooms/"+code+"?secret=do-not-log", nil)
	request.Header.Set("X-Host-Token", token)
	response := httptest.NewRecorder()
	mux.ServeHTTP(response, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d", response.Code)
	}
	logs := output.String()
	if !strings.Contains(logs, `"method":"DELETE"`) || !strings.Contains(logs, `"route":"DELETE /rooms/{code}"`) || !strings.Contains(logs, `"status":204`) {
		t.Fatalf("missing safe deletion observation: %s", logs)
	}
	if strings.Contains(logs, token) || strings.Contains(logs, code) || strings.Contains(logs, "do-not-log") {
		t.Fatalf("deletion logs expose request data: %s", logs)
	}
}

func TestStoreDeleteRoomFreesSlotDirectly(t *testing.T) {
	store := capacityStore(t, 1, &seqCodeGen{})
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
	if err := store.DeleteRoom(credentials.Code, "wrong"); !errors.Is(err, errInvalidHostToken) {
		t.Fatalf("wrong token: %v", err)
	}
	if err := store.DeleteRoom("unknown", credentials.HostToken); !errors.Is(err, errRoomNotFound) {
		t.Fatalf("unknown: %v", err)
	}
	if err := store.DeleteRoom(credentials.Code, credentials.HostToken); err != nil {
		t.Fatal(err)
	}
	if err := store.DeleteRoom(credentials.Code, credentials.HostToken); !errors.Is(err, errRoomNotFound) {
		t.Fatalf("second delete: %v", err)
	}
	if _, err := store.CreateRoom(); err != nil {
		t.Fatalf("new room: %v", err)
	}
}
