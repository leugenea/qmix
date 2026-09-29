package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// cacheWaitContext reports when a request actually begins waiting for a
// resolved source. Done is observed after cache admission, unlike merely
// launching a goroutine; it does not inspect the cache's flight bookkeeping.
type cacheWaitContext struct {
	context.Context
	waiting chan struct{}
	once    sync.Once
}

func observeCacheWait(ctx context.Context) *cacheWaitContext {
	return &cacheWaitContext{Context: ctx, waiting: make(chan struct{})}
}

func (c *cacheWaitContext) Done() <-chan struct{} {
	c.once.Do(func() { close(c.waiting) })
	return c.Context.Done()
}

type cacheLookup struct {
	url string
	err error
}

func startCacheLookup(b *YTDLP, ctx context.Context, track *Track) <-chan cacheLookup {
	ch := make(chan cacheLookup, 1)
	go func() {
		res, err := b.Stream(ctx, track, "")
		if err != nil {
			ch <- cacheLookup{err: err}
			return
		}
		defer res.Body.Close()
		if res.Status != http.StatusOK || res.ContentType != "audio/webm" {
			ch <- cacheLookup{err: fmt.Errorf("media response = %d %q, want 200 audio/webm", res.Status, res.ContentType)}
			return
		}
		body, err := io.ReadAll(res.Body)
		ch <- cacheLookup{string(body), err}
	}()
	return ch
}

// Return the requested URL as media bytes, so assertions about the public
// Stream result distinguish old and replacement URLs without network access.
type cacheContractTransport struct{}

func (cacheContractTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if err := req.Context().Err(); err != nil {
		return nil, err
	}
	url := req.URL.String()
	return &http.Response{
		StatusCode: http.StatusOK, Header: http.Header{"Content-Type": {"audio/webm"}},
		Body: io.NopCloser(strings.NewReader(url)), ContentLength: int64(len(url)), Request: req,
	}, nil
}

type cacheContractRunner func(context.Context, string) ([]byte, error)

func (f cacheContractRunner) Search(ctx context.Context, input string) ([]byte, error) {
	return f(ctx, input)
}

func cacheFixture(url string) []byte { return []byte(fmt.Sprintf(`{"url":%q}`, url)) }

func waitCacheGate(ch <-chan struct{}, name string) error {
	select {
	case <-ch:
		return nil
	case <-time.After(3 * time.Second):
		return fmt.Errorf("timed out waiting for %s", name)
	}
}

// qmix#287: fifty admitted requests for the same key share an in-flight
// search, and a later request reuses its published URL without another search.
func TestOrdinaryCacheSameKeyCoalescesAndReuses(t *testing.T) {
	started, release := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(release) })
	var calls atomic.Int32
	b := &YTDLP{CacheTTL: time.Minute, Client: &http.Client{Transport: cacheContractTransport{}}, Runner: cacheContractRunner(func(ctx context.Context, _ string) ([]byte, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		select {
		case <-release:
			return cacheFixture("https://media.example/shared"), nil
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(15 * time.Second):
			return nil, errors.New("timed out waiting for shared runner release")
		}
	})}
	track := testTrack()
	leader := observeCacheWait(context.Background())
	first := startCacheLookup(b, leader, track)
	awaitDisplaced(t, started, "first ordinary runner")
	awaitDisplaced(t, leader.waiting, "first admitted cache request")

	waiters := []<-chan cacheLookup{first}
	for i := 1; i < 50; i++ {
		follower := observeCacheWait(context.Background())
		waiters = append(waiters, startCacheLookup(b, follower, track))
		awaitDisplaced(t, follower.waiting, "admitted same-key waiter")
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("searches with 50 requests waiting = %d, want 1", got)
	}
	closeIfOpen(release)
	for i, ch := range waiters {
		got := awaitDisplaced(t, ch, "shared ordinary result")
		if got.err != nil || got.url != "https://media.example/shared" {
			t.Fatalf("result %d = (%q, %v), want shared URL", i, got.url, got.err)
		}
	}
	third := startCacheLookup(b, context.Background(), track)
	if got := awaitDisplaced(t, third, "cached ordinary result"); got.err != nil || got.url != "https://media.example/shared" {
		t.Fatalf("cached result = (%q, %v), want shared URL", got.url, got.err)
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("searches after cached reuse = %d, want 1", got)
	}
}

