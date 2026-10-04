#!/usr/bin/env python3
"""Small immutable Release/GHCR publisher; partial writes are never repaired (#334)."""

import argparse
import base64
import hashlib
import json
import os
import pathlib
import tempfile
import urllib.error
import urllib.parse
import urllib.request

import release_guard as guard
import release_images as images
import release_payload as payload
import release_signing as signing

REPOSITORY = "leugenea/qmix"
MEDIA = "application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"


def request(url, headers):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()
    except (OSError, urllib.error.URLError):
        raise ValueError("remote state UNKNOWN") from None


def parsed(data):
    return guard.decode(data.decode("utf-8"))


class Services:
    """Actual API/CLI adapter; controlled tests mock transport, not this protocol."""

    def __init__(self):
        self.token = os.environ.get("GH_TOKEN", "")
        payload.require(bool(self.token), "missing publication token")
        self.registry_token = None

    def github(self, endpoint):
        return request("https://api.github.com/repos/" + REPOSITORY + endpoint,
                       {"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                        "X-GitHub-Api-Version": "2022-11-28"})

    def releases(self):
        status, _, _ = self.github("")
        payload.require(status == 200, "repository state UNKNOWN")
        result = []
        page = 1
        while True:
            status, _, body = self.github(f"/releases?per_page=100&page={page}")
            payload.require(status == 200, "Release state UNKNOWN")
            rows = parsed(body)
            payload.require(type(rows) is list, "Release state UNKNOWN")
            result.extend(rows)
            if len(rows) < 100:
                return result
            page += 1
            payload.require(page <= 100, "Release inventory bound exceeded")

    def registry(self, image, ref):
        if self.registry_token is None:
            self.authenticate_registry(image)
        name = image.removeprefix("ghcr.io/")
        status, headers, body = request("https://ghcr.io/v2/" + name + "/manifests/" + ref,
                                       {"Authorization": "Bearer " + self.registry_token, "Accept": MEDIA})
        if status == 404:
            codes = [item.get("code") for item in parsed(body).get("errors", [])]
            payload.require(codes in (["MANIFEST_UNKNOWN"], ["NAME_UNKNOWN"]), "registry state UNKNOWN")
            return None
        payload.require(status == 200, "registry state UNKNOWN")
        digest = "sha256:" + hashlib.sha256(body).hexdigest()
        payload.require(headers.get("Docker-Content-Digest", headers.get("docker-content-digest")) == digest,
                        "registry digest differs")
        return digest, parsed(body)

    def authenticate_registry(self, image):
        scope = "repository:" + image.removeprefix("ghcr.io/") + ":pull,push"
        query = urllib.parse.urlencode({"service": "ghcr.io", "scope": scope})
        basic = base64.b64encode((os.environ.get("GITHUB_ACTOR", "") + ":" + self.token).encode()).decode()
        status, _, body = request("https://ghcr.io/token?" + query, {"Authorization": "Basic " + basic})
        payload.require(status == 200, "registry authentication UNKNOWN")
        self.registry_token = parsed(body).get("token")
        payload.require(type(self.registry_token) is str and bool(self.registry_token), "registry authentication UNKNOWN")

    def gh(self, *args):
        return signing.tool(["gh", "release", *args, "--repo", REPOSITORY]).decode().strip()

    def draft_id(self, tag):
        # Authenticated inventory includes drafts; the tag endpoint is published-only.
        rows = self.releases()
        matches = [item for item in rows if item.get("tag_name") == tag]
        payload.require(len(matches) == 1, "created draft lookup UNKNOWN")
        release_id = matches[0].get("id")
        payload.require(type(release_id) is int and release_id > 0 and
                        sum(item.get("id") == release_id for item in rows) == 1,
                        "created draft ID UNKNOWN")
        return release_id

    def release(self, release_id):
        status, _, body = self.github("/releases/" + str(release_id))
        payload.require(status == 200, "Release readback UNKNOWN")
        release = parsed(body)
        payload.require(type(release.get("id")) is int and release["id"] == release_id,
                        "Release ID differs")
        return release

    def latest(self):
        status, _, body = self.github("/releases/latest")
        payload.require(status in (200, 404), "latest state UNKNOWN")
        return parsed(body)["id"] if status == 200 else None

    def create(self, tag, policy):
        self.gh("create", tag, "--verify-tag", "--draft", "--prerelease=" + str(policy["prerelease"]).lower(),
                "--latest=" + str(policy["latest"]).lower(), "--title", tag,
                "--notes", "Coupled QMix release. See inventory, SBOMs and SHA256SUMS for exact identity.")

    def upload(self, tag, files):
        self.gh("upload", tag, *[str(path) for path in files])  # Deliberately no --clobber.

    def publish(self, tag, policy):
        self.gh("edit", tag, "--draft=false", "--prerelease=" + str(policy["prerelease"]).lower(),
                "--latest=" + str(policy["latest"]).lower())

    def download(self, tag, directory):
        self.gh("download", tag, "--dir", str(directory))  # New empty directory; no overwrite.

    def login(self):
        try:
            import subprocess
            subprocess.run(["docker", "login", "ghcr.io", "--username", os.environ["GITHUB_ACTOR"],
                            "--password-stdin"], input=self.token.encode(), check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        except Exception:
            raise ValueError("registry login failed (details suppressed)") from None


def preflight(service, tag, admission):
    payload.require(not any(item.get("tag_name") == tag for item in service.releases()), "immutable Release conflict")
    image, version = admission["image"], admission["identity"]["version"]
    for suffix in ("", "-amd64", "-arm64"):
        payload.require(service.registry(image, version + suffix) is None, "immutable image conflict")
    # Read every selected alias before writes; inaccessible alias state is UNKNOWN.
    return {alias: service.registry(image, alias) for alias in admission["distribution"]["aliases"]}


def verify_release(service, release_id, tag, policy, files, draft):
    release = service.release(release_id)
    payload.require(release["tag_name"] == tag and release["draft"] is draft and
                    release["prerelease"] is policy["prerelease"], "Release metadata differs")
    rows = release["assets"]
    expected = {path.name: path.stat().st_size for path in files}
    actual = {item["name"]: item["size"] for item in rows}
    payload.require(len(rows) == len(actual) and actual == expected and
                    all(item.get("state") == "uploaded" for item in rows), "Release incomplete asset allowlist")
    return release["id"]


def verify_download(service, tag, files, admission, sdk):
    with tempfile.TemporaryDirectory(dir=os.environ["RUNNER_TEMP"]) as temporary:
        destination = pathlib.Path(temporary)
        service.download(tag, destination)
        signing.verify_final(destination, admission["identity"], sdk, admission["certificateSha256"])
        payload.require(all(payload.sha256(destination / path.name) == payload.sha256(path) for path in files),
                        "download differs from validated bytes")


def save(receipt_path, state):
    payload.write_json(receipt_path, state)


def push_platforms(service, image, version, local, receipts, receipt_path, state):
    digests = {}
    for arch in ("amd64", "arm64"):
        destination = image + ":" + version + "-" + arch
        images.docker("tag", local[arch], destination)
        images.docker("push", destination)
        remote = service.registry(image, version + "-" + arch)
        payload.require(remote is not None, "pushed platform missing")
        digest, manifest = remote
        digests[arch] = digest
        state["platforms"][arch] = digest
        save(receipt_path, state)
        payload.require(manifest.get("config", {}).get("digest") == receipts[arch]["configDigest"],
                        "published config differs from native verified bytes")
    return digests


def verify_manifest(remote, digests):
    payload.require(remote is not None, "published manifest missing")
    digest, value = remote
    rows = value.get("manifests", [])
    actual = {(item.get("platform", {}).get("os"), item.get("platform", {}).get("architecture")): item.get("digest")
              for item in rows}
    payload.require(len(rows) == 2 and actual == {("linux", arch): value for arch, value in digests.items()},
                    "published manifest platforms/digests differ")
    return digest


def publish_images(service, admission, local, receipts, receipt_path, state):
    image, version = admission["image"], admission["identity"]["version"]
    digests = push_platforms(service, image, version, local, receipts, receipt_path, state)
    destination = image + ":" + version
    images.docker("buildx", "imagetools", "create", "--tag", destination,
                  *[image + "@" + digests[arch] for arch in ("amd64", "arm64")])
    state["manifest"] = verify_manifest(service.registry(image, version), digests)
    save(receipt_path, state)
    for alias in admission["distribution"]["aliases"]:
        images.docker("buildx", "imagetools", "create", "--tag", image + ":" + alias,
                      image + "@" + state["manifest"])
        state["aliases"][alias] = verify_manifest(service.registry(image, alias), digests)
        save(receipt_path, state)


def execute(directory, image_directory, receipt_path):
    # Validate key/certificate/final bytes/identity/archives before even staging a draft/image.
    admission = guard.admit(os.environ.get("QMIX_RELEASE_POLICY", ""), os.environ.get("QMIX_RELEASE_APPROVAL", ""),
                            os.environ.get("QMIX_RELEASE_TAG", ""))
    sdk = signing.sdk_tools()
    names = signing.verify_final(directory, admission["identity"], sdk, admission["certificateSha256"])
    files = [directory / name for name in names]
    local = images.load_pair(image_directory, admission["identity"])
    receipts = {arch: payload.read_json(image_directory / (arch + ".json")) for arch in ("amd64", "arm64")}
    service = Services()
    tag = "v" + admission["identity"]["version"]
    state = {"tag": tag, "identity": admission["identity"], "releaseId": None,
             "platforms": {}, "manifest": None, "aliases": {}, "status": "validated"}
    state["aliasesBefore"] = preflight(service, tag, admission)
    state["latestBefore"] = service.latest()
    save(receipt_path, state)
    # Ephemeral Docker auth config is supplied by the workflow outside the checkout.
    service.login()
    try:
        publish_set(service, tag, admission, files, sdk, local, receipts, receipt_path, state)
    except Exception:
        state["status"] = "partial-or-unknown; owner recovery required"
        save(receipt_path, state)
        raise ValueError("publication stopped; inspect public partial-state receipt, never overwrite") from None


def publish_set(service, tag, admission, files, sdk, local, receipts, receipt_path, state):
    policy = admission["distribution"]
    service.create(tag, policy)
    state["releaseId"] = service.draft_id(tag)
    save(receipt_path, state)
    verify_release(service, state["releaseId"], tag, policy, [], draft=True)
    service.upload(tag, files)
    verify_release(service, state["releaseId"], tag, policy, files, draft=True)
    verify_download(service, tag, files, admission, sdk)
    publish_images(service, admission, local, receipts, receipt_path, state)
    service.publish(tag, policy)
    verify_release(service, state["releaseId"], tag, policy, files, draft=False)
    verify_download(service, tag, files, admission, sdk)
    latest = service.latest()
    if policy["latest"]:
        payload.require(latest == state["releaseId"], "latest readback differs")
    else:
        payload.require(latest == state["latestBefore"], "unexpected latest release change")
    state["status"] = "published-and-read-back"
    state["assets"] = {path.name: payload.sha256(path) for path in files}
    save(receipt_path, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("directory", "images", "receipt"):
        parser.add_argument("--" + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    execute(args.directory, args.images, args.receipt)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("release publisher stopped (details suppressed); retain partial-state receipt", file=__import__("sys").stderr)
        raise SystemExit(1)
