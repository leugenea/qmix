package server

import (
	"context"
	"encoding/json"
	"fmt"
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
// mutations. Overflowing events are dropped — the client recovers via a
// queue_snapshot on reconnect.
type Hub struct {
	mu        sync.Mutex
	rooms     map[string]*roomHub
	Heartbeat time.Duration // keep-alive comment interval; <=0 means the 15s default
}

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
	return &Hub{rooms: make(map[string]*roomHub), Heartbeat: 15 * time.Second}
}

// Subscribe registers a subscriber for a room and returns the channel to read
// events from. The returned cancel function unsubscribes and closes the channel.
func (h *Hub) Subscribe(roomCode string) (<-chan Event, func()) {
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
			h.mu.Lock()
			defer h.mu.Unlock()
			if rh, ok := h.rooms[roomCode]; ok {
				delete(rh.subs, sub)
				if len(rh.subs) == 0 {
					delete(h.rooms, roomCode)
				}
			}
			close(sub.done)
		})
	}
	return sub.ch, cancel
}

// Publish sends an event to all subscribers of a room. Sends are non-blocking:
// a full subscriber buffer causes the event to be dropped for that client.
func (h *Hub) Publish(roomCode string, name string, data interface{}) {
	h.mu.Lock()
	rh := h.rooms[roomCode]
	if rh == nil {
		h.mu.Unlock()
		return
	}
	id := rh.seq + 1
	rh.seq = id
	ev := Event{ID: id, Name: name, Data: data}
	subs := make([]*subscriber, 0, len(rh.subs))
	for s := range rh.subs {
		subs = append(subs, s)
	}
	h.mu.Unlock()

	for _, s := range subs {
		select {
		case s.ch <- ev:
		default:
			// Buffer full: drop the event. Client recovers via snapshot.
		}
	}
}

// ServeHTTP streams SSE events for a room to the client. It sends a
// queue_snapshot immediately (or on reconnect with Last-Event-ID), then
// forwards live events. It blocks until ctx is done or the client disconnects.
func (h *Hub) ServeHTTP(ctx context.Context, w http.ResponseWriter, roomCode string, snapshot func() interface{}) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	ch, cancel := h.Subscribe(roomCode)
	defer cancel()

	// Always send a fresh snapshot on connect/reconnect.
	writeEvent(w, Event{ID: h.currentID(roomCode), Name: "queue_snapshot", Data: snapshot()})
	flusher.Flush()

	heartbeat := time.NewTicker(h.heartbeatInterval())
	defer heartbeat.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-heartbeat.C:
			_, _ = fmt.Fprint(w, ": ping\n\n")
			flusher.Flush()
		case ev := <-ch:
			writeEvent(w, ev)
			flusher.Flush()
		}
	}
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
func writeEvent(w http.ResponseWriter, ev Event) {
	_, _ = fmt.Fprintf(w, "id: %d\n", ev.ID)
	_, _ = fmt.Fprintf(w, "event: %s\n", ev.Name)
	data, err := json.Marshal(ev.Data)
	if err != nil {
		data = []byte("{}")
	}
	_, _ = fmt.Fprintf(w, "data: %s\n\n", data)
}
