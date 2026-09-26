package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"
)

// Event is a single SSE event sent to subscribers.
type Event struct {
	ID   int64
	Name string
	Data interface{}
}

// eventJSON is immutable, already-marshalled JSON; snapshots still need encoding.
type eventJSON string

// marshalEventData freezes one payload for every subscriber and Hub receiving
// the same Store mutation. Unmarshalable data keeps the legacy {} wire fallback.
func marshalEventData(data interface{}) eventJSON {
	if encoded, ok := data.(eventJSON); ok {
		return encoded
	}
	encoded, err := json.Marshal(data)
	if err != nil {
		encoded = []byte("{}")
	}
	return eventJSON(encoded)
}

// Hub fans out SSE events to per-room subscribers. Each subscriber has a
// buffered channel; sends are non-blocking so slow clients never stall room
// mutations. An overflowing subscriber is disconnected so it recovers via a
// queue_snapshot on reconnect.
type Hub struct {
	mu           sync.Mutex
	incarnations map[roomRef]*roomHub
	Heartbeat    time.Duration // keep-alive comment interval; <=0 means the 15s default
	WriteTimeout time.Duration // per-SSE-write deadline; <=0 means the 15s default
	logger       *slog.Logger
}

// Lock order: Store operations that need both locks acquire Store.mu before
// Hub.mu. Hub methods never call Store methods or snapshot callbacks while
// holding Hub.mu. Store mutations retain Store.mu through non-blocking publication
// so committed state and event sequencing share one boundary.

// roomHub holds the subscribers and the monotonic event counter for one room.
type roomHub struct {
	seq  int64
	subs map[*subscriber]struct{}
}

// subscriber is a single SSE connection.
type subscriber struct {
	ch   chan Event
	done chan struct{}
}

// NewHub returns an empty Hub.
func NewHub() *Hub {
	return NewHubWithLogger(discardLogger())
}

// NewHubWithLogger returns an SSE hub with stable component attribution.
func NewHubWithLogger(logger *slog.Logger) *Hub {
	if logger == nil {
		logger = discardLogger()
	}
	return &Hub{
		incarnations: make(map[roomRef]*roomHub),
		Heartbeat:    15 * time.Second,
		logger:       logger.With("component", "sse"),
	}
}

// subscribeRef registers an SSE subscriber for one exact room incarnation.
func (h *Hub) subscribeRef(ref roomRef) (*subscriber, func()) {
	h.mu.Lock()
	defer h.mu.Unlock()

	rh := h.incarnations[ref]
	if rh == nil {
		rh = &roomHub{subs: make(map[*subscriber]struct{})}
		h.incarnations[ref] = rh
	}
	sub := &subscriber{ch: make(chan Event, 16), done: make(chan struct{})}
	rh.subs[sub] = struct{}{}

	var once sync.Once
	cancel := func() {
		once.Do(func() {
			h.disconnectRef(ref, sub)
		})
	}
	return sub, cancel
}

func (h *Hub) disconnectRef(ref roomRef, sub *subscriber) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.disconnectRefLocked(ref, sub)
}

func (h *Hub) disconnectRefLocked(ref roomRef, sub *subscriber) {
	rh := h.incarnations[ref]
	if rh == nil {
		return
	}
	if _, ok := rh.subs[sub]; !ok {
		return
	}
	delete(rh.subs, sub)
	close(sub.done)
	if len(rh.subs) == 0 {
		delete(h.incarnations, ref)
	}
}

// invalidateRef disconnects every subscriber to one deleted room incarnation
// before that code can be reused.
func (h *Hub) invalidateRef(ref roomRef) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.invalidateRoomHubLocked(h.incarnations[ref])
	delete(h.incarnations, ref)
}

func (h *Hub) invalidateRoomHubLocked(rh *roomHub) {
	if rh == nil {
		return
	}
	for sub := range rh.subs {
		delete(rh.subs, sub)
		close(sub.done)
	}
}

