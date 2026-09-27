package stream

import (
	"context"
	"testing"
	"time"
)

// A terminal rejection of E1 cannot let E0's parked refresh repopulate the
// cache, even though terminal rejection itself starts no additional lookup.
func TestNewerTerminalRejectionRetiresOlderRefreshOwner(t *testing.T) {
	c := NewCache(time.Minute)
	if _, err := c.Do("track", func() (interface{}, error) { return "e0", nil }); err != nil {
		t.Fatal(err)
	}
	old := c.GetEntry("track")
	c.DeleteIfEntry("track", old)
	started, release := make(chan struct{}), make(chan struct{})
	defer closeIfOpen(release)
	finished := make(chan error, 1)
	go func() {
		_, err := c.DoContextFresh(context.Background(), "track", old, func(context.Context) (interface{}, error) {
			close(started)
			<-release
			return "older refresh", nil
		})
		finished <- err
	}()
	<-started
	c.mu.Lock()
	c.state("track").entry = &entry{value: "e1", expiry: time.Now().Add(time.Minute)}
	c.mu.Unlock()
	newer := c.GetEntry("track")
	if !c.DeleteIfEntryFinal("track", newer) {
		t.Fatal("terminal rejection failed to invalidate E1")
	}
	close(release)
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if got, ok := c.Get("track"); ok {
		t.Fatalf("older refresh republished after newer terminal failure: %v", got)
	}
}
