#!/usr/bin/env python3
"""Informational per-language complexity erosion for production sources (qmix#228).

Only the score can never fail the job. A missing tool, malformed CSV, or parse
error is an analysis failure, not zero erosion. Valid bodyless Kotlin sources
produce zero function rows.
The lizard invocation omits -C: warning exit codes are not a metric.
JSON schema_version 2 named Go/JS IDs use file::long_name for the first occurrence
by start line, then file::long_name#2, #3, ... for later occurrences (end line
breaks start ties). Named functions use lizard's signature-bearing long names,
so their IDs survive line movement unless another same-name duplicate precedes
them. Anonymous functions (anonymous: true) use $n ordinals scoped to their
inferred direct enclosing function, e.g. file::Outer$1$1, or file::$n at file
top level. Inserting an anonymous function shifts later siblings under the
same inferred parent and all their descendants; it is intended to leave IDs
under other parents alone. Parents are inferred from lizard line ranges.
Lizard 1.24.0 can misparse some Go (receiver methods after a package-level
immediately-invoked closure reported without names). Consumers such as gates
must not treat its anonymous IDs as stable identities. Equal line ranges cannot
establish anonymous nesting; equal-range anonymous rows are siblings (a named
row may enclose them), with CSV order breaking ties because lizard's location
has no column. Kotlin instead uses a repository-owned tree-sitter counter.
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
        try:
            with source.open(encoding="utf-8") as stream:
                header = "".join(stream.readline() for _ in range(20))
        except (OSError, UnicodeError) as exc:
            raise ValueError(f"{path}:1: cannot read source header: {exc}") from exc
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
        if path not in allowed or language_for(path) == "Kotlin":
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
                     "token": token, "params": params, "mass": ccn * math.sqrt(nloc),
                     "analyzer": "lizard"})
    # Named IDs retain their original file-scoped scheme and tiebreaks.
    grouped = collections.defaultdict(list)
    for index, row in enumerate(rows):
        if not row["anonymous"]:
            grouped[(row["file"], row["long_name"])].append(index)
    for (path, long_name), group in grouped.items():
        for ordinal, index in enumerate(sorted(group, key=lambda i: (
            rows[i]["start"], rows[i]["end"], rows[i]["function"], rows[i]["ccn"],
            rows[i]["nloc"], rows[i]["token"], rows[i]["params"]
        )), start=1):
            base = f"{path}::{long_name}"
            rows[index]["id"] = base if ordinal == 1 else f"{base}#{ordinal}"

    by_file = collections.defaultdict(list)
    for index, row in enumerate(rows):
        by_file[row["file"]].append(index)
    parents = {}
    for indices in by_file.values():
        for index in indices:
            child = rows[index]
            candidates = [other for other in indices if other != index and
                          rows[other]["start"] <= child["start"] and
                          child["end"] <= rows[other]["end"] and (
                              (rows[other]["start"], rows[other]["end"]) !=
                              (child["start"], child["end"]) or
                              child["anonymous"] and not rows[other]["anonymous"])]
            # Strictly enclosing ranges come first; equal-range anonymous rows
            # cannot infer nesting from lizard's line-only location. Prefer a
            # named equal-range parent when present. CSV order is the last tie.
            parents[index] = min(candidates, key=lambda i: (
                rows[i]["end"] - rows[i]["start"],
                rows[i]["anonymous"], rows[i]["start"], rows[i]["end"], i
            )) if candidates else None

    siblings = collections.defaultdict(list)
    for index, row in enumerate(rows):
        if row["anonymous"]:
            siblings[(row["file"], parents[index])].append(index)
    ordinals = {}
    for group in siblings.values():
        for ordinal, index in enumerate(sorted(group, key=lambda i: (
            rows[i]["start"], rows[i]["end"], i
        )), start=1):
            ordinals[index] = ordinal

    def identity(index):
        row = rows[index]
        if "id" not in row:
            parent = parents[index]
            base = identity(parent) if parent is not None else f"{row['file']}::"
            row["id"] = f"{base}${ordinals[index]}"
        return row["id"]

    for index, row in enumerate(rows):
        identity(index)
        parent = parents[index]
        row["parent"] = identity(parent) if parent is not None else None
    ids = set()
    for row in rows:
        if row["id"] in ids:
            raise ValueError(f"duplicate lizard function identity: {row['id']}")
        ids.add(row["id"])
    if allowed and not rows:
        raise ValueError("lizard produced no functions for nonempty source")
    for language in ("Go", "JS"):
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

    return {"schema_version": 2,
            "languages": {lang: aggregate([row for row in rows if row["language"] == lang],
                                          {path for path in selected if language_for(path) == lang})
                          for lang in LANGUAGES},
            "functions": rows,
            "top_five": {lang: sorted((row for row in rows if row["language"] == lang),
                                     key=lambda row: (-row["mass"], row["file"], row["function"]))[:5]
                         for lang in LANGUAGES}}


def render_markdown(report):
    """Use identical report content for job summary and the PR comment."""
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "\\|").replace("\n", " ")

    lines = [MARKER, "### Code complexity erosion", "", "Informational only; CCN > 10 contributes to erosion.",
             "Kotlin CCN follows the repository tree-sitter counter's rules and is not directly comparable with lizard's Go/JS CCN.", "",
             "| Scope | Erosion | Functions | Functions with CCN > 10 | Files selected | Files with functions |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for label, metrics in report["languages"].items():
        lines.append(f"| {label} | {metrics['erosion']:.2%} | {metrics['function_count']} | {metrics['high_ccn_count']} | {metrics['files_selected']} | {metrics['files_with_functions']} |")
    lines += ["", "A selected file may contain no functions; these counts expose gaps without treating declarations as failures."]
    for language, rows in report["top_five"].items():
        lines += ["", f"Top {len(rows)} {language} functions by mass (CCN × √NLOC):", "",
                  "| File | Function | CCN | NLOC | Mass |", "| --- | --- | ---: | ---: | ---: |"]
        for row in rows:
            lines.append(f"| {cell(row['file'])} | {cell(row['function'])} | {row['ccn']} | {row['nloc']} | {row['mass']:.2f} |")
    return "\n".join(lines) + "\n"


def analyze(root, lizard):
    sources = discover_sources(root)
    lizard_sources = [path for path in sources if language_for(path) != "Kotlin"]
    kotlin_sources = [path for path in sources if language_for(path) == "Kotlin"]
    rows = []
    if lizard_sources:
        # Explicit source files avoid lizard's recursive test/build discovery.
        completed = subprocess.run([lizard, "--csv", "-V", *lizard_sources], cwd=root,
                                   text=True, capture_output=True, check=False)
        if completed.returncode:
            raise RuntimeError(f"lizard failed (exit {completed.returncode}): {completed.stderr}")
        rows = parse_csv(completed.stdout, set(lizard_sources))
    if kotlin_sources:
        # Lazy import keeps routing/policy tests usable without the CI-only wheel.
        from kotlin_complexity import analyze_files
        kotlin_rows = analyze_files(root, [pathlib.Path(path) for path in kotlin_sources])
        for row in kotlin_rows:
            row["language"] = "Kotlin"
            row["function"] = ".".join(filter(None, (row["owner"], row["name"])))
            row["start"], row["end"] = row["start_line"], row["end_line"]
            row["mass"] = row["ccn"] * math.sqrt(row["nloc"])
        rows += kotlin_rows
    return calculate(rows, sources)


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
