package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/admission"
	"github.com/leugenea/qmix/internal/roomcreate"
)

func newRateLimitedServer(t *testing.T, rate, burst, identities int, clock func() time.Time) (*Server, *Store, *http.ServeMux) {
	t.Helper()
	server, store := newTestServer()
	server.roomCreateLimiter = roomcreate.NewLimiter(rate, burst, identities, clock)
	server.clientIdentities = roomcreate.NewIdentityResolver(nil)
	return server, store, newTestMux(server)
}

func createFromPeer(mux *http.ServeMux, remote string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/rooms", nil)
	req.RemoteAddr = remote
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func assertAdmissionResponse(t *testing.T, denial *admission.Error, wantStatus int, wantRetry, wantCode, wantMessage string) {
	t.Helper()
	rec := httptest.NewRecorder()
	writeAdmissionError(rec, denial)
	if rec.Code != wantStatus {
		t.Fatalf("status = %d, want %d", rec.Code, wantStatus)
	}
	if got := rec.Header().Get("Retry-After"); got != wantRetry {
		t.Fatalf("Retry-After = %q, want %q", got, wantRetry)
	}
	var fields map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &fields); err != nil {
		t.Fatal(err)
	}
	want := map[string]string{"error": wantCode, "message": wantMessage}
	if !reflect.DeepEqual(fields, want) {
		t.Fatalf("error envelope = %v, want %v", fields, want)
	}
}

func TestWriteAdmissionErrorMapsAllowlistedMetadata(t *testing.T) {
	assertAdmissionResponse(
		t,
		admission.NewRoomCreationRateLimit(9),
		http.StatusTooManyRequests,
		"9",
		"rate_limited",
		"room creation rate limit exceeded",
	)
}

func TestWriteAdmissionErrorUsesSafeFallbackForNilAndZeroValue(t *testing.T) {
	for _, tc := range []struct {
		name   string
		denial *admission.Error
	}{
		{name: "nil", denial: nil},
		{name: "zero value", denial: &admission.Error{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			assertAdmissionResponse(
				t,
				tc.denial,
				http.StatusServiceUnavailable,
				"1",
				"admission_denied",
				"request temporarily unavailable",
			)
		})
	}
}

func TestCreateRoomRateLimitHTTPContractAndNoAllocation(t *testing.T) {
	clock := func() time.Time { return time.Unix(100, 0) }
	_, store, mux := newRateLimitedServer(t, 10, 2, 8, clock)
	for i := 0; i < 2; i++ {
		if rec := createFromPeer(mux, "198.51.100.1:1234"); rec.Code != http.StatusCreated {
			t.Fatalf("burst request %d status = %d", i+1, rec.Code)
		}
	}

	rec := createFromPeer(mux, "198.51.100.1:9999")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429; body=%s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", got)
	}
	if got := rec.Header().Get("Retry-After"); got != "6" {
		t.Fatalf("Retry-After = %q, want centralized delta-seconds value 6", got)
	}
	var envelope errorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope != (errorEnvelope{Error: "rate_limited", Message: "room creation rate limit exceeded"}) {
		t.Fatalf("error envelope = %+v", envelope)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(rec.Body.Bytes(), &fields); err != nil {
		t.Fatal(err)
	}
	if len(fields) != 2 || fields["error"] == nil || fields["message"] == nil {
		t.Fatalf("error envelope keys = %v, want exactly error and message", fields)
	}
	store.mu.Lock()
	rooms := len(store.rooms)
	store.mu.Unlock()
	if rooms != 2 {
		t.Fatalf("rooms allocated = %d, want 2", rooms)
	}
}

