#!/usr/bin/env python3
"""Normalize and render the informational jscpd JSON report (qmix#200)."""
import argparse
import copy
import html
import json
import math
import pathlib
import sys

from erosion import discover_sources, is_in_scope

MARKER = "<!-- qmix-jscpd-duplication -->"
FORMATS = (("go", "Go"), ("kotlin", "Kotlin"), ("javascript", "JS"))


def normalize(raw, root):
    """Validate the native jscpd v5.3.2 JSON and remove runner-specific paths."""
    root = pathlib.Path(root).resolve()
    data = copy.deepcopy(raw)
    try:
        statistics = data["statistics"]
        formats = statistics["formats"]
        duplicates = data["duplicates"]
        expected_formats = {key for key, _ in FORMATS}
        if not isinstance(duplicates, list) or not isinstance(formats, dict) or not set(formats) <= expected_formats:
            raise ValueError("unexpected jscpd formats or duplicates")
        reported_formats = set(formats)
        for key in expected_formats - reported_formats:
            formats[key] = {"clones": 0, "lines": 0, "duplicatedLines": 0,
                            "sources": 0, "percentage": 0}
        for row in [statistics["total"], *(formats[key] for key, _ in FORMATS)]:
            for field in ("clones", "lines", "duplicatedLines", "sources"):
                value = row[field]
                if type(value) is not int or value < 0:
                    raise ValueError(f"invalid jscpd {field}")
            percentage = row["percentage"]
            if type(percentage) not in (int, float) or not math.isfinite(percentage) or not 0 <= percentage <= 100:
                raise ValueError("invalid jscpd percentage")
        if statistics["total"]["sources"] == 0:
            raise ValueError("jscpd analyzed no source files")
        if statistics["total"]["clones"] != len(duplicates) or sum(
            formats[key]["clones"] for key, _ in FORMATS
        ) != len(duplicates):
            raise ValueError("jscpd clone counts disagree")
        for clone in duplicates:
            if clone["format"] not in reported_formats or type(clone["lines"]) is not int or clone["lines"] <= 0:
                raise ValueError("invalid jscpd clone")
            if type(clone["tokens"]) is not int or clone["tokens"] <= 0:
                raise ValueError("invalid jscpd clone tokens")
            for side in ("firstFile", "secondFile"):
                fragment = clone[side]
                path = pathlib.Path(fragment["name"])
                if not path.is_absolute():
                    raise ValueError("jscpd requires --absolute paths")
                relative = path.relative_to(root).as_posix()
                if not is_in_scope(relative):
                    raise ValueError(f"jscpd reported out-of-scope source: {relative}")
                if type(fragment["start"]) is not int or type(fragment["end"]) is not int or not 1 <= fragment["start"] <= fragment["end"]:
                    raise ValueError("invalid jscpd source range")
                fragment["name"] = relative
    except (KeyError, TypeError, AttributeError, ValueError) as exc:
        raise ValueError(f"invalid jscpd report: {exc}") from exc
    data["schema_version"] = 1  # Version the normalized artifact for downstream consumers.
    return data


def render_markdown(report):
    """One body for the job summary and the PR comment."""
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "\\|").replace("\n", " ")

    statistics = report["statistics"]
    rows = [("Overall", statistics["total"])] + [
        (label, statistics["formats"][key]) for key, label in FORMATS
    ]
    lines = [MARKER, "### Code duplication (jscpd)", "", "Informational only; duplication never fails the job.", "",
             "| Scope | Duplication, % | Clones |", "| --- | ---: | ---: |"]
    for label, metrics in rows:
        lines.append(f"| {label} | {metrics['percentage']:.2f}% | {metrics['clones']} |")
    clones = sorted(report["duplicates"], key=lambda clone: (
        -clone["lines"], -clone["tokens"], clone["firstFile"]["name"],
        clone["firstFile"]["start"], clone["secondFile"]["name"],
    ))[:5]
    lines += ["", f"Top {len(clones)} clones by lines:", ""]
    if clones:
        lines += ["| Occurrences | Lines | Tokens |", "| --- | ---: | ---: |"]
        for clone in clones:
            first, second = clone["firstFile"], clone["secondFile"]
            lines.append(f"| {cell(first['name'])}:{first['start']}–{first['end']} ↔ "
                         f"{cell(second['name'])}:{second['start']}–{second['end']} | "
                         f"{clone['lines']} | {clone['tokens']} |")
    else:
        lines.append("No clones found.")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    sources = subcommands.add_parser("sources", help="NUL-delimited production source paths")
    sources.add_argument("repo", type=pathlib.Path)
    report = subcommands.add_parser("report", help="validate, normalize and render jscpd JSON")
    report.add_argument("input", type=pathlib.Path)
    report.add_argument("output", type=pathlib.Path)
    report.add_argument("markdown", type=pathlib.Path)
    report.add_argument("--repo", type=pathlib.Path, default=pathlib.Path("."))
    args = parser.parse_args()
    if args.command == "sources":
        selected = discover_sources(args.repo.resolve())
        if not selected:
            parser.error("no production source files selected")
        sys.stdout.buffer.write(b"\0".join(path.encode() for path in selected) + b"\0")
    else:
        normalized = normalize(json.loads(args.input.read_text(encoding="utf-8")), args.repo)
        args.output.write_text(json.dumps(normalized, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        args.markdown.write_text(render_markdown(normalized), encoding="utf-8")


if __name__ == "__main__":
    main()
