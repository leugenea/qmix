package stream

import (
	"context"
	"sync"
	"time"
)

// Cache is a thread-safe TTL cache with singleflight for concurrent misses.
// It stores resolved stream sources (direct audio URLs) for the configured TTL.
//
// Per-key ordering model (all transitions happen under mu):
//   - Each successful publication allocates a new *entry identity, even when
//     the URL text is identical. Active GETs pin the per-key lineage; expired
//     idle states are pruned, but their entry identities retain the last known
//     generation without cache-wide tombstones.
//   - Atomic rejection removes that entry, reserves owner and attaches its
//     status flight BEFORE another ordinary miss can publish. Only rejection
//     of a currently live generation can replace owner. A stale late failure
//     is read-only with respect to this key, including its existing refresh.
//   - Ordinary and status flights have separate namespaces. Status callers
//     join only the flight for their exact rejected entry (never an ordinary
//     or another generation's lookup). Ordinary misses can join the already
//     running owner refresh, but cannot publish while owner is reserved.
//   - A refresh publishes only while it still holds owner and there is no
//     newer live entry; an unowned fresh lookup may publish after the entry
//     seen at start expires, but only if its mutation epoch still matches.
//     A superseded flight returns its result to its own waiters but does not
//     cache it.
//   - The last waiter detaches and cancels its flight. The owner reservation
//     is released on refresh failure, abandonment or a terminal (second)
//     invalid status; a pre-canceled atomic rejection acquires none.
type Cache struct {
	mu            sync.Mutex
	ttl           time.Duration
	states        map[string]*keyState
	next          uint64        // unique cache-wide generation, survives per-key state pruning
	waiterChanged chan struct{} // coalesced wakeups for observing joined flights
}

type keyState struct {
	entry     *entry
	ordinary  *call
	refreshes map[*entry]*call
	owner     *entry
	epoch     uint64 // publication or accepted rejection, not stale failures
	latest    uint64 // newest published generation observed by this state
	lineage   *keyLineage
	pins      int // in-progress GETs keep the per-key history reachable
}

// Entries that outlive an idle state retain its publication history without
// keeping a cache-wide tombstone for every expired key.
type keyLineage struct{ latest uint64 }

type entry struct {
	value   interface{}
	expiry  time.Time
	cache   *Cache
	key     string
	gen     uint64
	lineage *keyLineage
	// A displaced local lookup is an attempt, not a publication. Its gen is
	// the newest publication seen when that lookup began, so a later one
	// cannot be rejected by this attempt even after expiring.
	transient bool
}

type call struct {
	done     chan struct{}
	cancel   context.CancelFunc
	waiters  int
	result   *entry
	err      error
	stale    *entry // non-nil for a status refresh of a cached generation
	fresh    bool
	start    *entry
	epoch    uint64
	baseline uint64
	lineage  *keyLineage
}

// NewCache returns an empty cache with the given TTL. A non-positive TTL makes
// entries expire immediately (they are never reused).
func NewCache(ttl time.Duration) *Cache {
	return &Cache{ttl: ttl, states: make(map[string]*keyState), waiterChanged: make(chan struct{}, 1)}
}

func (c *Cache) state(key string) *keyState {
	s := c.states[key]
	if s == nil {
		s = &keyState{lineage: &keyLineage{}}
		c.states[key] = s
	}
	return s
}

// liveEntry removes an expired value, but does not change the publication
// epoch: expiration revokes ownership without minting a new generation.
func (s *keyState) liveEntry() *entry {
	if s.entry != nil && !time.Now().Before(s.entry.expiry) {
		s.entry = nil
	}
	return s.entry
}

func (c *Cache) discard(key string, s *keyState) {
	if s.entry == nil && s.owner == nil && s.ordinary == nil && len(s.refreshes) == 0 && s.pins == 0 && c.states[key] == s {
		delete(c.states, key)
	}
}

// Get returns a cached value for key if present and not expired.
func (c *Cache) Get(key string) (interface{}, bool) {
	if e := c.GetEntry(key); e != nil {
		return e.value, true
	}
	return nil, false
}

