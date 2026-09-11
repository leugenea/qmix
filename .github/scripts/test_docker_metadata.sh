#!/bin/sh
set -eu

image=${1:-qmix:metadata-test}
eval "$(go run ./internal/buildinfo/cmd/version -format=env)"
expected=$(go run ./internal/buildinfo/cmd/version -format=json)

docker build \
  --build-arg VERSION="$VERSION" \
  --build-arg COMMIT="$COMMIT" \
  --build-arg DIRTY="$DIRTY" \
  --build-arg ANDROID_VERSION_CODE="$ANDROID_VERSION_CODE" \
  -t "$image" .

actual_version=$(docker image inspect "$image" --format '{{ index .Config.Labels "org.opencontainers.image.version" }}')
actual_revision=$(docker image inspect "$image" --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')
actual_source=$(docker image inspect "$image" --format '{{ index .Config.Labels "org.opencontainers.image.source" }}')

[ "$actual_version" = "$VERSION" ]
[ "$actual_revision" = "$COMMIT" ]
[ "$actual_source" = "https://github.com/leugenea/qmix" ]
[ "$(docker run --rm "$image" --version)" = "$expected" ]
