#!/usr/bin/env python3
"""Safely prepare the Compose bind directory and print its group ID."""

import argparse
import os
from pathlib import Path

_COMPONENTS = ("agent-apps-data", "qmix", "logs")
_OPEN_DIR = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


def prepare(root: Path) -> int:
    """Create the logging path without following symlinked components."""
    root_fd = os.open(root, _OPEN_DIR)
    current_fd = root_fd
    try:
        for component in _COMPONENTS:
            try:
                os.mkdir(component, mode=0o770, dir_fd=current_fd)
            except FileExistsError:
                pass
            next_fd = os.open(component, _OPEN_DIR, dir_fd=current_fd)
            if current_fd != root_fd:
                os.close(current_fd)
            current_fd = next_fd
        os.fchmod(current_fd, 0o770)
        return os.fstat(current_fd).st_gid
    finally:
        if current_fd != root_fd:
            os.close(current_fd)
        os.close(root_fd)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help=argparse.SUPPRESS,
    )
    args = parser.parse_args()
    print(prepare(args.root))


if __name__ == "__main__":
    main()
