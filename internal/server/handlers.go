package server

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/leugenea/qmix/internal/resolver"
)

// Server wires the Store and Hub to HTTP handlers.
type Server struct {
	store *Store
	hub   *Hub
	// Resolver turns source URLs into track metadata. If nil, tracks are added
	// with the URL as a stub title (pre-resolver behaviour); NewApp sets the
	// production resolver.
	Resolver resolver.Resolver
}

// NewServer returns a Server backed by the given store and hub.
func NewServer(store *Store, hub *Hub) *Server {
	return &Server{store: store, hub: hub}
}

// Routes registers all HTTP routes on mux.
func (s *Server) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /rooms", s.handleCreateRoom)
	mux.HandleFunc("GET /rooms/{code}", s.handleGetRoom)
	mux.HandleFunc("POST /rooms/{code}/queue", s.handleAddTrack)
	mux.HandleFunc("POST /rooms/{code}/skip", s.handleSkip)
	mux.HandleFunc("PATCH /rooms/{code}/queue", s.handleReorder)
	mux.HandleFunc("GET /rooms/{code}/events", s.handleEvents)
}

// writeJSON writes v as JSON with the given status code.
func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeError writes a human-readable error body.
func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// decodeJSON decodes the request body into v, returning a 400 on failure.
func decodeJSON(w http.ResponseWriter, r *http.Request, v interface{}) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json body")
		return false
	}
	return true
}

// hostToken returns the X-Host-Token header value.
func hostToken(r *http.Request) string {
	return r.Header.Get("X-Host-Token")
}

// handleCreateRoom creates a room and returns its code, host token and URL.
func (s *Server) handleCreateRoom(w http.ResponseWriter, r *http.Request) {
	room := s.store.CreateRoom()
	writeJSON(w, http.StatusCreated, map[string]string{
		"code":       room.Code,
		"host_token": room.HostToken,
		"url":        "/r/" + room.Code,
	})
}

// handleGetRoom returns the room state without the host token.
func (s *Server) handleGetRoom(w http.ResponseWriter, r *http.Request) {
	room := s.store.Get(r.PathValue("code"))
	if room == nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	s.store.mu.Lock()
	v := viewRoom(room)
	s.store.mu.Unlock()
	writeJSON(w, http.StatusOK, v)
}

// viewRoom is the public representation of a room (no host token).
type roomView struct {
	Code    string   `json:"code"`
	Current *curView `json:"current"`
	Queue   []Track  `json:"queue"`
}

type curView struct {
	TrackID string `json:"track_id"`
	PosSec  int    `json:"pos_sec"`
	State   string `json:"state"`
}

func viewRoom(room *Room) roomView {
	v := roomView{Code: room.Code, Queue: cloneTracks(room.Queue)}
	if room.Current != nil {
		c := *room.Current
		v.Current = &curView{TrackID: c.TrackID, PosSec: c.PosSec, State: c.State}
	}
	return v
}

