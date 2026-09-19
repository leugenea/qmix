package server

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os/exec"
	"strings"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// Server wires the Store and Hub to HTTP handlers.
type Server struct {
	store *Store
	hub   *Hub

	httpLogger     *slog.Logger
	storeLogger    *slog.Logger
	resolverLogger *slog.Logger
	streamLogger   *slog.Logger
	// Resolver turns source URLs into track metadata. If nil, tracks are added
	// with the URL as a stub title (pre-resolver behaviour); NewApp sets the
	// production resolver.
	Resolver resolver.Resolver
	// StreamBackend turns the current track into an audio stream. If nil, the
	// stream endpoint returns 404 (no streaming configured). NewApp sets the
	// production yt-dlp backend.
	StreamBackend stream.StreamBackend
}

// NewServer returns a Server backed by the given store and hub.
func NewServer(store *Store, hub *Hub) *Server {
	return NewServerWithLogger(store, hub, discardLogger())
}

// NewServerWithLogger returns a Server whose subsystem records share one
// process logger while retaining stable component attribution.
func NewServerWithLogger(store *Store, hub *Hub, logger *slog.Logger) *Server {
	if logger == nil {
		logger = discardLogger()
	}
	server := &Server{
		store:          store,
		hub:            hub,
		httpLogger:     logger.With("component", "server/http"),
		storeLogger:    logger.With("component", "store/rooms"),
		resolverLogger: logger.With("component", "resolver"),
		streamLogger:   logger.With("component", "stream"),
	}
	if store != nil {
		store.bindEvents(hub)
	}
	return server
}

// Routes registers all HTTP routes on mux.
func (s *Server) Routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /assets/guest.js", handleGuestScript)
	mux.HandleFunc("GET /assets/guest.webmanifest", handleGuestManifest)
	mux.HandleFunc("GET /assets/qmix-192.svg", handleGuestIcon(guestIcon192))
	mux.HandleFunc("GET /assets/qmix-512.svg", handleGuestIcon(guestIcon512))
	mux.HandleFunc("POST /rooms", s.observeHTTP(s.handleCreateRoom))
	mux.HandleFunc("GET /rooms/{code}", s.observeHTTP(s.handleGetRoom))
	mux.HandleFunc("POST /rooms/{code}/queue", s.observeHTTP(s.handleAddTrack))
	mux.HandleFunc("GET /r/{code}", s.observeHTTP(s.handleGuestPage))
	mux.HandleFunc("POST /r/{code}/queue", s.observeHTTP(s.handleGuestAddTrack))
	mux.HandleFunc("POST /rooms/{code}/skip", s.observeHTTP(s.handleSkip))
	mux.HandleFunc("PATCH /rooms/{code}/queue", s.observeHTTP(s.handleReorder))
	mux.HandleFunc("GET /rooms/{code}/events", s.observeHTTP(s.handleEvents))
	mux.HandleFunc("GET /rooms/{code}/current/stream", s.observeHTTP(s.handleStream))
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

const maxAddTrackBodyBytes int64 = 4 << 10
const maxQueueLength = 100

var (
	errRoomNotFound     = errors.New("room not found")
	errQueueFull        = errors.New("queue is full")
	errQueueEmpty       = errors.New("queue is empty")
	errInvalidHostToken = errors.New("invalid host token")
	errInvalidOrder     = errors.New("order must be a permutation of current track ids")
)

func decodeAddTrackJSON(w http.ResponseWriter, r *http.Request, v interface{}) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxAddTrackBodyBytes)
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(v); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		if err == nil {
			return errors.New("request body must contain one json value")
		}
		return err
	}
	return nil
}

func isRequestTooLarge(err error) bool {
	var maxBytesErr *http.MaxBytesError
	return errors.As(err, &maxBytesErr)
}

// handleCreateRoom creates a room and returns its code, host token and URL.
func (s *Server) handleCreateRoom(w http.ResponseWriter, r *http.Request) {
	credentials, err := s.store.CreateRoom()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create room")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{
		"code":       credentials.Code,
		"host_token": credentials.HostToken,
		"url":        "/r/" + credentials.Code,
	})
}

