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
            "ci.yml": ("ci", "integration-mandatory", "integration", "docker"),
            "android.yml": ("android-build", "android-tv-instrumentation"),
            "live.yml": ("live-nas",),
        }
        for filename, jobs in routed_jobs.items():
            text = (WORKFLOW_DIR / filename).read_text()
            self.assertRegex(text, r"(?m)^  route:\n")
            self.assertRegex(text, r"(?m)^  result:\n    if: always\(\)")
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
        for path in WORKFLOW_DIR.glob("*.yml"):
            script = self._route_script(path.name)
            completed = subprocess.run(
                ["bash", "-n"], input=script, text=True, capture_output=True
            )
            with self.subTest(path=path.name):
                self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_route_git_diff_disables_rename_detection(self):
        for path in WORKFLOW_DIR.glob("*.yml"):
            with self.subTest(path=path.name):
                self.assertIn(
                    "git diff --no-renames --name-only -z", self._route_script(path.name)
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
        for path in WORKFLOW_DIR.glob("*.yml"):
            script = self._route_script(path.name)
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
                with self.subTest(path=path.name):
                    self.assertEqual(completed.returncode, 0, completed.stderr)
                    self.assertEqual(output.read_text().splitlines(), expected)

    def test_live_manual_and_scheduled_runs_bypass_path_routing(self):
        text = (WORKFLOW_DIR / "live.yml").read_text()
        self.assertIn('github.event_name }}" == workflow_dispatch', text)
        self.assertIn('github.event_name }}" == schedule', text)
        self.assertIn("printf 'live=true\\n'", text)

    def test_missing_or_all_zero_base_forces_every_route(self):
        for path in WORKFLOW_DIR.glob("*.yml"):
            with self.subTest(path=path.name):
                text = path.read_text()
                self.assertNotIn("git diff-tree", text)
                self.assertIn("printf 'ci=true\\nandroid=true\\nlive=true\\n'", text)

    def test_result_jobs_enforce_route_truth_table(self):
        jobs = {
            "ci.yml": (
                "CI_RESULT",
                "INTEGRATION_RESULT",
                "OPTIONAL_INTEGRATION_RESULT",
                "DOCKER_RESULT",
            ),
            "android.yml": ("BUILD_RESULT", "INSTRUMENTATION_RESULT"),
            "live.yml": ("LIVE_RESULT",),
        }
        for filename, job_results in jobs.items():
            script = self._result_script(filename)
            base_env = {"ROUTE_RESULT": "success", "POLICY_RESULT": "success"}
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

    def test_android_emulator_version_parser_accepts_hosted_log_prefix(self):
        program = (
            "/Android emulator version / "
            '{for (i=1; i<=NF; i++) if ($i == "version") {print $(i+1); exit}}'
        )
        text = (WORKFLOW_DIR / "android.yml").read_text()
        self.assertIn(program, text)
        result = subprocess.run(
            ["awk", program],
            input="INFO         | Android emulator version 37.1.11.0 (build_id 15917651)\n",
            capture_output=True,
            check=True,
            text=True,
        )
        self.assertEqual(result.stdout.strip(), "37.1.11.0")

    def test_android_system_image_revision_comes_from_source_properties(self):
        program = '$1 == "Pkg.Revision" {print $2; exit}'
        text = (WORKFLOW_DIR / "android.yml").read_text()
        self.assertIn("source.properties", text)
        self.assertIn(program, text)
        result = subprocess.run(
            ["awk", "-F=", program],
            input="Pkg.Desc=Android TV Intel x86 Atom System Image\nPkg.Revision=4\n",
            capture_output=True,
            check=True,
            text=True,
        )
        self.assertEqual(result.stdout.strip(), "4")

    def test_android_avd_cache_key_covers_every_compatibility_input(self):
        text = (WORKFLOW_DIR / "android.yml").read_text()
        for value in (
            "TV_API_LEVEL: \"36\"",
            "TV_SYSTEM_IMAGE: android-tv",
            "TV_ARCH: x86",
            "TV_DEVICE_PROFILE: tv_1080p",
            "${AVD_NAME}",
            "${AVD_SNAPSHOT_NAME}",
            "AVD_PREP_REVISION:",
            "runner.os",
            "runner.arch",
            "image_revision",
            "steps.avd-key.outputs.key",
        ):
            with self.subTest(value=value):
                self.assertIn(value, text)

    def test_android_avd_cache_contains_only_avd_and_emulator_local_adb_material(self):
        text = (WORKFLOW_DIR / "android.yml").read_text()
        self.assertIn("uses: actions/cache/restore@", text)
        self.assertIn("uses: actions/cache/save@", text)
        cache_paths = (
            "path: |\n"
            "            ~/.android/avd/${{ env.AVD_NAME }}.avd\n"
            "            ~/.android/avd/${{ env.AVD_NAME }}.ini\n"
            "            ~/.android/adbkey\n"
            "            ~/.android/adbkey.pub"
        )
        self.assertEqual(text.count(cache_paths), 2)
        cache_block = text[text.index("uses: actions/cache/restore@") : text.index("name: Prepare clean AVD snapshot")]
        self.assertNotIn("secrets.", cache_block)
        self.assertIn("steps.avd-cache.outputs.cache-hit != 'true'", text)
        self.assertLess(text.index("name: Prepare clean AVD snapshot"), text.index("name: TV emulator smoke and Android coverage gate"))
        self.assertLess(text.index("name: TV emulator smoke and Android coverage gate"), text.index("uses: actions/cache/save@"))

    def test_android_avd_cache_preserves_tv_gate_and_diagnostics(self):
        text = (WORKFLOW_DIR / "android.yml").read_text()
        for value in (
            "--device \"$TV_DEVICE_PROFILE\"",
            "-snapshot \"$AVD_SNAPSHOT_NAME\"",
            "sys.boot_completed",
            "service check input",
            "get-state",
            "debug.qmix.snapshot_rev",
            "-snapshot-list",
            ":app:jacocoDebugCoverageVerification",
            "if: always()",
            "qmix-tv-emulator.log",
            "qmix-tv-adb.log",
            "qmix-tv-logcat.txt",
            "qmix-tv-timings.log",
            "CACHE_HIT: ${{ steps.avd-cache.outputs.cache-hit }}",
            "name: Collect Android TV diagnostics",
        ):
            with self.subTest(value=value):
                self.assertIn(value, text)

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
