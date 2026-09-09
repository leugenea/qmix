#!/usr/bin/env python3
"""Summarize a gotestsum JUnit report as a markdown table (qmix#47).

Usage: test_summary.py REPORT.xml [HEADING]

Reads the <testsuite> elements (tests/failures/errors/skipped) and prints a
passed/failed/skipped table to stdout; CI steps redirect it into
$GITHUB_STEP_SUMMARY so the counts are visible on the run page.
"""

import sys
import xml.etree.ElementTree as ET


def main():
    if len(sys.argv) < 2:
        print("usage: test_summary.py REPORT.xml [HEADING]", file=sys.stderr)
        return 2
    total = failed = skipped = 0
    for suite in ET.parse(sys.argv[1]).getroot().iter("testsuite"):
        a = suite.attrib
        total += int(a.get("tests", 0))
        failed += int(a.get("failures", 0)) + int(a.get("errors", 0))
        skipped += int(a.get("skipped", 0))
    if len(sys.argv) > 2:
        print(sys.argv[2])
    print("| Outcome | Count |")
    print("|---|---:|")
    print(f"| Passed | {total - failed - skipped} |")
    print(f"| Failed | {failed} |")
    print(f"| Skipped | {skipped} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
