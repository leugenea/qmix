import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_cyrillic.py")


def load_check_module():
    spec = importlib.util.spec_from_file_location("check_cyrillic", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CyrillicCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_tracked(self, relative_path: str, content: str) -> None:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        subprocess.run(["git", "add", relative_path], cwd=self.root, check=True)

    def run_check(self, allowlist: dict | None = None) -> subprocess.CompletedProcess[str]:
        allowlist_path = self.root / "allowlist.json"
        allowlist_path.write_text(
            json.dumps(
                allowlist
                or {"version": 1, "ignored_paths": [], "allowed_lines": []}
            ),
            encoding="utf-8",
        )
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--root",
                str(self.root),
                "--allowlist",
                str(allowlist_path),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_reports_unexpected_cyrillic_in_tracked_text(self):
        self.write_tracked("README.md", "English\n\u041d\u0435\u043e\u0436\u0438\u0434\u0430\u043d\u043d\u044b\u0439 \u0442\u0435\u043a\u0441\u0442\n")

        result = self.run_check()

        self.assertEqual(1, result.returncode)
        self.assertIn("README.md:2", result.stdout)
        self.assertIn("Unexpected Cyrillic text", result.stdout)

        self.write_tracked("README.md", "English only\n")
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_allows_only_real_android_locale_string_resources(self):
        for directory in (
            "values-ru",
            "values-ru-rRU",
            "values-b+ru+RU",
            "values-ru-rRU-night",
            "values-b+ru+RU-sw600dp-land",
            "values-ru-1920x1080-v35",
            "values-mcc310-en-rUS",
            "values-mcc310-mnc260-b+ru+RU-night",
        ):
            with self.subTest(directory=directory):
                check = load_check_module()
                self.assertTrue(
                    check.is_android_locale_string_resource(
                        f"android/app/src/main/res/{directory}/strings.xml"
                    )
                )

    def test_rejects_non_locale_android_value_qualifiers(self):
        check = load_check_module()

        for directory in (
            "values-night",
            "values-v35",
            "values-land",
            "values-car",
            "values-xx",
            "values-b+ru+ABCD",
            "values-ru-sw0dp",
            "values-ru-0x0",
            "values-ru-v0",
            "values-ru-320x480",
            "values-not-a-locale",
            "values-night-ru",
            "values-ru-night-sw600dp",
        ):
            with self.subTest(directory=directory):
                self.assertFalse(
                    check.is_android_locale_string_resource(
                        f"android/app/src/main/res/{directory}/strings.xml"
                    )
                )

    def test_non_locale_android_value_resources_remain_fail_closed(self):
        for directory in ("values-night", "values-v35", "values-land", "values-not-a-locale"):
            self.write_tracked(
                f"android/app/src/main/res/{directory}/strings.xml",
                "<resources><string name=\"hello\">\u041f\u0440\u0438\u0432\u0435\u0442</string></resources>\n",
            )

        result = self.run_check()

        self.assertEqual(1, result.returncode)
        for directory in ("values-night", "values-v35", "values-land", "values-not-a-locale"):
            self.assertIn(f"android/app/src/main/res/{directory}/strings.xml:1", result.stdout)

    def test_rejects_boolean_occurrence_count(self):
        text = "\u041d\u0430\u043c\u0435\u0440\u0435\u043d\u043d\u0430\u044f \u0444\u0438\u043a\u0441\u0442\u0443\u0440\u0430"
        self.write_tracked("testdata/international.txt", text + "\n")
        allowlist = {
            "version": 1,
            "ignored_paths": [],
            "allowed_lines": [
                {
                    "path": "testdata/international.txt",
                    "text": text,
                    "occurrences": True,
                    "reason": "Exercises international text handling.",
                }
            ],
        }

        result = self.run_check(allowlist)

        self.assertEqual(2, result.returncode)
        self.assertIn("positive occurrences", result.stderr)

    def test_rejects_duplicate_allowlist_entries(self):
        text = "\u041d\u0430\u043c\u0435\u0440\u0435\u043d\u043d\u0430\u044f \u0444\u0438\u043a\u0441\u0442\u0443\u0440\u0430"
        self.write_tracked("testdata/international.txt", text + "\n")
        entry = {
            "path": "testdata/international.txt",
            "text": text,
            "occurrences": 1,
            "reason": "Exercises international text handling.",
        }
        allowlist = {
            "version": 1,
            "ignored_paths": [],
            "allowed_lines": [entry, entry],
        }

        result = self.run_check(allowlist)

        self.assertEqual(2, result.returncode)
        self.assertIn("Duplicate allowed line", result.stderr)

    def test_detects_extended_cyrillic_blocks(self):
        self.write_tracked("README.md", "Extended character: \ua640\n")

        result = self.run_check()

        self.assertEqual(1, result.returncode)
        self.assertIn("README.md:1", result.stdout)

    def test_allows_only_the_exact_documented_line(self):
        allowed = "\u041d\u0430\u043c\u0435\u0440\u0435\u043d\u043d\u0430\u044f \u0444\u0438\u043a\u0441\u0442\u0443\u0440\u0430"
        self.write_tracked("testdata/international.txt", allowed + "\nEnglish\n")
        allowlist = {
            "version": 1,
            "ignored_paths": [],
            "allowed_lines": [
                {
                    "path": "testdata/international.txt",
                    "text": allowed,
                    "occurrences": 1,
                    "reason": "Exercises international text handling.",
                }
            ],
        }

        result = self.run_check(allowlist)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("No unexpected Cyrillic text", result.stdout)

        self.write_tracked(
            "testdata/international.txt",
            allowed + "\n\u041d\u043e\u0432\u0430\u044f \u0441\u0442\u0440\u043e\u043a\u0430\n",
        )
        result = self.run_check(allowlist)
        self.assertEqual(1, result.returncode)
        self.assertIn("testdata/international.txt:2", result.stdout)

    def test_rejects_duplicate_occurrences_of_an_allowed_line(self):
        allowed = "\u041d\u0430\u043c\u0435\u0440\u0435\u043d\u043d\u0430\u044f \u0444\u0438\u043a\u0441\u0442\u0443\u0440\u0430"
        self.write_tracked("testdata/international.txt", allowed + "\n" + allowed + "\n")
        allowlist = {
            "version": 1,
            "ignored_paths": [],
            "allowed_lines": [
                {
                    "path": "testdata/international.txt",
                    "text": allowed,
                    "occurrences": 1,
                    "reason": "Exercises international text handling.",
                }
            ],
        }

        result = self.run_check(allowlist)

        self.assertEqual(2, result.returncode)
        self.assertIn("allowlisted line count", result.stderr)

    def test_rejects_allowlisting_maintained_documentation(self):
        text = "\u041d\u0435 \u0444\u0438\u043a\u0441\u0442\u0443\u0440\u0430"
        self.write_tracked("README.md", text + "\n")
        allowlist = {
            "version": 1,
            "ignored_paths": [],
            "allowed_lines": [
                {
                    "path": "README.md",
                    "text": text,
                    "occurrences": 1,
                    "reason": "This must not bypass translation.",
                }
            ],
        }

        result = self.run_check(allowlist)

        self.assertEqual(2, result.returncode)
        self.assertIn("test or fixture path", result.stderr)

    def test_ignores_only_documented_generated_paths(self):
        check = load_check_module()
        check.GENERATED_PATHS = {"generated/report.json"}
        self.write_tracked("generated/report.json", "\u0421\u0433\u0435\u043d\u0435\u0440\u0438\u0440\u043e\u0432\u0430\u043d\u043e\n")
        allowlist_path = self.root / "allowlist.json"
        allowlist_path.write_text(
            json.dumps(
                {
                    "version": 1,
                    "ignored_paths": [
                        {
                            "path": "generated/report.json",
                            "reason": "Generated by an external tool.",
                        }
                    ],
                    "allowed_lines": [],
                }
            ),
            encoding="utf-8",
        )
        paths = check.tracked_files(self.root)

        ignored_paths, allowed_lines = check.load_allowlist(allowlist_path)
        findings, _ = check.unexpected_lines(self.root, paths, ignored_paths, allowed_lines)
        unignored, _ = check.unexpected_lines(self.root, paths, set(), allowed_lines)

        self.assertEqual({"generated/report.json"}, ignored_paths)
        self.assertEqual([], findings)
        self.assertEqual(["generated/report.json"], [path for path, _, _ in unignored])

    def test_rejects_ignoring_a_maintained_path(self):
        self.write_tracked("README.md", "\u041d\u0435 \u0438\u0433\u043d\u043e\u0440\u0438\u0440\u043e\u0432\u0430\u0442\u044c\n")
        allowlist = {
            "version": 1,
            "ignored_paths": [
                {"path": "README.md", "reason": "This must not bypass translation."}
            ],
            "allowed_lines": [],
        }

        result = self.run_check(allowlist)

        self.assertEqual(2, result.returncode)
        self.assertIn("generated path", result.stderr)

    def test_untracked_files_are_out_of_scope(self):
        (self.root / "scratch.txt").write_text("\u041d\u0435 \u043e\u0442\u0441\u043b\u0435\u0436\u0438\u0432\u0430\u0435\u0442\u0441\u044f\n", encoding="utf-8")

        result = self.run_check()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
