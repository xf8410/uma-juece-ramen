from contextlib import contextmanager
import json
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import apply_collector_handoff as handoff


@contextmanager
def owned_workspace():
    """All recursive fixture cleanup is confined to this project's ignored .tools."""
    tools = (SCRIPTS.parent / ".tools").resolve()
    tools.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="collector-handoff-test-", dir=tools)).resolve()
    try:
        yield directory
    finally:
        target = directory.resolve()
        if target == tools or not target.is_relative_to(tools):
            raise RuntimeError("Refusing fixture cleanup outside .tools")
        def remove_read_only(function, name, error):
            affected = Path(name).resolve()
            if not affected.is_relative_to(target):
                raise RuntimeError("Refusing fixture cleanup outside the owned directory")
            affected.chmod(stat.S_IWRITE | stat.S_IREAD)
            function(name)
        shutil.rmtree(target, onexc=remove_read_only)


def fixture(directory, *, blob_crlf=False, checkout_crlf=False):
    source, checkout, bundle = directory / "source", directory / "checkout", directory / "bundle"
    source.mkdir()
    bundle.mkdir()
    subprocess.run(["git", "init", "-q", str(source)], check=True, capture_output=True)
    handoff.git(source, "config", "core.autocrlf", "false")
    before = b"first\nold\nlast\n"
    if blob_crlf:
        before = before.replace(b"\n", b"\r\n")
    (source / "source.txt").write_bytes(before)
    handoff.git(source, "add", ".")
    handoff.git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "baseline")
    revision = handoff.git(source, "rev-parse", "HEAD").decode().strip()
    after = b"first\nupdated\nlast\n"
    added = b"pub fn observed() {}\n"
    (source / "source.txt").write_bytes(after)
    (source / "observer").mkdir()
    (source / "observer/new.rs").write_bytes(added)
    handoff.git(source, "add", ".")
    patch = handoff.git(source, "diff", "--cached", "--binary", "--no-renames", "HEAD")
    (bundle / "collector.patch").write_bytes(patch)
    lock = {"schema_version": 1, "baseline_revision": revision,
            "patch_sha256": handoff.sha256(patch), "affected_files": {
                "source.txt": {"before_sha256_lf": handoff.sha256(handoff.normalize_lf(before)),
                               "before_git_blob_sha256": handoff.sha256(before),
                               "after_sha256_lf": handoff.sha256(after)},
                "observer/new.rs": {"before_sha256_lf": None, "before_git_blob_sha256": None,
                                    "after_sha256_lf": handoff.sha256(added)}}}
    (bundle / "collector-source-lock.json").write_text(json.dumps(lock), encoding="utf-8")
    subprocess.run(["git", "clone", "-q", "-c", f"core.autocrlf={'true' if checkout_crlf else 'false'}",
                    str(source), str(checkout)], check=True, capture_output=True)
    return checkout, bundle


class CollectorHandoffTests(unittest.TestCase):
    def test_default_check_is_read_only_then_apply_keeps_index_clean(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory)
            index = (checkout / ".git/index").read_bytes()
            original = (checkout / "source.txt").read_bytes()
            handoff.apply_handoff(checkout, handoff=bundle)
            self.assertEqual(original, (checkout / "source.txt").read_bytes())
            self.assertEqual(index, (checkout / ".git/index").read_bytes())
            self.assertFalse((checkout / "observer/new.rs").exists())
            handoff.apply_handoff(checkout, apply=True, handoff=bundle)
            self.assertEqual(b"first\nupdated\nlast\n", handoff.normalize_lf((checkout / "source.txt").read_bytes()))
            self.assertTrue((checkout / "observer/new.rs").is_file())
            self.assertEqual(b"", handoff.git(checkout, "diff", "--cached"))

    def test_lf_git_blob_applies_to_windows_crlf_checkout(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory, checkout_crlf=True)
            self.assertIn(b"\r\n", (checkout / "source.txt").read_bytes())
            handoff.apply_handoff(checkout, apply=True, handoff=bundle)

    def test_crlf_git_blob_needs_no_unrecorded_normalization(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory, blob_crlf=True, checkout_crlf=True)
            handoff.apply_handoff(checkout, apply=True, handoff=bundle)

    def test_dirty_checkout_is_rejected_without_overwrite(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory)
            (checkout / "source.txt").write_bytes(b"user change\n")
            with self.assertRaisesRegex(ValueError, "not clean"):
                handoff.apply_handoff(checkout, apply=True, handoff=bundle)
            self.assertEqual(b"user change\n", (checkout / "source.txt").read_bytes())

    def test_corrupt_patch_is_rejected_before_mutation(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory)
            (bundle / "collector.patch").write_bytes(b"corrupted")
            with self.assertRaisesRegex(ValueError, "SHA256"):
                handoff.apply_handoff(checkout, apply=True, handoff=bundle)
            self.assertEqual(b"", handoff.git(checkout, "status", "--porcelain"))

    def test_wrong_baseline_is_rejected(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory)
            lock = json.loads((bundle / "collector-source-lock.json").read_text())
            lock["baseline_revision"] = "0" * 40
            (bundle / "collector-source-lock.json").write_text(json.dumps(lock), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Baseline mismatch"):
                handoff.apply_handoff(checkout, handoff=bundle)

    def test_before_hash_mismatch_is_rejected_without_staging(self):
        with owned_workspace() as directory:
            checkout, bundle = fixture(directory)
            lock_path = bundle / "collector-source-lock.json"
            lock = json.loads(lock_path.read_text())
            lock["affected_files"]["source.txt"]["before_sha256_lf"] = "0" * 64
            lock_path.write_text(json.dumps(lock), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Before hash mismatch"):
                handoff.apply_handoff(checkout, apply=True, handoff=bundle)
            self.assertEqual(b"", handoff.git(checkout, "status", "--porcelain"))


if __name__ == "__main__":
    unittest.main()