// qmix#287: another metadata key can resolve while the first key's search is
// still blocked; each URL then remains separately reusable.
func TestOrdinaryCacheIndependentKeys(t *testing.T) {
	started, release := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(release) })
	var calls atomic.Int32
	b := &YTDLP{CacheTTL: time.Minute, Client: &http.Client{Transport: cacheContractTransport{}}, Runner: cacheContractRunner(func(ctx context.Context, input string) ([]byte, error) {
		calls.Add(1)
		if input == "ytsearch:Artist - first" {
			close(started)
			select {
			case <-release:
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(3 * time.Second):
				return nil, errors.New("timed out waiting for independent runner release")
			}
		}
		return cacheFixture("https://media.example/" + strings.TrimPrefix(input, "ytsearch:Artist - ")), nil
	})}
	firstTrack := &Track{Title: "first", Artist: "Artist"}
	secondTrack := &Track{Title: "second", Artist: "Artist"}
	first := startCacheLookup(b, context.Background(), firstTrack)
	awaitDisplaced(t, started, "first key's runner")
	second := startCacheLookup(b, context.Background(), secondTrack)
	gotSecond := awaitDisplaced(t, second, "independent key while first is blocked")
	if gotSecond.err != nil || gotSecond.url != "https://media.example/second" {
		t.Fatalf("second key = (%q, %v), want its own URL", gotSecond.url, gotSecond.err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("searches while first key is blocked = %d, want 2", got)
	}
	closeIfOpen(release)
	gotFirst := awaitDisplaced(t, first, "first key's result")
	if gotFirst.err != nil || gotFirst.url != "https://media.example/first" {
		t.Fatalf("first key = (%q, %v), want its own URL", gotFirst.url, gotFirst.err)
	}
	for _, tc := range []struct {
		track *Track
		want  string
	}{{firstTrack, gotFirst.url}, {secondTrack, gotSecond.url}} {
		cached := awaitDisplaced(t, startCacheLookup(b, context.Background(), tc.track), "independent cached key")
		if cached.err != nil || cached.url != tc.want {
			t.Fatalf("cached key = (%q, %v), want %q", cached.url, cached.err, tc.want)
		}
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("searches after independent cache reuse = %d, want 2", got)
	}
}

// qmix#287: a positive TTL reuses the same URL before expiry, then resolves
// again after expiry. The timer starts after the first request completes, so
// it outlives publication without inspecting or modifying cache internals.
func TestOrdinaryCacheTTLExpirySearchesAgain(t *testing.T) {
	const ttl = time.Second
	var calls atomic.Int32
	b := &YTDLP{CacheTTL: ttl, Client: &http.Client{Transport: cacheContractTransport{}}, Runner: cacheContractRunner(func(context.Context, string) ([]byte, error) {
		return cacheFixture(fmt.Sprintf("https://media.example/attempt-%d", calls.Add(1))), nil
	})}
	track := testTrack()
	first := awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "first TTL lookup")
	if first.err != nil || first.url != "https://media.example/attempt-1" {
		t.Fatalf("first lookup = (%q, %v), want attempt-1", first.url, first.err)
	}
	beforeExpiry := awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "pre-expiry lookup")
	if beforeExpiry.err != nil || beforeExpiry.url != first.url || calls.Load() != 1 {
		t.Fatalf("pre-expiry lookup = (%q, %v), searches %d; want attempt-1 and one search", beforeExpiry.url, beforeExpiry.err, calls.Load())
	}
	timer := time.NewTimer(ttl + 25*time.Millisecond)
	defer timer.Stop()
	<-timer.C
	afterExpiry := awaitDisplaced(t, startCacheLookup(b, context.Background(), track), "post-expiry lookup")
	if afterExpiry.err != nil || afterExpiry.url != "https://media.example/attempt-2" {
		t.Fatalf("post-expiry lookup = (%q, %v), want attempt-2", afterExpiry.url, afterExpiry.err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("searches after expiry = %d, want 2", got)
	}
}

