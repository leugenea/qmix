#!/usr/bin/env python3
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("workflow_paths.py")
sys.path.insert(0, str(MODULE_PATH.parent))  # Standalone import mirrors the route CLI.
REPOSITORY_ROOT = MODULE_PATH.parents[2]
WORKFLOW_DIR = REPOSITORY_ROOT / ".github" / "workflows"
ROUTED_WORKFLOWS = ("ci.yml", "android.yml", "live.yml")
SPEC = importlib.util.spec_from_file_location("workflow_paths", MODULE_PATH)
workflow_paths = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workflow_paths)
EROSION_PATH = MODULE_PATH.with_name("erosion.py")
EROSION_SPEC = importlib.util.spec_from_file_location("erosion", EROSION_PATH)
erosion = importlib.util.module_from_spec(EROSION_SPEC)
EROSION_SPEC.loader.exec_module(erosion)


class WorkflowPathsTest(unittest.TestCase):
    def test_android_source_routes_android_erosion_and_duplication(self):
        self.assertEqual(
            workflow_paths.classify(["android/app/src/main/MainActivity.kt"]),
            {"android", "erosion", "duplication"},
        )

    def test_erosion_route_selects_only_production_sources_and_its_inputs(self):
        cases = {
            "cmd/qmix/main.go": True,
            "internal/server/server.go": True,
            "android/app/src/main/java/App.kt": True,
            "internal/server/guest/app.js": True,
            "internal/server/guest/__tests__/app.js": False,
            "internal/server/guest/generated/app.js": False,
            "internal/server/guest/vendor/app.js": False,
            "android/app/src/main/build/Build.kt": False,
            ".github/scripts/erosion.py": True,
            ".github/scripts/erosion_test.py": True,
            ".github/scripts/benchmark_metrics.py": True,
            ".github/scripts/benchmark_metrics_test.py": True,
            ".github/scripts/kotlin_complexity.py": True,
            ".github/scripts/kotlin_complexity_test.py": True,
            ".github/scripts/complexity_gate.py": True,
            ".github/scripts/complexity_gate_test.py": True,
            ".github/requirements-lizard.txt": True,
            "cmd/qmix/main_test.go": False,
            "internal/server/server_integration_test.go": False,
            "android/app/src/test/java/AppTest.kt": False,
            "android/app/src/androidTest/java/AppTest.kt": False,
            "internal/server/guest/app.test.js": False,
            "internal/server/guest/build/app.js": False,
            "internal/server/guest/third_party/lib.js": False,
            "third_party/go/vendor.go": False,
            "README.md": False,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual("erosion" in workflow_paths.classify([path]), expected)

    def test_erosion_routing_uses_analysis_scope_for_source_paths(self):
        sources = (
            "cmd/qmix/main.go", "internal/server/server.go", "cmd/qmix/main_test.go",
            "internal/server/server_integration_test.go", "internal/generated/code.go",
            "internal/third_party/code.go", "internal/vendor/code.go",
            "android/app/src/main/java/App.kt", "android/app/src/main/build/App.kt",
            "android/app/src/test/java/App.kt", "android/app/src/androidTest/java/App.kt",
            "internal/server/guest/app.js", "internal/server/guest/__tests__/app.js",
            "internal/server/guest/app.test.js", "internal/server/guest/third_party/app.js",
        )
        for path in sources:
            with self.subTest(path=path):
                self.assertEqual("erosion" in workflow_paths.classify([path]), erosion.is_in_scope(path))

    def test_duplication_routing_uses_production_scope_and_own_inputs(self):
        cases = {
            "cmd/qmix/main.go": True,
            "internal/server/server.go": True,
            "android/app/src/main/java/App.kt": True,
            "internal/server/guest/app.js": True,
            "internal/server/guest/app.test.js": False,
            "internal/server/guest/generated/app.js": False,
            "internal/server/guest/third_party/lib.js": False,
            "android/app/src/test/java/AppTest.kt": False,
            "android/app/src/main/build/App.kt": False,
            "cmd/qmix/main_test.go": False,
            "README.md": False,
            "go.mod": False,
            ".jscpd.json": True,
            ".github/scripts/jscpd_report.py": True,
            ".github/scripts/jscpd_report_test.py": True,
            ".github/scripts/erosion.py": True,  # Shared source discovery and scope.
            ".github/scripts/erosion_test.py": False,
            ".github/scripts/complexity_gate.py": False,
            ".github/scripts/complexity_gate_test.py": False,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual("duplication" in workflow_paths.classify([path]), expected)

    def test_generated_header_does_not_route_but_deleted_source_does(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            generated = root / "cmd/generated.go"
            generated.parent.mkdir()
            generated.write_text("// Code generated by tool. DO NOT EDIT.\npackage cmd\nfunc G() {}\n")
            self.assertNotIn("cmd/generated.go", erosion.discover_sources(root))
            self.assertNotIn("erosion", workflow_paths.classify(["cmd/generated.go"], root))
            self.assertIn("erosion", workflow_paths.classify(["cmd/deleted.go"], root))
            self.assertNotIn("duplication", workflow_paths.classify(["cmd/generated.go"], root))
            self.assertIn("duplication", workflow_paths.classify(["cmd/deleted.go"], root))

    def test_path_matrix(self):
        cases = {
            "cmd/qmix/main.go": {"ci", "erosion", "duplication"},
            "internal/server/server.go": {"ci", "erosion", "duplication"},
            "internal/stream/ytdlp.go": {"ci", "live", "erosion", "duplication"},
            "internal/buildinfo/buildinfo.go": {"ci", "android", "erosion", "duplication"},
            "go.mod": {"ci", "android", "live"},
            "go.sum": {"ci", "android", "live"},
            "Makefile": {"ci"},
            "Dockerfile": {"ci"},
            ".dockerignore": {"ci"},
            ".github/scripts/test_summary.py": {"ci"},
            ".github/scripts/generate_sbom.py": {"ci", "android"},
            ".github/workflows/ci.yml": {"ci", "erosion", "duplication"},
            ".github/workflows/android.yml": {"android"},
            ".github/workflows/live.yml": {"live"},
            ".github/scripts/workflow_paths.py": {"ci", "android", "live", "erosion", "duplication"},
            ".jscpd.json": {"duplication"},
            ".github/scripts/jscpd_report.py": {"ci", "duplication"},
            ".github/scripts/jscpd_report_test.py": {"ci", "duplication"},
            ".github/scripts/complexity_gate.py": {"ci", "erosion"},
            ".github/scripts/complexity_gate_test.py": {"ci", "erosion"},
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
            {"android", "ci", "erosion", "duplication"},
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
            ["ci=true", "android=false", "live=true", "erosion=true", "duplication=true"],
        )

    def test_workflows_share_isolated_concurrency_contract(self):
        group = (
            "group: ${{ github.workflow }}-${{ github.event_name }}-"
            "${{ github.event.pull_request.number || github.ref }}"
        )
        for path in list(WORKFLOW_DIR.glob("*.yml")) + list(WORKFLOW_DIR.glob("*.yaml")):
            with self.subTest(path=path.name):
                text = path.read_text()
                if path.name == "ci.yml":
                    self.assertIn(
                        "group: ${{ github.workflow }}-${{ github.event_name }}-"
                        "${{ github.event_name == 'pull_request' && github.event.pull_request.number || github.run_id }}",
                        text,
                    )
                    self.assertNotIn("github.event.pull_request.number || github.ref", text)
                    self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", text)
                    for name in ("erosion-history", "erosion-pages"):
                        block = self._job(text, name)
                        self.assertIn("queue: max", block)
                        self.assertIn("cancel-in-progress: false", block)
                else:
                    self.assertIn(group, text)
                    self.assertIn("cancel-in-progress: true", text)

    def test_main_push_forces_erosion_even_when_no_source_changed(self):
        for force, expected in (("true", "erosion=true"), ("false", "erosion=false")):
            completed = subprocess.run(
                [sys.executable, str(MODULE_PATH)], input=b"README.md\0",
                capture_output=True, check=True, env=os.environ | {"FORCE_EROSION": force},
            )
            with self.subTest(force=force):
                self.assertIn(expected, completed.stdout.decode().splitlines())
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        self.assertIn("FORCE_EROSION: ${{ github.event_name == 'push' }}", self._job(ci, "route"))

    def test_main_push_forces_duplication_even_when_no_source_changed(self):
        for force, expected in (("true", "duplication=true"), ("false", "duplication=false")):
            completed = subprocess.run(
                [sys.executable, str(MODULE_PATH)], input=b"README.md\0",
                capture_output=True, check=True, env=os.environ | {"FORCE_DUPLICATION": force},
            )
            with self.subTest(force=force):
                self.assertIn(expected, completed.stdout.decode().splitlines())
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        self.assertIn("FORCE_DUPLICATION: ${{ github.event_name == 'push' }}", self._job(ci, "route"))

    def test_complexity_gate_is_pr_only_and_reuses_erosion_route(self):
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        gate = self._job(ci, "complexity-gate")
        result = self._job(ci, "result")
        self.assertIn("needs: route", gate)
        self.assertIn("github.event_name == 'pull_request'", gate)
        self.assertIn("needs.route.outputs.erosion == 'true'", gate)
        self.assertIn("name: complexity-gate", gate)
        self.assertIn("contents: read", gate)
        self.assertNotIn("complexity-gate", result)  # Owner opts in to required status.
        self.assertNotIn("secrets.", gate)

    def test_complexity_gate_checkout_base_tests_summary_and_artifact(self):
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        gate = self._job(ci, "complexity-gate")
        self.assertIn("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1", gate)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha }}", gate)
        self.assertIn("fetch-depth: 0", gate)
        self.assertIn("persist-credentials: false", gate)
        self.assertIn("actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0", gate)
        self.assertIn("python-version: '3.13'", gate)
        self.assertIn("--require-hashes --no-deps -r .github/requirements-lizard.txt", gate)
        self.assertIn('git worktree add --detach "$RUNNER_TEMP/base" "$BASE_SHA"', gate)
        self.assertIn("BASE_SHA: ${{ github.event.pull_request.base.sha }}", gate)
        self.assertLess(gate.index("complexity_gate_test.py -v"), gate.index("complexity_gate.py "))
        for token in ('--base "$RUNNER_TEMP/base"', '--head .',
                      '--json "$RUNNER_TEMP/complexity-gate/report.json"',
                      '--markdown "$RUNNER_TEMP/complexity-gate/report.md"',
                      'cat "$RUNNER_TEMP/complexity-gate/report.md" >> "$GITHUB_STEP_SUMMARY"'):
            self.assertIn(token, gate)
        self.assertIn("if: always()", gate)
        self.assertIn("actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4", gate)
        self.assertIn("name: complexity-gate", gate)
        self.assertIn("if-no-files-found: error", gate)

    def test_complexity_gate_local_target_and_check_documentation(self):
        makefile = (REPOSITORY_ROOT / "Makefile").read_text()
        docs = (REPOSITORY_ROOT / "docs/code-quality.md").read_text()
        self.assertIn("test-complexity-gate:", makefile)
        self.assertIn("python3 .github/scripts/complexity_gate_test.py -v", makefile)
        self.assertRegex(makefile, r"test-workflow-routing:.*test-complexity-gate")
        self.assertIn("`complexity-gate`", docs)
        self.assertIn("CCN > 10", docs)
        self.assertIn("renamed", docs)
        self.assertIn("anonymous", docs)
        self.assertIn("branch protection", docs)

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
            self.assertEqual(workflow_paths.classify(paths), {"ci", "erosion", "duplication"})

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
                    self.assertEqual(output.read_text().splitlines(), expected + (["erosion=true", "duplication=true"] if filename == "ci.yml" else []))

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
                fallback = "printf 'ci=true\\nandroid=true\\nlive=true\\n"
                if filename == "ci.yml":
                    fallback += "erosion=true\\nduplication=true\\n"
                self.assertIn(fallback + "'", text)

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
                "EROSION_OUTPUT": "false",
                "EROSION_RESULT": "skipped",
                "DUPLICATION_OUTPUT": "false",
                "DUPLICATION_RESULT": "skipped",
                "EROSION_REPORT_RESULT": "skipped",
                "HISTORY_RESULT": "skipped",
                "PAGES_RESULT": "skipped",
                "REF": "refs/heads/main",
                "EVENT_NAME": "pull_request",
                "HEAD_REPOSITORY": "leugenea/qmix",
                "REPOSITORY": "leugenea/qmix",
                "ACTOR": "leugenea",
                "PR_AUTHOR": "leugenea",
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
            "EROSION_OUTPUT": "false",
            "EROSION_RESULT": "skipped",
            "DUPLICATION_OUTPUT": "false",
            "DUPLICATION_RESULT": "skipped",
            "EROSION_REPORT_RESULT": "skipped",
            "HISTORY_RESULT": "skipped",
            "PAGES_RESULT": "skipped",
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

    def test_ci_result_enforces_erosion_route_and_comment_permissions(self):
        script = self._result_script("ci.yml")
        base = {
            "ROUTE_RESULT": "success", "ROUTE_OUTPUT": "false",
            "POLICY_RESULT": "success", "CI_RESULT": "skipped",
            "INTEGRATION_RESULT": "skipped", "DOCKER_RESULT": "skipped",
            "REPORT_RESULT": "skipped", "EVENT_NAME": "pull_request",
            "DUPLICATION_OUTPUT": "false", "DUPLICATION_RESULT": "skipped",
            "HISTORY_RESULT": "skipped",
            "PAGES_RESULT": "skipped",
            "REPOSITORY": "leugenea/qmix", "HEAD_REPOSITORY": "leugenea/qmix",
            "ACTOR": "leugenea", "PR_AUTHOR": "leugenea",
        }
        for route, producer, comment, expected in (
            ("true", "success", "success", True),
            ("true", "success", "skipped", False),
            ("true", "failure", "skipped", False),
            ("false", "skipped", "skipped", True),
            ("false", "success", "skipped", False),
            ("invalid", "skipped", "skipped", False),
        ):
            env = base | {"EROSION_OUTPUT": route, "EROSION_RESULT": producer, "EROSION_REPORT_RESULT": comment}
            completed = subprocess.run(["bash", "-e", "-c", script], env=env)
            with self.subTest(route=route, producer=producer, comment=comment):
                self.assertEqual(completed.returncode == 0, expected)
        for actor, repository in (("dependabot[bot]", "leugenea/qmix"), ("contributor", "contributor/qmix")):
            env = base | {"EROSION_OUTPUT": "true", "EROSION_RESULT": "success",
                          "EROSION_REPORT_RESULT": "skipped", "ACTOR": actor, "HEAD_REPOSITORY": repository}
            completed = subprocess.run(["bash", "-e", "-c", script], env=env)
            with self.subTest(actor=actor, repository=repository):
                self.assertEqual(completed.returncode, 0)

        env = base | {"EROSION_OUTPUT": "true", "EROSION_RESULT": "success",
                      "EROSION_REPORT_RESULT": "skipped", "PR_AUTHOR": "dependabot[bot]"}
        self.assertEqual(subprocess.run(["bash", "-e", "-c", script], env=env).returncode, 0)

    def test_ci_result_enforces_duplication_for_prs_and_main_pushes(self):
        script = self._result_script("ci.yml")
        base = {
            "ROUTE_RESULT": "success", "POLICY_RESULT": "success",
            "ROUTE_OUTPUT": "false", "CI_RESULT": "skipped",
            "INTEGRATION_RESULT": "skipped", "DOCKER_RESULT": "skipped",
            "REPORT_RESULT": "skipped", "EROSION_OUTPUT": "false",
            "EROSION_RESULT": "skipped", "HISTORY_RESULT": "skipped",
            "PAGES_RESULT": "skipped", "REPOSITORY": "leugenea/qmix",
            "ACTOR": "leugenea", "PR_AUTHOR": "leugenea",
        }
        for event, route, producer, publisher, head, history, succeeds in (
            ("pull_request", "true", "success", "success", "leugenea/qmix", "skipped", True),
            ("pull_request", "true", "failure", "skipped", "leugenea/qmix", "skipped", False),
            ("pull_request", "true", "skipped", "skipped", "leugenea/qmix", "skipped", False),
            ("pull_request", "false", "skipped", "skipped", "leugenea/qmix", "skipped", True),
            ("pull_request", "true", "success", "skipped", "contributor/qmix", "skipped", True),
            ("pull_request", "true", "success", "success", "contributor/qmix", "skipped", False),
            ("push", "true", "skipped", "skipped", "", "skipped", False),
            ("push", "true", "success", "skipped", "", "success", False),
            ("push", "true", "success", "skipped", "", "failure", True),
            ("push", "false", "skipped", "skipped", "", "skipped", True),
            ("pull_request", "invalid", "skipped", "skipped", "leugenea/qmix", "skipped", False),
        ):
            env = base | {"EVENT_NAME": event, "DUPLICATION_OUTPUT": route,
                          "DUPLICATION_RESULT": producer, "EROSION_REPORT_RESULT": publisher,
                          "HEAD_REPOSITORY": head, "HISTORY_RESULT": history}
            completed = subprocess.run(["bash", "-e", "-c", script], env=env)
            with self.subTest(event=event, route=route, producer=producer, head=head, history=history):
                self.assertEqual(completed.returncode == 0, succeeds)

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

    def test_history_is_main_push_only_and_does_not_block_ci_result(self):
        script = self._result_script("ci.yml")
        base = {
            "ROUTE_RESULT": "success", "POLICY_RESULT": "success",
            "ROUTE_OUTPUT": "false", "CI_RESULT": "skipped",
            "INTEGRATION_RESULT": "skipped", "DOCKER_RESULT": "skipped",
            "REPORT_RESULT": "skipped", "EROSION_OUTPUT": "true",
            "EROSION_RESULT": "success", "EROSION_REPORT_RESULT": "skipped",
            "DUPLICATION_OUTPUT": "false", "DUPLICATION_RESULT": "skipped",
            "PAGES_RESULT": "skipped",
            "REPOSITORY": "leugenea/qmix", "HEAD_REPOSITORY": "",
            "ACTOR": "leugenea", "PR_AUTHOR": "",
        }
        for event, history, pages, succeeds in (
            ("push", "success", "success", True),
            ("push", "success", "failure", True),
            ("push", "success", "skipped", False),
            ("push", "success", "cancelled", False),
            ("push", "failure", "skipped", True),
            ("push", "skipped", "skipped", False),
            ("push", "cancelled", "skipped", False),
            ("pull_request", "skipped", "skipped", True),
            ("pull_request", "success", "skipped", False),
        ):
            # External PRs skip their privileged erosion comment publisher.
            env = base | {"EVENT_NAME": event, "HISTORY_RESULT": history,
                          "PAGES_RESULT": pages}
            if event == "pull_request":
                env["HEAD_REPOSITORY"] = "contributor/qmix"
            completed = subprocess.run(["bash", "-e", "-c", script], env=env)
            with self.subTest(event=event, history=history, pages=pages):
                self.assertEqual(completed.returncode == 0, succeeds)

    def test_history_runs_for_either_successful_main_report(self):
        script = self._result_script("ci.yml")
        base = {
            "ROUTE_RESULT": "success", "POLICY_RESULT": "success",
            "ROUTE_OUTPUT": "false", "CI_RESULT": "skipped",
            "INTEGRATION_RESULT": "skipped", "DOCKER_RESULT": "skipped",
            "REPORT_RESULT": "skipped", "EROSION_REPORT_RESULT": "skipped",
            "EROSION_OUTPUT": "false", "EROSION_RESULT": "skipped",
            "DUPLICATION_OUTPUT": "true", "DUPLICATION_RESULT": "success",
            "EVENT_NAME": "push", "REPOSITORY": "leugenea/qmix",
            "HEAD_REPOSITORY": "", "ACTOR": "leugenea", "PR_AUTHOR": "",
        }
        for producer, history, pages, expected in (
            ("success", "success", "success", True),
            ("success", "failure", "skipped", True),
            ("success", "skipped", "skipped", False),
            ("success", "cancelled", "skipped", False),
            ("skipped", "skipped", "skipped", False),
        ):
            env = base | {"DUPLICATION_RESULT": producer, "HISTORY_RESULT": history,
                          "PAGES_RESULT": pages}
            completed = subprocess.run(["bash", "-e", "-c", script], env=env)
            with self.subTest(producer=producer, history=history, pages=pages):
                self.assertEqual(completed.returncode == 0, expected)

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

    def test_comment_script_preserves_sections_across_partial_runs(self):
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        publisher = self._job(ci, "publish-erosion")
        run = re.search(r"(?m)^        run: \|\n(?P<script>(?:^          [^\n]*\n?)*)", publisher)
        self.assertIsNotNone(run)
        script = textwrap.dedent(run.group("script"))
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            for name in ("erosion", "duplication"):
                (root / name).mkdir()
                (root / name / "report.json").write_text("{}\n")
            stub = root / "gh"
            stub.write_text(textwrap.dedent('''\
                #!/usr/bin/env python3
                import json
                import os
                import pathlib
                import sys
                state = pathlib.Path(os.environ["STATE"])
                calls = pathlib.Path(os.environ["CALLS"])
                args = sys.argv[1:]
                if "--paginate" in args:
                    if state.exists():
                        print("42")
                elif "--method" in args:
                    method = args[args.index("--method") + 1]
                    body = args[args.index("-f") + 1].removeprefix("body=")
                    state.write_text(json.dumps({"id": 42, "body": body}))
                    with calls.open("a") as stream:
                        stream.write(method + "\\n")
                elif args[-1].endswith("/42") and state.exists():
                    print(state.read_text())
                else:
                    sys.exit(1)
            '''))
            stub.chmod(0o755)
            env = os.environ | {"PATH": f"{root}:{os.environ['PATH']}",
                                "STATE": str(root / "state.json"), "CALLS": str(root / "calls"),
                                "GITHUB_REPOSITORY": "leugenea/qmix", "PR_NUMBER": "200",
                                "GH_TOKEN": "local-fixture", "RUNNER_TEMP": str(root)}

            def publish(erosion, duplication):
                if erosion is not None:
                    (root / "erosion/report.md").write_text(
                        "<!-- qmix-lizard-erosion -->\n" + erosion + "\n")
                if duplication is not None:
                    (root / "duplication/report.md").write_text(
                        "<!-- qmix-jscpd-duplication -->\n" + duplication + "\n")
                result = subprocess.run(["bash", "-e", "-c", script], cwd=root,
                                        env=env | {"EROSION_READY": str(erosion is not None).lower(),
                                                   "DUPLICATION_READY": str(duplication is not None).lower()},
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                return json.loads((root / "state.json").read_text())["body"]

            body = publish("erosion v1", None)
            self.assertIn("erosion v1", body)
            self.assertNotIn("qmix-jscpd-duplication", body)
            body = publish(None, "duplication v1")
            self.assertIn("erosion v1", body)
            self.assertIn("duplication v1", body)
            body = publish("erosion v2", "duplication v2")
            self.assertIn("erosion v2", body)
            self.assertIn("duplication v2", body)
            self.assertNotIn("erosion v1", body)
            self.assertNotIn("duplication v1", body)
            self.assertEqual(body.count("<!-- qmix-lizard-erosion -->"), 1)
            self.assertEqual(body.count("<!-- qmix-jscpd-duplication -->"), 1)
            self.assertEqual((root / "calls").read_text().splitlines(), ["POST", "PATCH", "PATCH"])
            (root / "state.json").unlink()
            body = publish(None, "duplication first")
            self.assertNotIn("qmix-lizard-erosion", body)
            self.assertIn("duplication first", body)
            body = publish("erosion later", None)
            self.assertIn("duplication first", body)
            self.assertIn("erosion later", body)
            (root / "state.json").write_text(json.dumps({"id": 42, "body": "<!-- qmix-lizard-erosion -->\n"}))
            body = publish(None, "duplication after legacy placeholder")
            self.assertNotIn("qmix-lizard-erosion", body)
            self.assertIn("duplication after legacy placeholder", body)
            (root / "erosion/report.json").unlink()
            before = (root / "state.json").read_text()
            result = subprocess.run(["bash", "-e", "-c", script], cwd=root,
                                    env=env | {"EROSION_READY": "true", "DUPLICATION_READY": "false"},
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((root / "state.json").read_text(), before)

    def test_erosion_job_is_routed_unprivileged_and_publishes_artifact(self):
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        producer = self._job(ci, "erosion")
        self.assertIn("needs.route.outputs.erosion == 'true'", producer)
        self.assertIn("uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0", producer)
        self.assertIn("python-version: '3.13'", producer)
        self.assertIn('python -m venv "$RUNNER_TEMP/erosion-venv"', producer)
        self.assertIn(".github/requirements-lizard.txt", producer)
        self.assertIn("--require-hashes", producer)
        self.assertIn("$RUNNER_TEMP/erosion-venv/bin/python", producer)
        self.assertIn(".github/scripts/kotlin_complexity_test.py -v", producer)
        self.assertIn("name: code-erosion", producer)
        self.assertIn("if-no-files-found: error", producer)
        self.assertIn("$GITHUB_STEP_SUMMARY", producer)
        self.assertNotIn("pull-requests: write", producer)
        publisher = self._job(ci, "publish-erosion")
        self.assertIn("permissions:\n      pull-requests: write", publisher)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", publisher)
        self.assertIn("github.actor != 'dependabot[bot]'", publisher)
        self.assertIn("actions/download-artifact@", publisher)
        self.assertNotIn("actions/checkout@", publisher)
        self.assertIn("<!-- qmix-lizard-erosion -->", publisher)
        self.assertIn("gh api", publisher)
        self.assertIn("EROSION_RESULT", self._result_script("ci.yml"))

    def test_duplication_job_is_routed_verified_and_shares_safe_comment(self):
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        route = self._job(ci, "route")
        self.assertIn("duplication: ${{ steps.paths.outputs.duplication }}", route)
        producer = self._job(ci, "duplication")
        self.assertIn("needs.route.outputs.duplication == 'true'", producer)
        self.assertNotIn("github.event_name == 'pull_request'", producer)
        self.assertIn("persist-credentials: false", producer)
        self.assertNotIn("pull-requests: write", producer)
        self.assertIn("v5.3.2/jscpd-linux-x64-gnu.tar.gz", producer)
        self.assertIn("'97259f222ea7f6d51a0f2faa98ed5889430233e0fb8d2c129f23d84aff4b93b2' 'jscpd-linux-x64-gnu.tar.gz'", producer)
        self.assertLess(producer.index("sha256sum -c -"), producer.index("tar -xzf"))
        self.assertLess(producer.index("sha256sum -c -"), producer.index("./jscpd --version"))
        self.assertIn('"$RUNNER_TEMP/jscpd/jscpd"', producer)
        self.assertIn("--config .jscpd.json", producer)
        self.assertIn("--reporters json", producer)
        self.assertIn("--threshold 100 --fail-on-empty", producer)
        self.assertIn(".github/scripts/jscpd_report_test.py -v", producer)
        self.assertIn(".github/scripts/jscpd_report.py report ", producer)
        self.assertIn("name: code-duplication", producer)
        self.assertIn("duplication/report.json", producer)
        self.assertIn("duplication/report.md", producer)
        self.assertIn("if-no-files-found: error", producer)
        self.assertIn("$GITHUB_STEP_SUMMARY", producer)
        publisher = self._job(ci, "publish-erosion")
        self.assertIn("needs: [route, erosion, duplication]", publisher)
        self.assertIn("needs.duplication.result == 'success'", publisher)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", publisher)
        self.assertIn("github.actor != 'dependabot[bot]'", publisher)
        self.assertIn("github.event.pull_request.user.login != 'dependabot[bot]'", publisher)
        self.assertIn("name: code-duplication", publisher)
        self.assertNotIn("actions/checkout@", publisher)
        self.assertEqual(publisher.count("gh api --method POST"), 1)
        self.assertEqual(publisher.count("gh api --method PATCH"), 1)

    def test_history_starts_new_six_metric_series_without_overall(self):
        ci = (WORKFLOW_DIR / "ci.yml").read_text()
        history = self._job(ci, "erosion-history")
        self.assertIn("name: Code erosion", history)
        self.assertNotIn("name: Lizard erosion", history)
        self.assertIn("--input \"$RUNNER_TEMP/erosion/report.json\"", history)

    def _job(self, text, name):
        match = re.search(
            rf"(?ms)^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)", text
        )
        self.assertIsNotNone(match)
        return match.group("body")

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
