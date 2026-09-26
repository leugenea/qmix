package server

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"
)

// countingPayload proves encoding occurs at publication, not once per stream.
type countingPayload struct{ calls *int }

func (p countingPayload) MarshalJSON() ([]byte, error) {
	*p.calls++
	return []byte(`{"version":1}`), nil
}

func TestPublishEncodesOnceForAllSubscribers(t *testing.T) {
	hub := NewHub()
	ref := roomRef{code: "room", generation: 1}
	first, cancelFirst := hub.subscribeRef(ref)
	defer cancelFirst()
	second, cancelSecond := hub.subscribeRef(ref)
	defer cancelSecond()
	calls := 0
	hub.publishRef(ref, "queue_updated", countingPayload{calls: &calls})
	if calls != 1 {
		t.Fatalf("marshal calls at publication = %d, want 1", calls)
	}
	for _, sub := range []*subscriber{first, second} {
		recorder := httptest.NewRecorder()
		if err := writeEvent(recorder, <-sub.ch); err != nil {
			t.Fatal(err)
		}
		if got, want := recorder.Body.String(), "id: 1\nevent: queue_updated\ndata: {\"version\":1}\n\n"; got != want {
			t.Fatalf("wire bytes = %q, want %q", got, want)
		}
	}
	if calls != 1 {
		t.Fatalf("marshal calls after both writes = %d, want 1", calls)
	}
}

func TestStoreFanoutEncodesOnceAcrossHubs(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	firstHub, secondHub := NewHub(), NewHub()
	NewServer(store, firstHub)
	NewServer(store, secondHub)
	room := mustCreateRoom(t, store)
	first, cancelFirst := subscribeTestEvents(t, store, firstHub, room.Code)
	defer cancelFirst()
	second, cancelSecond := subscribeTestEvents(t, store, secondHub, room.Code)
	defer cancelSecond()
	calls := 0
	store.mu.Lock()
	store.publishLocked(room, "queue_updated", countingPayload{calls: &calls})
	store.mu.Unlock()
	if calls != 1 {
		t.Fatalf("marshal calls across hubs = %d, want 1", calls)
	}
	for _, events := range []<-chan Event{first, second} {
		recorder := httptest.NewRecorder()
		if err := writeEvent(recorder, <-events); err != nil {
			t.Fatal(err)
		}
		if got, want := recorder.Body.String(), "id: 1\nevent: queue_updated\ndata: {\"version\":1}\n\n"; got != want {
			t.Fatalf("wire bytes = %q, want %q", got, want)
		}
	}
}

func TestPublishedMarshalErrorUsesLegacyFallback(t *testing.T) {
	hub := NewHub()
	ref := roomRef{code: "room", generation: 1}
	sub, cancel := hub.subscribeRef(ref)
	defer cancel()
	hub.publishRef(ref, "queue_updated", make(chan int))
	recorder := httptest.NewRecorder()
	if err := writeEvent(recorder, <-sub.ch); err != nil {
		t.Fatal(err)
	}
	if got, want := recorder.Body.String(), "id: 1\nevent: queue_updated\ndata: {}\n\n"; got != want {
		t.Fatalf("wire bytes = %q, want %q", got, want)
	}
}

// subscribeTestEvents uses the same atomic Store/Hub boundary as HTTP SSE.
func subscribeTestEvents(t *testing.T, store *Store, hub *Hub, code string) (<-chan Event, func()) {
	t.Helper()
	sub, _, cancel, err := store.subscribeEvents(code, hub)
	if err != nil {
		t.Fatalf("subscribeEvents: %v", err)
	}
	return sub.ch, cancel
}

func decodeEventPayload[T any](t *testing.T, ev Event) T {
	t.Helper()
	var payload T
	if err := json.Unmarshal([]byte(ev.Data.(eventJSON)), &payload); err != nil {
		t.Fatalf("decode published event: %v", err)
	}
	return payload
}

// TestPublishedEventWireBytes pins the legacy writer's exact JSON and SSE
// framing before publish-time serialization replaces per-subscriber cloning.
func TestPublishedEventWireBytes(t *testing.T) {
	tests := []struct {
		name string
		data interface{}
		want string
	}{
		{"queue_updated", map[string]interface{}{"queue": []Track{{ID: "one", Title: "Track"}}}, "id: 1\nevent: queue_updated\ndata: {\"queue\":[{\"id\":\"one\",\"url\":\"\",\"title\":\"Track\",\"artist\":\"\",\"duration_sec\":0,\"resolved_by\":\"\"}]}\n\n"},
		{"track_changed", map[string]interface{}{"current": map[string]interface{}{"track_id": "one", "state": "playing"}}, "id: 1\nevent: track_changed\ndata: {\"current\":{\"state\":\"playing\",\"track_id\":\"one\"}}\n\n"},
		{"player_state", map[string]interface{}{"track_id": "one", "state": "paused", "pos_sec": 7}, "id: 1\nevent: player_state\ndata: {\"pos_sec\":7,\"state\":\"paused\",\"track_id\":\"one\"}\n\n"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			hub := NewHub()
			ref := roomRef{code: "room", generation: 1}
			sub, cancel := hub.subscribeRef(ref)
			defer cancel()
			hub.publishRef(ref, tc.name, tc.data)
			recorder := httptest.NewRecorder()
			if err := writeEvent(recorder, <-sub.ch); err != nil {
				t.Fatalf("writeEvent: %v", err)
			}
			if got := recorder.Body.String(); got != tc.want {
				t.Fatalf("wire bytes = %q, want %q", got, tc.want)
			}
		})
	}
}
