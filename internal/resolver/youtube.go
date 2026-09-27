package resolver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os/exec"
	"strings"
	"time"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

// Runner executes an external process and returns its raw stdout. It isolates
// the resolver behind an injectable seam so tests can feed fixture JSON
// instead of invoking a real binary.
type Runner interface {
	// Run returns the raw stdout bytes produced for url.
	Run(ctx context.Context, url string) ([]byte, error)
}

// ytdlpRunner shells out to yt-dlp and asks for metadata only (no download).
type ytdlpRunner struct {
	bin string
}

// ytdlpBin is the yt-dlp executable. The zero-value ytdlpRunner uses it.
const ytdlpBin = "yt-dlp"

// ytdlpMetadataTimeout bounds metadata extraction independently of the
// incoming request lifetime. Metadata calls are expected to finish quickly.
const ytdlpMetadataTimeout = 30 * time.Second

// Run invokes yt-dlp in single-video mode, separating options from the URL.
func (r ytdlpRunner) Run(ctx context.Context, url string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, r.bin, "--skip-download", "--dump-json", "--no-warnings", "--no-playlist", "--", url)
	return cmd.Output()
}

// youTubeJSON is the subset of yt-dlp's --dump-json document we consume.
type youTubeJSON struct {
	Title    string `json:"title"`
	Uploader string `json:"uploader"`
	Channel  string `json:"channel"`
	Artist   string `json:"artist"`
	Track    string `json:"track"`
	Duration int    `json:"duration"` // seconds
}

// YouTube resolves YouTube links via yt-dlp --dump-json. The external call is
// wrapped behind Runner so tests use fixture JSON. Duration comes from the
// "duration" field (seconds).
type YouTube struct {
	// Runner drives the metadata fetch. If nil, the default yt-dlp exec runner
	// is used. Tests inject a fake runner returning fixture JSON.
	Runner Runner
	// Bin overrides the yt-dlp executable path for the default runner.
	Bin string
	// Timeout bounds one metadata lookup. Zero uses ytdlpMetadataTimeout.
	Timeout time.Duration
	// Name is the value reported in Track.ResolvedBy. Defaults to "youtube".
	Name string
	// Limiter bounds concurrent yt-dlp subprocesses across the process
	// (qmix#130). Nil keeps the pre-#130 unbounded behavior.
	Limiter *ytdlpcap.Limiter
}

func (y *YouTube) runner() Runner {
	if y.Runner != nil {
		return y.Runner
	}
	bin := y.Bin
	if bin == "" {
		bin = ytdlpBin
	}
	return ytdlpRunner{bin: bin}
}

func (y *YouTube) name() string {
	if y.Name != "" {
		return y.Name
	}
	return "youtube"
}

func (y *YouTube) timeout() time.Duration {
	if y.Timeout <= 0 {
		return ytdlpMetadataTimeout
	}
	return y.Timeout
}

func hasNonBlankQueryValue(u *url.URL, key string) bool {
	for _, value := range u.Query()[key] {
		if strings.TrimSpace(value) != "" {
			return true
		}
	}
	return false
}

// hasYouTubeVideoID allows only explicit single-video resource forms. A v
// parameter on a channel, playlist, or search page is not a video resource.
func hasYouTubeVideoID(u *url.URL) bool {
	path := strings.Trim(u.Path, "/")
	if hostMatchesDomain(strings.TrimSuffix(strings.ToLower(u.Hostname()), "."), "youtu.be") {
		return path != "" && !strings.Contains(path, "/") && strings.TrimSpace(path) != ""
	}
	if path == "watch" {
		return hasNonBlankQueryValue(u, "v")
	}
	parts := strings.Split(path, "/")
	if len(parts) != 2 || strings.TrimSpace(parts[1]) == "" {
		return false
	}
	switch parts[0] {
	case "embed":
		return !strings.EqualFold(parts[1], "videoseries")
	case "live", "shorts", "v":
		return true
	default:
		return false
	}
}

func isYouTubeVideoURL(rawurl string) bool {
	host, err := DomainOf(rawurl)
	if err != nil || (!hostMatchesDomain(host, "youtube.com") && !hostMatchesDomain(host, "youtu.be")) {
		return false
	}
	u, err := url.Parse(rawurl)
	return err == nil && hasYouTubeVideoID(u)
}

// Resolve fetches metadata for an explicit YouTube video URL. Unsupported
// resources are rejected before acquiring shared subprocess capacity (qmix#205).
func (y *YouTube) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	rawurl = strings.TrimSpace(rawurl)
	if !isYouTubeVideoURL(rawurl) {
		return nil, errors.Join(ErrUnsupported, errors.New("youtube: URL requires an explicit video ID"))
	}
	if err := y.Limiter.Acquire(ctx); err != nil {
		return nil, err
	}
	defer y.Limiter.Release()
	runCtx, cancel := context.WithTimeout(ctx, y.timeout())
	defer cancel()
	out, err := y.runner().Run(runCtx, rawurl)
	if err != nil {
		return nil, fmt.Errorf("youtube: %w: %w", ErrService, err)
	}
	if len(out) == 0 {
		return nil, errors.Join(ErrService, errors.New("youtube: empty metadata output"))
	}
	var v youTubeJSON
	if err := json.Unmarshal(out, &v); err != nil {
		return nil, fmt.Errorf("youtube: %w: %w", ErrService, err)
	}
	if v.Title == "" {
		return nil, errors.Join(ErrService, errors.New("youtube: empty title in metadata"))
	}
	return &Track{
		Title:       v.Title,
		Artist:      youTubeArtist(&v),
		DurationSec: v.Duration,
		Source:      rawurl,
		ResolvedBy:  y.name(),
	}, nil
}

// youTubeArtist prefers embedded song metadata (artist/track) over the
// uploader, falling back to the channel name.
func youTubeArtist(v *youTubeJSON) string {
	if v.Artist != "" {
		return v.Artist
	}
	if v.Uploader != "" {
		return v.Uploader
	}
	return v.Channel
}
