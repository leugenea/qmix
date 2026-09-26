#!/usr/bin/env python3
"""Fixture tests for the informational hotspot ranking (qmix#242)."""
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import hotspots

SCRIPT = Path(__file__).with_name("hotspots.py")
GO = "cmd/a.go"
OTHER = "internal/b.go"
KT = "android/app/src/main/java/A.kt"
JS = "internal/server/guest/a.js"
NOW = "2026-09-26T12:00:00+00:00"


def function(path, language, name, complexity=11, nloc=4):
    return {"id": f"{path}::{name}", "file": path, "language": language,
            "function": name, "ccn": complexity, "nloc": nloc,
            "mass": complexity * math.sqrt(nloc), "start": 1, "end": 4}


def clone(first, second, lines, start=1):
    return {"format": "go", "lines": lines, "tokens": 60,
            "firstFile": {"name": first, "start": start, "end": start + lines - 1},
            "secondFile": {"name": second, "start": start + 20, "end": start + 20 + lines - 1}}


class HotspotsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", "-b", "main", str(self.root)], check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.root, check=True)
        self.erosion = self.root / "erosion.json"
        self.duplication = self.root / "duplication.json"
        self.notes = self.root / "docs/code-quality.md"
        self.notes.parent.mkdir()
        self.notes.write_text("# Quality\n\n## Hotspots left as is\n\nOwner maintains this list.\n")
        self.rows = []
        self.clones = []

    def commit(self, date, *paths):
        for path in paths:
            destination = self.root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(destination.read_text() + "change\n" if destination.exists() else "source\n")
        subprocess.run(["git", "add", "--", *paths], cwd=self.root, check=True)
        environment = {**os.environ, "GIT_AUTHOR_DATE": date, "GIT_COMMITTER_DATE": date}
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.root, env=environment, check=True)

    def reports(self):
        self.erosion.write_text(json.dumps({"schema_version": 2, "functions": self.rows}))
        counts = {"go": 0, "kotlin": 0, "javascript": 0}
        for item in self.clones:
            counts[item["format"]] += 1
        self.duplication.write_text(json.dumps({"schema_version": 1,
            "statistics": {"total": {"clones": len(self.clones)},
                           "formats": {key: {"clones": value} for key, value in counts.items()}},
            "duplicates": self.clones}))

    def run_script(self, *options):
        result = subprocess.run([sys.executable, str(SCRIPT), "--repo", str(self.root),
            "--erosion", str(self.erosion), "--duplication", str(self.duplication),
            "--skip-list", str(self.notes), "--now", NOW,
            "--json", str(self.root / "ranked.json"), "--markdown", str(self.root / "ranked.md"),
            *options], text=True, capture_output=True)
        data = json.loads((self.root / "ranked.json").read_text()) if (self.root / "ranked.json").exists() else None
        text = (self.root / "ranked.md").read_text() if (self.root / "ranked.md").exists() else None
        return result, data, text

    def test_churn_window_and_cold_entries_follow_ranked(self):
        self.commit("2026-06-01T00:00:00+00:00", GO)
        self.commit("2026-09-01T00:00:00+00:00", OTHER)
        self.commit("2026-09-10T00:00:00+00:00", OTHER)
        self.rows = [function(GO, "Go", "cold", complexity=40),
                     function(OTHER, "Go", "hot"),
                     function(OTHER, "Go", "boundary", complexity=10)]
        self.reports()
        result, data, text = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(row["function"], row["status"], row["rank"], row["churn"])
                          for row in data["languages"]["Go"]],
                         [("hot", "ranked", 1, 2), ("cold", "cold", None, 0)])
        self.assertEqual(data["languages"]["Go"][0]["score"], 44)
        self.assertLess(text.index("hot"), text.index("cold"))
        self.assertEqual(data["window_days"], 90)
        self.assertEqual(data["ref"], "main")

    def test_churn_counts_newer_commits_after_older_dated_ancestor(self):
        self.commit("2026-09-10T00:00:00+00:00", GO)
        self.commit("2026-01-01T00:00:00+00:00", GO)
        self.commit("2026-09-20T00:00:00+00:00", GO)
        self.rows = [function(GO, "Go", "hot")]
        self.reports()
        result, data, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(data["languages"]["Go"][0]["churn"], 2)

    def test_per_language_ranking_and_configurable_ref_window(self):
        self.commit("2026-09-01T00:00:00+00:00", GO, KT, JS)
        self.commit("2026-09-23T00:00:00+00:00", GO, KT, JS)
        self.commit("2026-09-24T00:00:00+00:00", GO)
        self.rows = [function(GO, "Go", "lower", complexity=11),
                     function(KT, "Kotlin", "kt", complexity=30),
                     function(JS, "JS", "js", complexity=12),
                     function(GO, "Go", "higher", complexity=20)]
        self.reports()
        result, data, text = self.run_script("--ref", "HEAD", "--days", "7")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(row["function"], row["rank"], row["score"])
                          for row in data["languages"]["Go"]],
                         [("higher", 1, 80), ("lower", 2, 44)])
        self.assertEqual({lang: [(row["function"], row["rank"], row["churn"])
                                for row in data["languages"][lang]]
                          for lang in ("Kotlin", "JS")},
                         {"Kotlin": [("kt", 1, 1)], "JS": [("js", 1, 1)]})
        self.assertIn("Kotlin", text)

    def test_ref_cannot_inject_git_log_options(self):
        self.commit("2026-09-01T00:00:00+00:00", GO)
        self.commit("2026-09-02T00:00:00+00:00", GO)
        self.rows = [function(GO, "Go", "hot")]
        self.reports()
        result, _, _ = self.run_script("--ref=--max-count=1")
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_low_ccn_rows_require_function_and_valid_range(self):
        self.commit("2026-09-01T00:00:00+00:00", GO)
        for field, value in (("function", None), ("start", 0), ("end", 0)):
            with self.subTest(field=field):
                row = function(GO, "Go", "below-threshold", complexity=10)
                if value is None:
                    del row[field]
                else:
                    row[field] = value
                self.rows = [row]
                self.reports()
                result, _, _ = self.run_script()
                self.assertEqual(result.returncode, 2, result.stderr)

    def test_clone_groups_sum_lines_and_use_maximum_file_churn(self):
        self.commit("2026-09-01T00:00:00+00:00", GO, OTHER)
        self.commit("2026-09-02T00:00:00+00:00", GO)
        self.commit("2026-09-03T00:00:00+00:00", GO)
        self.clones = [clone(GO, OTHER, 12), clone(OTHER, GO, 10, start=40)]
        self.reports()
        result, data, text = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        item = data["duplication"][0]
        self.assertEqual((item["files"], item["lines"], item["churn"], item["score"], item["rank"]),
                         ([GO, OTHER], 22, 3, 66, 1))
        self.assertEqual(len(item["locations"]), 2)
        self.assertIn("22", text)

    def test_clone_locations_are_identical_after_reordering_and_swapping_sides(self):
        self.commit("2026-09-01T00:00:00+00:00", GO, OTHER)
        first = clone(GO, OTHER, 12, start=1)
        second = clone(OTHER, GO, 10, start=40)
        self.clones = [first, second]
        self.reports()
        result, _, markdown = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        original_json = (self.root / "ranked.json").read_text()

        self.clones = [{**row, "firstFile": row["secondFile"], "secondFile": row["firstFile"]}
                       for row in (second, first)]
        self.reports()
        result, data, permuted_markdown = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "ranked.json").read_text(), original_json)
        self.assertEqual(permuted_markdown, markdown)
        self.assertEqual(data["duplication"][0]["locations"], [
            [{"file": GO, "start": 1, "end": 12},
             {"file": OTHER, "start": 21, "end": 32}],
            [{"file": GO, "start": 60, "end": 69},
             {"file": OTHER, "start": 40, "end": 49}]])

    def test_zero_churn_clone_is_cold_after_ranked_clone(self):
        self.commit("2026-06-01T00:00:00+00:00", GO)
        self.commit("2026-09-01T00:00:00+00:00", OTHER)
        self.clones = [clone(GO, GO, 30), clone(OTHER, OTHER, 12)]
        self.reports()
        result, data, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(row["files"], row["status"], row["rank"], row["score"])
                          for row in data["duplication"]],
                         [([OTHER], "ranked", 1, 12), ([GO], "cold", None, 0)])

    def test_skip_list_marks_function_and_clone_without_ranking(self):
        self.commit("2026-09-01T00:00:00+00:00", GO, OTHER)
        self.rows = [function(GO, "Go", "keep"), function(OTHER, "Go", "rank")]
        self.clones = [clone(OTHER, GO, 12)]
        self.notes.write_text("# Quality\n\n## Hotspots left as is\n\n"
            "- `cmd/a.go::keep` — owner-approved function\n"
            "- clone: `internal/b.go, cmd/a.go` — owner-approved duplication\n"
            "\n## Next section\n- `internal/b.go::rank` — not a skip\n")
        self.reports()
        result, data, text = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(row["function"], row["status"], row["rank"])
                          for row in data["languages"]["Go"]],
                         [("rank", "ranked", 1), ("keep", "skipped", None)])
        self.assertEqual(data["duplication"][0]["status"], "skipped")
        self.assertIsNone(data["duplication"][0]["rank"])
        self.assertIn("owner-approved function", text)
        self.assertIn("owner-approved duplication", text)

    def test_missing_and_malformed_reports_return_2_but_findings_do_not(self):
        self.commit("2026-09-01T00:00:00+00:00", GO)
        self.rows = [function(GO, "Go", "found")]
        self.reports()
        for missing in (self.erosion, self.duplication, self.notes):
            original = missing.read_text()
            missing.unlink()
            result, _, _ = self.run_script()
            self.assertEqual(result.returncode, 2, result.stderr)
            missing.write_text(original)
        for path in (self.erosion, self.duplication):
            original = path.read_text()
            path.write_text('{broken')
            result, _, _ = self.run_script()
            self.assertEqual(result.returncode, 2, result.stderr)
            path.write_text(original)
        result, data, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(data["languages"]["Go"][0]["status"], "ranked")

    def test_valid_json_with_wrong_top_level_shape_returns_2(self):
        self.commit("2026-09-01T00:00:00+00:00", GO)
        self.reports()
        self.erosion.write_text("[]")
        result, _, _ = self.run_script()
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_scope_rejects_unselected_source_in_reports(self):
        self.commit("2026-09-01T00:00:00+00:00", GO)
        self.rows = [function("cmd/a_test.go", "Go", "excluded")]
        self.reports()
        result, _, _ = self.run_script()
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("scope", result.stderr.lower())


if __name__ == "__main__":
    unittest.main()
