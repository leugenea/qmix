package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/resolver"
	"github.com/leugenea/qmix/internal/stream"
)

const (
	streamAcceptanceDuration    = 10 * time.Minute
	streamAcceptanceChunk       = 64 * 1024
	streamAcceptanceInterval    = time.Second
	streamAcceptanceMaxPause    = 5 * time.Second
	streamAcceptanceTimeout     = 15 * time.Minute
	streamAcceptanceFixtureID   = "FwDo7MdaxhA"
	streamAcceptanceFixtureURL  = "https://www.youtube.com/watch?v=" + streamAcceptanceFixtureID
	streamAcceptanceMinDuration = 10 * 60
)

type acceptanceSelection struct {
	ID       string  `json:"id"`
	Title    string  `json:"title"`
	Duration float64 `json:"duration"`
}

func streamAcceptanceFixtureTrack() *resolver.Track {
	return &resolver.Track{
		Title:       streamAcceptanceFixtureURL,
		DurationSec: 4059,
		Source:      streamAcceptanceFixtureURL,
		ResolvedBy:  "youtube",
	}
}

type acceptanceYTDLPRunner struct {
	bin string

	mu        sync.Mutex
	searches  int
	selection acceptanceSelection
}

func (r *acceptanceYTDLPRunner) Search(ctx context.Context, input string) ([]byte, error) {
	r.mu.Lock()
	r.searches++
	r.mu.Unlock()

	cmd := exec.CommandContext(ctx, r.bin,
		"--skip-download", "--dump-json", "--no-warnings", "--no-playlist",
		"-f", "bestaudio", input,
	)
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	var selected acceptanceSelection
	if err := json.Unmarshal(out, &selected); err != nil {
		return nil, fmt.Errorf("parse acceptance yt-dlp output: %w", err)
	}
	if selected.ID != streamAcceptanceFixtureID {
		return nil, fmt.Errorf("acceptance fixture id = %q, want %q", selected.ID, streamAcceptanceFixtureID)
	}
	if selected.Duration < streamAcceptanceMinDuration {
		return nil, fmt.Errorf("acceptance fixture duration = %.0fs, want at least %ds", selected.Duration, streamAcceptanceMinDuration)
	}
	r.mu.Lock()
	r.selection = selected
	r.mu.Unlock()
	return out, nil
}

func (r *acceptanceYTDLPRunner) snapshot() (int, acceptanceSelection) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.searches, r.selection
}

func TestStreamAcceptanceFixtureUsesYouTubeResolver(t *testing.T) {
	fixture := streamAcceptanceFixtureTrack()
	if fixture.Source != streamAcceptanceFixtureURL || fixture.ResolvedBy != "youtube" {
		t.Fatalf("acceptance fixture source = %q resolved by %q, want %q resolved by youtube", fixture.Source, fixture.ResolvedBy, streamAcceptanceFixtureURL)
	}
}

func TestAcceptanceYTDLPRunnerPassesCompleteInputUnchanged(t *testing.T) {
	dir := t.TempDir()
	capture := filepath.Join(dir, "inputs")
	bin := filepath.Join(dir, "yt-dlp")
	script := fmt.Sprintf(`#!/bin/sh
for input do :; done
printf '%%s\n' "$input" >> %q
printf '{"id":"%s","title":"fixture","duration":%d}'
`, capture, streamAcceptanceFixtureID, streamAcceptanceMinDuration)
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatalf("write fake yt-dlp: %v", err)
	}

	runner := &acceptanceYTDLPRunner{bin: bin}
	inputs := []string{
		streamAcceptanceFixtureURL,
		"ytsearch:Fixture Artist - Fixture Title",
	}
	for _, input := range inputs {
		if _, err := runner.Search(context.Background(), input); err != nil {
			t.Fatalf("search %q: %v", input, err)
		}
	}

	gotInputs, err := os.ReadFile(capture)
	if err != nil {
		t.Fatalf("read captured inputs: %v", err)
	}
	if got, want := strings.Split(strings.TrimSpace(string(gotInputs)), "\n"), inputs; !slices.Equal(got, want) {
		t.Fatalf("yt-dlp inputs = %q, want %q", got, want)
	}
	searches, selection := runner.snapshot()
	if searches != len(inputs) {
		t.Fatalf("yt-dlp searches = %d, want %d", searches, len(inputs))
	}
	if selection.ID != streamAcceptanceFixtureID || selection.Duration != streamAcceptanceMinDuration {
		t.Fatalf("selection = %+v, want fixture id %q and duration %ds", selection, streamAcceptanceFixtureID, streamAcceptanceMinDuration)
	}
}

