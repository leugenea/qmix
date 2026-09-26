#!/usr/bin/env python3
"""Render the jscpd baseline's new clones for the PR gate (qmix#202)."""
import argparse
import html
import json
from pathlib import Path
import sys

from jscpd_report import normalize


def evaluate(raw, repo):
    """Trust jscpd's isNew classification, but fail closed on invalid reports."""
    report = normalize(raw, repo)
    clones = report["duplicates"]
    if any(type(clone.get("isNew")) is not bool for clone in clones):
        raise ValueError("invalid or missing jscpd isNew flag")
    new = [clone for clone in clones if clone["isNew"]]
    total = report["statistics"]["total"]
    if type(total.get("newClones")) is not int or total["newClones"] != len(new):
        raise ValueError("jscpd new clone count disagrees with duplicates")
    return {"schema_version": 1, "counts": {"total_clones": len(clones),
            "new_clones": len(new)}, "new_clones": new}


def render_markdown(report):
    def cell(value):
        return (html.escape(str(value), quote=True).replace("\\", "&#92;")
                .replace("|", "&#124;").replace("\r", " ").replace("\n", " "))

    lines = ["### Clone gate (qmix#202)", ""]
    if "error" in report:
        lines.append(f"Analysis failed: {cell(report['error'])}")
    else:
        new = report["new_clones"]
        if new:
            lines += ["| Format | First occurrence | Second occurrence | Lines | Tokens |",
                      "| --- | --- | --- | ---: | ---: |"]
            for clone in new:
                def location(side):
                    part = clone[side]
                    return f"{cell(part['name'])}:{part['start']}–{part['end']}"
                lines.append(f"| {cell(clone['format'])} | {location('firstFile')} | "
                             f"{location('secondFile')} | {clone['lines']} | {clone['tokens']} |")
            lines.append("")
        status = "failed" if new else "passed"
        lines.append(f"Clone gate {status}: {len(new)} new clone(s) among "
                     f"{report['counts']['total_clones']} reported clone(s).")
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--scan-status", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args(argv)
    def reject_constant(value):
        raise ValueError(f"invalid JSON constant: {value}")

    try:
        scan_status = args.scan_status.read_text(encoding="utf-8").strip()
        if scan_status not in ("0", "1"):
            raise ValueError(f"jscpd failed (exit status {scan_status})")
        report = evaluate(json.loads(args.report.read_text(encoding="utf-8"),
                                     parse_constant=reject_constant), args.repo)
        status = int(bool(report["new_clones"]))
        if status != int(scan_status):
            raise ValueError("jscpd exit status disagrees with new clone count")
    except (OSError, UnicodeError, ValueError, TypeError) as exc:
        report = {"schema_version": 1, "counts": {}, "new_clones": [], "error": str(exc)}
        status = 2
        print(f"clone gate analysis error: {exc}", file=sys.stderr)
    try:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False,
                                        allow_nan=False) + "\n", encoding="utf-8")
    except (OSError, UnicodeError, ValueError) as exc:
        report = {"schema_version": 1, "counts": {}, "new_clones": [],
                  "error": f"cannot write JSON report: {exc}"}
        status = 2
        print(f"clone gate report error: {exc}", file=sys.stderr)
    try:
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        print(f"clone gate report error: {exc}", file=sys.stderr)
        return 2
    return status


if __name__ == "__main__":
    sys.exit(main())
