#!/usr/bin/env python3
"""Reject unexpected Cyrillic text in tracked repository files."""

from __future__ import annotations

import argparse
from collections import Counter
import json
import re
import subprocess
import sys
from pathlib import Path


CYRILLIC = re.compile(
    r"[\u0400-\u052f\u1c80-\u1c8f\u2de0-\u2dff\ua640-\ua69f\U0001e030-\U0001e08f]"
)
GENERATED_PATHS = {".ua/knowledge-graph.json"}


def is_test_or_fixture_path(path: str) -> bool:
    parts = Path(path).parts
    return (
        path.endswith("_test.go")
        or "testdata" in parts
        or "fixtures" in parts
        or ("src" in parts and ("test" in parts or "androidTest" in parts))
    )


def load_allowlist(path: Path) -> tuple[set[str], dict[tuple[str, str], int]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("version") != 1:
        raise ValueError("The allowlist must declare version 1")
    ignored_paths: set[str] = set()
    allowed_lines: dict[tuple[str, str], int] = {}

    for entry in data.get("ignored_paths", []):
        tracked_path = entry.get("path", "")
        reason = entry.get("reason", "")
        if not tracked_path or not reason:
            raise ValueError("Every ignored path requires non-empty path and reason fields")
        if tracked_path not in GENERATED_PATHS:
            raise ValueError(f"{tracked_path} is not a recognized generated path")
        ignored_paths.add(tracked_path)

    for entry in data.get("allowed_lines", []):
        tracked_path = entry.get("path", "")
        text = entry.get("text", "")
        occurrences = entry.get("occurrences")
        reason = entry.get("reason", "")
        if (
            not tracked_path
            or not text
            or not reason
            or type(occurrences) is not int
            or occurrences < 1
        ):
            raise ValueError(
                "Every allowed line requires path, text, positive occurrences, and reason fields"
            )
        if not is_test_or_fixture_path(tracked_path):
            raise ValueError("Allowed Cyrillic lines must be in a test or fixture path")
        key = (tracked_path, text)
        if key in allowed_lines:
            raise ValueError(f"Duplicate allowed line for {tracked_path}")
        allowed_lines[key] = occurrences

    return ignored_paths, allowed_lines


def tracked_files(root: Path) -> list[str]:
    output = subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=root, stderr=subprocess.DEVNULL
    )
    return [path.decode("utf-8") for path in output.split(b"\0") if path]


def unexpected_lines(
    root: Path,
    paths: list[str],
    ignored_paths: set[str],
    allowed_lines: dict[tuple[str, str], int],
) -> tuple[list[tuple[str, int, str]], Counter[tuple[str, str]]]:
    findings: list[tuple[str, int, str]] = []
    allowed_counts: Counter[tuple[str, str]] = Counter()
    for relative_path in paths:
        if relative_path in ignored_paths:
            continue
        raw = (root / relative_path).read_bytes()
        if b"\0" in raw:
            continue
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(text.splitlines(), start=1):
            key = (relative_path, line)
            if key in allowed_lines:
                allowed_counts[key] += 1
            elif CYRILLIC.search(line):
                findings.append((relative_path, line_number, line.strip()))
    return findings, allowed_counts


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=Path(".github/cyrillic-allowlist.json"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    allowlist_path = args.allowlist
    if not allowlist_path.is_absolute():
        allowlist_path = root / allowlist_path

    try:
        ignored_paths, allowed_lines = load_allowlist(allowlist_path)
        findings, allowed_counts = unexpected_lines(
            root, tracked_files(root), ignored_paths, allowed_lines
        )
        for key, expected in allowed_lines.items():
            actual = allowed_counts[key]
            if actual != expected:
                raise ValueError(
                    f"allowlisted line count for {key[0]} is {actual}, expected {expected}"
                )
    except (OSError, subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as error:
        print(f"Cyrillic check configuration error: {error}", file=sys.stderr)
        return 2

    if findings:
        print("Unexpected Cyrillic text found:")
        for path, line_number, line in findings:
            print(f"{path}:{line_number}: {line}")
        return 1

    print("No unexpected Cyrillic text found in tracked maintained files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
