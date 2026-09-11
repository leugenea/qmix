// Command version computes validated repository-wide build metadata.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"strconv"

	"github.com/leugenea/qmix/internal/buildinfo"
)

func render(info buildinfo.Info, format string) (string, error) {
	if format == "env" {
		return fmt.Sprintf("VERSION=%s\nCOMMIT=%s\nDIRTY=%s\nANDROID_VERSION_CODE=%d\n",
			info.Version, info.Commit, strconv.FormatBool(info.Dirty), info.AndroidVersionCode), nil
	}
	if format == "json" {
		out, _ := json.Marshal(info) // Info contains only JSON primitives.
		return string(out) + "\n", nil
	}
	if format != "ldflags" {
		return "", fmt.Errorf("unsupported format %q", format)
	}
	return fmt.Sprintf("-X=github.com/leugenea/qmix/internal/buildinfo.Version=%s "+
		"-X=github.com/leugenea/qmix/internal/buildinfo.Commit=%s "+
		"-X=github.com/leugenea/qmix/internal/buildinfo.Dirty=%s "+
		"-X=github.com/leugenea/qmix/internal/buildinfo.AndroidVersionCode=%d\n",
		info.Version, info.Commit, strconv.FormatBool(info.Dirty), info.AndroidVersionCode), nil
}

func run(dir, format string, out io.Writer) error {
	info, err := buildinfo.FromRepository(dir)
	if err != nil {
		return err
	}
	text, err := render(info, format)
	if err != nil {
		return err
	}
	_, err = io.WriteString(out, text)
	return err
}

func execute(args []string, out io.Writer) error {
	flags := flag.NewFlagSet("version", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	format := flags.String("format", "json", "output format: json, env, or ldflags")
	dir := flags.String("repository", ".", "Git repository path")
	if err := flags.Parse(args); err != nil {
		return err
	}
	return run(*dir, *format, out)
}

func main() {
	if err := execute(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
