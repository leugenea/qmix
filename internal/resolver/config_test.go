package resolver

import "testing"

// TestConfigFromEnvAll verifies all four variables are read.
func TestConfigFromEnvAll(t *testing.T) {
	t.Setenv("QMIX_VK_TOKEN", "vk")
	t.Setenv("QMIX_YM_TOKEN", "ym")
	t.Setenv("QMIX_SPOTIFY_CLIENT_ID", "id")
	t.Setenv("QMIX_SPOTIFY_CLIENT_SECRET", "secret")
	cfg := ConfigFromEnv()
	if cfg.VKToken != "vk" || cfg.YMToken != "ym" ||
		cfg.SpotifyClientID != "id" || cfg.SpotifyClientSecret != "secret" {
		t.Fatalf("cfg = %+v", cfg)
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
	cfg := ConfigFromEnv()
	if cfg != (Config{}) {
		t.Fatalf("cfg = %+v, want zero value", cfg)
	}
}

// TestDefaultMuxWithConfig verifies the config is passed to the token-consuming
// resolvers at the assembly point.
func TestDefaultMuxWithConfig(t *testing.T) {
	cfg := Config{VKToken: "vk", YMToken: "ym", SpotifyClientID: "id", SpotifyClientSecret: "secret"}
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
	vk, ok := m.matchers[2].Resolver.(*VKYandex)
	if !ok {
		t.Fatalf("matcher[2] resolver = %T, want *VKYandex", m.matchers[2].Resolver)
	}
	if vk.Config != cfg {
		t.Fatalf("vkyandex config = %+v, want %+v", vk.Config, cfg)
	}
}
