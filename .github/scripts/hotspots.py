#!/usr/bin/env python3
"""Informational complexity/duplication × file churn ranking (qmix#242).

Consumes erosion.py's schema-2 function rows and the normalized jscpd schema-1
artifact. Input generation remains in the existing analyzers, never here.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import html
import json
import math
from pathlib import Path
import re
import subprocess
import sys

import erosion


def churn_by_file(root, ref, days, now, sources):
    """Count commits touching each current path; renames are not followed."""
    if days <= 0:
        raise ValueError("window days must be positive")
    if not sources:
        return Counter()
    if not isinstance(ref, str) or not ref or ref.startswith("-"):
        raise ValueError(f"invalid git ref: {ref}")
    resolved = subprocess.run(
        ["git", "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}"],
        cwd=root, capture_output=True, text=True, check=False)
    if resolved.returncode:
        raise ValueError(f"invalid git ref: {ref}")
    commit = resolved.stdout.strip()
    since = (now - timedelta(days=days)).isoformat()
    command = ["git", "log", commit, f"--since-as-filter={since}", f"--until={now.isoformat()}",
               "--no-renames", "--format=%H", "--name-only", "-z", "--", *sorted(sources)]
    completed = subprocess.run(command, cwd=root, capture_output=True, check=False)
    if completed.returncode:
        raise ValueError(f"git log failed for ref {ref}: {completed.stderr.decode(errors='replace').strip()}")
    counts = Counter()
    touched = set()
    for token in completed.stdout.decode("utf-8").split("\0"):
        # With -z, Git separates the hash from the first path with a newline.
        path = token.removeprefix("\n")
        if re.fullmatch(r"[0-9a-f]{40}", path):
            counts.update(touched)
            touched = set()
        elif path in sources:
            touched.add(path)
        elif path:
            raise ValueError(f"unexpected git log path: {path}")
    counts.update(touched)
    return counts


def skip_list(document):
    """Parse owner-maintained bullet entries only under the exact H2 heading."""
    lines = document.splitlines()
    try:
        start = lines.index("## Hotspots left as is") + 1
    except ValueError as exc:
        raise ValueError("missing ## Hotspots left as is heading") from exc
    functions, clones = {}, {}
    for line in lines[start:]:
        if line.startswith("## "):
            break
        if not line.startswith("- "):
            continue
        match = re.fullmatch(r"- (clone: )?`([^`]+)` [—-] (.+)", line)
        if not match:
            raise ValueError(f"malformed hotspot skip entry: {line}")
        kind, identifier, reason = match.groups()
        if kind:
            files = tuple(sorted(part.strip() for part in identifier.split(",")))
            if not files or len(set(files)) != len(files) or not all(files):
                raise ValueError(f"invalid clone file set: {identifier}")
            if files in clones:
                raise ValueError(f"duplicate clone skip entry: {identifier}")
            clones[files] = reason
        else:
            if "::" not in identifier or identifier in functions:
                raise ValueError(f"invalid or duplicate function skip ID: {identifier}")
            functions[identifier] = reason
    return functions, clones


def _number(value, label):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError(f"invalid {label}")
    return value


def _integer(value, label, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"invalid {label}")
    return value


def rank(erosion_report, duplicate_report, root, ref="main", days=90, now=None, skips=None):
    """Rank each language independently; group clones by unordered file set."""
    if now is None:
        now = datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError("now must have a timezone")
    now = now.astimezone(timezone.utc)
    if days <= 0:
        raise ValueError("window days must be positive")
    selected = set(erosion.discover_sources(root))
    if not selected:
        raise ValueError("no production sources selected")
    if skips is None:
        skips = ({}, {})
    if not isinstance(erosion_report, dict) or erosion_report.get("schema_version") != 2 or not isinstance(erosion_report.get("functions"), list):
        raise ValueError("invalid erosion report schema/functions")
    if not isinstance(duplicate_report, dict) or duplicate_report.get("schema_version") != 1 or not isinstance(duplicate_report.get("duplicates"), list):
        raise ValueError("invalid normalized jscpd report schema/duplicates")
    functions = erosion_report["functions"]
    clones = duplicate_report["duplicates"]
    counts = duplicate_report["statistics"]
    _integer(counts["total"]["clones"], "jscpd clone count")
    if counts["total"]["clones"] != len(clones):
        raise ValueError("jscpd clone count disagrees with duplicates")
    for fmt in ("go", "kotlin", "javascript"):
        _integer(counts["formats"][fmt]["clones"], "jscpd format clone count")
    sources_in_report = {row["file"] for row in functions}
    if not sources_in_report <= selected:
        raise ValueError("erosion report contains out-of-scope source")
    churn = churn_by_file(root, ref, days, now, selected)
    languages = {language: [] for language in erosion.LANGUAGES}
    seen = set()
    for row in functions:
        path, language, identity = row["file"], row["language"], row["id"]
        if language != erosion.language_for(path) or not isinstance(identity, str) or not identity.startswith(path + "::") or identity in seen:
            raise ValueError(f"invalid erosion function identity/language: {identity}")
        seen.add(identity)
        ccn = _integer(row["ccn"], "CCN", 1)
        nloc = _integer(row["nloc"], "NLOC")
        mass = _number(row["mass"], "mass")
        if not math.isclose(mass, ccn * math.sqrt(nloc), rel_tol=1e-8):
            raise ValueError(f"erosion function mass disagrees with CCN/NLOC: {identity}")
        function = row["function"]
        if not isinstance(function, str):
            raise ValueError(f"invalid erosion function name: {identity}")
        start = _integer(row["start"], "function start", 1)
        end = _integer(row["end"], "function end", start)
        if ccn <= 10:
            continue
        frequency = churn[path]
        entry = {"id": identity, "file": path, "function": function,
                 "start": start, "end": end,
                 "ccn": ccn, "nloc": nloc, "mass": mass,
                 "churn": frequency, "score": mass * frequency}
        languages[language].append(entry)
    grouped = defaultdict(list)
    formats = {"go": "Go", "kotlin": "Kotlin", "javascript": "JS"}
    format_counts = Counter()
    for clone in clones:
        fmt = clone["format"]
        if fmt not in formats:
            raise ValueError(f"invalid clone format: {fmt}")
        format_counts[fmt] += 1
        lines = _integer(clone["lines"], "clone lines", 1)
        sides = []
        for side in ("firstFile", "secondFile"):
            occurrence = clone[side]
            path = occurrence["name"]
            if path not in selected or erosion.language_for(path) != formats[fmt]:
                raise ValueError(f"jscpd clone has out-of-scope or mismatched source: {path}")
            start = _integer(occurrence["start"], "clone start", 1)
            end = _integer(occurrence["end"], "clone end", start)
            sides.append({"file": path, "start": start, "end": end})
        sides.sort(key=lambda side: (side["file"], side["start"], side["end"]))
        grouped[tuple(sorted({side["file"] for side in sides}))].append((lines, sides))
    if any(format_counts[key] != counts["formats"][key]["clones"] for key in formats):
        raise ValueError("jscpd format counts disagree with duplicates")
    duplication = []
    for files, members in grouped.items():
        frequency = max(churn[path] for path in files)
        lines = sum(size for size, _ in members)
        locations = sorted((sides for _, sides in members),
                           key=lambda pair: tuple((side["file"], side["start"], side["end"])
                                                  for side in pair))
        duplication.append({"files": list(files), "file": ", ".join(files),
                            "locations": locations,
                            "lines": lines, "churn": frequency, "score": lines * frequency})

    def order(entries, reasons, key):
        for entry in entries:
            reason = reasons.get(key(entry))
            entry["status"] = "skipped" if reason is not None else "ranked" if entry["churn"] else "cold"
            entry["rank"] = None
            if reason is not None:
                entry["reason"] = reason
        entries.sort(key=lambda row: ({"ranked": 0, "cold": 1, "skipped": 2}[row["status"]],
                                      -row["score"], row["file"], str(key(row))))
        for index, entry in enumerate((row for row in entries if row["status"] == "ranked"), 1):
            entry["rank"] = index
    for entries in languages.values():
        order(entries, skips[0], lambda row: row["id"])
    order(duplication, skips[1], lambda row: tuple(row["files"]))
    return {"schema_version": 1, "ref": ref, "window_days": days,
            "now": now.isoformat(), "languages": languages, "duplication": duplication}


def render_markdown(report):
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "\\|").replace("\n", " ")
    lines = ["# Code hotspots (qmix#242)", "",
             f"Informational only. Churn counts commits per file on `{cell(report['ref'])}` "
             f"during the last {report['window_days']} days (through {cell(report['now'])}); "
             "renames are not followed. Cold and skipped entries are not ranked.", "",
             "Kotlin CCN is not comparable to lizard's Go/JS CCN; functions rank per language."]
    for language, entries in report["languages"].items():
        lines += ["", f"## {language} complexity (CCN > 10)", "",
                  "| Rank | File | Function (location) | CCN | NLOC | Churn | Score | Status |",
                  "| ---: | --- | --- | ---: | ---: | ---: | ---: | --- |"]
        for row in entries:
            status = row["status"] + (f": {row['reason']}" if "reason" in row else "")
            lines.append(f"| {row['rank'] or '—'} | {cell(row['file'])} | "
                         f"{cell(row['function'])} ({row['start']}–{row['end']}) | "
                         f"{row['ccn']} | {row['nloc']} | {row['churn']} | {row['score']:.2f} | {cell(status)} |")
    lines += ["", "## Duplication (jscpd clones by file set)", "",
              "| Rank | File set | Clone locations | Lines | Churn (max) | Score | Status |",
              "| ---: | --- | --- | ---: | ---: | ---: | --- |"]
    for row in report["duplication"]:
        locations = "; ".join(" ↔ ".join(f"{side['file']}:{side['start']}–{side['end']}" for side in pair)
                              for pair in row["locations"])
        status = row["status"] + (f": {row['reason']}" if "reason" in row else "")
        lines.append(f"| {row['rank'] or '—'} | {cell(row['file'])} | {cell(locations)} | "
                     f"{row['lines']} | {row['churn']} | {row['score']:.2f} | {cell(status)} |")
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--erosion", type=Path, required=True)
    parser.add_argument("--duplication", type=Path, required=True)
    parser.add_argument("--skip-list", type=Path, default=Path("docs/code-quality.md"))
    parser.add_argument("--ref", default="main")
    parser.add_argument("--days", type=int, default=90)
    parser.add_argument("--now", help="ISO-8601 instant (fixture override; default current UTC)")
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        now = datetime.fromisoformat(args.now.replace("Z", "+00:00")) if args.now else None
        report = rank(json.loads(args.erosion.read_text(encoding="utf-8")),
                      json.loads(args.duplication.read_text(encoding="utf-8")),
                      args.repo.resolve(strict=True), args.ref, args.days, now,
                      skip_list(args.skip_list.read_text(encoding="utf-8")))
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, OverflowError) as exc:
        print(f"hotspot input/report error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
