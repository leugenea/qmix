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
