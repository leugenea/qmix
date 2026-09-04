// Command token-vk is a small interactive CLI that obtains a VK Music access
// token via github.com/oklookat/vkmauth and prints it to stdout. Unlike the
// synchro CLI it stores nothing on disk: the token is printed in a simple
// machine-readable form suitable for `gh secret set` or an env file.
//
// Credentials (phone, password, 2FA code) are read from stdin, never from
// argv, so the password does not end up in shell history. Tokens are never
// logged.
package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/oklookat/vkmauth"
	"golang.org/x/oauth2"
)

// tokenFetcher abstracts the vkmauth network flow so tests can inject a mock
// and never hit the real VK API.
type tokenFetcher interface {
	// Fetch runs the full VK auth flow and returns the obtained token.
	Fetch(ctx context.Context, phone, password string, onCodeWaiting func(vkmauth.CodeSended) (vkmauth.GotCode, error)) (*oauth2.Token, error)
}

// vkmauthFetcher is the production implementation backed by vkmauth.New.
type vkmauthFetcher struct{}

// Fetch delegates to vkmauth.New.
func (vkmauthFetcher) Fetch(ctx context.Context, phone, password string, onCodeWaiting func(vkmauth.CodeSended) (vkmauth.GotCode, error)) (*oauth2.Token, error) {
	return vkmauth.New(ctx, phone, password, onCodeWaiting)
}

// codeHandler drives the interactive 2FA step: it reports where the code was
// sent, offers a resend when another channel is available, and reads the code
// from stdin. It is a struct so the reader/writer are injectable in tests.
type codeHandler struct {
	in  *bufio.Reader
	out io.Writer
}

// onCodeWaiting implements the vkmauth callback. An empty line resends the
// code via the next available channel (when one exists); any other input is
// treated as the confirmation code.
func (h *codeHandler) onCodeWaiting(by vkmauth.CodeSended) (vkmauth.GotCode, error) {
	if by.Resend != "" {
		fmt.Fprintf(h.out, "Code sent via %s. Enter code, or press Enter to resend via %s: ", by.Current, by.Resend)
	} else {
		fmt.Fprintf(h.out, "Code sent via %s. Enter code: ", by.Current)
	}
	line, err := h.in.ReadString('\n')
	if err != nil {
		return vkmauth.GotCode{}, err
	}
	code := strings.TrimSpace(line)
	if code == "" && by.Resend != "" {
		return vkmauth.GotCode{Resend: true}, nil
	}
	return vkmauth.GotCode{Code: code}, nil
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

	fmt.Fprint(stdout, "Phone: ")
	phone, err := readLine(in)
	if err != nil {
		return fmt.Errorf("read phone: %w", err)
	}
	if phone == "" {
		return errors.New("phone must not be empty")
	}

	fmt.Fprint(stdout, "Password: ")
	password, err := readLine(in)
	if err != nil {
		return fmt.Errorf("read password: %w", err)
	}
	if password == "" {
		return errors.New("password must not be empty")
	}

	handler := &codeHandler{in: in, out: stdout}
	token, err := fetcher.Fetch(context.Background(), phone, password, handler.onCodeWaiting)
	if err != nil {
		fmt.Fprintf(stderr, "VK auth failed: %v\n", err)
		return err
	}
	if token == nil || token.AccessToken == "" {
		err := errors.New("VK auth returned an empty access token")
		fmt.Fprintf(stderr, "%v\n", err)
		return err
	}

	fmt.Fprintf(stdout, "access_token=%s\n", token.AccessToken)
	fmt.Fprintf(stdout, "refresh_token=%s\n", token.RefreshToken)
	return nil
}

func main() {
	if err := run(os.Stdin, os.Stdout, os.Stderr, vkmauthFetcher{}); err != nil {
		os.Exit(1)
	}
}
