#!/usr/bin/env python3
"""Repository policy tests for public and trusted-workflow readiness."""

import pathlib
import re
import os
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
ACTION_USE = re.compile(
    r"^\s*-?\s*uses:\s+(?P<reference>[^\s#]+)(?:\s+#\s+(?P<comment>\S+))?\s*$"
)


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def workflow_files(directory=WORKFLOWS):
    return sorted((*directory.glob("*.yml"), *directory.glob("*.yaml")))


def assert_external_action_policy(test_case, path):
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if "uses:" not in line:
            continue
        match = ACTION_USE.fullmatch(line)
        if match is None:
            raise AssertionError(f"{path}:{line_number}: malformed uses entry")
        reference = match.group("reference")
        if reference.startswith(("./", "../", "docker://")):
            continue
        action, separator, revision = reference.rpartition("@")
        test_case.assertTrue(
            separator and "/" in action,
            f"{path}:{line_number}: malformed external Action reference",
        )
        test_case.assertRegex(
            revision,
            r"^[0-9a-f]{40}$",
            f"{path}:{line_number}: external Action must use a full 40-character SHA",
        )
        test_case.assertRegex(
            match.group("comment") or "",
            r"^v\d+(?:\.\d+){0,2}$",
            f"{path}:{line_number}: external Action SHA needs an adjacent semver release comment",
        )


def dependabot_update_blocks(text):
    return re.findall(r"(?ms)^  - package-ecosystem:.*?(?=^  - package-ecosystem:|\Z)", text)


def job(text, name):
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        text,
    )
    if not match:
        raise AssertionError(f"missing job {name}")
    return match.group("body")


