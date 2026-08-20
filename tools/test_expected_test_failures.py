"""Tests for the JVM expected-failure registry's two-sided contract."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.check_expected_test_failures import (  # noqa: E402
    ContractError,
    contract_problems,
    load_registry,
    read_test_results,
)


TEST = "afield.ExampleTest.the behavior is still broken"


class ExpectedFailureContractTests(unittest.TestCase):
    def test_registered_failure_is_the_only_green_expected_failure_state(self) -> None:
        with self.fixture(failed=True) as (registry, results):
            entries = load_registry(registry, root=registry.parent)
            seen, failed = read_test_results(results)
        self.assertEqual(contract_problems(entries, seen=seen, failed=failed), [])

    def test_registered_test_passing_is_a_stale_register_failure(self) -> None:
        with self.fixture(failed=False) as (registry, results):
            entries = load_registry(registry, root=registry.parent)
            seen, failed = read_test_results(results)
        self.assertIn(
            "registered failure passed",
            contract_problems(entries, seen=seen, failed=failed)[0],
        )

    def test_registered_test_not_executing_is_a_stale_register_failure(self) -> None:
        with self.fixture(write_result=False) as (registry, results):
            entries = load_registry(registry, root=registry.parent)
            seen, failed = read_test_results(results)
        self.assertIn(
            "registered failure did not execute",
            contract_problems(entries, seen=seen, failed=failed)[0],
        )

    def test_unregistered_failure_is_rejected(self) -> None:
        problems = contract_problems({}, seen={TEST}, failed={TEST})
        self.assertEqual(problems, [f"unregistered test failed: {TEST}"])

    def test_junit_error_counts_as_a_failure(self) -> None:
        with self.fixture(failed=True, failure_tag="error") as (registry, results):
            _, failed = read_test_results(results)
        self.assertEqual(failed, {TEST})

    def test_kotlin_multiplatform_xml_suffix_is_not_part_of_the_gradle_selector(self) -> None:
        with self.fixture(failed=True, xml_name_suffix="[jvm]") as (_, results):
            seen, failed = read_test_results(results)
        self.assertEqual(seen, {TEST})
        self.assertEqual(failed, {TEST})

    def test_duplicate_selectors_are_rejected(self) -> None:
        with self.fixture(entries=2) as (registry, _):
            with self.assertRaisesRegex(ContractError, "Duplicate expected-failure"):
                load_registry(registry, root=registry.parent)

    def test_wildcard_selectors_are_rejected(self) -> None:
        with self.fixture(test="afield.*") as (registry, _):
            with self.assertRaisesRegex(ContractError, "wildcard"):
                load_registry(registry, root=registry.parent)

    def test_documentation_must_resolve_inside_the_repository(self) -> None:
        with self.fixture(documentation="missing.md") as (registry, _):
            with self.assertRaisesRegex(ContractError, "documentation"):
                load_registry(registry, root=registry.parent)

    def fixture(
        self,
        *,
        failed: bool = True,
        failure_tag: str = "failure",
        write_result: bool = True,
        entries: int = 1,
        test: str = TEST,
        documentation: str = "finding.md#open-defect",
        xml_name_suffix: str = "",
    ):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "finding.md").write_text("# Open defect\n", encoding="utf-8")
        registry = root / "expected.json"
        entry = {
            "test": test,
            "reason": "The production defect is intentionally held open.",
            "documentation": documentation,
        }
        registry.write_text(
            json.dumps({"version": 1, "expectedFailures": [entry] * entries}),
            encoding="utf-8",
        )
        results = root / "results"
        results.mkdir()
        if write_result:
            class_name, _, name = test.rpartition(".")
            failure = f"<{failure_tag} message=\"known\"/>" if failed else ""
            (results / "TEST-example.xml").write_text(
                f'<testsuite><testcase classname="{class_name}" name="{name}{xml_name_suffix}">'
                f"{failure}</testcase></testsuite>",
                encoding="utf-8",
            )

        class Fixture:
            def __enter__(self):
                return registry, results

            def __exit__(self, exc_type, exc, traceback):
                temporary.cleanup()

        return Fixture()


if __name__ == "__main__":
    unittest.main()
