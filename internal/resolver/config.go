package resolver

import "os"

// Config holds service credentials read from the environment at startup. They
// are parsed once and passed to the resolver assembly point (DefaultMuxWithConfig)
// so the concrete consumers in later milestones (#8-#10) can use them. Empty
// values mean the service stays in its anonymous mode — M2 behaviour unchanged.
type Config struct {
	VKToken             string
	YMToken             string
	SpotifyClientID     string
	SpotifyClientSecret string
}

// ConfigFromEnv reads the service-token configuration from the environment,
// mirroring the QMIX_ADDR pattern in cmd/qmix. Missing variables yield empty
// values (anonymous mode). Values are never logged.
func ConfigFromEnv() Config {
	return Config{
		VKToken:             os.Getenv("QMIX_VK_TOKEN"),
		YMToken:             os.Getenv("QMIX_YM_TOKEN"),
		SpotifyClientID:     os.Getenv("QMIX_SPOTIFY_CLIENT_ID"),
		SpotifyClientSecret: os.Getenv("QMIX_SPOTIFY_CLIENT_SECRET"),
	}
}
