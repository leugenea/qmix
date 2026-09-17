package stream

import (
	"testing"
	"time"
)

// TestConfigFromEnvAll verifies bin and cache TTL are read from the env.
func TestConfigFromEnvAll(t *testing.T) {
	t.Setenv("QMIX_YTDLP_BIN", "/custom/yt-dlp")
	t.Setenv("QMIX_STREAM_CACHE_TTL", "2m")
	t.Setenv("QMIX_YTDLP_SEARCH_TIMEOUT", "41s")
	cfg := ConfigFromEnv()
	if cfg.YtdlpBin != "/custom/yt-dlp" {
		t.Fatalf("YtdlpBin = %q", cfg.YtdlpBin)
	}
	if cfg.CacheTTL != 2*time.Minute {
		t.Fatalf("CacheTTL = %v, want 2m", cfg.CacheTTL)
	}
	if cfg.YTDLPSearchTimeout != 41*time.Second {
		t.Fatalf("YTDLPSearchTimeout = %v, want 41s", cfg.YTDLPSearchTimeout)
	}
}

// TestConfigFromEnvNone verifies unset env yields zero values.
func TestConfigFromEnvNone(t *testing.T) {
	t.Setenv("QMIX_YTDLP_BIN", "")
	t.Setenv("QMIX_STREAM_CACHE_TTL", "")
	t.Setenv("QMIX_YTDLP_SEARCH_TIMEOUT", "")
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

func TestConfigFromEnvMalformedSearchTimeoutUsesDefault(t *testing.T) {
	t.Setenv("QMIX_YTDLP_SEARCH_TIMEOUT", "not-a-duration")
	if got := ConfigFromEnv().YTDLPSearchTimeout; got != 0 {
		t.Fatalf("YTDLPSearchTimeout = %v, want zero/default", got)
	}
}
