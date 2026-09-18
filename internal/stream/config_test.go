package stream

import (
	"testing"
	"time"
)

func TestConfigCarriesCompositionRootSettings(t *testing.T) {
	cfg := Config{
		YtdlpBin:           "/configured/yt-dlp",
		CacheTTL:           -time.Second,
		YTDLPSearchTimeout: 41 * time.Second,
	}
	if cfg.YtdlpBin != "/configured/yt-dlp" || cfg.CacheTTL != -time.Second || cfg.YTDLPSearchTimeout != 41*time.Second {
		t.Fatalf("config = %+v", cfg)
	}
}
