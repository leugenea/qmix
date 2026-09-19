package resolver

import (
	"time"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// Config holds resolver credentials and yt-dlp process settings supplied by
// the process composition root. Empty credentials keep anonymous resolution.
type Config struct {
	VKToken             string
	YMToken             string
	SpotifyClientID     string
	SpotifyClientSecret string
	// YTDLPBin is the path to the yt-dlp executable.
	YTDLPBin string
	// YTDLPMetadataTimeout bounds YouTube metadata subprocesses.
	YTDLPMetadataTimeout time.Duration
	// YTDLPLimiter bounds concurrent yt-dlp subprocesses across the process
	// (qmix#130). Nil keeps the pre-#130 unbounded behavior; the concrete
	// pointer is used because its nil methods are safe, unlike a nil
	// interface value.
	YTDLPLimiter *ytdlpcap.Limiter
}