// publishRef sequences an event for one room incarnation.
func (h *Hub) publishRef(ref roomRef, name string, data interface{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.publishRoomLocked(h.incarnations[ref], name, data, func(sub *subscriber) {
		h.disconnectRefLocked(ref, sub)
	})
}

func (h *Hub) publishRoomLocked(rh *roomHub, name string, data interface{}, disconnect func(*subscriber)) {
	if rh == nil {
		return
	}
	id := rh.seq + 1
	rh.seq = id
	event := Event{ID: id, Name: name, Data: marshalEventData(data)}
	for sub := range rh.subs {
		select {
		case sub.ch <- event:
		default:
			disconnect(sub)
		}
	}
}

// serveSubscriptionHTTP streams a Store-created incarnation subscription and
// its snapshot. Both must come from Store.subscribeEvents for this Hub.
func (h *Hub) serveSubscriptionHTTP(ctx context.Context, w http.ResponseWriter, sub *subscriber, cancel func(), snapshot eventSnapshot) {
	_ = h.serveSubscriberHTTP(ctx, w, sub, cancel, snapshot.ID, snapshot.Data)
}

func (h *Hub) serveSubscriberHTTP(ctx context.Context, w http.ResponseWriter, sub *subscriber, cancel func(), snapshotID int64, snapshotData interface{}) error {
	defer cancel()

	if _, ok := w.(http.Flusher); !ok {
		h.logger.Warn("SSE transport unavailable", "operation", "connect", "error_kind", "flusher_unavailable")
		writeError(w, http.StatusInternalServerError, "streaming_unsupported", "streaming unsupported")
		return nil
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	controller := http.NewResponseController(w)
	if h.setWriteDeadline(controller) != nil ||
		writeEvent(w, Event{ID: snapshotID, Name: "queue_snapshot", Data: snapshotData}) != nil ||
		controller.Flush() != nil {
		return nil
	}

	heartbeat := time.NewTicker(h.heartbeatInterval())
	defer heartbeat.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-sub.done:
			return nil
		case <-heartbeat.C:
			if h.setWriteDeadline(controller) != nil {
				return nil
			}
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return nil
			}
			if controller.Flush() != nil {
				return nil
			}
		case ev := <-sub.ch:
			select {
			case <-sub.done:
				return nil
			default:
			}
			if ev.ID <= snapshotID {
				continue
			}
			if h.setWriteDeadline(controller) != nil || writeEvent(w, ev) != nil || controller.Flush() != nil {
				return nil
			}
		}
	}
}

// setWriteDeadline prevents a client that stops reading from pinning an SSE
// handler forever. A fresh deadline is applied to each event or heartbeat.
func (h *Hub) setWriteDeadline(controller *http.ResponseController) error {
	timeout := h.WriteTimeout
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	return controller.SetWriteDeadline(time.Now().Add(timeout))
}

func (h *Hub) currentIDRef(ref roomRef) int64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	if rh := h.incarnations[ref]; rh != nil {
		return rh.seq
	}
	return 0
}

// heartbeatInterval returns the SSE keep-alive interval. A non-positive
// Heartbeat falls back to the 15s default.
func (h *Hub) heartbeatInterval() time.Duration {
	if h.Heartbeat <= 0 {
		return 15 * time.Second
	}
	return h.Heartbeat
}

// writeEvent writes one SSE event in the format:
//
//	id: <n>
//	event: <name>
//	data: <json>
func writeEvent(w http.ResponseWriter, ev Event) error {
	if _, err := fmt.Fprintf(w, "id: %d\n", ev.ID); err != nil {
		return err
	}
	if _, err := fmt.Fprintf(w, "event: %s\n", ev.Name); err != nil {
		return err
	}
	data, ok := ev.Data.(eventJSON)
	if !ok {
		encoded, err := json.Marshal(ev.Data)
		if err != nil {
			encoded = []byte("{}")
		}
		data = eventJSON(encoded)
	}
	_, err := fmt.Fprintf(w, "data: %s\n\n", data)
	return err
}
