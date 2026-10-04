#!/usr/bin/env python3
"""SDK signing and explicit signed-final payload verification (#334)."""

import argparse
import base64
import hashlib
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

import release_payload as payload


def tool(command, env=None, timeout=600):
    # Tool stderr can contain keystore/password details; never relay it or argv.
    try:
        result = subprocess.run(command, cwd=payload.ROOT, env=env, timeout=timeout,
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except (OSError, subprocess.SubprocessError):
        raise ValueError("release tool failed (details suppressed)") from None
    return result.stdout


def sdk_tools():
    root = pathlib.Path(os.environ.get("ANDROID_HOME", "")) / "build-tools" / "35.0.0"
    payload.require((root / "source.properties").is_file(), "pinned SDK missing")
    revision = re.findall(r"^Pkg.Revision\s*=\s*(.+)$", (root / "source.properties").read_text(), re.M)
    payload.require(revision == ["35.0.0"], "wrong SDK revision")
    for name in ("aapt", "zipalign", "apksigner"):
        payload.require((root / name).is_file(), "SDK tool missing")
    return root


def apk_metadata(apk, info, sdk):
    text = tool([str(sdk / "aapt"), "dump", "badging", str(apk)]).decode()
    lines = [line for line in text.splitlines() if line.startswith("package:")]
    payload.require(len(lines) == 1, "ambiguous APK package")
    values = dict(token.split("=", 1) for token in shlex.split(lines[0])[1:] if "=" in token)
    payload.require(values.get("name") == "com.qmix.tv" and values.get("versionName") == info["version"]
                    and values.get("versionCode") == str(info["androidVersionCode"]), "wrong signed APK identity")
    payload.require("application-debuggable" not in text, "debug final APK rejected")
    payload.require("sdkVersion:'23'" in text, "minSdk23 must be preserved")


def certificate(pin):
    payload.require(type(pin) is str and re.fullmatch(r"[0-9a-f]{64}", pin), "invalid certificate pin")
    return pin


def verify_apk(apk, info, sdk, pin):
    certificate(pin)
    payload.require(apk.is_file() and not apk.is_symlink(), "missing signed APK")
    tool([str(sdk / "zipalign"), "-c", "-P", "16", "4", str(apk)])
    text = tool([str(sdk / "apksigner"), "verify", "--verbose", "--print-certs",
                 "--min-sdk-version", "23", str(apk)]).decode()
    pins = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", text, re.M)
    payload.require([item.lower() for item in pins] == [pin], "signed APK certificate differs")
    apk_metadata(apk, info, sdk)


def sign(apk, output, info, sdk, pin, key, alias, env):
    # Legitimate unsigned input remains subject to the existing #322 rejection.
    payload.inspect_apk(apk, info, sdk / "aapt")
    payload.require(alias and env.get("QMIX_RELEASE_STORE_PASSWORD") and env.get("QMIX_RELEASE_KEY_PASSWORD"),
                    "missing signing configuration")
    raw_certificate = tool(["keytool", "-exportcert", "-keystore", str(key), "-alias", alias,
                            "-storepass:env", "QMIX_RELEASE_STORE_PASSWORD"], env)
    payload.require(hashlib.sha256(raw_certificate).hexdigest() == certificate(pin), "keystore certificate differs")
    payload.require(output.name == "app-release-signed.apk" and not output.exists(), "invalid signed output")
    aligned = key.parent / "aligned.apk"
    tool([str(sdk / "zipalign"), "-P", "16", "4", str(apk), str(aligned)], env)
    tool([str(sdk / "apksigner"), "sign", "--ks", str(key), "--ks-key-alias", alias,
          "--ks-pass", "env:QMIX_RELEASE_STORE_PASSWORD", "--key-pass", "env:QMIX_RELEASE_KEY_PASSWORD",
          "--v4-signing-enabled", "false", "--out", str(output), str(aligned)], env)
    verify_apk(output, info, sdk, pin)


def production_sign(apk, output, info, sdk, pin):
    # No production key generator or test fallback. Private directory outside COPY . context.
    parent = pathlib.Path(os.environ["RUNNER_TEMP"]).resolve()
    payload.require(not parent.is_relative_to(payload.ROOT), "key directory inside source")
    with tempfile.TemporaryDirectory(prefix="qmix-sign-", dir=parent) as directory:
        private = pathlib.Path(directory)
        private.chmod(0o700)
        key = private / "release.keystore"
        encoded = os.environ.get("QMIX_RELEASE_KEYSTORE_BASE64", "")
        payload.require(bool(encoded), "missing signing key")
        try:
            key.write_bytes(base64.b64decode(encoded, validate=True))
        except ValueError:
            raise ValueError("invalid signing key encoding") from None
        key.chmod(0o600)
        sign(apk, output, info, sdk, pin, key, os.environ.get("QMIX_RELEASE_KEY_ALIAS", ""), os.environ.copy())


def inventory_path(directory, info):
    return directory / f'qmix-inventory-{info["version"]}-any-any.json'


def exact_files(directory, items, info):
    names = {item["filename"] for item in items} | {inventory_path(directory, info).name, "SHA256SUMS"}
    actual = list(directory.iterdir())
    payload.require({path.name for path in actual} == names and
                    all(path.is_file() and not path.is_symlink() for path in actual), "unexpected final files")
    expected = "".join(f"{payload.sha256(directory / name)}  {name}\n" for name in sorted(names - {"SHA256SUMS"}))
    payload.require((directory / "SHA256SUMS").read_text(encoding="ascii") == expected,
                    "checksum allowlist or bytes differ")
    return sorted(names)


def go_roots(items, info):
    return {f'pkg:generic/{item["component"]}@{info["version"]}?arch={item["arch"]}&os={item["os"]}&type=binary':
            item["sha256"] for item in items if (item["component"], item["os"], item["arch"]) in payload.TARGETS}


def verify_final(directory, info, sdk, pin):
    value = payload.read_json(inventory_path(directory, info))
    items = payload.validate_inventory(value, directory, info, signed_final=True)
    payload.require(value["certificateSha256"] == certificate(pin), "inventory certificate differs")
    names = exact_files(directory, items, info)
    apk = directory / payload.filename("qmix-android", info["version"], "android", "any")
    verify_apk(apk, info, sdk, pin)
    payload.verify_bom(directory / payload.filename("qmix-android-sbom", info["version"], "any", "any"), info,
                       {f'pkg:generic/qmix-android@{info["version"]}?type=apk': payload.sha256(apk)})
    payload.verify_bom(directory / payload.filename("qmix-go-sbom", info["version"], "any", "any"), info,
                       go_roots(items, info))
    verify_go_and_licenses(directory, items, info)
    return names


def verify_go_and_licenses(directory, items, info):
    ldflags = payload.run(["go", "run", "./internal/buildinfo/cmd/version", "-format=ldflags"]) + " -buildid="
    for item in items:
        target = (item["component"], item["os"], item["arch"])
        if target in payload.TARGETS:
            payload.inspect_go(directory / item["filename"], info, target, ldflags)
    with tempfile.TemporaryDirectory(dir=os.environ["RUNNER_TEMP"]) as temporary:
        expected = pathlib.Path(temporary) / "licenses.tar.gz"
        payload.license_bundle(expected)
        actual = directory / payload.filename("qmix-licenses", info["version"], "any", "any")
        payload.require(payload.sha256(actual) == payload.sha256(expected), "license archive differs")


def finalize(unsigned, signed, android_bom, output, info, sdk, pin):
    value = payload.read_json(inventory_path(unsigned, info))
    items = payload.validate_inventory(value, unsigned, info)
    payload.require(len(items) == len(payload.TARGETS + payload.AUXILIARIES), "complete unsigned input required")
    exact_files(unsigned, items, info)
    verify_apk(signed, info, sdk, pin)
    payload.require(signed.name == "app-release-signed.apk", "wrong final provenance")
    payload.verify_bom(android_bom, info, {f'pkg:generic/qmix-android@{info["version"]}?type=apk': payload.sha256(signed)})
    output = payload.prepare_output(output)
    kept = [item for item in items if item["component"] not in {"qmix-android-unsigned", "qmix-android-unsigned-sbom"}]
    for item in kept:
        shutil.copyfile(unsigned / item["filename"], output / item["filename"])
    for source, target, method in ((signed, payload.FINAL_AUXILIARIES[0], "zipalign+apksigner+aapt"),
                                   (android_bom, payload.FINAL_AUXILIARIES[2], "sbom-hash-roots")):
        destination = output / payload.filename(target[0], info["version"], *target[1:])
        shutil.copyfile(source, destination)
        kept.append(payload.entry(destination, info, target, method, source))
    result = payload.inventory(info, kept)
    result.update(schemaVersion=2, certificateSha256=certificate(pin))
    payload.write_json(inventory_path(output, info), result)
    sums = "".join(f"{payload.sha256(path)}  {path.name}\n" for path in sorted(output.iterdir()))
    (output / "SHA256SUMS").write_text(sums, encoding="ascii")
    verify_final(output, info, sdk, pin)


def complete(unsigned, output, pin):
    info, sdk = payload.identity(), sdk_tools()
    with tempfile.TemporaryDirectory(prefix="qmix-final-", dir=os.environ["RUNNER_TEMP"]) as temporary:
        directory = pathlib.Path(temporary)
        apk = directory / "app-release-unsigned.apk"
        shutil.copyfile(unsigned / payload.filename("qmix-android-unsigned", info["version"], "android", "any"), apk)
        signed = directory / "app-release-signed.apk"
        production_sign(apk, signed, info, sdk, pin)
        clean_env = {key: value for key, value in os.environ.items() if not key.startswith("QMIX_RELEASE_")}
        bom = directory / "signed-android.cdx.json"
        tool([sys.executable, str(payload.ROOT / ".github/scripts/generate_sbom.py"),
              "android", "--apk", str(signed), "--output", str(bom)], env=clean_env)
        finalize(unsigned, signed, bom, output, info, sdk, pin)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    signer = sub.add_parser("sign")
    for name in ("apk", "output"):
        signer.add_argument("--" + name, required=True, type=pathlib.Path)
    final = sub.add_parser("finalize")
    for name in ("unsigned", "signed", "android-bom", "output"):
        final.add_argument("--" + name, required=True, type=pathlib.Path)
    verify = sub.add_parser("verify")
    verify.add_argument("--directory", required=True, type=pathlib.Path)
    complete_parser = sub.add_parser("complete")
    for name in ("unsigned", "output"):
        complete_parser.add_argument("--" + name, required=True, type=pathlib.Path)
    for command in (signer, final, verify, complete_parser):
        command.add_argument("--certificate", required=True)
    args = parser.parse_args()
    info, sdk = payload.identity(), sdk_tools()
    if args.command == "sign":
        production_sign(args.apk, args.output, info, sdk, args.certificate)
    elif args.command == "finalize":
        finalize(args.unsigned, args.signed, args.android_bom, args.output, info, sdk, args.certificate)
    elif args.command == "complete":
        complete(args.unsigned, args.output, args.certificate)
    else:
        verify_final(args.directory, info, sdk, args.certificate)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("release signing/final verification failed (details suppressed)", file=sys.stderr)
        sys.exit(1)
