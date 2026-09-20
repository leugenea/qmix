package server

import (
	"github.com/leugenea/qmix/internal/admission"
	"github.com/leugenea/qmix/internal/roomsubmission"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fixedCodeGenerator struct{ code string }

func (g fixedCodeGenerator) Generate() string { return g.code }

func TestStoreSubmissionAdmissionIsolatedByRoomIncarnation(t *testing.T) {
	now := time.Unix(100, 0)
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, fixedCodeGenerator{code: "room42"}, 30, 1, func() time.Time { return now })
	first, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	firstRef, err := store.AppendPreflight(first.Code)
	if err != nil {
		t.Fatal(err)
	}
	if denial, err := admitAndRelease(store, firstRef); err != nil || denial != nil {
		t.Fatalf("first admission = denial %v error %v", denial, err)
	}
	if denial, err := admitAndRelease(store, firstRef); err != nil || denial == nil {
		t.Fatalf("exhausted admission = denial %v error %v", denial, err)
	}

	store.mu.Lock()
	store.rooms[first.Code].LastActivity = now.Add(-2 * store.TTL)
	store.mu.Unlock()
	store.sweepAt(now)
	second, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	if second.Code != first.Code {
		t.Fatalf("reused code = %q, want %q", second.Code, first.Code)
	}
	secondRef, err := store.AppendPreflight(second.Code)
	if err != nil {
		t.Fatal(err)
	}
	if denial, err := admitAndRelease(store, secondRef); err != nil || denial != nil {
		t.Fatalf("replacement inherited exhausted limiter: denial %v error %v", denial, err)
	}
	if denial, err := admitAndRelease(store, firstRef); err != errRoomNotFound || denial != nil {
		t.Fatalf("stale incarnation admission = denial %v error %v", denial, err)
	}
}

func TestStoreSubmissionInvalidDirectConfigurationFailsClosed(t *testing.T) {
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 0, 0, nil)
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatal(err)
	}
	if denial, err := admitAndRelease(store, ref); err != nil || denial == nil {
		t.Fatalf("invalid direct configuration admission = denial %v error %v, want fail closed", denial, err)
	}
}

func TestStoreSubmissionAdmissionDoesNotHoldStoreMutexDuringLimiterWork(t *testing.T) {
	created := make(chan struct{})
	allowStarted := make(chan struct{})
	releaseAllow := make(chan struct{})
	calls := 0
	clock := func() time.Time {
		calls++
		if calls == 1 {
			close(created)
		} else {
			close(allowStarted)
			<-releaseAllow
		}
		return time.Unix(100, 0)
	}
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 30, 1, clock)
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatal(err)
	}
	<-created
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatal(err)
	}
	admissionDone := make(chan struct{})
	go func() {
		defer close(admissionDone)
		_, _ = admitAndRelease(store, ref)
	}()
	<-allowStarted

	viewDone := make(chan error, 1)
	go func() {
		_, viewErr := store.View(credentials.Code)
		viewDone <- viewErr
	}()
	select {
	case viewErr := <-viewDone:
		if viewErr != nil {
			t.Fatalf("View while limiter blocked: %v", viewErr)
		}
	case <-time.After(2 * time.Second):
		close(releaseAllow)
		t.Fatal("Store.mu remained held while submission limiter was running")
	}
	close(releaseAllow)
	<-admissionDone
}

// qmix#156: a competing append after validation must win before token charging.
func TestSubmissionQueueFillsBetweenCheckAndAdmission(t *testing.T) {
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 30, 1, func() time.Time { return time.Unix(100, 0) })
	room, _ := store.CreateRoom()
	ref, _ := store.AppendPreflight(room.Code)
	store.mu.Lock()
	store.rooms[room.Code].Queue = make([]Track, maxQueueLength-1)
	store.mu.Unlock()
	checked, filled := make(chan struct{}), make(chan struct{})
	go func() {
		<-checked
		_, err := store.Append(ref, Track{ID: "competitor"})
		if err != nil {
			panic(err)
		}
		close(filled)
	}()
	if err := store.CheckAppend(ref); err != nil {
		t.Fatal(err)
	}
	close(checked)
	select {
	case <-filled:
	case <-time.After(2 * time.Second):
		t.Fatal("append blocked")
	}
	denial, err := admitAndRelease(store, ref)
	if err != errQueueFull || denial != nil {
		t.Errorf("admission = %v, %v; want queue full without denial", denial, err)
	}
	store.mu.Lock()
	store.rooms[room.Code].Queue = nil
	store.mu.Unlock()
	if denial, err := admitAndRelease(store, ref); denial != nil || err != nil {
		t.Fatalf("queue-full attempt consumed token: %v, %v", denial, err)
	}
}

