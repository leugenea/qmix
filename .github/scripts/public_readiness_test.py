#!/usr/bin/env python3
"""Repository policy tests for public and trusted-workflow readiness."""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def job(text, name):
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        text,
    )
    if not match:
        raise AssertionError(f"missing job {name}")
    return match.group("body")


class PublicReadinessPolicyTest(unittest.TestCase):
    def test_mit_license_and_public_documents_are_present(self):
        license_text = read("LICENSE")
        self.assertIn("MIT License", license_text)
        self.assertIn("Copyright (c) 2026 QMix contributors", license_text)
        self.assertIn("Permission is hereby granted, free of charge", license_text)
        for filename in (
            "SECURITY.md",
            "CONTRIBUTING.md",
            "AGENTS.md",
            "THIRD_PARTY_NOTICES.md",
        ):
            with self.subTest(filename=filename):
                self.assertTrue((ROOT / filename).is_file(), f"missing {filename}")

    def test_public_readme_has_no_private_idea_engine_reference(self):
        self.assertNotIn("idea-engine", read("README.md").lower())

    def test_live_workflow_is_trusted_only_and_read_only(self):
        text = read(".github/workflows/live.yml")
        trigger = text.split("\nconcurrency:", 1)[0]
        self.assertNotRegex(trigger, r"(?m)^  pull_request:")
        self.assertRegex(trigger, r"(?m)^  push:\n    branches: \[main\]$")
        self.assertRegex(trigger, r"(?m)^  schedule:")
        self.assertRegex(trigger, r"(?m)^  workflow_dispatch:")
        self.assertRegex(text, r"(?m)^permissions:\n  contents: read$")
        nas = job(text, "live-nas")
        self.assertIn("runs-on: [self-hosted, nas]", nas)
        self.assertIn("github.ref == 'refs/heads/main'", nas)
        self.assertIn("persist-credentials: false", nas)

    def test_live_stream_acceptance_is_explicit_and_blocking(self):
        text = read(".github/workflows/live.yml")
        trigger = text.split("\nconcurrency:", 1)[0]
        self.assertRegex(
            trigger,
            r"(?ms)^  workflow_dispatch:\n    inputs:\n      stream_acceptance:.*?"
            r"^        type: boolean$",
        )
        nas = job(text, "live-nas")
        self.assertIn("continue-on-error: ${{ !inputs.stream_acceptance }}", nas)
        self.assertIn("timeout-minutes: 25", nas)
        self.assertIn('QMIX_STREAM_ACCEPTANCE: "1"', nas)
        self.assertIn("shell: bash", nas)
        self.assertIn("set -o pipefail", nas)
        self.assertIn("go test -count=1", nas)
        self.assertIn("-run '^TestStreamProxyAcceptanceLive$' ./internal/server", nas)
        self.assertIn("qmix-stream-acceptance.txt", nas)

    def test_live_yt_dlp_is_pinned_verified_and_job_temporary(self):
        text = read(".github/workflows/live.yml")
        expected_url = (
            "https://github.com/yt-dlp/yt-dlp/releases/download/"
            "2026.08.19/yt-dlp_linux"
        )
        expected_sha = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
        self.assertIn(expected_url, text)
        self.assertIn(expected_sha, text)
        self.assertNotIn("/releases/latest/", text)
        self.assertNotIn("$HOME/.local/bin", text)
        self.assertIn('YTDLP_BIN: ${{ runner.temp }}/yt-dlp', text)
        download = text.index(expected_url)
        verify = text.index("sha256sum -c", download)
        execute = text.index('"$YTDLP_BIN" --version', download)
        self.assertLess(download, verify)
        self.assertLess(verify, execute)

    def test_pull_request_workflows_receive_no_repository_secrets(self):
        for path in WORKFLOWS.glob("*.yml"):
            text = path.read_text(encoding="utf-8")
            trigger = text.split("\nconcurrency:", 1)[0]
            if re.search(r"(?m)^  pull_request:", trigger):
                with self.subTest(path=path.name):
                    self.assertNotIn("secrets.", text)
                    self.assertNotRegex(text, r"QMIX_(VK|YM|SPOTIFY).+secrets")
                    self.assertNotIn("self-hosted", text)
                    self.assertNotRegex(text, r"runs-on:.*\bnas\b")

    def test_every_self_hosted_workflow_excludes_pull_requests(self):
        for path in WORKFLOWS.glob("*.yml"):
            text = path.read_text(encoding="utf-8")
            if "self-hosted" in text:
                trigger = text.split("\nconcurrency:", 1)[0]
                with self.subTest(path=path.name):
                    self.assertNotRegex(trigger, r"(?m)^  pull_request:")

    def test_credentialed_tests_are_isolated_to_trusted_main(self):
        ci = read(".github/workflows/ci.yml")
        self.assertNotIn("secrets.", ci)
        self.assertNotRegex(ci, r"(?m)^  integration:\n")
        self.assertRegex(
            ci, r"(?m)^  result:\n    name: CI result\n    if: always\(\)"
        )

        trusted = read(".github/workflows/service-integration.yml")
        trigger = trusted.split("\nconcurrency:", 1)[0]
        self.assertNotRegex(trigger, r"(?m)^  pull_request:")
        self.assertRegex(trigger, r"(?m)^  push:\n    branches: \[main\]$")
        self.assertRegex(trigger, r"(?m)^  workflow_dispatch:")
        self.assertRegex(trusted, r"(?m)^permissions:\n  contents: read$")
        integration = job(trusted, "integration")
        self.assertIn("github.ref == 'refs/heads/main'", integration)
        for secret in (
            "QMIX_VK_TOKEN",
            "QMIX_YM_TOKEN",
            "QMIX_SPOTIFY_CLIENT_ID",
            "QMIX_SPOTIFY_CLIENT_SECRET",
        ):
            self.assertIn(f"secrets.{secret}", integration)
        self.assertRegex(trusted, r"(?m)^  result:\n    if: always\(\)")

    def test_pr_code_jobs_cannot_write_checks_or_keep_checkout_credentials(self):
        ci = read(".github/workflows/ci.yml")
        self.assertRegex(ci, r"(?m)^permissions:\n  contents: read$")

        for name in ("route", "policy", "ci", "integration-mandatory", "docker"):
            body = job(ci, name)
            with self.subTest(job=name):
                self.assertNotIn("checks: write", body)
                self.assertGreater(body.count("uses: actions/checkout@"), 0)
                self.assertEqual(
                    body.count("persist-credentials: false"),
                    body.count("uses: actions/checkout@"),
                )

        publisher = job(ci, "publish-test-reports")
        self.assertRegex(publisher, r"(?m)^    permissions:\n      checks: write$")
        self.assertNotIn("actions/checkout@", publisher)
        self.assertNotRegex(publisher, r"(?m)^\s+run:")
        self.assertEqual(publisher.count("uses: mikepenz/action-junit-report@"), 2)

    def test_fork_pull_requests_skip_privileged_check_publication(self):
        ci = read(".github/workflows/ci.yml")
        reporter_condition = (
            "github.event_name != 'pull_request' || "
            "(github.event.pull_request.head.repo.full_name == github.repository && "
            "github.actor != 'dependabot[bot]')"
        )
        publisher = job(ci, "publish-test-reports")
        self.assertEqual(publisher.count(reporter_condition), 1)

    def test_test_report_artifact_handoff_is_complete_and_fail_closed(self):
        ci = read(".github/workflows/ci.yml")
        unit = job(ci, "ci")
        integration = job(ci, "integration-mandatory")
        publisher = job(ci, "publish-test-reports")

        self.assertIn("name: test-report-unit\n", unit)
        self.assertIn("path: unit.xml\n", unit)
        self.assertIn("name: test-report-integration-mandatory\n", integration)
        self.assertIn("path: integration.xml\n", integration)
        self.assertEqual(ci.count("if-no-files-found: error"), 2)
        self.assertIn("pattern: test-report-*", publisher)
        self.assertIn("report_paths: 'test-reports/unit.xml'", publisher)
        self.assertIn("report_paths: 'test-reports/integration.xml'", publisher)
        self.assertEqual(publisher.count("require_tests: true"), 2)
        self.assertEqual(publisher.count("fail_on_parse_error: true"), 2)

    def test_all_actions_are_full_sha_pinned_and_permissions_are_explicit(self):
        action = re.compile(r"^\s*-?\s*uses:\s+[^@\s]+@([^\s#]+)", re.MULTILINE)
        for path in WORKFLOWS.glob("*.yml"):
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path.name, policy="permissions"):
                self.assertRegex(text, r"(?m)^permissions:\n(?:  [a-z-]+: (?:read|write|none)\n)+")
            for ref in action.findall(text):
                with self.subTest(path=path.name, ref=ref):
                    self.assertRegex(ref, r"^[0-9a-f]{40}$")

    def test_agent_and_contributor_guidance_covers_repository_contracts(self):
        agents = read("AGENTS.md")
        contributing = read("CONTRIBUTING.md")
        for phrase in (
            "ARCHITECTURE.md",
            "English",
            "sub-issue",
            "TDD",
            "full commit SHA",
            "secret",
            "Do not merge",
        ):
            with self.subTest(document="AGENTS.md", phrase=phrase):
                self.assertIn(phrase, agents)
        for command in (
            "make lint",
            "make test",
            "make test-integration",
            "make test-workflow-routing",
            "make test-public-readiness",
            "make test-sbom",
        ):
            with self.subTest(document="CONTRIBUTING.md", command=command):
                self.assertIn(command, contributing)

    def test_sbom_release_contract_is_wired(self):
        makefile = read("Makefile")
        gitignore = read(".gitignore")
        versioning = read("docs/versioning.md")
        ci = read(".github/workflows/ci.yml")
        android = read(".github/workflows/android.yml")
        self.assertTrue((ROOT / ".github/scripts/generate_sbom.py").is_file())
        self.assertTrue((ROOT / ".github/scripts/generate_sbom_test.py").is_file())
        for target in (
            "sbom:",
            "sbom-go:",
            "sbom-android:",
            "sbom-schema:",
            "test-sbom:",
        ):
            self.assertIn(target, makefile)
        self.assertIn("test-actionlint:", makefile)
        self.assertIn("dist/sbom/", gitignore)
        self.assertIn("CycloneDX", versioning)
        self.assertIn("#67", versioning)
        self.assertIn("make sbom-go", ci)
        self.assertIn("make sbom-android", android)
        self.assertIn("test-public-readiness", ci)
        self.assertIn("test-sbom", ci)
        self.assertIn("test-actionlint", ci)
        validator = read(".github/scripts/validate_sbom_schema.sh")
        self.assertIn("version=0.33.1", validator)
        self.assertIn("CycloneDX/cyclonedx-cli/releases/download/v${version}", validator)
        self.assertIn(
            "bfc8b2538da86fe239bc53658bbb63c1c8c510a293c1e6891aa5bea5d3c58746",
            validator,
        )
        self.assertIn("cyclonedx-linux-x64", validator)

    def test_android_ci_uploads_the_apk_bound_to_its_sbom(self):
        android = read(".github/workflows/android.yml")
        release_upload = re.search(
            r"(?ms)- name: Upload release APK and SBOM\n(?P<body>.*?)(?=^      - name:|\Z)",
            android,
        )
        self.assertIsNotNone(release_upload)
        body = release_upload.group("body")
        self.assertIn("android/app/build/outputs/apk/release/app-release-unsigned.apk", body)
        self.assertIn("dist/sbom/qmix-android.cdx.json", body)

    def test_third_party_license_material_covers_runtime_families(self):
        notices = read("THIRD_PARTY_NOTICES.md")
        for family in (
            "androidx.*",
            "org.jetbrains.kotlin*",
            "org.jetbrains.kotlinx*",
            "com.squareup.okhttp3*",
            "com.squareup.okio*",
            "com.google.guava*",
            "com.google.zxing:core",
            "org.jetbrains:annotations",
            "org.jspecify:jspecify",
        ):
            with self.subTest(family=family):
                self.assertIn(family, notices)
        for filename in (
            "Apache-2.0.txt",
            "goym-vantuz-MIT.txt",
            "go-querystring-BSD-3-Clause.txt",
            "go-BSD-3-Clause.txt",
        ):
            with self.subTest(filename=filename):
                self.assertTrue((ROOT / "third_party" / "licenses" / filename).is_file())


if __name__ == "__main__":
    unittest.main()
