#!/usr/bin/env python3
"""Generate deterministic CycloneDX 1.6 SBOMs for QMix release artifacts.

The generator is repository-owned and uses only Python's standard library plus
the pinned Go/Gradle project toolchains. It omits timestamps and random serial
numbers, sorts every set, and runs Gradle with strict dependency verification.
"""

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
GO_TARGETS = (
    ("qmix", "./cmd/qmix", ROOT / "bin" / "qmix"),
    ("token-vk", "./cmd/token-vk", ROOT / "bin" / "token-vk"),
    ("token-ym", "./cmd/token-ym", ROOT / "bin" / "token-ym"),
)
GO_LICENSES = {
    "github.com/google/go-querystring": "BSD-3-Clause",
    "github.com/oklookat/goym": "MIT",
    "github.com/oklookat/vantuz": "MIT",
    "golang.org/x/time": "BSD-3-Clause",
}


def _license(identifier):
    return [{"license": {"id": identifier}}]


def go_license(path):
    identifier = GO_LICENSES.get(path)
    if not identifier:
        raise ValueError(f"unreviewed Go runtime dependency: {path}")
    return identifier


def android_license(group, name):
    coordinate = f"{group}:{name}"
    if (
        group.startswith("androidx.")
        or group.startswith("org.jetbrains.kotlin")
        or group in {"com.squareup.okhttp3", "com.squareup.okio", "com.google.guava"}
        or coordinate
        in {
            "com.google.zxing:core",
            "org.jetbrains:annotations",
            "org.jspecify:jspecify",
        }
    ):
        return "Apache-2.0"
    raise ValueError(f"unreviewed Android runtime dependency: {coordinate}")


def serialize(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True) + "\n"


def _ordered_component(component):
    result = dict(component)
    if "hashes" in result:
        result["hashes"] = sorted(result["hashes"], key=lambda item: (item["alg"], item["content"]))
    return result


def make_bom(roots, components, edges):
    roots = [_ordered_component(item) for item in roots]
    components = [_ordered_component(item) for item in components]
    if not roots:
        raise ValueError("at least one root component is required")

    if len(roots) == 1:
        metadata_component = roots[0]
        listed = components
        dependency_edges = dict(edges)
    else:
        versions = sorted({item["version"] for item in roots})
        version = versions[0] if len(versions) == 1 else "mixed"
        metadata_component = {
            "type": "application",
            "name": "qmix-go-distribution",
            "version": version,
            "bom-ref": f"pkg:generic/qmix-go-distribution@{urllib.parse.quote(version, safe='')}",
            "licenses": _license("MIT"),
        }
        listed = roots + components
        dependency_edges = dict(edges)
        dependency_edges[metadata_component["bom-ref"]] = [item["bom-ref"] for item in roots]

    refs = {metadata_component["bom-ref"]} | {item["bom-ref"] for item in listed}
    dependencies = []
    for ref in sorted(refs | set(dependency_edges)):
        dependencies.append(
            {"ref": ref, "dependsOn": sorted(set(dependency_edges.get(ref, [])))}
        )

    return {
        "$schema": "https://cyclonedx.org/schema/bom-1.6.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {"component": metadata_component},
        "components": sorted(listed, key=lambda item: item["bom-ref"]),
        "dependencies": dependencies,
    }