// GetEntry returns the identity of the live value for conditional invalidation.
func (c *Cache) GetEntry(key string) *entry {
	c.mu.Lock()
	defer c.mu.Unlock()
	if s := c.states[key]; s != nil {
		e := s.liveEntry()
		c.discard(key, s)
		return e
	}
	return nil
}

// Do runs loader for key without caller cancellation. See DoContext.
func (c *Cache) Do(key string, loader func() (interface{}, error)) (interface{}, error) {
	return c.DoContext(context.Background(), key, func(context.Context) (interface{}, error) {
		return loader()
	})
}

// DoContext uses an ordinary singleflight, with independent waiter lifetimes.
func (c *Cache) DoContext(ctx context.Context, key string, loader func(context.Context) (interface{}, error)) (interface{}, error) {
	e, err := c.DoContextEntry(ctx, key, loader)
	if err != nil {
		return nil, err
	}
	return e.value, nil
}

func (c *Cache) DoContextEntry(ctx context.Context, key string, loader func(context.Context) (interface{}, error)) (*entry, error) {
	e, _, err := c.doContext(ctx, key, nil, false, false, false, loader)
	return e, err
}

// DoContextEntryLeased pins the key's lineage through an upstream GET. The
// caller must invoke release once that GET (including any status retry) ends.
func (c *Cache) DoContextEntryLeased(ctx context.Context, key string, loader func(context.Context) (interface{}, error)) (*entry, func(), error) {
	return c.doContext(ctx, key, nil, false, false, true, loader)
}

// DoContextFresh joins/starts a status flight without rejecting. Callers that
// need invalidation must use DoContextRejected so admission is atomic.
func (c *Cache) DoContextFresh(ctx context.Context, key string, stale *entry, loader func(context.Context) (interface{}, error)) (*entry, error) {
	e, _, err := c.doContext(ctx, key, stale, true, false, false, loader)
	return e, err
}

// DoContextRejected atomically rejects the attempted generation and attaches
// its post-failure flight. A duplicate can join it but cannot own or release
// another caller's reservation. A pre-canceled caller makes no transition.
func (c *Cache) DoContextRejected(ctx context.Context, key string, attempted *entry, loader func(context.Context) (interface{}, error)) (*entry, error) {
	e, _, err := c.doContext(ctx, key, attempted, true, true, false, loader)
	return e, err
}

// DeleteIfEntry reserves a generation for explicit cache-level callers. Stream
// uses DoContextRejected instead: split admission cannot safely attribute a
// pre-canceled duplicate's cleanup to the owner of this reservation.
// Duplicate failures of a rejected identity may join its in-flight refresh;
// failures of older identities cannot revoke a newer owner's reservation.
func (c *Cache) DeleteIfEntry(key string, attempted *entry) bool {
	return c.reject(key, attempted, true)
}

// DeleteIfEntryFinal invalidates a second failed GET without reserving a
// lookup that will never run. The retry budget ends at this status.
func (c *Cache) DeleteIfEntryFinal(key string, attempted *entry) bool {
	return c.reject(key, attempted, false)
}

func (c *Cache) reject(key string, attempted *entry, reserve bool) bool {
	if attempted == nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.foreignEntryLocked(key, attempted) {
		return false
	}
	s := c.state(key)
	return c.rejectLocked(key, s, attempted, reserve)
}

// Published identities are immutable. A zero-generation synthetic entry may
// acquire an identity only while it is the current entry in this cache.
// Check before accessing lineage (owned by the origin cache's mutex) or
// allocating state for a key this cache does not own.
func (c *Cache) foreignEntryLocked(key string, attempted *entry) bool {
	if attempted.cache == c && attempted.key == key {
		return false
	}
	s := c.states[key]
	return attempted.cache != nil || attempted.gen != 0 || s == nil || s.entry != attempted
}

