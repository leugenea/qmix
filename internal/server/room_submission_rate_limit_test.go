package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
)

type submissionCountingResolver struct {
	mu    sync.Mutex
	calls int
	err   error
}

func (r *submissionCountingResolver) Resolve(context.Context, string) (*resolver.Track, error) {
	r.mu.Lock()
	r.calls++
	err := r.err
	r.mu.Unlock()
	if err != nil {
		return nil, err
	}
	return &resolver.Track{Title: "track", ResolvedBy: "test"}, nil
}

func (r *submissionCountingResolver) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.calls
}

func newSubmissionLimitedServer(rate, burst int, clock func() time.Time, trackResolver resolver.Resolver) (*Server, *Store, *http.ServeMux) {
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, rate, burst, clock)
	server := NewServer(store, NewHub())
	server.Resolver = trackResolver
	return server, store, newTestMux(server)
}

func submitPath(code, alias string) string {
	if alias == "guest" {
		return "/r/" + code + "/queue"
	}
	return "/rooms/" + code + "/queue"
}

func TestRoomSubmissionAliasesShareExactRateLimitContract(t *testing.T) {
	clock := func() time.Time { return time.Unix(100, 0) }
	trackResolver := &submissionCountingResolver{}
	server, store, mux := newSubmissionLimitedServer(30, 2, clock, trackResolver)
	code, _ := createRoom(t, mux)
	for _, alias := range []string{"host", "guest"} {
		rec := doReq(t, mux, http.MethodPost, submitPath(code, alias), `{"url":"https://example.test/track"}`, "")
		if rec.Code != http.StatusCreated {
			t.Fatalf("%s admitted status = %d, want 201; body=%s", alias, rec.Code, rec.Body.String())
		}
	}

	events, cancel := subscribeTestEvents(t, server.store, server.hub, code)
	defer cancel()
	before, err := store.View(code)
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	activity := store.rooms[code].LastActivity
	store.mu.Unlock()

	var bodies []string
	for _, alias := range []string{"host", "guest"} {
		rec := doReq(t, mux, http.MethodPost, submitPath(code, alias), `{"url":"https://example.test/rejected"}`, "")
		bodies = append(bodies, rec.Body.String())
		if rec.Code != http.StatusTooManyRequests {
			t.Fatalf("%s status = %d, want 429; body=%s", alias, rec.Code, rec.Body.String())
		}
		if got := rec.Header().Get("Content-Type"); got != "application/json" {
			t.Fatalf("%s Content-Type = %q", alias, got)
		}
		if got := rec.Header().Get("Retry-After"); got != "2" {
			t.Fatalf("%s Retry-After = %q, want 2", alias, got)
		}
		var fields map[string]string
		if err := json.Unmarshal(rec.Body.Bytes(), &fields); err != nil {
			t.Fatal(err)
		}
		want := map[string]string{"error": "rate_limited", "message": "room submission rate limit exceeded"}
		if fmt.Sprint(fields) != fmt.Sprint(want) || len(fields) != 2 {
			t.Fatalf("%s envelope = %v, want %v", alias, fields, want)
		}
	}
	if bodies[0] != bodies[1] {
		t.Fatalf("alias rate-limit bodies differ: %q vs %q", bodies[0], bodies[1])
	}
	if got := trackResolver.count(); got != 2 {
		t.Fatalf("resolver calls = %d, want only two admitted attempts", got)
	}
	after, err := store.View(code)
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	afterActivity := store.rooms[code].LastActivity
	store.mu.Unlock()
	if len(after.Queue) != len(before.Queue) || !afterActivity.Equal(activity) {
		t.Fatalf("rejection mutated room: before=%+v after=%+v activity %v -> %v", before, after, activity, afterActivity)
	}
	select {
	case event := <-events:
		t.Fatalf("rate-limited request published event %+v", event)
	default:
	}
}

