package stream

import (
	"context"
	"os"
	"os/exec"
	"testing"
)

// skipIfNoLiveStream skips the test unless real streaming is explicitly
// enabled (QMIX_STREAM_LIVE=1) and a yt-dlp binary is present. It mirrors the
// token gate harness in the resolver package (#7) so the live test never blocks
// the main CI: it only runs in the integration job (or locally) with the flag
// set and the binary installed.
func skipIfNoLiveStream(t *testing.T) {
	t.Helper()
	if os.Getenv("QMIX_STREAM_LIVE") != "1" {
		t.Skip("live stream test skipped: QMIX_STREAM_LIVE not set to 1")
	}
	if _, err := exec.LookPath("yt-dlp"); err != nil {
		t.Skip("live stream test skipped: yt-dlp not on PATH")
	}
}

// TestYtdlpSearchLive is a gated integration test that searches a well-known
// track on YouTube via real yt-dlp and confirms a direct audio URL resolves. It
// only runs when QMIX_STREAM_LIVE=1 and yt-dlp is installed, so CI stays green
// by default.
func TestYtdlpSearchLive(t *testing.T) {
	skipIfNoLiveStream(t)

	b := &YTDLP{CacheTTL: -1}
	url, err := b.resolveURL(context.Background(), &Track{
		Title:  "Never Gonna Give You Up",
		Artist: "Rick Astley",
	})
	if err != nil {
		t.Fatalf("live search: %v", err)
	}
	if url == "" {
		t.Fatal("live search returned empty url")
	}
}
