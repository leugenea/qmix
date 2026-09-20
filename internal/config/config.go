// Package config owns the process-wide runtime configuration contract.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/leugenea/qmix/internal/logging"
	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/roomcreate"
	"github.com/leugenea/qmix/internal/roomsubmission"
	"github.com/leugenea/qmix/internal/stream"
	"github.com/leugenea/qmix/internal/ytdlpcap"
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
	// defaultYTDLPMaxConcurrent bounds concurrent yt-dlp subprocesses. The
	// Compose service is limited to 512 MiB while one yt-dlp process can
	// spike to 100–200 MiB RSS, so 2 keeps the worst case near 400 MiB with
	// headroom for the Go runtime and the audio proxy buffers (qmix#130).
	defaultYTDLPMaxConcurrent = 2
	// defaultYTDLPQueueLimit bounds callers waiting for a subprocess slot.
	// 8 admits a burst of ~10 simultaneous lookups before rejecting with 503
	// (qmix#130); deeper bursts are told to retry rather than queued into
	// unbounded request latency.
	defaultYTDLPQueueLimit     = 8
	defaultEmptyRoomTTL        = 12 * time.Hour
	defaultNonEmptyRoomTTL     = 24 * time.Hour
	defaultRoomJanitorInterval = time.Minute
	defaultRoomCreateRate      = 10
	defaultRoomCreateBurst     = 5
	defaultRoomIdentityLimit   = 4096
	defaultRoomSubmissionRate  = 30
	defaultRoomSubmissionBurst = 10
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
// plus their independent subprocess deadlines and the process-wide capacity
// bound for concurrent yt-dlp subprocesses (qmix#130).
type YTDLP struct {
	Binary          string
	MetadataTimeout time.Duration
	SearchTimeout   time.Duration
	// MaxConcurrent is how many yt-dlp subprocesses may run at once across
	// metadata resolution and streaming. Positive values enable the shared
	// capacity limiter.
	MaxConcurrent int
	// QueueLimit is how many callers may wait for a subprocess slot before
	// further lookups are rejected with 503 (qmix#130).
	QueueLimit int
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

// RoomCreation contains the process-wide admission and proxy trust policy for
// POST /rooms (qmix#155). TrustedProxyCIDRs is parsed once at startup.
type RoomCreation struct {
	RatePerMinute     int
	Burst             int
	IdentityLimit     int
	TrustedProxyCIDRs []netip.Prefix
}

// RoomSubmission contains per-room queue-submission token-bucket settings.
type RoomSubmission struct {
	RatePerMinute int
	Burst         int
}

// Config is the single typed startup configuration for the backend process.
type Config struct {
	Address        string
	Logging        Logging
	Resolver       Resolver
	YTDLP          YTDLP
	Stream         Stream
	Rooms          Rooms
	RoomCreation   RoomCreation
	RoomSubmission RoomSubmission
}

// LoggingConfig adapts the startup aggregate to the logging package.
func (c Config) LoggingConfig() logging.Config {
	return logging.Config{Level: c.Logging.Level, File: c.Logging.File}
}

// ResolverConfig adapts the startup aggregate to resolver construction,
// including the shared yt-dlp capacity limiter (qmix#130).
func (c Config) ResolverConfig(limiter *ytdlpcap.Limiter) resolver.Config {
	return resolver.Config{
		VKToken:              c.Resolver.VKToken,
		YMToken:              c.Resolver.YMToken,
		SpotifyClientID:      c.Resolver.SpotifyClientID,
		SpotifyClientSecret:  c.Resolver.SpotifyClientSecret,
		YTDLPBin:             c.YTDLP.Binary,
		YTDLPMetadataTimeout: c.YTDLP.MetadataTimeout,
		YTDLPLimiter:         limiter,
	}
}

// StreamConfig adapts the startup aggregate to stream backend construction,
// including the shared yt-dlp capacity limiter (qmix#130).
func (c Config) StreamConfig(limiter *ytdlpcap.Limiter) stream.Config {
	return stream.Config{
		YtdlpBin:           c.YTDLP.Binary,
		CacheTTL:           c.Stream.CacheTTL,
		YTDLPSearchTimeout: c.YTDLP.SearchTimeout,
		YTDLPLimiter:       limiter,
	}
}

// YTDLPCapacityLimiter builds the one shared capacity limiter for all yt-dlp
// subprocesses (qmix#130). Metadata resolution and streaming both consume it.
// A non-positive configured maximum disables the bound, keeping the pre-#130
// behavior.
func (c Config) YTDLPCapacityLimiter() *ytdlpcap.Limiter {
	return ytdlpcap.New(c.YTDLP.MaxConcurrent, c.YTDLP.QueueLimit)
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
		YTDLP: YTDLP{
			Binary:          defaultYTDLPBin,
			MetadataTimeout: defaultYTDLPMetadataTimeout,
			SearchTimeout:   defaultYTDLPSearchTimeout,
			MaxConcurrent:   defaultYTDLPMaxConcurrent,
			QueueLimit:      defaultYTDLPQueueLimit,
		},
		Stream: Stream{CacheTTL: defaultStreamCacheTTL},
		Rooms:  Rooms{EmptyTTL: defaultEmptyRoomTTL, NonEmptyTTL: defaultNonEmptyRoomTTL, JanitorInterval: defaultRoomJanitorInterval},
		RoomCreation: RoomCreation{
			RatePerMinute: defaultRoomCreateRate,
			Burst:         defaultRoomCreateBurst,
			IdentityLimit: defaultRoomIdentityLimit,
		},
		RoomSubmission: RoomSubmission{
			RatePerMinute: defaultRoomSubmissionRate,
			Burst:         defaultRoomSubmissionBurst,
		},
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
	if cfg.YTDLP.MaxConcurrent, err = positiveInt(lookup, "QMIX_YTDLP_MAX_CONCURRENT", defaultYTDLPMaxConcurrent); err != nil {
		return Config{}, err
	}
	if cfg.YTDLP.QueueLimit, err = queueLimit(lookup, "QMIX_YTDLP_QUEUE_LIMIT", defaultYTDLPQueueLimit); err != nil {
		return Config{}, err
	}
	if cfg.RoomCreation.RatePerMinute, err = boundedPositiveInt(lookup, "QMIX_ROOM_CREATE_RATE_PER_MINUTE", defaultRoomCreateRate, roomcreate.MaxRatePerMinute); err != nil {
		return Config{}, err
	}
	if cfg.RoomCreation.Burst, err = boundedPositiveInt(lookup, "QMIX_ROOM_CREATE_BURST", defaultRoomCreateBurst, roomcreate.MaxBurst); err != nil {
		return Config{}, err
	}
	if cfg.RoomCreation.IdentityLimit, err = boundedPositiveInt(lookup, "QMIX_ROOM_CREATE_IDENTITY_LIMIT", defaultRoomIdentityLimit, roomcreate.MaxIdentityLimit); err != nil {
		return Config{}, err
	}
	if cfg.RoomSubmission.RatePerMinute, err = boundedPositiveInt(lookup, "QMIX_ROOM_SUBMISSION_RATE_PER_MINUTE", defaultRoomSubmissionRate, roomsubmission.MaxRatePerMinute); err != nil {
		return Config{}, err
	}
	if cfg.RoomSubmission.Burst, err = boundedPositiveInt(lookup, "QMIX_ROOM_SUBMISSION_BURST", defaultRoomSubmissionBurst, roomsubmission.MaxBurst); err != nil {
		return Config{}, err
	}
	if cfg.RoomCreation.TrustedProxyCIDRs, err = cidrList(lookup, "QMIX_TRUSTED_PROXY_CIDRS"); err != nil {
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

// positiveInt parses a strictly positive integer runtime setting. Missing or
// blank values use the documented default; explicit zero, negative, or
// malformed values fail startup without echoing the supplied value.
func positiveInt(lookup LookupEnv, name string, fallback int) (int, error) {
	raw := value(lookup, name)
	if raw == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(raw)
	if err != nil || parsed <= 0 {
		return 0, invalid(name, "must be a positive integer")
	}
	return parsed, nil
}

// boundedPositiveInt additionally rejects values above an explicit safe
// maximum without echoing the supplied value.
func boundedPositiveInt(lookup LookupEnv, name string, fallback, maximum int) (int, error) {
	parsed, err := positiveInt(lookup, name, fallback)
	if err != nil {
		return 0, err
	}
	if parsed > maximum {
		return 0, invalid(name, fmt.Sprintf("must be at most %d", maximum))
	}
	return parsed, nil
}

// queueLimit parses the bounded-wait queue size. Missing, blank, zero, and
// negative values use the documented safe default; malformed values fail
// startup without echoing the supplied value.
func queueLimit(lookup LookupEnv, name string, fallback int) (int, error) {
	raw := value(lookup, name)
	if raw == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(raw)
	if err != nil {
		return 0, invalid(name, "must be an integer")
	}
	if parsed <= 0 {
		return fallback, nil
	}
	return parsed, nil
}

func cidrList(lookup LookupEnv, name string) ([]netip.Prefix, error) {
	raw := value(lookup, name)
	if raw == "" {
		return nil, nil
	}
	parts := strings.Split(raw, ",")
	prefixes := make([]netip.Prefix, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		prefix, err := netip.ParsePrefix(part)
		if err != nil || part == "" {
			return nil, invalid(name, "use a comma-separated list of IPv4 or IPv6 CIDR prefixes")
		}
		canonical, ok := roomcreate.CanonicalTrustedPrefix(prefix)
		if !ok {
			return nil, invalid(name, "IPv4-mapped prefixes must use a prefix length from 96 through 128")
		}
		prefixes = append(prefixes, canonical)
	}
	return prefixes, nil
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
