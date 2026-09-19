package stream

import (
	"time"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

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
	// YTDLPLimiter bounds concurrent yt-dlp subprocesses across the process
	// (qmix#130). Nil keeps the pre-#130 unbounded behavior.
	YTDLPLimiter *ytdlpcap.Limiter
}
