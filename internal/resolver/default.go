package resolver

// DefaultMux returns the production resolver dispatch: a Mux routing known
// service domains to their resolvers. All resolvers use unauthenticated,
// public endpoints or (YouTube) the locally installed yt-dlp binary.
func DefaultMux() *Mux {
	return NewMux(
		Matcher{Domains: []string{"open.spotify.com", "spotify.com"}, Resolver: &Spotify{}},
		Matcher{Domains: []string{"youtube.com", "youtu.be"}, Resolver: &YouTube{}},
		Matcher{Domains: append(append([]string{}, vkDomains...), yandexDomains...), Resolver: &VKYandex{}},
	)
}
