#!/usr/bin/env python3
"""Validate the versioned notes shared by every release destination."""

from pathlib import Path
import re
import sys


VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+\Z")


def release_notes_path(root: Path, version: str) -> Path:
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError(f"Invalid release version: {version!r}; expected X.Y.Z")
    return root / "changelogs" / f"{version}.md"


def validate_release_notes(root: Path, version: str) -> Path:
    path = release_notes_path(root, version)
    if not path.is_file():
        raise ValueError(f"Release notes are missing: {path}")
    if not path.read_text(encoding="utf-8").strip():
        raise ValueError(f"Release notes are empty: {path}")
    return path


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: release_notes.py X.Y.Z")
    try:
        print(validate_release_notes(Path.cwd(), sys.argv[1]))
    except (OSError, ValueError) as exc:
        sys.exit(str(exc))
