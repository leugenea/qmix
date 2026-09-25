#!/usr/bin/env python3
"""Convert per-language erosion into six named benchmark metrics (qmix#228).

The output is the generic customSmallerIsBetter array, independently reusable by
other quality tools under the same benchmark dashboard.
"""

import argparse
import json
import math
import pathlib

LANGUAGES = ("Go", "Kotlin", "JS")


def metrics_from_report(report):
    if report.get("schema_version") != 2:
        raise ValueError("unsupported erosion report schema")
    if "overall" in report:
        raise ValueError("cross-language overall metric is not supported")
    languages = report["languages"]
    metrics = []
    for name in LANGUAGES:
        scope = languages[name]
        fraction = scope["erosion"]
        if (isinstance(fraction, bool) or not isinstance(fraction, (int, float))
                or not math.isfinite(fraction) or not 0 <= fraction <= 1):
            raise ValueError(f"invalid {name} erosion")
        # Preserve precision: rounding small positives changes alert behavior.
        metrics.append({"name": f"{name} erosion", "unit": "%", "value": fraction * 100})
        count = scope["high_ccn_count"]
        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
            raise ValueError(f"invalid {name} high-CCN function count")
        metrics.append({"name": f"{name} CCN > 10", "unit": "functions", "value": count})
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    metrics = metrics_from_report(json.loads(args.input.read_text(encoding="utf-8")))
    args.output.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
