#!/usr/bin/env python3
"""Unprivileged disposable SDK fixture orchestration, never a production key fallback."""

import argparse
import os
import pathlib
import secrets
import shutil
import sys
import tempfile

import release_payload as payload
import release_signing as signing


def fixture_sign(apk, output, info, sdk):
    parent = pathlib.Path(os.environ["RUNNER_TEMP"]).resolve()
    payload.require(not parent.is_relative_to(payload.ROOT), "fixture key inside source")
    with tempfile.TemporaryDirectory(prefix="qmix-test-key-", dir=parent) as directory:
        private = pathlib.Path(directory)
        private.chmod(0o700)
        key = private / "test-only.keystore"
        password = secrets.token_urlsafe(32)
        env = os.environ | {"QMIX_RELEASE_STORE_PASSWORD": password, "QMIX_RELEASE_KEY_PASSWORD": password}
        signing.tool(["keytool", "-genkeypair", "-keystore", str(key), "-alias", "fixture-only",
                      "-storepass:env", "QMIX_RELEASE_STORE_PASSWORD", "-keypass:env", "QMIX_RELEASE_KEY_PASSWORD",
                      "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=QMix CI TEST ONLY"], env)
        key.chmod(0o600)
        cert = signing.tool(["keytool", "-exportcert", "-keystore", str(key), "-alias", "fixture-only",
                             "-storepass:env", "QMIX_RELEASE_STORE_PASSWORD"], env)
        pin = __import__("hashlib").sha256(cert).hexdigest()
        reject_signing_inputs(apk, output, info, sdk, pin, key, env)
        signing.sign(apk, output, info, sdk, pin, key, "fixture-only", env)
        # Actual apksigner rejection, even if the unsigned APK is not aligned.
        rejects(lambda: signing.tool([str(sdk / "apksigner"), "verify",
                                      "--min-sdk-version", "23", str(apk)]))
        rejects(lambda: signing.verify_apk(apk, info, sdk, pin))
        rejects(lambda: signing.verify_apk(output, info, sdk, "0" * 64))
        return pin


def rejects(operation):
    try:
        operation()
    except ValueError:
        return
    raise ValueError("negative SDK fixture unexpectedly accepted")


def reject_signing_inputs(apk, output, info, sdk, pin, key, env):
    cases = (("0" * 64, key, env), (pin, key, env | {"QMIX_RELEASE_STORE_PASSWORD": "wrong-fixture-password"}),
             (pin, key, env | {"QMIX_RELEASE_KEY_PASSWORD": ""}), (pin, key.parent / "missing.keystore", env))
    for wrong_pin, wrong_key, wrong_env in cases:
        rejects(lambda: signing.sign(apk, output, info, sdk, wrong_pin, wrong_key, "fixture-only", wrong_env))
        payload.require(not output.exists(), "failed signing emitted a final APK")
    corrupt = key.parent / "corrupt.keystore"
    corrupt.write_bytes(b"invalid test-only keystore")
    rejects(lambda: signing.sign(apk, output, info, sdk, pin, corrupt, "fixture-only", env))


def finalize_fixture(unsigned, output, receipt):
    info, sdk = payload.identity(), signing.sdk_tools()
    with tempfile.TemporaryDirectory(prefix="qmix-final-", dir=os.environ["RUNNER_TEMP"]) as temporary:
        directory = pathlib.Path(temporary)
        apk = directory / "app-release-unsigned.apk"
        source = unsigned / payload.filename("qmix-android-unsigned", info["version"], "android", "any")
        shutil.copyfile(source, apk)
        signed = directory / "app-release-signed.apk"
        pin = fixture_sign(apk, signed, info, sdk)
        bom = directory / "signed-android.cdx.json"
        signing.tool([sys.executable, str(payload.ROOT / ".github/scripts/generate_sbom.py"),
                      "android", "--apk", str(signed), "--output", str(bom)])
        signing.finalize(unsigned, signed, bom, output, info, sdk, pin)
        payload.write_json(receipt, {"identity": info, "certificateSha256": pin,
                           "inventorySha256": payload.sha256(signing.inventory_path(output, info)),
                           "kind": "disposable CI-only signing identity; NOT production"})


def verify_fixture(directory, receipt):
    info = payload.identity()
    value = payload.read_json(receipt)
    payload.require(value["identity"] == info and value["kind"] == "disposable CI-only signing identity; NOT production",
                    "fixture receipt differs")
    payload.require(value["inventorySha256"] == payload.sha256(signing.inventory_path(directory, info)),
                    "downloaded inventory differs")
    signing.verify_final(directory, info, signing.sdk_tools(), value["certificateSha256"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    final = sub.add_parser("finalize")
    final.add_argument("--unsigned", type=pathlib.Path, required=True)
    final.add_argument("--output", type=pathlib.Path, required=True)
    final.add_argument("--receipt", type=pathlib.Path, required=True)
    verify = sub.add_parser("verify")
    verify.add_argument("--directory", type=pathlib.Path, required=True)
    verify.add_argument("--receipt", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == "finalize":
        finalize_fixture(args.unsigned, args.output, args.receipt)
    else:
        verify_fixture(args.directory, args.receipt)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("disposable release fixture failed (details suppressed)", file=sys.stderr)
        sys.exit(1)