func skipIfNoStreamAcceptance(t *testing.T) string {
	t.Helper()
	if os.Getenv("QMIX_STREAM_ACCEPTANCE") != "1" {
		t.Skip("stream acceptance skipped: QMIX_STREAM_ACCEPTANCE not set to 1")
	}
	bin := stream.ConfigFromEnv().YtdlpBin
	if bin == "" {
		bin = "yt-dlp"
	}
	resolved, err := exec.LookPath(bin)
	if err != nil {
		t.Fatalf("stream acceptance requires configured yt-dlp: %v", err)
	}
	return resolved
}

func acceptanceRange(t *testing.T, client *http.Client, endpoint string, start int64) {
	t.Helper()
	const size int64 = 64 * 1024
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		t.Fatalf("create seek request: %v", err)
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, start+size-1))
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("seek at byte %d: %v", start, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("seek at byte %d returned %d, want 206", start, resp.StatusCode)
	}
	wantPrefix := fmt.Sprintf("bytes %d-%d/", start, start+size-1)
	if got := resp.Header.Get("Content-Range"); !strings.HasPrefix(got, wantPrefix) {
		t.Fatalf("seek at byte %d Content-Range = %q, want prefix %q", start, got, wantPrefix)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read seek at byte %d: %v", start, err)
	}
	if int64(len(body)) != size {
		t.Fatalf("seek at byte %d returned %d bytes, want %d", start, len(body), size)
	}
	t.Logf("seek passed: start=%d bytes=%d content_range=%s", start, len(body), resp.Header.Get("Content-Range"))
}

type acceptanceRead struct {
	n   int
	err error
}

func readWithin(body io.ReadCloser, buf []byte, timeout time.Duration) (int, error) {
	result := make(chan acceptanceRead, 1)
	go func() {
		n, err := body.Read(buf)
		result <- acceptanceRead{n: n, err: err}
	}()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case result := <-result:
		if result.n == 0 && result.err == nil {
			return 0, errors.New("audio body returned no data and no error")
		}
		return result.n, result.err
	case <-timer.C:
		_ = body.Close()
		return 0, fmt.Errorf("no playback progress for %s", timeout)
	}
}

func proportionalBytes(contentLength int64, elapsed, mediaDuration time.Duration) int64 {
	return int64(math.Ceil(float64(contentLength) * float64(elapsed) / float64(mediaDuration)))
}

func paceAcceptance(body io.ReadCloser, contentLength int64, mediaDuration, playbackDuration, interval, maxPause time.Duration) (int64, time.Duration, error) {
	started := time.Now()
	samples := int64(playbackDuration / interval)
	if samples <= 0 || playbackDuration%interval != 0 {
		return 0, 0, errors.New("playback duration must be a positive multiple of the pacing interval")
	}
	buf := make([]byte, streamAcceptanceChunk)
	var total int64
	for sample := int64(1); sample <= samples; sample++ {
		due := started.Add(time.Duration(sample) * interval)
		scheduledBytes := proportionalBytes(contentLength, time.Duration(sample)*interval, mediaDuration)
		for total < scheduledBytes {
			remaining := scheduledBytes - total
			readSize := int64(len(buf))
			if remaining < readSize {
				readSize = remaining
			}
			untilDue := time.Until(due)
			if untilDue <= 0 {
				return total, time.Since(started), fmt.Errorf("playback fell behind real time at %s: %d/%d bytes", time.Duration(sample)*interval, total, scheduledBytes)
			}
			readTimeout := maxPause
			if untilDue < readTimeout {
				readTimeout = untilDue
			}
			n, err := readWithin(body, buf[:readSize], readTimeout)
			total += int64(n)
			if err != nil && total < scheduledBytes {
				return total, time.Since(started), fmt.Errorf("playback interrupted at %d/%d bytes: %w", total, scheduledBytes, err)
			}
		}
		if wait := time.Until(due); wait > 0 {
			time.Sleep(wait)
		}
	}
	return total, time.Since(started), nil
}

