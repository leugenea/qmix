// Command token-ym is a small CLI that obtains a Yandex Music OAuth token
// via the device code flow and prints it to stdout. It uses the public
// OAuth device endpoints (oauth.yandex.ru/device/code + /token) with the
// credentials of the Yandex Music desktop client — the same client pair
// embedded in the sources of synchro (github.com/oklookat/synchro,
// remote/yandexmusic) — so the resulting token works with the
// Yandex Music API the same way synchro's tokens do.
//
// The utility stores nothing on disk and logs nothing: the verification
// URL + user_code, then access_token and refresh_token are printed in a
// simple machine-readable form suitable for `gh secret set` or an env
// file.
//
// yandexauth/v3 was not imported: its OAuth endpoints are unexported
// package constants and its polling loop has no injectable transport or
// sleep, so it cannot be tested without hitting the real network. The
// device flow implemented here mirrors yandexauth/v3 (oklookat) exactly.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/leugenea/qmix/internal/buildinfo"
)

// The Yandex Music desktop client, same pair as embedded in synchro's
// remote/yandexmusic/main.go (verified 2026-09-04). These are public
// application credentials, not user secrets.
const (
	clientID     = "23cabbbdc6cd418abb4b39c32c41195d"
	clientSecret = "53bc75238f0c4d08a118e51fe9203300"

	codeEndpoint  = "https://oauth.yandex.ru/device/code"
	tokenEndpoint = "https://oauth.yandex.ru/token"
)

// httpDoer abstracts *http.Client so tests can feed fixture responses.
type httpDoer interface {
	Do(req *http.Request) (*http.Response, error)
}

// sleeper abstracts time.Sleep so tests can verify the polling interval
// without really waiting.
type sleeper func(d time.Duration)

// deviceFlow runs the OAuth device code flow: it is given the transport,
// both endpoint URLs and the sleep function, so tests can inject fixtures
// and a fake clock.
type deviceFlow struct {
	do          httpDoer
	codeURL     string
	tokenURL    string
	sleep       sleeper
	pollTimeout time.Duration // hard limit on total polling time; <= 0 = default
}

// flowConfig ties run() and the device flow to the outside world; run()
// reads it instead of globals so tests can point everything at fixtures.
type flowConfig struct {
	// deviceID identifies the token's device in the Yandex UI (6..50 printable ASCII).
	deviceID string
	// deviceName is shown to the user in the Yandex UI (up to 100 chars).
	deviceName string
}

// confirmationCodes mirrors the JSON returned by /device/code.
type confirmationCodes struct {
	DeviceCode      string `json:"device_code"`
	UserCode        string `json:"user_code"`
	VerificationURL string `json:"verification_url"`
	// Interval is the minimum polling interval in seconds.
	Interval int64 `json:"interval"`
	// ExpiresIn is the lifetime of the code pair in seconds (typically 600).
	ExpiresIn int64 `json:"expires_in"`
}

// tokenResponse mirrors the JSON returned by /token: success and error
// shapes share one object.
type tokenResponse struct {
	AccessToken      string `json:"access_token"`
	RefreshToken     string `json:"refresh_token"`
	TokenType        string `json:"token_type"`
	ExpiresIn        int64  `json:"expires_in"`
	Error            string `json:"error"`
	ErrorDescription string `json:"error_description"`
}

