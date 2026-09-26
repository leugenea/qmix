package server

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/importer"
	"go/parser"
	"go/token"
	"go/types"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestStoreViewIsImmutableSnapshot(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom: %v", err)
	}
	store.mu.Lock()
	store.rooms[credentials.Code].Queue = []Track{{ID: "one", Title: "original"}}
	store.rooms[credentials.Code].Current = &Current{TrackID: "current", Title: "playing"}
	store.mu.Unlock()

	view, err := store.View(credentials.Code)
	if err != nil {
		t.Fatalf("View: %v", err)
	}
	view.Queue[0].Title = "mutated"
	view.Current.Title = "mutated"

	fresh, err := store.View(credentials.Code)
	if err != nil {
		t.Fatalf("View again: %v", err)
	}
	if fresh.Queue[0].Title != "original" || fresh.Current.Title != "playing" {
		t.Fatalf("stored state aliased by view: %+v", fresh)
	}
}

func TestStoreSnapshotsAreSafeOutsideLock(t *testing.T) {
	server, store := newTestServer()
	room := mustCreateRoom(t, store)
	store.mu.Lock()
	room.Queue = []Track{{ID: "queued", Title: "queued title"}}
	room.Current = &Current{TrackID: "current", URL: "https://example.com/current", Title: "current title", Artist: "artist", ResolvedBy: "test"}
	store.mu.Unlock()

	events, cancel := subscribeTestEvents(t, store, server.hub, room.Code)
	defer cancel()
	snapshot, err := store.EventSnapshot(room.Code)
	if err != nil {
		t.Fatalf("EventSnapshot: %v", err)
	}
	queue := snapshot.Data["queue"].([]Track)
	current := snapshot.Data["current"].(map[string]interface{})
	queue[0].Title = "changed"
	current["title"] = "changed"

	streamView, err := store.CurrentStream(room.Code)
	if err != nil {
		t.Fatalf("CurrentStream: %v", err)
	}
	streamView.Title = "changed"

	freshSnapshot, err := store.EventSnapshot(room.Code)
	if err != nil {
		t.Fatalf("EventSnapshot again: %v", err)
	}
	freshQueue := freshSnapshot.Data["queue"].([]Track)
	freshCurrent := freshSnapshot.Data["current"].(map[string]interface{})
	freshStream, err := store.CurrentStream(room.Code)
	if err != nil {
		t.Fatalf("CurrentStream again: %v", err)
	}
	if freshQueue[0].Title != "queued title" || freshCurrent["title"] != "current title" || freshStream.Title != "current title" {
		t.Fatalf("snapshot mutation reached stored state: queue=%+v current=%+v stream=%+v", freshQueue, freshCurrent, freshStream)
	}
	select {
	case event := <-events:
		t.Fatalf("read-only snapshots published event %+v", event)
	default:
	}
}

func TestConcurrentAppendEventsMatchCommittedOrder(t *testing.T) {
	server, store := newTestServer()
	room := mustCreateRoom(t, store)
	events, cancel := subscribeTestEvents(t, store, server.hub, room.Code)
	defer cancel()
	firstRef, err := store.AppendPreflight(room.Code)
	if err != nil {
		t.Fatalf("first preflight: %v", err)
	}
	secondRef, err := store.AppendPreflight(room.Code)
	if err != nil {
		t.Fatalf("second preflight: %v", err)
	}

	start := make(chan struct{})
	var writers sync.WaitGroup
	for _, appendCall := range []struct {
		ref   roomRef
		track Track
	}{{firstRef, Track{ID: "first"}}, {secondRef, Track{ID: "second"}}} {
		appendCall := appendCall
		writers.Add(1)
		go func() {
			defer writers.Done()
			<-start
			if _, err := store.Append(appendCall.ref, appendCall.track); err != nil {
				t.Errorf("Append(%s): %v", appendCall.track.ID, err)
			}
		}()
	}
	close(start)
	writers.Wait()

	firstEvent, secondEvent := <-events, <-events
	if firstEvent.ID >= secondEvent.ID || firstEvent.Name != "queue_updated" || secondEvent.Name != "queue_updated" {
		t.Fatalf("events out of order: %+v then %+v", firstEvent, secondEvent)
	}
	firstQueue := decodeEventPayload[struct {
		Queue []Track `json:"queue"`
	}](t, firstEvent).Queue
	secondQueue := decodeEventPayload[struct {
		Queue []Track `json:"queue"`
	}](t, secondEvent).Queue
	view, err := store.View(room.Code)
	if err != nil {
		t.Fatalf("View: %v", err)
	}
	if len(firstQueue) != 1 || len(secondQueue) != 2 || len(view.Queue) != 2 {
		t.Fatalf("event snapshots do not match committed states: first=%+v second=%+v view=%+v", firstQueue, secondQueue, view.Queue)
	}
	for index := range view.Queue {
		if secondQueue[index].ID != view.Queue[index].ID {
			t.Fatalf("final event queue = %+v, committed queue = %+v", secondQueue, view.Queue)
		}
	}
	secondQueue[0].Title = "mutated after publish"
	fresh, err := store.View(room.Code)
	if err != nil {
		t.Fatalf("View after event mutation: %v", err)
	}
	if fresh.Queue[0].Title == "mutated after publish" {
		t.Fatal("event payload aliases committed queue")
	}
}

