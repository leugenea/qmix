// Package buildinfo defines the repository-wide build identity shared by all
// QMix artifacts.
package buildinfo

import (
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

const (
	androidMajorFactor = 100_000_000
	androidMinorFactor = 1_000_000
	androidPatchFactor = 10_000
	androidStable      = 9_999
)

var (
	releaseTagPattern = regexp.MustCompile(`^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-rc\.([1-9][0-9]*))?$`)
	commitPattern     = regexp.MustCompile(`^[0-9a-f]{40}$`)

	// These strings are set by -ldflags -X for every shipped Go binary.
	Version            string
	Commit             string
	Dirty              string
	AndroidVersionCode string
)

// Info is the validated identity embedded in an artifact.
type Info struct {
	Version            string `json:"version"`
	Commit             string `json:"commit"`
	Dirty              bool   `json:"dirty"`
	AndroidVersionCode int    `json:"androidVersionCode"`
}

// Current validates and returns the metadata embedded with linker flags.
func Current() (Info, error) {
	if Dirty != "true" && Dirty != "false" {
		return Info{}, fmt.Errorf("invalid linked dirty value %q", Dirty)
	}
	dirty := Dirty == "true"
	code, err := strconv.Atoi(AndroidVersionCode)
	if err != nil {
		return Info{}, fmt.Errorf("invalid linked Android versionCode %q", AndroidVersionCode)
	}
	tag := "v" + Version
	if strings.HasPrefix(Version, "0.0.0-dev.") {
		tag = ""
	}
	want, err := Resolve(tag, Commit, dirty)
	if err != nil {
		return Info{}, fmt.Errorf("invalid linked build metadata: %w", err)
	}
	if Version != want.Version || code != want.AndroidVersionCode {
		return Info{}, fmt.Errorf("inconsistent linked build metadata: version=%q versionCode=%d", Version, code)
	}
	return want, nil
}

// JSON returns Current in the common one-line machine-readable format.
func JSON() ([]byte, error) {
	info, err := Current()
	if err != nil {
		return nil, err
	}
	out, _ := json.Marshal(info) // Info contains only JSON primitives.
	return append(out, '\n'), nil
}

// Run prints build metadata for --version, otherwise it runs next.
func Run(args []string, out io.Writer, next func() error) error {
	handled, err := PrintVersion(args, out)
	if err != nil || handled {
		return err
	}
	return next()
}

// PrintVersion writes the shared metadata when args is exactly --version.
func PrintVersion(args []string, out io.Writer) (bool, error) {
	if len(args) != 1 || args[0] != "--version" {
		return false, nil
	}
	data, err := JSON()
	if err != nil {
		return true, err
	}
	_, err = out.Write(data)
	return true, err
}

// FromRepository computes build identity from the Git repository at dir.
// Git is used only by the build-time tool; shipped binaries call Current.
func FromRepository(dir string) (Info, error) {
	run := func(args ...string) (string, error) {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		out, err := cmd.Output()
		if err != nil {
			return "", fmt.Errorf("git %s: %w", strings.Join(args, " "), err)
		}
		return strings.TrimSpace(string(out)), nil
	}
	commit, err := run("rev-parse", "--verify", "HEAD")
	if err != nil {
		return Info{}, err
	}
	tag, err := run("tag", "--points-at", "HEAD")
	if err != nil {
		return Info{}, err
	}
	status, err := run("status", "--porcelain", "--untracked-files=normal")
	if err != nil {
		return Info{}, err
	}
	return Resolve(tag, commit, status != "")
}

// Resolve validates build inputs and computes the shared artifact identity.
func Resolve(tag, commit string, dirty bool) (Info, error) {
	if !commitPattern.MatchString(commit) {
		return Info{}, fmt.Errorf("invalid commit %q: want 40 lowercase hexadecimal characters", commit)
	}
	if tag == "" {
		version := "0.0.0-dev." + commit[:12]
		if dirty {
			version += ".dirty"
		}
		return Info{Version: version, Commit: commit, Dirty: dirty, AndroidVersionCode: 1}, nil
	}
	if dirty {
		return Info{}, fmt.Errorf("release tag %q cannot be built from a dirty tree", tag)
	}
	matches := releaseTagPattern.FindStringSubmatch(tag)
	if matches == nil {
		return Info{}, fmt.Errorf("invalid release tag %q", tag)
	}
	major, _ := strconv.Atoi(matches[1])
	minor, _ := strconv.Atoi(matches[2])
	patch, _ := strconv.Atoi(matches[3])
	if major > 20 || minor > 99 || patch > 99 {
		return Info{}, fmt.Errorf("release tag %q exceeds Android versionCode ranges (major <= 20, minor/patch <= 99)", tag)
	}
	stage := androidStable
	if matches[4] != "" {
		stage, _ = strconv.Atoi(matches[4])
		if stage >= androidStable {
			return Info{}, fmt.Errorf("release candidate in %q must be between rc.1 and rc.9998", tag)
		}
	}
	return Info{
		Version:            tag[1:],
		Commit:             commit,
		Dirty:              dirty,
		AndroidVersionCode: major*androidMajorFactor + minor*androidMinorFactor + patch*androidPatchFactor + stage,
	}, nil
}
