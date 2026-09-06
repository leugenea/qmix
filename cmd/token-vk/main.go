// Command token-vk is a small interactive CLI that obtains a VK API access
// token with the audio scope and prints it to stdout. It uses the
// oauth.vk.com password grant while impersonating the official VK for
// Android app — the same flow used by actively maintained VK music clients —
// so the resulting token works with api.vk.com methods such as
// audio.getById. It stores nothing on disk: the token
// is printed in a simple machine-readable form suitable for `gh secret set`
// or an env file.
//
// Credentials (login, password, 2FA code) are read from stdin, never from
// argv, so the password does not end up in shell history. Tokens are never
// logged.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
)

// The official "VK for Android" standalone client. The password grant only
// works for trusted first-party apps, and this is the client pair used by
// actively maintained VK music clients (endpoints verified live 2026-09).
const (
	clientID     = "2274003"
	clientSecret = "hHbZxrka2uZ6jB1inYsH"
	userAgent    = "VKAndroidApp/4.13.1-1206 (Android 4.4.3; SDK 19; armeabi; ; ru)"

	oauthTokenURL    = "https://oauth.vk.com/token"
	validatePhoneURL = "https://api.vk.com/method/auth.validatePhone"
	apiVersion       = "5.131"

	// offline makes the token non-expiring; audio is the point of the tool.
	scope = "audio,offline"
)

// tokenFetcher abstracts the VK OAuth password-grant flow so tests can
// inject a mock and never hit the real VK API.
type tokenFetcher interface {
	// Auth performs one auth attempt. An empty code means "no code yet";
	// after a 2FA prompt the code from stdin is passed back in.
	Auth(ctx context.Context, login, password, code string) (*authResult, error)
}

// authResult is the outcome of one auth attempt: either a token, or a
// request for a 2FA/confirmation code, or a terminal error.
type authResult struct {
	// AccessToken is set when auth succeeded.
	AccessToken string
	// Need2FA: VK requires a confirmation code.
	Need2FA bool
	// ValidationType tells where the code comes from ("sms", "2fa_app", ...).
	ValidationType string
	// BadCode: the entered code was wrong; ask for another one.
	BadCode bool
	// UserID is the VK id the token belongs to (0 when unknown).
	UserID int
	// ExpiresIn is the token lifetime in seconds; 0 means non-expiring,
	// i.e. the offline scope was actually granted.
	ExpiresIn int
	// Err is a terminal error (bad credentials, flood control, ...).
	Err error
}

// tokenResponse mirrors the JSON returned by oauth.vk.com/token: the success
// and error shapes share one object.
type tokenResponse struct {
	AccessToken      string `json:"access_token"`
	UserID           int    `json:"user_id"`
	ExpiresIn        int    `json:"expires_in"`
	Error            string `json:"error"`
	ErrorType        string `json:"error_type"`
	ErrorDescription string `json:"error_description"`
	ValidationType   string `json:"validation_type"`
	ValidationSID    string `json:"validation_sid"`
	CaptchaSID       string `json:"captcha_sid"`
}

// result maps a raw token response onto an authResult.
func (tr tokenResponse) result() *authResult {
	if tr.AccessToken != "" {
		return &authResult{AccessToken: tr.AccessToken, UserID: tr.UserID, ExpiresIn: tr.ExpiresIn}
	}
	switch {
	case tr.Error == "need_validation":
		return &authResult{Need2FA: true, ValidationType: tr.ValidationType}
	case tr.Error == "invalid_request":
		// A wrong confirmation code; ask the user for another one.
		return &authResult{BadCode: true}
	case tr.Error == "invalid_client":
		return &authResult{Err: errors.New("invalid login or password")}
	case tr.Error == "9;Flood control" || tr.ErrorType == "password_bruteforce_attempt":
		return &authResult{Err: errors.New("flood control: too many login attempts, try again later")}
	case tr.Error == "need_captcha":
		return &authResult{Err: fmt.Errorf("captcha required (sid %s); try again later or from another network", tr.CaptchaSID)}
	}
	msg := tr.Error
	if tr.ErrorDescription != "" {
		if msg != "" {
			msg += ": "
		}
		msg += tr.ErrorDescription
	}
	if msg == "" {
		msg = "no access token in VK response"
	}
	return &authResult{Err: errors.New(msg)}
}

// httpDoer abstracts *http.Client so tests can feed fixture responses.
type httpDoer interface {
	Do(req *http.Request) (*http.Response, error)
}

// vkFetcher is the production implementation backed by the oauth.vk.com
// password grant, impersonating the VK for Android client.
type vkFetcher struct{ do httpDoer }