func TestEventDeliveriesDoNotShareMutableData(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hubs := []*Hub{NewHub(), NewHub()}
	for _, hub := range hubs {
		NewServer(store, hub)
	}
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom: %v", err)
	}
	var subscriptions []*subscriber
	var cancellations []func()
	for _, hub := range hubs {
		for subscriberIndex := 0; subscriberIndex < 2; subscriberIndex++ {
			sub, _, cancel, subscribeErr := store.subscribeEvents(credentials.Code, hub)
			if subscribeErr != nil {
				t.Fatalf("subscribeEvents: %v", subscribeErr)
			}
			subscriptions = append(subscriptions, sub)
			cancellations = append(cancellations, cancel)
		}
	}
	defer func() {
		for _, cancel := range cancellations {
			cancel()
		}
	}()
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatalf("AppendPreflight: %v", err)
	}
	if _, err := store.Append(ref, Track{ID: "one", Title: "original"}); err != nil {
		t.Fatalf("Append: %v", err)
	}
	var events []Event
	for _, sub := range subscriptions {
		events = append(events, receiveWithin(t, sub.ch))
	}
	first := decodeEventPayload[struct {
		Queue []Track `json:"queue"`
	}](t, events[0])
	first.Queue[0].Title = "mutated"
	for index, event := range events[1:] {
		payload := decodeEventPayload[struct {
			Queue []Track `json:"queue"`
		}](t, event)
		if got := payload.Queue[0].Title; got != "original" {
			t.Fatalf("delivery %d queue title = %q, want original", index+1, got)
		}
		if event.Data != events[0].Data {
			t.Fatalf("delivery %d differs from one immutable serialized payload", index+1)
		}
	}
	view, err := store.View(credentials.Code)
	if err != nil {
		t.Fatalf("View: %v", err)
	}
	if got := view.Queue[0].Title; got != "original" {
		t.Fatalf("stored queue title = %q, want original", got)
	}
}

func TestConcurrentEventConsumersAreIsolated(t *testing.T) {
	hub := NewHub()
	ref := roomRef{code: "room", generation: 1}
	first, cancelFirst := hub.subscribeRef(ref)
	defer cancelFirst()
	second, cancelSecond := hub.subscribeRef(ref)
	defer cancelSecond()
	hub.publishRef(ref, "queue_updated", map[string]interface{}{"queue": []Track{{ID: "one", Title: "original"}}})
	firstEvent, secondEvent := receiveWithin(t, first.ch), receiveWithin(t, second.ch)
	firstQueue := decodeEventPayload[struct {
		Queue []Track `json:"queue"`
	}](t, firstEvent).Queue
	start := make(chan struct{})
	var consumers sync.WaitGroup
	consumers.Add(2)
	go func() {
		defer consumers.Done()
		<-start
		for index := 0; index < 10000; index++ {
			firstQueue[0].Title = "mutated"
		}
	}()
	go func() {
		defer consumers.Done()
		<-start
		for index := 0; index < 10000; index++ {
			var payload struct {
				Queue []Track `json:"queue"`
			}
			if err := json.Unmarshal([]byte(secondEvent.Data.(eventJSON)), &payload); err != nil {
				t.Errorf("decode event: %v", err)
				return
			}
			if payload.Queue[0].Title != "original" {
				t.Errorf("second consumer saw mutation")
				return
			}
		}
	}()
	close(start)
	consumers.Wait()
	if got := decodeEventPayload[struct {
		Queue []Track `json:"queue"`
	}](t, secondEvent).Queue[0].Title; got != "original" {
		t.Fatalf("second consumer queue title = %q, want original", got)
	}
}

