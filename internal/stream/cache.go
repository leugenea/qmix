package stream

import (
	"context"
	"sync"
	"time"
)

// Cache keeps a generation for each publication and one admissible lookup per
// key. Displaced lookups may still finish for their own callers, but cannot
// publish; in-progress media GETs retain their key state until status settles.
type Cache struct {
	mu     sync.Mutex
	ttl    time.Duration
	states map[string]*keyState
	next   uint64
}

type keyState struct {
	entry  *entry
	flight *call
	status map[*entry]*call // displaced status attempts may overlap an owner
	latest uint64
	epoch  uint64
	pins   int
}

type entry struct {
	url    string
	expiry time.Time
	gen    uint64
	state  *keyState
}

type call struct {
	done      chan struct{}
	cancel    context.CancelFunc
	waiters   int
	result    *entry
	err       error
	attempted *entry // non-nil for a post-status lookup
	baseline  uint64
	epoch     uint64
	publish   bool
	unowned   bool // no owner existed at admission; may publish after expiry
}

func NewCache(ttl time.Duration) *Cache {
	return &Cache{ttl: ttl, states: make(map[string]*keyState)}
}

func (c *Cache) state(key string) *keyState {
	s := c.states[key]
	if s == nil {
		s = &keyState{}
		c.states[key] = s
	}
	return s
}

func (s *keyState) live() *entry {
	if s.entry != nil && !time.Now().Before(s.entry.expiry) {
		s.entry = nil
	}
	return s.entry
}

func (c *Cache) discard(key string, s *keyState) {
	if s.entry == nil && s.flight == nil && len(s.status) == 0 && s.pins == 0 && c.states[key] == s {
		delete(c.states, key)
	}
}

// DoContextEntryLeased retains lineage while the caller opens and validates
// the URL. The returned release is idempotent, including on error paths.
func (c *Cache) DoContextEntryLeased(ctx context.Context, key string, loader func(context.Context) (string, error)) (*entry, func(), error) {
	return c.lookup(ctx, key, nil, true, loader)
}

// DoContextRejected atomically compares the failed generation and attaches a
// new lookup. Duplicate failures join their generation's already-owned flight;
// a pre-failure ordinary flight can never supply the retry URL.
func (c *Cache) DoContextRejected(ctx context.Context, key string, attempted *entry, loader func(context.Context) (string, error)) (*entry, error) {
	e, _, err := c.lookup(ctx, key, attempted, false, loader)
	return e, err
}

func (c *Cache) lookup(ctx context.Context, key string, attempted *entry, leased bool, loader func(context.Context) (string, error)) (*entry, func(), error) {
	c.mu.Lock()
	if err := ctx.Err(); err != nil {
		c.mu.Unlock()
		return nil, nil, err
	}
	s := c.state(key)
	if attempted != nil && attempted.state != s {
		c.discard(key, s)
		c.mu.Unlock()
		return nil, nil, ErrService
	}
	current := s.live()
	release := c.leaseLocked(key, s, leased)
	if attempted == nil && current != nil {
		c.mu.Unlock()
		return current, release, nil
	}
	cl := c.flightLocked(ctx, key, s, current, attempted, loader)
	cl.waiters++
	c.mu.Unlock()
	return c.wait(ctx, key, s, cl, release)
}

func (c *Cache) leaseLocked(key string, s *keyState, leased bool) func() {
	if !leased {
		return nil
	}
	s.pins++
	var once sync.Once
	return func() {
		once.Do(func() {
			c.mu.Lock()
			s.pins--
			c.discard(key, s)
			c.mu.Unlock()
		})
	}
}

// A rejected generation may displace a pre-failure ordinary lookup. Older
// attempts remain read-only but share their own in-progress status lookup.
func (c *Cache) flightLocked(ctx context.Context, key string, s *keyState, current, attempted *entry, loader func(context.Context) (string, error)) *call {
	if attempted == nil {
		if s.flight != nil {
			return s.flight
		}
		return c.start(ctx, key, s, nil, true, loader)
	}
	if cl := s.status[attempted]; cl != nil {
		return cl
	}
	owns := s.canOwn(current, attempted)
	if owns {
		s.entry = nil
		s.epoch++
	}
	cl := c.start(ctx, key, s, attempted, owns, loader)
	cl.unowned = !owns && (s.flight == nil || s.flight.attempted == nil)
	return cl
}

