"""Check or apply the collector handoff to a clean, independent baseline checkout.

Default (read-only): python scripts/apply_collector_handoff.py --checkout PATH
Explicit mutation:  python scripts/apply_collector_handoff.py --checkout PATH --apply
No staging, commits, checkout changes, network calls, or deletion are performed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / "docs/handoff"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_lf(data: bytes) -> bytes:
    """Normalize text for host-independent checks; never rewrite checkout files."""
    data.decode("utf-8")
    return data.replace(b"\r\n", b"\n")


def git(checkout: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(checkout), *args], stderr=subprocess.PIPE)


def safe_relative(name: str) -> bool:
    value = PurePosixPath(name)
    return bool(value.parts) and not value.is_absolute() and ".." not in value.parts \
        and ".git" not in value.parts and "\\" not in name and ":" not in name


def validate(checkout: Path, handoff: Path = HANDOFF) -> tuple[dict, Path]:
    """Read-only prerequisite checks, including Git's actual working-tree patch check."""
    checkout = checkout.resolve(strict=True)
    if not (checkout / ".git").is_dir() or (checkout / ".git").is_symlink():
        raise ValueError("Use an independent clone with its own .git directory")
    top = Path(git(checkout, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    if top != checkout:
        raise ValueError("--checkout must name the repository root")
    lock = json.loads((handoff / "collector-source-lock.json").read_text(encoding="utf-8"))
    if lock.get("schema_version") != 1 or not re.fullmatch(r"[0-9a-f]{40}", lock.get("baseline_revision", "")):
        raise ValueError("Invalid collector source-lock schema or baseline revision")
    patch = handoff / "collector.patch"
    if sha256(patch.read_bytes()) != lock["patch_sha256"]:
        raise ValueError("collector.patch SHA256 differs from collector-source-lock.json")
    head = git(checkout, "rev-parse", "HEAD").decode().strip()
    if head != lock["baseline_revision"]:
        raise ValueError(f"Baseline mismatch: expected {lock['baseline_revision']}, found {head}")
    # Disable optional index refresh locks as even --check must remain read-only.
    dirty = git(checkout, "--no-optional-locks", "status", "--porcelain=v1", "--untracked-files=all")
    if dirty.strip():
        raise ValueError("Checkout is not clean; preserve existing changes and use a fresh independent clone")
    files = lock["affected_files"]
    if not isinstance(files, dict) or not files:
        raise ValueError("Source lock contains no affected files")
    patch_paths = set()
    for row in git(checkout, "apply", "--numstat", "-z", str(patch.resolve())).split(b"\0"):
        if row:
            fields = row.decode("utf-8").split("\t", 2)
            if len(fields) != 3:
                raise ValueError("Unsupported patch path record")
            patch_paths.add(fields[2])
    if patch_paths != set(files):
        raise ValueError("Patch file set differs from the source lock")
    for name, expected in files.items():
        if not safe_relative(name):
            raise ValueError(f"Unsafe source-lock path: {name}")
        target = checkout / name
        if target.is_symlink() or not target.resolve().is_relative_to(checkout):
            raise ValueError(f"Affected path escapes checkout: {name}")
        before = expected["before_sha256_lf"]
        if before is None:
            if target.exists():
                raise ValueError(f"New file already exists: {name}")
            continue
        if not target.is_file() or sha256(normalize_lf(target.read_bytes())) != before:
            raise ValueError(f"Before hash mismatch: {name}")
        original = git(checkout, "show", f"{head}:{name}")
        if sha256(original) != expected["before_git_blob_sha256"]:
            raise ValueError(f"Baseline Git blob hash mismatch: {name}")
    # Use normal Git autocrlf/attributes handling. The patch was generated from
    # raw baseline blobs, so no private pre-normalization or index mutation is needed.
    git(checkout, "apply", "--check", "--whitespace=nowarn", str(patch.resolve()))
    return lock, patch


def apply_handoff(checkout: Path, *, apply: bool = False, handoff: Path = HANDOFF) -> dict:
    """Validate, optionally apply without staging, then verify every changed/new file."""
    checkout = checkout.resolve(strict=True)
    lock, patch = validate(checkout, handoff)
    if apply:
        git(checkout, "apply", "--whitespace=nowarn", str(patch.resolve()))
        for name, expected in lock["affected_files"].items():
            target = checkout / name
            after = expected["after_sha256_lf"]
            if after is None:
                if target.exists():
                    raise ValueError(f"Deleted file still exists: {name}")
            elif not target.is_file() or sha256(normalize_lf(target.read_bytes())) != after:
                raise ValueError(f"After hash mismatch: {name}; inspect the checkout before continuing")
    return lock


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=Path, required=True)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--check", action="store_true", help="Read-only validation (the default)")
    action.add_argument("--apply", action="store_true", help="Apply to the clean baseline checkout; never stage or commit")
    args = parser.parse_args()
    try:
        lock = apply_handoff(args.checkout, apply=args.apply)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        detail = error.stderr.decode("utf-8", errors="replace").strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        parser.exit(1, f"Collector handoff failed: {detail}\n")
    mode = "APPLIED and after hashes verified" if args.apply else "CHECK passed; checkout unchanged"
    print(f"{mode}: {len(lock['affected_files'])} files; baseline {lock['baseline_revision']}; patch {lock['patch_sha256']}")


if __name__ == "__main__":
    main()
