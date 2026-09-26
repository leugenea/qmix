#!/usr/bin/env python3
"""Contract tests for the jscpd baseline clone-gate renderer (qmix#202)."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("clone_gate.py")


class CloneGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.input = self.root / "jscpd-report.json"
        self.output = self.root / "report.json"
        self.markdown = self.root / "report.md"
        self.scan_status = self.root / "scan-status"
        self.repo = self.root / "checkout"
        self.repo.mkdir()

    def report(self, clones):
        return {"statistics": {"total": {"clones": len(clones), "lines": 100,
                "duplicatedLines": 10, "sources": 2, "percentage": 10,
                "newClones": sum(c.get("isNew") is True for c in clones)},
                "formats": {"go": {"clones": len(clones), "lines": 100,
                "duplicatedLines": 10, "sources": 2, "percentage": 10}}},
                "duplicates": clones}

    def clone(self, new):
        return {"isNew": new, "format": "go", "lines": 13, "tokens": 56,
                "firstFile": {"name": str(self.repo / "internal/a.go"), "start": 5, "end": 17},
                "secondFile": {"name": str(self.repo / "internal/b.go"), "start": 8, "end": 20}}

    def run_gate(self, data=None, scan_status=None):
        if data is not None:
            self.input.write_text(json.dumps(data), encoding="utf-8")
        if scan_status is None:
            scan_status = int(any(c.get("isNew") is True for c in data.get("duplicates", []))) if data else 0
        self.scan_status.write_text(f"{scan_status}\n", encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "--report", str(self.input),
                                 "--repo", str(self.repo), "--scan-status", str(self.scan_status),
                                 "--json", str(self.output),
                                 "--markdown", str(self.markdown)], capture_output=True, text=True)
        return result, json.loads(self.output.read_text()), self.markdown.read_text()

    def test_new_clone_fails_and_lists_relative_locations(self):
        result, data, markdown = self.run_gate(self.report([self.clone(False), self.clone(True)]))
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(data["counts"], {"total_clones": 2, "new_clones": 1})
        self.assertEqual(len(data["new_clones"]), 1)
        self.assertEqual(data["new_clones"][0]["firstFile"],
                         {"name": "internal/a.go", "start": 5, "end": 17})
        self.assertIn("| Format | First occurrence | Second occurrence | Lines | Tokens |", markdown)
        self.assertIn("| go | internal/a.go:5–17 | internal/b.go:8–20 | 13 | 56 |", markdown)
        self.assertNotIn(str(self.root), markdown)

    def test_existing_and_empty_reports_pass(self):
        for clones in ([self.clone(False)], []):
            with self.subTest(clones=clones):
                result, data, markdown = self.run_gate(self.report(clones))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(data["new_clones"], [])
                self.assertIn("Clone gate passed", markdown)

    def test_missing_or_malformed_report_fails_closed(self):
        for data in (None, {"statistics": {}}, self.report([{"format": "go"}]),
                     self.report([self.clone("true")])):
            with self.subTest(data=data):
                if self.input.exists():
                    self.input.unlink()
                result, output, markdown = self.run_gate(data)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("error", output)
                self.assertIn("Analysis failed", markdown)

    def test_nonstandard_json_constants_fail_closed(self):
        for value in (float("nan"), float("inf"), -float("inf")):
            for location in ("fragment", "statistics", "unused"):
                with self.subTest(value=str(value), location=location):
                    report = self.report([self.clone(False)])
                    if location == "fragment":
                        report["duplicates"][0]["firstFile"]["fragment"] = value
                    elif location == "statistics":
                        report["statistics"]["total"]["percentage"] = value
                    else:
                        report["unused"] = value
                    result, output, markdown = self.run_gate(report)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn("error", output)
                    self.assertEqual(output["counts"], {})
                    self.assertEqual(output["new_clones"], [])
                    self.assertIn("Analysis failed", markdown)
                    self.assertNotIn("Clone gate passed", markdown)
                    self.assertNotIn("Clone gate failed:", markdown)

    def test_backslash_before_pipe_in_path_keeps_markdown_columns(self):
        clone = self.clone(True)
        clone["firstFile"]["name"] = str(self.repo / "internal/a\\|b.go")
        result, _, markdown = self.run_gate(self.report([clone]))
        self.assertEqual(result.returncode, 1, result.stderr)
        row = next(line for line in markdown.splitlines() if line.startswith("| go |"))
        self.assertIn("internal/a&#92;&#124;b.go:5–17", row)
        self.assertEqual(len(row.split("|")), 7, row)

    def test_crashed_scan_or_disagreeing_exit_fails_closed(self):
        for clones, status in (([], 127), ([], 1), ([self.clone(True)], 0)):
            with self.subTest(status=status, clones=clones):
                result, output, markdown = self.run_gate(self.report(clones), scan_status=status)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("error", output)
                self.assertIn("Analysis failed", markdown)

    def test_out_of_scope_and_bad_ranges_fail_closed(self):
        for mutation in (lambda c: c["firstFile"].update(name=str(self.root / "other.go")),
                         lambda c: c["firstFile"].update(start=20, end=5)):
            clone = self.clone(True)
            mutation(clone)
            result, output, _ = self.run_gate(self.report([clone]))
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertIn("error", output)


if __name__ == "__main__":
    unittest.main()