// handleGetRoom returns the room state without the host token.
func (s *Server) handleGetRoom(w http.ResponseWriter, r *http.Request) {
	view, err := s.store.View(r.PathValue("code"))
	if err != nil {
		s.storeLogger.Debug("room lookup failed", "operation", "get", "error_kind", "not_found")
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	writeJSON(w, http.StatusOK, view)
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
	Title   string `json:"title"`
	Artist  string `json:"artist"`
}

func viewRoom(room *Room) roomView {
	v := roomView{Code: room.Code, Queue: cloneTracks(room.Queue)}
	if room.Current != nil {
		c := *room.Current
		v.Current = &curView{TrackID: c.TrackID, PosSec: c.PosSec, State: c.State, Title: c.Title, Artist: c.Artist}
	}
	return v
}

// handleAddTrack appends a track to the queue. Open to guests. When a resolver
// is wired in, the URL is resolved to track metadata first; unresolvable links
// return 422 (bad link) or 502 (upstream service failure).
func (s *Server) handleAddTrack(w http.ResponseWriter, r *http.Request) {
	ref, err := s.store.AppendPreflight(r.PathValue("code"))
	if err != nil {
		if errors.Is(err, errQueueFull) {
			writeError(w, http.StatusConflict, errQueueFull.Error())
		} else {
			writeError(w, http.StatusNotFound, errRoomNotFound.Error())
		}
		return
	}

	var req struct {
		URL string `json:"url"`
	}
	if err := decodeAddTrackJSON(w, r, &req); err != nil {
		if isRequestTooLarge(err) {
			writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		writeError(w, http.StatusBadRequest, "invalid json body")
		return
	}
	if strings.TrimSpace(req.URL) == "" {
		writeError(w, http.StatusBadRequest, "url must not be empty")
		return
	}
	if err := s.store.CheckAppend(ref); errors.Is(err, errQueueFull) {
		writeError(w, http.StatusConflict, errQueueFull.Error())
		return
	} else if err != nil {
		writeError(w, http.StatusNotFound, errRoomNotFound.Error())
		return
	}

	track, err := s.trackFromRequest(r.Context(), req.URL)
	if err != nil {
		s.logResolverFailure(err)
		status, msg := resolveError(err)
		if status == http.StatusServiceUnavailable {
			w.Header().Set("Retry-After", retryAfterOverloaded)
		}
		writeError(w, status, msg)
		return
	}

	track, err = s.store.Append(ref, track)
	if errors.Is(err, errQueueFull) {
		writeError(w, http.StatusConflict, errQueueFull.Error())
		return
	}
	if err != nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	writeJSON(w, http.StatusCreated, track)
}

// trackFromRequest resolves rawurl into a track via the resolver. Without a
// resolver it returns the pre-resolver stub (title == URL).
func (s *Server) trackFromRequest(ctx context.Context, rawurl string) (Track, error) {
	if s.Resolver == nil {
		return trackFromURL(rawurl), nil
	}
	meta, err := s.Resolver.Resolve(ctx, rawurl)
	if err != nil {
		return Track{}, err
	}
	return Track{
		ID:          newTrackID(),
		URL:         rawurl,
		Title:       meta.Title,
		Artist:      meta.Artist,
		DurationSec: meta.DurationSec,
		ResolvedBy:  meta.ResolvedBy,
	}, nil
}

// trackFromURL returns the pre-resolver stub track (title == URL).
func trackFromURL(u string) Track {
	return Track{ID: newTrackID(), URL: u, Title: u}
}

// retryAfterOverloaded is the fixed Retry-After seconds sent with 503
// overload responses (qmix#130). It is deliberately short and documented:
// capacity frees as soon as one yt-dlp subprocess finishes, and clients that
// honor it naturally smooth bursts without hammering the endpoint.
const retryAfterOverloaded = "3"

// isOverloaded reports whether err is the typed yt-dlp capacity rejection.
func isOverloaded(err error) bool {
	return errors.Is(err, ytdlpcap.ErrOverloaded)
}

// resolveError maps a resolver failure to an HTTP status and a human-readable
// message. Unresolvable links (invalid, unsupported, no anonymous path) become
// 422; transient upstream failures become 502. Overload of the shared
// yt-dlp capacity becomes 503 (qmix#130). Nothing is ever 500.
func resolveError(err error) (int, string) {
	switch {
	case isOverloaded(err):
		return http.StatusServiceUnavailable, err.Error()
	case errors.Is(err, resolver.ErrInvalid),
		errors.Is(err, resolver.ErrUnsupported),
		errors.Is(err, resolver.ErrNoAnonymous):
		return http.StatusUnprocessableEntity, err.Error()
	default:
		return http.StatusBadGateway, err.Error()
	}
}

// guestError is the guest API error body: a machine-readable code for the
// frontend plus a human-readable message for the snackbar.
type guestError struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// writeGuestError writes a guest API error with the given status and code.
func writeGuestError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, guestError{Error: code, Message: msg})
}

