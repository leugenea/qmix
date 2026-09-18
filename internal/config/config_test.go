package config

import (
	"log/slog"
	"strings"
	"testing"
	"time"
)

func TestParseRuntimeEnvironment(t *testing.T) {
	tests := []struct {
		name string
		env  map[string]string
		want Config
	}{
		{
			name: "documented defaults",
			want: Config{
				Address: ":8080",
				Logging: Logging{Level: slog.LevelWarn, File: "qmix.log"},
				YTDLP:   YTDLP{Binary: "yt-dlp", MetadataTimeout: 30 * time.Second, SearchTimeout: 60 * time.Second},
				Stream:  Stream{CacheTTL: 5 * time.Minute},
				Rooms:   Rooms{EmptyTTL: 12 * time.Hour, NonEmptyTTL: 24 * time.Hour, JanitorInterval: time.Minute},
			},
		},
		{
			name: "all runtime variables",
			env: map[string]string{
				"QMIX_ADDR":                   "127.0.0.1:9000",
				"QMIX_LOG_LEVEL":              "INFO",
				"QMIX_LOG_FILE":               "/safe/qmix.log",
				"QMIX_VK_TOKEN":               "vk-secret",
				"QMIX_YM_TOKEN":               "ym-secret",
				"QMIX_SPOTIFY_CLIENT_ID":      "client-id",
				"QMIX_SPOTIFY_CLIENT_SECRET":  "client-secret",
				"QMIX_YTDLP_BIN":              "/opt/bin/yt-dlp",
				"QMIX_STREAM_CACHE_TTL":       "-1s",
				"QMIX_YTDLP_METADATA_TIMEOUT": "17s",
				"QMIX_YTDLP_SEARCH_TIMEOUT":   "41s",
			},
			want: Config{
				Address: "127.0.0.1:9000",
				Logging: Logging{Level: slog.LevelInfo, File: "/safe/qmix.log"},
				Resolver: Resolver{
					VKToken: "vk-secret", YMToken: "ym-secret",
					SpotifyClientID: "client-id", SpotifyClientSecret: "client-secret",
				},
				YTDLP:  YTDLP{Binary: "/opt/bin/yt-dlp", MetadataTimeout: 17 * time.Second, SearchTimeout: 41 * time.Second},
				Stream: Stream{CacheTTL: -time.Second},
				Rooms:  Rooms{EmptyTTL: 12 * time.Hour, NonEmptyTTL: 24 * time.Hour, JanitorInterval: time.Minute},
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := Parse(mapEnvironment(tc.env))
			if err != nil {
				t.Fatal(err)
			}
			if got != tc.want {
				t.Fatalf("config = %#v, want %#v", got, tc.want)
			}
		})
	}
}

func TestParseAcceptsDocumentedLogLevels(t *testing.T) {
	for input, want := range map[string]slog.Level{
		"debug":   slog.LevelDebug,
		"INFO":    slog.LevelInfo,
		"warn":    slog.LevelWarn,
		"warning": slog.LevelWarn,
		"error":   slog.LevelError,
	} {
		t.Run(input, func(t *testing.T) {
			cfg, err := Parse(mapEnvironment(map[string]string{"QMIX_LOG_LEVEL": input}))
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Logging.Level != want {
				t.Fatalf("level = %v, want %v", cfg.Logging.Level, want)
			}
		})
	}
}

func TestParseCacheZeroExplainsHowToDisableCaching(t *testing.T) {
	_, err := Parse(mapEnvironment(map[string]string{"QMIX_STREAM_CACHE_TTL": "0s"}))
	if err == nil || !strings.Contains(err.Error(), "negative disables caching") {
		t.Fatalf("error = %v, want actionable cache guidance", err)
	}
}

func TestParseRejectsInvalidValuesWithoutEchoingThem(t *testing.T) {
	secret := "token=SENTINEL_DO_NOT_LOG"
	tests := []struct {
		name     string
		variable string
		value    string
	}{
		{name: "log level", variable: "QMIX_LOG_LEVEL", value: secret},
		{name: "cache malformed", variable: "QMIX_STREAM_CACHE_TTL", value: secret},
		{name: "cache zero", variable: "QMIX_STREAM_CACHE_TTL", value: "0s"},
		{name: "metadata malformed", variable: "QMIX_YTDLP_METADATA_TIMEOUT", value: secret},
		{name: "metadata zero", variable: "QMIX_YTDLP_METADATA_TIMEOUT", value: "0s"},
		{name: "metadata negative", variable: "QMIX_YTDLP_METADATA_TIMEOUT", value: "-1s"},
		{name: "search malformed", variable: "QMIX_YTDLP_SEARCH_TIMEOUT", value: secret},
		{name: "search zero", variable: "QMIX_YTDLP_SEARCH_TIMEOUT", value: "0s"},
		{name: "search negative", variable: "QMIX_YTDLP_SEARCH_TIMEOUT", value: "-1s"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Parse(mapEnvironment(map[string]string{tc.variable: tc.value}))
			if err == nil || !strings.Contains(err.Error(), tc.variable) {
				t.Fatalf("error = %v, want actionable %s error", err, tc.variable)
			}
			if strings.Contains(err.Error(), tc.value) || strings.Contains(err.Error(), secret) {
				t.Fatalf("error echoed environment value: %q", err)
			}
		})
	}
}

func mapEnvironment(values map[string]string) LookupEnv {
	return func(name string) (string, bool) {
		value, ok := values[name]
		return value, ok
	}
}