def validate_bom(bom):
    if bom.get("bomFormat") != "CycloneDX" or bom.get("specVersion") != "1.6":
        raise ValueError("document is not CycloneDX 1.6")
    if bom.get("version") != 1:
        raise ValueError("CycloneDX document version must be 1")
    encoded = json.dumps(bom)
    if "serialNumber" in bom or '"timestamp"' in encoded:
        raise ValueError("nondeterministic metadata is forbidden")

    all_components = [bom.get("metadata", {}).get("component", {})] + bom.get("components", [])
    refs = []
    for component in all_components:
        if not component.get("type") or not component.get("name"):
            raise ValueError("component is missing type or name")
        ref = component.get("bom-ref")
        if not ref:
            raise ValueError("component is missing bom-ref")
        licenses = component.get("licenses")
        if not licenses or not all(item.get("license", {}).get("id") for item in licenses):
            raise ValueError("component is missing an SPDX license identifier")
        refs.append(ref)
    if len(refs) != len(set(refs)):
        raise ValueError("duplicate bom-ref")
    known = set(refs)

    dependency_refs = []
    for dependency in bom.get("dependencies", []):
        ref = dependency.get("ref")
        dependency_refs.append(ref)
        if ref not in known:
            raise ValueError(f"unknown dependency reference: {ref}")
        for target in dependency.get("dependsOn", []):
            if target not in known:
                raise ValueError(f"unknown dependency reference: {target}")
    if len(dependency_refs) != len(set(dependency_refs)):
        raise ValueError("duplicate dependency ref")
    if set(dependency_refs) != known:
        raise ValueError("dependency graph must mention every component")


def _run(command, cwd=ROOT):
    completed = subprocess.run(
        command, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE
    )
    return completed.stdout


def _json_stream(text):
    decoder = json.JSONDecoder()
    offset = 0
    while offset < len(text):
        while offset < len(text) and text[offset].isspace():
            offset += 1
        if offset == len(text):
            return
        value, offset = decoder.raw_decode(text, offset)
        yield value


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _artifact_component(name, version, path, qualifier=None):
    if not path.is_file():
        raise FileNotFoundError(f"release artifact does not exist: {path}")
    suffix = f"?{qualifier}" if qualifier else ""
    return {
        "type": "application",
        "name": name,
        "version": version,
        "bom-ref": f"pkg:generic/{name}@{urllib.parse.quote(version, safe='')}{suffix}",
        "hashes": [{"alg": "SHA-256", "content": _sha256(path)}],
        "licenses": _license("MIT"),
    }


def _go_ref(path, version):
    escaped_path = urllib.parse.quote(path, safe="/")
    escaped_version = urllib.parse.quote(version, safe="")
    return f"pkg:golang/{escaped_path}@{escaped_version}"


def generate_go(output):
    version = json.loads(
        _run(["go", "run", "./internal/buildinfo/cmd/version", "-format=json"])
    )["version"]
    roots = []
    modules = {}
    root_modules = {}

    for name, target, artifact in GO_TARGETS:
        root = _artifact_component(name, version, artifact, "type=binary")
        roots.append(root)
        used = set()
        listing = _run(["go", "list", "-deps", "-json", target])
        for package in _json_stream(listing):
            module = package.get("Module")
            if not module or module.get("Main"):
                continue
            effective = module.get("Replace") or module
            path = effective["Path"]
            module_version = effective.get("Version") or module.get("Version")
            ref = _go_ref(path, module_version)
            used.add(ref)
            modules[ref] = {
                "type": "library",
                "name": path,
                "version": module_version,
                "bom-ref": ref,
                "purl": ref,
                "licenses": _license(go_license(path)),
            }
        root_modules[root["bom-ref"]] = used

    module_by_vertex = {}
    for ref, component in modules.items():
        module_by_vertex[f'{component["name"]}@{component["version"]}'] = ref
    graph_edges = {ref: set() for ref in modules}
    for line in _run(["go", "mod", "graph"]).splitlines():
        source, target = line.split()
        source_ref = module_by_vertex.get(source)
        target_ref = module_by_vertex.get(target)
        if source_ref and target_ref:
            graph_edges[source_ref].add(target_ref)

    edges = {ref: sorted(values) for ref, values in graph_edges.items()}
    edges.update({ref: sorted(values) for ref, values in root_modules.items()})
    bom = make_bom(roots, list(modules.values()), edges)
    validate_bom(bom)
    _write(output, bom)


