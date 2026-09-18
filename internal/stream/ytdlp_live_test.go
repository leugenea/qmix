package stream

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// liveYtdlpBin resolves the configured executable before the live gate decides
// whether to run. An explicit QMIX_YTDLP_BIN path takes precedence over PATH.
func liveYtdlpBin() (string, error) {
	bin := ConfigFromEnv().YtdlpBin
	if bin == "" {
		bin = ytdlpBin
	}
	return exec.LookPath(bin)
}

// skipIfNoLiveStream skips the test unless real streaming is explicitly
// enabled (QMIX_STREAM_LIVE=1) and the configured yt-dlp binary is present. It mirrors the
// token gate harness in the resolver package (#7) so the live test never blocks
// the main CI: it only runs in the integration job (or locally) with the flag
// set and the binary installed.
func skipIfNoLiveStream(t *testing.T) {
	t.Helper()
	if os.Getenv("QMIX_STREAM_LIVE") != "1" {
		t.Skip("live stream test skipped: QMIX_STREAM_LIVE not set to 1")
	}
	if _, err := liveYtdlpBin(); err != nil {
		t.Skipf("live stream test skipped: configured yt-dlp unavailable: %v", err)
	}
}

func TestLiveYtdlpBinUsesConfiguredPath(t *testing.T) {
	want := filepath.Join(t.TempDir(), "yt-dlp")
	if err := os.WriteFile(want, []byte("#!/bin/sh\nexit 1\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("QMIX_YTDLP_BIN", want)
	bin, err := liveYtdlpBin()
	if err != nil {
		t.Fatalf("configured live yt-dlp: %v", err)
	}
	if bin != want {
		t.Fatalf("bin = %q, want %q", bin, want)
	}
}

// TestYtdlpSearchLive is a gated integration test that searches a well-known
// track on YouTube via real yt-dlp and confirms a direct audio URL resolves. It
// only runs when QMIX_STREAM_LIVE=1 and yt-dlp is installed, so CI stays green
// by default.
func TestYtdlpSearchLive(t *testing.T) {
	skipIfNoLiveStream(t)

	bin, err := liveYtdlpBin()
	if err != nil {
		t.Fatalf("resolve configured yt-dlp: %v", err)
	}
	b := &YTDLP{Bin: bin, CacheTTL: -1}
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
