#!/usr/bin/env python3
"""Convert the lizard report into named benchmark metrics (qmix#198).

The output is the generic customSmallerIsBetter array, independently reusable by
other quality tools under the same benchmark dashboard.
"""

import argparse
import json
import math
import pathlib

LANGUAGES = ("Go", "Kotlin", "JS")


def metrics_from_lizard(report):
    if report.get("schema_version") != 1:
        raise ValueError("unsupported lizard report schema")
    languages = report["languages"]
    scopes = (("Overall", report["overall"]),
              *((name, languages[name]) for name in LANGUAGES))
    metrics = []
    for name, scope in scopes:
        fraction = scope["erosion"]
        if (isinstance(fraction, bool) or not isinstance(fraction, (int, float))
                or not math.isfinite(fraction) or not 0 <= fraction <= 1):
            raise ValueError(f"invalid {name} erosion")
        # Retain precision: rounding small positive values to zero changes alert
        # behavior when the benchmark compares each value with its predecessor.
        metrics.append({"name": f"{name} erosion", "unit": "%", "value": fraction * 100})
    count = report["overall"]["high_ccn_count"]
    if isinstance(count, bool) or not isinstance(count, int) or count < 0:
        raise ValueError("invalid high-CCN function count")
    metrics.append({"name": "Functions with CCN > 10", "unit": "functions", "value": count})
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    metrics = metrics_from_lizard(json.loads(args.input.read_text(encoding="utf-8")))
    args.output.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
