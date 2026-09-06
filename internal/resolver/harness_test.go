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
	s := &Spotify{Config: ConfigFromEnv()}
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
