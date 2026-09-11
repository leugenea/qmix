package buildinfo_test

import (
	"context"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestAllCommandsReportIdenticalLinkedVersion(t *testing.T) {
	root, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal(err)
	}
	ldflags := "-X=github.com/leugenea/qmix/internal/buildinfo.Version=1.2.3-rc.4 " +
		"-X=github.com/leugenea/qmix/internal/buildinfo.Commit=0123456789abcdef0123456789abcdef01234567 " +
		"-X=github.com/leugenea/qmix/internal/buildinfo.Dirty=false " +
		"-X=github.com/leugenea/qmix/internal/buildinfo.AndroidVersionCode=102030004"
	want := `{"version":"1.2.3-rc.4","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":102030004}` + "\n"

	for _, name := range []string{"token-vk", "token-ym", "qmix"} {
		binary := filepath.Join(t.TempDir(), name)
		build := exec.Command("go", "build", "-ldflags", ldflags, "-o", binary, "./cmd/"+name)
		build.Dir = root
		if out, err := build.CombinedOutput(); err != nil {
			t.Fatalf("build %s: %v: %s", name, err, out)
		}

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		cmd := exec.CommandContext(ctx, binary, "--version")
		out, err := cmd.CombinedOutput()
		cancel()
		if err != nil {
			t.Fatalf("%s --version: %v: %s", name, err, out)
		}
		if string(out) != want {
			t.Fatalf("%s --version = %q, want %q", name, out, want)
		}
	}
}
