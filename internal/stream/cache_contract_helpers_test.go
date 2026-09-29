package stream

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"
	"time"
)

type lookupSequence struct {
	calls  atomic.Int32
	onCall func(context.Context, int) (string, error)
}

func (r *lookupSequence) Search(ctx context.Context, _ string) ([]byte, error) {
	u, err := r.onCall(ctx, int(r.calls.Add(1)))
	if err != nil {
		return nil, err
	}
	return []byte(fmt.Sprintf(`{"url":%q}`, u)), nil
}

func closeIfOpen(ch chan struct{}) {
	select {
	case <-ch:
	default:
		close(ch)
	}
}

func awaitDisplaced[T any](t *testing.T, ch <-chan T, what string) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		var zero T
		return zero
	}
}

// Completion is not observable after every caller has canceled. Read only the
// active flight's barrier; all behavior assertions still go through Stream.
func cacheFlightDone(t *testing.T, b *YTDLP, track *Track) <-chan struct{} {
	t.Helper()
	c := b.cacheRef()
	c.mu.Lock()
	defer c.mu.Unlock()
	s := c.states[cacheKey(track)]
	if s == nil || s.flight == nil {
		t.Fatal("expected parked cache flight")
	}
	return s.flight.done
}

func awaitCacheLookupWaiting(t *testing.T, ctx *cacheWaitContext, result <-chan cacheLookup, what string) {
	t.Helper()
	select {
	case <-ctx.waiting:
	case got := <-result:
		t.Fatalf("%s returned instead of waiting: %+v", what, got)
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s admission", what)
	}
	select {
	case got := <-result:
		t.Fatalf("%s returned before publisher release: %+v", what, got)
	default:
	}
}
