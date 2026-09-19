package server

import (
	"reflect"
	"testing"
	"time"
)

type eventClonePartiallyOpaque struct {
	Values []string
	marker string
}

type eventCloneNamedMap map[string][]string

type eventCloneNamedSlice []map[string]*[1][]string

func TestCloneEventDataClonesExportedContainersInPartiallyOpaqueStruct(t *testing.T) {
	original := eventClonePartiallyOpaque{
		Values: []string{"original"},
		marker: "preserved",
	}

	cloned := cloneEventData(original).(eventClonePartiallyOpaque)
	if cloned.marker != original.marker {
		t.Fatalf("unexported marker = %q, want %q", cloned.marker, original.marker)
	}
	cloned.Values[0] = "changed"
	if original.Values[0] != "original" {
		t.Fatalf("clone aliases exported slice: original=%q cloned=%q", original.Values[0], cloned.Values[0])
	}
}

func TestCloneEventValueSafelyPreservesInvalidValue(t *testing.T) {
	cloned := cloneEventValue(reflect.Value{})
	if cloned.IsValid() {
		t.Fatalf("clone = %#v, want invalid reflect.Value", cloned)
	}
}

func TestCloneEventDataPreservesTypedNilContainers(t *testing.T) {
	type payload struct {
		Interface any
		Pointer   *eventClonePartiallyOpaque
		Map       eventCloneNamedMap
		Slice     eventCloneNamedSlice
	}
	var nilPointer *eventClonePartiallyOpaque
	var nilMap eventCloneNamedMap
	var nilSlice eventCloneNamedSlice

	for name, original := range map[string]any{
		"pointer": nilPointer,
		"map":     nilMap,
		"slice":   nilSlice,
	} {
		t.Run(name, func(t *testing.T) {
			cloned := cloneEventData(original)
			if reflect.TypeOf(cloned) != reflect.TypeOf(original) {
				t.Fatalf("clone type = %T, want %T", cloned, original)
			}
			if !reflect.ValueOf(cloned).IsNil() {
				t.Fatalf("clone = %#v, want typed nil", cloned)
			}
		})
	}

	cloned := cloneEventData(payload{
		Interface: nil,
		Pointer:   nilPointer,
		Map:       nilMap,
		Slice:     nilSlice,
	}).(payload)
	if cloned.Interface != nil || cloned.Pointer != nil || cloned.Map != nil || cloned.Slice != nil {
		t.Fatalf("nested typed nils became non-nil: %#v", cloned)
	}
}

func TestCloneEventDataRecursivelyIsolatesNestedConcreteContainers(t *testing.T) {
	array := &[1][]string{{"original"}}
	originalSlice := eventCloneNamedSlice{{"array": array}}
	originalMap := eventCloneNamedMap{"values": {"original"}}
	original := map[string]any{
		"slice": originalSlice,
		"map":   originalMap,
	}

	cloned := cloneEventData(original).(map[string]any)
	clonedSlice, ok := cloned["slice"].(eventCloneNamedSlice)
	if !ok {
		t.Fatalf("nested slice type = %T, want eventCloneNamedSlice", cloned["slice"])
	}
	clonedMap, ok := cloned["map"].(eventCloneNamedMap)
	if !ok {
		t.Fatalf("nested map type = %T, want eventCloneNamedMap", cloned["map"])
	}
	clonedSlice[0]["array"][0][0] = "changed"
	clonedMap["values"][0] = "changed"
	cloned["added"] = true

	if got := (*array)[0][0]; got != "original" {
		t.Fatalf("nested pointer/array/slice clone aliases original: got %q", got)
	}
	if got := originalMap["values"][0]; got != "original" {
		t.Fatalf("nested map/slice clone aliases original: got %q", got)
	}
	if _, exists := original["added"]; exists {
		t.Fatal("cloned outer map aliases original")
	}
}

func TestCloneEventDataSafelyPreservesOpaqueStructAndLeafValues(t *testing.T) {
	instant := time.Date(2026, time.September, 19, 12, 34, 56, 789, time.FixedZone("test", 90))
	signal := make(chan struct{})
	original := struct {
		Instant *time.Time
		Signal  chan struct{}
		Count   int
	}{
		Instant: &instant,
		Signal:  signal,
		Count:   7,
	}

	cloned := cloneEventData(original).(struct {
		Instant *time.Time
		Signal  chan struct{}
		Count   int
	})
	if cloned.Instant == original.Instant {
		t.Fatal("time pointer was not cloned")
	}
	if !cloned.Instant.Equal(*original.Instant) {
		t.Fatalf("cloned time = %v, want %v", cloned.Instant, original.Instant)
	}
	if cloned.Signal != signal || cloned.Count != original.Count {
		t.Fatalf("leaf values changed: clone=%#v original=%#v", cloned, original)
	}
}
