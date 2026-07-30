#!/usr/bin/env python3
"""Run every `tools/test_*.py` unittest suite and report each one's pass count.

These suites pin parts of the automation substrate that nothing else covers — the shared
`run_field_tests.py` backdoor argument emitter, the docs-capture freshness classifier, and the
smoke runner's worktree-scoped JVM reaper — and until 2026-07-30 they were referenced by no
Gradle task, no FIELD_TEST and no CI entrypoint, so they ran only when a human happened to type
`python -m unittest`. `./gradlew checkPythonTools` (wired into `check`) runs this.

Discovery is by glob rather than a hand-maintained list, so a new `tools/test_*.py` is wired in
by existing. A module that cannot even be imported is reported as a failure rather than skipped:
an unimportable test is exactly as broken as a failing one, and quietly dropping it is how a
suite stops covering anything.

Run directly with:  python3 tools/run_python_tests.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent


def suite_module_names() -> list[str]:
    return sorted(f"tools.{path.stem}" for path in TOOLS_ROOT.glob("test_*.py"))


def main() -> int:
    # The suites import each other as `tools.<module>` (a PEP 420 namespace package — there is no
    # tools/__init__.py), so the repo root has to lead sys.path however this script was invoked.
    sys.path.insert(0, str(REPO_ROOT))

    names = suite_module_names()
    if not names:
        print("no tools/test_*.py suites found — that is itself a failure")
        return 1

    loader = unittest.TestLoader()
    rows: list[tuple[str, int, int, int]] = []
    failed = False

    for name in names:
        try:
            suite = loader.loadTestsFromName(name)
        except Exception as exc:  # noqa: BLE001 — any import-time explosion is a real failure
            print(f"\n=== {name}: could not be loaded: {exc!r}", flush=True)
            rows.append((name, 0, 0, 1))
            failed = True
            continue

        print(f"\n=== {name}", flush=True)
        # Everything on one stream: Gradle buffers stdout and stderr separately, so a summary
        # on stdout and unittest on stderr arrive interleaved and unreadable.
        result = unittest.TextTestRunner(stream=sys.stdout, verbosity=1).run(suite)
        ran = result.testsRun
        fails = len(result.failures)
        errors = len(result.errors)
        rows.append((name, ran, fails, errors))
        if fails or errors or not result.wasSuccessful():
            failed = True

    width = max(len(name) for name, *_ in rows)
    print("\nPython tool test suites:", flush=True)
    for name, ran, fails, errors in rows:
        passed = ran - fails - errors
        print(f"  {name.ljust(width)}  {passed:>3} passed / {ran:>3} run"
              + (f", {fails} failed, {errors} errored" if fails or errors else ""), flush=True)
    total_ran = sum(ran for _, ran, _, _ in rows)
    total_bad = sum(fails + errors for _, _, fails, errors in rows)
    print(f"  {'TOTAL'.ljust(width)}  {total_ran - total_bad:>3} passed / {total_ran:>3} run"
          + (f", {total_bad} not passing" if total_bad else ""), flush=True)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
