package server

import (
	"crypto/rand"
	"io"
	"sync"
	"time"

	"github.com/leugenea/qmix/internal/admission"
	"github.com/leugenea/qmix/internal/roomsubmission"
)

const defaultNonEmptyRoomTTL = 24 * time.Hour

// Store owns all room identity, authorization, lifecycle, queue mutation, and
// event snapshot boundaries. Callers receive values and immutable snapshots;
// mutable Room pointers never leave Store operations in production code.
type Store struct {
	mu    sync.Mutex
	rooms map[string]*Room

	TTL         time.Duration
	NonEmptyTTL time.Duration
	Tick        time.Duration

	gen         CodeGenerator
	tokenRandom io.Reader
	nextGen     uint64
	hubs        map[*Hub]struct{}

	submissionRate  int
	submissionBurst int
	submissionNow   func() time.Time
}

// RoomCredentials is the immutable result of room creation.
type RoomCredentials struct {
	Code      string
	HostToken string
}

// roomRef identifies one incarnation of a room without exposing mutable state.
type roomRef struct {
	code       string
	generation uint64
}

// eventSnapshot captures room state and its SSE event boundary atomically.
type eventSnapshot struct {
	ID   int64
	Data map[string]interface{}
}

// currentStreamView is the immutable input needed for stream lookup.
type currentStreamView struct {
	ID         string
	URL        string
	Title      string
	Artist     string
	ResolvedBy string
}

func NewStore(ttl, tick time.Duration, gen CodeGenerator) *Store {
	return NewStoreWithSubmissionLimit(ttl, tick, gen, 30, 10, nil)
}

// NewStoreWithSubmissionLimit constructs a Store whose fresh room incarnations
// each receive an independent submission bucket.
func NewStoreWithSubmissionLimit(ttl, tick time.Duration, gen CodeGenerator, ratePerMinute, burst int, now func() time.Time) *Store {
	if gen == nil {
		gen = NewRandomCodeGenerator(nil)
	}
	return &Store{
		rooms:           make(map[string]*Room),
		TTL:             ttl,
		NonEmptyTTL:     defaultNonEmptyRoomTTL,
		Tick:            tick,
		gen:             gen,
		tokenRandom:     rand.Reader,
		hubs:            make(map[*Hub]struct{}),
		submissionRate:  ratePerMinute,
		submissionBurst: burst,
		submissionNow:   now,
	}
}

// bindEvents registers a Hub for Store mutation fanout. Registration is
// idempotent so multiple Servers may safely compose the same Store and Hub.
func (s *Store) bindEvents(hub *Hub) {
	if s == nil || hub == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.hubs[hub] = struct{}{}
}

// CreateRoom allocates a fresh room and returns only immutable credentials.
func (s *Store) CreateRoom() (RoomCredentials, error) {
	// Entropy reads are outside the Store lock.
	token, err := newHostToken(s.tokenRandom)
	if err != nil {
		return RoomCredentials{}, err
	}
	submissionLimiter := roomsubmission.NewLimiter(s.submissionRate, s.submissionBurst, s.submissionNow)

	s.mu.Lock()
	defer s.mu.Unlock()
	var code string
	for {
		code = s.gen.Generate()
		if _, exists := s.rooms[code]; !exists {
			break
		}
	}
	s.nextGen++
	s.rooms[code] = &Room{
		Code:              code,
		HostToken:         token,
		LastActivity:      time.Now(),
		generation:        s.nextGen,
		submissionLimiter: submissionLimiter,
	}
	return RoomCredentials{Code: code, HostToken: token}, nil
}

// View returns an immutable public snapshot of a room.
func (s *Store) View(code string) (roomView, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room := s.rooms[code]
	if room == nil {
		return roomView{}, errRoomNotFound
	}
	return viewRoom(room), nil
}

// Exists reports whether the room code currently exists.
func (s *Store) Exists(code string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.rooms[code] != nil
}

// AppendPreflight validates room identity before request parsing and returns an
// opaque reference to this room incarnation. Capacity is checked after parsing
// by CheckAppend so malformed-body error precedence remains unchanged.
func (s *Store) AppendPreflight(code string) (roomRef, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room := s.rooms[code]
	if room == nil {
		return roomRef{}, errRoomNotFound
	}
	return roomRef{code: code, generation: room.generation}, nil
}

// CheckAppend repeats preflight before expensive resolution.
func (s *Store) CheckAppend(ref roomRef) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	room, err := s.roomLocked(ref)
	if err != nil {
		return err
	}
	if len(room.Queue)+room.appendReservations >= maxQueueLength {
		return errQueueFull
	}
	return nil
}

