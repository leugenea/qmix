#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
  echo "usage: $0 BOM.json [BOM.json ...]" >&2
  exit 2
fi

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo "official CycloneDX validation currently supports Linux x86_64 only" >&2
  exit 2
fi

version=0.33.1
expected_sha256=bfc8b2538da86fe239bc53658bbb63c1c8c510a293c1e6891aa5bea5d3c58746
tool_dir=$(mktemp -d)
trap 'rm -rf "$tool_dir"' EXIT
tool="$tool_dir/cyclonedx"

curl --fail --silent --show-error --location \
  "https://github.com/CycloneDX/cyclonedx-cli/releases/download/v${version}/cyclonedx-linux-x64" \
  --output "$tool"
printf '%s  %s\n' "$expected_sha256" "$tool" | sha256sum -c -
chmod +x "$tool"

for bom in "$@"; do
  DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 "$tool" validate \
    --input-file "$bom" \
    --input-format json \
    --input-version v1_6
done