// handleAddTrack appends a track to the queue. Open to guests. When a resolver
// is wired in, the URL is resolved to track metadata first; unresolvable links
// return 422 (bad link) or 502 (upstream service failure).
func (s *Server) handleAddTrack(w http.ResponseWriter, r *http.Request) {
	room := s.store.Get(r.PathValue("code"))
	if room == nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}

	var req struct {
		URL string `json:"url"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.URL) == "" {
		writeError(w, http.StatusBadRequest, "url must not be empty")
		return
	}

	track := trackFromURL(req.URL)
	if s.Resolver != nil {
		meta, err := s.Resolver.Resolve(r.Context(), req.URL)
		if err != nil {
			status, msg := resolveError(err)
			writeError(w, status, msg)
			return
		}
		track = Track{
			ID:          newTrackID(),
			URL:         req.URL,
			Title:       meta.Title,
			Artist:      meta.Artist,
			DurationSec: meta.DurationSec,
			ResolvedBy:  meta.ResolvedBy,
		}
	}

	s.store.mu.Lock()
	room.Queue = append(room.Queue, track)
	s.store.touch(room)
	payload := queuePayload(room)
	s.store.mu.Unlock()

	s.hub.Publish(room.Code, "queue_updated", payload)
	writeJSON(w, http.StatusCreated, track)
}

// trackFromURL returns the pre-resolver stub track (title == URL).
func trackFromURL(u string) Track {
	return Track{ID: newTrackID(), URL: u, Title: u}
}

// resolveError maps a resolver failure to an HTTP status and a human-readable
// message. Unresolvable links (invalid, unsupported, no anonymous path) become
// 422; transient upstream failures become 502. Nothing is ever 500.
func resolveError(err error) (int, string) {
	switch {
	case errors.Is(err, resolver.ErrInvalid),
		errors.Is(err, resolver.ErrUnsupported),
		errors.Is(err, resolver.ErrNoAnonymous):
		return http.StatusUnprocessableEntity, err.Error()
	default:
		return http.StatusBadGateway, err.Error()
	}
}

// handleSkip advances to the next track. Host-only.
func (s *Server) handleSkip(w http.ResponseWriter, r *http.Request) {
	room := s.store.Get(r.PathValue("code"))
	if room == nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	if hostToken(r) != room.HostToken {
		writeError(w, http.StatusForbidden, "invalid host token")
		return
	}

	s.store.mu.Lock()
	if len(room.Queue) == 0 {
		s.store.mu.Unlock()
		writeError(w, http.StatusBadRequest, "queue is empty")
		return
	}
	next := room.Queue[0]
	room.Queue = room.Queue[1:]
	room.Current = &Current{TrackID: next.ID, PosSec: 0, State: "playing"}
	s.store.touch(room)
	cur := *room.Current
	payload := currentPayload(&cur)
	s.store.mu.Unlock()

	s.hub.Publish(room.Code, "track_changed", payload)
	s.hub.Publish(room.Code, "player_state", map[string]string{"state": cur.State})
	writeJSON(w, http.StatusOK, map[string]interface{}{"current": payload})
}

// handleReorder sets a new queue order. Host-only. The order must be an exact
// permutation of the current track IDs.
func (s *Server) handleReorder(w http.ResponseWriter, r *http.Request) {
	room := s.store.Get(r.PathValue("code"))
	if room == nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	if hostToken(r) != room.HostToken {
		writeError(w, http.StatusForbidden, "invalid host token")
		return
	}

	var req struct {
		Order []string `json:"order"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}

	s.store.mu.Lock()
	if !isPermutation(req.Order, room.Queue) {
		s.store.mu.Unlock()
		writeError(w, http.StatusBadRequest, "order must be a permutation of current track ids")
		return
	}
	byID := make(map[string]Track, len(room.Queue))
	for _, t := range room.Queue {
		byID[t.ID] = t
	}
	newQueue := make([]Track, 0, len(req.Order))
	for _, id := range req.Order {
		newQueue = append(newQueue, byID[id])
	}
	room.Queue = newQueue
	s.store.touch(room)
	payload := queuePayload(room)
	queueCopy := cloneTracks(newQueue)
	s.store.mu.Unlock()

	s.hub.Publish(room.Code, "queue_updated", payload)
	writeJSON(w, http.StatusOK, map[string]interface{}{"queue": queueCopy})
}

// isPermutation reports whether ids is an exact permutation of the track IDs
// in queue (same multiset, same length).
func isPermutation(ids []string, queue []Track) bool {
	if len(ids) != len(queue) {
		return false
	}
	counts := make(map[string]int, len(queue))
	for _, t := range queue {
		counts[t.ID]++
	}
	for _, id := range ids {
		if counts[id] == 0 {
			return false
		}
		counts[id]--
	}
	return true
}

// handleEvents streams SSE events for a room.
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	room := s.store.Get(r.PathValue("code"))
	if room == nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	s.hub.ServeHTTP(r.Context(), w, room.Code, func() interface{} {
		s.store.mu.Lock()
		defer s.store.mu.Unlock()
		return snapshotPayload(room)
	})
}

// cloneTracks returns a copy of a queue slice so payloads stay safe to use
// outside the store lock.
func cloneTracks(q []Track) []Track {
	out := make([]Track, len(q))
	copy(out, q)
	return out
}

// queuePayload is the full queue for queue_updated events. It deep-copies the
// queue; callers must hold s.store.mu.
func queuePayload(room *Room) map[string]interface{} {
	return map[string]interface{}{"queue": cloneTracks(room.Queue)}
}

// currentPayload is the current track for track_changed events.
func currentPayload(cur *Current) map[string]interface{} {
	return map[string]interface{}{
		"track_id": cur.TrackID,
		"pos_sec":  cur.PosSec,
		"state":    cur.State,
	}
}

// snapshotPayload is the full room state for queue_snapshot events. It
// deep-copies mutable state; callers must hold s.store.mu.
func snapshotPayload(room *Room) map[string]interface{} {
	var cur interface{}
	if room.Current != nil {
		c := *room.Current
		cur = currentPayload(&c)
	}
	return map[string]interface{}{
		"current": cur,
		"queue":   cloneTracks(room.Queue),
	}
}

// newTrackID returns a unique-ish track id.
func newTrackID() string {
	return newToken()
}
