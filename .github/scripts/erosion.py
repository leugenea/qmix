#!/usr/bin/env python3
"""Informational lizard complexity erosion for production sources (qmix#197).

Only score can never fail the job. A missing tool, malformed CSV, or an entire
nonempty language producing no functions is an analysis failure, not zero erosion.
The lizard invocation omits -C: warning exit codes are not a metric.
JSON schema_version 1 function IDs use file::long_name for the first occurrence
by start line, then file::long_name#2, #3, ... for later occurrences (end line
breaks start ties). Named functions use lizard's signature-bearing long names,
so their IDs survive line movement unless another same-name duplicate precedes
them. Anonymous functions (anonymous: true) have positional ordinal IDs: adding
one with the same long name earlier in the file shifts later IDs. Consumers such
as history and gates must not treat anonymous IDs as stable identities.
"""

import argparse
import collections
import csv
import html
import io
import json
import math
import pathlib
import subprocess

LANGUAGES = ("Go", "Kotlin", "JS")
ROOTS = (("cmd", ".go"), ("internal", ".go"),
         ("android/app/src/main", ".kt"), ("internal/server/guest", ".js"))
EXCLUDED_DIRS = {"build", "generated", "third_party", "node_modules", "dist", "vendor", "__tests__"}
CSV_FIELDS = {"NLOC", "CCN", "token", "PARAM", "file", "function", "long_name", "start", "end"}
MARKER = "<!-- qmix-lizard-erosion -->"


def language_for(path):
    if path.startswith("android/app/src/main/") and path.endswith(".kt"):
        return "Kotlin"
    if path.startswith("internal/server/guest/") and path.endswith(".js"):
        return "JS"
    if path.startswith(("cmd/", "internal/")) and path.endswith(".go"):
        return "Go"
    raise ValueError(f"Unexpected source path: {path}")


def is_in_scope(path, root=None):
    """Shared production scope; missing files remain routed for deletions.

    The optional root lets both discovery and routing reject generated headers
    when source content is available. A deleted path cannot be inspected, so
    routing conservatively analyzes its former scope.
    """
    parts = pathlib.PurePosixPath(path).parts
    if any(part in EXCLUDED_DIRS for part in parts):
        return False
    if path.endswith(("_test.go", ".test.js", ".spec.js", ".min.js",
                      ".generated.go", ".generated.kt", ".generated.js")):
        return False
    if not any(path.startswith(prefix + "/") and path.endswith(extension)
               for prefix, extension in ROOTS):
        return False
    source = root / path if root is not None else None
    if source is not None and source.is_file():
        with source.open(encoding="utf-8") as stream:
            header = "".join(stream.readline() for _ in range(20))
        if "Code generated" in header and "DO NOT EDIT" in header:
            return False
    return True


def discover_sources(root):
    """Select only production sources, including exclusion of generated headers."""
    selected = set()
    for prefix, extension in ROOTS:
        base = root / prefix
        if not base.is_dir():
            continue
        for source in base.rglob(f"*{extension}"):
            path = source.relative_to(root).as_posix()
            if not is_in_scope(path, root):
                continue
            selected.add(path)
    return sorted(selected)