class PublicReadinessPolicyTest(unittest.TestCase):
    def test_compose_log_preparation_rejects_symlink_components(self):
        script = ROOT / "scripts" / "prepare_compose_logs.py"
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp) / "repo"
            outside = pathlib.Path(temp) / "outside"
            root.mkdir()
            outside.mkdir()
            (root / "agent-apps-data").symlink_to(outside, target_is_directory=True)

            result = subprocess.run(
                ["python3", str(script), "--root", str(root)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((outside / "qmix").exists())

            (root / "agent-apps-data").unlink()
            gid = subprocess.check_output(
                ["python3", str(script), "--root", str(root)], text=True
            ).strip()
            logs = root / "agent-apps-data" / "qmix" / "logs"
            self.assertEqual(str(os.getgid()), gid)
            self.assertEqual(0o770, logs.stat().st_mode & 0o777)

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

    def test_compose_resolves_to_the_agent_apps_project(self):
        compose = read("docker-compose.yml")
        self.assertRegex(compose, r"(?m)^name: agent-apps$")

        policy = job(read(".github/workflows/ci.yml"), "policy")
        self.assertIn("set -o pipefail", policy)
        self.assertIn("QMIX_LOG_GID=$(python3 scripts/prepare_compose_logs.py)", policy)
        self.assertIn("export QMIX_LOG_GID", policy)
        self.assertIn("docker compose config --format json", policy)
        self.assertIn("jq -e '.name == \"agent-apps\"'", policy)

    def test_backend_logging_is_persistent_and_smoke_tested(self):
        compose = read("docker-compose.yml")
        self.assertIn('QMIX_LOG_LEVEL: "${QMIX_LOG_LEVEL:-warn}"', compose)
        self.assertIn('QMIX_LOG_FILE: "/var/log/qmix/qmix.log"', compose)
        self.assertIn("source: ./agent-apps-data/qmix/logs", compose)
        self.assertIn("target: /var/log/qmix", compose)
        self.assertIn("create_host_path: false", compose)
        self.assertIn('"${QMIX_LOG_GID:?run make compose-up}"', compose)
        self.assertIn('max-size: "10m"', compose)
        self.assertIn('max-file: "3"', compose)
        self.assertNotIn("log-init:", compose)
        self.assertNotIn('user: "0:0"', compose)

        prepare = read("scripts/prepare_compose_logs.py")
        self.assertIn("os.O_NOFOLLOW", prepare)
        self.assertIn("dir_fd=", prepare)
        self.assertIn("os.fchmod", prepare)
        makefile = read("Makefile")
        self.assertIn("compose-up:", makefile)
        self.assertIn("scripts/prepare_compose_logs.py", makefile)

        dockerfile = read("Dockerfile")
        self.assertIn("adduser -D -H -u 10001 qmix", dockerfile)
        self.assertIn("chown -R qmix:qmix /var/log/qmix", dockerfile)

        docker = job(read(".github/workflows/ci.yml"), "docker")
        self.assertIn("docker compose logs --no-color --no-log-prefix backend", docker)
        self.assertIn("docker compose rm -s -f backend", docker)
        self.assertGreaterEqual(docker.count("qmix.log"), 2)
        self.assertNotIn("chmod 0777", docker)
        self.assertNotIn("chown 10001:10001 agent-apps-data/qmix/logs", docker)
        self.assertIn('"msg":"server listening"', docker)
        self.assertIn('"component":"server/http"', docker)
        self.assertIn('"level":"INFO"', docker)
        self.assertIn("jq -e .", docker)
        self.assertIn("stat -c '%a %u %g' agent-apps-data/qmix/logs", docker)
        self.assertIn("QMIX_LOG_GID=$(python3 scripts/prepare_compose_logs.py)", docker)
        self.assertIn('test "$log_dir_mode_owner" = "770 $host_uid $QMIX_LOG_GID"', docker)
        self.assertIn("test \"$log_file_mode_owner\" = \"600 10001 10001\"", docker)
        self.assertIn('test "$(docker compose exec -T backend id -u)" = "10001"', docker)
        self.assertEqual(2, docker.count('if [ -z "$ready" ]; then'))
        self.assertIn("(( after > before ))", docker)

        readme = read("README.md")
        for phrase in (
            "QMIX_LOG_LEVEL",
            "QMIX_LOG_FILE",
            "docker compose logs backend",
            "agent-apps-data/qmix/logs/qmix.log",
            "persistent log write failed",
            "10 MiB",
        ):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, readme)

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

        for name in ("route", "policy", "ci", "integration-mandatory", "docker", "erosion", "complexity-gate", "duplication"):
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

    def test_erosion_python_wheels_are_hash_pinned_and_installed_for_ci(self):
        requirements = read(".github/requirements-lizard.txt")
        workflow = read(".github/workflows/ci.yml")
        producer = job(workflow, "erosion")
        policy = job(workflow, "policy")
        for body, venv in ((policy, "erosion-policy-venv"), (producer, "erosion-venv")):
            with self.subTest(venv=venv):
                self.assertIn("uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0", body)
                self.assertIn("python-version: '3.13'", body)
                self.assertIn(f'python -m venv "$RUNNER_TEMP/{venv}"', body)
                self.assertNotIn("python3 -m venv", body)
                self.assertIn("--require-hashes --no-deps -r .github/requirements-lizard.txt", body)
        for package in ("lizard==1.24.0", "tree-sitter==0.25.2",
                        "tree-sitter-kotlin==1.1.0"):
            self.assertRegex(requirements, re.escape(package) + r" --hash=sha256:[0-9a-f]{64}")
        self.assertIn("0.26.0", requirements)  # Record why 0.25.2 is pinned.
        self.assertIn("--require-hashes --no-deps -r .github/requirements-lizard.txt", producer)
        self.assertIn(".github/scripts/kotlin_complexity_test.py -v", producer)
        self.assertIn("$RUNNER_TEMP/erosion-venv/bin/python", producer)
        self.assertIn("--lizard \"$RUNNER_TEMP/erosion-venv/bin/lizard\"", producer)

    def test_duplication_job_is_unprivileged_and_artifact_publisher_is_fork_safe(self):
        workflow = read(".github/workflows/ci.yml")
        producer = job(workflow, "duplication")
        publisher = job(workflow, "publish-erosion")
        self.assertIn("needs: route", producer)
        self.assertIn("needs.route.outputs.duplication == 'true'", producer)
        self.assertNotIn("github.event_name == 'pull_request'", producer)
        self.assertNotIn("permissions:", producer)
        self.assertNotIn("secrets.", producer)
        self.assertIn("persist-credentials: false", producer)
        self.assertIn("v5.3.2/jscpd-linux-x64-gnu.tar.gz", producer)
        self.assertIn("'97259f222ea7f6d51a0f2faa98ed5889430233e0fb8d2c129f23d84aff4b93b2' 'jscpd-linux-x64-gnu.tar.gz' | sha256sum -c -", producer)
        self.assertLess(producer.index("sha256sum -c -"), producer.index("tar -xzf"))
        self.assertIn('"$RUNNER_TEMP/jscpd/jscpd"', producer)
        self.assertIn("--config .jscpd.json", producer)
        self.assertIn("--threshold 100 --fail-on-empty", producer)
        self.assertIn(".github/scripts/jscpd_report.py report ", producer)
        self.assertIn("name: code-duplication", producer)
        self.assertIn("if-no-files-found: error", producer)
        self.assertIn("$GITHUB_STEP_SUMMARY", producer)
        self.assertIn("needs: [route, erosion, duplication]", publisher)
        self.assertIn("needs.duplication.result == 'success'", publisher)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", publisher)
        self.assertIn("github.actor != 'dependabot[bot]'", publisher)
        self.assertIn("github.event.pull_request.user.login != 'dependabot[bot]'", publisher)
        self.assertIn("if: needs.duplication.result == 'success'", publisher)
        self.assertIn("name: code-duplication", publisher)
        self.assertNotIn("actions/checkout@", publisher)
        self.assertIn("pull-requests: write", publisher)
        self.assertEqual(workflow.count("pull-requests: write"), 1)

    def test_duplication_history_shares_main_only_erosion_publisher(self):
        ci = read(".github/workflows/ci.yml")
        route = job(ci, "route")
        duplication = job(ci, "duplication")
        history = job(ci, "erosion-history")
        pages = job(ci, "erosion-pages")
        self.assertIn("FORCE_DUPLICATION: ${{ github.event_name == 'push' }}", route)
        self.assertIn("needs.route.outputs.duplication == 'true'", duplication)
        self.assertNotIn("github.event_name == 'pull_request'", duplication)
        self.assertIn("needs: [erosion, duplication]", history)
        self.assertIn("always()", history)
        for condition in ("github.event_name == 'push'", "github.ref == 'refs/heads/main'",
                          "needs.erosion.result == 'success' || needs.duplication.result == 'success'"):
            self.assertIn(condition, history)
        self.assertIn("needs: erosion-history", pages)
        self.assertIn("needs.erosion-history.result == 'success'", pages)
        self.assertIn("group: erosion-history-main", history)
        steps = re.findall(r"(?ms)^      - name: ([^\n]+)\n(?P<body>.*?)(?=^      - name:|\Z)", history)
        self.assertEqual([name for name, _ in steps if name.startswith("Publish ")],
                         ["Publish erosion history and alert", "Publish duplication history and alert"])
        for label, artifact, directory in (("erosion", "code-erosion", "erosion"),
                                           ("duplication", "code-duplication", "duplication")):
            download = next(body for name, body in steps if name == f"Download {label} report")
            convert = next(body for name, body in steps if name == f"Convert {label} report to benchmark metrics")
            publish = next(body for name, body in steps if name == f"Publish {label} history and alert")
            self.assertIn(f"if: needs.{label}.result == 'success'", download)
            self.assertIn(f"if: needs.{label}.result == 'success'", convert)
            self.assertIn(f"if: needs.{label}.result == 'success'", publish)
            self.assertIn(f"name: {artifact}", download)
            self.assertIn(f'--input "$RUNNER_TEMP/{directory}/report.json"', convert)
            self.assertIn(f"name: Code {label}", publish)
            self.assertIn("benchmark-action/github-action-benchmark@4322e5726e6334590d251fc4f92bec0efafc45dc # v1.22.2", publish)
            for setting in ("tool: customSmallerIsBetter", "gh-pages-branch: gh-pages",
                            "benchmark-data-dir-path: dev/bench", "auto-push: true",
                            'alert-threshold: "110%"', "comment-on-alert: true", "fail-on-alert: false"):
                self.assertIn(setting, publish)
            self.assertIn(f"output-file-path: ${{{{ runner.temp }}}}/{directory}/benchmark.json", publish)
        self.assertIn("--kind duplication", next(body for name, body in steps
                                                     if name == "Convert duplication report to benchmark metrics"))

    def test_erosion_history_has_only_main_push_write_permission(self):
        ci = read(".github/workflows/ci.yml")
        history = job(ci, "erosion-history")
        self.assertRegex(ci, r"(?m)^permissions:\n  contents: read$")
        self.assertIn("github.event_name == 'push'", history)
        self.assertIn("github.ref == 'refs/heads/main'", history)
        self.assertIn("needs.erosion.result == 'success'", history)
        self.assertRegex(history, r"(?m)^    permissions:\n      contents: write$")
        self.assertEqual(ci.count("contents: write"), 1)
        self.assertIn("persist-credentials: false", history)
        self.assertIn("cancel-in-progress: false", history)
        self.assertIn("queue: max", history)
        self.assertIn("name: code-erosion", history)
        self.assertIn("name: Code erosion", history)
        self.assertIn("--input \"$RUNNER_TEMP/erosion/report.json\"", history)
        self.assertIn("tool: customSmallerIsBetter", history)
        self.assertIn('alert-threshold: "110%"', history)
        self.assertIn("comment-on-alert: true", history)
        self.assertIn("fail-on-alert: false", history)
        self.assertIn("auto-push: true", history)
        self.assertIn("needs.erosion-history.result", job(ci, "result"))

    def test_pages_deploys_only_successful_main_history_with_minimal_permissions(self):
        ci = read(".github/workflows/ci.yml")
        pages = job(ci, "erosion-pages")
        self.assertIn("needs: erosion-history", pages)
        self.assertIn("github.event_name == 'push'", pages)
        self.assertIn("github.ref == 'refs/heads/main'", pages)
        self.assertIn("needs.erosion-history.result == 'success'", pages)
        # A job-level permission map replaces, rather than inherits, the
        # workflow-level contents: read. Checkout requires explicit read.
        self.assertRegex(
            pages,
            r"(?m)^    permissions:\n      contents: read\n"
            r"      pages: write\n      id-token: write$",
        )
        self.assertRegex(pages, r"(?m)^    environment:\n      name: github-pages\n"
                                r"      url: \$\{\{ steps.deploy.outputs.page_url \}\}$")
        self.assertIn("group: erosion-pages", pages)
        self.assertIn("queue: max", pages)
        self.assertIn("cancel-in-progress: false", pages)
        self.assertIn("ref: gh-pages", pages)
        self.assertIn("persist-credentials: false", pages)
        self.assertIn("run: touch .nojekyll", pages)
        self.assertIn("include-hidden-files: true", pages)
        self.assertIn("actions/upload-pages-artifact@", pages)
        self.assertIn("actions/deploy-pages@", pages)
        # qmix#232: a re-run retains the previous attempt's artifact, so the
        # upload and deployment must select the same per-attempt artifact.
        upload = re.search(
            r"(?ms)^      - name: Upload chart snapshot\n(?P<body>.*?)(?=^      - name:|\Z)",
            pages,
        )
        deploy = re.search(
            r"(?ms)^      - name: Deploy chart\n(?P<body>.*?)(?=^      - name:|\Z)",
            pages,
        )
        self.assertIsNotNone(upload)
        self.assertIsNotNone(deploy)
        upload_name = re.search(r"(?m)^          name: (.+)$", upload.group("body"))
        deploy_name = re.search(r"(?m)^          artifact_name: (.+)$", deploy.group("body"))
        self.assertIsNotNone(upload_name, "Pages upload must name the artifact")
        self.assertIsNotNone(deploy_name, "Pages deploy must select the artifact")
        self.assertEqual(upload_name.group(1), deploy_name.group(1))
        self.assertEqual(upload_name.group(1), "github-pages-${{ github.run_attempt }}")
        self.assertIn("needs.erosion-pages.result", job(ci, "result"))

        self.assertEqual(ci.count("queue: max"), 2)
        expected_writes = {
            "publish-erosion": {"pull-requests: write"},
            "erosion-history": {"contents: write"},
            "erosion-pages": {"pages: write", "id-token: write"},
            "publish-test-reports": {"checks: write"},
        }
        names = re.findall(r"(?m)^  ([a-z][a-z-]+):$", ci.split("\njobs:\n", 1)[1])
        for name in names:
            body = job(ci, name)
            actual = set(re.findall(r"(?m)^      [a-z-]+: write$", body))
            self.assertEqual({line.strip() for line in actual},
                             expected_writes.get(name, set()), name)

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
        for producer in (unit, integration):
            self.assertEqual(producer.count("if-no-files-found: error"), 1)
        erosion = job(ci, "erosion")
        self.assertIn("name: code-erosion", erosion)
        self.assertIn("if-no-files-found: error", erosion)
        duplication = job(ci, "duplication")
        self.assertIn("name: code-duplication", duplication)
        self.assertIn("duplication/report.json", duplication)
        self.assertIn("duplication/report.md", duplication)
        self.assertIn("if-no-files-found: error", duplication)
        gate = job(ci, "complexity-gate")
        self.assertIn("name: complexity-gate", gate)
        self.assertIn("complexity-gate/report.json", gate)
        self.assertIn("if-no-files-found: error", gate)
        self.assertIn("pattern: test-report-*", publisher)
        self.assertIn("report_paths: 'test-reports/unit.xml'", publisher)
        self.assertIn("report_paths: 'test-reports/integration.xml'", publisher)
        self.assertEqual(publisher.count("require_tests: true"), 2)
        self.assertEqual(publisher.count("fail_on_parse_error: true"), 2)

    def test_workflow_permissions_are_explicit(self):
        for path in workflow_files():
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path.name, policy="permissions"):
                self.assertRegex(text, r"(?m)^permissions:\n(?:  [a-z-]+: (?:read|write|none)\n)+")

    def test_every_external_action_has_full_sha_and_adjacent_semver_comment(self):
        paths = workflow_files()
        self.assertGreater(len(paths), 0)
        for path in paths:
            with self.subTest(path=path.name):
                assert_external_action_policy(self, path)

    def test_action_policy_covers_yaml_and_rejects_missing_version_comment(self):
        with tempfile.TemporaryDirectory() as temp:
            workflows = pathlib.Path(temp)
            workflow = workflows / "fixture.yaml"
            workflow.write_text(
                "jobs:\n"
                "  policy:\n"
                "    steps:\n"
                "      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262\n",
                encoding="utf-8",
            )
            self.assertEqual([workflow], workflow_files(workflows))
            with self.assertRaisesRegex(AssertionError, "adjacent semver release comment"):
                assert_external_action_policy(self, workflow)

    def test_dependabot_updates_github_actions_under_repository_policy(self):
        dependabot = read(".github/dependabot.yml")
        self.assertRegex(dependabot, r"(?m)^version: 2$")
        blocks = dependabot_update_blocks(dependabot)
        ecosystems = []
        for block in blocks:
            match = re.search(r'^  - package-ecosystem: "([^"]+)"$', block, re.MULTILINE)
            if match is None:
                self.fail("Dependabot update is missing a quoted package-ecosystem")
            ecosystems.append(match.group(1))
        self.assertEqual(["github-actions"], ecosystems)

        actions = blocks[0]
        for expected in (
            '    directory: "/"',
            '      interval: "weekly"',
            '      day: "monday"',
            '      time: "06:00"',
            '      timezone: "UTC"',
            "    open-pull-requests-limit: 3",
            '      prefix: "chore(actions)"',
            "      minor-and-patch:",
            '        applies-to: "version-updates"',
            '          - "*"',
        ):
            with self.subTest(setting=expected.strip()):
                self.assertIn(expected, actions)
        update_types = re.findall(r'^          - "(minor|patch|major)"$', actions, re.MULTILINE)
        self.assertEqual(["minor", "patch"], update_types)
        self.assertNotIn("automerge", actions.lower())
        self.assertIn(
            "SHA-pinned Actions do not receive Dependabot alerts or security-update PRs",
            actions,
        )
        self.assertIn(
            "weekly version-update scan is the automated Actions update path",
            actions,
        )

        ci = read(".github/workflows/ci.yml")
        trigger = ci.split("\nconcurrency:", 1)[0]
        self.assertRegex(trigger, r"(?m)^  pull_request:$")
        self.assertNotIn("pull_request_target", ci)
        self.assertNotIn("secrets.", ci)
        self.assertNotRegex(ci, r"runs-on:.*(?:self-hosted|\bnas\b)")

    def test_agent_and_contributor_guidance_covers_repository_contracts(self):
        agents = read("AGENTS.md")
        contributing = read("CONTRIBUTING.md")
        for phrase in (
            "ARCHITECTURE.md",
            "English",
            "sub-issue",
            "regression test",
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

    def test_android_setup_does_not_request_removed_legacy_tools_package(self):
        android = read(".github/workflows/android.yml")
        self.assertEqual(2, android.count("android-actions/setup-android@"))
        self.assertEqual(2, android.count("packages: platform-tools"))
        self.assertNotIn("packages: tools", android)

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
