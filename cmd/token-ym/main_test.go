package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fakeDoer answers with canned (statusCode, body) pairs in order and
// records every request.
type fakeDoer struct {
	resps  []resp
	calls  int
	reqs   []*http.Request
	bodies []string
}

type resp struct {
	status int
	body   string
}

func ok(body string) resp { return resp{200, body} }

func (d *fakeDoer) Do(req *http.Request) (*http.Response, error) {
	d.calls++
	d.reqs = append(d.reqs, req)
	body, _ := io.ReadAll(req.Body)
	d.bodies = append(d.bodies, string(body))
	if d.calls > len(d.resps) {
		return &http.Response{StatusCode: 500, Body: io.NopCloser(strings.NewReader(""))}, nil
	}
	r := d.resps[d.calls-1]
	return &http.Response{StatusCode: r.status, Body: io.NopCloser(strings.NewReader(r.body))}, nil
}

// fakeSleep records sleeps instead of waiting.
type fakeSleep struct {
	got []time.Duration
}

func (s *fakeSleep) sleep(d time.Duration) { s.got = append(s.got, d) }

// newFlow builds a deviceFlow over fixtures with a 0s poll timeout so
// tests never wait.
func newFlow(d httpDoer, s *fakeSleep) deviceFlow {
	return deviceFlow{do: d, codeURL: "https://code.example", tokenURL: "https://token.example", sleep: s.sleep, pollTimeout: time.Hour}
}

func codesJSON(interval int64) string {
	return fmt.Sprintf(`{"device_code":"devcode","user_code":"ABCD-EFGH","verification_url":"https://ya.cc/page","interval":%d,"expires_in":600}`, interval)
}

func testCfg() flowConfig { return flowConfig{deviceID: "qmix-token-ym", deviceName: "qmix token-ym"} }

func TestRunSuccess(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"access_token":"at123","refresh_token":"rt456","token_type":"bearer","expires_in":31536000}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	out := stdout.String()
	for _, want := range []string{
		"Open https://ya.cc/page and enter code:",
		"ABCD-EFGH",
		"Waiting for confirmation...",
		"access_token=at123",
		"refresh_token=rt456",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("stdout missing %q: %q", want, out)
		}
	}
}

func TestRunSuccessPendingThenToken(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"authorization_pending"}`),
		ok(`{"error":"authorization_pending"}`),
		ok(`{"access_token":"at","refresh_token":"rt"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if len(sl.got) != 2 {
		t.Fatalf("sleeps = %v, want 2", sl.got)
	}
	for _, d := range sl.got {
		if d != 5*time.Second {
			t.Fatalf("sleep = %v, want 5s", d)
		}
	}
	if !strings.Contains(stdout.String(), "access_token=at") {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

func TestRunExpiredToken(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"expired_token","error_description":"code is expired"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("err = %v", err)
	}
	if len(sl.got) != 0 {
		t.Fatalf("unexpected sleeps: %v", sl.got)
	}
	if strings.Contains(stdout.String(), "access_token=") {
		t.Fatalf("stdout leaked token on failure: %q", stdout.String())
	}
}

func TestRunAccessDenied(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"access_denied"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "denied") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunInvalidGrant(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"invalid_grant"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunInvalidClient(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"invalid_client"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "invalid client") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunSlowDownThenToken(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"slow_down"}`),
		ok(`{"access_token":"at","refresh_token":"rt"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if len(sl.got) != 1 || sl.got[0] != 5*time.Second {
		t.Fatalf("sleeps = %v", sl.got)
	}
}

func TestRunOtherError(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error":"bad_verification_code"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "bad_verification_code") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunErrorWithoutCode(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(codesJSON(5)),
		ok(`{"error_description":"weird"}`),
	}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "no access token") {
		t.Fatalf("err = %v", err)
	}
}

func TestPollingTimeout(t *testing.T) {
	// The server keeps answering authorization_pending; the pollTimeout
	// deadline stops the loop.
	doer := &fakeDoer{resps: []resp{ok(`{"error":"authorization_pending"}`)}}
	sl := &fakeSleep{}
	f := deviceFlow{do: doer, codeURL: "https://code.example", tokenURL: "https://token.example", sleep: sl.sleep, pollTimeout: -1}
	codes := &confirmationCodes{DeviceCode: "dc", Interval: 1, ExpiresIn: 0}
	_, err := f.pollTokens(context.Background(), codes)
	if err == nil || !strings.Contains(err.Error(), "polling timeout") {
		t.Fatalf("err = %v", err)
	}
	// With ExpiresIn=0 the deadline is now: the very first pending
	// response terminates the loop without sleeping.
	if len(sl.got) != 0 {
		t.Fatalf("sleeps = %v", sl.got)
	}
	if doer.calls != 1 {
		t.Fatalf("calls = %d, want 1", doer.calls)
	}
}

func TestPollTokensDoError(t *testing.T) {
	f := deviceFlow{do: errDoer{}, codeURL: "https://code.example", tokenURL: "https://token.example", sleep: func(time.Duration) {}}
	codes := &confirmationCodes{DeviceCode: "dc", Interval: 1}
	_, err := f.pollTokens(context.Background(), codes)
	if err == nil || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("err = %v", err)
	}
}

type errDoer struct{}

func (errDoer) Do(*http.Request) (*http.Response, error) { return nil, errors.New("boom") }

