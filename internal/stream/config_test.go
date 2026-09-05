package stream

import (
	"testing"
	"time"
)

// TestConfigFromEnvAll verifies bin and cache TTL are read from the env.
func TestConfigFromEnvAll(t *testing.T) {
	t.Setenv("QMIX_YTDLP_BIN", "/custom/yt-dlp")
	t.Setenv("QMIX_STREAM_CACHE_TTL", "2m")
	cfg := ConfigFromEnv()
	if cfg.YtdlpBin != "/custom/yt-dlp" {
		t.Fatalf("YtdlpBin = %q", cfg.YtdlpBin)
	}
	if cfg.CacheTTL != 2*time.Minute {
		t.Fatalf("CacheTTL = %v, want 2m", cfg.CacheTTL)
	}
}

// TestConfigFromEnvNone verifies unset env yields zero values.
func TestConfigFromEnvNone(t *testing.T) {
	t.Setenv("QMIX_YTDLP_BIN", "")
	t.Setenv("QMIX_STREAM_CACHE_TTL", "")
	if cfg := ConfigFromEnv(); cfg != (Config{}) {
		t.Fatalf("cfg = %+v, want zero value", cfg)
	}
}

// TestConfigFromEnvMalformedTTL verifies a malformed duration is ignored
// (falls back to the default rather than failing).
func TestConfigFromEnvMalformedTTL(t *testing.T) {
	t.Setenv("QMIX_STREAM_CACHE_TTL", "not-a-duration")
	cfg := ConfigFromEnv()
	if cfg.CacheTTL != 0 {
		t.Fatalf("CacheTTL = %v, want 0 (default)", cfg.CacheTTL)
	}
}
