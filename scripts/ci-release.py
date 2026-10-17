#!/usr/bin/env python3
"""Validate a release ref and package only verified, signed Android outputs."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"


def command(*args, cwd=None, data=None):
    return subprocess.check_output(args, cwd=cwd, input=data, stderr=subprocess.PIPE)


def validate_tag(repo, tag, default_branch, event_sha=None):
    if not re.fullmatch("v" + VERSION, tag):
        raise ValueError("Use a version tag such as v0.2.0 or v0.3.0-beta.1.")
    command("git", "check-ref-format", "refs/heads/" + default_branch, cwd=repo)
    commit = command("git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}", cwd=repo).decode().strip()
    if event_sha:
        triggered_commit = command("git", "rev-parse", "--verify", f"{event_sha}^{{commit}}", cwd=repo).decode().strip()
        if triggered_commit != commit:
            raise ValueError("The tag no longer points to the commit that triggered this run.")
    command("git", "merge-base", "--is-ancestor", commit, f"refs/remotes/origin/{default_branch}", cwd=repo)
    source = command("git", "show", f"{commit}:app/build.gradle.kts", cwd=repo).decode()
    names = re.findall(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$', source, re.MULTILINE)
    codes = re.findall(r"^\s*versionCode\s*=\s*([1-9][0-9]*)\s*$", source, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Expected one literal versionName and versionCode in app/build.gradle.kts.")
    if tag != "v" + names[0]:
        raise ValueError(f"Tag {tag} does not match versionName {names[0]}.")
    return {"tag": tag, "version": names[0], "version_code": codes[0], "commit": commit}


def metadata():
    values = validate_tag(
        Path.cwd(), os.environ["RELEASE_TAG"], os.environ["DEFAULT_BRANCH"],
        os.environ.get("EVENT_SHA") if os.environ.get("EVENT_NAME") == "push" else None,
    )
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")
    print(f"Validated {values['tag']} at {values['commit']} (versionCode {values['version_code']}).")


def restore_key():
    for name in ("LIGHTROVE_KEYSTORE_BASE64", "LIGHTROVE_KEYSTORE_PASSWORD", "LIGHTROVE_KEY_ALIAS", "LIGHTROVE_KEY_PASSWORD"):
        if not os.environ.get(name):
            raise ValueError(f"Missing GitHub Actions secret: {name}.")
    key = base64.b64decode("".join(os.environ["LIGHTROVE_KEYSTORE_BASE64"].split()), validate=True)
    if not key:
        raise ValueError("The signing keystore is empty.")
    # O_EXCL avoids silently replacing a file or following an existing symlink.
    descriptor = os.open(os.environ["LIGHTROVE_KEYSTORE_PATH"], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(key)


def certificate_digest(pem):
    der = command("openssl", "x509", "-outform", "DER", data=pem)
    return hashlib.sha256(der).hexdigest()


def check_release_manifest(manifest):
    if re.search(r":(?:debuggable|testOnly)\([^)]*\)\s*=\s*(?:true|\(type 0x12\)0xffffffff)\b", manifest):
        raise ValueError("Refusing a debuggable or test-only release APK.")


def check_apk_certificate(verification, expected):
    lines = [line.strip() for line in verification.splitlines()]
    counts = [match.group(1) for line in lines if (match := re.fullmatch(r"Number of signers: ([0-9]+)", line))]
    if counts != ["1"]:
        raise ValueError("Expected exactly one verified APK signer.")
    # Build Tools 37 labels schemes separately (V2 Signer / V3 Signer), while
    # older apksigner versions label the signer number. Only certificate hashes
    # from those records count; public-key and source-stamp hashes are distinct.
    pattern = r"(?:Signer #[0-9]+|V[0-9]+(?:\.[0-9]+)? Signer):? certificate SHA-256 digest:\s*(.*)"
    digests = [match.group(1).lower() for line in lines if (match := re.fullmatch(pattern, line))]
    if not digests or any(digest != expected.lower() for digest in digests):
        raise ValueError("APK signing certificate does not match signing/release-certificate.pem.")


def stage(build_directory=Path("app/build")):
    version = os.environ["RELEASE_VERSION"]
    if not re.fullmatch(VERSION, version):
        raise ValueError("Invalid release version.")
    release = build_directory / "outputs/apk/release"
    metadata = json.loads((release / "output-metadata.json").read_text())
    elements = metadata["elements"]
    if metadata["applicationId"] != "com.mcgeelee.photos" or len(elements) != 1:
        raise ValueError("Expected one universal Lightrove release APK.")
    element = elements[0]
    if element["versionName"] != version or str(element["versionCode"]) != os.environ["RELEASE_VERSION_CODE"]:
        raise ValueError("Built APK version does not match the validated tag.")
    apk = release / element["outputFile"]
    if apk.parent != release or apk.suffix != ".apk":
        raise ValueError("Unexpected APK output path.")
    aab = build_directory / "outputs/bundle/release/app-release.aab"
    expected = certificate_digest(Path("signing/release-certificate.pem").read_bytes())
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / os.environ["ANDROID_BUILD_TOOLS"]
    verification = command(str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)).decode()
    check_apk_certificate(verification, expected)
    manifest = command(str(build_tools / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml").decode()
    check_release_manifest(manifest)
    # Using the expected keystore makes the self-signed app certificate trusted;
    # -strict still rejects unsigned entries, wrong aliases, and invalid signatures.
    command(
        "jarsigner", "-verify", "-strict", "-keystore", os.environ["LIGHTROVE_KEYSTORE_PATH"],
        "-storepass:env", "LIGHTROVE_KEYSTORE_PASSWORD", str(aab), os.environ["LIGHTROVE_KEY_ALIAS"],
    )
    jar_certificates = command("keytool", "-printcert", "-rfc", "-jarfile", str(aab))
    if certificate_digest(jar_certificates) != expected:
        raise ValueError("AAB signing certificate does not match signing/release-certificate.pem.")
    destination = Path("release-artifacts")
    destination.mkdir(exist_ok=False)
    checksums = []
    for source, suffix in ((apk, "apk"), (aab, "aab")):
        target = destination / f"lightrove-{version}.{suffix}"
        shutil.copyfile(source, target)
        with target.open("rb") as content:
            digest = hashlib.sha256()
            for chunk in iter(lambda: content.read(1024 * 1024), b""):
                digest.update(chunk)
        checksums.append(f"{digest.hexdigest()}  {target.name}\n")
    (destination / "SHA256SUMS").write_text("".join(checksums), encoding="utf-8")
    print(f"Verified APK and AAB signer SHA-256: {expected}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("metadata", "restore-key", "stage"))
    parser.add_argument("--build-dir", type=Path, default=Path("app/build"), help="App build directory for stage (default: app/build)")
    arguments = parser.parse_args()
    try:
        if arguments.action == "stage":
            stage(arguments.build_dir)
        else:
            {"metadata": metadata, "restore-key": restore_key}[arguments.action]()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        # Do not echo subprocess output or secret values into an Actions log.
        if isinstance(error, subprocess.CalledProcessError):
            print(f"Release check failed: {Path(error.cmd[0]).name} returned {error.returncode}.", file=sys.stderr)
        else:
            print(f"Release check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
