#!/usr/bin/env python3
"""End-to-end source-tree fixtures for the qmix#199 complexity gate."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("complexity_gate.py")
GO = "internal/complexity.go"
KOTLIN = "android/app/src/main/java/Complexity.kt"
JS = "internal/server/guest/complexity.js"


def source(language, name="complex", decisions=10):
    """Make a body-bearing function whose CCN is exactly decisions + 1."""
    branches = {
        "Go": lambda: "    if true { println(1) }\n",
        "Kotlin": lambda: "    if (true) println(1)\n",
        "JS": lambda: "    if (true) console.log(1);\n",
    }
    opening, closing = {
        "Go": (f"package complexity\nfunc {name}() {{\n", "}\n"),
        "Kotlin": (f"fun {name}() {{\n", "}\n"),
        "JS": (f"function {name}() {{\n", "}\n"),
    }[language]
    return opening + branches[language]() * decisions + closing


class ComplexityGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base, self.head = self.root / "base", self.root / "head"
        self.base.mkdir()
        self.head.mkdir()

    def put(self, tree, path, content):
        destination = tree / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")

    def run_gate(self):
        report = self.root / "report.json"
        markdown = self.root / "report.md"
        result = subprocess.run([sys.executable, str(SCRIPT), "--base", str(self.base),
                                 "--head", str(self.head), "--json", str(report),
                                 "--markdown", str(markdown)], capture_output=True, text=True)
        return result, json.loads(report.read_text()) if report.exists() else None, (
            markdown.read_text() if markdown.exists() else None)

    def test_added_high_go_kotlin_js_fail_with_actionable_summary(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN), ("JS", JS)):
            with self.subTest(language=language):
                self.put(self.head, path, source(language))
                result, report, markdown = self.run_gate()
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(report["counts"]["added"], 1)
                self.assertEqual(report["findings"][0]["reason"], "added")
                self.assertIn(path, markdown)
                self.assertIn("complex", markdown)
                self.assertIn("— → 11", markdown)
                (self.head / path).unlink()

    def test_existing_worsened_high_fails(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN)):
            with self.subTest(language=language):
                self.put(self.base, path, source(language, decisions=9))
                self.put(self.head, path, source(language, decisions=10))
                result, report, markdown = self.run_gate()
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(report["findings"][0]["reason"], "worsened")
                self.assertIn("10 → 11", markdown)
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_worsened_at_limit_passes(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN)):
            with self.subTest(language=language):
                self.put(self.base, path, source(language, decisions=8))
                self.put(self.head, path, source(language, decisions=9))
                result, report, markdown = self.run_gate()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["findings"], [])
                self.assertIn("passed", markdown.lower())
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_high_unchanged_passes(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN), ("JS", JS)):
            with self.subTest(language=language):
                text = source(language)
                self.put(self.base, path, text)
                self.put(self.head, path, text)
                result, report, _ = self.run_gate()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["counts"]["matched_by_id"], 1)
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_high_improved_passes(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN)):
            with self.subTest(language=language):
                self.put(self.base, path, source(language, decisions=11))
                self.put(self.head, path, source(language, decisions=10))
                result, report, _ = self.run_gate()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["findings"], [])
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_rename_without_increase_passes(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN)):
            with self.subTest(language=language):
                self.put(self.base, path, source(language, "original"))
                self.put(self.head, path, source(language, "renamed"))
                result, report, _ = self.run_gate()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["counts"]["paired_by_similarity"], 1)
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_move_to_another_file_without_increase_passes(self):
        for language, former, moved in (("Go", GO, "internal/moved.go"),
                                        ("Kotlin", KOTLIN, "android/app/src/main/java/Moved.kt")):
            with self.subTest(language=language):
                self.put(self.base, former, source(language))
                self.put(self.head, moved, source(language))
                result, report, _ = self.run_gate()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["counts"]["paired_by_similarity"], 1)
                (self.base / former).unlink()
                (self.head / moved).unlink()

    def test_rename_and_increase_fails_as_renamed_worsened(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN)):
            with self.subTest(language=language):
                self.put(self.base, path, source(language, "old", decisions=10))
                self.put(self.head, path, source(language, "new", decisions=11))
                result, report, markdown = self.run_gate()
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(report["findings"][0]["reason"], "renamed+worsened")
                self.assertIn("11 → 12", markdown)
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_split_lowering_ccn_passes(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN)):
            with self.subTest(language=language):
                self.put(self.base, path, source(language, decisions=11))
                split = source(language, decisions=5)
                extracted = source(language, "extracted", decisions=6)
                if language == "Go":
                    extracted = extracted.replace("package complexity\n", "", 1)
                self.put(self.head, path, split + extracted)
                result, report, _ = self.run_gate()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["findings"], [])
                (self.base / path).unlink()
                (self.head / path).unlink()

    def test_unrelated_deletion_cannot_mask_new_high_function(self):
        for language, old, new in (("Go", GO, "internal/unrelated.go"),
                                   ("Kotlin", KOTLIN, "android/app/src/main/java/Other.kt")):
            with self.subTest(language=language):
                self.put(self.base, old, source(language, "original", decisions=12))
                unrelated = source(language, "unrelated", decisions=10).replace(
                    "if true { println(1) }" if language == "Go" else "if (true) println(1)",
                    "if false { panic(\"entirely different logic\") }" if language == "Go" else
                    'if (false) error("entirely different logic")')
                self.put(self.head, new, unrelated)
                result, report, _ = self.run_gate()
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(report["findings"][0]["reason"], "added")
                (self.base / old).unlink()
                (self.head / new).unlink()

    def test_anonymous_new_high_fails_and_unchanged_passes(self):
        anonymous = "(function () {\n" + "    if (true) console.log(1);\n" * 10 + "})();\n"
        self.put(self.head, JS, anonymous)
        result, report, _ = self.run_gate()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(report["counts"]["anonymous"], 1)
        self.assertTrue(report["findings"][0]["anonymous"])
        self.put(self.base, JS, anonymous)
        result, report, _ = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["counts"]["paired_by_similarity"], 1)
        self.assertEqual(report["counts"]["matched_by_id"], 0)

    def test_multiple_offenders_are_all_reported(self):
        for language, path in (("Go", GO), ("Kotlin", KOTLIN), ("JS", JS)):
            self.put(self.head, path, source(language))
        result, report, markdown = self.run_gate()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(len(report["findings"]), 3)
        for path in (GO, KOTLIN, JS):
            self.assertIn(path, markdown)
        self.assertIn("3 offending function(s)", markdown)

    def test_one_line_kotlin_expression_rename_matches_its_body(self):
        expression = ' && '.join(['true'] * 11)
        self.put(self.base, KOTLIN, f"fun old() = {expression}\n")
        self.put(self.head, KOTLIN, f"fun new() = {expression}\n")
        result, report, _ = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["counts"]["paired_by_similarity"], 1)

    def test_anonymous_below_threshold_is_ignored(self):
        self.put(self.head, JS, "(function () { if (true) console.log(1); })();\n")
        result, report, _ = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["counts"]["anonymous"], 1)
        self.assertEqual(report["findings"], [])

    def test_noop_empty_trees_pass(self):
        result, report, markdown = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["findings"], [])
        self.assertEqual(report["counts"]["added"], 0)
        self.assertIn("passed", markdown.lower())

    def test_kotlin_parse_error_returns_2_not_empty_success(self):
        self.put(self.head, KOTLIN, "fun broken( = 1\n")
        result, _, markdown = self.run_gate()
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("parse error", (result.stderr + (markdown or "")).lower())

    def test_unwritable_report_location_returns_2(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--base", str(self.base),
                                 "--head", str(self.head), "--json", str(self.root / "missing" / "report.json"),
                                 "--markdown", str(self.root / "report.md")], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_missing_required_cli_argument_returns_2(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--base", str(self.base)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
