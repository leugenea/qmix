package stream

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// A caller that was live at the fast-path check may be canceled while
// waiting for mu. It must not enter any cache transition after acquiring mu.
type admissionContext struct {
	context.Context
	firstErr chan struct{}
	once     sync.Once
}

func (c *admissionContext) Err() error {
	c.once.Do(func() { close(c.firstErr) })
	return c.Context.Err()
}

func TestCacheCanceledWhileWaitingForAdmissionHasNoEffect(t *testing.T) {
	for _, mode := range []string{"rejected", "ordinary", "leased"} {
		t.Run(mode, func(t *testing.T) {
			c := NewCache(time.Minute)
			if _, err := c.Do("track", func() (interface{}, error) { return "attempted", nil }); err != nil {
				t.Fatal(err)
			}
			attempted := c.GetEntry("track")
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			instrumented := &admissionContext{Context: ctx, firstErr: make(chan struct{})}
			var loaderCalls atomic.Int32
			loader := func(context.Context) (interface{}, error) {
				loaderCalls.Add(1)
				return "unexpected", nil
			}
			type result struct {
				e       *entry
				release func()
				err     error
			}
			done := make(chan result, 1)
			c.mu.Lock()
			locked := true
			defer func() {
				if locked {
					c.mu.Unlock()
				}
			}()
			before := c.states["track"]
			beforeEpoch, beforePins, beforeNext := before.epoch, before.pins, c.next
			go func() {
				var r result
				switch mode {
				case "rejected":
					r.e, r.err = c.DoContextRejected(instrumented, "track", attempted, loader)
				case "ordinary":
					r.e, r.err = c.DoContextEntry(instrumented, "track", loader)
				case "leased":
					r.e, r.release, r.err = c.DoContextEntryLeased(instrumented, "track", loader)
				}
				done <- r
			}()
			// The first Err check has run; this caller cannot acquire mu until
			// after cancellation, independent of scheduling or sleeps.
			select {
			case <-instrumented.firstErr:
			case <-time.After(5 * time.Second):
				t.Fatal("timed out waiting for the pre-lock context Err check")
			}
			cancel()
			c.mu.Unlock()
			locked = false
			var r result
			select {
			case r = <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("timed out waiting for the canceled admission to return")
			}
			if r.release != nil {
				r.release()
			}
			if !errors.Is(r.err, context.Canceled) || r.e != nil || r.release != nil {
				t.Fatalf("canceled admission returned entry %p, lease %v, err %v", r.e, r.release != nil, r.err)
			}
			if loaderCalls.Load() != 0 {
				t.Fatalf("loader called %d times", loaderCalls.Load())
			}
			c.mu.Lock()
			state := c.states["track"]
			unchanged := state == before && state.entry == attempted && state.owner == nil && state.ordinary == nil && len(state.refreshes) == 0 && state.epoch == beforeEpoch && state.pins == beforePins && c.next == beforeNext
			c.mu.Unlock()
			if !unchanged {
				t.Fatal("canceled admission changed identity, ownership, epoch, pins or generation")
			}
			got, err := c.DoContextEntry(context.Background(), "track", loader)
			if err != nil || got != attempted || loaderCalls.Load() != 0 {
				t.Fatalf("later cache hit = %p, %v, loader calls %d; want exact entry %p", got, err, loaderCalls.Load(), attempted)
			}
		})
	}
}