func (s *keyState) canOwn(current, attempted *entry) bool {
	return current == attempted || current == nil && s.latest == attempted.gen &&
		(s.flight == nil || s.flight.attempted == nil)
}

func (c *Cache) wait(ctx context.Context, key string, s *keyState, cl *call, release func()) (*entry, func(), error) {
	select {
	case <-cl.done:
		if cl.err != nil && release != nil {
			release()
			release = nil
		}
		return cl.result, release, cl.err
	case <-ctx.Done():
		c.stopWaiting(key, s, cl)
		if release != nil {
			release()
		}
		return nil, nil, ctx.Err()
	}
}

func (c *Cache) stopWaiting(key string, s *keyState, cl *call) {
	c.mu.Lock()
	defer c.mu.Unlock()
	cl.waiters--
	if cl.waiters != 0 {
		return
	}
	if s.flight == cl {
		s.flight = nil
	}
	if cl.attempted != nil && s.status[cl.attempted] == cl {
		delete(s.status, cl.attempted)
	}
	cl.cancel()
	c.discard(key, s)
}

func (c *Cache) start(ctx context.Context, key string, s *keyState, attempted *entry, owns bool, loader func(context.Context) (string, error)) *call {
	loadCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	cl := &call{done: make(chan struct{}), cancel: cancel, attempted: attempted,
		baseline: s.latest, epoch: s.epoch, publish: owns}
	if owns {
		s.flight = cl
	}
	if attempted != nil {
		if s.status == nil {
			s.status = make(map[*entry]*call)
		}
		s.status[attempted] = cl
	}
	go func() {
		url, err := loader(loadCtx)
		c.finish(key, s, cl, url, err)
	}()
	return cl
}

// Completion compares the original admission epoch before any publication.
// A displaced call still returns a local attempt to its existing waiters.
func (c *Cache) finish(key string, s *keyState, cl *call, url string, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	cl.result = c.finishPublicationLocked(s, cl, url, err)
	if cl.attempted != nil && s.status[cl.attempted] == cl {
		delete(s.status, cl.attempted)
	}
	c.discard(key, s)
	if err == nil && cl.result == nil {
		cl.result = &entry{url: url, gen: cl.baseline, state: s}
	}
	cl.err = err
	cl.cancel()
	close(cl.done)
}

func (c *Cache) finishPublicationLocked(s *keyState, cl *call, url string, err error) *entry {
	if s.flight == cl {
		s.flight = nil
		s.live()
		if err == nil && cl.publish && s.epoch == cl.epoch && s.entry == nil {
			return c.publishLocked(s, url)
		}
		return nil
	}
	if err == nil && s.canPublishUnowned(cl) {
		return c.publishLocked(s, url)
	}
	return nil
}

func (s *keyState) canPublishUnowned(cl *call) bool {
	return cl.unowned && s.epoch == cl.epoch && s.live() == nil &&
		(s.flight == nil || s.flight.attempted == nil) && s.status[cl.attempted] == cl
}

func (c *Cache) publishLocked(s *keyState, url string) *entry {
	c.next++
	e := &entry{url: url, expiry: time.Now().Add(c.ttl), gen: c.next, state: s}
	s.entry, s.latest = e, c.next
	s.epoch++
	return e
}

// DeleteIfEntryFinal retires the terminally rejected generation, fencing older
// publication even after expiry, without displacing a newer entry or owner.
func (c *Cache) DeleteIfEntryFinal(key string, attempted *entry) bool {
	if attempted == nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	s := c.states[key]
	if s == nil || attempted.state != s || !s.canOwn(s.live(), attempted) {
		return false
	}
	s.entry = nil
	s.epoch++
	c.discard(key, s)
	return true
}
