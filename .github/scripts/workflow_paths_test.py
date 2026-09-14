#!/usr/bin/env python3
import importlib.util
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("workflow_paths.py")
REPOSITORY_ROOT = MODULE_PATH.parents[2]
WORKFLOW_DIR = REPOSITORY_ROOT / ".github" / "workflows"
ROUTED_WORKFLOWS = ("ci.yml", "android.yml", "live.yml")
SPEC = importlib.util.spec_from_file_location("workflow_paths", MODULE_PATH)
workflow_paths = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workflow_paths)


class WorkflowPathsTest(unittest.TestCase):
    def test_android_source_routes_only_android(self):
        self.assertEqual(
            workflow_paths.classify(["android/app/src/main/MainActivity.kt"]),
            {"android"},
        )

    def test_path_matrix(self):
        cases = {
            "cmd/qmix/main.go": {"ci"},
            "internal/server/server.go": {"ci"},
            "internal/stream/ytdlp.go": {"ci", "live"},
            "internal/buildinfo/buildinfo.go": {"ci", "android"},
            "go.mod": {"ci", "android", "live"},
            "go.sum": {"ci", "android", "live"},
            "Makefile": {"ci"},
            "Dockerfile": {"ci"},
            ".dockerignore": {"ci"},
            ".github/scripts/test_summary.py": {"ci"},
            ".github/scripts/generate_sbom.py": {"ci", "android"},
            ".github/workflows/ci.yml": {"ci"},
            ".github/workflows/android.yml": {"android"},
            ".github/workflows/live.yml": {"live"},
            ".github/scripts/workflow_paths.py": {"ci", "android", "live"},
            "README.md": set(),
            ".github/cyrillic-allowlist.json": set(),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(workflow_paths.classify([path]), expected)

    def test_combines_routes_for_multiple_paths(self):
        self.assertEqual(
            workflow_paths.classify(
                ["android/app/build.gradle.kts", "internal/server/server.go"]
            ),
            {"android", "ci"},
        )

    def test_cli_reads_nul_delimited_paths_and_emits_every_output(self):
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)],
            input=b"internal/stream/ytdlp.go\0README.md\0",
            capture_output=True,
            check=True,
        )
        self.assertEqual(
            result.stdout.decode().splitlines(),
            ["ci=true", "android=false", "live=true"],
        )

    def test_workflows_share_isolated_concurrency_contract(self):
        group = (
            "group: ${{ github.workflow }}-${{ github.event_name }}-"
            "${{ github.event.pull_request.number || github.ref }}"
        )
        for path in list(WORKFLOW_DIR.glob("*.yml")) + list(WORKFLOW_DIR.glob("*.yaml")):
            with self.subTest(path=path.name):
                text = path.read_text()
                self.assertIn(group, text)
                self.assertIn("cancel-in-progress: true", text)

    def test_every_action_is_pinned_to_a_full_commit(self):
        action = re.compile(r"^\s*-?\s*uses:\s+[^@\s]+@([^\s#]+)", re.MULTILINE)
        for path in list(WORKFLOW_DIR.glob("*.yml")) + list(WORKFLOW_DIR.glob("*.yaml")):
            for ref in action.findall(path.read_text()):
                with self.subTest(path=path.name, ref=ref):
                    self.assertRegex(ref, r"^[0-9a-f]{40}$")

    def test_each_workflow_has_routing_and_a_stable_result(self):
        routed_jobs = {
            "ci.yml": ("CI result", ("ci", "integration-mandatory", "docker")),
            "android.yml": (
                "Android result",
                ("android-build", "android-tv-instrumentation"),
            ),
            "live.yml": ("Live result", ("live-nas",)),
        }
        for filename, (result_name, jobs) in routed_jobs.items():
            text = (WORKFLOW_DIR / filename).read_text()
            self.assertRegex(text, r"(?m)^  route:\n")
            self.assertRegex(
                text,
                rf"(?m)^  result:\n    name: {re.escape(result_name)}\n    if: always\(\)",
            )
            for job in jobs:
                with self.subTest(path=filename, job=job):
                    match = re.search(
                        rf"(?ms)^  {re.escape(job)}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
                        text,
                    )
                    self.assertIsNotNone(match)
                    block = match.group("body")
                    self.assertIn("needs: route", block)
                    self.assertIn("needs.route.outputs.", block)

    def test_route_job_shell_scripts_have_valid_bash_syntax(self):
        for filename in ROUTED_WORKFLOWS:
            script = self._route_script(filename)
            completed = subprocess.run(
                ["bash", "-n"], input=script, text=True, capture_output=True
            )
            with self.subTest(path=filename):
                self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_route_git_diff_disables_rename_detection(self):
        for filename in ROUTED_WORKFLOWS:
            with self.subTest(path=filename):
                self.assertIn(
                    "git diff --no-renames --name-only -z", self._route_script(filename)
                )

    def test_renaming_routed_file_to_unrouted_path_still_routes_deletion(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = pathlib.Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            source = repository / "internal" / "removed.go"
            source.parent.mkdir()
            source.write_text("package internal\n")
            subprocess.run(["git", "add", "."], cwd=repository, check=True)
            subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=Routing Test",
                    "-c",
                    "user.email=routing@example.invalid",
                    "commit",
                    "-qm",
                    "add routed file",
                ],
                cwd=repository,
                check=True,
            )
            source.rename(repository / "README.md")
            subprocess.run(["git", "add", "-A"], cwd=repository, check=True)
            subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=Routing Test",
                    "-c",
                    "user.email=routing@example.invalid",
                    "commit",
                    "-qm",
                    "rename routed file",
                ],
                cwd=repository,
                check=True,
            )
            changed = subprocess.run(
                [
                    "git",
                    "diff",
                    "--no-renames",
                    "--name-only",
                    "-z",
                    "HEAD~1",
                    "HEAD",
                ],
                cwd=repository,
                check=True,
                capture_output=True,
            ).stdout
            paths = [part.decode() for part in changed.split(b"\0") if part]
            self.assertIn("internal/removed.go", paths)
            self.assertEqual(workflow_paths.classify(paths), {"ci"})

    def test_unavailable_nonzero_base_forces_every_route(self):
        expected = ["ci=true", "android=true", "live=true"]
        for filename in ROUTED_WORKFLOWS:
            script = self._route_script(filename)
            with tempfile.TemporaryDirectory() as temp_dir:
                output = pathlib.Path(temp_dir) / "github-output"
                env = {
                    "BASE_SHA": "unavailable-base-sha",
                    "HEAD_SHA": "HEAD",
                    "GITHUB_OUTPUT": str(output),
                    "RUNNER_TEMP": temp_dir,
                    "PATH": os.environ["PATH"],
                }
                completed = subprocess.run(
                    ["bash", "-e", "-c", script],
                    cwd=REPOSITORY_ROOT,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                with self.subTest(path=filename):
                    self.assertEqual(completed.returncode, 0, completed.stderr)
                    self.assertEqual(output.read_text().splitlines(), expected)

    def test_live_manual_and_scheduled_runs_bypass_path_routing(self):
        text = (WORKFLOW_DIR / "live.yml").read_text()
        self.assertIn('github.event_name }}" == workflow_dispatch', text)
        self.assertIn('github.event_name }}" == schedule', text)
        self.assertIn("printf 'live=true\\n'", text)

    def test_missing_or_all_zero_base_forces_every_route(self):
        for filename in ROUTED_WORKFLOWS:
            with self.subTest(path=filename):
                text = (WORKFLOW_DIR / filename).read_text()
                self.assertNotIn("git diff-tree", text)
                self.assertIn("printf 'ci=true\\nandroid=true\\nlive=true\\n'", text)

    def test_result_jobs_enforce_route_truth_table(self):
        jobs = {
            "ci.yml": (
                "CI_RESULT",
                "INTEGRATION_RESULT",
                "REPORT_RESULT",
                "DOCKER_RESULT",
            ),
            "android.yml": ("BUILD_RESULT", "INSTRUMENTATION_RESULT"),
            "live.yml": ("LIVE_RESULT",),
        }
        for filename, job_results in jobs.items():
            script = self._result_script(filename)
            base_env = {
                "ROUTE_RESULT": "success",
                "POLICY_RESULT": "success",
                "REF": "refs/heads/main",
                "EVENT_NAME": "push",
                "HEAD_REPOSITORY": "",
                "REPOSITORY": "leugenea/qmix",
            }
            for route, result, succeeds in (
                ("true", "success", True),
                ("false", "skipped", True),
                ("", "skipped", False),
                ("invalid", "skipped", False),
                ("true", "skipped", False),
                ("false", "success", False),
            ):
                env = base_env | {"ROUTE_OUTPUT": route}
                env.update(dict.fromkeys(job_results, result))
                completed = subprocess.run(["bash", "-e", "-c", script], env=env)
                with self.subTest(filename=filename, route=route, result=result):
                    self.assertEqual(completed.returncode == 0, succeeds)

            env = base_env | {"ROUTE_RESULT": "failure", "ROUTE_OUTPUT": "false"}
            env.update(dict.fromkeys(job_results, "skipped"))
            completed = subprocess.run(["bash", "-e", "-c", script], env=env)
            with self.subTest(filename=filename, route_result="failure"):
                self.assertNotEqual(completed.returncode, 0)

    def test_ci_result_allows_skipped_publication_for_unprivileged_prs(self):
        script = self._result_script("ci.yml")
        base = {
            "ROUTE_RESULT": "success",
            "ROUTE_OUTPUT": "true",
            "POLICY_RESULT": "success",
            "CI_RESULT": "success",
            "INTEGRATION_RESULT": "success",
            "DOCKER_RESULT": "success",
            "REPORT_RESULT": "skipped",
            "EVENT_NAME": "pull_request",
            "REPOSITORY": "leugenea/qmix",
        }
        cases = (
            ({"HEAD_REPOSITORY": "contributor/qmix", "ACTOR": "contributor"}, "skipped"),
            ({"HEAD_REPOSITORY": "leugenea/qmix", "ACTOR": "dependabot[bot]"}, "skipped"),
            ({"HEAD_REPOSITORY": "leugenea/qmix", "ACTOR": "leugenea"}, "success"),
        )
        for case, expected_report in cases:
            completed = subprocess.run(
                ["bash", "-e", "-c", script],
                env=base | case | {"REPORT_RESULT": expected_report},
            )
            with self.subTest(**case, report=expected_report):
                self.assertEqual(completed.returncode, 0)

            unexpected_report = "success" if expected_report == "skipped" else "failure"
            completed = subprocess.run(
                ["bash", "-e", "-c", script],
                env=base | case | {"REPORT_RESULT": unexpected_report},
            )
            with self.subTest(**case, report=unexpected_report):
                self.assertNotEqual(completed.returncode, 0)

    def test_live_manual_non_main_ref_never_requires_nas(self):
        script = self._result_script("live.yml")
        base = {
            "ROUTE_RESULT": "success",
            "ROUTE_OUTPUT": "true",
            "REF": "refs/heads/unreviewed",
        }
        skipped = subprocess.run(
            ["bash", "-e", "-c", script], env=base | {"LIVE_RESULT": "skipped"}
        )
        ran = subprocess.run(
            ["bash", "-e", "-c", script], env=base | {"LIVE_RESULT": "success"}
        )
        self.assertEqual(skipped.returncode, 0)
        self.assertNotEqual(ran.returncode, 0)

    def test_trusted_service_result_accepts_only_expected_ref_outcome(self):
        script = self._result_script("service-integration.yml")
        cases = (
            ("refs/heads/main", "success", True),
            ("refs/heads/main", "skipped", False),
            ("refs/heads/unreviewed", "skipped", True),
            ("refs/heads/unreviewed", "success", False),
        )
        for ref, result, succeeds in cases:
            completed = subprocess.run(
                ["bash", "-e", "-c", script],
                env={"REF": ref, "INTEGRATION_RESULT": result},
            )
            with self.subTest(ref=ref, result=result):
                self.assertEqual(completed.returncode == 0, succeeds)

    def _route_script(self, filename):
        text = (WORKFLOW_DIR / filename).read_text()
        route = re.search(
            r"(?ms)^  route:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
            text,
        )
        self.assertIsNotNone(route)
        run = re.search(
            r"(?ms)^        run: \|\n(?P<script>(?:^          .*\n?)*)",
            route.group("body"),
        )
        self.assertIsNotNone(run)
        script = textwrap.dedent(run.group("script"))
        return re.sub(r"\$\{\{.*?\}\}", "pull_request", script)

    def _result_script(self, filename):
        text = (WORKFLOW_DIR / filename).read_text()
        result = re.search(r"(?ms)^  result:\n(?P<body>.*)\Z", text).group("body")
        run = re.search(r"(?ms)^        run: \|\n(?P<script>(?:^          .*\n?)*)", result)
        self.assertIsNotNone(run)
        return textwrap.dedent(run.group("script"))


if __name__ == "__main__":
    unittest.main()
