package resolver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
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

// Run invokes `yt-dlp --skip-download --dump-json`.
func (r ytdlpRunner) Run(ctx context.Context, url string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, r.bin, "--skip-download", "--dump-json", "--no-warnings", url)
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
	// Name is the value reported in Track.ResolvedBy. Defaults to "youtube".
	Name string
}

func (y *YouTube) runner() Runner {
	if y.Runner != nil {
		return y.Runner
	}
	return ytdlpRunner{bin: ytdlpBin}
}

func (y *YouTube) name() string {
	if y.Name != "" {
		return y.Name
	}
	return "youtube"
}

// Resolve fetches metadata for a YouTube url through the runner.
func (y *YouTube) Resolve(ctx context.Context, rawurl string) (*Track, error) {
	out, err := y.runner().Run(ctx, rawurl)
	if err != nil {
		return nil, fmt.Errorf("youtube: %w: %v", ErrService, err)
	}
	if len(out) == 0 {
		return nil, errors.Join(ErrService, errors.New("youtube: empty metadata output"))
	}
	var v youTubeJSON
	if err := json.Unmarshal(out, &v); err != nil {
		return nil, fmt.Errorf("youtube: %w: %v", ErrService, err)
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