func (c *Cache) rejectLocked(key string, s *keyState, attempted *entry, reserve bool) bool {
	if c.foreignEntryLocked(key, attempted) {
		return false
	}
	current := s.liveEntry()
	if current != attempted && !c.rejectableExpiredLocked(key, s, attempted, current) {
		c.discard(key, s)
		return false
	}
	if current == attempted && attempted.gen == 0 {
		// Tests and callers that inject entries under mu get an identity too.
		c.next++
		attempted.gen, attempted.cache, attempted.key, attempted.lineage = c.next, c, key, s.lineage
	}
	c.recordRejectionLocked(key, s, attempted, reserve)
	return current == attempted
}

// An expired attempt may reject only if no newer publication or owner has
// superseded it. Its lineage survives pruning of the old idle key state.
func (c *Cache) rejectableExpiredLocked(key string, s *keyState, attempted, current *entry) bool {
	latest := s.latest
	if attempted.lineage != nil && attempted.lineage.latest > latest {
		latest = attempted.lineage.latest
	}
	return current == nil && attempted.cache == c && attempted.key == key &&
		attempted.gen >= latest && s.owner == nil
}

func (c *Cache) recordRejectionLocked(key string, s *keyState, attempted *entry, reserve bool) {
	if attempted.lineage != nil && attempted.lineage.latest > s.latest {
		s.latest = attempted.lineage.latest
		s.lineage = attempted.lineage
	}
	s.entry = nil
	if reserve {
		s.owner = attempted
	} else if s.owner != nil && s.owner.gen <= attempted.gen {
		// Terminal rejection retires this or any older reservation.
		s.owner = nil
	}
	if attempted.gen > s.latest {
		s.latest = attempted.gen
	}
	if attempted.lineage != nil && attempted.gen > attempted.lineage.latest {
		attempted.lineage.latest = attempted.gen
	}
	s.epoch++
	c.discard(key, s)
}

func (c *Cache) doContext(ctx context.Context, key string, stale *entry, fresh, rejectFirst, leased bool, loader func(context.Context) (interface{}, error)) (*entry, func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	c.mu.Lock()
	s, current, err := c.admitLocked(ctx, key, stale, fresh, rejectFirst)
	if err != nil {
		c.mu.Unlock()
		return nil, nil, err
	}
	release := c.leaseLocked(key, s, leased)
	if !fresh && current != nil {
		c.mu.Unlock()
		return current, release, nil
	}
	pending := c.flightLocked(ctx, key, s, current, stale, fresh, loader)
	pending.waiters++
	select {
	case c.waiterChanged <- struct{}{}:
	default:
	}
	c.mu.Unlock()
	return c.waitForFlight(ctx, key, s, pending, release)
}

