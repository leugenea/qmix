package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"reflect"
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
	incarnations map[roomRef]*roomHub
	Heartbeat    time.Duration // keep-alive comment interval; <=0 means the 15s default
	WriteTimeout time.Duration // per-SSE-write deadline; <=0 means the 15s default
	logger       *slog.Logger
}

// Lock order: Store operations that need both locks acquire Store.mu before
// Hub.mu. Hub methods never call Store methods or snapshot callbacks while
// holding Hub.mu. Store mutations retain Store.mu through non-blocking Publish
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
		rooms:        make(map[string]*roomHub),
		incarnations: make(map[roomRef]*roomHub),
		Heartbeat:    15 * time.Second,
		logger:       logger.With("component", "sse"),
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
// and every code-only compatibility subscriber before that code can be reused.
func (h *Hub) invalidateRef(ref roomRef) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.invalidateRoomHubLocked(h.incarnations[ref])
	delete(h.incarnations, ref)
	h.invalidateRoomHubLocked(h.rooms[ref.code])
	delete(h.rooms, ref.code)
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

// Publish sends an event to all subscribers of a room. Sends are non-blocking:
// a full subscriber buffer disconnects that client so it can obtain a fresh
// snapshot without blocking healthy subscribers.
// Publish does not call Store methods, so Store transactions may safely retain
// their lock through event sequencing.
func (h *Hub) Publish(roomCode string, name string, data interface{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.publishRoomLocked(h.rooms[roomCode], name, data, func(sub *subscriber) {
		h.disconnectLocked(roomCode, sub)
	})
}

// publishRef sequences an event for one room incarnation. Code-only
// subscribers are retained as generic Hub compatibility wrappers; production
// SSE subscriptions use the incarnation-specific path.
func (h *Hub) publishRef(ref roomRef, name string, data interface{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.publishRoomLocked(h.incarnations[ref], name, data, func(sub *subscriber) {
		h.disconnectRefLocked(ref, sub)
	})
	h.publishRoomLocked(h.rooms[ref.code], name, data, func(sub *subscriber) {
		h.disconnectLocked(ref.code, sub)
	})
}

func (h *Hub) publishRoomLocked(rh *roomHub, name string, data interface{}, disconnect func(*subscriber)) {
	if rh == nil {
		return
	}
	id := rh.seq + 1
	rh.seq = id
	for sub := range rh.subs {
		event := Event{ID: id, Name: name, Data: cloneEventData(data)}
		select {
		case sub.ch <- event:
		default:
			disconnect(sub)
		}
	}
}

// cloneEventData preserves the concrete event contract while recursively
// isolating mutable containers for one subscriber delivery.
func cloneEventData(data interface{}) interface{} {
	if data == nil {
		return nil
	}
	return cloneEventValue(reflect.ValueOf(data)).Interface()
}

func cloneEventValue(value reflect.Value) reflect.Value {
	if !value.IsValid() {
		return value
	}
	switch value.Kind() {
	case reflect.Interface:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		clone := cloneEventValue(value.Elem())
		result := reflect.New(value.Type()).Elem()
		result.Set(clone)
		return result
	case reflect.Pointer:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		clone := reflect.New(value.Type().Elem())
		clone.Elem().Set(cloneEventValue(value.Elem()))
		return clone
	case reflect.Map:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		clone := reflect.MakeMapWithSize(value.Type(), value.Len())
		iterator := value.MapRange()
		for iterator.Next() {
			clone.SetMapIndex(cloneEventValue(iterator.Key()), cloneEventValue(iterator.Value()))
		}
		return clone
	case reflect.Slice:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		clone := reflect.MakeSlice(value.Type(), value.Len(), value.Len())
		for index := 0; index < value.Len(); index++ {
			clone.Index(index).Set(cloneEventValue(value.Index(index)))
		}
		return clone
	case reflect.Array:
		clone := reflect.New(value.Type()).Elem()
		for index := 0; index < value.Len(); index++ {
			clone.Index(index).Set(cloneEventValue(value.Index(index)))
		}
		return clone
	case reflect.Struct:
		clone := reflect.New(value.Type()).Elem()
		clone.Set(value)
		for index := 0; index < value.NumField(); index++ {
			if !clone.Field(index).CanSet() || !value.Field(index).CanInterface() {
				continue
			}
			clone.Field(index).Set(cloneEventValue(value.Field(index)))
		}
		return clone
	default:
		return value
	}
}

// ServeHTTP streams SSE events using an infallible snapshot callback.
func (h *Hub) ServeHTTP(ctx context.Context, w http.ResponseWriter, roomCode string, snapshot func() (int64, interface{})) {
	_ = h.serveHTTP(ctx, w, roomCode, func() (int64, interface{}, error) {
		id, data := snapshot()
		return id, data, nil
	})
}

// ServeRoomHTTP streams SSE events and returns a snapshot lookup error before
// response headers are committed. This lets handlers preserve a 404 if a room
// expires between routing and the atomic snapshot boundary.
func (h *Hub) ServeRoomHTTP(ctx context.Context, w http.ResponseWriter, roomCode string, snapshot func() (int64, interface{}, error)) error {
	return h.serveHTTP(ctx, w, roomCode, snapshot)
}

func (h *Hub) serveHTTP(ctx context.Context, w http.ResponseWriter, roomCode string, snapshot func() (int64, interface{}, error)) error {
	sub, cancel := h.subscribe(roomCode)

	// Capture state and its event boundary after subscription. Store operations
	// hold Store.mu through publication, so covered events are skipped below and
	// later events remain queued for delivery.
	snapshotID, snapshotData, err := snapshot()
	if err != nil {
		cancel()
		return err
	}
	return h.serveSubscriberHTTP(ctx, w, sub, cancel, snapshotID, snapshotData)
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

// currentID returns the latest event id for a room (0 if none yet).
func (h *Hub) currentID(roomCode string) int64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	if rh := h.rooms[roomCode]; rh != nil {
		return rh.seq
	}
	return 0
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
	data, err := json.Marshal(ev.Data)
	if err != nil {
		data = []byte("{}")
	}
	_, err = fmt.Fprintf(w, "data: %s\n\n", data)
	return err
}
