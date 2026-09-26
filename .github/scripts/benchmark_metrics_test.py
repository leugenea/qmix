#!/usr/bin/env python3
"""Contract tests for lizard-to-benchmark conversion (qmix#198)."""

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("benchmark_metrics.py")
SPEC = importlib.util.spec_from_file_location("benchmark_metrics", SCRIPT)
benchmark_metrics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark_metrics)


class BenchmarkMetricsTest(unittest.TestCase):
    def report(self):
        return {"schema_version": 2,
                "languages": {"Go": {"erosion": 0.75, "high_ccn_count": 3},
                              "Kotlin": {"erosion": 0.0025, "high_ccn_count": 1},
                              "JS": {"erosion": 0, "high_ccn_count": 0}}}

    def test_six_named_smaller_is_better_metrics_and_units(self):
        self.assertEqual(benchmark_metrics.metrics_from_report(self.report()), [
            {"name": "Go erosion", "unit": "%", "value": 75.0},
            {"name": "Go CCN > 10", "unit": "functions", "value": 3},
            {"name": "Kotlin erosion", "unit": "%", "value": 0.25},
            {"name": "Kotlin CCN > 10", "unit": "functions", "value": 1},
            {"name": "JS erosion", "unit": "%", "value": 0.0},
            {"name": "JS CCN > 10", "unit": "functions", "value": 0},
        ])

    def test_small_positive_erosion_is_not_rounded_to_zero(self):
        report = self.report()
        report["languages"]["JS"]["erosion"] = 1e-10
        self.assertGreater(benchmark_metrics.metrics_from_report(report)[4]["value"], 0)

    def test_cli_writes_one_generic_json_array(self):
        with tempfile.TemporaryDirectory() as temp:
            source, output = pathlib.Path(temp) / "report.json", pathlib.Path(temp) / "metrics.json"
            source.write_text(json.dumps(self.report()))
            subprocess.run([sys.executable, str(SCRIPT), "--input", str(source),
                            "--output", str(output)], check=True)
            self.assertEqual(json.loads(output.read_text()),
                             benchmark_metrics.metrics_from_report(self.report()))

    def duplication_report(self):
        return {"schema_version": 1, "statistics": {
            "total": {"percentage": 0.503012345, "clones": 4},
            "formats": {
                "go": {"percentage": 0.836812345, "clones": 4},
                "kotlin": {"percentage": 0, "clones": 0},
                "javascript": {"percentage": 0, "clones": 0},
            },
        }}

    def test_duplication_metrics_keep_exact_names_units_and_precision(self):
        self.assertEqual(benchmark_metrics.duplication_metrics_from_report(self.duplication_report()), [
            {"name": "Duplication", "unit": "%", "value": 0.503012345},
            {"name": "Duplication clones", "unit": "clones", "value": 4},
            {"name": "Go duplication", "unit": "%", "value": 0.836812345},
            {"name": "Go clones", "unit": "clones", "value": 4},
            {"name": "Kotlin duplication", "unit": "%", "value": 0},
            {"name": "Kotlin clones", "unit": "clones", "value": 0},
            {"name": "JS duplication", "unit": "%", "value": 0},
            {"name": "JS clones", "unit": "clones", "value": 0},
        ])

    def test_duplication_cli_writes_metrics_from_normalized_report(self):
        with tempfile.TemporaryDirectory() as temp:
            source, output = pathlib.Path(temp) / "report.json", pathlib.Path(temp) / "metrics.json"
            source.write_text(json.dumps(self.duplication_report()))
            subprocess.run([sys.executable, str(SCRIPT), "--kind", "duplication", "--input", str(source),
                            "--output", str(output)], check=True)
            self.assertEqual(json.loads(output.read_text()),
                             benchmark_metrics.duplication_metrics_from_report(self.duplication_report()))

    def test_missing_format_normalization_yields_zero_metrics(self):
        import jscpd_report
        row = {"percentage": 0, "clones": 0, "lines": 10,
               "duplicatedLines": 0, "sources": 1}
        raw = {"statistics": {"total": row.copy(), "formats": {"go": row.copy()}},
               "duplicates": []}
        normalized = jscpd_report.normalize(raw, pathlib.Path(__file__).parents[2])
        metrics = benchmark_metrics.duplication_metrics_from_report(normalized)
        self.assertEqual(metrics[4:], [
            {"name": "Kotlin duplication", "unit": "%", "value": 0},
            {"name": "Kotlin clones", "unit": "clones", "value": 0},
            {"name": "JS duplication", "unit": "%", "value": 0},
            {"name": "JS clones", "unit": "clones", "value": 0},
        ])

    def test_duplication_rejects_missing_formats_and_invalid_values(self):
        for change in (
            lambda r: r.pop("schema_version"),
            lambda r: r.update(schema_version=True),
            lambda r: r.update(schema_version=2),
            lambda r: r["statistics"].pop("total"),
            lambda r: r["statistics"]["formats"].pop("javascript"),
            lambda r: r["statistics"]["total"].update(percentage=float("nan")),
            lambda r: r["statistics"]["total"].update(percentage=float("inf")),
            lambda r: r["statistics"]["formats"]["go"].update(percentage=-0.01),
            lambda r: r["statistics"]["formats"]["go"].update(percentage=100.01),
            lambda r: r["statistics"]["formats"]["kotlin"].update(percentage=True),
            lambda r: r["statistics"]["formats"]["javascript"].update(clones=-1),
            lambda r: r["statistics"]["formats"]["javascript"].update(clones=1.5),
            lambda r: r["statistics"]["formats"]["javascript"].update(clones=True),
        ):
            report = self.duplication_report()
            change(report)
            with self.subTest(report=report), self.assertRaises((ValueError, KeyError)):
                benchmark_metrics.duplication_metrics_from_report(report)

    def test_rejects_malformed_report_and_values(self):
        for change in (
            lambda r: r.update(schema_version=1),
            lambda r: r["languages"].pop("Go"),
            lambda r: r["languages"]["Go"].update(erosion=float("nan")),
            lambda r: r["languages"]["Go"].update(erosion=-0.01),
            lambda r: r["languages"]["Go"].update(erosion=1.01),
            lambda r: r["languages"]["JS"].update(erosion=True),
            lambda r: r["languages"]["Go"].update(high_ccn_count=-1),
            lambda r: r["languages"]["Go"].update(high_ccn_count=1.2),
        ):
            report = self.report()
            change(report)
            with self.subTest(report=report), self.assertRaises((ValueError, KeyError)):
                benchmark_metrics.metrics_from_report(report)


if __name__ == "__main__":
    unittest.main()
