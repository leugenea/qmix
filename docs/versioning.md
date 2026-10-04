# Versions and build metadata

QMix ships the server, CLI utilities, Docker image, and Android APK as a single
compatible set. The components do not have independent versions.

## Version source

A release is defined by exactly one tag on the commit:

- stable: `vMAJOR.MINOR.PATCH`, for example `v0.2.0`;
- release candidate: `vMAJOR.MINOR.PATCH-rc.N`, where `N` is a number from 1 to
  9998 without leading zeros, for example `v0.2.0-rc.1`.

The `MAJOR`, `MINOR`, and `PATCH` numbers follow SemVer 2.0 and have no leading
zeros. QMix release tags do not use build metadata (`+...`) or prerelease forms
other than `rc.N`; this preserves an unambiguous, monotonic mapping to the
Android `versionCode`. A release commit must have exactly one tag. A malformed
tag, multiple tags on `HEAD`, a dirty tree at a release tag, or inconsistent
embedded fields cause the build or `--version` to fail.

An untagged build has the form
`0.0.0-dev.<12-hex-commit>[.dirty]`. The full 40-character commit and `dirty`
status are also present as separate fields. No timestamp is used, so identical
inputs produce identical metadata.

The single contract calculator is:

```bash
# JSON: version, commit, dirty, androidVersionCode
make version
# The same values as linker flags
go run ./internal/buildinfo/cmd/version -format=ldflags
# Shell variables for Docker/CI
go run ./internal/buildinfo/cmd/version -format=env
```

`make build` embeds these values through Go linker flags in all three binaries.
`qmix --version`, `token-vk --version`, and `token-ym --version` print one line
of JSON with the same schema, for example:

```json
{"version":"0.2.0-rc.1","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":2000001}
```

The binaries do not read `.git` at runtime.

## Android version contract

The Android build must obtain both values only from the output of the shared
calculator:

- `versionName = version` (without the `v` prefix);
- `versionCode = MAJOR*100000000 + MINOR*1000000 + PATCH*10000 + STAGE`;
- `STAGE = N` for `rc.N`, `STAGE = 9999` for stable;
- a local development build gets `versionCode = 1` and is not published.

The allowed ranges are `MAJOR 0..20`, `MINOR/PATCH 0..99`, and `rc.N 1..9998`.
Thus, `rc.1 < rc.2 < stable`, and a patch, minor, or major bump is always greater
than the previous version. The maximum code is `2099999999`, below the Google
Play limit of `2100000000`. Android Gradle files must not define a separate
version constant or recalculate the code; they invoke this mechanism and pass
the resulting fields to `versionName`/`versionCode`.

## Bump, RC, and stable

1. Choose the next `MAJOR.MINOR.PATCH`: PATCH for a compatible fix, MINOR for a
   backward-compatible feature, and MAJOR for an incompatible change.
2. Ensure that the working tree is clean and the required commit is on the main
   development line.
3. For acceptance testing, tag `vX.Y.Z-rc.1`; subsequent candidates increment
   only `N`. Do not move an existing tag.
4. Stable is a new `vX.Y.Z` tag on an otherwise-untagged eligible accepted
   main-line SHA later selected by the owner. It cannot be added to the already
   RC-tagged commit: the shared calculator deliberately rejects multiple tags
   at HEAD. Accept that selected source SHA before tagging; do not manufacture
   a dummy commit, move/delete the RC tag, hide tags or change the parser to
   simulate promotion of the same SHA. The RC tag is not renamed.
5. Before publication, compare the JSON from `--version` for all binaries and
   the image OCI labels with the tag and commit. Artifact publication remains
   tracked in #67.

## Release SBOMs

Issue #67 must attach both deterministic CycloneDX 1.6 inventories alongside
the distributed artifacts:

- `dist/sbom/qmix-go.cdx.json` defaults to the three host-built Go binaries, their
  SHA-256 hashes, and the selected runtime module graph;
- `dist/sbom/qmix-android.cdx.json` covers the unsigned release APK, its SHA-256
  hash, and the resolved `releaseRuntimeClasspath` graph.

Generate and validate both files with `make sbom`, or use `make sbom-go` and
`make sbom-android` separately. `make sbom-validate` validates existing files.
The repository-owned generator uses the Go toolchain and checked-in Gradle
wrapper, invokes Gradle with strict dependency verification, records SPDX
license identifiers, sorts components and edges, and omits timestamps and
random serial numbers. A checksum-pinned official CycloneDX CLI validates each
document against the CycloneDX 1.6 JSON schema. Consequently, the same source
tree, dependency state, and artifact bytes produce byte-identical SBOMs.
Generated files are ignored and must not be committed. Distribute the license
texts under `third_party/licenses/` with release artifacts, and update
`THIRD_PARTY_NOTICES.md` whenever shipped runtime dependencies change.