_GRADLE_INIT = r'''
import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

gradle.projectsEvaluated {
    rootProject.tasks.register("qmixRuntimeGraph") {
        doLast {
            def configuration = rootProject.project(":app").configurations.getByName("releaseRuntimeClasspath")
            def result = configuration.incoming.resolutionResult
            def components = [] as Set
            def edges = [] as Set
            result.allComponents.each { component ->
                if (component.id instanceof ModuleComponentIdentifier) {
                    components.add([component.id.group, component.id.module, component.id.version])
                }
            }
            result.allDependencies.each { dependency ->
                if (dependency instanceof ResolvedDependencyResult && dependency.selected.id instanceof ModuleComponentIdentifier) {
                    def target = dependency.selected.id
                    def source = dependency.from.id
                    def from = source instanceof ModuleComponentIdentifier ? [source.group, source.module, source.version] : null
                    edges.add([from, [target.group, target.module, target.version]])
                }
            }
            println("QMIX_RUNTIME_GRAPH=" + JsonOutput.toJson([components: components, edges: edges]))
        }
    }
}
'''


def _maven_ref(group, name, version):
    namespace = urllib.parse.quote(group, safe=".")
    package = urllib.parse.quote(name, safe="")
    release = urllib.parse.quote(version, safe="")
    return f"pkg:maven/{namespace}/{package}@{release}"


def _android_graph():
    with tempfile.NamedTemporaryFile("w", suffix=".gradle", encoding="utf-8") as init:
        init.write(_GRADLE_INIT)
        init.flush()
        output = _run(
            [
                "./gradlew",
                "--no-daemon",
                "--dependency-verification=strict",
                "--init-script",
                init.name,
                "-q",
                "qmixRuntimeGraph",
            ],
            cwd=ROOT / "android",
        )
    marker = "QMIX_RUNTIME_GRAPH="
    lines = [line for line in output.splitlines() if line.startswith(marker)]
    if len(lines) != 1:
        raise ValueError("Gradle did not emit exactly one runtime graph")
    return json.loads(lines[0][len(marker) :])


def generate_android(output, apk):
    version = json.loads(
        _run(["go", "run", "./internal/buildinfo/cmd/version", "-format=json"])
    )["version"]
    root = _artifact_component("qmix-android", version, apk, "type=apk")
    graph = _android_graph()
    components = {}
    for group, name, release in graph["components"]:
        ref = _maven_ref(group, name, release)
        components[ref] = {
            "type": "library",
            "group": group,
            "name": name,
            "version": release,
            "bom-ref": ref,
            "purl": ref,
            "licenses": _license(android_license(group, name)),
        }
    edges = {ref: set() for ref in components}
    root_dependencies = set()
    for source, target in graph["edges"]:
        target_ref = _maven_ref(*target)
        if source is None:
            root_dependencies.add(target_ref)
        else:
            source_ref = _maven_ref(*source)
            if source_ref in edges:
                edges[source_ref].add(target_ref)
    edges[root["bom-ref"]] = root_dependencies
    bom = make_bom([root], list(components.values()), edges)
    validate_bom(bom)
    _write(output, bom)


def _write(path, bom):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(serialize(bom), encoding="utf-8")
    print(path.relative_to(ROOT) if path.is_relative_to(ROOT) else path)


def validate_files(paths):
    for path in paths:
        bom = json.loads(path.read_text(encoding="utf-8"))
        validate_bom(bom)
        canonical = serialize(bom)
        if path.read_text(encoding="utf-8") != canonical:
            raise ValueError(f"SBOM is not canonically serialized: {path}")
        print(path)


def main(argv=None):
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    go_parser = subparsers.add_parser("go")
    go_parser.add_argument("--output", type=pathlib.Path, required=True)
    android_parser = subparsers.add_parser("android")
    android_parser.add_argument("--output", type=pathlib.Path, required=True)
    android_parser.add_argument("--apk", type=pathlib.Path, required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("paths", nargs="+", type=pathlib.Path)
    args = parser.parse_args(argv)

    if args.command == "go":
        generate_go(args.output.resolve())
    elif args.command == "android":
        generate_android(args.output.resolve(), args.apk.resolve())
    else:
        validate_files([path.resolve() for path in args.paths])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, FileNotFoundError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
