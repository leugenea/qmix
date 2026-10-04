#!/usr/bin/env python3
"""Public, default-deny release admission (#334); not a secret security boundary."""

import argparse
import json
import os
import pathlib
import re
import sys

import release_payload as payload


def decode(text):
    return json.loads(text, object_pairs_hook=payload.unique_object,
                      parse_constant=payload.invalid_constant)


def fields(value, keys):
    payload.require(type(value) is dict and set(value) == set(keys), "invalid configuration fields")


def boolean(value):
    payload.require(type(value) is bool, "configuration requires Boolean")
    return value


def trust_environment(trust):
    fields(trust, ("mode", "environment", "acknowledged"))
    payload.require(trust["acknowledged"] is True, "owner trust acknowledgement required")
    environment = trust["environment"]
    payload.require(type(environment) is str, "invalid environment")
    if trust["mode"] == "repository-writers":
        payload.require(environment == "", "writers mode must not select environment")
    else:
        payload.require(trust["mode"] == "protected-environment", "invalid trust mode")
        payload.require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", environment)
                        and environment.lower() != "github-pages", "invalid release environment")
    return environment


def stable_policy(stable):
    fields(stable, ("enabled", "aliases", "latest"))
    boolean(stable["enabled"])
    boolean(stable["latest"])
    aliases = stable["aliases"]
    payload.require(type(aliases) is list and all(type(item) is str for item in aliases),
                    "invalid alias policy")
    payload.require(len(aliases) == len(set(aliases)) and set(aliases) <= {"major", "minor", "latest"},
                    "unbounded or duplicate aliases")
    return stable


def configuration(policy_text, approval_text, tag):
    policy, approval = decode(policy_text), decode(approval_text)
    fields(policy, ("schemaVersion", "enabled", "trust", "certificateSha256", "image", "stable"))
    fields(approval, ("schemaVersion", "tag", "commit", "productReceipt"))
    for value in (policy, approval):
        payload.require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1,
                        "unsupported configuration schema")
    payload.require(boolean(policy["enabled"]), "production disabled")
    environment = trust_environment(policy["trust"])
    stable_policy(policy["stable"])
    payload.require(policy["image"] == "ghcr.io/leugenea/qmix", "unapproved image namespace")
    payload.require(type(policy["certificateSha256"]) is str and
                    re.fullmatch(r"[0-9a-f]{64}", policy["certificateSha256"]), "invalid certificate pin")
    payload.require(type(tag) is str and re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?", tag),
                    "invalid release ref")
    payload.require(approval["tag"] == tag and type(approval["commit"]) is str and
                    re.fullmatch(r"[0-9a-f]{40}", approval["commit"]), "invalid exact approval")
    payload.require(type(approval["productReceipt"]) is str, "invalid product receipt")
    return policy, approval, environment


def distribution_policy(policy, approval, info):
    rc = "-rc." in info["version"]
    if rc:
        payload.require(approval["productReceipt"] == "", "RC approval must not depend on stable receipt")
        return {"prerelease": True, "latest": False, "aliases": []}
    stable = policy["stable"]
    payload.require(stable["enabled"], "stable disabled")
    match = re.fullmatch(r"https://github.com/leugenea/qmix/issues/([1-9][0-9]*)(?:#issuecomment-[0-9]+)?",
                         approval["productReceipt"])
    payload.require(match is not None and match[1] not in {"67", "323", "334", "335"},
                    "non-release product receipt required")
    major, minor, _ = info["version"].split(".")
    names = {"major": "v" + major, "minor": f"v{major}.{minor}", "latest": "latest"}
    return {"prerelease": False, "latest": stable["latest"],
            "aliases": [names[item] for item in stable["aliases"]]}


def remote_identity(tag, approved):
    # No force/tag rewriting. Stale/moved tags fail fetch or the exact comparison.
    payload.run(["git", "fetch", "--no-tags", "origin", "refs/heads/main:refs/remotes/origin/main"])
    payload.run(["git", "fetch", "--tags", "origin"])
    remote = payload.run(["git", "ls-remote", "--tags", "origin", "refs/tags/" + tag,
                          "refs/tags/" + tag + "^{}"])
    refs = dict(line.split()[::-1] for line in remote.splitlines())
    peeled = refs.get("refs/tags/" + tag + "^{}", refs.get("refs/tags/" + tag))
    payload.require(peeled == approved, "remote tag differs from approval")
    payload.require(payload.run(["git", "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}"]) == approved,
                    "local tag differs from current remote")
    payload.run(["git", "merge-base", "--is-ancestor", approved, "refs/remotes/origin/main"])
    payload.require(payload.run(["git", "tag", "--points-at", "HEAD"]) == tag,
                    "exactly one tag required")
    info = payload.identity()
    payload.require(info["commit"] == approved and "v" + info["version"] == tag,
                    "calculator differs from exact approval")
    return info


def admit(policy_text, approval_text, tag):
    policy, approval, environment = configuration(policy_text, approval_text, tag)
    info = remote_identity(tag, approval["commit"])
    return {"identity": info, "certificateSha256": policy["certificateSha256"],
            "image": policy["image"], "environment": environment,
            "distribution": distribution_policy(policy, approval, info)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=pathlib.Path, required=True)
    args = parser.parse_args()
    receipt = admit(os.environ.get("QMIX_RELEASE_POLICY", ""),
                    os.environ.get("QMIX_RELEASE_APPROVAL", ""), os.environ.get("QMIX_RELEASE_TAG", ""))
    payload.write_json(args.receipt, receipt)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            policy = decode(os.environ["QMIX_RELEASE_POLICY"])
            output.write("admitted=true\nenvironment=" + receipt["environment"] +
                         "\ntrust_mode=" + policy["trust"]["mode"] + "\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, TypeError) as error:
        print("release admission denied: invalid or unavailable public configuration/ref", file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("release admission denied: ref/calculator verification failed", file=sys.stderr)
        sys.exit(1)