// handleGuestAddTrack is the guest-facing POST /r/{code}/queue endpoint. It
// adds a link to the room queue like handleAddTrack, but responses carry
// machine-readable status codes for the guest UI.
func (s *Server) handleGuestAddTrack(w http.ResponseWriter, r *http.Request) {
	ref, err := s.store.AppendPreflight(r.PathValue("code"))
	if err != nil {
		if errors.Is(err, errQueueFull) {
			writeGuestError(w, http.StatusConflict, "queue_full", errQueueFull.Error())
		} else {
			writeGuestError(w, http.StatusNotFound, "room_not_found", "room not found")
		}
		return
	}

	var req struct {
		URL string `json:"url"`
	}
	if err := decodeAddTrackJSON(w, r, &req); err != nil {
		if isRequestTooLarge(err) {
			writeGuestError(w, http.StatusRequestEntityTooLarge, "request_too_large", "request body too large")
			return
		}
		writeGuestError(w, http.StatusBadRequest, "bad_request", "invalid json body")
		return
	}
	if strings.TrimSpace(req.URL) == "" {
		writeGuestError(w, http.StatusBadRequest, "invalid_url", "url must not be empty")
		return
	}
	if err := s.store.CheckAppend(ref); errors.Is(err, errQueueFull) {
		writeGuestError(w, http.StatusConflict, "queue_full", errQueueFull.Error())
		return
	} else if err != nil {
		writeGuestError(w, http.StatusNotFound, "room_not_found", errRoomNotFound.Error())
		return
	}

	track, err := s.trackFromRequest(r.Context(), req.URL)
	if err != nil {
		s.logResolverFailure(err)
		status, code, msg := guestResolveError(err)
		if status == http.StatusServiceUnavailable {
			w.Header().Set("Retry-After", retryAfterOverloaded)
		}
		writeGuestError(w, status, code, msg)
		return
	}

	track, err = s.store.Append(ref, track)
	if errors.Is(err, errQueueFull) {
		writeGuestError(w, http.StatusConflict, "queue_full", errQueueFull.Error())
		return
	}
	if err != nil {
		writeGuestError(w, http.StatusNotFound, "room_not_found", "room not found")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]interface{}{"status": "accepted", "track": track})
}

// guestResolveError maps a resolver failure to the guest API status, code and
// human-readable message. Unlike the host API, an unparseable link is 400
// invalid_url; unsupported and no-anonymous links are 422 unsupported_service;
// upstream failures are 502 upstream_failure. Overload of the shared yt-dlp
// capacity is 503 overloaded (qmix#130).
func guestResolveError(err error) (int, string, string) {
	switch {
	case isOverloaded(err):
		return http.StatusServiceUnavailable, "overloaded", err.Error()
	case errors.Is(err, resolver.ErrInvalid):
		return http.StatusBadRequest, "invalid_url", err.Error()
	case errors.Is(err, resolver.ErrUnsupported), errors.Is(err, resolver.ErrNoAnonymous):
		return http.StatusUnprocessableEntity, "unsupported_service", err.Error()
	default:
		return http.StatusBadGateway, "upstream_failure", err.Error()
	}
}

// handleSkip advances to the next track. Host-only.
func (s *Server) handleSkip(w http.ResponseWriter, r *http.Request) {
	payload, err := s.store.Skip(r.PathValue("code"), hostToken(r))
	if errors.Is(err, errRoomNotFound) {
		writeError(w, http.StatusNotFound, errRoomNotFound.Error())
		return
	}
	if errors.Is(err, errInvalidHostToken) {
		writeError(w, http.StatusForbidden, errInvalidHostToken.Error())
		return
	}
	if errors.Is(err, errQueueEmpty) {
		writeError(w, http.StatusBadRequest, "queue is empty")
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"current": payload})
}

