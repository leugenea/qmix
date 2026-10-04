#!/usr/bin/env python3
"""Offline compatible release payload contract (qmix#322); never publishes/signs."""

import argparse
import gzip
import hashlib
import json
import os
import pathlib
import platform
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tarfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
TARGETS = tuple(
    (component, system, arch)
    for component in ("qmix", "token-vk", "token-ym")
    for system, arch in (("linux", "amd64"), ("linux", "arm64"),
                         ("darwin", "amd64"), ("darwin", "arm64"), ("windows", "amd64"))
    if component != "qmix" or system == "linux"
)
AUXILIARIES = (("qmix-android-unsigned", "android", "any"),
               ("qmix-go-sbom", "any", "any"), ("qmix-android-unsigned-sbom", "any", "any"),
               ("qmix-licenses", "any", "any"))
SUFFIXES = {"qmix-android-unsigned": ".apk", "qmix-go-sbom": ".cdx.json",
            "qmix-android-unsigned-sbom": ".cdx.json", "qmix-licenses": ".tar.gz"}
METHODS = {"go-version-m+header", "aapt+unsigned-zip", "sbom-hash-roots", "license-bundle"}
LICENSES = ("LICENSE", "THIRD_PARTY_NOTICES.md", "third_party/licenses/Apache-2.0.txt",
            "third_party/licenses/goym-vantuz-MIT.txt",
            "third_party/licenses/go-querystring-BSD-3-Clause.txt",
            "third_party/licenses/go-BSD-3-Clause.txt")
IDENTITY_KEYS = {"version", "commit", "dirty", "androidVersionCode"}
ENTRY_KEYS = {"component", "version", "commit", "os", "arch", "sourceArtifact",
              "filename", "size", "sha256", "verification"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(command, cwd=ROOT, env=None, timeout=600):
    return subprocess.check_output(command, cwd=cwd, env=env, text=True, timeout=timeout).strip()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def invalid_constant(value):
    raise ValueError("non-finite JSON number")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object,
                      parse_constant=invalid_constant)


def canonical(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n"


def write_json(path, value):
    path.write_text(canonical(value), encoding="utf-8")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_identity(info):
    require(type(info) is dict and set(info) == IDENTITY_KEYS, "invalid identity fields")
    require(type(info["version"]) is str and re.fullmatch(r"[0-9][0-9A-Za-z.-]*", info["version"]),
            "unsafe version")
    require(not info["version"].startswith("0.0.0-dev."), "release identity required")
    require(type(info["commit"]) is str and re.fullmatch(r"[0-9a-f]{40}", info["commit"]),
            "invalid commit")
    require(info["dirty"] is False, "clean release identity required")
    require(type(info["androidVersionCode"]) is int and 0 < info["androidVersionCode"] < 2100000000,
            "invalid Android code")
    return info


def identity():
    # SemVer and Android mapping belong exclusively to the existing Go calculator.
    commit = run(["git", "rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"])
    info = json.loads(run(["go", "run", "./internal/buildinfo/cmd/version", "-format=json"]))
    require(info.get("commit") == commit, "calculator commit differs from resolved HEAD")
    return validate_identity(info)


def filename(component, version, system, arch):
    suffix = SUFFIXES.get(component, ".exe" if system == "windows" else "")
    return f"{component}-{version}-{system}-{arch}{suffix}"


def entry(path, info, target, method, source=None):
    component, system, arch = target
    return {"component": component, "version": info["version"], "commit": info["commit"],
            "os": system, "arch": arch, "sourceArtifact": source.name if source is not None else path.name, "filename": path.name,
            "size": path.stat().st_size, "sha256": sha256(path), "verification": method}


def validate_source(item, name):
    source = item["sourceArtifact"]
    require(type(source) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", source),
            "invalid source artifact")
    if item["component"] == "qmix-android-unsigned":
        require(source == "app-release-unsigned.apk", "wrong Gradle source artifact")
    elif item["component"] in {"qmix-go-sbom", "qmix-android-unsigned-sbom"}:
        require(source.endswith(".json"), "wrong SBOM source artifact")
    else:
        require(source == name, "wrong source artifact")


def validate_entry(item, info):
    require(type(item) is dict and set(item) == ENTRY_KEYS, "invalid artifact fields")
    target = (item["component"], item["os"], item["arch"])
    require(all(type(value) is str for value in target), "invalid target types")
    require(target in TARGETS + AUXILIARIES, "unexpected target")
    require((item["version"], item["commit"]) == (info["version"], info["commit"]),
            "conflicting artifact identity")
    name = filename(*target[:1], info["version"], *target[1:])
    require(item["filename"] == name, "invalid artifact filename")
    validate_source(item, name)
    require(type(item["size"]) is int and item["size"] > 0, "empty or invalid artifact size")
    require(type(item["sha256"]) is str and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]),
            "invalid artifact hash")
    require(type(item["verification"]) is str and item["verification"] in METHODS,
            "invalid metadata verification method")
    expected = "go-version-m+header" if target in TARGETS else {
        "qmix-android-unsigned": "aapt+unsigned-zip", "qmix-licenses": "license-bundle",
        "qmix-go-sbom": "sbom-hash-roots", "qmix-android-unsigned-sbom": "sbom-hash-roots",
    }[target[0]]
    require(item["verification"] == expected, "wrong metadata verification method")
    return target


