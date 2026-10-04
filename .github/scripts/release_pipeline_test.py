#!/usr/bin/env python3
"""Controlled production adapter contracts; NOT SDK, install or registry runtime proof."""

import copy
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import release_guard as guard
import release_images as images
import release_payload as payload
import release_publish as publish
import release_signing as signing
from release_payload_test import INFO

PIN = "1" * 64
POLICY = {"schemaVersion": 1, "enabled": True,
          "trust": {"mode": "repository-writers", "environment": "", "acknowledged": True},
          "certificateSha256": PIN, "image": "ghcr.io/leugenea/qmix",
          "stable": {"enabled": False, "aliases": [], "latest": False}}
APPROVAL = {"schemaVersion": 1, "tag": "v" + INFO["version"], "commit": INFO["commit"], "productReceipt": ""}
ADMISSION = {"identity": INFO, "certificateSha256": PIN, "image": POLICY["image"], "environment": "",
             "distribution": {"prerelease": True, "latest": False, "aliases": []}}


class GuardTest(unittest.TestCase):
    def admit(self, policy=POLICY, approval=APPROVAL):
        with mock.patch.object(guard, "remote_identity", return_value=INFO):
            return guard.admit(json.dumps(policy), json.dumps(approval), APPROVAL["tag"])

    def test_missing_empty_invalid_config_denies(self):
        for value in ("", "{}", "null", "[]", '{"enabled":true,"enabled":true}', '{"enabled":NaN}'):
            with self.subTest(value=value), self.assertRaises((ValueError, TypeError)):
                guard.admit(value, json.dumps(APPROVAL), APPROVAL["tag"])
        for field, value in (("enabled", "true"), ("enabled", False), ("schemaVersion", True),
                             ("certificateSha256", ""), ("image", "ghcr.io/other/image")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.admit(POLICY | {field: value})
        for field, value in (("tag", "v0.2.0"), ("commit", ""), ("schemaVersion", True), ("productReceipt", False)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.admit(approval=APPROVAL | {field: value})

    def test_trust_modes_are_explicit_and_do_not_provision(self):
        self.assertEqual(self.admit()["environment"], "")
        valid = {"mode": "protected-environment", "environment": "owner-release", "acknowledged": True}
        self.assertEqual(self.admit(POLICY | {"trust": valid})["environment"], "owner-release")
        for value in ({}, valid | {"environment": "github-pages"}, valid | {"acknowledged": False},
                      valid | {"mode": "automatic"}, valid | {"environment": ""},
                      POLICY["trust"] | {"environment": "unexpected"}):
            with self.subTest(trust=value), self.assertRaises(ValueError):
                self.admit(POLICY | {"trust": value})

    def test_rc_does_not_wait_product_and_stable_is_separately_disabled(self):
        self.assertEqual(self.admit()["distribution"], {"prerelease": True, "latest": False, "aliases": []})
        stable_info = INFO | {"version": "0.1.0", "androidVersionCode": 1009999}
        with self.assertRaisesRegex(ValueError, "stable disabled"):
            guard.distribution_policy(POLICY, APPROVAL, stable_info)
        stable = {"enabled": True, "aliases": ["major", "minor", "latest"], "latest": True}
        policy = POLICY | {"stable": stable}
        for receipt in ("", "https://github.com/leugenea/qmix/issues/323", "not-a-receipt"):
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                guard.distribution_policy(policy, APPROVAL | {"productReceipt": receipt}, stable_info)
        result = guard.distribution_policy(policy, APPROVAL | {"productReceipt": "https://github.com/leugenea/qmix/issues/62#issuecomment-123"}, stable_info)
        self.assertEqual(result, {"prerelease": False, "latest": True, "aliases": ["v0", "v0.1", "latest"]})
        for aliases in (["latest", "latest"], ["arbitrary"], "latest"):
            with self.subTest(aliases=aliases), self.assertRaises(ValueError):
                self.admit(POLICY | {"stable": stable | {"aliases": aliases}})

    def test_actual_ref_protocol_peels_and_checks_main_and_single_tag(self):
        tag = APPROVAL["tag"]
        remote = "a" * 40 + "\trefs/tags/" + tag + "\n" + INFO["commit"] + "\trefs/tags/" + tag + "^{}"
        with mock.patch.object(payload, "run", side_effect=["", "", remote, INFO["commit"], "", tag]) as runner, mock.patch.object(payload, "identity", return_value=INFO):
            self.assertEqual(guard.remote_identity(tag, INFO["commit"]), INFO)
        commands = [call.args[0] for call in runner.call_args_list]
        self.assertIn(["git", "merge-base", "--is-ancestor", INFO["commit"], "refs/remotes/origin/main"], commands)
        self.assertTrue(all("--force" not in command for command in commands))
        for wrong in ("v0.1.0", tag + "\nv0.1.0", ""):
            with mock.patch.object(payload, "run", side_effect=["", "", remote, INFO["commit"], "", wrong]), self.assertRaises(ValueError):
                guard.remote_identity(tag, INFO["commit"])
        with mock.patch.object(payload, "run", side_effect=["", "", remote.replace(INFO["commit"], "b" * 40)]), self.assertRaises(ValueError):
            guard.remote_identity(tag, INFO["commit"])
        with mock.patch.object(payload, "run", side_effect=subprocess.CalledProcessError(1, ["git"])), self.assertRaises(subprocess.CalledProcessError):
            guard.remote_identity(tag, INFO["commit"])


    def test_off_main_and_inconsistent_calculator_deny_exact_approval(self):
        tag = APPROVAL["tag"]
        remote = INFO["commit"] + "\trefs/tags/" + tag
        failure = subprocess.CalledProcessError(1, ["git", "merge-base", "--is-ancestor"])
        with mock.patch.object(payload, "run", side_effect=["", "", remote, INFO["commit"], failure]), self.assertRaises(subprocess.CalledProcessError):
            guard.remote_identity(tag, INFO["commit"])
        for info in (INFO | {"commit": "b" * 40}, INFO | {"version": "0.1.0"}):
            with mock.patch.object(payload, "run", side_effect=["", "", remote, INFO["commit"], "", tag]), mock.patch.object(payload, "identity", return_value=info), self.assertRaises(ValueError):
                guard.remote_identity(tag, INFO["commit"])


class SigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.apk = self.root / "app-release-signed.apk"
        self.apk.write_bytes(b"controlled metadata input, NOT an APK signature")
        self.sdk = self.root / "sdk"

    def test_sdk_configuration_denies_missing_and_wrong_revision(self):
        with mock.patch.dict(os.environ, {"ANDROID_HOME": str(self.root)}):
            with self.assertRaises(ValueError):
                signing.sdk_tools()
            sdk = self.root / "build-tools" / "35.0.0"
            sdk.mkdir(parents=True)
            (sdk / "source.properties").write_text("Pkg.Revision = 34.0.0\n")
            with self.assertRaises(ValueError):
                signing.sdk_tools()
            (sdk / "source.properties").write_text("Pkg.Revision = 35.0.0\n")
            with self.assertRaises(ValueError):
                signing.sdk_tools()
            for name in ("aapt", "apksigner", "zipalign"):
                (sdk / name).write_bytes(b"controlled SDK file existence; not executable")
            self.assertEqual(signing.sdk_tools(), sdk)

    def metadata(self):
        return f"package: name='com.qmix.tv' versionCode='{INFO['androidVersionCode']}' versionName='{INFO['version']}'\nsdkVersion:'23'\n".encode()

    def test_sdk_verifier_command_and_pin_metadata_rejections(self):
        cert = ("Signer #1 certificate SHA-256 digest: " + PIN + "\n").encode()
        with mock.patch.object(signing, "tool", side_effect=[b"", cert, self.metadata()]) as runner:
            signing.verify_apk(self.apk, INFO, self.sdk, PIN)
        args = [call.args[0] for call in runner.call_args_list]
        self.assertIn("--min-sdk-version", args[1])
        self.assertEqual(args[1][args[1].index("--min-sdk-version") + 1], "23")
        for text in (b"", cert.replace(PIN.encode(), b"0" * 64), cert + cert):
            with mock.patch.object(signing, "tool", side_effect=[b"", text]), self.assertRaises(ValueError):
                signing.verify_apk(self.apk, INFO, self.sdk, PIN)
        for text in (self.metadata() + b"application-debuggable", self.metadata().replace(b"23", b"24"),
                     self.metadata().replace(b"com.qmix.tv", b"wrong.package"), self.metadata().replace(b"1000001", b"1")):
            with mock.patch.object(signing, "tool", side_effect=[b"", cert, text]), self.assertRaises(ValueError):
                signing.verify_apk(self.apk, INFO, self.sdk, PIN)

    def test_sign_alignment_order_env_passwords_and_sanitized_errors(self):
        unsigned = self.root / "app-release-unsigned.apk"
        key = self.root / "key.keystore"
        cert = b"controlled public certificate bytes"
        pin = hashlib.sha256(cert).hexdigest()
        env = {"QMIX_RELEASE_STORE_PASSWORD": "test-private-sentinel", "QMIX_RELEASE_KEY_PASSWORD": "test-private-sentinel"}
        self.apk.unlink()
        with mock.patch.object(payload, "inspect_apk"), mock.patch.object(signing, "verify_apk"), mock.patch.object(signing, "tool", side_effect=[cert, b"", b""]) as runner:
            signing.sign(unsigned, self.apk, INFO, self.sdk, pin, key, "fixture", env)
        commands = [call.args[0] for call in runner.call_args_list]
        self.assertEqual(pathlib.Path(commands[1][0]).name, "zipalign")
        self.assertEqual(pathlib.Path(commands[2][0]).name, "apksigner")
        self.assertIn("env:QMIX_RELEASE_STORE_PASSWORD", commands[2])
        self.assertIn("env:QMIX_RELEASE_KEY_PASSWORD", commands[2])
        self.assertNotIn("test-private-sentinel", repr(commands))
        failure = subprocess.CalledProcessError(1, ["secret-argv"], stderr=b"test-private-sentinel")
        with mock.patch.object(subprocess, "run", side_effect=failure), self.assertRaises(ValueError) as caught:
            signing.tool(["test"])
        self.assertNotIn("test-private-sentinel", str(caught.exception))
        self.assertNotIn("secret-argv", str(caught.exception))

    def test_private_production_key_cleanup_on_sign_failure_no_fallback(self):
        env = {"RUNNER_TEMP": str(self.root), "QMIX_RELEASE_KEYSTORE_BASE64": "aW52YWxpZA==",
               "QMIX_RELEASE_KEY_ALIAS": "fixture"}
        with mock.patch.dict(os.environ, env), mock.patch.object(signing, "sign", side_effect=ValueError("reject")) as signer:
            with self.assertRaises(ValueError):
                signing.production_sign(self.root / "unsigned", self.apk, INFO, self.sdk, PIN)
        key = signer.call_args.args[5]
        self.assertFalse(key.exists())
        self.assertFalse(key.parent.exists())
        with mock.patch.dict(os.environ, {"RUNNER_TEMP": str(self.root), "QMIX_RELEASE_KEYSTORE_BASE64": ""}):
            with self.assertRaises(ValueError):
                signing.production_sign(self.root / "unsigned", self.apk, INFO, self.sdk, PIN)
        self.assertEqual(list(self.root.glob("qmix-sign-*")), [])


class ImagesTest(unittest.TestCase):
    def image_data(self, arch="amd64"):
        return {"Os": "linux", "Architecture": arch, "Id": "sha256:" + PIN,
                "Config": {"Labels": {"org.opencontainers.image.source": images.SOURCE,
                "org.opencontainers.image.version": INFO["version"], "org.opencontainers.image.revision": INFO["commit"]}}}

    def test_real_native_adapter_inspect_and_execution_protocol(self):
        with mock.patch.object(images, "docker", side_effect=[json.dumps([self.image_data()]), json.dumps(INFO)]) as command:
            self.assertEqual(images.inspect_image("controlled-native-image", INFO, "amd64"), "sha256:" + PIN)
        self.assertEqual(command.call_args_list[1].args, ("run", "--rm", "controlled-native-image", "--version"))
        for value in (self.image_data("arm64"), self.image_data() | {"Os": "windows"},
                      self.image_data() | {"Id": "not-a-digest"}):
            with mock.patch.object(images, "docker", side_effect=[json.dumps([value]), json.dumps(INFO)]), self.assertRaises(ValueError):
                images.inspect_image("controlled-native-image", INFO, "amd64")

    def test_readiness_cleanup_on_failed_smoke(self):
        binding = [{"NetworkSettings": {"Ports": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49100"}]}}}]
        with mock.patch.object(images, "docker", side_effect=["controlled-container", json.dumps(binding), ""]) as command, mock.patch.object(images, "wait_ready", side_effect=ValueError("controlled timeout")) as ready:
            with self.assertRaises(ValueError):
                images.readiness("controlled-image")
        ready.assert_called_once_with("http://127.0.0.1:49100")
        self.assertEqual(command.call_args.args, ("rm", "--force", "controlled-container"))


class Protocol:
    """Bounded in-memory transport responses to actual adapter calls, not servers."""

    def __init__(self, directory):
        self.directory = directory
        self.events = []
        self.release = None
        self.manifests = {}
        self.latest = None
        self.corrupt_download = False
        self.info = INFO

    def response(self, value, status=200):
        return (404 if value is None else status), {}, json.dumps(value).encode()

    def request(self, url, headers):
        self.events.append(("GET", url))
        if url.startswith("https://ghcr.io/token?"):
            return self.response({"token": "controlled-registry-token"})
        if "/manifests/" in url:
            return self.manifest_response(url.rsplit("/", 1)[1])
        assert headers["Authorization"] == "Bearer test-only-token"
        return self.github_response(url)

    def github_response(self, url):
        if "/releases/latest" in url:
            return self.response(self.latest)
        if "/releases/tags/" in url:
            # GitHub's tag endpoint exposes published releases, never drafts.
            visible = self.release
            if visible and visible["draft"]:
                visible = None
            return self.response(visible)
        if "/releases?" in url:
            return self.response([self.release] if self.release else [])
        if "/releases/" in url:
            visible = self.release
            if visible and not url.endswith("/" + str(visible["id"])):
                visible = None
            return self.response(visible)
        return self.response({"id": 1})

    def manifest_response(self, ref):
        if ref not in self.manifests:
            return self.response({"errors": [{"code": "MANIFEST_UNKNOWN"}]}, 404)
        raw = json.dumps(self.manifests[ref]).encode()
        digest = "sha256:" + hashlib.sha256(raw).hexdigest()
        return 200, {"Docker-Content-Digest": digest}, raw

    def command(self, args, **kwargs):
        self.events.append(("CLI", args))
        if args[:3] == ["gh", "release", "create"]:
            self.release = {"id": 123, "tag_name": args[3], "draft": True,
                            "prerelease": "--prerelease=true" in args, "assets": []}
        elif args[:3] == ["gh", "release", "upload"]:
            self.release["assets"] = [{"name": name, "size": (self.directory / name).stat().st_size, "state": "uploaded"}
                                      for name in ("controlled.bin",)]
        elif args[:3] == ["gh", "release", "download"]:
            destination = pathlib.Path(args[args.index("--dir") + 1])
            shutil.copyfile(self.directory / "controlled.bin", destination / "controlled.bin")
            if self.corrupt_download:
                (destination / "controlled.bin").write_bytes(b"altered")
        elif args[:3] == ["gh", "release", "edit"]:
            self.release["draft"] = False
            if "--latest=true" in args:
                self.latest = self.release
        return b""

    def docker(self, *args):
        self.events.append(("DOCKER", args))
        if args[0] == "push":
            arch = args[1].rsplit("-", 1)[1]
            self.manifests[self.info["version"] + "-" + arch] = {"config": {"digest": "sha256:" + ("a" if arch == "amd64" else "b") * 64}}
        elif args[:3] == ("buildx", "imagetools", "create"):
            rows = [{"platform": {"os": "linux", "architecture": arch},
                     "digest": self.manifest_response(self.info["version"] + "-" + arch)[1]["Docker-Content-Digest"]}
                    for arch in ("amd64", "arm64")]
            self.manifests[args[args.index("--tag") + 1].rsplit(":", 1)[1]] = {"manifests": rows}
        return ""


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.file = self.root / "controlled.bin"
        self.file.write_bytes(b"controlled publisher protocol bytes, NOT runtime artifact")
        self.protocol = Protocol(self.root)
        for arch, digit in (("amd64", "a"), ("arm64", "b")):
            payload.write_json(self.root / (arch + ".json"), {"configDigest": "sha256:" + digit * 64})
        self.receipt = self.root / "publication.json"
        self.stack = __import__("contextlib").ExitStack()
        self.addCleanup(self.stack.close)
        patches = ((guard, "admit", {"return_value": ADMISSION}), (signing, "sdk_tools", {"return_value": self.root}),
                   (signing, "verify_final", {"return_value": [self.file.name]}),
                   (images, "load_pair", {"return_value": {"amd64": "local-amd64", "arm64": "local-arm64"}}),
                   (publish, "request", {"side_effect": self.protocol.request}),
                   (signing, "tool", {"side_effect": self.protocol.command}),
                   (images, "docker", {"side_effect": self.protocol.docker}),
                   (publish.Services, "login", {"return_value": None}))
        for target, name, options in patches:
            self.stack.enter_context(mock.patch.object(target, name, **options))
        self.stack.enter_context(mock.patch.dict(os.environ, {"RUNNER_TEMP": str(self.root), "GH_TOKEN": "test-only-token", "GITHUB_ACTOR": "fixture"}))

    def run_publisher(self):
        publish.execute(self.root, self.root, self.receipt)

    def writes(self):
        return [event for event in self.protocol.events if event[0] in {"CLI", "DOCKER"}]

    def test_actual_adapter_complete_draft_download_order_and_immutable_rerun(self):
        self.run_publisher()
        commands = [event[1] for event in self.writes() if event[0] == "CLI"]
        create = commands[0]
        self.assertEqual(create[:4], ["gh", "release", "create", APPROVAL["tag"]])
        for flag in ("--verify-tag", "--draft", "--prerelease=true", "--latest=false"):
            self.assertIn(flag, create)
        self.assertNotIn("--clobber", repr(commands))
        self.assert_publish_order()
        state = payload.read_json(self.receipt)
        self.assertEqual(state["status"], "published-and-read-back")
        self.assertEqual(state["releaseId"], 123)
        readbacks = [event[1] for event in self.protocol.events if event[0] == "GET" and "/releases/123" in event[1]]
        self.assertEqual(len(readbacks), 3)  # Fresh draft, uploaded draft, public: same ID.
        self.assertFalse(any("/releases/tags/" in event[1] for event in self.protocol.events if event[0] == "GET"))
        self.assertEqual(set(state["platforms"]), {"amd64", "arm64"})
        self.assertEqual(state["aliases"], {})
        before = len(self.writes())
        with self.assertRaisesRegex(ValueError, "conflict"):
            self.run_publisher()
        self.assertEqual(len(self.writes()), before)

    def assert_publish_order(self):
        downloads = [index for index, event in enumerate(self.protocol.events) if event[0] == "CLI" and event[1][2] == "download"]
        pushes = [index for index, event in enumerate(self.protocol.events) if event[0] == "DOCKER" and event[1][0] == "push"]
        edit = next(index for index, event in enumerate(self.protocol.events) if event[0] == "CLI" and event[1][2] == "edit")
        self.assertLess(downloads[0], min(pushes))
        self.assertLess(max(pushes), edit)
        self.assertLess(edit, downloads[1])

    def test_signing_validation_failures_have_zero_external_writes(self):
        with mock.patch.object(signing, "verify_final", side_effect=ValueError("unsigned/debug/wrong signing bytes")):
            with self.assertRaises(ValueError):
                self.run_publisher()
        self.assertEqual(self.protocol.events, [])
        self.assertFalse(self.receipt.exists())

    def test_preflight_release_and_platform_conflicts_no_writes(self):
        for ref in (INFO["version"], INFO["version"] + "-amd64", INFO["version"] + "-arm64"):
            self.protocol.manifests = {ref: {"config": {"digest": "sha256:" + "a" * 64}}}
            with self.subTest(ref=ref), self.assertRaisesRegex(ValueError, "conflict"):
                self.run_publisher()
            self.assertEqual(self.writes(), [])

    def test_unknown_auth_and_non_manifest_404_deny_no_writes(self):
        for status, body in ((401, {}), (403, {}), (500, {}), (404, {"errors": [{"code": "NOT_A_REGISTRY_ERROR"}]})):
            response = lambda url, headers: (status, {}, json.dumps(body).encode())
            with mock.patch.object(publish, "request", side_effect=response), self.subTest(status=status), self.assertRaises(ValueError):
                self.run_publisher()
            self.assertEqual(self.writes(), [])
        service = publish.Services()
        service.registry_token = "test-only"
        for status in (401, 403, 500):
            with mock.patch.object(publish, "request", return_value=(status, {}, b"{}")), self.assertRaises(ValueError):
                service.registry(POLICY["image"], INFO["version"])

    def test_stable_protocol_explicit_aliases_and_latest_readback(self):
        info = INFO | {"version": "0.1.0", "androidVersionCode": 1009999}
        admission = ADMISSION | {"identity": info, "distribution": {
            "prerelease": False, "latest": True, "aliases": ["v0", "v0.1", "latest"]}}
        self.protocol.info = info
        with mock.patch.object(guard, "admit", return_value=admission):
            self.run_publisher()
        state = payload.read_json(self.receipt)
        self.assertEqual(set(state["aliases"]), {"v0", "v0.1", "latest"})
        self.assertEqual(self.protocol.latest["id"], state["releaseId"])
        commands = [event[1] for event in self.writes() if event[0] == "CLI"]
        self.assertIn("--prerelease=false", commands[0])
        self.assertIn("--latest=true", commands[0])
        self.assertEqual(state["status"], "published-and-read-back")

    def test_rc_keeps_existing_latest_and_has_no_mutable_alias_push(self):
        self.protocol.latest = {"id": 9}
        self.run_publisher()
        self.assertEqual(self.protocol.latest["id"], 9)
        creates = [event[1] for event in self.writes() if event[0] == "DOCKER" and event[1][:3] == ("buildx", "imagetools", "create")]
        self.assertEqual(len(creates), 1)
        self.assertIn(POLICY["image"] + ":" + INFO["version"], creates[0])

    def test_partial_platform_failure_retains_observed_digest_and_never_repairs(self):
        original = self.protocol.docker
        def fail_second(*args):
            if args[0] == "push" and args[1].endswith("-arm64"):
                raise ValueError("controlled second push failed")
            return original(*args)
        with mock.patch.object(images, "docker", side_effect=fail_second), self.assertRaisesRegex(ValueError, "publication stopped"):
            self.run_publisher()
        state = payload.read_json(self.receipt)
        self.assertEqual(set(state["platforms"]), {"amd64"})
        self.assertEqual(state["releaseId"], 123)
        self.assertIsNone(state["manifest"])
        self.assertTrue(self.protocol.release["draft"])
        before = len(self.writes())
        with self.assertRaisesRegex(ValueError, "conflict"):
            self.run_publisher()
        self.assertEqual(len(self.writes()), before)
        self.assertEqual(payload.read_json(self.receipt), state)

    def test_authenticated_absence_and_unknown_registry_bodies(self):
        for code in ("MANIFEST_UNKNOWN", "NAME_UNKNOWN"):
            service = publish.Services()
            with mock.patch.object(publish, "request", side_effect=[self.protocol.response({"token": "controlled-token"}),
                    self.protocol.response({"errors": [{"code": code}]}, 404)]) as transport:
                self.assertIsNone(service.registry(POLICY["image"], INFO["version"]))
                self.assertIn("Basic ", transport.call_args_list[0].args[1]["Authorization"])
                self.assertEqual(transport.call_args_list[1].args[1]["Authorization"], "Bearer controlled-token")
        service = publish.Services()
        service.registry_token = "test-only"
        for body in ({}, {"errors": []}, {"errors": [{"code": "DENIED"}]}):
            with mock.patch.object(publish, "request", return_value=self.protocol.response(body, 404)), self.assertRaises(ValueError):
                service.registry(POLICY["image"], INFO["version"])
        with mock.patch.object(publish, "request", return_value=self.protocol.response({}, 200)), self.assertRaises(ValueError):
            publish.Services().authenticate_registry(POLICY["image"])

    def test_download_corruption_stops_before_registry_and_records_draft(self):
        self.protocol.corrupt_download = True
        with self.assertRaisesRegex(ValueError, "publication stopped"):
            self.run_publisher()
        self.assertEqual([event for event in self.writes() if event[0] == "DOCKER"], [])
        state = payload.read_json(self.receipt)
        self.assertEqual(state["releaseId"], 123)
        self.assertIn("partial-or-unknown", state["status"])
        self.assertTrue(self.protocol.release["draft"])

    def test_exact_release_asset_readback_rejects_extra_missing_duplicate(self):
        service = publish.Services()
        self.protocol.release = {"id": 123, "tag_name": APPROVAL["tag"], "draft": True, "prerelease": True,
                                 "assets": [{"name": self.file.name, "size": self.file.stat().st_size, "state": "uploaded"}]}
        good = self.protocol.release["assets"]
        for rows in ([], good * 2, good + [{"name": "key.keystore", "size": 1, "state": "uploaded"}]):
            self.protocol.release["assets"] = rows
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                publish.verify_release(service, 123, APPROVAL["tag"], ADMISSION["distribution"], [self.file], True)

    def test_draft_lookup_requires_exact_unique_tag_and_positive_unique_id(self):
        good = {"id": 123, "tag_name": APPROVAL["tag"], "draft": True}
        service = publish.Services()
        for rows in ([], [good | {"tag_name": "v0.2.0"}], [good, good | {"id": 124}],
                     [good, good | {"tag_name": "v0.2.0"}], [good | {"id": None}],
                     [good | {"id": True}], [good | {"id": 0}], [good | {"id": "123"}]):
            with self.subTest(rows=rows), mock.patch.object(service, "releases", return_value=rows), self.assertRaises(ValueError):
                service.draft_id(APPROVAL["tag"])
        self.protocol.release = good
        self.assertEqual(self.protocol.github_response("/releases/tags/" + APPROVAL["tag"])[0], 404)
        self.assertEqual(service.draft_id(APPROVAL["tag"]), 123)
        self.assertEqual(service.release(123), good)

    def test_post_create_inventory_unknown_stops_without_upload_or_repair(self):
        original = self.protocol.github_response
        good = {"id": 123, "tag_name": APPROVAL["tag"]}
        for response in ((401, {}, b"{}"), (403, {}, b"{}"), (500, {}, b"{}"), (200, {}, b"[]"),
                         self.protocol.response([good, good | {"id": 124}]),
                         self.protocol.response([good, good | {"tag_name": "v0.2.0"}]),
                         self.protocol.response([good | {"tag_name": "v0.2.0"}]),
                         ValueError("remote state UNKNOWN")):
            self.protocol.release = None
            self.protocol.events.clear()
            def fail_inventory(url):
                if self.protocol.release and "/releases?" in url:
                    if isinstance(response, Exception):
                        raise response
                    return response
                return original(url)
            with self.subTest(response=response), mock.patch.object(self.protocol, "github_response", side_effect=fail_inventory), self.assertRaisesRegex(ValueError, "publication stopped"):
                self.run_publisher()
            self.assertEqual([event[1][2] for event in self.writes()], ["create"])
            state = payload.read_json(self.receipt)
            self.assertIsNone(state["releaseId"])
            self.assertIn("partial-or-unknown", state["status"])
            with self.assertRaisesRegex(ValueError, "conflict"):
                self.run_publisher()
            self.assertEqual(payload.read_json(self.receipt), state)

    def test_observed_draft_id_saved_before_unknown_or_mismatched_readback(self):
        original = self.protocol.github_response
        good = {"id": 123, "tag_name": APPROVAL["tag"], "draft": True, "prerelease": True, "assets": []}
        for response in ((401, {}, b"{}"), (403, {}, b"{}"), (404, {}, b"{}"),
                         self.protocol.response(good | {"id": 124}),
                         self.protocol.response(good | {"tag_name": "v0.2.0"}),
                         self.protocol.response(good | {"draft": False}),
                         self.protocol.response(good | {"prerelease": False}),
                         ValueError("remote state UNKNOWN")):
            self.protocol.release = None
            self.protocol.events.clear()
            def fail_readback(url):
                if url.endswith("/releases/123"):
                    self.assertEqual(payload.read_json(self.receipt)["releaseId"], 123)
                    if isinstance(response, Exception):
                        raise response
                    return response
                return original(url)
            with self.subTest(response=response), mock.patch.object(self.protocol, "github_response", side_effect=fail_readback), self.assertRaisesRegex(ValueError, "publication stopped"):
                self.run_publisher()
            state = payload.read_json(self.receipt)
            self.assertEqual(state["releaseId"], 123)
            self.assertIn("partial-or-unknown", state["status"])
            self.assertEqual([event[1][2] for event in self.writes()], ["create"])
            with self.assertRaisesRegex(ValueError, "conflict"):
                self.run_publisher()
            self.assertEqual(payload.read_json(self.receipt), state)

    def test_actual_manifest_protocol_rejects_wrong_platform_and_digest(self):
        digests = {"amd64": "sha256:" + "a" * 64, "arm64": "sha256:" + "b" * 64}
        rows = [{"platform": {"os": "linux", "architecture": arch}, "digest": digest} for arch, digest in digests.items()]
        self.assertEqual(publish.verify_manifest(("sha256:" + PIN, {"manifests": rows}), digests), "sha256:" + PIN)
        for bad in (rows[:1], rows * 2, rows + [{"platform": {"os": "windows", "architecture": "amd64"}, "digest": "wrong"}]):
            with self.subTest(rows=bad), self.assertRaises(ValueError):
                publish.verify_manifest(("sha256:" + PIN, {"manifests": bad}), digests)


if __name__ == "__main__":
    unittest.main()
