package stream

import (
	"sync"
	"time"
)

// Cache is a thread-safe TTL cache with singleflight for concurrent misses.
// It stores resolved stream sources (direct audio URLs) so repeated requests
// for the same track do not re-search the expensive upstream service. Entries
// expire after TTL; a concurrent miss for the same key runs the loader once
// and shares the result across all waiters.
type Cache struct {
	mu       sync.Mutex
	ttl      time.Duration
	entries  map[string]*entry
	inflight map[string]*call
}

type entry struct {
	value  interface{}
	expiry time.Time
}

// call represents an in-flight load for a key. Its done channel is closed when
// the loader finishes, releasing all waiters.
type call struct {
	done chan struct{}
	val  interface{}
	err  error
}

// NewCache returns an empty cache with the given TTL. A non-positive TTL makes
// entries expire immediately (they are never reused).
func NewCache(ttl time.Duration) *Cache {
	return &Cache{
		ttl:      ttl,
		entries:  make(map[string]*entry),
		inflight: make(map[string]*call),
	}
}

// Get returns a cached value for key if present and not expired.
func (c *Cache) Get(key string) (interface{}, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	if e, ok := c.entries[key]; ok {
		if now.Before(e.expiry) {
			return e.value, true
		}
		delete(c.entries, key)
	}
	return nil, false
}

// Do runs loader for key, reusing an in-flight load for the same key. If a
// fresh value is already cached for key it is returned without running loader.
// On expiry a concurrent miss runs loader once and every waiter receives its
// result.
func (c *Cache) Do(key string, loader func() (interface{}, error)) (interface{}, error) {
	c.mu.Lock()
	now := time.Now()
	if e, ok := c.entries[key]; ok && now.Before(e.expiry) {
		v := e.value
		c.mu.Unlock()
		return v, nil
	}
	if pending, ok := c.inflight[key]; ok {
		c.mu.Unlock()
		<-pending.done
		return pending.val, pending.err
	}

	cl := &call{done: make(chan struct{})}
	c.inflight[key] = cl
	c.mu.Unlock()

	// Load outside the lock so a slow upstream does not block other keys.
	val, err := loader()

	c.mu.Lock()
	delete(c.inflight, key)
	if err == nil {
		c.entries[key] = &entry{value: val, expiry: time.Now().Add(c.ttl)}
	}
	cl.val = val
	cl.err = err
	close(cl.done)
	c.mu.Unlock()
	return val, err
}

// Len returns the number of live cached entries (for diagnostics/tests).
func (c *Cache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for k, e := range c.entries {
		if !now.Before(e.expiry) {
			delete(c.entries, k)
		}
	}
	return len(c.entries)
}
