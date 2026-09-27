package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/ytdlpcap"
)

type captureCanonicalResolver struct {
	inputs []string
}

func (r *captureCanonicalResolver) Resolve(_ context.Context, input string) (*resolver.Track, error) {
	r.inputs = append(r.inputs, input)
	return &resolver.Track{Title: "Video", ResolvedBy: "youtube", Source: input}, nil
}

func TestQueueSubmissionCanonicalizesURLForBothAliases(t *testing.T) {
	const source = "https://YouTube.com/watch?v=video-id&list=playlist-id"
	for _, route := range []string{"host", "guest"} {
		t.Run(route, func(t *testing.T) {
			r := &captureCanonicalResolver{}
			s, _ := newResolverTestServer(r)
			mux := newTestMux(s)
			code, _ := createRoom(t, mux)
			path := "/rooms/" + code + "/queue"
			if route == "guest" {
				path = "/r/" + code + "/queue"
			}
			input := " \t" + source + "\n"
			rec := doReq(t, mux, http.MethodPost, path, fmt.Sprintf(`{"url":%q}`, input), "")
			if rec.Code != http.StatusCreated {
				t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
			}
			if len(r.inputs) != 1 || r.inputs[0] != source {
				t.Fatalf("resolver inputs = %q, want [%q]", r.inputs, source)
			}
			var track Track
			if route == "guest" {
				var payload guestResp
				decodeBody(t, rec, &payload)
				if payload.Status != "accepted" {
					t.Fatalf("guest response = %+v", payload)
				}
				track = payload.Track
			} else {
				decodeBody(t, rec, &track)
			}
			if track.URL != source || track.ResolvedBy != "youtube" {
				t.Fatalf("track = %+v, want canonical URL %q", track, source)
			}
			view := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
			var body struct {
				Queue []Track `json:"queue"`
			}
			decodeBody(t, view, &body)
			if len(body.Queue) != 1 || body.Queue[0].URL != source || body.Queue[0].ID != track.ID {
				t.Fatalf("stored queue = %+v, want same canonical track", body.Queue)
			}
		})
	}
}

func TestQueueSubmissionRejectsNonVideoYouTubeBeforeCapacity(t *testing.T) {
	for _, route := range []string{"host", "guest"} {
		t.Run(route, func(t *testing.T) {
			limiter := ytdlpcap.New(1, 0)
			if err := limiter.Acquire(context.Background()); err != nil {
				t.Fatal(err)
			}
			defer limiter.Release()
			s, _ := newResolverTestServer(resolver.NewMux(resolver.Matcher{
				Domains:  []string{"youtube.com", "youtu.be"},
				Resolver: &resolver.YouTube{Runner: rejectingURLRunner{t: t}, Limiter: limiter},
			}))
			mux := newTestMux(s)
			code, _ := createRoom(t, mux)
			path := "/rooms/" + code + "/queue"
			if route == "guest" {
				path = "/r/" + code + "/queue"
			}
			for _, input := range []string{
				" \thttps://www.youtube.com/playlist?list=playlist-id\n",
				"https://www.youtube.com/@SomeChannel/videos",
				"https://www.youtube.com/results?search_query=a",
				"https://www.youtube.com/c/Name",
			} {
				rec := doReq(t, mux, http.MethodPost, path, fmt.Sprintf(`{"url":%q}`, input), "")
				if rec.Code != http.StatusUnprocessableEntity {
					t.Fatalf("input %q: status = %d, body = %s", input, rec.Code, rec.Body.String())
				}
				want := errorEnvelope{Error: "unsupported_service", Message: "unsupported track service"}
				var got errorEnvelope
				decodeBody(t, rec, &got)
				if got != want || rec.Header().Get("Retry-After") != "" {
					t.Fatalf("input %q: error = %+v, Retry-After = %q", input, got, rec.Header().Get("Retry-After"))
				}
			}
			if running, queued := limiter.Stats(); running != 1 || queued != 0 {
				t.Fatalf("limiter stats = %d, %d; want 1, 0", running, queued)
			}
			view := doReq(t, mux, http.MethodGet, "/rooms/"+code, "", "")
			var state struct {
				Queue []Track `json:"queue"`
			}
			decodeBody(t, view, &state)
			if len(state.Queue) != 0 {
				t.Fatalf("queue = %+v, want empty", state.Queue)
			}
		})
	}
}

type rejectingURLRunner struct{ t *testing.T }

func (r rejectingURLRunner) Run(_ context.Context, url string) ([]byte, error) {
	r.t.Errorf("runner called for rejected URL %q", url)
	return json.Marshal(struct {
		Title string `json:"title"`
	}{Title: "Unexpected"})
}

type captureYouTubeMetadataRunner struct {
	urls []string
}

func (r *captureYouTubeMetadataRunner) Run(_ context.Context, input string) ([]byte, error) {
	r.urls = append(r.urls, input)
	return []byte(`{"title":"Selected Video","duration":123}`), nil
}

func TestQueueSubmissionExplicitYouTubeVideoUsesCanonicalRunnerInput(t *testing.T) {
	for _, route := range []string{"host", "guest"} {
		t.Run(route, func(t *testing.T) {
			const source = "https://music.youtube.com/watch?v=&v=selected-video&list=playlist-id"
			runner := &captureYouTubeMetadataRunner{}
			s, _ := newResolverTestServer(resolver.NewMux(resolver.Matcher{
				Domains:  []string{"youtube.com", "youtu.be"},
				Resolver: &resolver.YouTube{Runner: runner},
			}))
			mux := newTestMux(s)
			code, _ := createRoom(t, mux)
			path := "/rooms/" + code + "/queue"
			if route == "guest" {
				path = "/r/" + code + "/queue"
			}
			rec := doReq(t, mux, http.MethodPost, path, fmt.Sprintf(`{"url":%q}`, " 	"+source+"\n"), "")
			if rec.Code != http.StatusCreated {
				t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
			}
			if len(runner.urls) != 1 || runner.urls[0] != source {
				t.Fatalf("runner inputs = %q, want [%q]", runner.urls, source)
			}
			var track Track
			if route == "guest" {
				var payload guestResp
				decodeBody(t, rec, &payload)
				if payload.Status != "accepted" {
					t.Fatalf("guest response = %+v", payload)
				}
				track = payload.Track
			} else {
				decodeBody(t, rec, &track)
			}
			if track.URL != source || track.ResolvedBy != "youtube" || track.Title != "Selected Video" {
				t.Fatalf("track = %+v, want canonical selected video", track)
			}
		})
	}
}
