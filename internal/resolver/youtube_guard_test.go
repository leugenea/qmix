package resolver

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/leugenea/qmix/internal/ytdlpcap"
)

type recordingYouTubeRunner struct {
	urls []string
}

func (r *recordingYouTubeRunner) Run(_ context.Context, url string) ([]byte, error) {
	r.urls = append(r.urls, url)
	return []byte(ytFixture), nil
}

// qmix#205: all non-video resources must be rejected before subprocess admission.
func TestYouTubeRejectsNonVideoResourcesBeforeRunner(t *testing.T) {
	cases := []struct {
		name string
		url  string
	}{
		{"whitespace playlist", " \thttps://www.youtube.com/playlist?list=playlist-id\n"},
		{"channel videos", "https://www.youtube.com/@SomeChannel/videos"},
		{"channel root", "https://www.youtube.com/@SomeChannel"},
		{"channel ID", "https://www.youtube.com/channel/UCabc/videos"},
		{"custom channel", "https://www.youtube.com/c/Name"},
		{"user", "https://www.youtube.com/user/Name"},
		{"search", "https://www.youtube.com/results?search_query=a"},
		{"home", "https://www.youtube.com/"},
		{"watch without ID", "https://www.youtube.com/watch"},
		{"watch blank ID", "https://www.youtube.com/watch?v=%20%09"},
		{"search with video query", "https://www.youtube.com/results?search_query=a&v=video-id"},
		{"channel with video query", "https://www.youtube.com/c/Name?v=video-id"},
		{"playlist with video query", "https://www.youtube.com/playlist?list=playlist-id&v=video-id"},
		{"short URL without path but video query", "https://youtu.be/?v=video-id"},
		{"embedded playlist without list", "https://www.youtube.com/embed/videoseries"},
		{"malformed resource", "https://www.youtube.com/watch?v=%zz"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			runner := &recordingYouTubeRunner{}
			limiter := ytdlpcap.New(1, 0)
			if err := limiter.Acquire(context.Background()); err != nil {
				t.Fatal(err)
			}
			defer limiter.Release()
			y := &YouTube{Runner: runner, Limiter: limiter}
			_, err := y.Resolve(context.Background(), tc.url)
			if !errors.Is(err, ErrUnsupported) {
				t.Fatalf("error = %v, want ErrUnsupported", err)
			}
			if len(runner.urls) != 0 {
				t.Fatalf("runner URLs = %q, want none", runner.urls)
			}
			if running, queued := limiter.Stats(); running != 1 || queued != 0 {
				t.Fatalf("limiter stats = %d running, %d queued; want 1, 0", running, queued)
			}
		})
	}
}

// Direct callers must reject malformed authorities before touching shared capacity.
func TestYouTubeRejectsMalformedAuthoritiesBeforeCapacity(t *testing.T) {
	cases := []struct {
		name string
		url  string
	}{
		{"empty port", "https://youtube.com:/watch?v=video-id"},
		{"zero port", "https://youtube.com:0/watch?v=video-id"},
		{"port too large", "https://youtube.com:65536/watch?v=video-id"},
		{"nonnumeric port", "https://youtube.com:abc/watch?v=video-id"},
		{"empty hostname label", "https://foo..youtube.com/watch?v=video-id"},
		{"leading hyphen", "https://-foo.youtube.com/watch?v=video-id"},
		{"trailing hyphen", "https://foo-.youtube.com/watch?v=video-id"},
		{"invalid hostname character", "https://foo_bar.youtube.com/watch?v=video-id"},
		{"userinfo", "https://user:password@youtube.com/watch?v=video-id"},
		{"unsupported scheme", "ftp://youtube.com/watch?v=video-id"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			for _, saturated := range []bool{false, true} {
				name := "available capacity"
				if saturated {
					name = "saturated capacity"
				}
				t.Run(name, func(t *testing.T) {
					runner := &recordingYouTubeRunner{}
					limiter := ytdlpcap.New(1, 0)
					if saturated {
						if err := limiter.Acquire(context.Background()); err != nil {
							t.Fatal(err)
						}
						defer limiter.Release()
					}
					y := &YouTube{Runner: runner, Limiter: limiter}
					_, err := y.Resolve(context.Background(), tc.url)
					if !errors.Is(err, ErrUnsupported) {
						t.Fatalf("error = %v, want ErrUnsupported before admission", err)
					}
					if len(runner.urls) != 0 {
						t.Fatalf("runner URLs = %q, want none", runner.urls)
					}
					wantRunning := 0
					if saturated {
						wantRunning = 1
					}
					if running, queued := limiter.Stats(); running != wantRunning || queued != 0 {
						t.Fatalf("limiter stats = %d running, %d queued; want %d, 0", running, queued, wantRunning)
					}
				})
			}
		})
	}
}