func TestRoomSubmissionFailedResolutionConsumesAdmission(t *testing.T) {
	trackResolver := &submissionCountingResolver{err: resolver.ErrUnsupported}
	_, _, mux := newSubmissionLimitedServer(30, 1, func() time.Time { return time.Unix(100, 0) }, trackResolver)
	code, _ := createRoom(t, mux)
	first := doReq(t, mux, http.MethodPost, submitPath(code, "host"), `{"url":"https://example.test/unsupported"}`, "")
	if first.Code != http.StatusUnprocessableEntity {
		t.Fatalf("first status = %d, want 422", first.Code)
	}
	second := doReq(t, mux, http.MethodPost, submitPath(code, "guest"), `{"url":"https://example.test/unsupported"}`, "")
	if second.Code != http.StatusTooManyRequests {
		t.Fatalf("second status = %d, want 429; body=%s", second.Code, second.Body.String())
	}
	if got := trackResolver.count(); got != 1 {
		t.Fatalf("resolver calls = %d, want one admitted failed resolution", got)
	}
}

func TestInvalidRoomSubmissionDoesNotConsumeAdmission(t *testing.T) {
	trackResolver := &submissionCountingResolver{err: resolver.ErrUnsupported}
	_, _, mux := newSubmissionLimitedServer(30, 1, func() time.Time { return time.Unix(100, 0) }, trackResolver)
	code, _ := createRoom(t, mux)
	invalid := doReq(t, mux, http.MethodPost, submitPath(code, "guest"), `{"url":" "}`, "")
	if invalid.Code != http.StatusBadRequest {
		t.Fatalf("blank URL status = %d, want 400", invalid.Code)
	}
	admitted := doReq(t, mux, http.MethodPost, submitPath(code, "host"), `{"url":"https://example.test/unsupported"}`, "")
	if admitted.Code != http.StatusUnprocessableEntity {
		t.Fatalf("valid attempt after blank URL status = %d, want 422; body=%s", admitted.Code, admitted.Body.String())
	}
	limited := doReq(t, mux, http.MethodPost, submitPath(code, "guest"), `{"url":"https://example.test/unsupported"}`, "")
	if limited.Code != http.StatusTooManyRequests {
		t.Fatalf("next valid attempt status = %d, want 429", limited.Code)
	}
	if trackResolver.count() != 1 {
		t.Fatalf("resolver calls = %d, want 1", trackResolver.count())
	}
}

func TestRoomSubmissionValidationPrecedesExhaustedAdmission(t *testing.T) {
	for _, tc := range []struct {
		name       string
		body       string
		unknown    bool
		full       bool
		wantStatus int
	}{
		{name: "unknown room", body: `{`, unknown: true, wantStatus: http.StatusNotFound},
		{name: "malformed body", body: `{`, wantStatus: http.StatusBadRequest},
		{name: "oversized body", body: fmt.Sprintf(`{"url":%q}`, strings.Repeat("x", 5000)), wantStatus: http.StatusRequestEntityTooLarge},
		{name: "queue full", body: `{"url":"https://example.test/track"}`, full: true, wantStatus: http.StatusConflict},
	} {
		t.Run(tc.name, func(t *testing.T) {
			trackResolver := &submissionCountingResolver{err: resolver.ErrUnsupported}
			_, store, mux := newSubmissionLimitedServer(30, 1, func() time.Time { return time.Unix(100, 0) }, trackResolver)
			code, _ := createRoom(t, mux)
			consume := doReq(t, mux, http.MethodPost, submitPath(code, "host"), `{"url":"https://example.test/consume"}`, "")
			if consume.Code != http.StatusUnprocessableEntity {
				t.Fatalf("consume status = %d", consume.Code)
			}
			if tc.unknown {
				code = "missing"
			}
			if tc.full {
				store.mu.Lock()
				store.rooms[code].Queue = make([]Track, maxQueueLength)
				store.mu.Unlock()
			}
			beforeCalls := trackResolver.count()
			rec := doReq(t, mux, http.MethodPost, submitPath(code, "guest"), tc.body, "")
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d; body=%s", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if got := trackResolver.count(); got != beforeCalls {
				t.Fatalf("precedence request invoked resolver: calls %d -> %d", beforeCalls, got)
			}
		})
	}
}

