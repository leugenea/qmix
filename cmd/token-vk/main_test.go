package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/oklookat/vkmauth"
	"golang.org/x/oauth2"
)

// mockFetcher is a test double for tokenFetcher. It records the arguments and
// returns a canned token or error.
type mockFetcher struct {
	token *oauth2.Token
	err   error
	// onCodeWaiting, when set, is invoked by Fetch to exercise the callback.
	onCodeWaiting func(vkmauth.CodeSended) (vkmauth.GotCode, error)
	gotPhone      string
	gotPassword   string
}

func (m *mockFetcher) Fetch(_ context.Context, phone, password string, _ func(vkmauth.CodeSended) (vkmauth.GotCode, error)) (*oauth2.Token, error) {
	m.gotPhone = phone
	m.gotPassword = password
	if m.onCodeWaiting != nil {
		if _, err := m.onCodeWaiting(vkmauth.CodeSended{Current: vkmauth.AuthSupportedWayPush, Resend: vkmauth.AuthSupportedWaySms}); err != nil {
			return nil, err
		}
	}
	return m.token, m.err
}

func TestRunSuccess(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{
		token: &oauth2.Token{AccessToken: "acc", RefreshToken: "ref"},
	}
	err := run(strings.NewReader("79000000000\nsecret\n"), &stdout, &stderr, fetcher)
	if err != nil {
		t.Fatalf("run err = %v", err)
	}
	if fetcher.gotPhone != "79000000000" {
		t.Fatalf("phone = %q", fetcher.gotPhone)
	}
	if fetcher.gotPassword != "secret" {
		t.Fatalf("password = %q", fetcher.gotPassword)
	}
	if !strings.Contains(stdout.String(), "access_token=acc\n") {
		t.Fatalf("stdout missing access_token: %q", stdout.String())
	}
	if !strings.Contains(stdout.String(), "refresh_token=ref\n") {
		t.Fatalf("stdout missing refresh_token: %q", stdout.String())
	}
	if stderr.Len() != 0 {
		t.Fatalf("stderr not empty: %q", stderr.String())
	}
}

func TestRunEmptyPhone(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("\nsecret\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error for empty phone")
	}
	if !strings.Contains(err.Error(), "phone must not be empty") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunEmptyPassword(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("79000000000\n\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error for empty password")
	}
	if !strings.Contains(err.Error(), "password must not be empty") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunReadPhoneError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader(""), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error on EOF reading phone")
	}
	if !strings.Contains(err.Error(), "read phone") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunReadPasswordError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("79000000000\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error on EOF reading password")
	}
	if !strings.Contains(err.Error(), "read password") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunFetcherError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{err: errors.New("invalid password")}
	err := run(strings.NewReader("79000000000\nwrong\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error from fetcher")
	}
	if !strings.Contains(err.Error(), "invalid password") {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(stderr.String(), "VK auth failed: invalid password") {
		t.Fatalf("stderr = %q", stderr.String())
	}
	if strings.Contains(stdout.String(), "access_token=") {
		t.Fatalf("stdout leaked token on failure: %q", stdout.String())
	}
}

func TestRunEmptyAccessToken(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{token: &oauth2.Token{RefreshToken: "ref"}}
	err := run(strings.NewReader("79000000000\nsecret\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error for empty access token")
	}
	if !strings.Contains(err.Error(), "empty access token") {
		t.Fatalf("err = %v", err)
	}
	if strings.Contains(stdout.String(), "access_token=") {
		t.Fatalf("stdout leaked token: %q", stdout.String())
	}
}

func TestRunNilToken(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := run(strings.NewReader("79000000000\nsecret\n"), &stdout, &stderr, &mockFetcher{})
	if err == nil {
		t.Fatal("expected error for nil token")
	}
	if !strings.Contains(err.Error(), "empty access token") {
		t.Fatalf("err = %v", err)
	}
}

func TestRunCallbackError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	fetcher := &mockFetcher{
		onCodeWaiting: func(vkmauth.CodeSended) (vkmauth.GotCode, error) {
			return vkmauth.GotCode{}, errors.New("cancelled")
		},
	}
	err := run(strings.NewReader("79000000000\nsecret\n"), &stdout, &stderr, fetcher)
	if err == nil {
		t.Fatal("expected error from callback")
	}
	if !strings.Contains(err.Error(), "cancelled") {
		t.Fatalf("err = %v", err)
	}
}

func TestCodeHandlerEnterCode(t *testing.T) {
	var out bytes.Buffer
	h := &codeHandler{in: newReader("123456\n"), out: &out}
	got, err := h.onCodeWaiting(vkmauth.CodeSended{Current: vkmauth.AuthSupportedWayPush, Resend: vkmauth.AuthSupportedWaySms})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if got.Code != "123456" || got.Resend {
		t.Fatalf("got = %+v, want code 123456", got)
	}
	if !strings.Contains(out.String(), "Code sent via push") {
		t.Fatalf("out = %q", out.String())
	}
	if !strings.Contains(out.String(), "resend via sms") {
		t.Fatalf("out = %q", out.String())
	}
}

func TestCodeHandlerResend(t *testing.T) {
	var out bytes.Buffer
	h := &codeHandler{in: newReader("\n"), out: &out}
	got, err := h.onCodeWaiting(vkmauth.CodeSended{Current: vkmauth.AuthSupportedWayPush, Resend: vkmauth.AuthSupportedWaySms})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if !got.Resend || got.Code != "" {
		t.Fatalf("got = %+v, want resend", got)
	}
}

func TestCodeHandlerNoResendChannel(t *testing.T) {
	var out bytes.Buffer
	h := &codeHandler{in: newReader("654321\n"), out: &out}
	got, err := h.onCodeWaiting(vkmauth.CodeSended{Current: vkmauth.AuthSupportedWayPush})
	if err != nil {
		t.Fatalf("err = %v", err)
	}
	if got.Code != "654321" || got.Resend {
		t.Fatalf("got = %+v, want code 654321", got)
	}
	if strings.Contains(out.String(), "resend") {
		t.Fatalf("out should not offer resend: %q", out.String())
	}
}

func TestCodeHandlerReadError(t *testing.T) {
	var out bytes.Buffer
	h := &codeHandler{in: newReader(""), out: &out}
	_, err := h.onCodeWaiting(vkmauth.CodeSended{Current: vkmauth.AuthSupportedWayPush})
	if err == nil {
		t.Fatal("expected error on EOF")
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

func TestVkmauthFetcherDelegates(t *testing.T) {
	// The real fetcher hits the network; with an empty phone it must fail
	// fast with a non-nil error, which exercises the delegation path without
	// any live credentials.
	f := vkmauthFetcher{}
	_, err := f.Fetch(context.Background(), "", "", func(vkmauth.CodeSended) (vkmauth.GotCode, error) {
		return vkmauth.GotCode{}, nil
	})
	if err == nil {
		t.Fatal("expected error from vkmauth.New with empty phone")
	}
}

// newReader returns a *bufio.Reader over s.
func newReader(s string) *bufio.Reader {
	return bufio.NewReader(strings.NewReader(s))
}