func TestSubmissionDefaultStoreHasExplicitBucket(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	room, _ := store.CreateRoom()
	store.mu.Lock()
	limiter := store.rooms[room.Code].submissionLimiter
	store.mu.Unlock()
	if limiter == nil {
		t.Fatal("default room has implicit nil admission")
	}
	for range 10 {
		if denial := limiter.Allow(); denial != nil {
			t.Fatal("default burst denied")
		}
	}
	if denial := limiter.Allow(); denial == nil {
		t.Fatal("default bucket did not enforce burst")
	}
}

// Admission-only tests release the slot as a handler does on resolver failure.
func admitAndRelease(store *Store, ref roomRef) (*admission.Error, error) {
	reservation, denial, err := store.AdmitAppend(ref)
	if reservation != nil {
		reservation.Release()
	}
	return denial, err
}

// qmix#156: reservations protect capacity even while limiter work is blocked.
func TestSubmissionReservationDuringLimiterWork(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	var calls atomic.Int32
	clock := func() time.Time {
		if calls.Add(1) == 2 {
			close(entered)
			<-release
		}
		return time.Unix(100, 0)
	}
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 30, 1, clock)
	room, _ := store.CreateRoom()
	ref, _ := store.AppendPreflight(room.Code)
	store.mu.Lock()
	store.rooms[room.Code].Queue = make([]Track, maxQueueLength-1)
	store.mu.Unlock()
	done := make(chan *appendReservation, 1)
	go func() { reservation, _, _ := store.AdmitAppend(ref); done <- reservation }()
	receiveWithin(t, entered)
	checked := make(chan [3]error, 1)
	go func() {
		checkErr := store.CheckAppend(ref)
		_, appendErr := store.Append(ref, Track{ID: "competing"})
		_, _, admitErr := store.AdmitAppend(ref)
		checked <- [3]error{checkErr, appendErr, admitErr}
	}()
	if errs := receiveWithin(t, checked); errs != [3]error{errQueueFull, errQueueFull, errQueueFull} {
		t.Errorf("reserved capacity checks = %v", errs)
	}
	once.Do(func() { close(release) })
	reservation := receiveWithin(t, done)
	if reservation == nil {
		t.Fatal("reserved admission failed")
	}
	defer reservation.Release()
	if _, err := reservation.Append(Track{ID: "owner"}); err != nil {
		t.Fatal(err)
	}
	view, _ := store.View(room.Code)
	if len(view.Queue) != maxQueueLength {
		t.Fatalf("queue length = %d", len(view.Queue))
	}
}

func TestSubmissionLimiterIdentityRevalidated(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	var calls atomic.Int32
	clock := func() time.Time {
		if calls.Add(1) == 2 {
			close(entered)
			<-release
		}
		return time.Unix(100, 0)
	}
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 30, 1, clock)
	room, _ := store.CreateRoom()
	ref, _ := store.AppendPreflight(room.Code)
	done := make(chan error, 1)
	go func() { _, err := admitAndRelease(store, ref); done <- err }()
	receiveWithin(t, entered)
	replaced := make(chan struct{})
	go func() {
		fresh := roomsubmission.NewLimiter(30, 1, func() time.Time { return time.Unix(100, 0) })
		store.mu.Lock()
		store.rooms[room.Code].submissionLimiter = fresh
		store.mu.Unlock()
		close(replaced)
	}()
	receiveWithin(t, replaced)
	once.Do(func() { close(release) })
	if err := receiveWithin(t, done); err != errRoomNotFound {
		t.Fatalf("changed limiter admission = %v", err)
	}
	if denial, err := admitAndRelease(store, ref); denial != nil || err != nil {
		t.Fatalf("replacement limiter affected: %v %v", denial, err)
	}
	store.mu.Lock()
	pending := store.rooms[room.Code].appendReservations
	store.mu.Unlock()
	if pending != 0 {
		t.Fatalf("stale admission leaked %d reservations", pending)
	}
}

