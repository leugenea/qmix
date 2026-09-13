#!/usr/bin/env python3
"""Unit tests for deterministic CycloneDX SBOM construction."""

import importlib.util
import json
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("generate_sbom.py")
SPEC = importlib.util.spec_from_file_location("generate_sbom", MODULE_PATH)
generate_sbom = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generate_sbom)


class GenerateSbomTest(unittest.TestCase):
    def setUp(self):
        self.root = {
            "type": "application",
            "name": "qmix",
            "version": "1.0.0",
            "bom-ref": "pkg:generic/qmix@1.0.0",
            "licenses": [{"license": {"id": "MIT"}}],
        }
        self.library = {
            "type": "library",
            "name": "example.org/library",
            "version": "2.0.0",
            "bom-ref": "pkg:golang/example.org/library@v2.0.0",
            "purl": "pkg:golang/example.org/library@v2.0.0",
            "licenses": [{"license": {"id": "Apache-2.0"}}],
        }

    def test_document_is_cyclonedx_1_6_and_has_no_nondeterministic_fields(self):
        bom = generate_sbom.make_bom(
            [self.root], [self.library], {self.root["bom-ref"]: [self.library["bom-ref"]]}
        )
        self.assertEqual(bom["bomFormat"], "CycloneDX")
        self.assertEqual(bom["specVersion"], "1.6")
        self.assertEqual(bom["version"], 1)
        self.assertNotIn("serialNumber", bom)
        self.assertNotIn("timestamp", json.dumps(bom))
        generate_sbom.validate_bom(bom)

    def test_output_is_stable_when_input_order_changes(self):
        other = {
            "type": "library",
            "name": "example.org/another",
            "version": "1.0.0",
            "bom-ref": "pkg:golang/example.org/another@v1.0.0",
            "licenses": [{"license": {"id": "MIT"}}],
        }
        first = generate_sbom.make_bom(
            [self.root],
            [self.library, other],
            {self.root["bom-ref"]: [self.library["bom-ref"], other["bom-ref"]]},
        )
        second = generate_sbom.make_bom(
            [self.root],
            [other, self.library],
            {self.root["bom-ref"]: [other["bom-ref"], self.library["bom-ref"]]},
        )
        self.assertEqual(generate_sbom.serialize(first), generate_sbom.serialize(second))

    def test_validation_rejects_unknown_dependency_reference(self):
        bom = generate_sbom.make_bom(
            [self.root], [], {self.root["bom-ref"]: ["pkg:generic/missing@1"]}
        )
        with self.assertRaisesRegex(ValueError, "unknown dependency reference"):
            generate_sbom.validate_bom(bom)

    def test_validation_rejects_duplicate_component_reference(self):
        bom = generate_sbom.make_bom([self.root], [self.library, self.library], {})
        with self.assertRaisesRegex(ValueError, "duplicate bom-ref"):
            generate_sbom.validate_bom(bom)

    def test_unknown_runtime_dependency_license_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "unreviewed Go runtime dependency"):
            generate_sbom.go_license("example.invalid/module")
        with self.assertRaisesRegex(ValueError, "unreviewed Android runtime dependency"):
            generate_sbom.android_license("example.invalid", "library")


if __name__ == "__main__":
    unittest.main()
