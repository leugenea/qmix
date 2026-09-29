package stream

import "context"

// resolveURL finds the direct audio URL for track, honoring the URL cache.
// This compatibility helper is test-only; production streams use resolveSource
// to retain the lease through their upstream status decision (qmix#292).
func (b *YTDLP) resolveURL(ctx context.Context, t *Track) (string, error) {
	u, _, _, release, err := b.resolveSource(ctx, t)
	if release != nil {
		release()
	}
	return u, err
}
