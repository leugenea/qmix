#!/usr/bin/env python3
"""Block new/worsened high-CCN production functions on PRs (qmix#199).

Analyze separate BASE and HEAD source roots with the same erosion.py analyzers.
Named IDs are compared exactly; remaining high-CCN functions may be paired by
body similarity to tolerate renames, moves and lizard's unstable anonymous IDs.
"""

import argparse
from difflib import SequenceMatcher
import html
import json
from pathlib import Path
import sys

import erosion

CCN_LIMIT = 10  # Strictly greater than 10 is a violation only when added or worsened.
SIMILARITY_LIMIT = 0.8


def normalized_body(root, row):
    """Collapse whitespace in source lines; exclude the declaration/name line.

    On single-line functions retain only the part after the body opener, so
    expression bodies and inline closures can still be matched after a rename.
    Comments are left intact: a different comment alone cannot manufacture a
    false match, while the 0.8 threshold permits small layout/comment changes.
    """
    source = (root / row["file"]).read_text(encoding="utf-8").splitlines()
    start, end = row["start"], row["end"]
    if not (1 <= start <= end <= len(source)):
        raise ValueError(f"{row['file']}:{start}: invalid analyzer line range {start}-{end}")
    lines = source[start - 1:end]
    if len(lines) == 1:
        line = lines[0]
        # Declaration-bearing lines can contain a complete expression function.
        # Prefer an arrow / block opener; '=' covers Kotlin expression bodies.
        for marker in ("=>", "{", "="):
            if marker in line:
                lines = [line.split(marker, 1)[1]]
                break
        else:
            lines = []
    else:
        lines = lines[1:]
    return " ".join(" ".join(lines).split())


def compare(base_root, head_root, lizard="lizard"):
    """Return a JSON-serializable comparison of both entire production trees."""
    base_rows = erosion.analyze(base_root, lizard)["functions"]
    head_rows = erosion.analyze(head_root, lizard)["functions"]
    base_named = {(row["language"], row["id"]): index for index, row in enumerate(base_rows)
                  if not row.get("anonymous", False)}
    used_base = set()
    paired_head = set()
    findings = []
    comparisons = []
    counts = {"matched_by_id": 0, "paired_by_similarity": 0,
              "added": 0, "anonymous": sum(bool(row.get("anonymous", False)) for row in head_rows),
              "base_functions": len(base_rows), "head_functions": len(head_rows)}

    def record(head, before, reason, similarity=None):
        entry = {"id": head["id"], "language": head["language"], "file": head["file"],
                 "name": head["function"], "start": head["start"], "end": head["end"],
                 "ccn_before": before["ccn"] if before is not None else None,
                 "ccn_after": head["ccn"], "reason": reason,
                 "anonymous": bool(head.get("anonymous", False))}
        if similarity is not None:
            entry["similarity"] = similarity
        comparisons.append(entry)
        if head["ccn"] > CCN_LIMIT and (before is None or head["ccn"] > before["ccn"]):
            findings.append(entry)

    for head_index, head in enumerate(head_rows):
        if head.get("anonymous", False):
            continue
        base_index = base_named.get((head["language"], head["id"]))
        if base_index is not None:
            used_base.add(base_index)
            paired_head.add(head_index)
            counts["matched_by_id"] += 1
            record(head, base_rows[base_index], "worsened" if head["ccn"] > base_rows[base_index]["ccn"] else "unchanged-or-improved")

    # Compare only still-unmatched high-CCN HEAD functions. Guard against
    # empty bodies (which would otherwise spuriously pair two unrelated files).
    candidates = []
    base_bodies = {}
    for head_index, head in enumerate(head_rows):
        if head_index in paired_head or head["ccn"] <= CCN_LIMIT:
            continue
        body = normalized_body(head_root, head)
        if not body:
            continue
        for base_index, base in enumerate(base_rows):
            if base_index in used_base or base["language"] != head["language"]:
                continue
            if base_index not in base_bodies:
                base_bodies[base_index] = normalized_body(base_root, base)
            other = base_bodies[base_index]
            if other:
                similarity = SequenceMatcher(None, other, body, autojunk=False).ratio()
                if similarity >= SIMILARITY_LIMIT:
                    candidates.append((similarity, head_index, base_index))

    # Highest similarity wins globally; one-to-one pairings avoid using one
    # deleted legacy hotspot to excuse multiple added functions.
    candidates.sort(key=lambda item: (-item[0], head_rows[item[1]]["id"],
                                      base_rows[item[2]]["id"]))
    for similarity, head_index, base_index in candidates:
        if head_index in paired_head or base_index in used_base:
            continue
        head, base = head_rows[head_index], base_rows[base_index]
        used_base.add(base_index)
        paired_head.add(head_index)
        counts["paired_by_similarity"] += 1
        record(head, base, "renamed+worsened" if head["ccn"] > base["ccn"] else "renamed/moved", similarity)

    for head_index, head in enumerate(head_rows):
        if head_index in paired_head or head["ccn"] <= CCN_LIMIT:
            continue
        counts["added"] += 1
        record(head, None, "added")

    findings.sort(key=lambda row: (row["file"], row["start"], row["name"]))
    return {"schema_version": 1, "ccn_limit": CCN_LIMIT,
            "similarity_limit": SIMILARITY_LIMIT, "counts": counts,
            "findings": findings, "comparisons": comparisons}


def render_markdown(report):
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "\\|").replace("\n", " ")

    lines = ["### Complexity gate (qmix#199)", "",
             f"Rule: fail when a function is added with CCN > {CCN_LIMIT}, or its CCN rises above {CCN_LIMIT}.",
             "Legacy hotspots that remain unchanged or improve pass. Kotlin and lizard CCN use different counting rules.", ""]
    if "error" in report:
        lines.append(f"Analysis failed: {cell(report['error'])}")
    elif report["findings"]:
        lines += ["| File | Function | CCN before → after | Reason |", "| --- | --- | ---: | --- |"]
        for row in report["findings"]:
            before = "—" if row["ccn_before"] is None else row["ccn_before"]
            location = f"{row['file']}:{row['start']}" if row["anonymous"] else row["file"]
            lines.append(f"| {cell(location)} | {cell(row['name'])} | {before} → {row['ccn_after']} | {row['reason']} |")
        lines += ["", f"Complexity gate failed: {len(report['findings'])} offending function(s)."]
    else:
        lines.append("Complexity gate passed: no added or worsened functions above CCN 10.")
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--head", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    parser.add_argument("--lizard", default="lizard")
    args = parser.parse_args(argv)
    try:
        base, head = args.base.resolve(strict=True), args.head.resolve(strict=True)
        if not base.is_dir() or not head.is_dir():
            raise ValueError("base and head must be directories")
        report = compare(base, head, args.lizard)
        status = int(bool(report["findings"]))
    except (OSError, UnicodeError, ValueError, RuntimeError) as exc:
        report = {"schema_version": 1, "ccn_limit": CCN_LIMIT,
                  "counts": {}, "findings": [], "error": str(exc)}
        status = 2
        print(f"complexity gate analysis error: {exc}", file=sys.stderr)
    try:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    except OSError as exc:
        print(f"complexity gate report error: {exc}", file=sys.stderr)
        return 2
    return status


if __name__ == "__main__":
    sys.exit(main())
