package config

import (
	"errors"
	"log/slog"
	"net/netip"
	"reflect"
	"strconv"
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
				Address:        ":8080",
				Logging:        Logging{Level: slog.LevelWarn, File: "qmix.log"},
				YTDLP:          YTDLP{Binary: "yt-dlp", MetadataTimeout: 30 * time.Second, SearchTimeout: 60 * time.Second, MaxConcurrent: 2, QueueLimit: 8},
				Stream:         Stream{CacheTTL: 5 * time.Minute},
				Rooms:          Rooms{EmptyTTL: 12 * time.Hour, NonEmptyTTL: 24 * time.Hour, JanitorInterval: time.Minute},
				RoomCreation:   RoomCreation{RatePerMinute: 10, Burst: 5, IdentityLimit: 4096},
				RoomSubmission: RoomSubmission{RatePerMinute: 30, Burst: 10},
			},
		},
		{
			name: "all runtime variables",
			env: map[string]string{
				"QMIX_ADDR":                            "127.0.0.1:9000",
				"QMIX_LOG_LEVEL":                       "INFO",
				"QMIX_LOG_FILE":                        "/safe/qmix.log",
				"QMIX_VK_TOKEN":                        "vk-secret",
				"QMIX_YM_TOKEN":                        "ym-secret",
				"QMIX_SPOTIFY_CLIENT_ID":               "client-id",
				"QMIX_SPOTIFY_CLIENT_SECRET":           "client-secret",
				"QMIX_YTDLP_BIN":                       "/opt/bin/yt-dlp",
				"QMIX_STREAM_CACHE_TTL":                "-1s",
				"QMIX_YTDLP_METADATA_TIMEOUT":          "17s",
				"QMIX_YTDLP_SEARCH_TIMEOUT":            "41s",
				"QMIX_YTDLP_MAX_CONCURRENT":            "5",
				"QMIX_YTDLP_QUEUE_LIMIT":               "9",
				"QMIX_ROOM_CREATE_RATE_PER_MINUTE":     "30",
				"QMIX_ROOM_CREATE_BURST":               "7",
				"QMIX_ROOM_CREATE_IDENTITY_LIMIT":      "99",
				"QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE": "45",
				"QMIX_ROOM_SUBMISSION_BURST":           "12",
				"QMIX_TRUSTED_PROXY_CIDRS":             "10.0.0.0/8, 2001:db8::/32",
			},
			want: Config{
				Address: "127.0.0.1:9000",
				Logging: Logging{Level: slog.LevelInfo, File: "/safe/qmix.log"},
				Resolver: Resolver{
					VKToken: "vk-secret", YMToken: "ym-secret",
					SpotifyClientID: "client-id", SpotifyClientSecret: "client-secret",
				},
				YTDLP:  YTDLP{Binary: "/opt/bin/yt-dlp", MetadataTimeout: 17 * time.Second, SearchTimeout: 41 * time.Second, MaxConcurrent: 5, QueueLimit: 9},
				Stream: Stream{CacheTTL: -time.Second},
				Rooms:  Rooms{EmptyTTL: 12 * time.Hour, NonEmptyTTL: 24 * time.Hour, JanitorInterval: time.Minute},
				RoomCreation: RoomCreation{
					RatePerMinute: 30,
					Burst:         7,
					IdentityLimit: 99,
					TrustedProxyCIDRs: []netip.Prefix{
						netip.MustParsePrefix("10.0.0.0/8"),
						netip.MustParsePrefix("2001:db8::/32"),
					},
				},
				RoomSubmission: RoomSubmission{RatePerMinute: 45, Burst: 12},
			},
		},
		{
			name: "documented capacity defaults",
			env: map[string]string{
				"QMIX_YTDLP_MAX_CONCURRENT": "2",
				"QMIX_YTDLP_QUEUE_LIMIT":    "8",
			},
			want: Config{
				Address:        ":8080",
				Logging:        Logging{Level: slog.LevelWarn, File: "qmix.log"},
				YTDLP:          YTDLP{Binary: "yt-dlp", MetadataTimeout: 30 * time.Second, SearchTimeout: 60 * time.Second, MaxConcurrent: 2, QueueLimit: 8},
				Stream:         Stream{CacheTTL: 5 * time.Minute},
				Rooms:          Rooms{EmptyTTL: 12 * time.Hour, NonEmptyTTL: 24 * time.Hour, JanitorInterval: time.Minute},
				RoomCreation:   RoomCreation{RatePerMinute: 10, Burst: 5, IdentityLimit: 4096},
				RoomSubmission: RoomSubmission{RatePerMinute: 30, Burst: 10},
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := Parse(mapEnvironment(tc.env))
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, tc.want) {
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

func TestParseQueueLimitContract(t *testing.T) {
	tests := []struct {
		name    string
		env     map[string]string
		want    int
		wantErr bool
	}{
		{name: "missing", want: defaultYTDLPQueueLimit},
		{name: "blank", env: map[string]string{"QMIX_YTDLP_QUEUE_LIMIT": "  "}, want: defaultYTDLPQueueLimit},
		{name: "zero", env: map[string]string{"QMIX_YTDLP_QUEUE_LIMIT": "0"}, want: defaultYTDLPQueueLimit},
		{name: "negative", env: map[string]string{"QMIX_YTDLP_QUEUE_LIMIT": "-1"}, want: defaultYTDLPQueueLimit},
		{name: "positive", env: map[string]string{"QMIX_YTDLP_QUEUE_LIMIT": "3"}, want: 3},
		{name: "malformed", env: map[string]string{"QMIX_YTDLP_QUEUE_LIMIT": "not-an-integer"}, wantErr: true},
		{name: "overflow", env: map[string]string{"QMIX_YTDLP_QUEUE_LIMIT": "99999999999999999999"}, wantErr: true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := Parse(mapEnvironment(tc.env))
			if tc.wantErr {
				if !errors.Is(err, ErrInvalid) {
					t.Fatalf("Parse() error = %v, want ErrInvalid", err)
				}
				detail, ok := InvalidDetail(err)
				if !ok || detail != (ValidationDetail{Variable: "QMIX_YTDLP_QUEUE_LIMIT", Guidance: "must be an integer"}) {
					t.Fatalf("InvalidDetail() = %#v, %v, want queue variable and predeclared guidance", detail, ok)
				}
				return
			}
			if err != nil {
				t.Fatalf("Parse() error = %v, want nil", err)
			}
			if cfg.YTDLP.QueueLimit != tc.want {
				t.Fatalf("QueueLimit = %d, want %d", cfg.YTDLP.QueueLimit, tc.want)
			}
		})
	}
}

func TestParseBoundsRoomCreationSettings(t *testing.T) {
	tests := []struct {
		name     string
		variable string
		maximum  int
		read     func(Config) int
	}{
		{
			name: "rate per minute", variable: "QMIX_ROOM_CREATE_RATE_PER_MINUTE", maximum: 60_000,
			read: func(cfg Config) int { return cfg.RoomCreation.RatePerMinute },
		},
		{
			name: "burst", variable: "QMIX_ROOM_CREATE_BURST", maximum: 10_000,
			read: func(cfg Config) int { return cfg.RoomCreation.Burst },
		},
		{
			name: "identity limit", variable: "QMIX_ROOM_CREATE_IDENTITY_LIMIT", maximum: 65_536,
			read: func(cfg Config) int { return cfg.RoomCreation.IdentityLimit },
		},
		{
			name: "submission rate per minute", variable: "QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE", maximum: 60_000,
			read: func(cfg Config) int { return cfg.RoomSubmission.RatePerMinute },
		},
		{
			name: "submission burst", variable: "QMIX_ROOM_SUBMISSION_BURST", maximum: 10_000,
			read: func(cfg Config) int { return cfg.RoomSubmission.Burst },
		},
	}

	for _, tc := range tests {
		t.Run(tc.name+" accepts maximum", func(t *testing.T) {
			cfg, err := Parse(mapEnvironment(map[string]string{tc.variable: strconv.Itoa(tc.maximum)}))
			if err != nil {
				t.Fatalf("Parse() error = %v, want nil", err)
			}
			if got := tc.read(cfg); got != tc.maximum {
				t.Fatalf("configured value = %d, want %d", got, tc.maximum)
			}
		})
		t.Run(tc.name+" rejects above maximum safely", func(t *testing.T) {
			supplied := strconv.Itoa(tc.maximum + 1)
			_, err := Parse(mapEnvironment(map[string]string{tc.variable: supplied}))
			if !errors.Is(err, ErrInvalid) {
				t.Fatalf("Parse() error = %v, want ErrInvalid", err)
			}
			detail, ok := InvalidDetail(err)
			if !ok || detail.Variable != tc.variable || !strings.Contains(detail.Guidance, strconv.Itoa(tc.maximum)) {
				t.Fatalf("InvalidDetail() = %#v, %v, want safe %s maximum guidance", detail, ok, tc.variable)
			}
			if strings.Contains(err.Error(), supplied) {
				t.Fatalf("error echoed supplied value: %q", err)
			}
		})
	}
}

func TestParseCanonicalizesMappedTrustedProxyCIDRBoundariesAndHostBits(t *testing.T) {
	for _, tc := range []struct {
		name     string
		supplied string
		want     string
	}{
		{name: "slash 96", supplied: "::ffff:192.0.2.129/96", want: "0.0.0.0/0"},
		{name: "masked host bits", supplied: "::ffff:192.0.2.129/120", want: "192.0.2.0/24"},
		{name: "slash 128", supplied: "::ffff:192.0.2.129/128", want: "192.0.2.129/32"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := Parse(mapEnvironment(map[string]string{
				"QMIX_TRUSTED_PROXY_CIDRS": tc.supplied,
			}))
			if err != nil {
				t.Fatalf("Parse() error = %v, want nil", err)
			}
			want := []netip.Prefix{netip.MustParsePrefix(tc.want)}
			if !reflect.DeepEqual(cfg.RoomCreation.TrustedProxyCIDRs, want) {
				t.Fatalf("TrustedProxyCIDRs = %v, want %v", cfg.RoomCreation.TrustedProxyCIDRs, want)
			}
		})
	}
}

