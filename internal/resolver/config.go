package resolver

import "time"

// Config holds resolver credentials and yt-dlp process settings supplied by the
// process composition root. Empty credentials keep anonymous resolution.
type Config struct {
	VKToken             string
	YMToken             string
	SpotifyClientID     string
	SpotifyClientSecret string
	// YTDLPBin is the path to the yt-dlp executable.
	YTDLPBin string
	// YTDLPMetadataTimeout bounds YouTube metadata subprocesses.
	YTDLPMetadataTimeout time.Duration
}