func TestProductionHandlersDoNotReachIntoStoreInternals(t *testing.T) {
	fileSet := token.NewFileSet()
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatalf("read package directory: %v", err)
	}
	var files []*ast.File
	handlerFiles := make(map[*ast.File]bool)
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		file, parseErr := parser.ParseFile(fileSet, name, nil, 0)
		if parseErr != nil {
			t.Fatalf("parse %s: %v", name, parseErr)
		}
		files = append(files, file)
		if name == "handlers.go" || name == "guest_page.go" {
			handlerFiles[file] = true
		}
	}
	typeImporter, err := goListImporter(fileSet)
	if err != nil {
		t.Fatalf("create production importer: %v", err)
	}
	selectors, err := forbiddenStoreSelectors(fileSet, files, handlerFiles, typeImporter)
	if err != nil {
		t.Fatalf("type-check production package: %v", err)
	}
	for _, selector := range selectors {
		t.Errorf("production handler accesses forbidden Store field .%s at %s", selector.Sel.Name, fileSet.Position(selector.Pos()))
	}

	for _, file := range files {
		for _, declaration := range file.Decls {
			function, ok := declaration.(*ast.FuncDecl)
			if ok && function.Recv != nil && function.Name.Name == "Get" {
				t.Errorf("Store exposes forbidden live-room Get API at %s", fileSet.Position(function.Pos()))
			}
		}
	}
}

func goListImporter(fileSet *token.FileSet) (types.Importer, error) {
	command := exec.Command("go", "list", "-export", "-deps", "-json", ".")
	output, err := command.Output()
	if err != nil {
		return nil, fmt.Errorf("go list production dependencies: %w", err)
	}
	exports := make(map[string]string)
	decoder := json.NewDecoder(bytes.NewReader(output))
	for {
		var listedPackage struct {
			ImportPath string
			Export     string
		}
		if err := decoder.Decode(&listedPackage); err != nil {
			if err == io.EOF {
				break
			}
			return nil, fmt.Errorf("decode go list output: %w", err)
		}
		if listedPackage.Export != "" {
			exports[listedPackage.ImportPath] = listedPackage.Export
		}
	}
	return importer.ForCompiler(fileSet, "gc", func(importPath string) (io.ReadCloser, error) {
		exportPath := exports[importPath]
		if exportPath == "" {
			return nil, fmt.Errorf("no export data for %s", importPath)
		}
		return os.Open(exportPath)
	}), nil
}

func forbiddenStoreSelectors(fileSet *token.FileSet, files []*ast.File, targetFiles map[*ast.File]bool, typeImporter types.Importer) ([]*ast.SelectorExpr, error) {
	info := &types.Info{
		Defs:       make(map[*ast.Ident]types.Object),
		Uses:       make(map[*ast.Ident]types.Object),
		Selections: make(map[*ast.SelectorExpr]*types.Selection),
		Types:      make(map[ast.Expr]types.TypeAndValue),
	}
	configuration := types.Config{Importer: typeImporter}
	checkedPackage, err := configuration.Check(files[0].Name.Name, fileSet, files, info)
	if err != nil {
		return nil, err
	}
	storeObject, _ := checkedPackage.Scope().Lookup("Store").(*types.TypeName)
	serverObject, _ := checkedPackage.Scope().Lookup("Server").(*types.TypeName)
	if storeObject == nil || serverObject == nil {
		return nil, fmt.Errorf("package must define Store and Server types")
	}
	storeStruct, ok := storeObject.Type().Underlying().(*types.Struct)
	if !ok {
		return nil, fmt.Errorf("Store must be a struct")
	}
	forbiddenFields := make(map[types.Object]bool)
	for index := 0; index < storeStruct.NumFields(); index++ {
		field := storeStruct.Field(index)
		if field.Name() == "mu" || field.Name() == "rooms" {
			forbiddenFields[field] = true
		}
	}

	var forbidden []*ast.SelectorExpr
	for _, file := range files {
		if !targetFiles[file] {
			continue
		}
		for _, declaration := range file.Decls {
			function, ok := declaration.(*ast.FuncDecl)
			if !ok || function.Body == nil || function.Recv == nil || len(function.Recv.List) != 1 || len(function.Recv.List[0].Names) != 1 {
				continue
			}
			receiver := info.Defs[function.Recv.List[0].Names[0]]
			if receiver == nil || namedTypeObject(receiver.Type()) != serverObject {
				continue
			}
			ast.Inspect(function.Body, func(node ast.Node) bool {
				selector, ok := node.(*ast.SelectorExpr)
				if ok {
					selection := info.Selections[selector]
					if selection != nil && forbiddenFields[selection.Obj()] {
						forbidden = append(forbidden, selector)
					}
				}
				return true
			})
		}
	}
	return forbidden, nil
}

