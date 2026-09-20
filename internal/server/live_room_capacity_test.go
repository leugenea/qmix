package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/admission"
	"github.com/leugenea/qmix/internal/config"
)

type countingCodeGenerator struct {
	mu    sync.Mutex
	calls int
}

func (g *countingCodeGenerator) Generate() string {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.calls++
	return string(rune('a'+g.calls/26)) + string(rune('a'+g.calls%26)) + "room"
}

func (g *countingCodeGenerator) count() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.calls
}

type countingReader struct {
	mu    sync.Mutex
	reads int
}

func (r *countingReader) Read(p []byte) (int, error) {
	r.mu.Lock()
	r.reads++
	value := byte(r.reads)
	r.mu.Unlock()
	return bytes.NewReader(bytes.Repeat([]byte{value}, len(p))).Read(p)
}

func (r *countingReader) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.reads
}

type blockingReader struct {
	started chan struct{}
	release chan struct{}
}

func (r *blockingReader) Read(p []byte) (int, error) {
	close(r.started)
	<-r.release
	for i := range p {
		p[i] = 1
	}
	return len(p), nil
}

type stagedCancelContext struct {
	mu       sync.Mutex
	errCalls int
}

func (*stagedCancelContext) Deadline() (time.Time, bool) { return time.Time{}, false }
func (*stagedCancelContext) Done() <-chan struct{}       { return nil }
func (*stagedCancelContext) Value(any) any               { return nil }
func (c *stagedCancelContext) Err() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.errCalls++
	if c.errCalls >= 3 {
		return context.Canceled
	}
	return nil
}

func capacityStore(t *testing.T, maximum int, gen CodeGenerator) *Store {
	t.Helper()
	store, err := NewStoreWithLimits(time.Hour, 1500*time.Millisecond, gen, maximum, 30, 10, nil)
	if err != nil {
		t.Fatalf("NewStoreWithLimits: %v", err)
	}
	return store
}

func requireCapacityDenial(t *testing.T, err error, retry int) {
	t.Helper()
	var denial *admission.Error
	if !errors.As(err, &denial) {
		t.Fatalf("error = %v, want typed admission denial", err)
	}
	if denial.Code() != "room_capacity_exhausted" || denial.RetryAfterSeconds() != retry {
		t.Fatalf("denial = code %q retry %d", denial.Code(), denial.RetryAfterSeconds())
	}
}

func TestNewStoreWithLimitsRejectsInvalidCapacity(t *testing.T) {
	for _, maximum := range []int{0, -1} {
		if store, err := NewStoreWithLimits(time.Hour, time.Second, &seqCodeGen{}, maximum, 30, 10, nil); err == nil || store != nil {
			t.Fatalf("maximum %d: store=%v error=%v, want constructor failure", maximum, store, err)
		}
	}
}

func TestCreateRoomCapacityBoundaryConsumesOnlySuccessfulAllocationState(t *testing.T) {
	gen := &countingCodeGenerator{}
	entropy := &countingReader{}
	store := capacityStore(t, 2, gen)
	store.tokenRandom = entropy

	for wantRooms := 1; wantRooms <= 2; wantRooms++ {
		if _, err := store.CreateRoom(); err != nil {
			t.Fatalf("create at %d: %v", wantRooms, err)
		}
		if len(store.rooms) != wantRooms {
			t.Fatalf("rooms = %d, want %d", len(store.rooms), wantRooms)
		}
	}
	beforeGeneration := store.nextGen
	requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
	if len(store.rooms) != 2 || store.nextGen != beforeGeneration || gen.count() != 2 || entropy.count() != 2 {
		t.Fatalf("rejected create consumed allocation state: rooms=%d generation=%d codes=%d entropy=%d", len(store.rooms), store.nextGen, gen.count(), entropy.count())
	}
}

func TestConcurrentCreateRoomNeverExceedsCapacity(t *testing.T) {
	const maximum = 7
	const callers = 64
	store := capacityStore(t, maximum, &countingCodeGenerator{})
	start := make(chan struct{})
	results := make(chan error, callers)
	var requests sync.WaitGroup
	requests.Add(callers)
	for range callers {
		go func() {
			defer requests.Done()
			<-start
			_, err := store.CreateRoom()
			results <- err
		}()
	}
	close(start)
	requests.Wait()
	close(results)

	succeeded := 0
	for err := range results {
		if err == nil {
			succeeded++
			continue
		}
		requireCapacityDenial(t, err, 2)
	}
	if succeeded != maximum || len(store.rooms) != maximum || store.nextGen != maximum {
		t.Fatalf("successes=%d rooms=%d generation=%d, want %d", succeeded, len(store.rooms), store.nextGen, maximum)
	}
}