def validate_inventory(value, directory, expected):
    require(type(value) is dict and set(value) == {"schemaVersion", "identity", "artifacts"},
            "invalid inventory fields")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1,
            "unsupported inventory schema")
    info = validate_identity(value["identity"])
    require(info == expected, "inventory differs from calculator")
    require(type(value["artifacts"]) is list, "invalid artifact list")
    # Validate EVERY record before selecting Go targets (including Android/auxiliaries).
    targets = [validate_entry(item, info) for item in value["artifacts"]]
    require(len(targets) == len(set(targets)), "duplicate target")
    require(set(targets) in (set(TARGETS), set(TARGETS + AUXILIARIES)), "missing targets")
    for item in value["artifacts"]:
        path = directory / item["filename"]
        require(path.is_file() and not path.is_symlink(), "missing or symlink artifact")
        require(path.stat().st_size == item["size"] and sha256(path) == item["sha256"],
                "artifact bytes differ from inventory")
    return sorted(value["artifacts"], key=lambda item: item["filename"])


def inventory(info, artifacts):
    return {"schemaVersion": 1, "identity": info,
            "artifacts": sorted(artifacts, key=lambda item: item["filename"])}


def prepare_output(path):
    path = path.resolve()
    # No source-tree staging or accidental credential-directory copying.
    require(not path.is_relative_to(ROOT) or path.is_relative_to(ROOT / "bin"),
            "output must be outside source or under ignored bin/")
    path.mkdir(parents=True, exist_ok=False)
    return path


def inspect_header(path, system, arch):
    with path.open("rb") as stream:
        header = stream.read(4096)
    require(len(header) >= 64, "truncated binary header")
    if system == "linux":
        require(header[:6] == b"\x7fELF\x02\x01", "not little-endian ELF64")
        machine = struct.unpack_from("<H", header, 18)[0]
        require(machine == {"amd64": 62, "arm64": 183}[arch], "wrong ELF architecture")
        return
    if system == "darwin":
        require(header[:4] == b"\xcf\xfa\xed\xfe", "not Mach-O64")
        machine = struct.unpack_from("<I", header, 4)[0]
        require(machine == {"amd64": 0x1000007, "arm64": 0x100000c}[arch], "wrong Mach-O architecture")
        return
    require(header[:2] == b"MZ", "not PE")
    offset = struct.unpack_from("<I", header, 60)[0]
    with path.open("rb") as stream:
        stream.seek(offset)
        pe = stream.read(6)
    require(pe == b"PE\0\0\x64\x86", "wrong PE architecture")


def inspect_go(path, info, target, ldflags):
    component, system, arch = target
    inspect_header(path, system, arch)
    text = run(["go", "version", "-m", str(path)])
    settings = {}
    for line in text.splitlines():
        fields = line.strip().split("\t", 1)
        if len(fields) == 2 and fields[0] == "build":
            key, value = fields[1].split("=", 1)
            settings[key] = value.strip('"')
    wanted = {"GOOS": system, "GOARCH": arch, "CGO_ENABLED": "0", "vcs.revision": info["commit"],
              "vcs.modified": "false", "-ldflags": ldflags}
    require(all(settings.get(key) == value for key, value in wanted.items()), "wrong Go build metadata")
    require(f"path\tgithub.com/leugenea/qmix/cmd/{component}" in text, "wrong Go command")


