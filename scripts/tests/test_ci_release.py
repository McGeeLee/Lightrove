"""Exercise release blockers against local Git fixtures; no network or secrets."""

import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("ci_release", Path(__file__).parents[1] / "ci-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTagTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release@example.invalid")
        (self.repo / "app").mkdir()
        self.commit_version("0.2.0")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("tag", "v0.2.0")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.PIPE).decode().strip()

    def commit_version(self, name):
        (self.repo / "app/build.gradle.kts").write_text(f'versionCode = 2\nversionName = "{name}"\n')
        self.git("add", ".")
        self.git("commit", "-m", "Version " + name)

    def test_existing_matching_tag_returns_exact_commit(self):
        values = release.validate_tag(self.repo, "v0.2.0", "main")
        self.assertEqual(values["commit"], self.git("rev-parse", "HEAD"))
        self.assertEqual(values["version"], "0.2.0")

    def test_missing_tag_is_rejected(self):
        with self.assertRaises(subprocess.CalledProcessError):
            release.validate_tag(self.repo, "v0.3.0", "main")

    def test_tag_version_mismatch_is_rejected(self):
        self.git("tag", "v0.3.0")
        with self.assertRaisesRegex(ValueError, "does not match"):
            release.validate_tag(self.repo, "v0.3.0", "main")

    def test_tag_outside_default_branch_is_rejected(self):
        self.git("switch", "-c", "unreviewed")
        self.commit_version("0.3.0")
        self.git("tag", "v0.3.0")
        with self.assertRaises(subprocess.CalledProcessError):
            release.validate_tag(self.repo, "v0.3.0", "main")

    def test_moved_trigger_tag_is_rejected(self):
        previous = self.git("rev-parse", "HEAD")
        self.commit_version("0.3.0")
        self.git("tag", "-f", "v0.2.0")
        with self.assertRaisesRegex(ValueError, "no longer points"):
            release.validate_tag(self.repo, "v0.2.0", "main", previous)

    def test_shell_or_output_injection_in_tag_is_rejected(self):
        for tag in ("v0.2.0\ncommit=bad", "v0.2.0;echo bad", "--help", "../../main"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.validate_tag(self.repo, tag, "main")


class SigningSecretTest(unittest.TestCase):
    def test_missing_secret_is_rejected_before_creating_file(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "Missing GitHub"):
            release.restore_key()

    def test_invalid_base64_is_rejected(self):
        environment = {name: "invalid!" for name in (
            "LIGHTROVE_KEYSTORE_BASE64", "LIGHTROVE_KEYSTORE_PASSWORD", "LIGHTROVE_KEY_ALIAS", "LIGHTROVE_KEY_PASSWORD"
        )}
        with patch.dict(os.environ, environment, clear=True), self.assertRaises(ValueError):
            release.restore_key()


class ReleaseManifestTest(unittest.TestCase):
    def test_debuggable_and_test_only_are_rejected(self):
        for attribute in ("debuggable", "testOnly"):
            for value in ("true", "(type 0x12)0xffffffff"):
                with self.subTest(attribute=attribute, value=value), self.assertRaises(ValueError):
                    release.check_release_manifest(f" A: http://schemas.android.com/apk/res/android:{attribute}(0x0101000f)={value}")

    def test_explicit_false_and_absent_flags_are_allowed(self):
        release.check_release_manifest(" A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=false")
        release.check_release_manifest(" E: manifest\n E: application")


class ApkCertificateTest(unittest.TestCase):
    expected = "69bebcec726f3864e0be9619dd0cc4b94f8f4ce3ef647615269ffb1fb686c3c7"
    different = "f" * 64

    def test_build_tools_37_actual_output_is_accepted(self):
        output = (
            "Number of signers: 1\n"
            "V2 Signer: certificate DN: CN=Lightrove, O=Lightrove\n"
            f"V2 Signer: certificate SHA-256 digest: {self.expected}\n"
            "V2 Signer: public key SHA-256 digest: 4efe43ba33988979d823675eeaf347c621d4207b5f575421ec89c0a777535861\n"
        )
        release.check_apk_certificate(output, self.expected)

    def test_legacy_format_is_accepted(self):
        release.check_apk_certificate(
            f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {self.expected}\n", self.expected
        )

    def test_same_certificate_in_multiple_schemes_is_accepted(self):
        output = "Number of signers: 1\n" + "\n".join(
            f"{scheme} Signer: certificate SHA-256 digest: {self.expected.upper()}" for scheme in ("V2", "V3", "V3.1")
        )
        release.check_apk_certificate(output, self.expected)

    def test_wrong_certificate_in_any_scheme_is_rejected(self):
        for label in ("Signer #1", "V2 Signer:", "V3 Signer:"):
            output = (
                f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {self.expected}\n"
                f"{label} certificate SHA-256 digest: {self.different}\n"
            )
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, "does not match"):
                release.check_apk_certificate(output, self.expected)

    def test_public_key_and_source_stamp_cannot_substitute_for_signer(self):
        output = (
            f"Number of signers: 1\nV2 Signer: public key SHA-256 digest: {self.expected}\n"
            f"Source Stamp Signer certificate SHA-256 digest: {self.expected}\n"
        )
        with self.assertRaisesRegex(ValueError, "does not match"):
            release.check_apk_certificate(output, self.expected)

    def test_unrelated_stamp_certificate_is_ignored(self):
        output = (
            f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {self.expected}\n"
            f"Source Stamp Signer certificate SHA-256 digest: {self.different}\n"
        )
        release.check_apk_certificate(output, self.expected)

    def test_missing_or_multiple_signer_count_is_rejected(self):
        for prefix in ("", "Number of signers: 0\n", "Number of signers: 2\n", "Number of signers: 1\nNumber of signers: 1\n"):
            with self.subTest(prefix=prefix), self.assertRaisesRegex(ValueError, "exactly one"):
                release.check_apk_certificate(prefix + f"V2 Signer: certificate SHA-256 digest: {self.expected}\n", self.expected)


if __name__ == "__main__":
    unittest.main()