func TestEmptyAndNonEmptyExpiryEachReleaseOneCapacitySlot(t *testing.T) {
	for _, nonEmpty := range []bool{false, true} {
		t.Run(map[bool]string{false: "empty", true: "non-empty"}[nonEmpty], func(t *testing.T) {
			store := capacityStore(t, 1, &countingCodeGenerator{})
			credentials, err := store.CreateRoom()
			if err != nil {
				t.Fatal(err)
			}
			store.mu.Lock()
			room := store.rooms[credentials.Code]
			if nonEmpty {
				room.Queue = []Track{{ID: "queued"}}
				room.LastActivity = time.Unix(0, 0).Add(-store.NonEmptyTTL - time.Second)
			} else {
				room.LastActivity = time.Unix(0, 0).Add(-store.TTL - time.Second)
			}
			store.mu.Unlock()

			requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
			store.sweepAt(time.Unix(0, 0))
			if _, err := store.CreateRoom(); err != nil {
				t.Fatalf("create after expiry: %v", err)
			}
			requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
		})
	}
}

func TestCreateRoomCapacityHTTPContract(t *testing.T) {
	store := capacityStore(t, 1, &countingCodeGenerator{})
	server := NewServer(store, NewHub())
	mux := newTestMux(server)
	if rec := doReq(t, mux, http.MethodPost, "/rooms", "", ""); rec.Code != http.StatusCreated {
		t.Fatalf("first create status = %d", rec.Code)
	}

	rec := doReq(t, mux, http.MethodPost, "/rooms", "", "")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503; body=%s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q", got)
	}
	if got := rec.Header().Get("Retry-After"); got != "2" {
		t.Fatalf("Retry-After = %q, want janitor cadence rounded up to 2", got)
	}
	var fields map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &fields); err != nil {
		t.Fatal(err)
	}
	want := map[string]string{"error": "room_capacity_exhausted", "message": "room capacity is temporarily exhausted"}
	if !reflect.DeepEqual(fields, want) || len(fields) != 2 {
		t.Fatalf("envelope = %#v, want %#v", fields, want)
	}
}

func TestExistingRoomOperationsRemainUsableAtCapacity(t *testing.T) {
	store := capacityStore(t, 1, &countingCodeGenerator{})
	server := NewServer(store, NewHub())
	server.StreamBackend = &stubStreamBackend{res: streamBodyResult("audio")}
	mux := newTestMux(server)
	code, token := createRoom(t, mux)
	if rec := doReq(t, mux, http.MethodPost, "/rooms", "", ""); rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("capacity create status = %d", rec.Code)
	}
	if rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", ""); rec.Code != http.StatusOK {
		t.Fatalf("get status = %d", rec.Code)
	}
	sub, _, cancel, err := store.subscribeEvents(code, server.hub)
	if err != nil {
		t.Fatal(err)
	}
	defer cancel()
	addedResponse := addTrack(t, mux, code, "https://example.test/song")
	if addedResponse.Code != http.StatusCreated {
		t.Fatalf("add status = %d", addedResponse.Code)
	}
	var added Track
	if err := json.Unmarshal(addedResponse.Body.Bytes(), &added); err != nil {
		t.Fatal(err)
	}
	if event := receiveWithin(t, sub.ch); event.Name != "queue_updated" {
		t.Fatalf("SSE event = %+v", event)
	}
	reorder := doReq(t, mux, http.MethodPatch, "/rooms/"+code+"/queue", `{"order":["`+added.ID+`"]}`, token)
	if reorder.Code != http.StatusOK {
		t.Fatalf("reorder status = %d; body=%s", reorder.Code, reorder.Body.String())
	}
	if rec := doReq(t, mux, http.MethodPost, "/rooms/"+code+"/skip", "", token); rec.Code != http.StatusOK {
		t.Fatalf("skip status = %d", rec.Code)
	}
	if rec := doReq(t, mux, http.MethodGet, "/rooms/"+code+"/current/stream", "", ""); rec.Code != http.StatusOK || rec.Body.String() != "audio" {
		t.Fatalf("stream status=%d body=%q", rec.Code, rec.Body.String())
	}
}

func TestCanceledCreateRequestDoesNotAllocateRoom(t *testing.T) {
	store := capacityStore(t, 1, &countingCodeGenerator{})
	mux := newTestMux(NewServer(store, NewHub()))
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	req := httptest.NewRequest(http.MethodPost, "/rooms", strings.NewReader("")).WithContext(ctx)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if len(store.rooms) != 0 || store.nextGen != 0 {
		t.Fatalf("canceled request allocated rooms=%d generation=%d", len(store.rooms), store.nextGen)
	}
}