def parse_csv(text, allowed):
    """Validate lizard's --csv -V rows rather than trusting its exit status."""
    reader = csv.DictReader(io.StringIO(text), strict=True)
    if reader.fieldnames is None or not CSV_FIELDS.issubset(reader.fieldnames):
        raise ValueError("lizard CSV header is missing required columns")
    rows = []
    for row in reader:
        if None in row or any(row.get(field) is None for field in CSV_FIELDS):
            raise ValueError("malformed lizard CSV row")
        path = row["file"]
        if path not in allowed:
            raise ValueError(f"lizard reported unexpected source: {path}")
        try:
            ccn, nloc, token, params, start, end = (
                int(row[field]) for field in ("CCN", "NLOC", "token", "PARAM", "start", "end")
            )
        except ValueError as exc:
            raise ValueError(f"invalid lizard metrics for {path}") from exc
        if min(ccn, nloc, token, params, start, end) < 0 or end < start:
            raise ValueError(f"invalid lizard metrics for {path}")
        rows.append({"file": path, "function": row["function"],
                     "anonymous": row["function"] in ("", "(anonymous)"),
                     "long_name": row["long_name"], "start": start, "end": end,
                     "language": language_for(path), "ccn": ccn, "nloc": nloc,
                     "token": token, "params": params, "mass": ccn * math.sqrt(nloc)})
    # #198/#199 join on file + lizard long_name. First occurrence keeps
    # the plain ID; later ones use #2, #3, ... in source order.
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[(row["file"], row["long_name"])].append(row)
    for (path, long_name), group in grouped.items():
        for ordinal, row in enumerate(sorted(group, key=lambda item: (
            item["start"], item["end"], item["function"], item["ccn"],
            item["nloc"], item["token"], item["params"]
        )), start=1):
            base = f"{path}::{long_name}"
            row["id"] = base if ordinal == 1 else f"{base}#{ordinal}"
    ids = set()
    for row in rows:
        if row["id"] in ids:
            raise ValueError(f"duplicate lizard function identity: {row['id']}")
        ids.add(row["id"])
    if allowed and not rows:
        raise ValueError("lizard produced no functions for nonempty source")
    for language in LANGUAGES:
        if any(language_for(path) == language for path in allowed) and not any(
            row["language"] == language for row in rows
        ):
            raise ValueError(f"lizard produced no {language} functions")
    return rows


def calculate(rows, sources=None):
    """Return unrounded values; consumers can format without losing precision."""
    if sources is None:
        sources = {row["file"] for row in rows}
    selected = set(sources)
    with_functions = {row["file"] for row in rows}

    def aggregate(group, source_paths):
        mass = math.fsum(row["mass"] for row in group)
        high = [row for row in group if row["ccn"] > 10]
        high_mass = math.fsum(row["mass"] for row in high)
        return {"erosion": high_mass / mass if mass else 0.0,
                "mass": mass, "high_ccn_mass": high_mass,
                "function_count": len(group), "high_ccn_count": len(high),
                "files_selected": len(source_paths),
                "files_with_functions": len(with_functions & source_paths)}

    return {"schema_version": 1, "overall": aggregate(rows, selected),
            "languages": {lang: aggregate([row for row in rows if row["language"] == lang],
                                          {path for path in selected if language_for(path) == lang})
                          for lang in LANGUAGES},
            "functions": rows,
            "top_five": sorted(rows, key=lambda row: (-row["mass"], row["file"], row["function"]))[:5]}


def render_markdown(report):
    """Use identical report content for job summary and the PR comment."""
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "\\|").replace("\n", " ")

    lines = [MARKER, "### Lizard complexity erosion", "", "Informational only; CCN > 10 contributes to erosion.", "",
             "| Scope | Erosion | Functions | Functions with CCN > 10 | Files selected | Files with functions |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for label, metrics in (("Overall", report["overall"]), *report["languages"].items()):
        lines.append(f"| {label} | {metrics['erosion']:.2%} | {metrics['function_count']} | {metrics['high_ccn_count']} | {metrics['files_selected']} | {metrics['files_with_functions']} |")
    lines += ["", "A selected file may contain no functions; these counts expose gaps without treating declarations as failures.",
              "", "Top 5 functions by mass (CCN × √NLOC):", "",
              "| File | Function | CCN | NLOC | Mass |", "| --- | --- | ---: | ---: | ---: |"]
    for row in report["top_five"]:
        lines.append(f"| {cell(row['file'])} | {cell(row['function'])} | {row['ccn']} | {row['nloc']} | {row['mass']:.2f} |")
    return "\n".join(lines) + "\n"


def analyze(root, lizard):
    sources = discover_sources(root)
    if not sources:
        return calculate([], sources)
    # Explicit source files avoid lizard's recursive discovery of tests/build output.
    completed = subprocess.run([lizard, "--csv", "-V", *sources], cwd=root,
                               text=True, capture_output=True, check=False)
    if completed.returncode:
        raise RuntimeError(f"lizard failed (exit {completed.returncode}): {completed.stderr}")
    return calculate(parse_csv(completed.stdout, set(sources)), sources)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path("."))
    parser.add_argument("--lizard", default="lizard")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--summary", required=True, type=pathlib.Path)
    args = parser.parse_args()
    report = analyze(args.repo.resolve(), args.lizard)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    args.summary.write_text(render_markdown(report), encoding="utf-8")


if __name__ == "__main__":
    main()
