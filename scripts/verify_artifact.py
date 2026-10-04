"""Verify native ELF segments, APK ZIP alignment, data and exact build provenance."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import zipfile

from prepare_engine import DATA_FILES

ROOT = Path(__file__).resolve().parents[1]
PAGE = 16384
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def check_elf(data: bytes, abi: str, name: str) -> None:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected little-endian ELF64")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != MACHINES.get(abi):
        raise ValueError(f"{name}: ELF machine {machine} does not match {abi}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or not count or offset + size * count > len(data):
        raise ValueError(f"{name}: malformed ELF program headers")
    loads = 0
    for i in range(count):
        kind, _, file_offset, address, _, length, _, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + i * size)
        if kind != 1:
            continue
        loads += 1
        if alignment < PAGE or alignment & (alignment - 1) or (address - file_offset) % PAGE:
            raise ValueError(f"{name}: LOAD segment {i} is not 16 KB aligned")
        if file_offset + length > len(data):
            raise ValueError(f"{name}: truncated LOAD segment {i}")
    if not loads:
        raise ValueError(f"{name}: no LOAD segments")


def source_fingerprint() -> str:
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0")
    files = {}
    for name in sorted(set(paths)):
        if not name or name.endswith(".md") or name.startswith("docs/"):
            continue
        path = ROOT / name
        if path.is_file():
            data = path.read_bytes()
            if path.suffix in (".java", ".xml", ".gradle", ".properties", ".toml", ".json", ".rs", ".py", ".yml", ".yaml", ".patch"):
                data = data.replace(b"\r\n", b"\n")
            files[name] = sha(data)
    return sha(json.dumps(files, sort_keys=True, separators=(",", ":")).encode())


def verify_protocol_manifest(manifest: dict) -> None:
    # Manifest format and snapshot protocol versions are separate contracts.
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported build manifest format")
    if manifest.get("protocol_version") != 2 or manifest.get("supported_snapshot_schema_versions") != [1, 2]:
        raise ValueError("Build manifest must declare V2 snapshots and V1 compatibility")


def verify_inputs(abi: str = "arm64-v8a") -> dict:
    assets = ROOT / "app/src/main/assets"
    data_manifest = json.loads((assets / "gamedata/manifest.json").read_text(encoding="utf-8"))
    if data_manifest["schema_version"] != 1:
        raise ValueError("Unsupported gamedata manifest")
    if set(data_manifest["files"]) != set(DATA_FILES):
        raise ValueError("Gamedata manifest must contain the complete pinned data set")
    lock = json.loads((ROOT / "engine/source-lock.json").read_text(encoding="utf-8"))
    for name, checksum in data_manifest["files"].items():
        if Path(name).name != name or sha((assets / "gamedata" / name).read_bytes()) != checksum:
            raise ValueError(f"Gamedata mismatch: {name}")
        if checksum != lock["files"][f"gamedata/{name}"]:
            raise ValueError(f"Gamedata differs from the locked engine: {name}")
    manifest = json.loads((assets / "build-manifest.json").read_text(encoding="utf-8"))
    verify_protocol_manifest(manifest)
    if manifest["abi"] != abi:
        raise ValueError(f"Build manifest ABI {manifest['abi']} does not match requested {abi}")
    if manifest["android_source_fingerprint"] != source_fingerprint():
        raise ValueError("Sources changed after native packaging: rerun scripts/build_android.py")
    if manifest["engine_revision"] != data_manifest["engine_revision"]:
        raise ValueError("Engine/data provenance mismatch")
    expected_engine = f"{lock['base_revision']}+{lock['patch_sha256'][:12]}"
    if manifest["engine_revision"] != expected_engine:
        raise ValueError("Build manifest engine identity differs from the source lock")
    if manifest["engine_lock_sha256"] != sha((ROOT / "engine/source-lock.json").read_bytes()):
        raise ValueError("Engine source lock changed after native packaging")
    if manifest["data_manifest_sha256"] != sha((assets / "gamedata/manifest.json").read_bytes()):
        raise ValueError("Data manifest changed after native packaging")
    libraries = ROOT / "app/src/main/jniLibs"
    if not (libraries / abi / "libuma_jni.so").is_file():
        raise ValueError(f"Required {abi} libuma_jni.so is missing")
    actual = {}
    for path in (libraries / abi).rglob("*.so"):
        relative = path.relative_to(libraries).as_posix()
        data = path.read_bytes()
        check_elf(data, relative.split("/")[0], relative)
        actual[relative] = sha(data)
    if actual != manifest["native_libraries"]:
        raise ValueError("Native binaries differ from build manifest")
    print(f"Packaging inputs verified: {len(actual)} ELF libraries, {len(data_manifest['files'])} data files")
    return manifest


def verify_apk(apk: Path, abi: str = "arm64-v8a") -> None:
    manifest = verify_inputs(abi)
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
        native = [entry for entry in archive.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        if not any(entry.filename == f"lib/{abi}/libuma_jni.so" for entry in native):
            raise ValueError(f"APK is missing {abi} JNI library")
        actual = {}
        for entry in native:
            data = archive.read(entry)
            abi = entry.filename.split("/")[1]
            check_elf(data, abi, entry.filename)
            if entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"{entry.filename}: expected uncompressed native library")
            raw.seek(entry.header_offset)
            header = raw.read(30)
            name_size, extra_size = struct.unpack_from("<HH", header, 26)
            start = entry.header_offset + 30 + name_size + extra_size
            if start % PAGE:
                raise ValueError(f"{entry.filename}: APK ZIP data offset {start} is not 16 KB aligned")
            actual[entry.filename.removeprefix("lib/")] = sha(data)
        if actual != manifest["native_libraries"]:
            raise ValueError("APK native bytes differ from tested native inputs")
        packaged = archive.read("assets/build-manifest.json")
        if json.loads(packaged) != manifest:
            raise ValueError("APK build manifest mismatch")
        data_manifest = json.loads(archive.read("assets/gamedata/manifest.json"))
        if sha(archive.read("assets/gamedata/manifest.json")) != manifest["data_manifest_sha256"]:
            raise ValueError("APK data manifest mismatch")
        for name, checksum in data_manifest["files"].items():
            if sha(archive.read("assets/gamedata/" + name)) != checksum:
                raise ValueError(f"APK data mismatch: {name}")
    print(f"APK verified: {apk.name}; ELF and ZIP 16 KB alignment; sha256={sha(apk.read_bytes())}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--abi", choices=tuple(MACHINES), default="arm64-v8a")
    args = parser.parse_args()
    if args.apk:
        verify_apk(args.apk, args.abi)
    elif args.inputs:
        verify_inputs(args.abi)
    else:
        parser.error("choose --inputs or --apk")


if __name__ == "__main__":
    main()
