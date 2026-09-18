package resolver

import (
	"context"
	"os"
	"testing"
)

// skipIfNoToken skips the test unless every named environment variable is set
// and non-empty. It is the gate for integration tests that need real service
// credentials; without them the test is skipped so CI stays green even when
// secrets are absent.
func skipIfNoToken(t *testing.T, env ...string) {
	t.Helper()
	for _, key := range env {
		if os.Getenv(key) == "" {
			t.Skipf("integration test skipped: %s not set", key)
		}
	}
}

// TestSkipIfNoTokenMissing verifies the harness skips when a variable is unset.
func TestSkipIfNoTokenMissing(t *testing.T) {
	t.Setenv("QMIX_SPOTIFY_CLIENT_ID", "")
	skipIfNoToken(t, "QMIX_SPOTIFY_CLIENT_ID")
	t.Fatal("expected skip")
}

// TestSkipIfNoTokenPresent verifies the harness does not skip when all
// variables are set.
func TestSkipIfNoTokenPresent(t *testing.T) {
	t.Setenv("QMIX_SPOTIFY_CLIENT_ID", "id")
	t.Setenv("QMIX_SPOTIFY_CLIENT_SECRET", "secret")
	skipIfNoToken(t, "QMIX_SPOTIFY_CLIENT_ID", "QMIX_SPOTIFY_CLIENT_SECRET")
}

// TestSpotifyOEmbedLive is a gated integration test against the real Spotify
// oEmbed endpoint. It runs only when QMIX_SPOTIFY_CLIENT_ID is set (a proxy for
// "credentials available"); otherwise it is skipped. The body is kept small and
// reuses the already-covered anonymous path.
func TestSpotifyOEmbedLive(t *testing.T) {
	skipIfNoToken(t, "QMIX_SPOTIFY_CLIENT_ID")
	tr, err := (&Spotify{}).Resolve(context.Background(), "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
	if err != nil {
		t.Fatalf("live resolve: %v", err)
	}
	if tr.Title == "" {
		t.Fatal("live resolve returned empty title")
	}
}

// TestSpotifyAPILive is a gated integration test for the Client Credentials
// path (#8): a real token is fetched from accounts.spotify.com and the real
// Web API resolves a known track. It runs only when both app credentials are
// configured (CI job `integration` provides them); otherwise it is skipped.
func TestSpotifyAPILive(t *testing.T) {
	skipIfNoToken(t, "QMIX_SPOTIFY_CLIENT_ID", "QMIX_SPOTIFY_CLIENT_SECRET")
	s := &Spotify{Config: liveResolverConfig()}
	tr, err := s.Resolve(context.Background(), "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
	if err != nil {
		t.Fatalf("live api resolve: %v", err)
	}
	if tr.Title == "" {
		t.Fatal("live api resolve returned empty title")
	}
	if tr.DurationSec <= 0 {
		t.Fatalf("live api resolve duration = %d, want > 0", tr.DurationSec)
	}
	if tr.Artist == "" {
		t.Fatal("live api resolve returned empty artist")
	}
}

// TestVKAPILive is a gated integration test for the authenticated VK path
// (qmix#9): the real api.vk.com resolves a known public audio link via
// audio.getById. It runs only when QMIX_VK_TOKEN is set; otherwise it is
// skipped so CI stays green without the secret.
func TestVKAPILive(t *testing.T) {
	skipIfNoToken(t, "QMIX_VK_TOKEN")
	v := &VKYandex{Config: liveResolverConfig()}
	// Public audio link from issue qmix#9 (not a secret).
	tr, err := v.Resolve(context.Background(), "https://vk.ru/audio1132822_456240773_bb5b9b9640eed9638f")
	if err != nil {
		t.Fatalf("live vk resolve: %v", err)
	}
	if tr.Title == "" {
		t.Fatal("live vk resolve returned empty title")
	}
	if tr.Artist == "" {
		t.Fatal("live vk resolve returned empty artist")
	}
	if tr.DurationSec <= 0 {
		t.Fatalf("live vk resolve duration = %d, want > 0", tr.DurationSec)
	}
	if tr.ResolvedBy != "vk" {
		t.Fatalf("live vk resolve resolvedBy = %q, want vk", tr.ResolvedBy)
	}
}

// TestYMAPILive is a gated integration test for the authenticated Yandex
// Music path (qmix#10): the real goym client (api.music.yandex.net) resolves
// a known public track link obtained via the share button. It runs only when
// QMIX_YM_TOKEN is set; otherwise it is skipped so CI stays green without
// the secret.
func TestYMAPILive(t *testing.T) {
	skipIfNoToken(t, "QMIX_YM_TOKEN")
	v := &VKYandex{Config: liveResolverConfig()}
	// Public share link from issue qmix#10 (not a secret): query parameters
	// from the share button must be ignored by the parser.
	tr, err := v.Resolve(context.Background(), "https://music.yandex.ru/album/14599266/track/609676?utm_medium=copy_link&ref_id=3469c57a-0a9c-44ba-abeb-8293f2bb2fa7")
	if err != nil {
		t.Fatalf("live yandex resolve: %v", err)
	}
	if tr.Title == "" {
		t.Fatal("live yandex resolve returned empty title")
	}
	if tr.Artist == "" {
		t.Fatal("live yandex resolve returned empty artist")
	}
	if tr.DurationSec <= 0 {
		t.Fatalf("live yandex resolve duration = %d, want > 0", tr.DurationSec)
	}
	if tr.ResolvedBy != "yandex" {
		t.Fatalf("live yandex resolve resolvedBy = %q, want yandex", tr.ResolvedBy)
	}
}

func liveResolverConfig() Config {
	return Config{
		VKToken:             os.Getenv("QMIX_VK_TOKEN"),
		YMToken:             os.Getenv("QMIX_YM_TOKEN"),
		SpotifyClientID:     os.Getenv("QMIX_SPOTIFY_CLIENT_ID"),
		SpotifyClientSecret: os.Getenv("QMIX_SPOTIFY_CLIENT_SECRET"),
	}
}