func TestRoomSubmissionRoomsAreIndependentAndReplenishWithoutSleep(t *testing.T) {
	now := time.Unix(100, 0)
	trackResolver := &submissionCountingResolver{}
	_, _, mux := newSubmissionLimitedServer(30, 1, func() time.Time { return now }, trackResolver)
	first, _ := createRoom(t, mux)
	second, _ := createRoom(t, mux)
	for _, code := range []string{first, second} {
		if rec := doReq(t, mux, http.MethodPost, submitPath(code, "host"), `{"url":"https://example.test/track"}`, ""); rec.Code != http.StatusCreated {
			t.Fatalf("room %s initial status = %d", code, rec.Code)
		}
	}
	if rec := doReq(t, mux, http.MethodPost, submitPath(first, "host"), `{"url":"https://example.test/track"}`, ""); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("exhausted status = %d, want 429", rec.Code)
	}
	now = now.Add(2 * time.Second)
	if rec := doReq(t, mux, http.MethodPost, submitPath(first, "guest"), `{"url":"https://example.test/track"}`, ""); rec.Code != http.StatusCreated {
		t.Fatalf("replenished status = %d, want 201; body=%s", rec.Code, rec.Body.String())
	}
}

func TestConcurrentRoomSubmissionsAreBounded(t *testing.T) {
	const burst = 10
	const callers = 64
	trackResolver := &submissionCountingResolver{}
	_, _, mux := newSubmissionLimitedServer(30, burst, func() time.Time { return time.Unix(100, 0) }, trackResolver)
	code, _ := createRoom(t, mux)
	start := make(chan struct{})
	statuses := make(chan int, callers)
	var requests sync.WaitGroup
	for i := 0; i < callers; i++ {
		requests.Add(1)
		go func(index int) {
			defer requests.Done()
			<-start
			alias := "host"
			if index%2 == 1 {
				alias = "guest"
			}
			req := httptest.NewRequest(http.MethodPost, submitPath(code, alias), strings.NewReader(`{"url":"https://example.test/track"}`))
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			statuses <- rec.Code
		}(i)
	}
	close(start)
	requests.Wait()
	close(statuses)
	created, limited := 0, 0
	for status := range statuses {
		switch status {
		case http.StatusCreated:
			created++
		case http.StatusTooManyRequests:
			limited++
		default:
			t.Fatalf("unexpected status %d", status)
		}
	}
	if created != burst || limited != callers-burst || trackResolver.count() != burst {
		t.Fatalf("created=%d limited=%d resolver=%d; want %d %d %d", created, limited, trackResolver.count(), burst, callers-burst, burst)
	}
}

// qmix#156: expiry during limiter work must override both allow and old 429.
func TestSubmissionExpiryDuringLimiterWork(t *testing.T) {
	for _, alias := range []string{"host", "guest"} {
		for _, exhausted := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/exhausted=%t", alias, exhausted), func(t *testing.T) {
				entered, release := make(chan struct{}), make(chan struct{})
				var once sync.Once
				defer once.Do(func() { close(release) })
				var calls atomic.Int32
				blockAt := int32(2)
				if exhausted {
					blockAt = 3
				}
				clock := func() time.Time {
					if calls.Add(1) == blockAt {
						close(entered)
						<-release
					}
					return time.Unix(100, 0)
				}
				store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, fixedCodeGenerator{code: "reuse"}, 30, 1, clock)
				trackResolver := &submissionCountingResolver{}
				server := NewServer(store, NewHub())
				server.Resolver = trackResolver
				mux := newTestMux(server)
				room, _ := store.CreateRoom()
				store.mu.Lock()
				oldRoom := store.rooms[room.Code]
				store.mu.Unlock()
				if exhausted {
					ref, _ := store.AppendPreflight(room.Code)
					if denial, err := admitAndRelease(store, ref); denial != nil || err != nil {
						t.Fatal("initial admission failed")
					}
				}
				done := make(chan *httptest.ResponseRecorder, 1)
				go func() {
					rec := httptest.NewRecorder()
					mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, submitPath(room.Code, alias), strings.NewReader(`{"url":"https://example.test/stale"}`)))
					done <- rec
				}()
				receiveWithin(t, entered)
				replaced := make(chan error, 1)
				go func() {
					store.mu.Lock()
					store.rooms[room.Code].LastActivity = time.Unix(100, 0).Add(-2 * time.Hour)
					store.mu.Unlock()
					store.sweepAt(time.Unix(100, 0))
					_, err := store.CreateRoom()
					replaced <- err
				}()
				if err := receiveWithin(t, replaced); err != nil {
					t.Fatal(err)
				}
				once.Do(func() { close(release) })
				rec := receiveWithin(t, done)
				store.mu.Lock()
				oldPending := oldRoom.appendReservations
				newPending := store.rooms[room.Code].appendReservations
				store.mu.Unlock()
				if oldPending != 0 || newPending != 0 {
					t.Errorf("stale admission leaked capacity: old=%d new=%d", oldPending, newPending)
				}
				if rec.Code != http.StatusNotFound {
					t.Errorf("stale status = %d, body=%s; want 404", rec.Code, rec.Body.String())
				}
				if got := trackResolver.count(); got != 0 {
					t.Errorf("stale request began resolver work: %d calls", got)
				}
				fresh := doReq(t, mux, http.MethodPost, submitPath(room.Code, alias), `{"url":"https://example.test/fresh"}`, "")
				if fresh.Code != http.StatusCreated {
					t.Fatalf("replacement bucket affected: %d %s", fresh.Code, fresh.Body.String())
				}
				limited := doReq(t, mux, http.MethodPost, submitPath(room.Code, alias), `{"url":"https://example.test/fresh"}`, "")
				if limited.Code != http.StatusTooManyRequests {
					t.Fatalf("replacement bucket not charged: %d", limited.Code)
				}
			})
		}
	}
}

