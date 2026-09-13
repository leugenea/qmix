# Agent instructions

- Read `ARCHITECTURE.md`, `README.md`, and the relevant source and tests before
  editing. The Go backend is under `cmd/` and `internal/`; the Android TV app is
  the single Gradle module under `android/app`; release policy is documented in
  `docs/versioning.md`.
- Keep all maintained repository text and documentation in English.
- Work from a canonical issue. Use a sub-issue for each bounded implementation
  unit and preserve issue references in tests, docs, and pull requests where
  they explain a contract.
- Follow TDD: add one focused failing test, run it to prove RED, make the minimum
  change, prove GREEN, and then refactor. Do not weaken tests or coverage gates.
- Run `make lint test test-integration test-workflow-routing
  test-public-readiness test-sbom test-actionlint`. For Android changes, also
  run the Gradle assemble, lint, and JVM test commands in `CONTRIBUTING.md` with
  strict dependency verification.
- Never read, expose, log, commit, or add secret values to tests. Pull-request
  workflows must not receive repository secrets or use persistent self-hosted
  runners.
- Pin every GitHub Action to a full commit SHA. Pin every downloaded tool to an
  exact version and verify its reviewed SHA-256 before execution.
- Keep generated SBOMs under ignored `dist/sbom/`; update
  `THIRD_PARTY_NOTICES.md` when shipped runtime dependencies change.
- Do not merge, push, publish, or change remote settings without explicit
  maintainer instruction. Do not merge merely because checks are green.
