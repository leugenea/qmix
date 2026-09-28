package server

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/config"
	"github.com/leugenea/qmix/internal/roomcreate"
)

// qmix#260: one identity can fill capacity at its allowed rate. The fake
// limiter clock and recorded creation/activity times share the same timeline;
// sweepAt runs the janitor's normal expiry path without waiting 45 minutes.
func TestUnusedRoomsReleaseFullCapacityAtShortTTL(t *testing.T) {
	const maximum = 256
	clock := time.Unix(1_000_000, 0)
	store := capacityStore(t, maximum, &countingCodeGenerator{})
	store.TTL = 12 * time.Hour
	store.UnusedTTL = 45 * time.Minute
	hub := NewHub()
	server := NewServer(store, hub)
	server.roomCreateLimiter = roomcreate.NewLimiter(10, 5, 8, func() time.Time { return clock })
	server.clientIdentities = roomcreate.NewIdentityResolver(nil)
	mux := newTestMux(server)

	firstCreated := clock
	var first string
	for i := range maximum {
		response := createFromPeer(mux, "198.51.100.1:4000")
		if response.Code != http.StatusCreated {
			t.Fatalf("creation %d status = %d; body=%s", i, response.Code, response.Body.String())
		}
		var result RoomCredentials
		if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		if i == 0 {
			first = result.Code
		}
		store.mu.Lock()
		store.rooms[result.Code].LastActivity = clock
		store.mu.Unlock()
		clock = clock.Add(6 * time.Second) // 10 tokens/minute after burst 5
	}
	lastCreated := clock.Add(-6 * time.Second)
	if len(store.rooms) != maximum {
		t.Fatalf("live rooms = %d, want %d", len(store.rooms), maximum)
	}
	sub, _, cancel, err := store.subscribeEvents(first, hub)
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	full := createFromPeer(mux, "198.51.100.1:4000")
	if full.Code != http.StatusServiceUnavailable {
		t.Fatalf("full capacity status = %d; body=%s", full.Code, full.Body.String())
	}
	var denial errorEnvelope
	if err := json.Unmarshal(full.Body.Bytes(), &denial); err != nil || denial.Error != "room_capacity_exhausted" {
		t.Fatalf("capacity envelope = %+v, error %v", denial, err)
	}
	clock = firstCreated.Add(store.UnusedTTL - time.Nanosecond)
	store.sweepAt(clock)
	if len(store.rooms) != maximum {
		t.Fatalf("live rooms just before first unused TTL = %d, want %d", len(store.rooms), maximum)
	}
	if response := createFromPeer(mux, "198.51.100.1:4000"); response.Code != http.StatusServiceUnavailable {
		t.Fatalf("create just before first unused TTL = %d; body=%s", response.Code, response.Body.String())
	}
	clock = firstCreated.Add(store.UnusedTTL)
	store.sweepAt(clock)
	if !store.Exists(first) {
		t.Fatal("unused room expired at exact TTL boundary")
	}
	clock = lastCreated.Add(store.UnusedTTL + time.Nanosecond)
	if elapsed := clock.Sub(firstCreated); elapsed >= store.TTL {
		t.Fatalf("all-room sweep age = %v, must be below empty-room TTL %v", elapsed, store.TTL)
	}
	store.sweepAt(clock)
	if len(store.rooms) != 0 {
		t.Fatalf("live rooms after all unused TTLs = %d, want 0", len(store.rooms))
	}
	if store.Exists(first) {
		t.Fatal("unused room still occupied a slot after short TTL")
	}
	select {
	case <-sub.done:
	default:
		t.Fatal("expired unused room kept its SSE subscriber")
	}
	if response := createFromPeer(mux, "198.51.100.1:4000"); response.Code != http.StatusCreated {
		t.Fatalf("create after janitor freed all unused slots = %d; body=%s", response.Code, response.Body.String())
	}
}

func TestUsedRoomEmptyAndNonEmptyTTLs(t *testing.T) {
	for _, tc := range []struct {
		name     string
		leave    func(t *testing.T, store *Store, credentials RoomCredentials, id string)
		lifetime time.Duration
	}{
		{name: "track queued then ended", leave: func(t *testing.T, store *Store, credentials RoomCredentials, id string) {
			t.Helper()
			if _, err := store.Skip(credentials.Code, credentials.HostToken); err != nil {
				t.Fatal(err)
			}
			ref, err := store.PlayerPreflight(credentials.Code, credentials.HostToken)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := store.ReportPlayer(ref, credentials.HostToken, playerReport{TrackID: id, State: "ended"}); err != nil {
				t.Fatal(err)
			}
		}, lifetime: 12 * time.Hour},
		{name: "track queued and not played", leave: func(t *testing.T, store *Store, credentials RoomCredentials, id string) {}, lifetime: 24 * time.Hour},
	} {
		t.Run(tc.name, func(t *testing.T) {
			store := NewStore(12*time.Hour, time.Minute, &countingCodeGenerator{})
			store.UnusedTTL = 45 * time.Minute
			credentials, err := store.CreateRoom()
			if err != nil {
				t.Fatal(err)
			}
			ref, err := store.AppendPreflight(credentials.Code)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := store.Append(ref, Track{ID: "track"}); err != nil {
				t.Fatal(err)
			}
			tc.leave(t, store, credentials, "track")
			anchor := time.Unix(2_000_000, 0)
			store.mu.Lock()
			store.rooms[credentials.Code].LastActivity = anchor
			store.mu.Unlock()
			store.sweepAt(anchor.Add(store.UnusedTTL + time.Nanosecond))
			if !store.Exists(credentials.Code) {
				t.Fatal("room that received a track expired on unused TTL")
			}
			store.sweepAt(anchor.Add(tc.lifetime))
			if !store.Exists(credentials.Code) {
				t.Fatal("room expired at exact used-room TTL boundary")
			}
			store.sweepAt(anchor.Add(tc.lifetime + time.Nanosecond))
			if store.Exists(credentials.Code) {
				t.Fatal("room survived past used-room TTL")
			}
		})
	}
}

func TestPendingSubmissionDoesNotMarkRoomUsed(t *testing.T) {
	store := NewStore(12*time.Hour, time.Minute, &countingCodeGenerator{})
	store.UnusedTTL = 45 * time.Minute
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatal(err)
	}
	reservation, denial, err := store.AdmitAppend(ref)
	if err != nil || denial != nil {
		t.Fatalf("admission: reservation=%v denial=%v error=%v", reservation, denial, err)
	}
	reservation.Release() // resolver failed; no successful append
	anchor := time.Unix(3_000_000, 0)
	store.mu.Lock()
	store.rooms[credentials.Code].LastActivity = anchor
	store.mu.Unlock()
	store.sweepAt(anchor.Add(store.UnusedTTL + time.Nanosecond))
	if store.Exists(credentials.Code) {
		t.Fatal("failed submission kept an unused room alive")
	}
}

func TestNewAppWiresCustomUnusedRoomTTL(t *testing.T) {
	cfg, err := config.Parse(func(name string) (string, bool) {
		if name == "QMIX_UNUSED_ROOM_TTL" {
			return "2m", true
		}
		return "", false
	})
	if err != nil {
		t.Fatal(err)
	}
	app, err := NewApp(cfg, nil, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	if app.Store.UnusedTTL != 2*time.Minute {
		t.Fatalf("store unused TTL = %v, want configured 2m", app.Store.UnusedTTL)
	}
}