// qmix#287 / qmix#89: either the initiator or a joined waiter may leave
// without canceling the search needed by the surviving request.
func TestOrdinaryCacheOneWaiterCanCancel(t *testing.T) {
	for _, cancelFirst := range []bool{false, true} {
		name := "joined_waiter"
		if cancelFirst {
			name = "initiator"
		}
		t.Run(name, func(t *testing.T) {
			started, release := make(chan struct{}), make(chan struct{})
			t.Cleanup(func() { closeIfOpen(release) })
			var calls atomic.Int32
			b := &YTDLP{CacheTTL: time.Minute, Client: &http.Client{Transport: cacheContractTransport{}}, Runner: cacheContractRunner(func(ctx context.Context, _ string) ([]byte, error) {
				if calls.Add(1) == 1 {
					close(started)
				}
				select {
				case <-release:
					return cacheFixture("https://media.example/survivor"), nil
				case <-ctx.Done():
					return nil, ctx.Err()
				case <-time.After(3 * time.Second):
					return nil, errors.New("timed out waiting for surviving runner release")
				}
			})}
			firstCtx, cancelFirstCtx := context.WithCancel(context.Background())
			secondCtx, cancelSecondCtx := context.WithCancel(context.Background())
			t.Cleanup(cancelFirstCtx)
			t.Cleanup(cancelSecondCtx)
			firstObserved := observeCacheWait(firstCtx)
			first := startCacheLookup(b, firstObserved, testTrack())
			awaitDisplaced(t, started, "initiating runner")
			awaitDisplaced(t, firstObserved.waiting, "initiating request admitted")
			secondObserved := observeCacheWait(secondCtx)
			second := startCacheLookup(b, secondObserved, testTrack())
			awaitDisplaced(t, secondObserved.waiting, "second request admitted")
			canceled, surviving := second, first
			if cancelFirst {
				cancelFirstCtx()
				canceled, surviving = first, second
			} else {
				cancelSecondCtx()
			}
			left := awaitDisplaced(t, canceled, "canceled request before runner release")
			if !errors.Is(left.err, context.Canceled) {
				t.Fatalf("canceled request = (%q, %v), want context.Canceled", left.url, left.err)
			}
			if got := calls.Load(); got != 1 {
				t.Fatalf("searches after one request left = %d, want 1", got)
			}
			closeIfOpen(release)
			kept := awaitDisplaced(t, surviving, "surviving request")
			if kept.err != nil || kept.url != "https://media.example/survivor" {
				t.Fatalf("survivor = (%q, %v), want shared URL", kept.url, kept.err)
			}
			cached := awaitDisplaced(t, startCacheLookup(b, context.Background(), testTrack()), "result after one cancellation")
			if cached.err != nil || cached.url != kept.url || calls.Load() != 1 {
				t.Fatalf("cached result = (%q, %v), searches %d, want survivor URL and one search", cached.url, cached.err, calls.Load())
			}
		})
	}
}

// qmix#287 / qmix#89: only the final departure cancels shared yt-dlp work;
// its slot can then be used by a new lookup instead of caching a failed load.
func TestOrdinaryCacheLastWaiterCancelsRunner(t *testing.T) {
	started, release, stopped := make(chan struct{}), make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(release) })
	var calls atomic.Int32
	b := &YTDLP{CacheTTL: time.Minute, Client: &http.Client{Transport: cacheContractTransport{}}, Runner: cacheContractRunner(func(ctx context.Context, _ string) ([]byte, error) {
		if calls.Add(1) == 1 {
			close(started)
			select {
			case <-ctx.Done():
				close(stopped)
				return nil, ctx.Err()
			case <-release:
				return cacheFixture("https://media.example/abandoned"), nil
			case <-time.After(3 * time.Second):
				return nil, errors.New("timed out waiting for canceled runner release")
			}
		}
		return cacheFixture("https://media.example/replacement"), nil
	})}
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	secondCtx, cancelSecond := context.WithCancel(context.Background())
	t.Cleanup(cancelFirst)
	t.Cleanup(cancelSecond)
	firstObserved := observeCacheWait(firstCtx)
	first := startCacheLookup(b, firstObserved, testTrack())
	awaitDisplaced(t, started, "shared runner")
	awaitDisplaced(t, firstObserved.waiting, "first caller admitted")
	secondObserved := observeCacheWait(secondCtx)
	second := startCacheLookup(b, secondObserved, testTrack())
	awaitDisplaced(t, secondObserved.waiting, "last caller admitted")
	cancelFirst()
	if got := awaitDisplaced(t, first, "first canceled caller"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("first caller error = %v, want context.Canceled", got.err)
	}
	cancelSecond()
	if got := awaitDisplaced(t, second, "final canceled caller"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("final caller error = %v, want context.Canceled", got.err)
	}
	awaitDisplaced(t, stopped, "last-waiter runner cancellation")
	fresh := awaitDisplaced(t, startCacheLookup(b, context.Background(), testTrack()), "new lookup after cancellation")
	cached := awaitDisplaced(t, startCacheLookup(b, context.Background(), testTrack()), "cached replacement")
	if fresh.err != nil || fresh.url != "https://media.example/replacement" || cached.err != nil || cached.url != fresh.url || calls.Load() != 2 {
		t.Fatalf("replacement = (%q, %v), reuse = (%q, %v), searches %d; want replacement cached after two searches", fresh.url, fresh.err, cached.url, cached.err, calls.Load())
	}
}

