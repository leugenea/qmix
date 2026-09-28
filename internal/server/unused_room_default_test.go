package server

import (
	"testing"
	"time"
)

// qmix#260: this test uses only the pre-existing Store API so it fails on the
// old 12-hour behavior without relying on the new configuration field.
func TestDefaultUnusedRoomExpiresBeforeEmptyTTL(t *testing.T) {
	store := NewStore(12*time.Hour, time.Minute, &seqCodeGen{})
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	anchor := time.Unix(4_000_000, 0)
	store.mu.Lock()
	store.rooms[credentials.Code].LastActivity = anchor
	store.mu.Unlock()
	store.sweepAt(anchor.Add(45*time.Minute + time.Nanosecond))
	if store.Exists(credentials.Code) {
		t.Fatal("never-used room still holds capacity after default 45-minute TTL")
	}
}
