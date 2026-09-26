#!/usr/bin/env python3
"""Convert erosion or normalized jscpd reports to named benchmark metrics.

The output is a generic customSmallerIsBetter array; erosion's original six
metric names and CLI behavior remain unchanged (qmix#228, qmix#201).
"""

import argparse
import json
import math
import pathlib

LANGUAGES = ("Go", "Kotlin", "JS")
DUPLICATION_FORMATS = (("go", "Go"), ("kotlin", "Kotlin"), ("javascript", "JS"))


def duplication_metrics_from_report(report):
    if type(report.get("schema_version")) is not int or report["schema_version"] != 1:
        raise ValueError("unsupported duplication report schema")
    statistics = report["statistics"]
    rows = [("Duplication", statistics["total"])] + [
        (label, statistics["formats"][key]) for key, label in DUPLICATION_FORMATS
    ]
    metrics = []
    for label, row in rows:
        percentage = row["percentage"]
        if (isinstance(percentage, bool) or not isinstance(percentage, (int, float))
                or not math.isfinite(percentage) or not 0 <= percentage <= 100):
            raise ValueError(f"invalid {label} duplication")
        metrics.append({"name": label if label == "Duplication" else f"{label} duplication",
                        "unit": "%", "value": percentage})
        clones = row["clones"]
        if isinstance(clones, bool) or not isinstance(clones, int) or clones < 0:
            raise ValueError(f"invalid {label} clone count")
        metrics.append({"name": f"{label} clones", "unit": "clones", "value": clones})
    return metrics


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
    parser.add_argument("--kind", choices=("erosion", "duplication"), default="erosion")
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    report = json.loads(args.input.read_text(encoding="utf-8"))
    converter = metrics_from_report if args.kind == "erosion" else duplication_metrics_from_report
    metrics = converter(report)
    args.output.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
