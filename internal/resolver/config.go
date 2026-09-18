package resolver

import (
	"os"
	"time"
)

// Config holds resolver credentials and process settings read from the environment
// at startup. They are parsed once and passed to the resolver assembly point (DefaultMuxWithConfig)
// so the concrete consumers in later milestones (#8-#10) can use them. Empty
// values mean the service stays in its anonymous mode — M2 behaviour unchanged.
type Config struct {
	VKToken             string
	YMToken             string
	SpotifyClientID     string
	SpotifyClientSecret string
	// YTDLPBin is the path to the yt-dlp executable. Empty means "yt-dlp" on
	// PATH.
	YTDLPBin string
	// YTDLPMetadataTimeout bounds YouTube metadata subprocesses. Zero uses the
	// resolver default.
	YTDLPMetadataTimeout time.Duration
}

// ConfigFromEnv reads the resolver configuration from the environment,
// mirroring the QMIX_ADDR pattern in cmd/qmix. A missing executable path uses
// "yt-dlp" from PATH, missing credentials keep anonymous mode, and a missing or
// invalid process timeout uses the default. Credential values are never logged.
func ConfigFromEnv() Config {
	metadataTimeout, _ := time.ParseDuration(os.Getenv("QMIX_YTDLP_METADATA_TIMEOUT"))
	return Config{
		VKToken:              os.Getenv("QMIX_VK_TOKEN"),
		YMToken:              os.Getenv("QMIX_YM_TOKEN"),
		SpotifyClientID:      os.Getenv("QMIX_SPOTIFY_CLIENT_ID"),
		SpotifyClientSecret:  os.Getenv("QMIX_SPOTIFY_CLIENT_SECRET"),
		YTDLPBin:             os.Getenv("QMIX_YTDLP_BIN"),
		YTDLPMetadataTimeout: metadataTimeout,
	}
}
