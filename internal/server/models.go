package server

import (
	"time"

	"github.com/leugenea/qmix/internal/roomsubmission"
)

// Room is a collaborative listening session. HostToken authorizes host-only
// operations (skip, reorder, player reports). Queue holds the ordered list of tracks.
type Room struct {
	Code               string
	HostToken          string
	Queue              []Track
	Current            *Current
	LastActivity       time.Time
	generation         uint64
	submissionLimiter  *roomsubmission.Limiter
	appendReservations int
}

// Track is a single queue entry. Title, Artist, DurationSec and ResolvedBy are
// filled by the resolver plugin from the source link (see ARCHITECTURE §7).
type Track struct {
	ID          string `json:"id"`
	URL         string `json:"url"`          // original source link
	Title       string `json:"title"`        // resolved by resolver
	Artist      string `json:"artist"`       // resolved by resolver
	DurationSec int    `json:"duration_sec"` // seconds; 0 if not known
	ResolvedBy  string `json:"resolved_by"`  // which resolver produced metadata
}

// Current is the host-reported current track. PosSec is the last reported
// position, not a server-side clock. State is playing, paused, or error while a
// track remains current. Title/Artist keep the audio metadata of the playing
// track on the room so the stream endpoint and public room view can expose its
// metadata. URL and ResolvedBy are retained for internal stream
// source selection and are not exposed through the current-track API.
type Current struct {
	TrackID    string
	PosSec     int
	State      string
	URL        string
	Title      string
	Artist     string
	ResolvedBy string
}

// playerReport is the validated host playback report applied atomically by the
// Store. It deliberately contains no free-form error detail.
type playerReport struct {
	TrackID string
	State   string
	PosSec  int
}