func TestYouTubeRejectsNonVideoWithSaturatedCapacity(t *testing.T) {
	limiter := ytdlpcap.New(1, 0)
	if err := limiter.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer limiter.Release()
	runner := &recordingYouTubeRunner{}
	y := &YouTube{Runner: runner, Limiter: limiter}
	_, err := y.Resolve(context.Background(), " https://youtube.com/results?search_query=a ")
	if !errors.Is(err, ErrUnsupported) || errors.Is(err, ytdlpcap.ErrOverloaded) {
		t.Fatalf("error = %v, want ErrUnsupported before capacity acquisition", err)
	}
	if running, queued := limiter.Stats(); running != 1 || queued != 0 {
		t.Fatalf("limiter stats = %d running, %d queued; want 1, 0", running, queued)
	}
	if len(runner.urls) != 0 {
		t.Fatalf("runner URLs = %q, want none", runner.urls)
	}
}

func TestYouTubeExplicitVideoResourcesKeepCanonicalInput(t *testing.T) {
	cases := []string{
		"https://www.youtube.com/watch?v=video-id&list=playlist-id",
		"https://www.youtube.com/watch?v=&v=video-id&list=playlist-id",
		"https://music.youtube.com/watch?v=video-id",
		"https://YOUTUBE.COM/watch?v=video-id",
		"https://youtu.be/video-id?list=playlist-id",
		"https://WWW.YOUTU.BE/video-id",
		"https://www.youtube.com/embed/video-id",
		"https://www.youtube.com/shorts/video-id",
		"https://www.youtube.com/live/video-id",
		"https://www.youtube.com/v/video-id",
	}
	for _, source := range cases {
		t.Run(source, func(t *testing.T) {
			runner := &recordingYouTubeRunner{}
			y := &YouTube{Runner: runner}
			input := " \t" + source + "\n"
			track, err := y.Resolve(context.Background(), input)
			if err != nil {
				t.Fatalf("resolve: %v", err)
			}
			if len(runner.urls) != 1 || runner.urls[0] != source || track.Source != source {
				t.Fatalf("runner URLs = %q, track source = %q; want %q", runner.urls, track.Source, source)
			}
		})
	}
}

func TestYouTubeRunnerSeparatesOptionsFromURL(t *testing.T) {
	dir := t.TempDir()
	argsFile := filepath.Join(dir, "args")
	bin := filepath.Join(dir, "yt-dlp")
	t.Setenv("QMIX_TEST_ARGS_FILE", argsFile)
	script := "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$QMIX_TEST_ARGS_FILE\"\nprintf '%s\\n' '{\"title\":\"Video\"}'\n"
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	const source = "https://www.youtube.com/watch?v=selected&list=playlist-id"
	track, err := (&YouTube{Runner: ytdlpRunner{bin: bin}}).Resolve(context.Background(), source)
	if err != nil || track.Source != source {
		t.Fatalf("track = %+v, error = %v", track, err)
	}
	args, err := os.ReadFile(argsFile)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"--skip-download", "--dump-json", "--no-warnings", "--no-playlist", "--", source, ""}
	if got := strings.Split(string(args), "\n"); len(got) != len(want) {
		t.Fatalf("args = %q, want %q", got, want)
	} else {
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("args = %q, want %q", got, want)
			}
		}
	}
}
