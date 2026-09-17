package resolver

import (
	"testing"
	"time"
)

// TestConfigFromEnvAll verifies credentials and the metadata timeout are read.
func TestConfigFromEnvAll(t *testing.T) {
	t.Setenv("QMIX_VK_TOKEN", "vk")
	t.Setenv("QMIX_YM_TOKEN", "ym")
	t.Setenv("QMIX_SPOTIFY_CLIENT_ID", "id")
	t.Setenv("QMIX_SPOTIFY_CLIENT_SECRET", "secret")
	t.Setenv("QMIX_YTDLP_METADATA_TIMEOUT", "17s")
	cfg := ConfigFromEnv()
	if cfg.VKToken != "vk" || cfg.YMToken != "ym" ||
		cfg.SpotifyClientID != "id" || cfg.SpotifyClientSecret != "secret" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if cfg.YTDLPMetadataTimeout != 17*time.Second {
		t.Fatalf("YTDLPMetadataTimeout = %v, want 17s", cfg.YTDLPMetadataTimeout)
	}
}

// TestConfigFromEnvPartial verifies a subset of variables is read and the rest
// stay empty.
func TestConfigFromEnvPartial(t *testing.T) {
	t.Setenv("QMIX_VK_TOKEN", "vk")
	t.Setenv("QMIX_SPOTIFY_CLIENT_ID", "id")
	cfg := ConfigFromEnv()
	if cfg.VKToken != "vk" || cfg.SpotifyClientID != "id" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if cfg.YMToken != "" || cfg.SpotifyClientSecret != "" {
		t.Fatalf("cfg = %+v, want empty for unset vars", cfg)
	}
}

// TestConfigFromEnvNone verifies all variables unset yields an empty config.
func TestConfigFromEnvNone(t *testing.T) {
	t.Setenv("QMIX_VK_TOKEN", "")
	t.Setenv("QMIX_YM_TOKEN", "")
	t.Setenv("QMIX_SPOTIFY_CLIENT_ID", "")
	t.Setenv("QMIX_SPOTIFY_CLIENT_SECRET", "")
	t.Setenv("QMIX_YTDLP_METADATA_TIMEOUT", "")
	cfg := ConfigFromEnv()
	if cfg != (Config{}) {
		t.Fatalf("cfg = %+v, want zero value", cfg)
	}
}

func TestConfigFromEnvMalformedYTDLPMetadataTimeoutUsesDefault(t *testing.T) {
	t.Setenv("QMIX_YTDLP_METADATA_TIMEOUT", "not-a-duration")
	if got := ConfigFromEnv().YTDLPMetadataTimeout; got != 0 {
		t.Fatalf("YTDLPMetadataTimeout = %v, want zero/default", got)
	}
}

// TestDefaultMuxWithConfig verifies the config is passed to the token-consuming
// resolvers at the assembly point.
func TestDefaultMuxWithConfig(t *testing.T) {
	cfg := Config{VKToken: "vk", YMToken: "ym", SpotifyClientID: "id", SpotifyClientSecret: "secret", YTDLPMetadataTimeout: 17 * time.Second}
	m := DefaultMuxWithConfig(cfg)
	if m == nil {
		t.Fatal("DefaultMuxWithConfig returned nil")
	}
	if len(m.matchers) != 3 {
		t.Fatalf("matchers = %d, want 3", len(m.matchers))
	}
	sp, ok := m.matchers[0].Resolver.(*Spotify)
	if !ok {
		t.Fatalf("matcher[0] resolver = %T, want *Spotify", m.matchers[0].Resolver)
	}
	if sp.Config != cfg {
		t.Fatalf("spotify config = %+v, want %+v", sp.Config, cfg)
	}
	yt, ok := m.matchers[1].Resolver.(*YouTube)
	if !ok {
		t.Fatalf("matcher[1] resolver = %T, want *YouTube", m.matchers[1].Resolver)
	}
	if yt.Timeout != cfg.YTDLPMetadataTimeout {
		t.Fatalf("youtube timeout = %v, want %v", yt.Timeout, cfg.YTDLPMetadataTimeout)
	}
	vk, ok := m.matchers[2].Resolver.(*VKYandex)
	if !ok {
		t.Fatalf("matcher[2] resolver = %T, want *VKYandex", m.matchers[2].Resolver)
	}
	if vk.Config != cfg {
		t.Fatalf("vkyandex config = %+v, want %+v", vk.Config, cfg)
	}
}