// postForm sends a form-encoded POST and returns the response body.
func (f deviceFlow) postForm(ctx context.Context, endpoint string, vals url.Values) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(vals.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := f.do.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// requestCodes performs step 1: POST /device/code.
func (f deviceFlow) requestCodes(ctx context.Context, cfg flowConfig) (*confirmationCodes, error) {
	vals := url.Values{
		"client_id":   {clientID},
		"device_id":   {cfg.deviceID},
		"device_name": {cfg.deviceName},
	}
	body, err := f.postForm(ctx, f.codeURL, vals)
	if err != nil {
		return nil, err
	}
	var codes confirmationCodes
	if err := json.Unmarshal(body, &codes); err != nil {
		return nil, fmt.Errorf("parse code response: %w", err)
	}
	if codes.DeviceCode == "" || codes.UserCode == "" || codes.VerificationURL == "" {
		return nil, errors.New("no device_code/user_code in Yandex response")
	}
	return &codes, nil
}

// pollTokens performs step 2: poll /token with grant_type=device_code until
// the user confirms (authorization_pending), or the flow fails
// (expired_token, access_denied, ...).
func (f deviceFlow) pollTokens(ctx context.Context, codes *confirmationCodes) (*tokenResponse, error) {
	vals := url.Values{
		"grant_type":    {"device_code"},
		"code":          {codes.DeviceCode},
		"client_id":     {clientID},
		"client_secret": {clientSecret},
	}
	// Yandex requires at least `interval` seconds between requests.
	sleepFor := time.Duration(codes.Interval) * time.Second
	if sleepFor <= 0 {
		sleepFor = 5 * time.Second
	}
	// The codes live ~10 minutes; a hard timeout guards against a server
	// that keeps answering authorization_pending forever.
	deadline := time.Now().Add(f.pollTimeout)
	if f.pollTimeout <= 0 {
		deadline = time.Now().Add(time.Duration(codes.ExpiresIn) * time.Second)
	}
	for {
		body, err := f.postForm(ctx, f.tokenURL, vals)
		if err != nil {
			return nil, err
		}
		var tr tokenResponse
		if err := json.Unmarshal(body, &tr); err != nil {
			return nil, fmt.Errorf("parse token response: %w", err)
		}
		if tr.AccessToken != "" {
			return &tr, nil
		}
		switch tr.Error {
		case "authorization_pending":
			// The user has not confirmed yet: wait the polling interval
			// and try again, unless the codes have expired.
			if time.Now().After(deadline) {
				return nil, errors.New("authorization pending: code expired or polling timeout reached")
			}
			f.sleep(sleepFor)
			continue
		case "expired_token", "invalid_grant":
			return nil, errors.New("confirmation code expired: run the utility again")
		case "access_denied":
			return nil, errors.New("access denied: the code was declined on the confirmation page")
		case "invalid_client":
			return nil, errors.New("invalid client credentials")
		case "slow_down":
			// The server asks to slow down; wait one extra interval.
			f.sleep(sleepFor)
			continue
		}
		if tr.Error == "" {
			return nil, errors.New("no access token in Yandex response")
		}
		return nil, fmt.Errorf("Yandex auth error: %s", tr.Error)
	}
}

// run executes the device flow. It returns an error (with a human-readable
// message already written to stderr) on any failure, so the caller can
// exit non-zero. Nothing is written to stderr on success.
func run(ctx context.Context, stdout io.Writer, cfg flowConfig, f deviceFlow) error {
	codes, err := f.requestCodes(ctx, cfg)
	if err != nil {
		return fmt.Errorf("request confirmation codes: %w", err)
	}
	fmt.Fprintf(stdout, "Open %s and enter code:\n%s\n", codes.VerificationURL, codes.UserCode)
	fmt.Fprintln(stdout, "Waiting for confirmation...")
	tok, err := f.pollTokens(ctx, codes)
	if err != nil {
		return err
	}
	fmt.Fprintf(stdout, "access_token=%s\n", tok.AccessToken)
	fmt.Fprintf(stdout, "refresh_token=%s\n", tok.RefreshToken)
	return nil
}

// defaultFlow builds the production device flow: the default HTTP client,
// the real Yandex endpoints and time.Sleep as the polling ticker. It is a
// function (not a literal in main) so a test can verify that every field
// is set: sleep was once forgotten here and the very first
// authorization_pending response panicked on the nil sleeper (#33).
func defaultFlow() deviceFlow {
	return deviceFlow{
		do:       http.DefaultClient,
		codeURL:  codeEndpoint,
		tokenURL: tokenEndpoint,
		sleep:    time.Sleep,
	}
}

func execute(args []string, ctx context.Context, out io.Writer, cfg flowConfig, flow deviceFlow) error {
	return buildinfo.Run(args, out, func() error {
		return run(ctx, out, cfg, flow)
	})
}

func main() {
	cfg := flowConfig{
		// A fixed device identity, same idea as synchro's stored DeviceID:
		// lets the user revoke this particular token in Yandex ID settings
		// and is accepted by the device endpoints (printable ASCII, 6..50).
		deviceID:   "qmix-token-ym",
		deviceName: "qmix token-ym",
	}
	if err := execute(os.Args[1:], context.Background(), os.Stdout, cfg, defaultFlow()); err != nil {
		fmt.Fprintf(os.Stderr, "Yandex auth failed: %v\n", err)
		os.Exit(1)
	}
}
