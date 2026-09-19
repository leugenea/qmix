package resolver

// DefaultMuxWithConfig returns the production resolver dispatch, passing cfg to
// the resolver constructors so the token consumers in later milestones (#8-#10)
// can use them. Without tokens the resolvers keep their anonymous M2 behaviour.
// cfg.YTDLPBin and cfg.YTDLPLimiter configure the YouTube subprocess seam
// (qmix#130: one shared capacity limiter spans resolution and streaming).
func DefaultMuxWithConfig(cfg Config) *Mux {
	return NewMux(
		Matcher{Domains: []string{"open.spotify.com", "spotify.com"}, Resolver: &Spotify{Config: cfg}},
		Matcher{Domains: []string{"youtube.com", "youtu.be"}, Resolver: &YouTube{Bin: cfg.YTDLPBin, Timeout: cfg.YTDLPMetadataTimeout, Limiter: cfg.YTDLPLimiter}},
		Matcher{Domains: append(append([]string{}, vkDomains...), yandexDomains...), Resolver: &VKYandex{Config: cfg}},
	)
}
