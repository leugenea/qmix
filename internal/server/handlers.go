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
	mux.HandleFunc("POST /r/{code}/queue", s.observeHTTP(s.handleAddTrack))
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

// writeError writes the uniform public API error envelope.
func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, errorEnvelope{Error: code, Message: msg})
}

// decodeJSON decodes the request body into v, returning a 400 on failure.
func decodeJSON(w http.ResponseWriter, r *http.Request, v interface{}) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid json body")
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
		writeError(w, http.StatusInternalServerError, "internal_error", "failed to create room")
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
		writeError(w, http.StatusNotFound, "room_not_found", "room not found")
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

// handleAddTrack implements queue submission for both public route aliases. The
// guest route keeps its historical success wrapper; errors are identical.
func (s *Server) handleAddTrack(w http.ResponseWriter, r *http.Request) {
	guestAlias := r.Pattern == "POST /r/{code}/queue"
	ref, err := s.store.AppendPreflight(r.PathValue("code"))
	if err != nil {
		writeQueueSubmissionError(w, err)
		return
	}

	var req struct {
		URL string `json:"url"`
	}
	if err := decodeAddTrackJSON(w, r, &req); err != nil {
		if isRequestTooLarge(err) {
			writeQueueSubmissionError(w, errRequestTooLarge)
		} else {
			writeQueueSubmissionError(w, errInvalidJSON)
		}
		return
	}
	if strings.TrimSpace(req.URL) == "" {
		writeQueueSubmissionError(w, errEmptyTrackURL)
		return
	}
	if err := s.store.CheckAppend(ref); err != nil {
		writeQueueSubmissionError(w, err)
		return
	}

	track, err := s.trackFromRequest(r.Context(), req.URL)
	if err != nil {
		s.logResolverFailure(err)
		writeQueueSubmissionError(w, err)
		return
	}

	track, err = s.store.Append(ref, track)
	if err != nil {
		writeQueueSubmissionError(w, err)
		return
	}
	if guestAlias {
		writeJSON(w, http.StatusCreated, map[string]interface{}{"status": "accepted", "track": track})
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

var (
	errInvalidJSON     = errors.New("invalid json body")
	errRequestTooLarge = errors.New("request body too large")
	errEmptyTrackURL   = errors.New("url must not be empty")
)

// apiError is the public, allowlisted error contract. Message values must never
// be built from wrapped errors, request data, URLs, paths, or credentials.
type apiError struct {
	Status     int
	Code       string
	Message    string
	RetryAfter string
}

type errorEnvelope struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// queueSubmissionError centralizes every domain/resolver failure mapping used
// by both queue route aliases.
func queueSubmissionError(err error) apiError {
	switch {
	case errors.Is(err, errRoomNotFound):
		return apiError{Status: http.StatusNotFound, Code: "room_not_found", Message: "room not found"}
	case errors.Is(err, errQueueFull):
		return apiError{Status: http.StatusConflict, Code: "queue_full", Message: "queue is full"}
	case errors.Is(err, errRequestTooLarge):
		return apiError{Status: http.StatusRequestEntityTooLarge, Code: "request_too_large", Message: "request body too large"}
	case errors.Is(err, errInvalidJSON):
		return apiError{Status: http.StatusBadRequest, Code: "bad_request", Message: "invalid json body"}
	case errors.Is(err, errEmptyTrackURL):
		return apiError{Status: http.StatusBadRequest, Code: "invalid_url", Message: "url must not be empty"}
	case isOverloaded(err):
		return apiError{Status: http.StatusServiceUnavailable, Code: "overloaded", Message: "track resolver is temporarily overloaded", RetryAfter: retryAfterOverloaded}
	case errors.Is(err, resolver.ErrInvalid):
		return apiError{Status: http.StatusBadRequest, Code: "invalid_url", Message: "invalid track url"}
	case errors.Is(err, resolver.ErrUnsupported):
		return apiError{Status: http.StatusUnprocessableEntity, Code: "unsupported_service", Message: "unsupported track service"}
	case errors.Is(err, resolver.ErrNoAnonymous):
		return apiError{Status: http.StatusUnprocessableEntity, Code: "unsupported_service", Message: "track is not publicly available"}
	default:
		return apiError{Status: http.StatusBadGateway, Code: "upstream_failure", Message: "track service is temporarily unavailable"}
	}
}

func writeQueueSubmissionError(w http.ResponseWriter, err error) {
	public := queueSubmissionError(err)
	if public.RetryAfter != "" {
		w.Header().Set("Retry-After", public.RetryAfter)
	}
	writeJSON(w, public.Status, errorEnvelope{Error: public.Code, Message: public.Message})
}

// handleSkip advances to the next track. Host-only.
func (s *Server) handleSkip(w http.ResponseWriter, r *http.Request) {
	payload, err := s.store.Skip(r.PathValue("code"), hostToken(r))
	if errors.Is(err, errRoomNotFound) {
		writeError(w, http.StatusNotFound, "room_not_found", errRoomNotFound.Error())
		return
	}
	if errors.Is(err, errInvalidHostToken) {
		writeError(w, http.StatusForbidden, "invalid_host_token", errInvalidHostToken.Error())
		return
	}
	if errors.Is(err, errQueueEmpty) {
		writeError(w, http.StatusBadRequest, "queue_empty", "queue is empty")
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
			writeError(w, http.StatusNotFound, "room_not_found", errRoomNotFound.Error())
		} else {
			writeError(w, http.StatusForbidden, "invalid_host_token", errInvalidHostToken.Error())
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
			writeError(w, http.StatusNotFound, "room_not_found", errRoomNotFound.Error())
		case errors.Is(err, errInvalidHostToken):
			writeError(w, http.StatusForbidden, "invalid_host_token", errInvalidHostToken.Error())
		default:
			writeError(w, http.StatusBadRequest, "invalid_order", errInvalidOrder.Error())
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
		writeError(w, http.StatusNotFound, "room_not_found", errRoomNotFound.Error())
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
		writeError(w, http.StatusNotFound, "room_not_found", "room not found")
		return
	}
	if s.StreamBackend == nil {
		writeError(w, http.StatusNotFound, "stream_unavailable", "no stream backend configured")
		return
	}

	var track *stream.Track
	if current != nil {
		track = &stream.Track{ID: current.ID, URL: current.URL, Title: current.Title, Artist: current.Artist, ResolvedBy: current.ResolvedBy}
	}
	if track == nil || strings.TrimSpace(track.Title) == "" {
		writeError(w, http.StatusNotFound, "no_current_track", "no current track")
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
