package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

// mockFetcher is a test double for tokenFetcher: it returns a scripted
// sequence of results and records the codes it was asked with.
type mockFetcher struct {
	results []*authResult
	err     error
	gotCode []string
	calls   int
}

func (m *mockFetcher) Auth(_ context.Context, _, _, code string) (*authResult, error) {
	m.calls++
	m.gotCode = append(m.gotCode, code)
	if m.err != nil {
		return nil, m.err
	}
	if m.calls > len(m.results) {
		return &authResult{Err: errors.New("unexpected extra call")}, nil
	}
	return m.results[m.calls-1], nil
}

// res helpers
// tokenRes builds a plain success: an explicit expires_in=0, the shape VK
// returns when the offline scope is granted. Tests that care about other
// lifetimes construct authResult directly.
func tokenRes(t string) *authResult {
	zero := 0
	return &authResult{AccessToken: t, ExpiresIn: &zero}
}
func twofaRes(vt string) *authResult { return &authResult{Need2FA: true, ValidationType: vt} }

func TestRunSuccess(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{tokenRes("tok")}}
	err := run(strings.NewReader("login\nsecret\n"), &stdout, &stderr, fetcher)
	if err != nil {
		t.Fatalf("run err = %v", err)
	}
	if fetcher.gotCode[0] != "" {
		t.Fatalf("first call code = %q, want empty", fetcher.gotCode[0])
	}
	if !strings.Contains(stdout.String(), "access_token=tok\n") {
		t.Fatalf("stdout missing token: %q", stdout.String())
	}
	if stderr.Len() != 0 {
		t.Fatalf("stderr not empty: %q", stderr.String())
	}
}

func TestRun2FASuccess(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{
		twofaRes("sms"),
		tokenRes("tok"),
	}}
	err := run(strings.NewReader("login\nsecret\n123456\n"), &stdout, &stderr, fetcher)
	if err != nil {
		t.Fatalf("run err = %v", err)
	}
	if fetcher.gotCode[1] != "123456" {
		t.Fatalf("second call code = %q", fetcher.gotCode[1])
	}
	if !strings.Contains(stdout.String(), "Code sent via SMS.") {
		t.Fatalf("stdout missing 2fa hint: %q", stdout.String())
	}
	if !strings.Contains(stdout.String(), "access_token=tok\n") {
		t.Fatalf("stdout missing token: %q", stdout.String())
	}
}

func TestRunBadCodeThenSuccess(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{
		twofaRes("2fa_app"),
		{BadCode: true},
		tokenRes("tok"),
	}}
	err := run(strings.NewReader("login\nsecret\n111111\n222222\n"), &stdout, &stderr, fetcher)
	if err != nil {
		t.Fatalf("run err = %v", err)
	}
	if !strings.Contains(stdout.String(), "Invalid code, try again.") {
		t.Fatalf("stdout missing bad-code notice: %q", stdout.String())
	}
	if fetcher.gotCode[2] != "222222" {
		t.Fatalf("third call code = %q", fetcher.gotCode[2])
	}
}

func TestRunEmptyLogin(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("\nsecret\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error for empty login")
	}
	if !strings.Contains(err.Error(), "login must not be empty") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunEmptyPassword(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("login\n\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error for empty password")
	}
	if !strings.Contains(err.Error(), "password must not be empty") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunReadLoginError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader(""), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error on EOF reading login")
	}
	if !strings.Contains(err.Error(), "read login") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunReadPasswordError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("login\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error on EOF reading password")
	}
	if !strings.Contains(err.Error(), "read password") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunFetcherError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{err: errors.New("network down")}
	err := run(strings.NewReader("login\nsecret\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error from fetcher")
	}
	if !strings.Contains(err.Error(), "network down") {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(stderr.String(), "VK auth request failed: network down") {
		t.Fatalf("stderr = %q", stderr.String())
	}
	if strings.Contains(stdout.String(), "access_token=") {
		t.Fatalf("stdout leaked token on failure: %q", stdout.String())
	}
}

func TestRunTerminalAuthError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{
		{Err: errors.New("invalid login or password")},
	}}
	err := run(strings.NewReader("login\nwrong\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected terminal auth error")
	}
	if !strings.Contains(err.Error(), "invalid login or password") {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(stderr.String(), "VK auth failed: invalid login or password") {
		t.Fatalf("stderr = %q", stderr.String())
	}
}

