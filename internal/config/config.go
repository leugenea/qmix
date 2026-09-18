// Package config owns the process-wide runtime configuration contract.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/leugenea/qmix/internal/logging"
	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
)

// ErrInvalid categorizes a secret-safe startup configuration failure.
var ErrInvalid = errors.New("invalid runtime configuration")

// ValidationDetail is the safe, operator-actionable part of a configuration
// validation failure. Values come only from validation rules, never input.
type ValidationDetail struct {
	Variable string
	Guidance string
}

type invalidError struct {
	detail ValidationDetail
}

func (e *invalidError) Error() string {
	return fmt.Sprintf("%s: invalid %s: %s", ErrInvalid, e.detail.Variable, e.detail.Guidance)
}

func (e *invalidError) Unwrap() error { return ErrInvalid }

// InvalidDetail returns predeclared, input-free guidance from an invalid
// configuration error, including through ordinary error wrapping.
func InvalidDetail(err error) (ValidationDetail, bool) {
	var target *invalidError
	if !errors.As(err, &target) {
		return ValidationDetail{}, false
	}
	return target.detail, true
}

const (
	defaultAddress              = ":8080"
	defaultLogFile              = "qmix.log"
	defaultYTDLPBin             = "yt-dlp"
	defaultStreamCacheTTL       = 5 * time.Minute
	defaultYTDLPMetadataTimeout = 30 * time.Second
	defaultYTDLPSearchTimeout   = 60 * time.Second
	defaultEmptyRoomTTL         = 12 * time.Hour
	defaultNonEmptyRoomTTL      = 24 * time.Hour
	defaultRoomJanitorInterval  = time.Minute
)

// LookupEnv is the environment lookup shape used by Parse.
type LookupEnv func(string) (string, bool)

// Logging contains process-wide structured logging settings.
type Logging struct {
	Level slog.Level
	File  string
}

// Resolver contains optional service credentials. Empty values keep anonymous
// resolution; credential values must never be logged or included in errors.
type Resolver struct {
	VKToken             string
	YMToken             string
	SpotifyClientID     string
	SpotifyClientSecret string
}

// YTDLP contains the one executable setting shared by metadata and streaming,
// plus their independent subprocess deadlines.
type YTDLP struct {
	Binary          string
	MetadataTimeout time.Duration
	SearchTimeout   time.Duration
}

// Stream contains direct audio URL cache settings.
type Stream struct {
	CacheTTL time.Duration
}

// Rooms contains the current process-wide room lifetime settings. They are not
// environment-configurable, but live beside the runtime settings they affect.
type Rooms struct {
	EmptyTTL        time.Duration
	NonEmptyTTL     time.Duration
	JanitorInterval time.Duration
}

// Config is the single typed startup configuration for the backend process.
type Config struct {
	Address  string
	Logging  Logging
	Resolver Resolver
	YTDLP    YTDLP
	Stream   Stream
	Rooms    Rooms
}

// LoggingConfig adapts the startup aggregate to the logging package.
func (c Config) LoggingConfig() logging.Config {
	return logging.Config{Level: c.Logging.Level, File: c.Logging.File}
}

// ResolverConfig adapts the startup aggregate to resolver construction.
func (c Config) ResolverConfig() resolver.Config {
	return resolver.Config{
		VKToken:              c.Resolver.VKToken,
		YMToken:              c.Resolver.YMToken,
		SpotifyClientID:      c.Resolver.SpotifyClientID,
		SpotifyClientSecret:  c.Resolver.SpotifyClientSecret,
		YTDLPBin:             c.YTDLP.Binary,
		YTDLPMetadataTimeout: c.YTDLP.MetadataTimeout,
	}
}

// StreamConfig adapts the startup aggregate to stream backend construction.
func (c Config) StreamConfig() stream.Config {
	return stream.Config{YtdlpBin: c.YTDLP.Binary, CacheTTL: c.Stream.CacheTTL, YTDLPSearchTimeout: c.YTDLP.SearchTimeout}
}

