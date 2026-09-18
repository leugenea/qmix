package server

import (
	"bytes"
	"encoding/base64"
	"errors"
	"testing"
	"time"
)

// TestNewHostTokenUsesURLSafeRawEncoding pins qmix#131: host credentials carry
// at least 192 bits of entropy and are safe to transport in an HTTP header.
func TestNewHostTokenUsesURLSafeRawEncoding(t *testing.T) {
	entropy := bytes.Repeat([]byte{0xfb}, hostTokenBytes)

	token, err := newHostToken(bytes.NewReader(entropy))
	if err != nil {
		t.Fatalf("newHostToken: %v", err)
	}
	decoded, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		t.Fatalf("token %q is not raw URL-safe base64: %v", token, err)
	}
	if !bytes.Equal(decoded, entropy) {
		t.Fatalf("decoded token = %x, want %x", decoded, entropy)
	}
	if len(decoded) < 24 {
		t.Fatalf("decoded token has %d bytes, want at least 24", len(decoded))
	}
}

func TestCreateRoomFailsClosedWhenEntropyUnavailable(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	store.tokenRandom = errorReader{err: errors.New("entropy unavailable")}

	room, err := store.CreateRoom()
	if err == nil {
		t.Fatal("CreateRoom returned nil error")
	}
	if room != nil {
		t.Fatalf("room = %+v, want nil", room)
	}
	if len(store.rooms) != 0 {
		t.Fatalf("stored %d rooms after token generation failed", len(store.rooms))
	}
}

type errorReader struct{ err error }

func (r errorReader) Read([]byte) (int, error) { return 0, r.err }

func TestHostTokensEqual(t *testing.T) {
	stored := "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
	for _, tc := range []struct {
		name     string
		supplied string
		want     bool
	}{
		{name: "match", supplied: stored, want: true},
		{name: "wrong same length", supplied: "X" + stored[1:]},
		{name: "wrong shorter", supplied: stored[:len(stored)-1]},
		{name: "missing"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := hostTokensEqual(tc.supplied, stored); got != tc.want {
				t.Fatalf("hostTokensEqual() = %t, want %t", got, tc.want)
			}
		})
	}
}
