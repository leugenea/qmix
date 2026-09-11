package buildinfo

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const testCommit = "0123456789abcdef0123456789abcdef01234567"

func TestRunSelectsVersionOrApplication(t *testing.T) {
	setLinkedValues(t, "1.2.3", testCommit, "false", "102039999")
	var out bytes.Buffer
	called := false
	next := func() error { called = true; return nil }

	if err := Run([]string{"--version"}, &out, next); err != nil {
		t.Fatal(err)
	}
	if called || out.Len() == 0 {
		t.Fatalf("version path: called=%t output=%q", called, out.String())
	}
	out.Reset()
	if err := Run(nil, &out, next); err != nil {
		t.Fatal(err)
	}
	if !called || out.Len() != 0 {
		t.Fatalf("application path: called=%t output=%q", called, out.String())
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("write failed") }

func TestPrintVersionPassesThroughAndReportsErrors(t *testing.T) {
	if handled, err := PrintVersion(nil, failingWriter{}); handled || err != nil {
		t.Fatalf("non-version request = handled %t, err %v", handled, err)
	}
	setLinkedValues(t, "1.2.3", testCommit, "false", "102039999")
	if handled, err := PrintVersion([]string{"--version"}, failingWriter{}); !handled || err == nil {
		t.Fatalf("failed write = handled %t, err %v", handled, err)
	}
	setLinkedValues(t, "", "", "", "")
	if handled, err := PrintVersion([]string{"--version"}, failingWriter{}); !handled || err == nil {
		t.Fatalf("invalid metadata = handled %t, err %v", handled, err)
	}
}

func TestPrintVersionHandlesVersionFlag(t *testing.T) {
	setLinkedValues(t, "1.2.3", testCommit, "false", "102039999")
	var out bytes.Buffer
	handled, err := PrintVersion([]string{"--version"}, &out)
	if err != nil {
		t.Fatal(err)
	}
	if !handled || out.String() == "" {
		t.Fatalf("PrintVersion() = handled %t, output %q", handled, out.String())
	}
}

func TestFromRepositoryReportsGitFailures(t *testing.T) {
	dir := t.TempDir()
	fakeGit := filepath.Join(dir, "git")
	script := "#!/bin/sh\n" +
		"[ \"$1\" = \"$FAIL_GIT\" ] && exit 1\n" +
		"case \"$1\" in\n" +
		"  rev-parse) echo " + testCommit + ";;\n" +
		"  tag|status) :;;\n" +
		"esac\n"
	if err := os.WriteFile(fakeGit, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir)
	for _, command := range []string{"tag", "status"} {
		t.Run(command, func(t *testing.T) {
			t.Setenv("FAIL_GIT", command)
			if _, err := FromRepository(dir); err == nil {
				t.Fatalf("FromRepository() ignored git %s failure", command)
			}
		})
	}
}

func TestFromRepositoryRejectsMultipleExactTags(t *testing.T) {
	dir := newGitRepository(t)
	git(t, dir, "tag", "v1.2.3")
	git(t, dir, "tag", "v1.2.4")
	if _, err := FromRepository(dir); err == nil {
		t.Fatal("FromRepository() accepted multiple release tags")
	}
}

func TestFromRepositoryRejectsMalformedExactTag(t *testing.T) {
	dir := newGitRepository(t)
	git(t, dir, "tag", "v1.2")
	if _, err := FromRepository(dir); err == nil {
		t.Fatal("FromRepository() accepted malformed release tag")
	}
}

func TestFromRepositoryMarksUntaggedDirtyBuild(t *testing.T) {
	dir := newGitRepository(t)
	if err := os.WriteFile(filepath.Join(dir, "tracked"), []byte("changed\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := FromRepository(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Dirty || !strings.HasSuffix(got.Version, ".dirty") {
		t.Fatalf("FromRepository() = %+v", got)
	}
}

func TestFromRepositoryUsesExactReleaseTag(t *testing.T) {
	dir := newGitRepository(t)
	git(t, dir, "tag", "v2.3.4")

	got, err := FromRepository(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got.Version != "2.3.4" || got.Commit != git(t, dir, "rev-parse", "HEAD") || got.Dirty {
		t.Fatalf("FromRepository() = %+v", got)
	}
}

func newGitRepository(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	git(t, dir, "init", "-q")
	git(t, dir, "config", "user.email", "test@example.com")
	git(t, dir, "config", "user.name", "Test")
	if err := os.WriteFile(filepath.Join(dir, "tracked"), []byte("content\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	git(t, dir, "add", "tracked")
	git(t, dir, "commit", "-q", "-m", "initial")
	return dir
}

func git(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s: %v: %s", strings.Join(args, " "), err, out)
	}
	return strings.TrimSpace(string(out))
}

func TestCurrentAcceptsDevelopmentMetadata(t *testing.T) {
	setLinkedValues(t, "0.0.0-dev.0123456789ab.dirty", testCommit, "true", "1")
	got, err := Current()
	if err != nil {
		t.Fatal(err)
	}
	if !got.Dirty || got.Version != "0.0.0-dev.0123456789ab.dirty" {
		t.Fatalf("Current() = %+v", got)
	}
}

func TestCurrentRejectsInconsistentLinkedMetadata(t *testing.T) {
	for _, tc := range []struct {
		name, version, commit, dirty, code string
	}{
		{name: "dirty", version: "1.2.3", commit: testCommit, dirty: "yes", code: "102039999"},
		{name: "code syntax", version: "1.2.3", commit: testCommit, dirty: "false", code: "none"},
		{name: "code mismatch", version: "1.2.3", commit: testCommit, dirty: "false", code: "1"},
		{name: "version", version: "1.2", commit: testCommit, dirty: "false", code: "1"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			setLinkedValues(t, tc.version, tc.commit, tc.dirty, tc.code)
			if _, err := Current(); err == nil {
				t.Fatal("Current() accepted inconsistent metadata")
			}
		})
	}
}

func TestCurrentRendersValidatedMachineReadableMetadata(t *testing.T) {
	setLinkedValues(t, "1.2.3", testCommit, "false", "102039999")

	got, err := JSON()
	if err != nil {
		t.Fatal(err)
	}
	want := `{"version":"1.2.3","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":102039999}` + "\n"
	if string(got) != want {
		t.Fatalf("JSON() = %q, want %q", got, want)
	}
}

func setLinkedValues(t *testing.T, version, commit, dirty, code string) {
	t.Helper()
	oldVersion, oldCommit := Version, Commit
	oldDirty, oldCode := Dirty, AndroidVersionCode
	Version, Commit, Dirty, AndroidVersionCode = version, commit, dirty, code
	t.Cleanup(func() {
		Version, Commit, Dirty, AndroidVersionCode = oldVersion, oldCommit, oldDirty, oldCode
	})
}

func TestResolveValidReleaseTags(t *testing.T) {
	for _, tc := range []struct {
		tag     string
		version string
		code    int
	}{
		{tag: "v0.0.0-rc.1", version: "0.0.0-rc.1", code: 1},
		{tag: "v0.0.0", version: "0.0.0", code: 9999},
		{tag: "v20.99.99-rc.9998", version: "20.99.99-rc.9998", code: 2099999998},
		{tag: "v20.99.99", version: "20.99.99", code: 2099999999},
	} {
		got, err := Resolve(tc.tag, testCommit, false)
		if err != nil {
			t.Fatalf("Resolve(%q): %v", tc.tag, err)
		}
		if got.Version != tc.version || got.AndroidVersionCode != tc.code {
			t.Fatalf("Resolve(%q) = %+v", tc.tag, got)
		}
	}
}

func TestResolveStableRelease(t *testing.T) {
	got, err := Resolve("v1.2.3", testCommit, false)
	if err != nil {
		t.Fatal(err)
	}
	if got.Version != "1.2.3" || got.Commit != testCommit || got.Dirty {
		t.Fatalf("Resolve() = %+v", got)
	}
	if got.AndroidVersionCode != 102039999 {
		t.Fatalf("AndroidVersionCode = %d, want 102039999", got.AndroidVersionCode)
	}
}

func TestResolveDevelopmentBuildIncludesRevisionAndDirtyState(t *testing.T) {
	clean, err := Resolve("", testCommit, false)
	if err != nil {
		t.Fatal(err)
	}
	if clean.Version != "0.0.0-dev.0123456789ab" || clean.AndroidVersionCode != 1 {
		t.Fatalf("clean development build = %+v", clean)
	}

	dirty, err := Resolve("", testCommit, true)
	if err != nil {
		t.Fatal(err)
	}
	if dirty.Version != "0.0.0-dev.0123456789ab.dirty" || !dirty.Dirty {
		t.Fatalf("dirty development build = %+v", dirty)
	}
}

func TestResolveRejectsInvalidOrInconsistentInputs(t *testing.T) {
	for _, tc := range []struct {
		name, tag, commit string
		dirty             bool
	}{
		{name: "missing v", tag: "1.2.3", commit: testCommit},
		{name: "leading zero", tag: "v1.02.3", commit: testCommit},
		{name: "build metadata", tag: "v1.2.3+local", commit: testCommit},
		{name: "unsupported prerelease", tag: "v1.2.3-beta.1", commit: testCommit},
		{name: "zero rc", tag: "v1.2.3-rc.0", commit: testCommit},
		{name: "rc exceeds slot", tag: "v1.2.3-rc.9999", commit: testCommit},
		{name: "major exceeds code", tag: "v21.0.0", commit: testCommit},
		{name: "minor exceeds code", tag: "v1.100.0", commit: testCommit},
		{name: "patch exceeds code", tag: "v1.0.100", commit: testCommit},
		{name: "numeric overflow", tag: "v999999999999999999999999.0.0", commit: testCommit},
		{name: "bad commit", tag: "v1.2.3", commit: "not-a-sha"},
		{name: "dirty release", tag: "v1.2.3", commit: testCommit, dirty: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := Resolve(tc.tag, tc.commit, tc.dirty); err == nil {
				t.Fatalf("Resolve(%q, %q, %t) succeeded", tc.tag, tc.commit, tc.dirty)
			}
		})
	}
}

func TestResolveReleaseCandidatesAreMonotonicBeforeStable(t *testing.T) {
	var previous int
	for _, tag := range []string{"v1.2.3-rc.1", "v1.2.3-rc.2", "v1.2.3"} {
		got, err := Resolve(tag, testCommit, false)
		if err != nil {
			t.Fatalf("Resolve(%q): %v", tag, err)
		}
		if got.AndroidVersionCode <= previous {
			t.Fatalf("code for %s = %d, want > %d", tag, got.AndroidVersionCode, previous)
		}
		previous = got.AndroidVersionCode
	}
}
