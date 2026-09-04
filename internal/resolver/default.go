package resolver

// DefaultMux returns the production resolver dispatch built from the process
// environment (see ConfigFromEnv). It is the zero-argument convenience wrapper
// used by callers that do not need to inject a config explicitly.
func DefaultMux() *Mux {
	return DefaultMuxWithConfig(ConfigFromEnv())
}

// DefaultMuxWithConfig returns the production resolver dispatch, passing cfg to
// the resolver constructors so the token consumers in later milestones (#8-#10)
// can use them. Without tokens the resolvers keep their anonymous M2 behaviour.
func DefaultMuxWithConfig(cfg Config) *Mux {
	return NewMux(
		Matcher{Domains: []string{"open.spotify.com", "spotify.com"}, Resolver: &Spotify{Config: cfg}},
		Matcher{Domains: []string{"youtube.com", "youtu.be"}, Resolver: &YouTube{}},
		Matcher{Domains: append(append([]string{}, vkDomains...), yandexDomains...), Resolver: &VKYandex{Config: cfg}},
	)
}
