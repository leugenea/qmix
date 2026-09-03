package server

import (
	"sync"
	"time"
)

// Store holds rooms in memory under a mutex. Empty rooms (no queue and no
// current track) are removed by the janitor once their LastActivity is older
// than TTL. TTL and the janitor tick interval are configurable so tests can
// use small values.
type Store struct {
	mu    sync.Mutex
	rooms map[string]*Room

	// TTL is how long an empty room may stay idle before being removed.
	TTL time.Duration
	// Tick is the janitor sweep interval.
	Tick time.Duration

	// gen is the room-code generator (configurable for deterministic tests).
	gen CodeGenerator
}

// NewStore returns a Store with the given TTL and janitor tick. gen may be nil
// to use a random generator.
func NewStore(ttl, tick time.Duration, gen CodeGenerator) *Store {
	if gen == nil {
		gen = NewRandomCodeGenerator(nil)
	}
	return &Store{
		rooms: make(map[string]*Room),
		TTL:   ttl,
		Tick:  tick,
		gen:   gen,
	}
}

// CreateRoom allocates a fresh room code (checking for collisions), stores the
// room and returns it.
func (s *Store) CreateRoom() *Room {
	s.mu.Lock()
	defer s.mu.Unlock()

	var code string
	for {
		code = s.gen.Generate()
		if _, exists := s.rooms[code]; !exists {
			break
		}
	}
	r := &Room{
		Code:         code,
		HostToken:    newToken(),
		LastActivity: time.Now(),
	}
	s.rooms[code] = r
	return r
}

// Get returns the room with the given code, or nil.
func (s *Store) Get(code string) *Room {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.rooms[code]
}

// touch updates LastActivity. Callers must hold s.mu.
func (s *Store) touch(r *Room) {
	r.LastActivity = time.Now()
}

// Janitor runs until stop is closed, sweeping expired empty rooms every Tick.
func (s *Store) Janitor(stop <-chan struct{}) {
	t := time.NewTicker(s.Tick)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			s.sweep()
		}
	}
}

// sweep removes empty rooms whose LastActivity is older than TTL.
func (s *Store) sweep() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for code, r := range s.rooms {
		if r.isEmpty() && now.Sub(r.LastActivity) > s.TTL {
			delete(s.rooms, code)
		}
	}
}

// isEmpty reports whether the room has no queue and no current track.
func (r *Room) isEmpty() bool {
	return len(r.Queue) == 0 && r.Current == nil
}