func namedTypeObject(value types.Type) *types.TypeName {
	if pointer, ok := value.(*types.Pointer); ok {
		value = pointer.Elem()
	}
	named, _ := value.(*types.Named)
	if named == nil {
		return nil
	}
	return named.Obj()
}

func TestStoreSelectorCheckIsReceiverSpecificAndAliasAware(t *testing.T) {
	tests := []struct {
		name       string
		source     string
		wantFields string
	}{
		{
			name: "rejects Store fields through every typed Server-method reference",
			source: `package synthetic

type Store struct { mu int; rooms int }
type Server struct { store *Store }

func (s *Server) forbiddenDirect() { _ = s.store.mu; _ = s.store.rooms }
func (server *Server) forbiddenAliases(condition bool, parameter *Store) {
	st := server.store
	alias := st
	_ = alias.mu
	if condition { st = parameter }
	_ = st.rooms
	_ = parameter.mu
}
`,
			wantFields: "mu,rooms,mu,rooms,mu",
		},
		{
			name: "allows unrelated field objects shadowing and non Server functions",
			source: `package synthetic

type Store struct { mu int; rooms int }
type Server struct { store *Store }
type OtherStore struct { mu int; rooms int }
type Other struct { store *OtherStore; mu int; rooms int }

func allowedStoreParameter(store *Store) { _ = store.mu; _ = store.rooms }
func (s *Server) allowedUnrelated() {
	other := &Other{}
	_ = other.mu
	_ = other.rooms
	_ = other.store.mu
	{
		s := other
		_ = s.mu
		_ = s.rooms
	}
}
`,
			wantFields: "",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fileSet := token.NewFileSet()
			file, err := parser.ParseFile(fileSet, "synthetic.go", test.source, 0)
			if err != nil {
				t.Fatalf("parse synthetic source: %v", err)
			}
			selectors, err := forbiddenStoreSelectors(fileSet, []*ast.File{file}, map[*ast.File]bool{file: true}, importer.Default())
			if err != nil {
				t.Fatalf("type-check synthetic source: %v", err)
			}
			if got := strings.Join(selectorNames(selectors), ","); got != test.wantFields {
				t.Fatalf("forbidden selector names = %q, want %q", got, test.wantFields)
			}
		})
	}
}

func selectorNames(selectors []*ast.SelectorExpr) []string {
	names := make([]string, len(selectors))
	for index, selector := range selectors {
		names[index] = selector.Sel.Name
	}
	return names
}

type reusedCodeGenerator struct{ code string }

func (g reusedCodeGenerator) Generate() string { return g.code }

func TestRefInvalidationAndCancelAreIdempotent(t *testing.T) {
	ref := roomRef{code: "room", generation: 1}
	for attempt := 0; attempt < 1000; attempt++ {
		hub := NewHub()
		sub, cancel := hub.subscribeRef(ref)
		start := make(chan struct{})
		var calls sync.WaitGroup
		calls.Add(2)
		go func() { defer calls.Done(); <-start; cancel() }()
		go func() { defer calls.Done(); <-start; hub.invalidateRef(ref) }()
		close(start)
		calls.Wait()
		cancel()
		select {
		case <-sub.done:
		default:
			t.Fatal("subscriber remains connected")
		}
	}
}

