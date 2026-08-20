#!/usr/bin/env python3
"""Check the expected JVM-test failure register against Gradle JUnit XML."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent


class ContractError(RuntimeError):
    pass


@dataclass(frozen=True)
class ExpectedFailure:
    test: str
    reason: str
    documentation: str


def load_registry(path: Path, *, root: Path) -> dict[str, ExpectedFailure]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(f"Cannot read expected-failure register {path}: {exc}") from exc
    if not isinstance(raw, dict) or raw.get("version") != 1:
        raise ContractError(f"Expected-failure register {path} must have version 1")
    entries = raw.get("expectedFailures")
    if not isinstance(entries, list):
        raise ContractError(f"Expected-failure register {path} needs an expectedFailures array")

    registry: dict[str, ExpectedFailure] = {}
    for index, entry in enumerate(entries, start=1):
        if not isinstance(entry, dict):
            raise ContractError(f"Expected-failure entry {index} must be an object")
        test = _required_string(entry, "test", index)
        reason = _required_string(entry, "reason", index)
        documentation = _required_string(entry, "documentation", index)
        if "*" in test or "?" in test:
            raise ContractError(
                f"Expected-failure entry {index} uses a wildcard test selector; exact tests only"
            )
        if test in registry:
            raise ContractError(f"Duplicate expected-failure test selector: {test}")
        documentation_path = documentation.partition("#")[0]
        candidate = Path(documentation_path)
        if candidate.is_absolute() or ".." in candidate.parts or not (root / candidate).is_file():
            raise ContractError(
                f"Expected-failure entry {index} documentation does not name a repository file: "
                f"{documentation}"
            )
        registry[test] = ExpectedFailure(test, reason, documentation)
    return registry


def _required_string(entry: dict[str, Any], field: str, index: int) -> str:
    value = entry.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ContractError(f"Expected-failure entry {index} needs a non-empty {field}")
    return value.strip()


def read_test_results(results: Path) -> tuple[set[str], set[str]]:
    seen: set[str] = set()
    failed: set[str] = set()
    if not results.is_dir():
        return seen, failed
    for xml_path in sorted(results.glob("TEST-*.xml")):
        try:
            suite = ET.parse(xml_path).getroot()
        except (OSError, ET.ParseError) as exc:
            raise ContractError(f"Cannot read JUnit result {xml_path}: {exc}") from exc
        for case in suite.iter("testcase"):
            class_name = case.get("classname")
            name = case.get("name")
            if not class_name or not name:
                raise ContractError(f"JUnit result {xml_path} has a testcase without class/name")
            # Kotlin Multiplatform's XML display name appends ``[jvm]`` even
            # though Gradle's includeTestsMatching selector uses the source
            # method name. Keep the committed register in Gradle-selector form.
            selector = f"{class_name}.{_gradle_test_name(name)}"
            seen.add(selector)
            if case.find("failure") is not None or case.find("error") is not None:
                failed.add(selector)
    return seen, failed


def _gradle_test_name(xml_name: str) -> str:
    return xml_name.removesuffix("[jvm]")


def contract_problems(
    registry: dict[str, ExpectedFailure], *, seen: set[str], failed: set[str]
) -> list[str]:
    expected = set(registry)
    problems: list[str] = []
    for test in sorted(failed - expected):
        problems.append(f"unregistered test failed: {test}")
    for test in sorted(expected - failed):
        state = "passed" if test in seen else "did not execute"
        entry = registry[test]
        problems.append(
            f"registered failure {state}: {test} ({entry.documentation}; {entry.reason})"
        )
    return problems


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--registry",
        default=str(REPO_ROOT / "dev_docs" / "testing" / "expected_test_failures.json"),
    )
    parser.add_argument("--results", required=True)
    parser.add_argument("--root", default=str(REPO_ROOT))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.root).resolve()
    registry = load_registry(Path(args.registry), root=root)
    seen, failed = read_test_results(Path(args.results))
    problems = contract_problems(registry, seen=seen, failed=failed)
    if problems:
        print("Expected-test-failure contract FAILED:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(
        f"Expected-test-failure contract: {len(registry)} registered failure(s), "
        f"{len(failed)} observed"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
