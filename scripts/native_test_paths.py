"""Resolve compiled fixture binaries with or without a shared Cargo target directory."""

import os
from pathlib import Path


def native_binary(crate: Path, package: str, profile: str = "debug") -> Path:
    """Match Cargo's CARGO_TARGET_DIR override, preserving legacy fixture paths."""
    target = os.environ.get("CARGO_TARGET_DIR")
    return (Path(target) if target else crate / "target") / profile / package