// qmix#287 / qmix#89: an abandoned runner may ignore cancellation and
// succeed late. A new caller must start a replacement search, and a third
// caller must wait for that replacement rather than receiving the old URL.
// oldReturned signals runner return, not completion of Cache.load; the
// retained TestCacheAbandonedLoadCannotOverwriteReplacement checks that
// stricter completion boundary before asserting replacement identity.
func TestOrdinaryCacheAbandonedSuccessIsNotPublished(t *testing.T) {
	oldStarted, oldRelease, oldReturned := make(chan struct{}), make(chan struct{}), make(chan struct{})
	newStarted, newRelease := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() { closeIfOpen(oldRelease); closeIfOpen(newRelease) })
	var calls atomic.Int32
	b := &YTDLP{CacheTTL: time.Minute, Client: &http.Client{Transport: cacheContractTransport{}}, Runner: cacheContractRunner(func(_ context.Context, _ string) ([]byte, error) {
		switch calls.Add(1) {
		case 1:
			close(oldStarted)
			if err := waitCacheGate(oldRelease, "abandoned runner release"); err != nil { // deliberately ignore cancellation
				return nil, err
			}
			defer close(oldReturned)
			return cacheFixture("https://media.example/abandoned"), nil
		case 2:
			close(newStarted)
			if err := waitCacheGate(newRelease, "replacement runner release"); err != nil {
				return nil, err
			}
			return cacheFixture("https://media.example/replacement"), nil
		default:
			return cacheFixture("https://media.example/unexpected-extra-search"), nil
		}
	})}
	oldCtx, cancelOld := context.WithCancel(context.Background())
	t.Cleanup(cancelOld)
	first := startCacheLookup(b, oldCtx, testTrack())
	awaitDisplaced(t, oldStarted, "abandoned runner entry")
	cancelOld()
	if got := awaitDisplaced(t, first, "abandoned caller cancellation"); !errors.Is(got.err, context.Canceled) {
		t.Fatalf("abandoned caller error = %v, want context.Canceled", got.err)
	}

	replacement := startCacheLookup(b, context.Background(), testTrack())
	awaitDisplaced(t, newStarted, "independent replacement runner")
	closeIfOpen(oldRelease)
	awaitDisplaced(t, oldReturned, "late success of abandoned runner")
	observer := observeCacheWait(context.Background())
	joined := startCacheLookup(b, observer, testTrack())
	awaitDisplaced(t, observer.waiting, "new caller waiting for replacement, not stale URL")
	closeIfOpen(newRelease)
	for _, ch := range []<-chan cacheLookup{replacement, joined} {
		got := awaitDisplaced(t, ch, "replacement result after late abandoned success")
		if got.err != nil || got.url != "https://media.example/replacement" {
			t.Fatalf("replacement result = (%q, %v), want fresh URL", got.url, got.err)
		}
	}
	cached := awaitDisplaced(t, startCacheLookup(b, context.Background(), testTrack()), "cached replacement after abandoned success")
	if cached.err != nil || cached.url != "https://media.example/replacement" || calls.Load() != 2 {
		t.Fatalf("cached URL = (%q, %v), searches %d, want replacement and two searches", cached.url, cached.err, calls.Load())
	}
}