func TestConcurrentCreateRoomAdmissionIsAtomicBeforeAllocation(t *testing.T) {
	const burst = 4
	const callers = 32
	clock := func() time.Time { return time.Unix(100, 0) }
	_, store, mux := newRateLimitedServer(t, 1, burst, 8, clock)

	// Hold allocation so every admitted handler is parked after admission while
	// rejected handlers can report immediately. Channels control the start and
	// prove the admission count without scheduler sleeps.
	store.mu.Lock()
	locked := true
	defer func() {
		if locked {
			store.mu.Unlock()
		}
	}()

	start := make(chan struct{})
	results := make(chan int, callers)
	var requests sync.WaitGroup
	requests.Add(callers)
	for i := 0; i < callers; i++ {
		go func(port int) {
			defer requests.Done()
			<-start
			remote := "198.51.100.1:" + strconv.Itoa(10_000+port)
			results <- createFromPeer(mux, remote).Code
		}(i)
	}
	close(start)

	watchdog := time.NewTimer(3 * time.Second)
	defer watchdog.Stop()
	for rejected := 0; rejected < callers-burst; rejected++ {
		select {
		case status := <-results:
			if status != http.StatusTooManyRequests {
				t.Fatalf("completed request %d status = %d while allocation blocked, want 429", rejected+1, status)
			}
		case <-watchdog.C:
			t.Fatal("concurrent admission did not reject callers beyond burst before allocation")
		}
	}
	if rooms := len(store.rooms); rooms != 0 {
		t.Fatalf("rooms allocated while Store.mu held = %d, want 0", rooms)
	}

	store.mu.Unlock()
	locked = false
	for admitted := 0; admitted < burst; admitted++ {
		select {
		case status := <-results:
			if status != http.StatusCreated {
				t.Fatalf("admitted request %d status = %d, want 201", admitted+1, status)
			}
		case <-watchdog.C:
			t.Fatal("admitted concurrent room creation did not finish")
		}
	}
	requests.Wait()
	store.mu.Lock()
	rooms := len(store.rooms)
	store.mu.Unlock()
	if rooms != burst {
		t.Fatalf("rooms allocated = %d, want burst %d", rooms, burst)
	}
}

func TestCreateRoomRateLimitUsesTransportIdentity(t *testing.T) {
	clock := func() time.Time { return time.Unix(100, 0) }
	_, _, mux := newRateLimitedServer(t, 1, 1, 8, clock)
	first := httptest.NewRequest(http.MethodPost, "/rooms", nil)
	first.RemoteAddr = "198.51.100.1:1000"
	first.Header.Set("X-Forwarded-For", "203.0.113.1")
	first.Header.Set("Forwarded", "for=203.0.113.2")
	first.Header.Set("X-Real-IP", "203.0.113.3")
	firstRec := httptest.NewRecorder()
	mux.ServeHTTP(firstRec, first)
	if firstRec.Code != http.StatusCreated {
		t.Fatalf("first status = %d", firstRec.Code)
	}

	spoofed := httptest.NewRequest(http.MethodPost, "/rooms", strings.NewReader(""))
	spoofed.RemoteAddr = "198.51.100.1:2000"
	spoofed.Header.Set("X-Forwarded-For", "192.0.2.99")
	spoofedRec := httptest.NewRecorder()
	mux.ServeHTTP(spoofedRec, spoofed)
	if spoofedRec.Code != http.StatusTooManyRequests {
		t.Fatalf("spoofed forwarding bypass status = %d, want 429", spoofedRec.Code)
	}

	if rec := createFromPeer(mux, "198.51.100.2:1000"); rec.Code != http.StatusCreated {
		t.Fatalf("isolated peer status = %d, want 201", rec.Code)
	}
}

func TestRateLimitAdmissionDoesNotWaitForStoreMutex(t *testing.T) {
	clock := func() time.Time { return time.Unix(100, 0) }
	_, store, mux := newRateLimitedServer(t, 1, 1, 8, clock)
	if rec := createFromPeer(mux, "198.51.100.1:1000"); rec.Code != http.StatusCreated {
		t.Fatalf("initial create status = %d", rec.Code)
	}

	store.mu.Lock()
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- createFromPeer(mux, "198.51.100.1:2000") }()
	select {
	case rec := <-done:
		store.mu.Unlock()
		if rec.Code != http.StatusTooManyRequests {
			t.Fatalf("contended status = %d, want 429", rec.Code)
		}
	case <-time.After(2 * time.Second):
		store.mu.Unlock()
		t.Fatal("rate-limit admission waited for Store.mu")
	}
}
