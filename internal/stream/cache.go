package stream

import (
	"context"
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
	done    chan struct{}
	cancel  context.CancelFunc
	waiters int
	val     interface{}
	err     error
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

// Do runs loader for key without caller cancellation. See DoContext.
func (c *Cache) Do(key string, loader func() (interface{}, error)) (interface{}, error) {
	return c.DoContext(context.Background(), key, func(context.Context) (interface{}, error) {
		return loader()
	})
}

// DoContext runs loader for key, reusing an in-flight load for the same key. If
// a fresh value is already cached for key it is returned without running
// loader. The shared load runs while at least one caller is waiting: one caller
// can leave without poisoning others, and the load is canceled when none remain.
func (c *Cache) DoContext(ctx context.Context, key string, loader func(context.Context) (interface{}, error)) (interface{}, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	c.mu.Lock()
	now := time.Now()
	if e, ok := c.entries[key]; ok && now.Before(e.expiry) {
		v := e.value
		c.mu.Unlock()
		return v, nil
	}
	pending, ok := c.inflight[key]
	if !ok {
		loadCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
		pending = &call{done: make(chan struct{}), cancel: cancel}
		c.inflight[key] = pending
		go c.load(loadCtx, key, pending, loader)
	}
	pending.waiters++
	c.mu.Unlock()

	select {
	case <-pending.done:
		return pending.val, pending.err
	case <-ctx.Done():
		c.stopWaiting(key, pending)
		return nil, ctx.Err()
	}
}

// stopWaiting releases one caller and cancels an abandoned shared load.
func (c *Cache) stopWaiting(key string, cl *call) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.inflight[key] != cl {
		return
	}
	cl.waiters--
	if cl.waiters == 0 {
		delete(c.inflight, key)
		cl.cancel()
	}
}

// load completes one shared call outside the lock and publishes its result.
func (c *Cache) load(ctx context.Context, key string, cl *call, loader func(context.Context) (interface{}, error)) {
	val, err := loader(ctx)

	c.mu.Lock()
	if c.inflight[key] == cl {
		delete(c.inflight, key)
		if err == nil {
			c.entries[key] = &entry{value: val, expiry: time.Now().Add(c.ttl)}
		}
	}
	cl.val = val
	cl.err = err
	cl.cancel()
	close(cl.done)
	c.mu.Unlock()
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
