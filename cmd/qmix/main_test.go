package main

import (
	"bytes"
	"errors"
	"testing"

	"github.com/leugenea/qmix/internal/buildinfo"
)

func TestExecuteDispatchesVersionOrServer(t *testing.T) {
	oldVersion, oldCommit := buildinfo.Version, buildinfo.Commit
	oldDirty, oldCode := buildinfo.Dirty, buildinfo.AndroidVersionCode
	buildinfo.Version = "1.2.3"
	buildinfo.Commit = "0123456789abcdef0123456789abcdef01234567"
	buildinfo.Dirty = "false"
	buildinfo.AndroidVersionCode = "102039999"
	t.Cleanup(func() {
		buildinfo.Version, buildinfo.Commit = oldVersion, oldCommit
		buildinfo.Dirty, buildinfo.AndroidVersionCode = oldDirty, oldCode
	})

	var out bytes.Buffer
	served := false
	serve := func() error {
		served = true
		return errors.New("server stopped")
	}
	if err := execute([]string{"--version"}, &out, serve); err != nil {
		t.Fatal(err)
	}
	want := `{"version":"1.2.3","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":102039999}` + "\n"
	if out.String() != want || served {
		t.Fatalf("version dispatch: output=%q served=%t", out.String(), served)
	}

	out.Reset()
	if err := execute(nil, &out, serve); err == nil || err.Error() != "server stopped" {
		t.Fatalf("server dispatch error = %v", err)
	}
	if !served || out.Len() != 0 {
		t.Fatalf("server dispatch: served=%t output=%q", served, out.String())
	}
}

func TestListenAddrDefault(t *testing.T) {
	t.Setenv("QMIX_ADDR", "")
	if got := listenAddr(); got != ":8080" {
		t.Fatalf("addr = %q, want :8080", got)
	}
}

func TestListenAddrFromEnv(t *testing.T) {
	t.Setenv("QMIX_ADDR", ":9000")
	if got := listenAddr(); got != ":9000" {
		t.Fatalf("addr = %q, want :9000", got)
	}
}
