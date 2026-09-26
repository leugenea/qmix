#!/usr/bin/env python3
"""Tests for the informational jscpd report (qmix#200)."""
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

MODULE = pathlib.Path(__file__).with_name("jscpd_report.py")
SPEC = importlib.util.spec_from_file_location("jscpd_report", MODULE)
reporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reporter)

ROOT = pathlib.Path(__file__).resolve().parents[2]


def fixture():
    def clone(fmt, path, other, lines, tokens):
        return {"format": fmt, "lines": lines, "tokens": tokens,
                "firstFile": {"name": str(ROOT / path), "start": 4, "end": 4 + lines - 1},
                "secondFile": {"name": str(ROOT / other), "start": 20, "end": 20 + lines - 1},
                "fragment": "sample"}

    formats = {}
    for fmt, count in (("go", 2), ("kotlin", 1), ("javascript", 0)):
        formats[fmt] = {"percentage": count * 2.5, "clones": count,
                        "lines": 100, "duplicatedLines": count * 5, "sources": 2}
    return {"statistics": {"total": {"percentage": 2.5, "clones": 3,
                                      "lines": 300, "duplicatedLines": 15, "sources": 6},
                           "formats": formats},
            "duplicates": [clone("go", "internal/server/store.go", "internal/server/hub.go", 12, 80),
                           clone("kotlin", "android/app/src/main/java/Screen.kt",
                                 "android/app/src/main/java/Other.kt", 7, 55),
                           clone("go", "cmd/token-vk/main.go", "cmd/token-ym/main.go", 17, 60)]}


class JscpdReportTest(unittest.TestCase):
    def test_config_pins_owner_approved_minimum_without_raising_token_limit(self):
        config = json.loads((ROOT / ".jscpd.json").read_text(encoding="utf-8"))
        self.assertEqual(config["minLines"], 10)
        self.assertNotIn("minTokens", config)  # Retain jscpd's 50-token default.
        self.assertNotIn("mode", config)  # Retain jscpd's mild default.
        self.assertEqual(config["threshold"], 100)

    def test_normalizes_absolute_paths_and_renders_five_largest(self):
        data = fixture()
        data["duplicates"] += json.loads(json.dumps(data["duplicates"]))
        data["statistics"]["total"]["clones"] = 6
        data["statistics"]["formats"]["go"]["clones"] = 4
        data["statistics"]["formats"]["kotlin"]["clones"] = 2
        normalized = reporter.normalize(data, ROOT)
        self.assertEqual(normalized["duplicates"][0]["firstFile"]["name"], "internal/server/store.go")
        self.assertEqual(data["duplicates"][0]["firstFile"]["name"], str(ROOT / "internal/server/store.go"))
        text = reporter.render_markdown(normalized)
        self.assertIn("<!-- qmix-jscpd-duplication -->", text)
        self.assertIn("| Scope | Duplication, % | Clones |", text)
        self.assertIn("| Overall | 2.50% | 6 |", text)
        self.assertIn("| Go | 5.00% | 4 |", text)
        self.assertIn("| Kotlin | 2.50% | 2 |", text)
        self.assertIn("| JS | 0.00% | 0 |", text)
        self.assertLess(text.index("cmd/token-vk/main.go:4–20"), text.index("internal/server/store.go:4–15"))
        self.assertIn("internal/server/hub.go:20–31", text)
        self.assertEqual(text.count("↔"), 5)

    def test_missing_format_is_zero_in_normalized_report(self):
        data = fixture()
        del data["statistics"]["formats"]["javascript"]
        data["statistics"]["total"].update(sources=4, lines=200)
        normalized = reporter.normalize(data, ROOT)
        self.assertEqual(normalized["statistics"]["formats"]["javascript"], {
            "percentage": 0, "clones": 0, "lines": 0,
            "duplicatedLines": 0, "sources": 0,
        })
        self.assertIn("| JS | 0.00% | 0 |", reporter.render_markdown(normalized))
        self.assertNotIn("javascript", data["statistics"]["formats"])

    def test_rejects_malformed_or_unexpected_sources(self):
        for mutation in (lambda d: d.pop("statistics"),
                         lambda d: d["statistics"]["total"].update(percentage="bad"),
                         lambda d: d["statistics"]["total"].update(sources=0),
                         lambda d: d["statistics"]["formats"].update(rust={}),
                         lambda d: d["statistics"]["formats"].pop("go"),
                         lambda d: d["duplicates"][0]["firstFile"].update(name="/tmp/outside.go"),
                         lambda d: d["duplicates"][0]["firstFile"].update(start=0),
                         lambda d: d["duplicates"][0].update(lines=-1)):
            data = fixture()
            mutation(data)
            with self.subTest(data=data), self.assertRaises(ValueError):
                reporter.normalize(data, ROOT)

    def test_zero_clones_and_markdown_escaping(self):
        data = fixture()
        data["duplicates"] = []
        for row in [data["statistics"]["total"], *data["statistics"]["formats"].values()]:
            row["clones"] = row["duplicatedLines"] = row["percentage"] = 0
        normalized = reporter.normalize(data, ROOT)
        self.assertIn("No clones found.", reporter.render_markdown(normalized))
        data = fixture()
        data["duplicates"][0]["firstFile"]["name"] = str(ROOT / "internal/a|b.go")
        self.assertIn("a\\|b.go", reporter.render_markdown(reporter.normalize(data, ROOT)))

    def test_sources_cli_rejects_empty_tree_and_excludes_generated_headers(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            empty = subprocess.run([sys.executable, str(MODULE), "sources", str(root)], capture_output=True)
            self.assertNotEqual(empty.returncode, 0)
            (root / "cmd").mkdir()
            (root / "cmd" / "generated.go").write_text("// Code generated by tool. DO NOT EDIT.\npackage main\n")
            (root / "cmd" / "main.go").write_text("package main\n")
            result = subprocess.run([sys.executable, str(MODULE), "sources", str(root)], capture_output=True, check=True)
            self.assertEqual(result.stdout, b"cmd/main.go\0")

    def test_report_cli_writes_normalized_json_and_same_markdown(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            source, output, markdown = [base / filename for filename in ("raw.json", "report.json", "report.md")]
            source.write_text(json.dumps(fixture()))
            subprocess.run([sys.executable, str(MODULE), "report", str(source), str(output), str(markdown),
                            "--repo", str(ROOT)], check=True)
            data = json.loads(output.read_text())
            self.assertEqual(data["statistics"]["total"]["clones"], 3)
            self.assertEqual(markdown.read_text(), reporter.render_markdown(data))
            self.assertEqual(data["duplicates"][0]["firstFile"]["name"], "internal/server/store.go")


if __name__ == "__main__":
    unittest.main()
