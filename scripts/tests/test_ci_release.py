"""Exercise release blockers against local Git fixtures; no network or secrets."""

import importlib.util
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile


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


def _uleb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def _registrar_dex(name, constructor=True, flags=0x10001, arguments=False, method_name="<init>"):
    """Minimal structural DEX fixture; no Android runtime or compiler is required."""
    strings = [name, "V", method_name] + (["I"] if arguments else [])
    type_strings = [0, 1] + ([3] if arguments else [])
    strings_offset = 112
    types_offset = strings_offset + 4 * len(strings)
    protos_offset = types_offset + 4 * len(type_strings)
    methods_offset = protos_offset + 12
    classes_offset = methods_offset + (8 if constructor else 0)
    data_offset = classes_offset + 32
    data = bytearray(data_offset)
    string_positions = []
    for string in strings:
        string_positions.append(len(data))
        data.extend(_uleb(len(string)) + string.encode() + b"\0")
    parameters_offset = 0
    if arguments:
        data.extend(b"\0" * (-len(data) % 4))
        parameters_offset = len(data)
        data.extend(struct.pack("<IH", 1, 2))
    class_data_offset = len(data)
    data.extend(bytes((0, 0, int(constructor), 0)))
    if constructor:
        data.extend(_uleb(0) + _uleb(flags) + _uleb(1))
    data[:8] = b"dex\n039\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    for position, count, offset in ((56, len(strings), strings_offset), (64, len(type_strings), types_offset),
                                    (72, 1, protos_offset), (88, int(constructor), methods_offset),
                                    (96, 1, classes_offset)):
        struct.pack_into("<II", data, position, count, offset)
    for index, position in enumerate(string_positions):
        struct.pack_into("<I", data, strings_offset + index * 4, position)
    for index, string in enumerate(type_strings):
        struct.pack_into("<I", data, types_offset + index * 4, string)
    struct.pack_into("<III", data, protos_offset, 1, 1, parameters_offset)
    if constructor:
        struct.pack_into("<HHI", data, methods_offset, 0, 0, 2)
    struct.pack_into("<8I", data, classes_offset, 0, 1, 0xffffffff, 0, 0xffffffff, 0, class_data_offset, 0)
    return data


def _apk_with_dex(entries):
    output = io.BytesIO()
    with ZipFile(output, "w") as archive:
        for name, data in entries:
            archive.writestr(name, data)
    output.seek(0)
    return output


class OcrRegistrarPackagingTest(unittest.TestCase):
    names = sorted(release.OCR_REGISTRARS)

    def apk(self, **options):
        return _apk_with_dex([
            ("classes.dex" if index == 0 else f"classes{index + 1}.dex", _registrar_dex(name, **options))
            for index, name in enumerate(self.names)
        ])

    def test_public_no_argument_constructors_across_dex_files_are_accepted(self):
        release.check_apk_ocr_registrars(self.apk())

    def test_class_and_init_strings_without_constructor_methods_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing public no-argument"):
            release.check_apk_ocr_registrars(self.apk(constructor=False))

    def test_private_parameterized_static_or_ordinary_methods_are_rejected(self):
        for options in ({"flags": 0x10002}, {"arguments": True}, {"flags": 0x10009}, {"method_name": "initialize"}):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "missing public no-argument"):
                release.check_apk_ocr_registrars(self.apk(**options))

    def test_missing_registrar_class_is_rejected(self):
        apk = _apk_with_dex([("classes.dex", _registrar_dex(self.names[0]))])
        with self.assertRaisesRegex(ValueError, "missing public no-argument"):
            release.check_apk_ocr_registrars(apk)

    def test_duplicate_registrar_definitions_are_rejected(self):
        apk = _apk_with_dex([("classes.dex", _registrar_dex(self.names[0])),
                             ("classes2.dex", _registrar_dex(self.names[0]))])
        with self.assertRaisesRegex(ValueError, "Duplicate OCR registrar"):
            release.check_apk_ocr_registrars(apk)

    def test_missing_dex_and_non_zip_apks_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "no classes.dex"):
            release.check_apk_ocr_registrars(_apk_with_dex([]))
        with self.assertRaisesRegex(ValueError, "Malformed release APK ZIP"):
            release.check_apk_ocr_registrars(io.BytesIO(b"not an APK"))

    def test_malformed_dex_offsets_sizes_and_integers_fail_closed(self):
        original = _registrar_dex(self.names[0])
        invalid_size = bytearray(original)
        struct.pack_into("<I", invalid_size, 32, len(original) + 1)
        invalid_table = bytearray(original)
        struct.pack_into("<II", invalid_table, 96, 1, len(original) + 4)
        invalid_string = bytearray(original)
        struct.pack_into("<I", invalid_string, 112, len(original) + 4)
        invalid_integer = bytearray(original)
        class_offset = struct.unpack_from("<I", original, 100)[0]
        member_offset = struct.unpack_from("<I", original, class_offset + 24)[0]
        invalid_integer[member_offset:member_offset + 5] = b"\xff" * 5
        for data in (b"dex\n039\0", invalid_size, invalid_table, invalid_string, invalid_integer):
            with self.subTest(data_length=len(data)), self.assertRaises(ValueError):
                release.check_apk_ocr_registrars(_apk_with_dex([("classes.dex", data)]))


if __name__ == "__main__":
    unittest.main()
