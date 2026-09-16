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

// Hub fans out SSE events to per-room subscribers. Each subscriber has a
// buffered channel; sends are non-blocking so slow clients never stall room
// mutations. An overflowing subscriber is disconnected so it recovers via a
// queue_snapshot on reconnect.
type Hub struct {
	mu           sync.Mutex
	rooms        map[string]*roomHub
	Heartbeat    time.Duration // keep-alive comment interval; <=0 means the 15s default
	WriteTimeout time.Duration // per-SSE-write deadline; <=0 means the 15s default
	logger       *slog.Logger
}

// Lock order: code that needs both Store.mu and Hub.mu must acquire Store.mu
// first. Hub methods never call Store methods or snapshot callbacks while
// holding Hub.mu. Mutations retain Store.mu through Publish so state changes
// and event sequencing share one boundary.

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
		rooms:     make(map[string]*roomHub),
		Heartbeat: 15 * time.Second,
		logger:    logger.With("component", "sse"),
	}
}

// Subscribe registers a subscriber for a room and returns the channel to read
// events from. The returned cancel function unsubscribes it.
func (h *Hub) Subscribe(roomCode string) (<-chan Event, func()) {
	sub, cancel := h.subscribe(roomCode)
	return sub.ch, cancel
}

// subscribe exposes the disconnect signal to the SSE transport while keeping
// the public subscription API focused on events and cancellation.
func (h *Hub) subscribe(roomCode string) (*subscriber, func()) {
	h.mu.Lock()
	defer h.mu.Unlock()

	rh := h.rooms[roomCode]
	if rh == nil {
		rh = &roomHub{subs: make(map[*subscriber]struct{})}
		h.rooms[roomCode] = rh
	}
	sub := &subscriber{ch: make(chan Event, 16), done: make(chan struct{})}
	rh.subs[sub] = struct{}{}

	var once sync.Once
	cancel := func() {
		once.Do(func() {
			h.disconnect(roomCode, sub)
		})
	}
	return sub, cancel
}

// disconnect removes a subscriber and signals its stream to stop. It is safe
// to call more than once, including after an overflow raced with cancellation.
func (h *Hub) disconnect(roomCode string, sub *subscriber) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.disconnectLocked(roomCode, sub)
}

// disconnectLocked removes a subscriber while h.mu is held.
func (h *Hub) disconnectLocked(roomCode string, sub *subscriber) {
	rh := h.rooms[roomCode]
	if rh == nil {
		return
	}
	if _, ok := rh.subs[sub]; !ok {
		return
	}
	delete(rh.subs, sub)
	close(sub.done)
	if len(rh.subs) == 0 {
		delete(h.rooms, roomCode)
	}
}

// Publish sends an event to all subscribers of a room. Sends are non-blocking:
// a full subscriber buffer disconnects that client so it can obtain a fresh
// snapshot without blocking healthy subscribers.
// Publish does not call Store methods, so callers may safely hold Store.mu to
// make room mutation and event sequencing atomic.
func (h *Hub) Publish(roomCode string, name string, data interface{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	rh := h.rooms[roomCode]
	if rh == nil {
		return
	}
	id := rh.seq + 1
	rh.seq = id
	ev := Event{ID: id, Name: name, Data: data}
	for s := range rh.subs {
		select {
		case s.ch <- ev:
		default:
			h.disconnectLocked(roomCode, s)
		}
	}
}

// ServeHTTP streams SSE events for a room to the client. It sends a
// queue_snapshot immediately (or on reconnect with Last-Event-ID), then
// forwards live events. It blocks until ctx is done or the client disconnects.
func (h *Hub) ServeHTTP(ctx context.Context, w http.ResponseWriter, roomCode string, snapshot func() (int64, interface{})) {
	if _, ok := w.(http.Flusher); !ok {
		h.logger.Warn("SSE transport unavailable", "operation", "connect", "error_kind", "flusher_unavailable")
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	sub, cancel := h.subscribe(roomCode)
	defer cancel()

	// Always send a fresh snapshot on connect/reconnect. The callback captures
	// the state and its event boundary in one Store.mu critical section.
	snapshotID, snapshotData := snapshot()
	controller := http.NewResponseController(w)
	if h.setWriteDeadline(controller) != nil ||
		writeEvent(w, Event{ID: snapshotID, Name: "queue_snapshot", Data: snapshotData}) != nil ||
		controller.Flush() != nil {
		return
	}

	heartbeat := time.NewTicker(h.heartbeatInterval())
	defer heartbeat.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-sub.done:
			return
		case <-heartbeat.C:
			if h.setWriteDeadline(controller) != nil {
				return
			}
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return
			}
			if controller.Flush() != nil {
				return
			}
		case ev := <-sub.ch:
			select {
			case <-sub.done:
				return
			default:
			}
			if ev.ID <= snapshotID {
				continue
			}
			if h.setWriteDeadline(controller) != nil || writeEvent(w, ev) != nil || controller.Flush() != nil {
				return
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

// currentID returns the latest event id for a room (0 if none yet).
func (h *Hub) currentID(roomCode string) int64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	if rh := h.rooms[roomCode]; rh != nil {
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
	data, err := json.Marshal(ev.Data)
	if err != nil {
		data = []byte("{}")
	}
	_, err = fmt.Fprintf(w, "data: %s\n\n", data)
	return err
}
