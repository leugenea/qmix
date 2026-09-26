#!/usr/bin/env python3
"""Classify changed repository paths for GitHub Actions routing."""

import os
import pathlib
import sys

from erosion import is_in_scope

ROUTES = ("ci", "android", "live")
OUTPUTS = (*ROUTES, "erosion", "duplication")
SHARED_GO_FILES = {"go.mod", "go.sum"}
CI_ROOT_FILES = {".dockerignore", "Dockerfile", "Makefile"}


def classify(paths, root=None):
    if root is None:
        root = pathlib.Path(__file__).resolve().parents[2]
    routes = set()
    for raw_path in paths:
        path = raw_path.strip()
        if not path:
            continue

        if path.startswith(".github/scripts/workflow_paths"):
            routes.update(OUTPUTS)
        in_scope = is_in_scope(path, root)
        if in_scope or path in {
            ".github/scripts/erosion.py", ".github/scripts/benchmark_metrics.py",
            ".github/scripts/benchmark_metrics_test.py", ".github/requirements-lizard.txt",
            ".github/scripts/kotlin_complexity.py", ".github/scripts/kotlin_complexity_test.py",
        }:
            routes.add("erosion")
        # The erosion job executes this test suite, even for a test-only edit.
        if path == ".github/scripts/erosion_test.py":
            routes.add("erosion")
        # qmix#200 imports erosion's source discovery and scope; route changes
        # to that shared input even when no production file changed.
        if in_scope or path in {
            ".jscpd.json", ".github/scripts/jscpd_report.py",
            ".github/scripts/jscpd_report_test.py", ".github/scripts/erosion.py",
        }:
            routes.add("duplication")
        if path in SHARED_GO_FILES:
            routes.update(ROUTES)
        if path.startswith("android/"):
            routes.add("android")
        if path.startswith("internal/buildinfo/"):
            routes.update(("ci", "android"))
        elif path.startswith("internal/stream/"):
            routes.update(("ci", "live"))
        elif path.startswith(("cmd/", "internal/")) or pathlib.PurePosixPath(path).suffix == ".go":
            routes.add("ci")
        if path in CI_ROOT_FILES or path.startswith(".github/scripts/"):
            routes.add("ci")
        if path.startswith(".github/scripts/generate_sbom"):
            routes.add("android")
        if path == ".github/workflows/ci.yml":
            routes.update(("ci", "erosion", "duplication"))
        elif path == ".github/workflows/android.yml":
            routes.add("android")
        elif path == ".github/workflows/live.yml":
            routes.add("live")
    return routes


def main():
    paths = (part.decode() for part in sys.stdin.buffer.read().split(b"\0"))
    selected = classify(paths)
    if os.environ.get("FORCE_EROSION") == "true":
        selected.add("erosion")
    for route in OUTPUTS:
        print(f"{route}={'true' if route in selected else 'false'}")


if __name__ == "__main__":
    main()
