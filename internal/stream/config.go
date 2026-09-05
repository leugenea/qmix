package stream

import (
	"os"
	"time"
)

// Config holds the stream backend's process configuration read from the
// environment at startup (mirroring the QMIX_ADDR / resolver-token pattern).
// All values have defaults, so the zero environment keeps production behaviour.
type Config struct {
	// YtdlpBin is the path to the yt-dlp executable. Empty means "yt-dlp" on
	// PATH.
	YtdlpBin string
	// CacheTTL is how long a resolved audio URL is reused. Zero means the
	// 5-minute default; a negative value disables caching.
	CacheTTL time.Duration
}

// ConfigFromEnv reads the stream configuration from the environment:
// QMIX_YTDLP_BIN and QMIX_STREAM_CACHE_TTL (e.g. "5m"). Missing or invalid
// values fall back to defaults so the service keeps working out of the box.
func ConfigFromEnv() Config {
	ttl, _ := time.ParseDuration(os.Getenv("QMIX_STREAM_CACHE_TTL"))
	return Config{
		YtdlpBin: os.Getenv("QMIX_YTDLP_BIN"),
		CacheTTL: ttl,
	}
}