func TestParseRejectsNonRepresentableMappedTrustedProxyCIDRSafely(t *testing.T) {
	supplied := "::ffff:192.0.2.129/80"
	_, err := Parse(mapEnvironment(map[string]string{"QMIX_TRUSTED_PROXY_CIDRS": supplied}))
	if !errors.Is(err, ErrInvalid) {
		t.Fatalf("Parse() error = %v, want ErrInvalid", err)
	}
	detail, ok := InvalidDetail(err)
	if !ok || detail.Variable != "QMIX_TRUSTED_PROXY_CIDRS" {
		t.Fatalf("InvalidDetail() = %#v, %v", detail, ok)
	}
	if strings.Contains(err.Error(), supplied) {
		t.Fatalf("error echoed supplied value: %q", err)
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
		{name: "max concurrent malformed", variable: "QMIX_YTDLP_MAX_CONCURRENT", value: secret},
		{name: "max concurrent zero", variable: "QMIX_YTDLP_MAX_CONCURRENT", value: "0"},
		{name: "max concurrent negative", variable: "QMIX_YTDLP_MAX_CONCURRENT", value: "-2"},
		{name: "max concurrent overflow", variable: "QMIX_YTDLP_MAX_CONCURRENT", value: "99999999999999999999"},
		{name: "queue limit malformed", variable: "QMIX_YTDLP_QUEUE_LIMIT", value: secret},
		{name: "queue limit overflow", variable: "QMIX_YTDLP_QUEUE_LIMIT", value: "99999999999999999999"},
		{name: "room rate zero", variable: "QMIX_ROOM_CREATE_RATE_PER_MINUTE", value: "0"},
		{name: "room rate malformed", variable: "QMIX_ROOM_CREATE_RATE_PER_MINUTE", value: secret},
		{name: "room burst negative", variable: "QMIX_ROOM_CREATE_BURST", value: "-1"},
		{name: "identity limit overflow", variable: "QMIX_ROOM_CREATE_IDENTITY_LIMIT", value: "99999999999999999999"},
		{name: "submission rate zero", variable: "QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE", value: "0"},
		{name: "submission rate malformed", variable: "QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE", value: secret},
		{name: "submission burst negative", variable: "QMIX_ROOM_SUBMISSION_BURST", value: "-1"},
		{name: "submission burst overflow", variable: "QMIX_ROOM_SUBMISSION_BURST", value: "99999999999999999999"},
		{name: "trusted proxy malformed", variable: "QMIX_TRUSTED_PROXY_CIDRS", value: secret},
		{name: "trusted proxy ambiguous empty member", variable: "QMIX_TRUSTED_PROXY_CIDRS", value: "10.0.0.0/8,,192.0.2.0/24"},
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
