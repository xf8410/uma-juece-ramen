"""Strict local/CI build. Requires pinned JDK/SDK/NDK/Rust; never downloads mutable data."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

from prepare_engine import ROOT, package_data, prepare, sha
from verify_artifact import check_elf, source_fingerprint, verify_apk


def execute(args: list[str], **kwargs) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, check=True, **kwargs)


def windows_build_environment(env: dict[str, str]) -> dict[str, str]:
    """Initialize MSVC in this child process, without changing system/user PATH."""
    if os.name != "nt":
        return env
    installer = Path(os.environ["ProgramFiles(x86)"]) / "Microsoft Visual Studio/Installer/vswhere.exe"
    installation = subprocess.check_output([str(installer), "-latest", "-products", "*", "-requires",
                                            "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                                            "-property", "installationPath"], text=True).strip()
    if not installation:
        raise ValueError("Windows cross builds require the Visual Studio C++ build tools")
    command = Path(installation) / "Common7/Tools/VsDevCmd.bat"
    # The installation path comes from vswhere, not user-supplied shell text.
    output = subprocess.check_output(f'"{command}" -no_logo -arch=x64 >nul && set',
                                     shell=True, env=env, text=True, errors="replace")
    result = dict(env)
    for line in output.splitlines():
        name, separator, value = line.partition("=")
        if separator and name and not name.startswith("="):
            result[name.upper()] = value
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Existing upstream checkout for offline source reconstruction")
    parser.add_argument("--native-only", action="store_true")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a",
                        help="ARM64 production target by default; x86_64 is an explicit emulator test build")
    parser.add_argument("--skip-prepare", action="store_true", help="Use already reconstructed locked source (still verifies hashes)")
    args = parser.parse_args()
    lock = json.loads((ROOT / "engine/source-lock.json").read_text(encoding="utf-8"))
    toolchain = json.loads((ROOT / "toolchains.json").read_text(encoding="utf-8"))
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise ValueError("Set JAVA_HOME to the pinned JDK before building")
    java_release = (Path(java_home) / "release").read_text(encoding="utf-8")
    if toolchain["jdk"] not in java_release:
        raise ValueError(f"JAVA_HOME must point to JDK {toolchain['jdk']}")
    if not args.skip_prepare:
        prepare(args.source.resolve() if args.source else None, lock)
    for name, checksum in lock["files"].items():
        if sha((ROOT / ".engine-source" / name).read_bytes()) != checksum:
            raise ValueError(f"Engine source changed: {name}")
    build_source_fingerprint = source_fingerprint()
    rust = subprocess.check_output(["rustc", "--version"], text=True, cwd=ROOT)
    if not rust.startswith("rustc " + toolchain["rust"] + " "):
        raise ValueError("Rust toolchain differs from toolchains.json")
    cargo_ndk = subprocess.check_output(["cargo", "ndk", "--version"], text=True, cwd=ROOT).strip()
    if cargo_ndk.split()[-1] != toolchain["cargo_ndk"]:
        raise ValueError("cargo-ndk differs from toolchains.json")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", str(Path(sdk) / "ndk" / toolchain["ndk"]) if sdk else ""))
    if not ndk.is_dir() or toolchain["ndk"] not in (ndk / "source.properties").read_text():
        raise ValueError(f"Install NDK {toolchain['ndk']} and set ANDROID_NDK_HOME or ANDROID_HOME")
    # cargo-ndk includes the complete environment in a panic report. Only pass
    # build/tool discovery variables; unrelated application credentials stay out.
    inherited = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
                 "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES",
                 "PROGRAMFILES(X86)", "PROGRAMW6432", "CARGO_HOME", "RUSTUP_HOME",
                 "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "LIB", "INCLUDE",
                 "NUMBER_OF_PROCESSORS"}
    env = {name: value for name, value in os.environ.items() if name.upper() in inherited}
    env = windows_build_environment(env)
    env["ANDROID_NDK_HOME"] = str(ndk.resolve())
    env["UMAAI_ENGINE_REVISION"] = f"{lock['base_revision']}+{lock['patch_sha256'][:12]}"
    env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
    execute(["cargo", "ndk", "-t", args.abi, "--platform", "26", "build", "--release", "--locked", "--lib"], cwd=ROOT / "rust", env=env)
    if source_fingerprint() != build_source_fingerprint:
        raise ValueError("Sources changed during compilation; rerun after edits finish to bind the correct provenance")
    triple = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}[args.abi]
    library = ROOT / f"rust/target/{triple}/release/libuma_jni.so"
    check_elf(library.read_bytes(), args.abi, str(library))
    destination = ROOT / f"app/src/main/jniLibs/{args.abi}/libuma_jni.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, destination)
    package_data(lock)
    assets = ROOT / "app/src/main/assets"
    manifest = {"schema_version": 1, "protocol_version": 2,
                "supported_snapshot_schema_versions": [1, 2],
                "android_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "android_source_fingerprint": build_source_fingerprint,
                "engine_revision": f"{lock['base_revision']}+{lock['patch_sha256'][:12]}",
                "engine_base_revision": lock["base_revision"], "engine_patch_sha256": lock["patch_sha256"],
                "engine_lock_sha256": sha((ROOT / "engine/source-lock.json").read_bytes()),
                "config_version": lock["files"]["gamedata/default_config.toml"],
                "data_manifest_sha256": sha((assets / "gamedata/manifest.json").read_bytes()),
                "model": None, "toolchains": toolchain, "abi": args.abi,
                "native_libraries": {f"{args.abi}/libuma_jni.so": sha(library.read_bytes())}}
    (assets / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    if not args.native_only:
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
        execute(command + [f"-PtestAbi={args.abi}", "testDebugUnitTest", "assembleDebug", "--no-daemon"], cwd=ROOT)
        apks = list((ROOT / "app/build/outputs/apk/debug").glob("*.apk"))
        if not apks:
            raise ValueError("Gradle completed without producing a debug APK")
        for apk in apks:
            verify_apk(apk, args.abi)


if __name__ == "__main__":
    main()