def build_go(output):
    info = identity()
    ldflags = run(["go", "run", "./internal/buildinfo/cmd/version", "-format=ldflags"]) + " -buildid="
    output = prepare_output(output)
    artifacts = []
    for target in TARGETS:
        component, system, arch = target
        path = output / filename(component, info["version"], system, arch)
        env = os.environ | {"GOOS": system, "GOARCH": arch, "CGO_ENABLED": "0"}
        run(["go", "build", "-mod=readonly", "-buildvcs=true", "-ldflags", ldflags,
             "-o", str(path), f"./cmd/{component}"], env=env)
        inspect_go(path, info, target, ldflags)
        artifacts.append(entry(path, info, target, "go-version-m+header"))
    value = inventory(info, artifacts)
    validate_inventory(value, output, info)
    write_json(output / "go-inventory.json", value)


def native_target():
    system = {"Linux": "linux", "Darwin": "darwin", "Windows": "windows"}.get(platform.system())
    arch = {"x86_64": "amd64", "AMD64": "amd64", "arm64": "arm64", "aarch64": "arm64"}.get(platform.machine())
    require(system is not None and arch is not None, "unsupported native runner")
    return system, arch


def verify_native(path, receipt, expected_target):
    value = read_json(path)
    info = identity()
    items = validate_inventory(value, path.parent, info)
    system, arch = native_target()
    require((system, arch) == expected_target, "native runner target differs from matrix")
    selected = [item for item in items if (item["os"], item["arch"]) == (system, arch)]
    require(bool(selected), "no native targets in payload")
    results = []
    for item in selected:
        binary = path.parent / item["filename"]
        binary.chmod(0o755)
        result = validate_identity(json.loads(run([str(binary), "--version"], timeout=10),
                                              object_pairs_hook=unique_object, parse_constant=invalid_constant))
        require(result == info, "native --version differs from calculator")
        require(sha256(binary) == item["sha256"], "native execution changed artifact bytes")
        results.append({"filename": binary.name, "sha256": item["sha256"],
                        "identity": result, "verification": "native --version"})
    write_json(receipt, {"os": system, "arch": arch, "artifacts": results})


def inspect_apk(apk, info, aapt):
    require(apk.name == "app-release-unsigned.apk", "only Gradle unsigned release APK accepted")
    with zipfile.ZipFile(apk) as archive:
        require("AndroidManifest.xml" in archive.namelist(), "missing APK manifest")
        require(not any(name.upper().startswith("META-INF/") and name.upper().endswith(
            (".RSA", ".DSA", ".EC", ".SF")) for name in archive.namelist()), "signed APK is not an unsigned intermediate")
    require(b"APK Sig Block 42" not in apk.read_bytes(), "signed APK block rejected")
    # apksigner is not used here: no signing capability is part of this helper.
    text = run([str(aapt), "dump", "badging", str(apk)])
    lines = [line for line in text.splitlines() if line.startswith("package:")]
    require(len(lines) == 1, "ambiguous APK metadata")
    fields = dict(token.split("=", 1) for token in shlex.split(lines[0])[1:] if "=" in token)
    require(fields.get("name") == "com.qmix.tv", "wrong APK package")
    require(fields.get("versionName") == info["version"] and fields.get("versionCode") == str(info["androidVersionCode"]),
            "wrong APK version")
    require("application-debuggable" not in text, "debug APK rejected")


def license_bundle(path):
    with path.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name in sorted(LICENSES):
                source = ROOT / name
                require(source.is_file() and not source.is_symlink(), "missing license text")
                item = tarfile.TarInfo(name)
                item.size = source.stat().st_size
                item.mode = 0o644
                with source.open("rb") as stream:
                    archive.addfile(item, stream)