// appendReservation owns one queue slot until append or release. Its mutable
// accounting is guarded by its Store's mutex, including after room expiry.
type appendReservation struct {
	store   *Store
	room    *Room
	ref     roomRef
	limiter *roomsubmission.Limiter
	active  bool
}

// AdmitAppend reserves capacity before charging a token outside Store.mu.
// Revalidation after limiter work prevents an expired incarnation's result
// (including a denial) from reaching the resolver or its replacement room.
func (s *Store) AdmitAppend(ref roomRef) (*appendReservation, *admission.Error, error) {
	s.mu.Lock()
	room, err := s.roomLocked(ref)
	if err != nil {
		s.mu.Unlock()
		return nil, nil, err
	}
	if len(room.Queue)+room.appendReservations >= maxQueueLength {
		s.mu.Unlock()
		return nil, nil, errQueueFull
	}
	limiter := room.submissionLimiter
	reservation := &appendReservation{store: s, room: room, ref: ref, limiter: limiter, active: true}
	room.appendReservations++
	s.mu.Unlock()
	denial := limiter.Allow()
	s.mu.Lock()
	defer s.mu.Unlock()
	current, err := s.roomLocked(ref)
	if err != nil || current != room || current.submissionLimiter != limiter {
		reservation.releaseLocked()
		return nil, nil, errRoomNotFound
	}
	if denial != nil {
		reservation.releaseLocked()
		return nil, denial, nil
	}
	return reservation, nil, nil
}

// Release abandons capacity, never refunds a token, and is idempotent so the
// handler can defer it across resolver errors and every append outcome.
func (r *appendReservation) Release() {
	r.store.mu.Lock()
	defer r.store.mu.Unlock()
	r.releaseLocked()
}

func (r *appendReservation) releaseLocked() {
	if r.active {
		r.active = false
		r.room.appendReservations--
	}
}

func (r *appendReservation) Append(track Track) (Track, error) {
	s := r.store
	s.mu.Lock()
	defer s.mu.Unlock()
	if !r.active {
		return Track{}, errRoomNotFound
	}
	r.releaseLocked()
	room, err := s.roomLocked(r.ref)
	if err != nil || room != r.room || room.submissionLimiter != r.limiter {
		return Track{}, errRoomNotFound
	}
	return s.appendLocked(r.ref, track)
}

// Append commits a resolved track and its queue_updated event in one ordered
// Store transaction. The returned value and event payload do not alias state.
func (s *Store) Append(ref roomRef, track Track) (Track, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.appendLocked(ref, track)
}

func (s *Store) appendLocked(ref roomRef, track Track) (Track, error) {
	room, err := s.roomLocked(ref)
	if err != nil {
		return Track{}, err
	}
	if len(room.Queue)+room.appendReservations >= maxQueueLength {
		return Track{}, errQueueFull
	}
	room.Queue = append(room.Queue, track)
	s.touch(room)
	s.publishLocked(room, "queue_updated", queuePayload(room))
	return track, nil
}

// Skip atomically authorizes the host, advances playback, and publishes the
// deterministic track_changed, player_state, queue_updated event sequence.
func (s *Store) Skip(code, token string) (map[string]interface{}, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room := s.rooms[code]
	if room == nil {
		return nil, errRoomNotFound
	}
	if !hostTokensEqual(token, room.HostToken) {
		return nil, errInvalidHostToken
	}
	if len(room.Queue) == 0 {
		return nil, errQueueEmpty
	}
	next := room.Queue[0]
	room.Queue = room.Queue[1:]
	room.Current = &Current{TrackID: next.ID, State: "playing", URL: next.URL, Title: next.Title, Artist: next.Artist, ResolvedBy: next.ResolvedBy}
	s.touch(room)
	payload := currentPayload(room.Current)
	s.publishLocked(room, "track_changed", payload)
	s.publishLocked(room, "player_state", map[string]string{"state": room.Current.State})
	s.publishLocked(room, "queue_updated", queuePayload(room))
	return cloneMap(payload), nil
}

// ReorderPreflight validates host authorization before the request body is read
// and binds the later commit to this room incarnation.
func (s *Store) ReorderPreflight(code, token string) (roomRef, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room := s.rooms[code]
	if room == nil {
		return roomRef{}, errRoomNotFound
	}
	if !hostTokensEqual(token, room.HostToken) {
		return roomRef{}, errInvalidHostToken
	}
	return roomRef{code: code, generation: room.generation}, nil
}

