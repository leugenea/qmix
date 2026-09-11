package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"testing"

	"github.com/leugenea/qmix/internal/buildinfo"
)

type rejectingWriter struct{}

func (rejectingWriter) Write([]byte) (int, error) { return 0, errors.New("write failed") }

func TestRunReportsRenderAndWriteFailures(t *testing.T) {
	if err := run("../../../..", "xml", &bytes.Buffer{}); err == nil {
		t.Fatal("run() ignored render error")
	}
	if err := run("../../../..", "env", rejectingWriter{}); err == nil {
		t.Fatal("run() ignored write error")
	}
}

func TestExecuteAndRenderRejectInvalidRequests(t *testing.T) {
	var out bytes.Buffer
	if err := execute([]string{"-unknown"}, &out); err == nil {
		t.Fatal("execute() accepted unknown flag")
	}
	if err := execute([]string{"-repository", t.TempDir()}, &out); err == nil {
		t.Fatal("execute() accepted non-repository")
	}
	if _, err := render(buildinfo.Info{}, "xml"); err == nil {
		t.Fatal("render() accepted unknown format")
	}
}

func TestExecuteParsesCommandLine(t *testing.T) {
	var out bytes.Buffer
	if err := execute([]string{"-repository", "../../../..", "-format", "env"}, &out); err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(out.Bytes(), []byte("VERSION=")) {
		t.Fatalf("execute() output = %q", out.String())
	}
}

func TestRunComputesRepositoryMetadata(t *testing.T) {
	var out bytes.Buffer
	if err := run("../../../..", "json", &out); err != nil {
		t.Fatal(err)
	}
	var info buildinfo.Info
	if err := json.Unmarshal(out.Bytes(), &info); err != nil {
		t.Fatalf("output is not JSON: %v", err)
	}
	if len(info.Commit) != 40 || info.Version == "" {
		t.Fatalf("run() output = %+v", info)
	}
}

func TestRenderEnvironment(t *testing.T) {
	info := buildinfo.Info{
		Version:            "0.0.0-dev.0123456789ab.dirty",
		Commit:             "0123456789abcdef0123456789abcdef01234567",
		Dirty:              true,
		AndroidVersionCode: 1,
	}
	got, err := render(info, "env")
	if err != nil {
		t.Fatal(err)
	}
	want := "VERSION=0.0.0-dev.0123456789ab.dirty\n" +
		"COMMIT=0123456789abcdef0123456789abcdef01234567\n" +
		"DIRTY=true\nANDROID_VERSION_CODE=1\n"
	if got != want {
		t.Fatalf("render() = %q, want %q", got, want)
	}
}

func TestRenderJSON(t *testing.T) {
	info := buildinfo.Info{
		Version:            "1.2.3",
		Commit:             "0123456789abcdef0123456789abcdef01234567",
		AndroidVersionCode: 102039999,
	}
	got, err := render(info, "json")
	if err != nil {
		t.Fatal(err)
	}
	want := `{"version":"1.2.3","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":102039999}` + "\n"
	if got != want {
		t.Fatalf("render() = %q, want %q", got, want)
	}
}

func TestRenderLinkerFlags(t *testing.T) {
	info := buildinfo.Info{
		Version:            "1.2.3-rc.4",
		Commit:             "0123456789abcdef0123456789abcdef01234567",
		Dirty:              false,
		AndroidVersionCode: 102030004,
	}
	got, err := render(info, "ldflags")
	if err != nil {
		t.Fatal(err)
	}
	want := "-X=github.com/leugenea/qmix/internal/buildinfo.Version=1.2.3-rc.4 " +
		"-X=github.com/leugenea/qmix/internal/buildinfo.Commit=0123456789abcdef0123456789abcdef01234567 " +
		"-X=github.com/leugenea/qmix/internal/buildinfo.Dirty=false " +
		"-X=github.com/leugenea/qmix/internal/buildinfo.AndroidVersionCode=102030004\n"
	if got != want {
		t.Fatalf("render() = %q, want %q", got, want)
	}
}