func TestNewAppValidatesAndWiresMaxLiveRooms(t *testing.T) {
	cfg, err := config.Parse(func(string) (string, bool) { return "", false })
	if err != nil {
		t.Fatal(err)
	}
	cfg.Rooms.MaxLiveRooms = 0
	if app, err := NewApp(cfg, nil, Dependencies{CheckExecutable: func(string) bool { return true }}); !errors.Is(err, ErrInvalidMaxLiveRooms) || app != nil {
		t.Fatalf("invalid capacity: app=%v err=%v", app, err)
	}

	cfg.Rooms.MaxLiveRooms = 1
	app, err := NewApp(cfg, nil, Dependencies{CheckExecutable: func(string) bool { return true }})
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	if _, err := app.Store.CreateRoom(); err != nil {
		t.Fatal(err)
	}
	requireCapacityDenial(t, func() error { _, err := app.Store.CreateRoom(); return err }(), 60)
}

func TestCapacityExpiryInvalidatesStaleReferencesAndSubscribersBeforeCodeReuse(t *testing.T) {
	store := capacityStore(t, 1, reusedCodeGenerator{code: "reuse1"})
	hub := NewHub()
	NewServer(store, hub)
	old, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	oldRef, err := store.AppendPreflight(old.Code)
	if err != nil {
		t.Fatal(err)
	}
	oldSub, _, cancelOld, err := store.subscribeEvents(old.Code, hub)
	if err != nil {
		t.Fatal(err)
	}
	defer cancelOld()
	store.mu.Lock()
	store.rooms[old.Code].LastActivity = time.Unix(0, 0).Add(-store.TTL - time.Second)
	store.mu.Unlock()
	store.sweepAt(time.Unix(0, 0))
	select {
	case <-oldSub.done:
	default:
		t.Fatal("expired subscriber remained connected")
	}

	replacement, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	if replacement.Code != old.Code {
		t.Fatalf("replacement code = %q, want reused %q", replacement.Code, old.Code)
	}
	if _, err := store.Append(oldRef, Track{ID: "stale"}); !errors.Is(err, errRoomNotFound) {
		t.Fatalf("stale append error = %v, want room not found", err)
	}
	view, err := store.View(replacement.Code)
	if err != nil || len(view.Queue) != 0 {
		t.Fatalf("replacement mutated by stale append: view=%+v err=%v", view, err)
	}
	requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
}

func TestFailedCredentialGenerationDoesNotLeakCapacity(t *testing.T) {
	store := capacityStore(t, 1, &countingCodeGenerator{})
	store.tokenRandom = errorReader{err: errors.New("entropy unavailable")}
	if credentials, err := store.CreateRoom(); err == nil || credentials != (RoomCredentials{}) {
		t.Fatalf("failed create = %+v, %v", credentials, err)
	}
	if len(store.rooms) != 0 || store.nextGen != 0 {
		t.Fatalf("failed create leaked rooms=%d generation=%d", len(store.rooms), store.nextGen)
	}
	store.tokenRandom = &countingReader{}
	if _, err := store.CreateRoom(); err != nil {
		t.Fatalf("create after failure: %v", err)
	}
	requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 2)
}

func TestCapacityRetryAfterRoundsSubsecondCadenceUpToOne(t *testing.T) {
	store, err := NewStoreWithLimits(time.Hour, time.Nanosecond, &countingCodeGenerator{}, 1, 30, 10, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.CreateRoom(); err != nil {
		t.Fatal(err)
	}
	requireCapacityDenial(t, func() error { _, err := store.CreateRoom(); return err }(), 1)
}

func TestCreateRoomContextCanceledWhileWaitingDoesNotAllocate(t *testing.T) {
	store := capacityStore(t, 2, &countingCodeGenerator{})
	blocked := &blockingReader{started: make(chan struct{}), release: make(chan struct{})}
	store.tokenRandom = blocked
	firstDone := make(chan error, 1)
	go func() {
		_, err := store.CreateRoom()
		firstDone <- err
	}()
	<-blocked.started

	ctx, cancel := context.WithCancel(context.Background())
	secondDone := make(chan error, 1)
	go func() {
		_, err := store.CreateRoomContext(ctx)
		secondDone <- err
	}()
	cancel()
	close(blocked.release)
	if err := <-firstDone; err != nil {
		t.Fatalf("first create: %v", err)
	}
	if err := <-secondDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled create error = %v", err)
	}
	if len(store.rooms) != 1 || store.nextGen != 1 {
		t.Fatalf("canceled waiter allocated rooms=%d generation=%d", len(store.rooms), store.nextGen)
	}
}

func TestCreateRoomContextCanceledAtCommitDoesNotAllocate(t *testing.T) {
	store := capacityStore(t, 1, &countingCodeGenerator{})
	ctx := &stagedCancelContext{}
	credentials, err := store.CreateRoomContext(ctx)
	if !errors.Is(err, context.Canceled) || credentials != (RoomCredentials{}) {
		t.Fatalf("canceled create = %+v, %v", credentials, err)
	}
	if len(store.rooms) != 0 || store.nextGen != 0 {
		t.Fatalf("commit-time cancellation allocated rooms=%d generation=%d", len(store.rooms), store.nextGen)
	}
}
