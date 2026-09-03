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

// Track is a single queue entry. In M1 the metadata is a stub: Title equals the
// original URL and Artist/DurationSec are zero. TODO(M2): the resolver plugin
// fills real metadata from the source link.
type Track struct {
	ID          string `json:"id"`
	URL         string `json:"url"`          // original source link
	Title       string `json:"title"`        // M1 stub: == URL; TODO(M2): resolved by resolver
	Artist      string `json:"artist"`       // M1: always ""
	DurationSec int    `json:"duration_sec"` // M1: always 0
}

// Current is the currently playing track. In M1 PosSec is always 0 and State
// is either "idle" or "playing".
type Current struct {
	TrackID string
	PosSec  int
	State   string
}
