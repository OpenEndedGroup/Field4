"""build.gradle.kts refuses SNAPSHOT and dynamic repository versions (AGENTS.md used to ask in prose).

`./gradlew dependencies` is the real proof -- on 2026-09-28 it resolved every root configuration
with zero FAILED under the rule, once the one Kotlin-plugin-internal range was exempted -- but it
takes a Gradle run. This pins the rule's presence and the shape of its exemptions without one.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

BUILD = Path(__file__).resolve().parent.parent / "build.gradle.kts"


class DependencyVersionRules(unittest.TestCase):
    def setUp(self) -> None:
        self.text = BUILD.read_text(encoding="utf-8")

    def test_both_rules_are_applied_to_configurations(self) -> None:
        block = self.text.split("configurationsExemptFromVersionRules }.configureEach {", 1)
        self.assertEqual(len(block), 2, "the version-rule block moved or was renamed")
        body = block[1].split("\n}\n", 1)[0]
        self.assertIn("failOnDynamicVersions()", body)
        self.assertIn("failOnChangingVersions()", body)

    def test_exemptions_are_exact_names_and_few(self) -> None:
        match = re.search(r"val configurationsExemptFromVersionRules = setOf\(([^)]*)\)", self.text)
        self.assertIsNotNone(match)
        names = re.findall(r'"([^"]+)"', match.group(1))
        self.assertEqual(names, ["kotlinAbiValidationCompatClasspath"])
        for name in names:
            self.assertRegex(name, r"^[A-Za-z]+$", "exemptions are exact configuration names")

    def test_no_repository_dependency_is_declared_as_a_snapshot_or_dynamic_version(self) -> None:
        """Belt and braces for declarations the rule would only catch at resolution time."""
        offenders = [
            line.strip()
            for line in self.text.splitlines()
            if re.search(r'(implementation|api|runtimeOnly|compileOnly)\("[^"]+:[^"]*(SNAPSHOT|\+|latest\.)[^"]*"\)', line)
            and not line.strip().startswith("//")
        ]
        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
