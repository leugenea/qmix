#!/usr/bin/env python3
"""Classify changed repository paths for GitHub Actions routing."""

import pathlib
import sys

ROUTES = ("ci", "android", "live")
SHARED_GO_FILES = {"go.mod", "go.sum"}
CI_ROOT_FILES = {".dockerignore", "Dockerfile", "Makefile"}


def classify(paths):
    routes = set()
    for raw_path in paths:
        path = raw_path.strip()
        if not path:
            continue

        if path.startswith(".github/scripts/workflow_paths"):
            routes.update(ROUTES)
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
        if path == ".github/workflows/ci.yml":
            routes.add("ci")
        elif path == ".github/workflows/android.yml":
            routes.add("android")
        elif path == ".github/workflows/live.yml":
            routes.add("live")
    return routes


def main():
    paths = (part.decode() for part in sys.stdin.buffer.read().split(b"\0"))
    selected = classify(paths)
    for route in ROUTES:
        print(f"{route}={'true' if route in selected else 'false'}")


if __name__ == "__main__":
    main()
