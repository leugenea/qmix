package buildinfo_test

import (
	"os"
	"strings"
	"testing"
)

func TestDockerfileEmbedsAndLabelsBuildMetadata(t *testing.T) {
	data, err := os.ReadFile("../../Dockerfile")
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, required := range []string{
		"ARG VERSION",
		"ARG COMMIT",
		"ARG DIRTY",
		"ARG ANDROID_VERSION_CODE",
		"-X=github.com/leugenea/qmix/internal/buildinfo.Version=${VERSION}",
		"org.opencontainers.image.version=$VERSION",
		"org.opencontainers.image.revision=$COMMIT",
		"org.opencontainers.image.source=https://github.com/leugenea/qmix",
	} {
		if !strings.Contains(text, required) {
			t.Errorf("Dockerfile missing %q", required)
		}
	}
}
