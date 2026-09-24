# Contributing

## Prerequisites

Install Go 1.25, Docker, Python 3, JDK 17, and the Android SDK packages listed in
`.github/workflows/android.yml`. Use the checked-in Gradle wrapper; dependency
verification remains strict.

## Workflow

Open or reference an issue before substantial work. Use sub-issues for bounded
parts of a larger issue, keep documentation and maintained repository text in
English, and keep pull requests focused. Describe the change, tests, and related
issue in the pull request.

Add or update automated tests for every behavior change: new features need
tests covering the new behavior, and bug fixes need a regression test that
fails without the fix. Behavior-preserving refactors and dependency or
toolchain upgrades rely on the existing tests and gates. Do not weaken tests
or coverage gates. Then run the relevant full gates:

```bash
make lint
make test
make test-integration
make test-workflow-routing
make test-public-readiness
make test-sbom
make test-actionlint

# Android build, lint, and JVM tests
cd android
./gradlew --no-daemon --dependency-verification=strict \
  :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Generate release SBOMs with `make sbom`; see `docs/versioning.md`.

## Secrets and dependencies

Never commit, print, or place credentials in fixtures. Keep local values in
ignored `.env` files. Pull-request workflows must not receive repository
secrets or run on persistent self-hosted runners. Pin GitHub Actions to full
40-character commit SHAs and pin downloaded executables with a reviewed
SHA-256 checksum. Do not disable or weaken Gradle dependency verification.