// Load parses the process environment once.
func Load() (Config, error) {
	return Parse(os.LookupEnv)
}

// Parse builds a Config from an environment lookup. Missing or blank values use
// documented defaults. Explicit malformed values fail without echoing values.
func Parse(lookup LookupEnv) (Config, error) {
	cfg := Config{
		Address: defaultAddress,
		Logging: Logging{Level: slog.LevelWarn, File: defaultLogFile},
		YTDLP:   YTDLP{Binary: defaultYTDLPBin, MetadataTimeout: defaultYTDLPMetadataTimeout, SearchTimeout: defaultYTDLPSearchTimeout},
		Stream:  Stream{CacheTTL: defaultStreamCacheTTL},
		Rooms:   Rooms{EmptyTTL: defaultEmptyRoomTTL, NonEmptyTTL: defaultNonEmptyRoomTTL, JanitorInterval: defaultRoomJanitorInterval},
	}

	if v := value(lookup, "QMIX_ADDR"); v != "" {
		cfg.Address = v
	}
	if v := value(lookup, "QMIX_LOG_FILE"); v != "" {
		cfg.Logging.File = v
	}
	if raw := value(lookup, "QMIX_LOG_LEVEL"); raw != "" {
		level, ok := logLevel(raw)
		if !ok {
			return Config{}, invalid("QMIX_LOG_LEVEL", "use debug, info, warn, or error")
		}
		cfg.Logging.Level = level
	}

	cfg.Resolver.VKToken = rawValue(lookup, "QMIX_VK_TOKEN")
	cfg.Resolver.YMToken = rawValue(lookup, "QMIX_YM_TOKEN")
	cfg.Resolver.SpotifyClientID = rawValue(lookup, "QMIX_SPOTIFY_CLIENT_ID")
	cfg.Resolver.SpotifyClientSecret = rawValue(lookup, "QMIX_SPOTIFY_CLIENT_SECRET")
	if bin := value(lookup, "QMIX_YTDLP_BIN"); bin != "" {
		cfg.YTDLP.Binary = bin
	}

	var err error
	if cfg.Stream.CacheTTL, err = duration(lookup, "QMIX_STREAM_CACHE_TTL", defaultStreamCacheTTL, true); err != nil {
		return Config{}, err
	}
	if cfg.YTDLP.MetadataTimeout, err = duration(lookup, "QMIX_YTDLP_METADATA_TIMEOUT", defaultYTDLPMetadataTimeout, false); err != nil {
		return Config{}, err
	}
	if cfg.YTDLP.SearchTimeout, err = duration(lookup, "QMIX_YTDLP_SEARCH_TIMEOUT", defaultYTDLPSearchTimeout, false); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func rawValue(lookup LookupEnv, name string) string {
	value, _ := lookup(name)
	return value
}

func value(lookup LookupEnv, name string) string {
	return strings.TrimSpace(rawValue(lookup, name))
}

func duration(lookup LookupEnv, name string, fallback time.Duration, allowNegative bool) (time.Duration, error) {
	raw := value(lookup, name)
	if raw == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(raw)
	if err != nil {
		return 0, invalid(name, "use Go duration syntax")
	}
	if parsed == 0 {
		if allowNegative {
			return 0, invalid(name, "must be non-zero; negative disables caching")
		}
		return 0, invalid(name, "must be greater than zero")
	}
	if parsed < 0 && !allowNegative {
		return 0, invalid(name, "must be greater than zero")
	}
	return parsed, nil
}

func logLevel(raw string) (slog.Level, bool) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "debug":
		return slog.LevelDebug, true
	case "info":
		return slog.LevelInfo, true
	case "warn", "warning":
		return slog.LevelWarn, true
	case "error":
		return slog.LevelError, true
	default:
		return 0, false
	}
}

func invalid(variable, guidance string) error {
	return &invalidError{detail: ValidationDetail{Variable: variable, Guidance: guidance}}
}