func TestSweepTerminatesRefHTTPStreamBeforeCodeReuse(t *testing.T) {
	const code = "reuseh"
	store := NewStore(time.Hour, time.Hour, reusedCodeGenerator{code: code})
	hub := NewHub()
	NewServer(store, hub)
	if _, err := store.CreateRoom(); err != nil {
		t.Fatalf("CreateRoom old: %v", err)
	}
	sub, snapshot, cancelSub, err := store.subscribeEvents(code, hub)
	if err != nil {
		t.Fatalf("subscribeEvents: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	writer := &controlledSSEWriter{header: make(http.Header)}
	subscribed := make(chan struct{})
	handlerDone := make(chan struct{})
	go func() {
		defer close(handlerDone)
		close(subscribed)
		hub.serveSubscriptionHTTP(ctx, writer, sub, cancelSub, snapshot)
	}()
	<-subscribed
	store.mu.Lock()
	store.rooms[code].LastActivity = time.Now().Add(-2 * store.TTL)
	store.mu.Unlock()
	store.sweep()
	select {
	case <-handlerDone:
	case <-time.After(time.Second):
		t.Fatal("SSE handler remained connected after expiry")
	}
	writesAfterExpiry := writer.writes
	replacement, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom replacement: %v", err)
	}
	ref, err := store.AppendPreflight(replacement.Code)
	if err != nil {
		t.Fatalf("AppendPreflight replacement: %v", err)
	}
	if _, err := store.Append(ref, Track{ID: "replacement"}); err != nil {
		t.Fatalf("Append replacement: %v", err)
	}
	if writer.writes != writesAfterExpiry {
		t.Fatalf("expired stream received replacement event: writes = %d, want %d", writer.writes, writesAfterExpiry)
	}
}

func TestSweepDisconnectsExpiredIncarnationAndCodeReuseIsIsolated(t *testing.T) {
	const code = "reuse1"
	store := NewStore(time.Hour, time.Hour, reusedCodeGenerator{code: code})
	hub := NewHub()
	NewServer(store, hub)

	oldCredentials, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom old: %v", err)
	}
	oldSub, _, cancelOld, err := store.subscribeEvents(oldCredentials.Code, hub)
	if err != nil {
		t.Fatalf("SubscribeEvents old: %v", err)
	}
	defer cancelOld()

	store.mu.Lock()
	store.rooms[code].LastActivity = time.Now().Add(-2 * store.TTL)
	store.mu.Unlock()
	store.sweep()
	select {
	case <-oldSub.done:
	default:
		t.Fatal("expired room subscriber remains connected")
	}

	newCredentials, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom replacement: %v", err)
	}
	newSub, snapshot, cancelNew, err := store.subscribeEvents(newCredentials.Code, hub)
	if err != nil {
		t.Fatalf("SubscribeEvents replacement: %v", err)
	}
	defer cancelNew()
	ref, err := store.AppendPreflight(code)
	if err != nil {
		t.Fatalf("AppendPreflight replacement: %v", err)
	}
	if _, err := store.Append(ref, Track{ID: "replacement"}); err != nil {
		t.Fatalf("Append replacement: %v", err)
	}
	if event := receiveWithin(t, newSub.ch); event.ID != snapshot.ID+1 || event.Name != "queue_updated" {
		t.Fatalf("replacement event = %+v, snapshot ID = %d", event, snapshot.ID)
	}
	select {
	case event := <-oldSub.ch:
		t.Fatalf("old incarnation received replacement event: %+v", event)
	default:
	}
}