## Offline release payload (qmix#322)

The offline helper requires a clean, single-release-tag checkout and consumes
only the existing calculator. It does not create tags, sign, publish, or change
registry state. Its output directory must be outside the source tree (hosted
CI uses `RUNNER_TEMP`), or inside ignored `bin/`.

```bash
work=$(mktemp -d)
python3 .github/scripts/release_payload.py build-go --output "$work/go"
python3 .github/scripts/generate_sbom.py go \
  --target-inventory "$work/go/go-inventory.json" --output "$work/go.cdx.json"
make sbom-android
python3 .github/scripts/release_payload.py package \
  --go-inventory "$work/go/go-inventory.json" \
  --apk android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --go-bom "$work/go.cdx.json" --android-bom dist/sbom/qmix-android.cdx.json \
  --aapt "$ANDROID_HOME/build-tools/35.0.0/aapt" --output "$work/payload"
(cd "$work/payload" && sha256sum -c SHA256SUMS)
```

The Go matrix is exactly twelve binaries: `qmix` for Linux amd64/arm64;
`token-vk` and `token-ym` each for Linux amd64/arm64, Darwin amd64/arm64 and
Windows amd64. Windows names end in `.exe`. Builds use `CGO_ENABLED=0`,
`-mod=readonly`, `-buildvcs=true`, the calculator's exact ldflags and an empty
linker build ID. `-trimpath` is deliberately not used: Go's embedded build
settings must retain the ldflags for static identity verification. No claim of
hermetic, cross-host byte-identical rebuilds is made.

The schema-1 inventory binds the shared calculator identity and each artifact's
component, version, commit, OS, architecture, producer source basename,
final filename, positive byte size, SHA-256 and verification method. Consumers
read final files by `filename`; `sourceArtifact` is provenance, not a path to
follow. All records are validated before any Go-only filtering; missing,
duplicate, unexpected, conflicting or malformed records fail closed.
Cross-target SBOM roots use `arch=...&os=...&type=binary` qualifiers and each
runtime graph is resolved with that target's `GOOS`, `GOARCH` and disabled CGO.
Without `--target-inventory`, existing host SBOM generation is unchanged.

The complete intermediate directory contains sixteen inventoried objects,
`qmix-inventory-VERSION-any-any.json` and `SHA256SUMS` (eighteen files total).
Target names contain component/version/OS/arch. SBOMs, the inventory and the
license/notices archive use explicit `any-any`; fixed `SHA256SUMS` is the
owner-approved sole naming exception. Checksums cover all seventeen other
files, including the inventory; the inventory does not hash itself. The license
archive preserves the six existing project/notices/license paths with fixed
ordering, ownership, permissions and timestamps. Packaging identical inputs
and bytes produces identical output bytes; nothing is published.

`qmix-android-unsigned-VERSION-android-any.apk` and
`qmix-android-unsigned-sbom-VERSION-any-any.cdx.json` are **unsigned
intermediates**, never signed distribution substitutes. The helper accepts only
`app-release-unsigned.apk`, checks `com.qmix.tv` and calculator versionName/code
with pinned SDK `aapt`, and rejects debuggable or signed bytes. The separate
#334 implementation adds explicit schema-2 signed-final selection, SDK signing,
signed-APK SBOM regeneration through the existing `--apk` input and immutable
publication. It does not weaken schema 1 or turn an unsigned intermediate into
a distribution. Production remains default-denied until owner #335 setup and
exact approval. See [releasing.md](releasing.md) for the full owner runbook;
#323 remains open until real RC acceptance.

Unprivileged hosted `release-payload` jobs use isolated, locally tagged RC and
stable fixture clones of the exact tested source SHA, strict unsigned Gradle
release builds, schema validation and repeated packaging comparisons. They
retain `go-version-m+header` static evidence in the inventory. Five native runner
platforms execute the exact downloaded Go bytes with `--version` and produce
hash-bound receipts covering all twelve targets, not host-only replacements.
Disposable SDK signing/finalization extends these same RC/stable producers;
native consumers explicitly select the complete signed-final inventory.
Separate downloaded signed-APK hosted TV install/Leanback and native amd64/arm64
image identity/readiness jobs feed the unchanged `CI result` context;
existing Android build/lint/JVM/native coverage and quality gates remain intact.
`make test-release-payload` exercises the focused offline contract tests without
building Go/Android or accessing credentials.

Docker accepts the required build args `VERSION`, `COMMIT`, `DIRTY`, and
`ANDROID_VERSION_CODE`, embeds them in the binary, and verifies it during the
build. The final image contains the OCI labels
`org.opencontainers.image.version`, `org.opencontainers.image.revision`, and
`org.opencontainers.image.source`. To verify locally or in CI:

```bash
.github/scripts/test_docker_metadata.sh qmix:metadata-test
```