func TestSubmissionReservationReleasePaths(t *testing.T) {
	for _, outcome := range []string{"release", "append", "append full", "expired", "limiter changed"} {
		t.Run(outcome, func(t *testing.T) {
			store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, fixedCodeGenerator{code: "reuse"}, 30, 1, func() time.Time { return time.Unix(100, 0) })
			credentials, _ := store.CreateRoom()
			ref, _ := store.AppendPreflight(credentials.Code)
			reservation, denial, err := store.AdmitAppend(ref)
			if err != nil || denial != nil {
				t.Fatalf("admission: %v %v", denial, err)
			}
			store.mu.Lock()
			old := store.rooms[credentials.Code]
			store.mu.Unlock()
			wantErr := error(nil)
			switch outcome {
			case "release":
				reservation.Release()
				wantErr = errRoomNotFound
			case "append full":
				store.mu.Lock()
				old.Queue = make([]Track, maxQueueLength)
				store.mu.Unlock()
				wantErr = errQueueFull
			case "expired":
				store.mu.Lock()
				old.LastActivity = time.Unix(100, 0).Add(-2 * time.Hour)
				store.mu.Unlock()
				store.sweepAt(time.Unix(100, 0))
				if _, err := store.CreateRoom(); err != nil {
					t.Fatal(err)
				}
				wantErr = errRoomNotFound
			case "limiter changed":
				store.mu.Lock()
				old.submissionLimiter = roomsubmission.NewLimiter(30, 1, nil)
				store.mu.Unlock()
				wantErr = errRoomNotFound
			}
			if _, err := reservation.Append(Track{ID: "resolved"}); err != wantErr {
				t.Fatalf("append error = %v, want %v", err, wantErr)
			}
			reservation.Release()
			reservation.Release()
			if _, err := reservation.Append(Track{ID: "duplicate"}); err != errRoomNotFound {
				t.Fatalf("reused reservation: %v", err)
			}
			store.mu.Lock()
			pending := old.appendReservations
			replacementPending := store.rooms[credentials.Code].appendReservations
			store.mu.Unlock()
			if pending != 0 || replacementPending != 0 {
				t.Fatalf("leaked reservation: old=%d replacement=%d", pending, replacementPending)
			}
			if outcome == "expired" {
				fresh, _ := store.AppendPreflight(credentials.Code)
				if denial, err := admitAndRelease(store, fresh); denial != nil || err != nil {
					t.Fatalf("replacement affected: %v %v", denial, err)
				}
			}
		})
	}
}

func TestSubmissionDenialReleasesReservation(t *testing.T) {
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 30, 1, func() time.Time { return time.Unix(100, 0) })
	room, _ := store.CreateRoom()
	ref, _ := store.AppendPreflight(room.Code)
	if denial, err := admitAndRelease(store, ref); denial != nil || err != nil {
		t.Fatal("first admission failed")
	}
	for range maxQueueLength + 1 {
		reservation, denial, err := store.AdmitAppend(ref)
		if reservation != nil || denial == nil || err != nil {
			t.Fatalf("denial leaked capacity: reservation=%v denial=%v error=%v", reservation, denial, err)
		}
	}
	store.mu.Lock()
	pending := store.rooms[room.Code].appendReservations
	store.mu.Unlock()
	if pending != 0 {
		t.Fatalf("denial leaked %d slots", pending)
	}
}

func TestSubmissionConcurrentReservationsAndAppendsNeverExceedCapacity(t *testing.T) {
	const callers = maxQueueLength + 28
	store := NewStoreWithSubmissionLimit(time.Hour, time.Hour, &seqCodeGen{}, 30, callers, func() time.Time { return time.Unix(100, 0) })
	room, _ := store.CreateRoom()
	ref, _ := store.AppendPreflight(room.Code)
	start := make(chan struct{})
	type result struct {
		reservation *appendReservation
		denial      *admission.Error
		err         error
	}
	results := make(chan result, callers)
	for range callers {
		go func() {
			<-start
			reservation, denial, err := store.AdmitAppend(ref)
			results <- result{reservation, denial, err}
		}()
	}
	close(start)
	var reservations []*appendReservation
	for range callers {
		result := receiveWithin(t, results)
		if result.denial != nil {
			t.Fatalf("capacity rejection consumed token: %v", result.denial)
		}
		if result.err != nil {
			if result.err != errQueueFull {
				t.Fatal(result.err)
			}
		} else {
			reservations = append(reservations, result.reservation)
		}
	}
	if len(reservations) != maxQueueLength {
		t.Fatalf("admitted %d, want %d", len(reservations), maxQueueLength)
	}
	appended := make(chan error, len(reservations))
	for _, reservation := range reservations {
		go func() { _, err := reservation.Append(Track{ID: "concurrent"}); reservation.Release(); appended <- err }()
	}
	for range reservations {
		if err := receiveWithin(t, appended); err != nil {
			t.Fatal(err)
		}
	}
	view, _ := store.View(room.Code)
	if len(view.Queue) != maxQueueLength {
		t.Fatalf("queue length = %d", len(view.Queue))
	}
	store.mu.Lock()
	pending := store.rooms[room.Code].appendReservations
	store.mu.Unlock()
	if pending != 0 {
		t.Fatalf("pending = %d", pending)
	}
	// All 28 capacity-rejected requests must leave their tokens available.
	for range callers - maxQueueLength {
		if _, err := store.Skip(room.Code, room.HostToken); err != nil {
			t.Fatal(err)
		}
		if denial, err := admitAndRelease(store, ref); denial != nil || err != nil {
			t.Fatalf("capacity rejection charged token: %v %v", denial, err)
		}
	}
}
