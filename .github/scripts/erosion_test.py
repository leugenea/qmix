#!/usr/bin/env python3
"""Contract tests for the informational lizard report (qmix#197)."""

import importlib.util
import json
import math
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).with_name("erosion.py")
SPEC = importlib.util.spec_from_file_location("erosion", SCRIPT)
erosion = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(erosion)


class ErosionTest(unittest.TestCase):
    def test_weighted_erosion_threshold_and_language_split(self):
        csv_text = (
            'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
            '4,10,0,0,0,"",cmd/qmix/a.go,low,"",1,4\n'
            '9,11,0,0,0,"",cmd/qmix/a.go,high,"",5,13\n'
            '1,1,0,0,0,"",internal/server/guest/app.js,js,"",1,1\n'
        )
        allowed = {"cmd/qmix/a.go", "internal/server/guest/app.js"}
        report = erosion.calculate(erosion.parse_csv(csv_text, allowed))
        self.assertNotIn("overall", report)
        self.assertEqual(report["schema_version"], 2)
        self.assertEqual(report["languages"]["Go"]["high_ccn_count"], 1)
        self.assertAlmostEqual(report["languages"]["Go"]["erosion"], 33 / 53)
        self.assertEqual(report["languages"]["Kotlin"]["erosion"], 0)
        self.assertEqual(report["languages"]["JS"]["function_count"], 1)
        self.assertEqual(report["functions"][1]["mass"], 11 * math.sqrt(9))

    def test_csv_preserves_qualified_identity_and_quoted_signature(self):
        csv_text = (
            'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
            '4,2,21,2,4,"render@10-13@internal/server/guest/app.js",'
            'internal/server/guest/app.js,render,"Widget.render(a, b)",10,13\n'
            '3,1,12,1,3,"render@25-27@internal/server/guest/app.js",'
            'internal/server/guest/app.js,render,Other.render(x),25,27\n'
        )
        report = erosion.calculate(erosion.parse_csv(csv_text, {"internal/server/guest/app.js"}))
        first, second = report["functions"]
        self.assertEqual(report["schema_version"], 2)
        self.assertEqual(first["long_name"], "Widget.render(a, b)")
        self.assertIs(first["anonymous"], False)
        self.assertIs(second["anonymous"], False)
        self.assertEqual(first["function"], second["function"])
        self.assertNotEqual(first["id"], second["id"])
        self.assertEqual(first["id"], "internal/server/guest/app.js::Widget.render(a, b)")
        self.assertEqual((first["start"], first["end"], first["token"], first["params"]), (10, 13, 21, 2))

    def test_anonymous_flag_uses_lizard_short_name_and_is_serialized(self):
        csv_text = (
            'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
            '1,1,3,0,1,"",cmd/a.go,,func,1,1\n'
            '1,1,3,0,1,"",internal/server/guest/other.js,(anonymous),(anonymous),2,2\n'
            '1,1,3,0,1,"",internal/server/guest/app.js,(anonymous),(anonymous),3,3\n'
            '1,1,3,0,1,"",cmd/a.go,named,named(x),4,4\n'
        )
        allowed = {"cmd/a.go", "internal/server/guest/other.js", "internal/server/guest/app.js"}
        report = erosion.calculate(erosion.parse_csv(csv_text, allowed))
        self.assertEqual(report["schema_version"], 2)
        self.assertEqual([row["anonymous"] for row in report["functions"]],
                         [True, True, True, False])
        encoded = json.loads(json.dumps(report))
        self.assertEqual([row["anonymous"] for row in encoded["functions"]],
                         [True, True, True, False])

    def test_adding_same_name_below_preserves_existing_id(self):
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        original = '1,1,3,0,1,"",cmd/a.go,render,render,2,2\n'
        added = '1,1,3,0,1,"",cmd/a.go,render,render,9,9\n'
        before = erosion.parse_csv(header + original, {"cmd/a.go"})
        after = erosion.parse_csv(header + original + added, {"cmd/a.go"})
        self.assertEqual(before[0]["id"], "cmd/a.go::render")
        self.assertEqual(after[0]["id"], before[0]["id"])
        self.assertEqual(after[1]["id"], "cmd/a.go::render#2")

    def test_moving_function_preserves_id(self):
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        before = erosion.parse_csv(header +
            '1,1,3,0,1,"",cmd/a.go,render,render,2,2\n'
            '1,1,3,0,1,"",cmd/a.go,render,render,9,9\n', {"cmd/a.go"})
        after = erosion.parse_csv(header +
            '1,1,3,0,1,"",cmd/a.go,render,render,20,20\n'
            '1,1,3,0,1,"",cmd/a.go,render,render,29,29\n', {"cmd/a.go"})
        self.assertEqual([row["id"] for row in after], [row["id"] for row in before])
        self.assertEqual([row["start"] for row in after], [20, 29])

    def test_duplicate_qualified_names_have_unique_deterministic_ordinals(self):
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        lines = [
            '1,1,3,0,1,"",cmd/a.go,render,render,2,2\n',
            '1,1,3,0,1,"",cmd/a.go,render,render,9,9\n',
            '1,1,3,0,1,"",cmd/a.go,render,render,15,15\n',
            '1,1,3,0,1,"",cmd/b.go,render,render,1,1\n',
        ]
        allowed = {"cmd/a.go", "cmd/b.go"}
        expected = ["cmd/a.go::render", "cmd/a.go::render#2",
                    "cmd/a.go::render#3", "cmd/b.go::render"]
        first = erosion.parse_csv(header + "".join(lines), allowed)
        shuffled = erosion.parse_csv(header + "".join(reversed(lines)), allowed)
        self.assertEqual([row["id"] for row in first], expected)
        self.assertEqual([row["id"] for row in reversed(shuffled)], expected)
        self.assertEqual(len({row["id"] for row in first}), len(first))

    def test_nested_anonymous_ids_follow_direct_parent_and_sibling_order(self):
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        path = 'cmd/a.go'
        lines = [
            f'1,1,3,0,1,"",{path},,func,12,13\n',
            f'1,1,3,0,1,"",{path},,func,10,30\n',
            f'1,1,3,0,1,"",{path},outer,outer,1,40\n',
            f'1,1,3,0,1,"",{path},,func,32,35\n',
        ]
        rows = erosion.parse_csv(header + ''.join(lines), {path})
        self.assertEqual([row['id'] for row in rows],
                         [f'{path}::outer$1$1', f'{path}::outer$1',
                          f'{path}::outer', f'{path}::outer$2'])
        self.assertEqual([row['parent'] for row in rows],
                         [f'{path}::outer$1', f'{path}::outer', None,
                          f'{path}::outer'])
        self.assertEqual(len({row['id'] for row in rows}), len(rows))

    def test_anonymous_ordinals_are_scoped_to_direct_parent(self):
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        path = 'internal/server/guest/other.js'
        before = [
            f'1,1,3,0,1,"",{path},a,a(),1,30\n',
            f'1,1,3,0,1,"",{path},(anonymous),(anonymous),10,11\n',
            f'1,1,3,0,1,"",{path},b,b(),40,70\n',
            f'1,1,3,0,1,"",{path},(anonymous),(anonymous),50,51\n',
        ]
        inserted = f'1,1,3,0,1,"",{path},(anonymous),(anonymous),5,6\n'
        original = erosion.parse_csv(header + ''.join(before), {path})
        changed = erosion.parse_csv(header + ''.join(before + [inserted]), {path})
        self.assertEqual(original[1]['id'], f'{path}::a()$1')
        self.assertEqual(original[3]['id'], f'{path}::b()$1')
        self.assertEqual(changed[1]['id'], f'{path}::a()$2')
        self.assertEqual(changed[3]['id'], original[3]['id'])
        self.assertEqual(changed[4]['id'], f'{path}::a()$1')
        self.assertEqual(changed[3]['parent'], f'{path}::b()')

    def test_top_level_anonymous_ids_and_moved_lines(self):
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        path = 'internal/server/guest/app.js'
        def rows(offset):
            return erosion.parse_csv(header +
                f'1,1,3,0,1,"",{path},(anonymous),(anonymous),{offset},{offset}\n' +
                f'1,1,3,0,1,"",{path},(anonymous),(anonymous),{offset + 8},{offset + 8}\n', {path})
        before, after = rows(2), rows(20)
        self.assertEqual([row['id'] for row in before], [f'{path}::$1', f'{path}::$2'])
        self.assertEqual([row['id'] for row in after], [row['id'] for row in before])
        self.assertEqual([row['parent'] for row in after], [None, None])

    def test_same_start_uses_containment_to_nest_anonymous_functions(self):
        path = 'internal/server/guest/other.js'
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        outer = f'3,1,9,0,3,"",{path},render,render,1,10\n'
        short = f'1,1,3,0,1,"",{path},(anonymous),(anonymous),2,2\n'
        long = f'2,1,6,0,2,"",{path},(anonymous),(anonymous),2,3\n'
        rows = erosion.parse_csv(header + short + long + outer, {path})
        self.assertEqual([row['id'] for row in rows],
                         [f'{path}::render$1$1', f'{path}::render$1', f'{path}::render'])
        reversed_rows = erosion.parse_csv(header + outer + long + short, {path})
        self.assertEqual([row['id'] for row in reversed_rows],
                         [f'{path}::render', f'{path}::render$1', f'{path}::render$1$1'])

    def test_equal_ranges_are_siblings_unless_a_named_parent_has_same_range(self):
        path = 'cmd/a.go'
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        named = f'1,1,3,0,1,"",{path},outer,outer,1,1\n'
        anon = f'1,1,3,0,1,"",{path},,func,1,1\n'
        other_anon = f'1,1,6,0,1,"",{path},,func,1,1\n'
        rows = erosion.parse_csv(header + anon + other_anon + named, {path})
        self.assertEqual([row['id'] for row in rows],
                         [f'{path}::outer$1', f'{path}::outer$2', f'{path}::outer'])
        self.assertEqual([row['parent'] for row in rows],
                         [f'{path}::outer', f'{path}::outer', None])
        self.assertEqual(len({row['id'] for row in rows}), len(rows))
        reversed_rows = erosion.parse_csv(header + other_anon + anon + named, {path})
        self.assertEqual([row['token'] for row in reversed_rows[:2]], [6, 3])
        self.assertEqual([row['id'] for row in reversed_rows[:2]],
                         [f'{path}::outer$1', f'{path}::outer$2'])

    def test_line_movement_preserves_nested_ids_and_named_local_parent(self):
        path = 'cmd/a.go'
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        def rows(offset):
            return erosion.parse_csv(header +
                f'5,1,20,0,5,"",{path},outer,outer,{offset},{offset + 20}\n' +
                f'3,1,10,0,3,"",{path},local,local,{offset + 2},{offset + 10}\n' +
                f'1,1,3,0,1,"",{path},,func,{offset + 4},{offset + 5}\n', {path})
        before, after = rows(1), rows(100)
        self.assertEqual([row['id'] for row in after], [row['id'] for row in before])
        self.assertEqual([row['parent'] for row in before],
                         [None, f'{path}::outer', f'{path}::local'])
        self.assertEqual(before[2]['id'], f'{path}::local$1')

    def test_named_identity_collision_is_rejected(self):
        path = 'cmd/a.go'
        header = 'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
        with self.assertRaisesRegex(ValueError, 'duplicate lizard function identity'):
            erosion.parse_csv(header +
                f'1,1,3,0,1,"",{path},named,$1,1,1\n' +
                f'1,1,3,0,1,"",{path},,func,4,4\n', {path})

    def test_unreadable_in_scope_header_reports_file_and_line(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "android/app/src/main/java/Unreadable.kt"
            source.parent.mkdir(parents=True)
            source.write_text("fun f() = 1\n")
            with mock.patch.object(pathlib.Path, "open", side_effect=OSError("denied")):
                with self.assertRaisesRegex(ValueError, r"Unreadable.kt:1: cannot read source header"):
                    erosion.discover_sources(root)

    def test_excludes_tests_generated_build_and_third_party(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            files = {
                "cmd/qmix/main.go": "package main\nfunc main() {}\n",
                "internal/server/handler.go": "package server\nfunc handle() {}\n",
                "internal/server/handler_test.go": "package server\nfunc TestHandle() {}\n",
                "internal/server/handler_integration_test.go": "package server\nfunc TestIntegration() {}\n",
                "internal/server/generated.go": "// Code generated by a tool. DO NOT EDIT.\npackage server\n",
                "internal/server/guest/app.js": "function app() {}\n",
                "internal/server/guest/app.test.js": "function test() {}\n",
                "internal/server/guest/build/app.js": "function built() {}\n",
                "internal/server/guest/third_party/lib.js": "function vendor() {}\n",
                "android/app/src/main/java/App.kt": "fun app() {}\n",
                "android/app/src/main/generated/G.kt": "fun generated() {}\n",
                "android/app/src/test/java/AppTest.kt": "fun test() {}\n",
                "android/app/src/androidTest/java/AppTest.kt": "fun test() {}\n",
            }
            for path, content in files.items():
                dest = root / path
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(content)
            self.assertEqual(
                erosion.discover_sources(root),
                ["android/app/src/main/java/App.kt", "cmd/qmix/main.go", "internal/server/guest/app.js", "internal/server/handler.go"],
            )

    def test_empty_input_has_all_languages_and_zero_erosion(self):
        report = erosion.calculate(erosion.parse_csv(
            "NLOC,CCN,token,PARAM,location,file,function,long_name,start,end\n", set()
        ))
        self.assertEqual(report["functions"], [])
        self.assertNotIn("overall", report)
        self.assertEqual(list(report["languages"]), ["Go", "Kotlin", "JS"])
        self.assertTrue(all(item["erosion"] == 0 for item in report["languages"].values()))

    def test_top_five_sorted_by_mass_and_both_renderings_match(self):
        rows = [
            {"file": "cmd/a.go", "function": f"f{i}", "language": "Go", "ccn": i, "nloc": 4, "mass": i * 2}
            for i in range(1, 8)
        ]
        report = erosion.calculate(rows)
        body = erosion.render_markdown(report)
        self.assertEqual([row["function"] for row in report["top_five"]["Go"]], [f"f{i}" for i in range(7, 2, -1)])
        self.assertEqual(body.count("<!-- qmix-lizard-erosion -->"), 1)
        self.assertIn("Go", body)
        self.assertIn("Kotlin", body)
        self.assertIn("JS", body)
        self.assertIn("Functions with CCN > 10", body)
        for row in report["top_five"]["Go"]:
            self.assertIn(row["function"], body)
            self.assertIn(str(row["ccn"]), body)
        self.assertEqual(json.loads(json.dumps(report))["languages"], report["languages"])

    def test_markdown_escapes_untrusted_function_names(self):
        report = erosion.calculate([{"file": "cmd/<x>.go", "function": "<script>|x", "language": "Go", "ccn": 11, "nloc": 1, "mass": 11}])
        body = erosion.render_markdown(report)
        self.assertNotIn("<script>", body)
        self.assertIn("&lt;script&gt;", body)
        self.assertIn("\\|", body)

    def test_invalid_csv_and_missing_function_rows_fail_loudly(self):
        with self.assertRaises(ValueError):
            erosion.parse_csv('NLOC,CCN,file,function\n2,nope,cmd/a.go,f\n', {"cmd/a.go"})
        with self.assertRaises(ValueError):
            erosion.parse_csv('NLOC,CCN,file,function\n2,1,cmd/out.go,f\n', {"cmd/a.go"})
        with self.assertRaises(ValueError):
            erosion.parse_csv('NLOC,CCN,file,function\n', {"cmd/a.go"})

    def test_missing_language_rows_are_a_tool_failure(self):
        with self.assertRaisesRegex(ValueError, "no JS functions"):
            erosion.parse_csv(
                'NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
                '1,1,1,0,1,"",cmd/a.go,a,a,1,1\n',
                {"cmd/a.go", "internal/server/guest/app.js"},
            )

    def test_selected_files_and_analyzed_files_are_visible_when_output_is_partial(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "cmd/a.go"
            source.parent.mkdir()
            source.write_text("package a\nfunc A() {}\n")
            (root / "cmd/declarations.go").write_text("package a\ntype T struct{}\n")
            (root / "cmd/omitted.go").write_text("package a\nfunc Omitted() {}\n")
            lizard = root / "fake-lizard"
            lizard.write_text(
                '#!/bin/sh\n'
                'printf "%s\\n" "NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end" '
                '"1,1,3,0,1,loc,cmd/a.go,A,A,2,2"\n'
            )
            lizard.chmod(0o755)
            report = erosion.analyze(root, str(lizard))
            self.assertEqual(report["languages"]["Go"]["files_selected"], 3)
            self.assertEqual(report["languages"]["Go"]["files_with_functions"], 1)
            self.assertEqual(report["languages"]["Go"]["files_selected"], 3)
            self.assertEqual(report["languages"]["Go"]["files_with_functions"], 1)
            self.assertEqual(report["languages"]["JS"]["files_selected"], 0)
            body = erosion.render_markdown(report)
            self.assertIn("Files selected", body)
            self.assertIn("Files with functions", body)
            self.assertIn("| Go | 0.00% | 1 | 0 | 3 | 1 |", body)

    def test_lizard_crash_is_not_reported_as_zero(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "cmd/a.go"
            source.parent.mkdir()
            source.write_text("package a\nfunc A() {}\n")
            lizard = root / "fake-lizard"
            lizard.write_text("#!/bin/sh\nexit 2\n")
            lizard.chmod(0o755)
            with self.assertRaisesRegex(RuntimeError, r"lizard failed \(exit 2\)"):
                erosion.analyze(root, str(lizard))

    def test_kotlin_uses_tree_sitter_and_lizard_only_receives_go_js(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            for name, content in {
                "cmd/a.go": "package a\nfunc A() {}\n",
                "internal/server/guest/app.js": "function app() {}\n",
                "android/app/src/main/java/A.kt": "fun KotlinBranch() { if (true) println(1) }\n",
            }.items():
                destination = root / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(content)
            lizard = root / "fake-lizard"
            lizard.write_text(
                '#!/bin/sh\n'
                'for arg in "$@"; do case "$arg" in *.kt) exit 19 ;; esac; done\n'
                'printf "%s\\n" "NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end" '
                '"1,1,3,0,1,loc,cmd/a.go,A,A,2,2" '
                '"1,1,3,0,1,loc,internal/server/guest/app.js,app,app,1,1"\n'
            )
            lizard.chmod(0o755)
            report = erosion.analyze(root, str(lizard))
            self.assertEqual([row["analyzer"] for row in report["functions"]],
                             ["lizard", "lizard", "tree-sitter-kotlin"])
            self.assertEqual(report["languages"]["Kotlin"]["function_count"], 1)
            self.assertEqual(report["functions"][-1]["ccn"], 2)
            self.assertNotIn("overall", report)
            self.assertNotIn("Overall", erosion.render_markdown(report))

    def test_kotlin_declaration_only_scope_has_zero_mass_without_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "android/app/src/main/java/Contract.kt"
            source.parent.mkdir(parents=True)
            source.write_text("interface Contract {\n fun invoke(): Unit\n}\n")
            report = erosion.analyze(root, "unused-lizard")
            self.assertEqual(report["languages"]["Kotlin"]["function_count"], 0)
            self.assertEqual(report["languages"]["Kotlin"]["files_selected"], 1)
            self.assertEqual(report["languages"]["Kotlin"]["files_with_functions"], 0)
            self.assertEqual(report["languages"]["Kotlin"]["erosion"], 0)

    def test_kotlin_parse_error_aborts_report_with_file_and_line(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "android/app/src/main/java/Broken.kt"
            source.parent.mkdir(parents=True)
            source.write_text("fun broken() {\n    if (\n}\n")
            with self.assertRaisesRegex(ValueError, r"Broken.kt:[1-3]: Kotlin parse error"):
                erosion.analyze(root, "unused-lizard")

    def test_lizard_rejects_kotlin_csv_rows(self):
        csv_text = ('NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end\n'
                    '1,1,3,0,1,loc,android/app/src/main/A.kt,f,f,1,1\n')
        with self.assertRaisesRegex(ValueError, "lizard reported unexpected source"):
            erosion.parse_csv(csv_text, {"android/app/src/main/A.kt"})

    def test_cli_empty_repository_writes_json_and_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            output = root / "report.json"
            summary = root / "summary.md"
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--repo", str(root), "--output", str(output), "--summary", str(summary)],
                capture_output=True, text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(json.loads(output.read_text())["languages"]["Go"]["function_count"], 0)
            self.assertIn("0.00%", summary.read_text())


if __name__ == "__main__":
    unittest.main()