func acceptancePlayback(t *testing.T, client *http.Client, endpoint string, mediaDuration time.Duration) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), streamAcceptanceTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		t.Fatalf("create playback request: %v", err)
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("start playback: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("playback returned %d, want 200", resp.StatusCode)
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Type"), "audio/") {
		t.Fatalf("playback Content-Type = %q, want audio/*", resp.Header.Get("Content-Type"))
	}
	if resp.ContentLength <= 0 {
		t.Fatalf("playback Content-Length = %d, want a known positive size", resp.ContentLength)
	}
	if mediaDuration < streamAcceptanceDuration {
		t.Fatalf("selected media duration = %s, want at least %s", mediaDuration, streamAcceptanceDuration)
	}

	targetBytes := proportionalBytes(resp.ContentLength, streamAcceptanceDuration, mediaDuration)
	total, elapsed, err := paceAcceptance(resp.Body, resp.ContentLength, mediaDuration, streamAcceptanceDuration, streamAcceptanceInterval, streamAcceptanceMaxPause)
	if err != nil {
		t.Fatalf("continuous playback failed after %s and %d/%d bytes: %v", elapsed, total, targetBytes, err)
	}
	if total < targetBytes {
		t.Fatalf("playback bytes = %d, want at least %d for %s of selected media", total, targetBytes, streamAcceptanceDuration)
	}
	t.Logf("continuous playback passed: elapsed=%s media_duration=%s content_length=%d target_bytes=%d bytes=%d max_pause=%s", elapsed, mediaDuration, resp.ContentLength, targetBytes, total, streamAcceptanceMaxPause)
}

type delayedReadCloser struct {
	delay time.Duration
	*bytes.Reader
}

func (r *delayedReadCloser) Read(p []byte) (int, error) {
	time.Sleep(r.delay)
	return r.Reader.Read(p)
}

func (r *delayedReadCloser) Close() error { return nil }

func TestPaceAcceptanceCompletesAtRealTime(t *testing.T) {
	body := &delayedReadCloser{delay: time.Millisecond, Reader: bytes.NewReader(make([]byte, 1000))}
	total, elapsed, err := paceAcceptance(body, 1000, 500*time.Millisecond, 100*time.Millisecond, 50*time.Millisecond, 100*time.Millisecond)
	if err != nil {
		t.Fatalf("pace acceptance: %v", err)
	}
	if total != 200 {
		t.Fatalf("bytes = %d, want 200", total)
	}
	if elapsed < 100*time.Millisecond || elapsed >= 150*time.Millisecond {
		t.Fatalf("elapsed = %s, want [100ms, 150ms)", elapsed)
	}
}

func TestPaceAcceptanceRejectsSlowerThanRealTime(t *testing.T) {
	body := &delayedReadCloser{delay: 75 * time.Millisecond, Reader: bytes.NewReader(make([]byte, 1000))}
	_, _, err := paceAcceptance(body, 1000, 500*time.Millisecond, 100*time.Millisecond, 50*time.Millisecond, 100*time.Millisecond)
	if err == nil || !strings.Contains(err.Error(), "playback interrupted") {
		t.Fatalf("error = %v, want real-time playback failure", err)
	}
}

func requireSearches(t *testing.T, runner *acceptanceYTDLPRunner, want int) {
	t.Helper()
	got, selection := runner.snapshot()
	if got != want {
		t.Fatalf("yt-dlp searches = %d, want %d", got, want)
	}
	t.Logf("yt-dlp searches=%d fixture_id=%s title=%q duration=%.0fs", got, selection.ID, selection.Title, selection.Duration)
}

// TestStreamProxyAcceptanceLive proves the M3 acceptance contract against a
// fixed long-form YouTube fixture through QMix's production stream backend and
// HTTP endpoint. It is intentionally gated and takes ten minutes when enabled.
func TestStreamProxyAcceptanceLive(t *testing.T) {
	bin := skipIfNoStreamAcceptance(t)
	t.Logf("acceptance started: utc=%s yt_dlp=%s duration=%s", time.Now().UTC().Format(time.RFC3339), bin, streamAcceptanceDuration)

	runner := &acceptanceYTDLPRunner{bin: bin}
	s, _ := newStreamServer(&stream.YTDLP{
		Runner:   runner,
		CacheTTL: 5 * time.Minute,
	})
	s.Resolver = stubResolver{meta: streamAcceptanceFixtureTrack()}
	mux := newTestMux(s)
	code, token := createRoom(t, mux)
	startCurrentTrack(t, mux, code, token, streamAcceptanceFixtureURL)

	ts := httptest.NewServer(mux)
	defer ts.Close()
	client := ts.Client()
	endpoint := ts.URL + "/rooms/" + code + "/current/stream"

	acceptanceRange(t, client, endpoint, 1*1024*1024)
	requireSearches(t, runner, 1)
	_, selection := runner.snapshot()
	acceptancePlayback(t, client, endpoint, time.Duration(selection.Duration*float64(time.Second)))
	requireSearches(t, runner, 1)

	// The first post-playback seek must force a fresh lookup after the
	// five-minute cache expires; the next seek must reuse that new URL.
	acceptanceRange(t, client, endpoint, 8*1024*1024)
	requireSearches(t, runner, 2)
	acceptanceRange(t, client, endpoint, 4*1024*1024)
	requireSearches(t, runner, 2)
}