// Admission linearizes under mu. Cancellation while waiting for the lock
// cannot reject a live entry, reserve an owner, pin, or join a flight.
func (c *Cache) admitLocked(ctx context.Context, key string, stale *entry, fresh, rejectFirst bool) (*keyState, *entry, error) {
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	if fresh && stale != nil && c.foreignEntryLocked(key, stale) {
		return nil, nil, ErrService
	}
	s := c.state(key)
	if rejectFirst && stale != nil {
		c.rejectLocked(key, s, stale, true)
		// A stale failure may have discarded a newly-created empty state.
		s = c.state(key)
	}
	return s, s.liveEntry(), nil
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

// A status flight is keyed by the failed identity. Ordinary misses may join
// an owner's refresh but never compete with it for publication.
func (s *keyState) pending(stale *entry, fresh bool) *call {
	if fresh {
		return s.refreshes[stale]
	}
	if s.owner != nil && s.refreshes[s.owner] != nil {
		return s.refreshes[s.owner]
	}
	return s.ordinary
}

func (c *Cache) flightLocked(ctx context.Context, key string, s *keyState, current, stale *entry, fresh bool, loader func(context.Context) (interface{}, error)) *call {
	if pending := s.pending(stale, fresh); pending != nil {
		return pending
	}
	loadCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	pending := &call{done: make(chan struct{}), cancel: cancel, fresh: fresh, stale: stale, start: current, epoch: s.epoch, baseline: s.latest, lineage: s.lineage}
	if fresh {
		if s.refreshes == nil {
			s.refreshes = make(map[*entry]*call)
		}
		s.refreshes[stale] = pending
	} else {
		s.ordinary = pending
	}
	go c.load(loadCtx, key, s, pending, loader)
	return pending
}

func (c *Cache) waitForFlight(ctx context.Context, key string, s *keyState, pending *call, release func()) (*entry, func(), error) {
	select {
	case <-pending.done:
		if pending.err != nil && release != nil {
			release()
			return nil, nil, pending.err
		}
		return pending.result, release, pending.err
	case <-ctx.Done():
		c.stopWaiting(key, s, pending)
		if release != nil {
			release()
		}
		return nil, nil, ctx.Err()
	}
}

func (s *keyState) active(cl *call) bool {
	if cl.fresh {
		return s.refreshes[cl.stale] == cl
	}
	return s.ordinary == cl
}

func (s *keyState) remove(cl *call) {
	if cl.fresh {
		delete(s.refreshes, cl.stale)
	} else {
		s.ordinary = nil
	}
}

// stopWaiting detaches the final waiter so a canceled load cannot publish.
func (c *Cache) stopWaiting(key string, s *keyState, cl *call) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.states[key] != s || !s.active(cl) {
		return
	}
	cl.waiters--
	if cl.waiters == 0 {
		s.remove(cl)
		if cl.fresh && s.owner == cl.stale {
			s.owner = nil
		}
		cl.cancel()
		c.discard(key, s)
	}
}

func (c *Cache) load(ctx context.Context, key string, s *keyState, cl *call, loader func(context.Context) (interface{}, error)) {
	val, err := loader(ctx)
	c.mu.Lock()
	if c.states[key] == s && s.active(cl) {
		s.remove(cl)
		current := s.liveEntry()
		if cl.fresh {
			c.finishFreshLocked(key, s, cl, current, val, err)
		} else if err == nil && cl.canPublishOrdinary(s, current) {
			cl.result = c.publish(s, key, val)
		}
		c.discard(key, s)
	}
	// A displaced call still serves its waiters with its own local attempt
	// identity. This is not a publication: keep the generation seen at start
	// rather than minting a newer one or changing the cache's lineage.
	if err == nil && cl.result == nil {
		cl.result = &entry{value: val, cache: c, key: key, gen: cl.baseline, lineage: cl.lineage, transient: true}
	}
	cl.err = err
	cl.cancel()
	close(cl.done)
	c.mu.Unlock()
}

func (cl *call) canPublishOrdinary(s *keyState, current *entry) bool {
	return s.owner == nil && current == cl.start && s.epoch == cl.epoch
}

// An unreserved refresh can publish after expiry, but not over a live newer
// generation or an intervening mutation of this key.
func (cl *call) canPublishUnownedFresh(s *keyState, current *entry) bool {
	return s.owner == nil && (current == nil || current == cl.start) &&
		s.epoch == cl.epoch && (current == nil || current == cl.stale)
}

func (c *Cache) finishFreshLocked(key string, s *keyState, cl *call, current *entry, val interface{}, err error) {
	if s.owner == cl.stale && cl.stale != nil {
		if err == nil && current == nil && s.epoch == cl.epoch {
			cl.result = c.publish(s, key, val)
		}
		s.owner = nil
	} else if err == nil && cl.canPublishUnownedFresh(s, current) {
		cl.result = c.publish(s, key, val)
	}
}

func (c *Cache) publish(s *keyState, key string, val interface{}) *entry {
	c.next++
	e := &entry{value: val, expiry: time.Now().Add(c.ttl), cache: c, key: key, gen: c.next, lineage: s.lineage}
	s.entry = e
	s.latest = e.gen
	s.lineage.latest = e.gen
	s.epoch++
	return e
}

// Len returns the number of live cached entries (for diagnostics/tests).
func (c *Cache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for key, s := range c.states {
		if s.liveEntry() != nil {
			n++
		} else {
			c.discard(key, s)
		}
	}
	return n
}
