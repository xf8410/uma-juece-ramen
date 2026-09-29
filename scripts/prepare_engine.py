"""Freeze/reconstruct an auditable upstream + working-tree patch; never mutate upstream.

Maintainer: python scripts/prepare_engine.py --capture --source ../../umaai-rs
Build/CI:   python scripts/prepare_engine.py [--source /path/to/upstream.git]
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engine"
DEST = ROOT / ".engine-source"
UPSTREAM = "https://github.com/xulai1001/umaai-rs.git"
PATHS = ["Cargo.toml", "Cargo.lock", "crates", "gamedata", "testsupport"]
DATA_FILES = ("constants.json", "cardDB.json", "umaDB.json", "text_data_dict.json",
              "events.json", "scenario_ramen.json", "scenario_onsen.json", "default_config.toml")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(*args: str, cwd: Path | None = None, **kwargs) -> bytes:
    return subprocess.check_output(args, cwd=cwd, **kwargs)


def valid_path(name: str) -> bool:
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts or "\\" in name:
        return False
    if name in ("Cargo.toml", "Cargo.lock"):
        return True
    if not p.parts or p.parts[0] not in ("crates", "gamedata", "testsupport"):
        return False
    if any(part.startswith(".") or part in ("target", "node_modules", "build") for part in p.parts):
        return False
    return p.suffix in (".rs", ".toml", ".json", ".html", ".css", ".js", ".txt", ".md", ".svg", ".j2", ".ico")


def archive(source: Path, revision: str) -> dict[str, bytes]:
    # Optional roots (for example testsupport) may not exist in an older base.
    roots = [name for name in run("git", "ls-tree", "--name-only", "-z", revision,
                                 "--", *PATHS, cwd=source).decode().split("\0") if name]
    if not roots:
        raise ValueError("Base revision contains no supported engine source roots")
    data = run("git", "archive", revision, "--", *roots, cwd=source)
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for entry in tar:
            if entry.isdir():
                continue
            if not entry.isfile():
                raise ValueError(f"Non-regular upstream file: {entry.name}")
            if valid_path(entry.name):
                data = tar.extractfile(entry).read()
                # The upstream Git blobs themselves may contain CRLF. Apply
                # the same canonical text encoding to base and working tree.
                result[entry.name] = data if PurePosixPath(entry.name).suffix == ".ico" else data.replace(b"\r\n", b"\n")
    return result


def write_tree(directory: Path, files: dict[str, bytes]) -> None:
    for name, data in files.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def fingerprint(files: dict[str, bytes]) -> dict[str, str]:
    return {name: sha(data) for name, data in sorted(files.items())}


def source_tree(source: Path) -> dict[str, bytes]:
    names = run("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard",
                "--", *PATHS, cwd=source).decode().split("\0")
    result = {}
    for name in sorted(set(names)):
        if not name or not valid_path(name):
            continue
        path = source / name
        if path.is_symlink():
            raise ValueError(f"Refusing symlink: {name}")
        if path.is_file():
            # Git snapshots use LF. Normalize text exactly once across Windows/Linux.
            data = path.read_bytes()
            result[name] = data if path.suffix == ".ico" else data.replace(b"\r\n", b"\n")
    return result


def capture(source: Path) -> dict:
    revision = run("git", "rev-parse", "HEAD", cwd=source).decode().strip()
    base = archive(source, revision)
    current = source_tree(source)
    ENGINE.mkdir(exist_ok=True)
    # An isolated index produces standard binary-capable Git patches, including new
    # runtime files, without staging or committing anything in the real repository.
    with tempfile.TemporaryDirectory(prefix=".staging-", dir=ENGINE) as temporary:
        staging = Path(temporary)
        write_tree(staging, base)
        run("git", "init", "-q", cwd=staging)
        run("git", "config", "core.autocrlf", "false", cwd=staging)
        run("git", "add", "--", ".", cwd=staging)
        base_tree = run("git", "write-tree", cwd=staging).decode().strip()
        for name in set(base) - set(current):
            (staging / name).unlink()
        write_tree(staging, current)
        run("git", "add", "-A", cwd=staging)
        patch = run("git", "diff", "--cached", "--binary", base_tree, cwd=staging)
    (ENGINE / "runtime.patch").write_bytes(patch)
    lock = {"schema_version": 1, "repository": UPSTREAM, "base_revision": revision,
            "patch_sha256": sha(patch), "files": fingerprint(current)}
    (ENGINE / "source-lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    return lock


def prepare(source: Path | None, lock: dict) -> None:
    patch = (ENGINE / "runtime.patch").read_bytes()
    if sha(patch) != lock["patch_sha256"]:
        raise ValueError("runtime.patch does not match source-lock.json; capture intentional changes first")
    if source is None:
        source = ROOT / ".engine-upstream"
        if not (source / ".git").is_dir():
            run("git", "clone", "--no-checkout", "--filter=blob:none", lock["repository"], str(source))
        run("git", "fetch", "--depth=1", "origin", lock["base_revision"], cwd=source)
    files = archive(source, lock["base_revision"])
    with tempfile.TemporaryDirectory(prefix=".staging-", dir=ENGINE) as temporary:
        staging = Path(temporary)
        write_tree(staging, files)
        # A separate git directory prevents git apply from interpreting paths as
        # relative to the Android repository above this staging directory.
        run("git", "init", "-q", cwd=staging)
        run("git", "config", "core.autocrlf", "false", cwd=staging)
        if patch:
            run("git", "apply", "--whitespace=nowarn", str(ENGINE / "runtime.patch"), cwd=staging)
        restored = {p.relative_to(staging).as_posix(): p.read_bytes()
                    for p in staging.rglob("*") if p.is_file()
                    and valid_path(p.relative_to(staging).as_posix())}
        if fingerprint(restored) != lock["files"]:
            raise ValueError("Reconstructed engine does not match the complete locked file set")
        # Only touch explicitly owned, generated .engine-source files. No broad clean.
        DEST.mkdir(exist_ok=True)
        for p in DEST.rglob("*"):
            if p.is_file() and valid_path(p.relative_to(DEST).as_posix()) and p.relative_to(DEST).as_posix() not in restored:
                p.unlink()
        write_tree(DEST, restored)
    print(f"Engine: {lock['base_revision']} + patch {lock['patch_sha256'][:12]} ({len(restored)} files)")


def package_data(lock: dict) -> None:
    assets = ROOT / "app/src/main/assets/gamedata"
    assets.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name in DATA_FILES:
        data = (DEST / "gamedata" / name).read_bytes()
        if name.endswith(".json"):
            json.loads(data)
        if sha(data) != lock["files"][f"gamedata/{name}"]:
            raise ValueError(f"Data hash mismatch: {name}")
        (assets / name).write_bytes(data)
        hashes[name] = sha(data)
    manifest = {"schema_version": 1,
                "engine_revision": f"{lock['base_revision']}+{lock['patch_sha256'][:12]}",
                "config_version": hashes["default_config.toml"], "files": hashes}
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--capture", action="store_true", help="Update tracked patch and lock from this explicitly selected working tree")
    parser.add_argument("--package-data", action="store_true")
    args = parser.parse_args()
    source = args.source.resolve() if args.source else None
    if args.capture and source is None:
        parser.error("--capture requires --source")
    lock = capture(source) if args.capture else json.loads((ENGINE / "source-lock.json").read_text(encoding="utf-8"))
    prepare(source, lock)
    if args.package_data:
        package_data(lock)


if __name__ == "__main__":
    main()