// Auth performs one password-grant attempt. On need_validation it also asks
// VK to deliver the confirmation code (best effort, the way maintained VK
// clients do).
func (f vkFetcher) Auth(ctx context.Context, login, password, code string) (*authResult, error) {
	vals := url.Values{
		"grant_type":    {"password"},
		"client_id":     {clientID},
		"client_secret": {clientSecret},
		"username":      {login},
		"password":      {password},
		"scope":         {scope},
		"2fa_supported": {"1"},
		"force_sms":     {"1"},
		"v":             {apiVersion},
	}
	if code != "" {
		vals.Set("code", code)
	}
	body, err := f.post(ctx, oauthTokenURL, vals)
	if err != nil {
		return nil, err
	}
	var tr tokenResponse
	if err := json.Unmarshal(body, &tr); err != nil {
		return nil, fmt.Errorf("parse VK response: %w", err)
	}
	res := tr.result()
	if res.Need2FA && tr.ValidationSID != "" {
		// Best effort: this call asks VK to actually deliver the code. A
		// failure is not fatal — the code may arrive anyway (e.g. push).
		_, _ = f.post(ctx, validatePhoneURL, url.Values{
			"sid": {tr.ValidationSID},
			"v":   {apiVersion},
		})
	}
	return res, nil
}

// post sends a form-encoded POST with the client user agent and returns the
// response body.
func (f vkFetcher) post(ctx context.Context, endpoint string, vals url.Values) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(vals.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", userAgent)
	resp, err := f.do.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// validationHint renders a human-readable hint for where the code comes from.
func validationHint(validationType string) string {
	switch validationType {
	case "sms":
		return "SMS"
	case "2fa_app":
		return "authenticator app"
	case "2fa_callreset":
		return "incoming call"
	case "":
		return "VK"
	default:
		return validationType
	}
}

// readLine reads a single trimmed line from r.
func readLine(r *bufio.Reader) (string, error) {
	line, err := r.ReadString('\n')
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(line), nil
}

// run executes the interactive flow. It returns an error (with a
// human-readable message already written to stderr) on any failure, so the
// caller can exit non-zero.
func run(stdin io.Reader, stdout, stderr io.Writer, fetcher tokenFetcher) error {
	in := bufio.NewReader(stdin)

	fmt.Fprint(stdout, "Login (phone or email): ")
	login, err := readLine(in)
	if err != nil {
		return fmt.Errorf("read login: %w", err)
	}
	if login == "" {
		return errors.New("login must not be empty")
	}

	fmt.Fprint(stdout, "Password: ")
	password, err := readLine(in)
	if err != nil {
		return fmt.Errorf("read password: %w", err)
	}
	if password == "" {
		return errors.New("password must not be empty")
	}

	code := ""
	for {
		res, err := fetcher.Auth(context.Background(), login, password, code)
		if err != nil {
			fmt.Fprintf(stderr, "VK auth request failed: %v\n", err)
			return err
		}
		if res.AccessToken != "" {
			fmt.Fprintf(stdout, "access_token=%s\n", res.AccessToken)
			fmt.Fprintf(stdout, "user_id=%d\n", res.UserID)
			fmt.Fprintf(stdout, "expires_in=%d\n", res.ExpiresIn)
			// VK ignores the requested scope for first-party clients and
			// returns the app's own mask, so "offline" is not guaranteed.
			// A non-zero expires_in means the token WILL die; say so loudly
			// instead of letting it fail later as "token expired".
			if res.ExpiresIn != 0 {
				fmt.Fprintf(stderr, "warning: VK did not grant the offline scope — this token expires in %d seconds (~%dh); re-run the tool to refresh it\n",
					res.ExpiresIn, res.ExpiresIn/3600)
			}
			return nil
		}
		if res.Need2FA || res.BadCode {
			if res.BadCode {
				fmt.Fprintln(stdout, "Invalid code, try again.")
			} else {
				fmt.Fprintf(stdout, "Code sent via %s.\n", validationHint(res.ValidationType))
			}
			fmt.Fprint(stdout, "Code: ")
			code, err = readLine(in)
			if err != nil {
				return fmt.Errorf("read code: %w", err)
			}
			if code == "" {
				return errors.New("code must not be empty")
			}
			continue
		}
		if res.Err != nil {
			fmt.Fprintf(stderr, "VK auth failed: %v\n", res.Err)
			return res.Err
		}
		err = errors.New("unexpected auth result")
		fmt.Fprintf(stderr, "%v\n", err)
		return err
	}
}

func main() {
	if err := run(os.Stdin, os.Stdout, os.Stderr, vkFetcher{do: http.DefaultClient}); err != nil {
		os.Exit(1)
	}
}