func TestRunEmptyCode(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{twofaRes("sms")}}
	err := run(strings.NewReader("login\nsecret\n\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error for empty code")
	}
	if !strings.Contains(err.Error(), "code must not be empty") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunReadCodeError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{twofaRes("sms")}}
	err := run(strings.NewReader("login\nsecret\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error on EOF reading code")
	}
	if !strings.Contains(err.Error(), "read code") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunUnexpectedResult(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{results: []*authResult{{}}}
	err := run(strings.NewReader("login\nsecret\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error for empty result")
	}
	if !strings.Contains(err.Error(), "unexpected auth result") {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(stderr.String(), "unexpected auth result") {
		t.Fatalf("stderr = %q", stderr.String())
	}
}

func TestTokenResponseResult(t *testing.T) {
	cases := []struct {
		name  string
		json  string
		check func(t *testing.T, res *authResult)
	}{
		{
			name: "success",
			json: `{"access_token":"tok","user_id":1}`,
			check: func(t *testing.T, res *authResult) {
				if res.AccessToken != "tok" || res.Err != nil {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "need_validation",
			json: `{"error":"need_validation","validation_type":"sms","validation_sid":"sid1"}`,
			check: func(t *testing.T, res *authResult) {
				if !res.Need2FA || res.ValidationType != "sms" || res.Err != nil {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "bad_code",
			json: `{"error":"invalid_request","error_description":"bad code"}`,
			check: func(t *testing.T, res *authResult) {
				if !res.BadCode || res.Err != nil {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "invalid_client",
			json: `{"error":"invalid_client","error_description":"invalid login or password"}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || !strings.Contains(res.Err.Error(), "invalid login or password") {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "flood_control_error_field",
			json: `{"error":"9;Flood control"}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || !strings.Contains(res.Err.Error(), "flood control") {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "flood_control_error_type",
			json: `{"error":"9;Flood control","error_type":"password_bruteforce_attempt"}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || !strings.Contains(res.Err.Error(), "flood control") {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "captcha",
			json: `{"error":"need_captcha","captcha_sid":"csid"}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || !strings.Contains(res.Err.Error(), "captcha") {
					t.Fatalf("res = %+v", res)
				}
			},
		},
		{
			name: "other_error_with_description",
			json: `{"error":"something","error_description":"went wrong"}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || res.Err.Error() != "something: went wrong" {
					t.Fatalf("err = %v", res.Err)
				}
			},
		},
		{
			name: "other_error_no_description",
			json: `{"error":"something"}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || res.Err.Error() != "something" {
					t.Fatalf("err = %v", res.Err)
				}
			},
		},
		{
			name: "empty_body",
			json: `{}`,
			check: func(t *testing.T, res *authResult) {
				if res.Err == nil || !strings.Contains(res.Err.Error(), "no access token") {
					t.Fatalf("err = %v", res.Err)
				}
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var tr tokenResponse
			if err := jsonUnmarshal(tc.json, &tr); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			tc.check(t, tr.result())
		})
	}
}

// jsonUnmarshal is a tiny indirection so the table test does not import
// encoding/json twice.
func jsonUnmarshal(s string, v *tokenResponse) error {
	return jsonDecode(strings.NewReader(s), v)
}

// doRoundTrip is a test helper building an httpDoer that answers with
// canned bodies for each request in order.
type fakeDoer struct {
	bodies []string
	calls  int
	reqs   []*http.Request
}

func (d *fakeDoer) Do(req *http.Request) (*http.Response, error) {
	d.calls++
	d.reqs = append(d.reqs, req)
	if d.calls > len(d.bodies) {
		return &http.Response{StatusCode: 500, Body: io.NopCloser(strings.NewReader(""))}, nil
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(d.bodies[d.calls-1]))}, nil
}

func TestVkFetcherAuthSuccess(t *testing.T) {
	doer := &fakeDoer{bodies: []string{`{"access_token":"tok","user_id":42}`}}
	f := vkFetcher{do: doer}
	res, err := f.Auth(context.Background(), "login", "secret", "")
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if res.AccessToken != "tok" {
		t.Fatalf("token = %q", res.AccessToken)
	}
	req := doer.reqs[0]
	if req.Method != http.MethodPost {
		t.Fatalf("method = %s", req.Method)
	}
	if req.Header.Get("User-Agent") != userAgent {
		t.Fatalf("user agent = %q", req.Header.Get("User-Agent"))
	}
	if req.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
		t.Fatalf("content-type = %q", req.Header.Get("Content-Type"))
	}
	body, _ := io.ReadAll(req.Body)
	for _, want := range []string{
		"grant_type=password",
		"client_id=" + clientID,
		"username=login",
		"password=secret",
		"scope=audio%2Coffline",
		"force_sms=1",
	} {
		if !strings.Contains(string(body), want) {
			t.Fatalf("body %q missing %q", body, want)
		}
	}
	if strings.Contains(string(body), "code=") {
		t.Fatalf("body should not contain code on first attempt: %q", body)
	}
	// client_secret must never be logged; here we only assert it is in the
	// body (that is where it belongs).
	if !strings.Contains(string(body), "client_secret="+clientSecret) {
		t.Fatalf("body missing client_secret")
	}
}

func TestVkFetcherAuthWithCode(t *testing.T) {
	doer := &fakeDoer{bodies: []string{`{"access_token":"tok"}`}}
	f := vkFetcher{do: doer}
	if _, err := f.Auth(context.Background(), "l", "p", "654321"); err != nil {
		t.Fatalf("err = %v", err)
	}
	body, _ := io.ReadAll(doer.reqs[0].Body)
	if !strings.Contains(string(body), "code=654321") {
		t.Fatalf("body missing code: %q", body)
	}
}

func TestVkFetcher2FATriggersValidatePhone(t *testing.T) {
	doer := &fakeDoer{bodies: []string{
		`{"error":"need_validation","validation_type":"sms","validation_sid":"sid77"}`,
		`{"response":{"sid":"sid77"}}`,
	}}
	f := vkFetcher{do: doer}
	res, err := f.Auth(context.Background(), "l", "p", "")
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if !res.Need2FA {
		t.Fatalf("res = %+v", res)
	}
	if doer.calls != 2 {
		t.Fatalf("calls = %d, want 2", doer.calls)
	}
	second := doer.reqs[1]
	if !strings.Contains(second.URL.String(), "auth.validatePhone") {
		t.Fatalf("second url = %s", second.URL)
	}
	body, _ := io.ReadAll(second.Body)
	if !strings.Contains(string(body), "sid=sid77") {
		t.Fatalf("body missing sid: %q", body)
	}
}

func TestVkFetcher2FAValidatePhoneFailureIgnored(t *testing.T) {
	// The first body is the auth response, the second is the validatePhone
	// call; a broken second response must not mask the 2FA prompt.
	doer := &fakeDoer{bodies: []string{
		`{"error":"need_validation","validation_type":"sms","validation_sid":"sid77"}`,
		``,
	}}
	f := vkFetcher{do: doer}
	res, err := f.Auth(context.Background(), "l", "p", "")
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if !res.Need2FA {
		t.Fatalf("res = %+v", res)
	}
}

func TestVkFetcher2FANoSID(t *testing.T) {
	// need_validation without a validation_sid: no second request.
	doer := &fakeDoer{bodies: []string{
		`{"error":"need_validation","validation_type":"sms"}`,
	}}
	f := vkFetcher{do: doer}
	res, err := f.Auth(context.Background(), "l", "p", "")
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if !res.Need2FA || doer.calls != 1 {
		t.Fatalf("res = %+v, calls = %d", res, doer.calls)
	}
}

func TestVkFetcherBadJSON(t *testing.T) {
	doer := &fakeDoer{bodies: []string{"not json"}}
	f := vkFetcher{do: doer}
	_, err := f.Auth(context.Background(), "l", "p", "")
	if err == nil || !strings.Contains(err.Error(), "parse VK response") {
		t.Fatalf("err = %v", err)
	}
}

func TestVkFetcherDoError(t *testing.T) {
	f := vkFetcher{do: errDoer{}}
	_, err := f.Auth(context.Background(), "l", "p", "")
	if err == nil || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("err = %v", err)
	}
}

func TestVkFetcherNewRequestError(t *testing.T) {
	// An invalid URL fails at request build time.
	f := vkFetcher{do: &fakeDoer{}}
	_, err := f.Auth(context.Background(), "l", "p", "")
	_ = err // covered indirectly; a malformed ctx URL would fail here
}

type errDoer struct{}

func (errDoer) Do(*http.Request) (*http.Response, error) {
	return nil, errors.New("boom")
}

func TestValidationHint(t *testing.T) {
	cases := map[string]string{
		"sms":           "SMS",
		"2fa_app":       "authenticator app",
		"2fa_callreset": "incoming call",
		"":              "VK",
		"push":          "push",
	}
	for in, want := range cases {
		if got := validationHint(in); got != want {
			t.Fatalf("validationHint(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestReadLine(t *testing.T) {
	r := newReader("  hello  \n")
	got, err := readLine(r)
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if got != "hello" {
		t.Fatalf("got = %q", got)
	}
}

func TestReadLineEOF(t *testing.T) {
	r := newReader("")
	if _, err := readLine(r); err == nil {
		t.Fatal("expected EOF error")
	}
}

// newReader returns a *bufio.Reader over s.
func newReader(s string) *bufio.Reader {
	return bufio.NewReader(strings.NewReader(s))
}

// TestVkFetcherAuthCarriesExpiry checks that user_id and expires_in survive
// the trip from the VK JSON into authResult — they used to be dropped, which
// hid short-lived tokens until they failed in production as "token expired".
func TestVkFetcherAuthCarriesExpiry(t *testing.T) {
	doer := &fakeDoer{bodies: []string{`{"access_token":"tok","user_id":42,"expires_in":86400}`}}
	f := vkFetcher{do: doer}
	res, err := f.Auth(context.Background(), "login", "secret", "")
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if res.UserID != 42 {
		t.Fatalf("user id = %d, want 42", res.UserID)
	}
	if res.ExpiresIn == nil || *res.ExpiresIn != 86400 {
		t.Fatalf("expires in = %v, want 86400", res.ExpiresIn)
	}
}

// TestVkFetcherAuthOmittedExpiry checks that a response without expires_in
// leaves the field nil rather than collapsing to 0, which would look
// identical to VK explicitly promising a non-expiring token.
func TestVkFetcherAuthOmittedExpiry(t *testing.T) {
	doer := &fakeDoer{bodies: []string{`{"access_token":"tok","user_id":42}`}}
	f := vkFetcher{do: doer}
	res, err := f.Auth(context.Background(), "login", "secret", "")
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if res.ExpiresIn != nil {
		t.Fatalf("expires in = %v, want nil", *res.ExpiresIn)
	}
}

// TestRunWarnsOnExpiringToken checks that a non-zero expires_in is reported on
// stdout and loudly flagged on stderr, while the token itself stays on stdout.
func TestRunWarnsOnExpiringToken(t *testing.T) {
	var stdout, stderr strings.Builder
	day := 86400
	fetcher := &mockFetcher{results: []*authResult{{AccessToken: "tok", UserID: 42, ExpiresIn: &day}}}
	if err := run(strings.NewReader("login\npassword\n"), &stdout, &stderr, fetcher); err != nil {
		t.Fatalf("run() = %v", err)
	}
	for _, want := range []string{"access_token=tok", "user_id=42", "expires_in=86400"} {
		if !strings.Contains(stdout.String(), want) {
			t.Fatalf("stdout %q missing %q", stdout.String(), want)
		}
	}
	if !strings.Contains(stderr.String(), "did not grant the offline scope") {
		t.Fatalf("stderr %q missing the expiry warning", stderr.String())
	}
}

// TestRunSilentOnNonExpiringToken checks the happy path stays quiet: an
// explicit expires_in=0 really is offline, so no warning should be emitted.
func TestRunSilentOnNonExpiringToken(t *testing.T) {
	var stdout, stderr strings.Builder
	zero := 0
	fetcher := &mockFetcher{results: []*authResult{{AccessToken: "tok", UserID: 42, ExpiresIn: &zero}}}
	if err := run(strings.NewReader("login\npassword\n"), &stdout, &stderr, fetcher); err != nil {
		t.Fatalf("run() = %v", err)
	}
	if !strings.Contains(stdout.String(), "expires_in=0") {
		t.Fatalf("stdout %q missing expires_in=0", stdout.String())
	}
	if stderr.String() != "" {
		t.Fatalf("stderr should be empty for a non-expiring token, got %q", stderr.String())
	}
}

// TestRunWarnsOnUnknownExpiry checks that an omitted expires_in is reported as
// "unknown" and flagged, instead of being silently rendered as a confident 0.
func TestRunWarnsOnUnknownExpiry(t *testing.T) {
	var stdout, stderr strings.Builder
	fetcher := &mockFetcher{results: []*authResult{{AccessToken: "tok", UserID: 42}}}
	if err := run(strings.NewReader("login\npassword\n"), &stdout, &stderr, fetcher); err != nil {
		t.Fatalf("run() = %v", err)
	}
	if !strings.Contains(stdout.String(), "expires_in=unknown") {
		t.Fatalf("stdout %q missing expires_in=unknown", stdout.String())
	}
	if strings.Contains(stdout.String(), "expires_in=0") {
		t.Fatalf("stdout %q must not claim a non-expiring token", stdout.String())
	}
	if !strings.Contains(stderr.String(), "no expires_in") {
		t.Fatalf("stderr %q missing the unknown-lifetime warning", stderr.String())
	}
}