def verify_bom(path, info, roots):
    import generate_sbom
    bom = read_json(path)
    generate_sbom.validate_bom(bom)
    require(path.read_text(encoding="utf-8") == canonical(bom), "noncanonical SBOM")
    components = [bom["metadata"]["component"]] + bom["components"]
    actual = {item["bom-ref"]: item for item in components}
    applications = {ref for ref, item in actual.items() if item["type"] == "application"}
    expected = set(roots)
    if len(roots) > 1:
        expected.add(f'pkg:generic/qmix-go-distribution@{info["version"]}')
    require(applications == expected, "SBOM target roots differ")
    for ref, digest in roots.items():
        item = actual.get(ref, {})
        require(item.get("version") == info["version"] and item.get("hashes") == [{"alg": "SHA-256", "content": digest}],
                "SBOM root hash/version differs")


def package(go_inventory, apk, go_bom, android_bom, aapt, output):
    value = read_json(go_inventory)
    info = identity()
    artifacts = validate_inventory(value, go_inventory.parent, info)
    require(len(artifacts) == len(TARGETS), "expected Go-only build inventory")
    ldflags = run(["go", "run", "./internal/buildinfo/cmd/version", "-format=ldflags"]) + " -buildid="
    for item in artifacts:
        inspect_go(go_inventory.parent / item["filename"], info,
                   (item["component"], item["os"], item["arch"]), ldflags)
    inspect_apk(apk, info, aapt)
    roots = {f'pkg:generic/{item["component"]}@{info["version"]}?arch={item["arch"]}&os={item["os"]}&type=binary':
             item["sha256"] for item in artifacts}
    verify_bom(go_bom, info, roots)
    verify_bom(android_bom, info, {f'pkg:generic/qmix-android@{info["version"]}?type=apk': sha256(apk)})
    output = prepare_output(output)
    for item in artifacts:
        shutil.copyfile(go_inventory.parent / item["sourceArtifact"], output / item["filename"])
    for source, target, method in ((apk, AUXILIARIES[0], "aapt+unsigned-zip"),
                                  (go_bom, AUXILIARIES[1], "sbom-hash-roots"),
                                  (android_bom, AUXILIARIES[2], "sbom-hash-roots")):
        destination = output / filename(target[0], info["version"], *target[1:])
        shutil.copyfile(source, destination)
        artifacts.append(entry(destination, info, target, method, source))
    destination = output / filename("qmix-licenses", info["version"], "any", "any")
    license_bundle(destination)
    artifacts.append(entry(destination, info, AUXILIARIES[3], "license-bundle"))
    result = inventory(info, artifacts)
    validate_inventory(result, output, info)
    # Validate the copied bytes too, not just a source file that could have changed.
    verify_bom(output / filename("qmix-go-sbom", info["version"], "any", "any"), info, roots)
    copied_apk = output / filename("qmix-android-unsigned", info["version"], "android", "any")
    verify_bom(output / filename("qmix-android-unsigned-sbom", info["version"], "any", "any"),
               info, {f'pkg:generic/qmix-android@{info["version"]}?type=apk': sha256(copied_apk)})
    write_json(output / f'qmix-inventory-{info["version"]}-any-any.json', result)
    # Owner-approved sole fixed-name exception. Inventory is covered, not self-hashed.
    checksums = [f"{sha256(path)}  {path.name}\n" for path in sorted(output.iterdir())]
    (output / "SHA256SUMS").write_text("".join(checksums), encoding="ascii")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build-go")
    build.add_argument("--output", type=pathlib.Path, required=True)
    native = sub.add_parser("verify-native")
    native.add_argument("--inventory", type=pathlib.Path, required=True)
    native.add_argument("--receipt", type=pathlib.Path, required=True)
    native.add_argument("--os", choices=("linux", "darwin", "windows"), required=True)
    native.add_argument("--arch", choices=("amd64", "arm64"), required=True)
    pack = sub.add_parser("package")
    for name in ("go-inventory", "apk", "go-bom", "android-bom", "aapt", "output"):
        pack.add_argument("--" + name, type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "build-go":
        build_go(args.output)
    elif args.command == "verify-native":
        verify_native(args.inventory, args.receipt, (args.os, args.arch))
    else:
        package(args.go_inventory, args.apk, args.go_bom, args.android_bom, args.aapt, args.output)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
