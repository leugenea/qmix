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
GENERATED_PATHS: set[str] = set()
ANDROID_LEGACY_REGION = re.compile(r"^r(?:[A-Z]{2}|\d{3})$")
ISO_639_1_LANGUAGES = frozenset(
    """aa ab ae af ak am an ar as av ay az ba be bg bh bi bm bn bo br bs ca ce ch co
    cr cs cu cv cy da de dv dz ee el en eo es et eu fa ff fi fj fo fr fy ga gd gl gn gu
    gv ha he hi ho hr ht hu hy hz ia id ie ig ii ik io is it iu ja jv ka kg ki kj kk kl
    km kn ko kr ks ku kv kw ky la lb lg li ln lo lt lu lv mg mh mi mk ml mn mr ms mt my
    na nb nd ne ng nl nn no nr nv ny oc oj om or os pa pi pl ps pt qu rm rn ro ru rw sa
    sc sd se sg si sk sl sm sn so sq sr ss st su sv sw ta te tg th ti tk tl tn to tr ts
    tt tw ty ug uk ur uz ve vi vo wa wo xh yi yo za zh zu""".split()
)
ANDROID_MCC = re.compile(r"^mcc\d{3}$")
ANDROID_MNC = re.compile(r"^mnc\d{2,3}$")
ANDROID_POST_LOCALE_QUALIFIERS: tuple[tuple[re.Pattern[str], int], ...] = (
    (re.compile(r"^(?:ldltr|ldrtl)$"), 1),
    (re.compile(r"^sw[1-9]\d*dp$"), 2),
    (re.compile(r"^w[1-9]\d*dp$"), 3),
    (re.compile(r"^h[1-9]\d*dp$"), 4),
    (re.compile(r"^(?:small|normal|large|xlarge)$"), 5),
    (re.compile(r"^(?:long|notlong)$"), 6),
    (re.compile(r"^(?:round|notround)$"), 7),
    (re.compile(r"^(?:widecg|nowidecg)$"), 8),
    (re.compile(r"^(?:highdr|lowdr)$"), 9),
    (re.compile(r"^(?:port|land)$"), 10),
    (re.compile(r"^(?:car|desk|television|appliance|watch|vrheadset)$"), 11),
    (re.compile(r"^(?:night|notnight)$"), 12),
    (re.compile(r"^(?:ldpi|mdpi|tvdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|anydpi|nodpi)$"), 13),
    (re.compile(r"^(?:notouch|finger)$"), 14),
    (re.compile(r"^(?:keysexposed|keyshidden|keyssoft)$"), 15),
    (re.compile(r"^(?:nokeys|qwerty|12key)$"), 16),
    (re.compile(r"^(?:navexposed|navhidden)$"), 17),
    (re.compile(r"^(?:nonav|dpad|trackball|wheel)$"), 18),
    (re.compile(r"^\d+x\d+$"), 19),
    (re.compile(r"^v[1-9]\d*$"), 20),
)


def is_android_bcp47_locale(qualifier: str) -> bool:
    subtags = qualifier.split("+")
    if len(subtags) < 2 or subtags[0] != "b" or subtags[1] not in ISO_639_1_LANGUAGES:
        return False
    index = 2
    if index < len(subtags) and re.fullmatch(r"[A-Z][a-z]{3}", subtags[index]):
        index += 1
    if index < len(subtags) and re.fullmatch(r"(?:[A-Z]{2}|\d{3})", subtags[index]):
        index += 1
    variants = subtags[index:]
    return (
        all(
            re.fullmatch(r"(?:[A-Za-z0-9]{5,8}|\d[A-Za-z0-9]{3})", variant)
            for variant in variants
        )
        and len({variant.casefold() for variant in variants}) == len(variants)
    )


def is_test_or_fixture_path(path: str) -> bool:
    parts = Path(path).parts
    return (
        path.endswith("_test.go")
        or "testdata" in parts
        or "fixtures" in parts
        or ("src" in parts and ("test" in parts or "androidTest" in parts))
    )


def is_android_locale_string_resource(path: str) -> bool:
    parts = Path(path).parts
    if not (
        len(parts) == 7
        and parts[-1] == "strings.xml"
        and parts[:5] == ("android", "app", "src", "main", "res")
    ):
        return False

    qualifiers = parts[-2].split("-")
    if len(qualifiers) < 2 or qualifiers[0] != "values":
        return False
    index = 1
    if index < len(qualifiers) and ANDROID_MCC.fullmatch(qualifiers[index]):
        index += 1
        if index < len(qualifiers) and ANDROID_MNC.fullmatch(qualifiers[index]):
            index += 1
    if index >= len(qualifiers):
        return False
    first_qualifier = qualifiers[index]
    if any(pattern.fullmatch(first_qualifier) for pattern, _ in ANDROID_POST_LOCALE_QUALIFIERS):
        return False
    if is_android_bcp47_locale(first_qualifier):
        index += 1
    elif first_qualifier in ISO_639_1_LANGUAGES:
        index += 1
        if index < len(qualifiers) and ANDROID_LEGACY_REGION.fullmatch(qualifiers[index]):
            index += 1
    else:
        return False

    previous_rank = 0
    for qualifier in qualifiers[index:]:
        rank = next(
            (candidate for pattern, candidate in ANDROID_POST_LOCALE_QUALIFIERS if pattern.fullmatch(qualifier)),
            None,
        )
        if rank is None or rank <= previous_rank:
            return False
        if rank == 19:
            width, height = (int(value) for value in qualifier.split("x"))
            if width <= height or height == 0:
                return False
        previous_rank = rank
    return True


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
        if relative_path in ignored_paths or is_android_locale_string_resource(relative_path):
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
