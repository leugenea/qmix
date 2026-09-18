package stream

import "time"

// Config holds stream backend settings supplied by the process composition
// root.
type Config struct {
	// YtdlpBin is the path to the yt-dlp executable.
	YtdlpBin string
	// CacheTTL is how long a resolved audio URL is reused. A negative value
	// disables caching.
	CacheTTL time.Duration
	// YTDLPSearchTimeout bounds one yt-dlp YouTube search.
	YTDLPSearchTimeout time.Duration
}