// Reorder atomically revalidates identity and authorization, applies an exact
// permutation, and publishes the matching immutable queue snapshot.
func (s *Store) Reorder(ref roomRef, token string, order []string) ([]Track, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room, err := s.roomLocked(ref)
	if err != nil {
		return nil, err
	}
	if !hostTokensEqual(token, room.HostToken) {
		return nil, errInvalidHostToken
	}
	if !isPermutation(order, room.Queue) {
		return nil, errInvalidOrder
	}
	byID := make(map[string]Track, len(room.Queue))
	for _, track := range room.Queue {
		byID[track.ID] = track
	}
	queue := make([]Track, 0, len(order))
	for _, id := range order {
		queue = append(queue, byID[id])
	}
	room.Queue = queue
	s.touch(room)
	payload := queuePayload(room)
	s.publishLocked(room, "queue_updated", payload)
	return cloneTracks(queue), nil
}

// EventSnapshot returns an immutable room snapshot. Event IDs are scoped to a
// particular Hub subscription, so callers serving SSE use subscribeEvents to
// capture both from the same Hub atomically.
func (s *Store) EventSnapshot(code string) (eventSnapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room := s.rooms[code]
	if room == nil {
		return eventSnapshot{}, errRoomNotFound
	}
	return eventSnapshot{Data: snapshotPayload(room)}, nil
}

// subscribeEvents binds an SSE subscriber to the current room incarnation and
// captures its immutable state plus the selected Hub's event-ID boundary while
// the Store lock is held.
func (s *Store) subscribeEvents(code string, hub *Hub) (*subscriber, eventSnapshot, func(), error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.subscribeEventsLocked(code, hub)
}

func (s *Store) subscribeEventsLocked(code string, hub *Hub) (*subscriber, eventSnapshot, func(), error) {
	room := s.rooms[code]
	if room == nil {
		return nil, eventSnapshot{}, nil, errRoomNotFound
	}
	ref := roomRef{code: code, generation: room.generation}
	sub, cancel := hub.subscribeRef(ref)
	room, err := s.roomLocked(ref)
	if err != nil {
		cancel()
		return nil, eventSnapshot{}, nil, err
	}
	return sub, eventSnapshot{ID: hub.currentIDRef(ref), Data: snapshotPayload(room)}, cancel, nil
}

// CurrentStream returns an immutable current-track view safe for use during
// network and process work after the Store lock is released.
func (s *Store) CurrentStream(code string) (*currentStreamView, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	room := s.rooms[code]
	if room == nil {
		return nil, errRoomNotFound
	}
	if room.Current == nil {
		return nil, nil
	}
	cur := room.Current
	return &currentStreamView{ID: cur.TrackID, URL: cur.URL, Title: cur.Title, Artist: cur.Artist, ResolvedBy: cur.ResolvedBy}, nil
}

func (s *Store) roomLocked(ref roomRef) (*Room, error) {
	room := s.rooms[ref.code]
	if room == nil || room.generation != ref.generation {
		return nil, errRoomNotFound
	}
	return room, nil
}

func (s *Store) publishLocked(room *Room, name string, data interface{}) {
	ref := roomRef{code: room.Code, generation: room.generation}
	for hub := range s.hubs {
		hub.publishRef(ref, name, data)
	}
}

func (s *Store) invalidateLocked(room *Room) {
	ref := roomRef{code: room.Code, generation: room.generation}
	for hub := range s.hubs {
		hub.invalidateRef(ref)
	}
}

func (s *Store) touch(room *Room) { room.LastActivity = time.Now() }

func (s *Store) Janitor(stop <-chan struct{}) {
	ticker := time.NewTicker(s.Tick)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			s.sweep()
		}
	}
}

func (s *Store) sweep() {
	s.sweepAt(time.Now())
}

func (s *Store) sweepAt(now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for code, room := range s.rooms {
		idle := now.Sub(room.LastActivity)
		if (room.isEmpty() && idle > s.TTL) || (!room.isEmpty() && idle > s.NonEmptyTTL) {
			s.invalidateLocked(room)
			delete(s.rooms, code)
		}
	}
}

func (r *Room) isEmpty() bool { return len(r.Queue) == 0 && r.Current == nil }

func cloneMap(source map[string]interface{}) map[string]interface{} {
	clone := make(map[string]interface{}, len(source))
	for key, value := range source {
		clone[key] = value
	}
	return clone
}
