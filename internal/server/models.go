package server

import "time"

// Room is a collaborative listening session. HostToken authorizes host-only
// operations (skip, reorder). Queue holds the ordered list of tracks.
type Room struct {
	Code         string
	HostToken    string
	Queue        []Track
	Current      *Current
	LastActivity time.Time
}

// Track is a single queue entry. Title, Artist, DurationSec and ResolvedBy are
// filled by the resolver plugin from the source link (see ARCHITECTURE §7);
// streamUrl is set by M3 (stream backend).
type Track struct {
	ID          string `json:"id"`
	URL         string `json:"url"`          // original source link
	Title       string `json:"title"`        // resolved by resolver
	Artist      string `json:"artist"`       // resolved by resolver
	DurationSec int    `json:"duration_sec"` // seconds; 0 if not known
	ResolvedBy  string `json:"resolved_by"`  // which resolver produced metadata
}

// Current is the currently playing track. In M1 PosSec is always 0 and State
// is either "idle" or "playing". Title/Artist keep the audio metadata of the
// playing track on the room so the stream endpoint can locate its source
// (they are internal and not part of the room view).
type Current struct {
	TrackID string
	PosSec  int
	State   string
	Title   string
	Artist  string
}
