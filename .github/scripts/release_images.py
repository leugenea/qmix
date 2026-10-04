#!/usr/bin/env python3
"""Native Docker CLI build, identity/readiness proof and image handoff (#334)."""

import argparse
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

import release_payload as payload
from release_signing import tool

SOURCE = "https://github.com/leugenea/qmix"


def docker(*args):
    return tool(["docker", *args]).decode().strip()


def inspect_image(image, info, arch):
    value = json.loads(docker("image", "inspect", image))
    payload.require(len(value) == 1, "ambiguous image")
    value = value[0]
    payload.require(value["Os"] == "linux" and value["Architecture"] == arch, "wrong native image platform")
    labels = value["Config"]["Labels"]
    expected = {"org.opencontainers.image.source": SOURCE, "org.opencontainers.image.version": info["version"],
                "org.opencontainers.image.revision": info["commit"]}
    payload.require(all(labels.get(key) == item for key, item in expected.items()), "image labels differ")
    actual = json.loads(docker("run", "--rm", image, "--version"), object_pairs_hook=payload.unique_object,
                        parse_constant=payload.invalid_constant)
    payload.require(actual == info, "image identity differs")
    digest = value["Id"]
    payload.require(re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "invalid config digest")
    return digest


def readiness(image):
    container = docker("run", "--detach", "--publish", "127.0.0.1::8080", image)
    try:
        binding = json.loads(docker("inspect", container))[0]["NetworkSettings"]["Ports"]["8080/tcp"]
        payload.require(len(binding) == 1 and binding[0]["HostIp"] == "127.0.0.1", "invalid smoke port")
        origin = "http://127.0.0.1:" + binding[0]["HostPort"]
        wait_ready(origin)
    finally:
        docker("rm", "--force", container)


def wait_ready(origin):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            results = []
            for endpoint in ("healthz", "readyz"):
                with urllib.request.urlopen(origin + "/" + endpoint, timeout=2) as response:
                    results.append(json.load(response))
            if results == [{"status": "ok"}, {"status": "ready"}]:
                return
        except (OSError, urllib.error.URLError, ValueError):
            pass
        time.sleep(1)
    raise ValueError("native image health/readiness timeout")


def build(output, arch):
    info = payload.identity()
    payload.require(payload.native_target() == ("linux", arch), "native build runner required")
    output = payload.prepare_output(output)
    image = "qmix-release:" + info["version"] + "-" + arch
    args = ["build", "--platform", "linux/" + arch, "--tag", image]
    for key, value in (("VERSION", info["version"]), ("COMMIT", info["commit"]),
                       ("DIRTY", "false"), ("ANDROID_VERSION_CODE", str(info["androidVersionCode"]))):
        args += ["--build-arg", key + "=" + value]
    docker(*args, ".")
    digest = inspect_image(image, info, arch)
    readiness(image)
    archive = output / (arch + ".tar")
    docker("save", "--output", str(archive), image)
    payload.write_json(output / (arch + ".json"), {"identity": info, "os": "linux", "arch": arch,
                       "image": image, "configDigest": digest, "archiveSha256": payload.sha256(archive),
                       "verification": "native image --version + healthz + readyz"})


def image_receipt(directory, info, arch):
    receipt = payload.read_json(directory / (arch + ".json"))
    keys = {"identity", "os", "arch", "image", "configDigest", "archiveSha256", "verification"}
    payload.require(type(receipt) is dict and set(receipt) == keys, "invalid image receipt")
    payload.require(receipt["identity"] == info and receipt["os"] == "linux" and receipt["arch"] == arch,
                    "image receipt identity/platform differs")
    payload.require(receipt["image"] == "qmix-release:" + info["version"] + "-" + arch and
                    receipt["verification"] == "native image --version + healthz + readyz", "invalid image provenance")
    payload.require(type(receipt["configDigest"]) is str and
                    re.fullmatch(r"sha256:[0-9a-f]{64}", receipt["configDigest"]), "invalid config digest")
    payload.require(type(receipt["archiveSha256"]) is str and
                    re.fullmatch(r"[0-9a-f]{64}", receipt["archiveSha256"]), "invalid image archive hash")
    return receipt


def offline_plan(directory, output):
    info = payload.identity()
    receipts = [image_receipt(directory, info, arch) for arch in ("amd64", "arm64")]
    payload.write_json(output, {"identity": info, "published": False,
                       "digestKind": "local image config digest; NOT registry manifest digest",
                       "platforms": [{"os": "linux", "arch": row["arch"], "configDigest": row["configDigest"],
                                      "archiveSha256": row["archiveSha256"]} for row in receipts]})


def load_pair(directory, info):
    result = {}
    for arch in ("amd64", "arm64"):
        receipt = image_receipt(directory, info, arch)
        expected = receipt["image"]
        archive = directory / (arch + ".tar")
        payload.require(archive.is_file() and not archive.is_symlink() and
                        payload.sha256(archive) == receipt["archiveSha256"], "image archive differs")
        docker("load", "--input", str(archive))
        # The central runner cannot run arm64. Native proof was produced by the
        # arm64 job; inspect platform/config/labels here without QEMU.
        verify_loaded(expected, receipt, info)
        result[arch] = expected
    return result


def verify_loaded(image, receipt, info):
    data = json.loads(docker("image", "inspect", image))[0]
    payload.require(data["Id"] == receipt["configDigest"] and data["Os"] == "linux" and
                    data["Architecture"] == receipt["arch"], "loaded image differs")
    labels = data["Config"]["Labels"]
    expected = {"org.opencontainers.image.source": SOURCE, "org.opencontainers.image.version": info["version"],
                "org.opencontainers.image.revision": info["commit"]}
    payload.require(all(labels.get(key) == value for key, value in expected.items()), "loaded labels differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--arch", choices=("amd64", "arm64"))
    mode.add_argument("--plan", type=pathlib.Path)
    args = parser.parse_args()
    if args.plan:
        offline_plan(args.plan, args.output)
    else:
        build(args.output, args.arch)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("native release image verification failed (details suppressed)", file=sys.stderr)
        sys.exit(1)
