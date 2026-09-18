package server

import (
	"crypto/rand"
	"io"
	"sync"
	"time"
)

const defaultNonEmptyRoomTTL = 24 * time.Hour

// Store holds rooms in memory under a mutex. Empty rooms use the shorter TTL;
// non-empty rooms are also removed after NonEmptyTTL so abandoned queues cannot
// consume memory indefinitely.
type Store struct {
	mu    sync.Mutex
	rooms map[string]*Room

	// TTL is how long an empty room may stay idle before being removed.
	TTL time.Duration
	// NonEmptyTTL is how long a room with queued or current tracks may stay idle.
	NonEmptyTTL time.Duration
	// Tick is the janitor sweep interval.
	Tick time.Duration

	// gen is the room-code generator (configurable for deterministic tests).
	gen CodeGenerator
	// tokenRandom provides cryptographic entropy for host tokens.
	tokenRandom io.Reader
}

// NewStore returns a Store with the given empty-room TTL and janitor tick.
// Non-empty rooms use the 24-hour default. gen may be nil to use a random
// generator.
func NewStore(ttl, tick time.Duration, gen CodeGenerator) *Store {
	if gen == nil {
		gen = NewRandomCodeGenerator(nil)
	}
	return &Store{
		rooms:       make(map[string]*Room),
		TTL:         ttl,
		NonEmptyTTL: defaultNonEmptyRoomTTL,
		Tick:        tick,
		gen:         gen,
		tokenRandom: rand.Reader,
	}
}

// CreateRoom allocates a fresh room code (checking for collisions), stores the
// room and returns it.
func (s *Store) CreateRoom() (*Room, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var code string
	for {
		code = s.gen.Generate()
		if _, exists := s.rooms[code]; !exists {
			break
		}
	}
	token, err := newHostToken(s.tokenRandom)
	if err != nil {
		return nil, err
	}
	r := &Room{
		Code:         code,
		HostToken:    token,
		LastActivity: time.Now(),
	}
	s.rooms[code] = r
	return r, nil
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

// Janitor runs until stop is closed, sweeping expired idle rooms every Tick.
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

// sweep removes idle rooms using separate empty and non-empty TTLs.
func (s *Store) sweep() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for code, r := range s.rooms {
		idle := now.Sub(r.LastActivity)
		if (r.isEmpty() && idle > s.TTL) || (!r.isEmpty() && idle > s.NonEmptyTTL) {
			delete(s.rooms, code)
		}
	}
}

// isEmpty reports whether the room has no queue and no current track.
func (r *Room) isEmpty() bool {
	return len(r.Queue) == 0 && r.Current == nil
}
