from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import prepare_engine as prepare
import verify_artifact as verify


def elf(alignment=16384, machine=183):
    content = bytearray(120)
    content[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", content, 18, machine)
    struct.pack_into("<Q", content, 32, 64)
    struct.pack_into("<HH", content, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", content, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(content)


class ProtocolManifestTests(unittest.TestCase):
    def test_snapshot_protocol_is_separate_from_manifest_format(self):
        verify.verify_protocol_manifest({"schema_version": 1, "protocol_version": 2,
                                         "supported_snapshot_schema_versions": [1, 2]})

    def test_rejects_missing_or_stale_snapshot_protocol(self):
        for fields in ({}, {"protocol_version": 1},
                       {"protocol_version": 2, "supported_snapshot_schema_versions": [1]}):
            with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, "V2 snapshots"):
                verify.verify_protocol_manifest({"schema_version": 1, **fields})

    def test_rejects_confusing_snapshot_version_with_manifest_format(self):
        with self.assertRaisesRegex(ValueError, "manifest format"):
            verify.verify_protocol_manifest({"schema_version": 2, "protocol_version": 2,
                                             "supported_snapshot_schema_versions": [1, 2]})


class NativeGateTests(unittest.TestCase):
    def test_valid_arm64_16kb(self):
        verify.check_elf(elf(), "arm64-v8a", "valid.so")

    def test_rejects_4kb_segments(self):
        with self.assertRaisesRegex(ValueError, "not 16 KB aligned"):
            verify.check_elf(elf(4096), "arm64-v8a", "bad.so")

    def test_rejects_wrong_architecture(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify.check_elf(elf(machine=62), "arm64-v8a", "bad.so")

    def test_rejects_truncated_library(self):
        with self.assertRaisesRegex(ValueError, "malformed"):
            verify.check_elf(elf()[:-1], "arm64-v8a", "bad.so")


class SourceLockTests(unittest.TestCase):
    def test_rejects_non_source_paths(self):
        for path in ("../Cargo.toml", "/Cargo.toml", "crates/x/.env", "crates/x/target/a.rs", "crates/x/link.so"):
            self.assertFalse(prepare.valid_path(path), path)

    def test_capture_roundtrip_preserves_dirty_and_untracked_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, engine, dest = root / "upstream", root / "engine", root / "prepared"
            source.mkdir()
            engine.mkdir()
            (source / "crates/core/src").mkdir(parents=True)
            (source / "gamedata").mkdir()
            (source / "Cargo.toml").write_text("[workspace]\n", encoding="utf-8")
            (source / "Cargo.lock").write_text("version = 4\n", encoding="utf-8")
            (source / "crates/core/src/lib.rs").write_text("pub fn old() {}\n", encoding="utf-8")
            (source / "gamedata/constants.json").write_text("{}\n", encoding="utf-8")
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=source)
            git("init", "-q")
            git("config", "core.autocrlf", "false")
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "base")
            (source / "crates/core/src/lib.rs").write_text("pub fn new() {}\n", encoding="utf-8")
            (source / "crates/runtime/src").mkdir(parents=True)
            (source / "crates/runtime/src/lib.rs").write_text("pub fn session() {}\n", encoding="utf-8")
            (source / "crates/runtime/.env").write_text("SECRET=never-export\n", encoding="utf-8")
            before = git("status", "--porcelain")
            with patch.multiple(prepare, ROOT=root, ENGINE=engine, DEST=dest):
                lock = prepare.capture(source)
                self.assertNotIn(b"gamedata/constants.json", (engine / "runtime.patch").read_bytes())
                prepare.prepare(source, lock)
                self.assertEqual((dest / "crates/runtime/src/lib.rs").read_text(), "pub fn session() {}\n")
                self.assertEqual((dest / "crates/core/src/lib.rs").read_text(), "pub fn new() {}\n")
                self.assertFalse((dest / "crates/runtime/.env").exists())
                self.assertEqual(git("status", "--porcelain"), before)
                (engine / "runtime.patch").write_bytes(b"corruption")
                with self.assertRaisesRegex(ValueError, "does not match"):
                    prepare.prepare(source, lock)


class ApkGateTests(unittest.TestCase):
    def make_apk(self, destination, alignment, abi="arm64-v8a"):
        library = elf(machine=verify.MACHINES[abi])
        data = b"{}"
        data_manifest = json.dumps({"files": {"constants.json": verify.sha(data)}}).encode()
        manifest = {"native_libraries": {f"{abi}/libuma_jni.so": verify.sha(library)},
                    "data_manifest_sha256": verify.sha(data_manifest)}
        with zipfile.ZipFile(destination, "w") as apk:
            entry = zipfile.ZipInfo(f"lib/{abi}/libuma_jni.so")
            padding = alignment - (30 + len(entry.filename))
            entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
            apk.writestr(entry, library)
            apk.writestr("assets/build-manifest.json", json.dumps(manifest))
            apk.writestr("assets/gamedata/manifest.json", data_manifest)
            apk.writestr("assets/gamedata/constants.json", data)
        return manifest

    def test_aligned_apk_passes(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "aligned.apk"
            manifest = self.make_apk(apk, 16384)
            with patch.object(verify, "verify_inputs", return_value=manifest):
                verify.verify_apk(apk)

    def test_4kb_zip_rejected_even_with_16kb_elf(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "unaligned.apk"
            manifest = self.make_apk(apk, 4096)
            with patch.object(verify, "verify_inputs", return_value=manifest):
                with self.assertRaisesRegex(ValueError, "APK ZIP data offset"):
                    verify.verify_apk(apk)

    def test_explicit_emulator_abi_is_checked(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "emulator.apk"
            manifest = self.make_apk(apk, 16384, "x86_64")
            with patch.object(verify, "verify_inputs", return_value=manifest):
                verify.verify_apk(apk, "x86_64")
                with self.assertRaisesRegex(ValueError, "missing arm64-v8a"):
                    verify.verify_apk(apk)


if __name__ == "__main__":
    unittest.main()
import json