type submissionResolverFunc func(context.Context, string) (*resolver.Track, error)

func (f submissionResolverFunc) Resolve(ctx context.Context, url string) (*resolver.Track, error) {
	return f(ctx, url)
}

// qmix#156: the last free slot belongs to the admitted in-flight operation.
func TestSubmissionReservesCapacityDuringResolution(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(fmt.Sprintf("resolverError=%t", fail), func(t *testing.T) {
			entered, release := make(chan struct{}), make(chan struct{})
			var once sync.Once
			defer once.Do(func() { close(release) })
			var calls atomic.Int32
			trackResolver := submissionResolverFunc(func(context.Context, string) (*resolver.Track, error) {
				if calls.Add(1) == 1 {
					close(entered)
					<-release
					if fail {
						return nil, resolver.ErrUnsupported
					}
				}
				return &resolver.Track{Title: "track"}, nil
			})
			_, store, mux := newSubmissionLimitedServer(30, 2, func() time.Time { return time.Unix(100, 0) }, trackResolver)
			room, _ := store.CreateRoom()
			store.mu.Lock()
			store.rooms[room.Code].Queue = make([]Track, maxQueueLength-1)
			store.mu.Unlock()
			done := make(chan int, 1)
			go func() {
				rec := httptest.NewRecorder()
				mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, submitPath(room.Code, "host"), strings.NewReader(`{"url":"https://example.test/first"}`)))
				done <- rec.Code
			}()
			receiveWithin(t, entered)
			// Completion while resolution is blocked also proves Store.mu is free.
			statuses := make(chan int, 32)
			for range cap(statuses) {
				go func() {
					rec := httptest.NewRecorder()
					mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, submitPath(room.Code, "guest"), strings.NewReader(`{"url":"https://example.test/competing"}`)))
					statuses <- rec.Code
				}()
			}
			for range cap(statuses) {
				if status := receiveWithin(t, statuses); status != http.StatusConflict {
					t.Errorf("competing status = %d, want 409", status)
				}
			}
			if got := calls.Load(); got != 1 {
				t.Errorf("resolver calls while reserved = %d, want 1", got)
			}
			once.Do(func() { close(release) })
			want := http.StatusCreated
			if fail {
				want = http.StatusUnprocessableEntity
			}
			if status := receiveWithin(t, done); status != want {
				t.Errorf("reserved result = %d, want %d", status, want)
			}
			view, _ := store.View(room.Code)
			wantLength := maxQueueLength
			if fail {
				wantLength--
			}
			if len(view.Queue) != wantLength {
				t.Errorf("queue length = %d, want %d", len(view.Queue), wantLength)
			}
			if !fail {
				if _, err := store.Skip(room.Code, room.HostToken); err != nil {
					t.Fatal(err)
				}
			}
			next := doReq(t, mux, http.MethodPost, submitPath(room.Code, "guest"), `{"url":"https://example.test/next"}`, "")
			if next.Code != http.StatusCreated {
				t.Fatalf("reservation leaked or competing attempts consumed token: %d %s", next.Code, next.Body.String())
			}
		})
	}
}