// handleReorder sets a new queue order. Host-only. The order must be an exact
// permutation of the current track IDs.
func (s *Server) handleReorder(w http.ResponseWriter, r *http.Request) {
	token := hostToken(r)
	ref, err := s.store.ReorderPreflight(r.PathValue("code"), token)
	if err != nil {
		if errors.Is(err, errRoomNotFound) {
			writeError(w, http.StatusNotFound, errRoomNotFound.Error())
		} else {
			writeError(w, http.StatusForbidden, errInvalidHostToken.Error())
		}
		return
	}

	var req struct {
		Order []string `json:"order"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	queue, err := s.store.Reorder(ref, token, req.Order)
	if err != nil {
		switch {
		case errors.Is(err, errRoomNotFound):
			writeError(w, http.StatusNotFound, errRoomNotFound.Error())
		case errors.Is(err, errInvalidHostToken):
			writeError(w, http.StatusForbidden, errInvalidHostToken.Error())
		default:
			writeError(w, http.StatusBadRequest, errInvalidOrder.Error())
		}
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"queue": queue})
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
	code := r.PathValue("code")
	sub, snapshot, cancel, err := s.store.subscribeEvents(code, s.hub)
	if errors.Is(err, errRoomNotFound) {
		writeError(w, http.StatusNotFound, errRoomNotFound.Error())
		return
	}
	s.hub.serveSubscriptionHTTP(r.Context(), w, sub, cancel, snapshot)
}

// cloneTracks returns a copy of a queue slice so payloads stay safe to use
// outside the store lock.
func cloneTracks(q []Track) []Track {
	out := make([]Track, len(q))
	copy(out, q)
	return out
}

// queuePayload returns an immutable full-queue event payload.
func queuePayload(room *Room) map[string]interface{} {
	return map[string]interface{}{"queue": cloneTracks(room.Queue)}
}

// currentPayload is the current track for track_changed events.
func currentPayload(cur *Current) map[string]interface{} {
	return map[string]interface{}{
		"track_id": cur.TrackID,
		"pos_sec":  cur.PosSec,
		"state":    cur.State,
		"title":    cur.Title,
		"artist":   cur.Artist,
	}
}

// snapshotPayload returns an immutable complete room-state event payload.
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

// handleStream streams the current track's audio to the client. It delegates
// Range/seek handling to the StreamBackend via stream.ServeStream. Rooms without
// a current track, or with no stream backend configured, return 404.
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	current, err := s.store.CurrentStream(r.PathValue("code"))
	if err != nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	if s.StreamBackend == nil {
		writeError(w, http.StatusNotFound, "no stream backend configured")
		return
	}

	var track *stream.Track
	if current != nil {
		track = &stream.Track{ID: current.ID, URL: current.URL, Title: current.Title, Artist: current.Artist, ResolvedBy: current.ResolvedBy}
	}
	if track == nil || strings.TrimSpace(track.Title) == "" {
		writeError(w, http.StatusNotFound, "no current track")
		return
	}

	if err := stream.ServeStream(w, r, s.StreamBackend, track); err != nil {
		s.logStreamFailure(err)
	}
}

func (s *Server) logResolverFailure(err error) {
	args := []any{"operation", "resolve", "error_kind", resolverErrorKind(err), "cause", safeDiagnosticCause(err)}
	if errors.Is(err, resolver.ErrInvalid) || errors.Is(err, resolver.ErrUnsupported) || errors.Is(err, resolver.ErrNoAnonymous) || errors.Is(err, context.Canceled) {
		s.resolverLogger.Debug("track resolution rejected", args...)
		return
	}
	s.resolverLogger.Warn("track resolution failed", args...)
}

func (s *Server) logStreamFailure(err error) {
	args := []any{"operation", "serve", "error_kind", streamErrorKind(err), "cause", safeDiagnosticCause(err)}
	if errors.Is(err, stream.ErrNotFound) || errors.Is(err, stream.ErrInvalidRange) || errors.Is(err, stream.ErrClientWrite) || errors.Is(err, context.Canceled) {
		s.streamLogger.Debug("audio stream rejected", args...)
		return
	}
	s.streamLogger.Warn("audio stream failed", args...)
}

func resolverErrorKind(err error) string {
	switch {
	case errors.Is(err, resolver.ErrInvalid):
		return "invalid_url"
	case errors.Is(err, resolver.ErrUnsupported):
		return "unsupported_service"
	case errors.Is(err, resolver.ErrNoAnonymous):
		return "anonymous_unavailable"
	default:
		return "upstream_failure"
	}
}

func streamErrorKind(err error) string {
	switch {
	case errors.Is(err, stream.ErrClientWrite):
		return "client_disconnected"
	case errors.Is(err, stream.ErrNotFound):
		return "not_found"
	case errors.Is(err, stream.ErrInvalidRange):
		return "invalid_range"
	default:
		return "upstream_failure"
	}
}

func safeDiagnosticCause(err error) string {
	switch {
	case errors.Is(err, stream.ErrClientWrite):
		return "client_write_failed"
	case errors.Is(err, context.DeadlineExceeded):
		return "deadline_exceeded"
	case errors.Is(err, context.Canceled):
		return "canceled"
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return "process_exit"
	}
	var netErr net.Error
	if errors.As(err, &netErr) {
		if netErr.Timeout() {
			return "network_timeout"
		}
		return "network_error"
	}
	return "internal_error"
}

// newTrackID returns a unique-ish track id.
func newTrackID() string {
	return newToken()
}