func TestSharedStoreFansMutationsToEveryServerHub(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hubOne, hubTwo := NewHub(), NewHub()
	serverOne := NewServer(store, hubOne)
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom: %v", err)
	}
	one, oneSnapshot, cancelOne, err := store.subscribeEvents(credentials.Code, hubOne)
	if err != nil {
		t.Fatalf("SubscribeEvents hub one: %v", err)
	}
	defer cancelOne()
	response := addTrack(t, newTestMux(serverOne), credentials.Code, "https://example.com/first")
	if response.Code != http.StatusCreated {
		t.Fatalf("first append status = %d; body=%s", response.Code, response.Body.String())
	}
	if event := receiveWithin(t, one.ch); event.ID != 1 {
		t.Fatalf("hub one first event = %+v, want ID 1", event)
	}

	serverTwo := NewServer(store, hubTwo)
	NewServer(store, hubOne) // registration is idempotent
	two, twoSnapshot, cancelTwo, err := store.subscribeEvents(credentials.Code, hubTwo)
	if err != nil {
		t.Fatalf("SubscribeEvents hub two: %v", err)
	}
	defer cancelTwo()
	if oneSnapshot.ID != 0 || twoSnapshot.ID != 0 {
		t.Fatalf("initial snapshot IDs = %d, %d; want 0, 0", oneSnapshot.ID, twoSnapshot.ID)
	}
	oneAfterSecondHub, _, cancelOneAfterSecondHub, err := store.subscribeEvents(credentials.Code, hubOne)
	if err != nil {
		t.Fatalf("SubscribeEvents hub one after hub two: %v", err)
	}
	defer cancelOneAfterSecondHub()
	_, hubOneSnapshot, cancelHubOneSnapshot, err := store.subscribeEvents(credentials.Code, hubOne)
	if err != nil {
		t.Fatalf("snapshot hub one: %v", err)
	}
	cancelHubOneSnapshot()
	if hubOneSnapshot.ID != 1 {
		t.Fatalf("hub one snapshot ID = %d, want 1 from hub one rather than hub two", hubOneSnapshot.ID)
	}

	for index, mutation := range []struct {
		server  *Server
		oneID   int64
		twoID   int64
		address string
	}{{serverTwo, 2, 1, "second"}, {serverOne, 3, 2, "third"}} {
		response = addTrack(t, newTestMux(mutation.server), credentials.Code, "https://example.com/"+mutation.address)
		if response.Code != http.StatusCreated {
			t.Fatalf("mutation %d status = %d; body=%s", index+2, response.Code, response.Body.String())
		}
		if event := receiveWithin(t, one.ch); event.ID != mutation.oneID || event.Name != "queue_updated" {
			t.Fatalf("hub one event after mutation %d = %+v", index+2, event)
		}
		if event := receiveWithin(t, oneAfterSecondHub.ch); event.ID != mutation.oneID || event.Name != "queue_updated" {
			t.Fatalf("second hub one subscriber event after mutation %d = %+v", index+2, event)
		}
		if event := receiveWithin(t, two.ch); event.ID != mutation.twoID || event.Name != "queue_updated" {
			t.Fatalf("hub two event after mutation %d = %+v", index+2, event)
		}
	}
	for name, subscription := range map[string]*subscriber{"one": one, "one-again": oneAfterSecondHub, "two": two} {
		select {
		case event := <-subscription.ch:
			t.Fatalf("hub %s received duplicate event from duplicate registration: %+v", name, event)
		default:
		}
	}
}

func TestStoreSubscribeSnapshotPrecedesBlockedMutation(t *testing.T) {
	store := NewStore(time.Hour, time.Hour, &seqCodeGen{})
	hub := NewHub()
	NewServer(store, hub)
	credentials, err := store.CreateRoom()
	if err != nil {
		t.Fatalf("CreateRoom: %v", err)
	}
	ref, err := store.AppendPreflight(credentials.Code)
	if err != nil {
		t.Fatalf("AppendPreflight: %v", err)
	}

	store.mu.Lock()
	started := make(chan struct{})
	appendDone := make(chan error, 1)
	go func() {
		close(started)
		_, appendErr := store.Append(ref, Track{ID: "after-snapshot"})
		appendDone <- appendErr
	}()
	<-started
	sub, snapshot, cancel, err := store.subscribeEventsLocked(credentials.Code, hub)
	store.mu.Unlock()
	if err != nil {
		t.Fatalf("subscribeEventsLocked: %v", err)
	}
	defer cancel()
	if snapshot.ID != 0 || len(snapshot.Data["queue"].([]Track)) != 0 {
		t.Fatalf("snapshot = %+v, want empty state at ID 0", snapshot)
	}
	if err := <-appendDone; err != nil {
		t.Fatalf("Append: %v", err)
	}
	if event := receiveWithin(t, sub.ch); event.ID != 1 || event.Name != "queue_updated" {
		t.Fatalf("post-snapshot event = %+v, want queue_updated ID 1", event)
	}
}

func TestNewServerWithNilStorePreservesConstructionTiming(t *testing.T) {
	server := NewServerWithLogger(nil, NewHub(), nil)
	if server == nil || server.store != nil {
		t.Fatalf("server = %+v, want constructed server with nil store", server)
	}
}
