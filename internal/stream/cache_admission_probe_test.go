package stream

import (
	"context"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// Cache.lookup has no pre-lock callback. Record the public request's goroutine
// at its first Err check, then observe that exact goroutine parked in lookup's
// mutex admission. This test-only handshake adds no production hook and does
// not infer queuing from a goroutine launch, elapsed time, or cancellation.
type cacheAdmissionContext struct {
	context.Context
	goroutine chan string
	once      sync.Once
}

func (c *cacheAdmissionContext) Err() error {
	c.once.Do(func() {
		var stack [256]byte
		n := runtime.Stack(stack[:], false)
		c.goroutine <- strings.Fields(string(stack[:n]))[1]
	})
	return c.Context.Err()
}

func awaitCacheAdmissionQueued(t *testing.T, goroutine string) {
	t.Helper()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(time.Millisecond)
	defer tick.Stop()
	stack := make([]byte, 1<<20)
	for {
		n := runtime.Stack(stack, true)
		for _, trace := range strings.Split(string(stack[:n]), "\n\n") {
			if strings.HasPrefix(trace, "goroutine "+goroutine+" [sync.Mutex.Lock]") && strings.Contains(trace, ".(*Cache).lookup(") {
				return
			}
		}
		select {
		case <-tick.C:
		case <-deadline.C:
			t.Fatal("request never queued at the cache admission mutex")
		}
	}
}
