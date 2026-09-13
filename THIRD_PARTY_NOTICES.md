# Third-party notices

QMix is licensed under the MIT License. Distributed artifacts also contain the
runtime dependencies listed below. Exact versions, dependency edges, package
identifiers, license identifiers, and artifact hashes are recorded in the
CycloneDX SBOMs described in `docs/versioning.md`.

The complete license texts distributed with QMix are under
`third_party/licenses/`. Copyright remains with each upstream project and its
contributors.

## Go binaries

- `github.com/oklookat/goym` and `github.com/oklookat/vantuz` — MIT; copyright
  2023 oklookat. See `third_party/licenses/goym-vantuz-MIT.txt`.
- `github.com/google/go-querystring` — BSD 3-Clause; copyright 2013 Google.
  See `third_party/licenses/go-querystring-BSD-3-Clause.txt`.
- `golang.org/x/time` and the Go standard library — BSD 3-Clause; copyright The
  Go Authors. See `third_party/licenses/go-BSD-3-Clause.txt`.

## Android APK

Every resolved runtime artifact in the following coordinate families is
licensed under Apache License 2.0:

- `androidx.*` — Android Open Source Project and AndroidX contributors;
- `org.jetbrains.kotlin*` and `org.jetbrains.kotlinx*` — JetBrains and Kotlin
  contributors;
- `com.squareup.okhttp3*` and `com.squareup.okio*` — Square and contributors;
- `com.google.guava*` — Google and Guava contributors;
- `com.google.zxing:core` — ZXing authors;
- `org.jetbrains:annotations` — JetBrains and contributors;
- `org.jspecify:jspecify` — JSpecify authors and contributors.

This covers all direct and transitive artifacts currently resolved from the
Android `releaseRuntimeClasspath`, including every AndroidX family represented
in the generated SBOM. See `third_party/licenses/Apache-2.0.txt` for the complete
license terms. When the runtime graph changes, update this notice and verify
that every generated SBOM component still has a corresponding license entry.
