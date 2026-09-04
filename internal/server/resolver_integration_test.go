package server

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
)

// stubResolver resolves configured URLs to fixed metadata and returns a canned
// error otherwise, so HTTP integration tests don't touch the network.
type stubResolver struct {
	meta *resolver.Track
	err  error
}

func (s stubResolver) Resolve(_ context.Context, _ string) (*resolver.Track, error) {
	return s.meta, s.err
}

// newResolverTestServer returns a Server with the given resolver wired in.
func newResolverTestServer(r resolver.Resolver) (*Server, *Store) {
	gen := &seqCodeGen{}
	store := NewStore(time.Hour, time.Hour, gen)
	hub := NewHub()
	s := NewServer(store, hub)
	s.Resolver = r
	return s, store
}

// TestAddTrackResolvesMetadata verifies a resolved track carries metadata and
// resolvedBy in the queue.
func TestAddTrackResolvesMetadata(t *testing.T) {
	r := stubResolver{meta: &resolver.Track{
		Title: "Some Song", Artist: "Some Artist", DurationSec: 199, Source: "https://open.spotify.com/track/x", ResolvedBy: "spotify",
	}}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://open.spotify.com/track/x")
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusCreated, rec.Body.String())
	}
	var track Track
	decodeBody(t, rec, &track)
	if track.Title != "Some Song" || track.Artist != "Some Artist" || track.DurationSec != 199 || track.ResolvedBy != "spotify" {
		t.Fatalf("track = %+v", track)
	}

	// Persisted in the room view too (stream_url untouched in M2).
	rec = doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 1 || view.Queue[0].Title != "Some Song" || view.Queue[0].Artist != "Some Artist" {
		t.Fatalf("view = %+v", view)
	}
}

// TestAddTrackUnknownDomain422 verifies unknown domains map to 422 (not 500).
func TestAddTrackUnknownDomain422(t *testing.T) {
	r := stubResolver{err: resolver.ErrUnsupported}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://example.com/song")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
	if !containsStr(rec.Body.String(), "unsupported") {
		t.Fatalf("body = %q, want human-readable message", rec.Body.String())
	}
}

// TestAddTrackInvalidLink422 verifies an invalid URL maps to 422.
func TestAddTrackInvalidLink422(t *testing.T) {
	r := stubResolver{err: resolver.ErrInvalid}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "::::not a url")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
}

// TestAddTrackNoAnonymous422 maps no-anonymous-path to 422.
func TestAddTrackNoAnonymous422(t *testing.T) {
	r := stubResolver{err: resolver.ErrNoAnonymous}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://vk.com/audio1")
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
}

// TestAddTrackServiceError502 maps an upstream failure to 502.
func TestAddTrackServiceError502(t *testing.T) {
	r := stubResolver{err: errors.New("spotify: service error: upstream down")}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	rec := addTrack(t, mux, code, "https://open.spotify.com/track/x")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusBadGateway, rec.Body.String())
	}
}

// TestAddTrackResolvedQueueNotGrown verifies failed resolution does not append.
func TestAddTrackResolvedQueueNotGrown(t *testing.T) {
	r := stubResolver{err: resolver.ErrUnsupported}
	s, _ := newResolverTestServer(r)
	mux := newTestMux(s)
	code, _ := createRoom(t, mux)

	addTrack(t, mux, code, "https://example.com/x")
	rec := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
	var view struct {
		Queue []Track `json:"queue"`
	}
	decodeBody(t, rec, &view)
	if len(view.Queue) != 0 {
		t.Fatalf("queue = %+v, want empty on failed resolution", view.Queue)
	}
}

func containsStr(s, sub string) bool {
	return strings.Contains(s, sub)
}
