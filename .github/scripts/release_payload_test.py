#!/usr/bin/env python3
"""Focused offline payload regressions for qmix#322 (no Go/Gradle builds)."""

import copy
import json
import pathlib
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import generate_sbom
import release_payload as payload

INFO = {"version": "0.1.0-rc.1", "commit": "0123456789abcdef0123456789abcdef01234567",
        "dirty": False, "androidVersionCode": 1000001}
FLAGS = ("-X=github.com/leugenea/qmix/internal/buildinfo.Version=0.1.0-rc.1 "
         "-X=github.com/leugenea/qmix/internal/buildinfo.Commit=" + INFO["commit"] + " "
         "-X=github.com/leugenea/qmix/internal/buildinfo.Dirty=false "
         "-X=github.com/leugenea/qmix/internal/buildinfo.AndroidVersionCode=1000001")


def header(system, arch):
    data = bytearray(128)
    if system == "linux":
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, {"amd64": 62, "arm64": 183}[arch])
    elif system == "darwin":
        data[:4] = b"\xcf\xfa\xed\xfe"
        struct.pack_into("<I", data, 4, {"amd64": 0x1000007, "arm64": 0x100000c}[arch])
    else:
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 60, 64)
        data[64:70] = b"PE\0\0\x64\x86"
    return bytes(data)


def go_metadata(target, flags=FLAGS + " -buildid="):
    name, system, arch = target
    settings = {"GOOS": system, "GOARCH": arch, "CGO_ENABLED": "0",
                "vcs.revision": INFO["commit"], "vcs.modified": "false", "-ldflags": flags}
    return "fixture: go1.25.0\n\tpath\tgithub.com/leugenea/qmix/cmd/" + name + "\n" + "\n".join(
        f'\tbuild\t{key}={json.dumps(value) if " " in value else value}' for key, value in settings.items())


class ReleasePayloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.go = self.root / "go"
        self.go.mkdir()
        entries = []
        for target in payload.TARGETS:
            path = self.go / payload.filename(target[0], INFO["version"], *target[1:])
            path.write_bytes(header(*target[1:]))
            entries.append(payload.entry(path, INFO, target, "go-version-m+header"))
        self.value = payload.inventory(INFO, entries)
        self.manifest = self.go / "go-inventory.json"
        payload.write_json(self.manifest, self.value)

    def test_exact_matrix_names_and_suffixes(self):
        self.assertEqual(len(payload.TARGETS), 12)
        self.assertEqual(sum(target[0] == "qmix" for target in payload.TARGETS), 2)
        for component in ("token-vk", "token-ym"):
            self.assertEqual(sum(target[0] == component for target in payload.TARGETS), 5)
        records = payload.validate_inventory(self.value, self.go, INFO)
        self.assertEqual(len({item["filename"] for item in records}), 12)
        for item in records:
            self.assertEqual(item["filename"].endswith(".exe"), item["os"] == "windows")

    def test_inventory_rejects_empty_missing_duplicate_unexpected(self):
        cases = ([], self.value["artifacts"][:-1], self.value["artifacts"] * 2,
                 self.value["artifacts"] + [{"component": "unexpected"}])
        for records in cases:
            with self.subTest(count=len(records)), self.assertRaises(ValueError):
                payload.validate_inventory(self.value | {"artifacts": records}, self.go, INFO)

    def test_inventory_rejects_wrong_fields_identity_paths_and_types(self):
        cases = {"version": "1.0.0", "commit": "f" * 40, "filename": "../secret",
                 "sourceArtifact": "/absolute", "sha256": "bad", "size": 0,
                 "verification": "native --version", "os": "other", "arch": ["amd64"]}
        for field, value in cases.items():
            candidate = copy.deepcopy(self.value)
            candidate["artifacts"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                payload.validate_inventory(candidate, self.go, INFO)
        for size in (True, 128.0, float("nan"), float("inf")):
            candidate = copy.deepcopy(self.value)
            candidate["artifacts"][0]["size"] = size
            with self.subTest(size=size), self.assertRaises(ValueError):
                payload.validate_inventory(candidate, self.go, INFO)

    def test_json_rejects_duplicate_keys_and_nonfinite_before_filtering(self):
        for text in ('{"identity":{},"identity":{}}', '{"ignored":NaN}', '{"ignored":Infinity}', '{"ignored":-Infinity}'):
            self.manifest.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                payload.read_json(self.manifest)
        candidate = copy.deepcopy(self.value)
        candidate["artifacts"].append({"component": "qmix-android-unsigned", "size": "bad"})
        payload.write_json(self.manifest, candidate)
        with self.assertRaises(ValueError):
            generate_sbom._go_targets(self.manifest, INFO)

    def test_inventory_rejects_changed_bytes_and_symlinks(self):
        path = self.go / self.value["artifacts"][0]["filename"]
        path.write_bytes(b"altered")
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            payload.validate_inventory(self.value, self.go, INFO)
        path.unlink()
        path.symlink_to(self.manifest)
        with self.assertRaisesRegex(ValueError, "symlink"):
            payload.validate_inventory(self.value, self.go, INFO)

    def test_identity_uses_calculator_and_resolved_commit(self):
        with mock.patch.object(payload, "run", side_effect=[INFO["commit"], json.dumps(INFO)]) as run:
            self.assertEqual(payload.identity(), INFO)
        self.assertEqual(run.call_args_list[0].args[0],
                         ["git", "rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"])
        with mock.patch.object(payload, "run", side_effect=["f" * 40, json.dumps(INFO)]):
            with self.assertRaisesRegex(ValueError, "resolved HEAD"):
                payload.identity()
        for change in ({"dirty": True}, {"androidVersionCode": True}, {"version": "0.0.0-dev.0123456789ab"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                payload.validate_identity(INFO | change)

    def test_static_headers_and_go_metadata_reject_wrong_target_identity(self):
        for target in payload.TARGETS:
            path = self.go / payload.filename(target[0], INFO["version"], *target[1:])
            with mock.patch.object(payload, "run", return_value=go_metadata(target)):
                payload.inspect_go(path, INFO, target, FLAGS + " -buildid=")
            for original, wrong in (("CGO_ENABLED=0", "CGO_ENABLED=1"),
                                    (INFO["version"], "1.0.0"), (INFO["commit"], "f" * 40),
                                    ("vcs.modified=false", "vcs.modified=true")):
                with mock.patch.object(payload, "run", return_value=go_metadata(target).replace(original, wrong)):
                    with self.assertRaisesRegex(ValueError, "build metadata"):
                        payload.inspect_go(path, INFO, target, FLAGS + " -buildid=")
            path.write_bytes(header("linux", "arm64") if target[1:] != ("linux", "arm64") else header("linux", "amd64"))
            with self.assertRaises(ValueError):
                payload.inspect_header(path, *target[1:])
        path = self.root / "short-binary"
        path.write_bytes(b"short")
        with self.assertRaisesRegex(ValueError, "truncated"):
            payload.inspect_header(path, "linux", "amd64")

    def test_build_commands_cover_twelve_targets_with_shared_flags(self):
        calls = []

        def execute(command, cwd=payload.ROOT, env=None):
            calls.append((command, env))
            if command[1] == "run":
                return FLAGS
            if command[1] == "build":
                assert env is not None
                pathlib.Path(command[command.index("-o") + 1]).write_bytes(header(env["GOOS"], env["GOARCH"]))
                return ""
            name = pathlib.Path(command[-1]).name
            target = next(target for target in payload.TARGETS if name == payload.filename(target[0], INFO["version"], *target[1:]))
            return go_metadata(target)

        with mock.patch.object(payload, "identity", return_value=INFO), mock.patch.object(payload, "run", side_effect=execute):
            payload.build_go(self.root / "built")
        builds = [(command, env) for command, env in calls if command[1] == "build"]
        self.assertEqual(len(builds), 12)
        for command, env in builds:
            self.assertIn("-mod=readonly", command)
            self.assertIn("-buildvcs=true", command)
            self.assertEqual(command[command.index("-ldflags") + 1], FLAGS + " -buildid=")
            self.assertEqual(env["CGO_ENABLED"], "0")
        self.assertEqual(len(payload.read_json(self.root / "built/go-inventory.json")["artifacts"]), 12)

    def test_native_receipts_run_exact_hash_bound_bytes_and_reject_wrong_runner(self):
        receipt = self.root / "native.json"
        with mock.patch.object(payload, "identity", return_value=INFO), mock.patch.object(payload, "native_target", return_value=("linux", "arm64")):
            with mock.patch.object(payload, "run", return_value=json.dumps(INFO)) as run:
                payload.verify_native(self.manifest, receipt, ("linux", "arm64"))
            self.assertEqual(len(run.call_args_list), 3)
            self.assertTrue(all(call.args[0][1] == "--version" for call in run.call_args_list))
            self.assertEqual(len(payload.read_json(receipt)["artifacts"]), 3)
            with self.assertRaisesRegex(ValueError, "runner target"):
                payload.verify_native(self.manifest, receipt, ("linux", "amd64"))
            with mock.patch.object(payload, "run", return_value=json.dumps(INFO | {"version": "1.0.0"})):
                with self.assertRaisesRegex(ValueError, "--version"):
                    payload.verify_native(self.manifest, receipt, ("linux", "arm64"))
            with mock.patch.object(payload, "run", return_value=json.dumps(INFO | {"androidVersionCode": 1000001.0})):
                with self.assertRaisesRegex(ValueError, "Android code"):
                    payload.verify_native(self.manifest, receipt, ("linux", "arm64"))

    def apk_fixture(self):
        path = self.root / "app-release-unsigned.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"test-only fixture manifest")
        return path

    def test_apk_package_version_debug_selection_and_signed_bytes(self):
        apk = self.apk_fixture()
        good = f"package: name='com.qmix.tv' versionCode='{INFO['androidVersionCode']}' versionName='{INFO['version']}'"
        with mock.patch.object(payload, "run", return_value=good):
            payload.inspect_apk(apk, INFO, pathlib.Path("aapt"))
        for text in (good.replace("com.qmix.tv", "other.app"), good.replace("1000001", "1"),
                     good.replace(INFO["version"], "1.0.0"), good + "\napplication-debuggable", good + "\n" + good):
            with mock.patch.object(payload, "run", return_value=text), self.assertRaises(ValueError):
                payload.inspect_apk(apk, INFO, pathlib.Path("aapt"))
        with self.assertRaisesRegex(ValueError, "Gradle unsigned"):
            payload.inspect_apk(self.root / "app-debug.apk", INFO, pathlib.Path("aapt"))
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("META-INF/CERT.RSA", b"test-only marker, not a key")
        with self.assertRaisesRegex(ValueError, "signed APK"):
            payload.inspect_apk(apk, INFO, pathlib.Path("aapt"))
        apk = self.apk_fixture()
        with apk.open("ab") as stream:
            stream.write(b"APK Sig Block 42")
        with self.assertRaisesRegex(ValueError, "signed APK block"):
            payload.inspect_apk(apk, INFO, pathlib.Path("aapt"))

    def bom_fixture(self, path, roots):
        components = [{"type": "application", "name": ref.split("/")[-1].split("@")[0],
                       "version": INFO["version"], "bom-ref": ref,
                       "hashes": [{"alg": "SHA-256", "content": digest}], "licenses": [{"license": {"id": "MIT"}}]}
                      for ref, digest in roots.items()]
        payload.write_json(path, generate_sbom.make_bom(components, [], {}))

    def test_packaging_is_deterministic_complete_and_allowlisted(self):
        apk = self.apk_fixture()
        go_bom, android_bom = self.root / "go.cdx.json", self.root / "android.cdx.json"
        roots = {f'pkg:generic/{item["component"]}@{INFO["version"]}?arch={item["arch"]}&os={item["os"]}&type=binary': item["sha256"]
                 for item in self.value["artifacts"]}
        self.bom_fixture(go_bom, roots)
        self.bom_fixture(android_bom, {f'pkg:generic/qmix-android@{INFO["version"]}?type=apk': payload.sha256(apk)})
        with mock.patch.object(payload, "identity", return_value=INFO), mock.patch.object(payload, "inspect_go"), mock.patch.object(payload, "inspect_apk"), mock.patch.object(payload, "run", return_value=FLAGS):
            for name in ("first", "second"):
                payload.package(self.manifest, apk, go_bom, android_bom, pathlib.Path("aapt"), self.root / name)
        first, second = self.root / "first", self.root / "second"
        self.assertEqual({path.name: path.read_bytes() for path in first.iterdir()},
                         {path.name: path.read_bytes() for path in second.iterdir()})
        result = payload.read_json(first / f'qmix-inventory-{INFO["version"]}-any-any.json')
        self.assertEqual(len(payload.validate_inventory(result, first, INFO)), 16)
        self.assertEqual(len(list(first.iterdir())), 18)
        self.assertNotIn(f'qmix-android-{INFO["version"]}-android-any.apk', [path.name for path in first.iterdir()])
        sums = (first / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(len(sums), 17)
        for line in sums:
            digest, name = line.split("  ")
            self.assertEqual(digest, payload.sha256(first / name))
        with tarfile.open(first / payload.filename("qmix-licenses", INFO["version"], "any", "any")) as archive:
            self.assertEqual(archive.getnames(), sorted(payload.LICENSES))
            for member in archive.getmembers():
                self.assertEqual((member.mtime, member.uid, member.gid, member.mode), (0, 0, 0, 0o644))
                stream = archive.extractfile(member)
                self.assertIsNotNone(stream)
                assert stream is not None
                self.assertEqual(stream.read(), (payload.ROOT / member.name).read_bytes())
        android_record = next(item for item in result["artifacts"] if item["component"] == "qmix-android-unsigned")
        self.assertEqual(android_record["sourceArtifact"], "app-release-unsigned.apk")
        android_record["size"] = "bad non-Go data"
        payload.write_json(first / "invalid.json", result)
        with self.assertRaises(ValueError):
            generate_sbom._go_targets(first / "invalid.json", INFO)
        roots[next(iter(roots))] = "f" * 64
        with self.assertRaisesRegex(ValueError, "hash/version"):
            payload.verify_bom(go_bom, INFO, roots)

    def test_sbom_uses_target_environments_roots_hashes_and_runtime_graphs(self):
        calls = []

        def execute(command, cwd=generate_sbom.ROOT, env=None):
            calls.append((command, env))
            if command[1] == "run":
                return json.dumps(INFO)
            if command[1] == "list":
                assert env is not None
                module = "golang.org/x/time" if env["GOOS"] == "linux" else "github.com/oklookat/vantuz"
                return json.dumps({"Module": {"Path": module, "Version": "v1.0.0"}})
            return ""

        output = self.root / "sbom.json"
        with mock.patch.object(generate_sbom, "_run", side_effect=execute):
            generate_sbom.generate_go(output, self.manifest)
        bom = payload.read_json(output)
        self.assert_target_bom(bom)
        listings = [(command, env) for command, env in calls if command[1] == "list"]
        self.assertEqual(len(listings), 12)
        expected = {(item["component"], item["os"], item["arch"]) for item in self.value["artifacts"]}
        self.assertEqual({(command[-1].split("/")[-1], env["GOOS"], env["GOARCH"]) for command, env in listings}, expected)

    def assert_target_bom(self, bom):
        applications = [item for item in bom["components"] if item["type"] == "application"]
        self.assertEqual(len(applications), 12)
        edges = {item["ref"]: item["dependsOn"] for item in bom["dependencies"]}
        hashes = {f'pkg:generic/{record["component"]}@{INFO["version"]}?arch={record["arch"]}&os={record["os"]}&type=binary':
                  record["sha256"] for record in self.value["artifacts"]}
        for item in applications:
            system = "linux" if "os=linux" in item["bom-ref"] else "other"
            module = "golang.org/x/time" if system == "linux" else "github.com/oklookat/vantuz"
            self.assertEqual(edges[item["bom-ref"]], [generate_sbom._go_ref(module, "v1.0.0")])
            self.assertEqual(item["hashes"][0]["content"], hashes[item["bom-ref"]])

    def test_default_sbom_target_contract_and_output_guard_are_preserved(self):
        defaults = generate_sbom._go_targets(None, {})
        self.assertEqual([item[:3] for item in defaults], list(generate_sbom.GO_TARGETS))
        self.assertTrue(all(item[3:] == ("type=binary", None) for item in defaults))
        with self.assertRaisesRegex(ValueError, "outside source"):
            payload.prepare_output(payload.ROOT / "source-stage")


if __name__ == "__main__":
    unittest.main()