func TestPollTokensBadJSON(t *testing.T) {
	doer := &fakeDoer{resps: []resp{
		ok(`not json`),
	}}
	f := deviceFlow{do: doer, codeURL: "https://code.example", tokenURL: "https://token.example", sleep: func(time.Duration) {}}
	codes := &confirmationCodes{DeviceCode: "dc", Interval: 1}
	_, err := f.pollTokens(context.Background(), codes)
	if err == nil || !strings.Contains(err.Error(), "parse token response") {
		t.Fatalf("err = %v", err)
	}
}

func TestRequestCodesSuccess(t *testing.T) {
	doer := &fakeDoer{resps: []resp{ok(codesJSON(10))}}
	sl := &fakeSleep{}
	f := newFlow(doer, sl)
	codes, err := f.requestCodes(context.Background(), testCfg())
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if codes.DeviceCode != "devcode" || codes.UserCode != "ABCD-EFGH" || codes.VerificationURL != "https://ya.cc/page" {
		t.Fatalf("codes = %+v", codes)
	}
	req := doer.reqs[0]
	if req.Method != http.MethodPost || req.URL.String() != "https://code.example" {
		t.Fatalf("req = %s %s", req.Method, req.URL)
	}
	if req.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
		t.Fatalf("content-type = %q", req.Header.Get("Content-Type"))
	}
	vals, _ := url.ParseQuery(doer.bodies[0])
	if vals.Get("client_id") != clientID {
		t.Fatalf("client_id = %q", vals.Get("client_id"))
	}
	if vals.Get("device_id") != "qmix-token-ym" || vals.Get("device_name") != "qmix token-ym" {
		t.Fatalf("device params = %v", vals)
	}
}

func TestRequestCodesBadJSON(t *testing.T) {
	doer := &fakeDoer{resps: []resp{ok("nope")}}
	sl := &fakeSleep{}
	_, err := newFlow(doer, sl).requestCodes(context.Background(), testCfg())
	if err == nil || !strings.Contains(err.Error(), "parse code response") {
		t.Fatalf("err = %v", err)
	}
}

func TestRequestCodesEmptyFields(t *testing.T) {
	cases := []struct{ name, json string }{
		{"no_device_code", `{"user_code":"u","verification_url":"v"}`},
		{"no_user_code", `{"device_code":"d","verification_url":"v"}`},
		{"no_verification_url", `{"device_code":"d","user_code":"u"}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			doer := &fakeDoer{resps: []resp{ok(tc.json)}}
			sl := &fakeSleep{}
			_, err := newFlow(doer, sl).requestCodes(context.Background(), testCfg())
			if err == nil || !strings.Contains(err.Error(), "no device_code") {
				t.Fatalf("err = %v", err)
			}
		})
	}
}

func TestRequestCodesDoError(t *testing.T) {
	f := deviceFlow{do: errDoer{}, codeURL: "https://code.example"}
	_, err := f.requestCodes(context.Background(), testCfg())
	if err == nil || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunRequestCodesFailure(t *testing.T) {
	doer := &fakeDoer{resps: []resp{ok(`{"device_code":"d"}`)}}
	sl := &fakeSleep{}
	var stdout strings.Builder
	err := run(context.Background(), &stdout, testCfg(), newFlow(doer, sl))
	if err == nil || !strings.Contains(err.Error(), "request confirmation codes") {
		t.Fatalf("err = %v", err)
	}
}

func TestPollTokensIntervalFallback(t *testing.T) {
	// interval=0 falls back to 5s.
	doer := &fakeDoer{resps: []resp{
		ok(`{"error":"authorization_pending"}`),
		ok(`{"access_token":"at","refresh_token":"rt"}`),
	}}
	sl := &fakeSleep{}
	f := deviceFlow{do: doer, codeURL: "https://code.example", tokenURL: "https://token.example", sleep: sl.sleep, pollTimeout: time.Hour}
	codes := &confirmationCodes{DeviceCode: "dc", Interval: 0, ExpiresIn: 600}
	tok, err := f.pollTokens(context.Background(), codes)
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if len(sl.got) != 1 || sl.got[0] != 5*time.Second {
		t.Fatalf("sleeps = %v", sl.got)
	}
	if tok.AccessToken != "at" {
		t.Fatalf("token = %+v", tok)
	}
}

func TestPostFormNewRequestError(t *testing.T) {
	f := deviceFlow{do: errDoer{}, codeURL: "://bad url"}
	_, err := f.postForm(context.Background(), f.codeURL, url.Values{})
	if err == nil {
		t.Fatal("expected request build error")
	}
}

// TestDefaultFlowSetsSleep is the regression for #33: the deviceFlow
// literal in main() left sleep unset, so the very first
// authorization_pending response panicked with a nil pointer dereference.
// The production constructor must fill every field.
func TestDefaultFlowSetsSleep(t *testing.T) {
	f := defaultFlow()
	if f.sleep == nil {
		t.Fatal("defaultFlow: sleep is nil, pollTokens would panic on authorization_pending")
	}
	if f.do == nil {
		t.Fatal("defaultFlow: do is nil")
	}
	if f.codeURL != codeEndpoint || f.tokenURL != tokenEndpoint {
		t.Fatalf("defaultFlow: urls = %q / %q", f.codeURL, f.tokenURL)
	}
}
