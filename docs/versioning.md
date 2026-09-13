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
4. Stable is a new `vX.Y.Z` tag on the accepted commit. The RC tag is not
   renamed.
5. Before publication, compare the JSON from `--version` for all binaries and
   the image OCI labels with the tag and commit. Artifact publication remains
   tracked in #67.

## Release SBOMs

Issue #67 must attach both deterministic CycloneDX 1.6 inventories alongside
the distributed artifacts:

- `dist/sbom/qmix-go.cdx.json` covers the three shipped Go binaries, their
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

Docker accepts the required build args `VERSION`, `COMMIT`, `DIRTY`, and
`ANDROID_VERSION_CODE`, embeds them in the binary, and verifies it during the
build. The final image contains the OCI labels
`org.opencontainers.image.version`, `org.opencontainers.image.revision`, and
`org.opencontainers.image.source`. To verify locally or in CI:

```bash
.github/scripts/test_docker_metadata.sh qmix:metadata-test
```
