#!/usr/bin/env python3
"""Create a local release identity and provision GitHub Actions Secrets.

Passwords are never written to command-line arguments or standard output.
Existing keys and repository signing secrets are never replaced implicitly.
"""

import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / ".signing"
PROPERTIES = PRIVATE / "release.properties"
KEYSTORE = PRIVATE / "lightrove-release.p12"
CERTIFICATE = ROOT / "signing" / "release-certificate.pem"
SECRET_NAMES = (
    "LIGHTROVE_KEYSTORE_BASE64",
    "LIGHTROVE_KEYSTORE_PASSWORD",
    "LIGHTROVE_KEY_ALIAS",
    "LIGHTROVE_KEY_PASSWORD",
)


def keytool():
    java_home = os.environ.get("JAVA_HOME")
    candidates = [Path(java_home) / "bin/keytool"] if java_home else []
    candidates += [Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool")]
    return next((str(p) for p in candidates if p.is_file()), None) or shutil.which("keytool") or sys.exit("Set JAVA_HOME to a JDK containing keytool.")


def fingerprint():
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(CERTIFICATE.read_text())).hexdigest()


def create():
    if any(p.exists() for p in (PROPERTIES, KEYSTORE, CERTIFICATE)):
        raise SystemExit("Signing files already exist; refusing to replace the release identity.")
    os.umask(0o077)
    PRIVATE.mkdir(mode=0o700, exist_ok=True)
    PRIVATE.chmod(0o700)
    password = secrets.token_urlsafe(48)
    alias = "lightrove"
    # This generated format uses ASCII values without Properties escape sequences.
    with PROPERTIES.open("x", encoding="ascii") as config:
        config.write(f"storeFile=.signing/lightrove-release.p12\nstorePassword={password}\nkeyAlias={alias}\nkeyPassword={password}\n")
    env = {**os.environ, "LIGHTROVE_GENERATED_PASSWORD": password}
    subprocess.run([
        keytool(), "-genkeypair", "-noprompt", "-keystore", str(KEYSTORE),
        "-storetype", "PKCS12", "-alias", alias, "-keyalg", "RSA", "-keysize", "4096",
        "-sigalg", "SHA256withRSA", "-validity", "10000", "-dname", "CN=Lightrove,O=Lightrove",
        "-storepass:env", "LIGHTROVE_GENERATED_PASSWORD", "-keypass:env", "LIGHTROVE_GENERATED_PASSWORD",
    ], env=env, check=True)
    KEYSTORE.chmod(0o600)
    CERTIFICATE.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        keytool(), "-exportcert", "-rfc", "-keystore", str(KEYSTORE), "-alias", alias,
        "-storepass:env", "LIGHTROVE_GENERATED_PASSWORD", "-file", str(CERTIFICATE),
    ], env=env, check=True)
    CERTIFICATE.chmod(0o644)
    print("Created private signing files in .signing/ (ignored by Git).")
    print("Public certificate SHA-256:", fingerprint())


def upload(repo, replace):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise SystemExit("Use --repo owner/repository.")
    # Only read the generated local file; do not accept secrets as CLI arguments.
    values = dict(line.split("=", 1) for line in PROPERTIES.read_text().splitlines() if line and not line.startswith("#"))
    store = ROOT / values["storeFile"]
    required = [values.get(k) for k in ("storeFile", "storePassword", "keyAlias", "keyPassword")]
    if not all(required) or not store.is_file():
        raise SystemExit("Local signing files are incomplete.")
    # Confirm these credentials belong to the committed public identity before uploading.
    env = {**os.environ, "LIGHTROVE_LOCAL_PASSWORD": values["storePassword"]}
    actual_certificate = subprocess.check_output([
        keytool(), "-exportcert", "-keystore", str(store), "-alias", values["keyAlias"],
        "-storepass:env", "LIGHTROVE_LOCAL_PASSWORD",
    ], env=env)
    if hashlib.sha256(actual_certificate).hexdigest() != fingerprint():
        raise SystemExit("Keystore does not match the public release certificate.")
    canonical = subprocess.check_output(["gh", "repo", "view", repo, "--json", "nameWithOwner", "--jq", ".nameWithOwner"], text=True).strip()
    if canonical.lower() != repo.lower():
        raise SystemExit("Repository identity mismatch.")
    existing = subprocess.check_output(["gh", "secret", "list", "--repo", repo, "--json", "name", "--jq", ".[].name"], text=True).splitlines()
    if set(existing).intersection(SECRET_NAMES):
        if not replace:
            raise SystemExit("Repository signing secrets already exist. Use --replace only to restore the same signing identity.")
        encoded = subprocess.check_output(["gh", "api", f"repos/{repo}/contents/signing/release-certificate.pem", "--jq", ".content"], text=True)
        remote_pem = base64.b64decode(encoded).decode("ascii")
        remote_fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(remote_pem)).hexdigest()
        if remote_fingerprint != fingerprint():
            raise SystemExit("Refusing to replace secrets with a different release signing identity.")
    payloads = (
        base64.b64encode(store.read_bytes()),
        values["storePassword"].encode(), values["keyAlias"].encode(), values["keyPassword"].encode(),
    )
    for name, value in zip(SECRET_NAMES, payloads):
        subprocess.run(["gh", "secret", "set", name, "--repo", repo], input=value, check=True)
        print("Configured", name)
    print("Signing secrets configured for", canonical)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("create", help="Generate a new key once; never overwrites existing files")
    commands.add_parser("fingerprint", help="Print only the public certificate SHA-256")
    provision = commands.add_parser("upload", help="Upload local signing material to GitHub Actions Secrets using gh")
    provision.add_argument("--repo", required=True)
    provision.add_argument("--replace", action="store_true", help="Explicitly restore existing repository signing secrets")
    args = parser.parse_args()
    try:
        if args.command == "create":
            create()
        elif args.command == "fingerprint":
            print(fingerprint())
        else:
            upload(args.repo, args.replace)
    except (OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Signing setup failed ({type(error).__name__}); private values were not printed.") from None


if __name__ == "__main__":
    main()
