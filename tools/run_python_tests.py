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

Run directly with:  uv run --project . --group backdoor python tools/run_python_tests.py
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent

# Fallback only. The real floor is `requires-python` in pyproject.toml, read at runtime by
# declared_python_floor() so this constant cannot drift away from the project's own declaration.
FALLBACK_PYTHON_FLOOR = (3, 12)


def declared_python_floor() -> tuple[int, ...]:
    """The `requires-python` floor from pyproject.toml, parsed without tomllib.

    tomllib arrived in 3.11, and the whole point of this check is to run on interpreters older
    than the floor, so the floor has to be readable without it.
    """
    try:
        text = (REPO_ROOT / "pyproject.toml").read_text()
    except OSError:
        return FALLBACK_PYTHON_FLOOR
    match = re.search(r'^requires-python\s*=\s*"[><=~^]*\s*([0-9]+(?:\.[0-9]+)*)"', text, re.M)
    if not match:
        return FALLBACK_PYTHON_FLOOR
    return tuple(int(part) for part in match.group(1).split("."))


def check_interpreter() -> str | None:
    """None if this interpreter can run the suites, else why it cannot.

    These suites are not interpreter-agnostic — they exercise `tools/` modules that use PEP 604
    (`str | None`) unguarded and construct `asyncio.Condition()` outside a running loop, which
    3.9 evaluates differently. Run under an interpreter below the floor, the suites do not report
    a defect in the code under test; they report six unrelated-looking import and setUp errors.
    That is worse than not running: on 2026-08-21 it read, to a nightly CI run, exactly like a
    regression on `main`, because the failing gate shells bare `python3` and PATH decides which
    interpreter that is (`/usr/bin/python3` is 3.9.6 on macOS; a login shell here resolves 3.11+).
    So say so, in one line, instead of erupting.
    """
    floor = declared_python_floor()
    if sys.version_info[: len(floor)] >= floor:
        return None
    running = ".".join(str(part) for part in sys.version_info[:3])
    wanted = ".".join(str(part) for part in floor)
    return (
        f"this suite needs Python >= {wanted} (pyproject.toml requires-python) but is running "
        f"{running} from {sys.executable}.\n"
        "Run it the way every other Python entry point in this repo is run:\n"
        "  uv run --project . --group backdoor python tools/run_python_tests.py"
    )


def suite_module_names() -> list[str]:
    return sorted(f"tools.{path.stem}" for path in TOOLS_ROOT.glob("test_*.py"))


def main() -> int:
    complaint = check_interpreter()
    if complaint is not None:
        print(complaint)
        return 1

    # The suites import each other as `tools.<module>` (a PEP 420 namespace package — there is no
    # tools/__init__.py), so the repo root has to lead sys.path however this script was invoked.
    sys.path.insert(0, str(REPO_ROOT))

    names = suite_module_names()
    if not names:
        print("no tools/test_*.py suites found — that is itself a failure")
        return 1

    loader = unittest.TestLoader()
    rows: list[tuple[str, int, int, int, int]] = []
    failed = False

    for name in names:
        try:
            suite = loader.loadTestsFromName(name)
        except Exception as exc:  # noqa: BLE001 — any import-time explosion is a real failure
            print(f"\n=== {name}: could not be loaded: {exc!r}", flush=True)
            rows.append((name, 0, 0, 1, 0))
            failed = True
            continue

        print(f"\n=== {name}", flush=True)
        # Everything on one stream: Gradle buffers stdout and stderr separately, so a summary
        # on stdout and unittest on stderr arrive interleaved and unreadable.
        result = unittest.TextTestRunner(stream=sys.stdout, verbosity=1).run(suite)
        ran = result.testsRun
        fails = len(result.failures)
        errors = len(result.errors)
        skipped = len(result.skipped)
        rows.append((name, ran, fails, errors, skipped))
        if fails or errors or not result.wasSuccessful():
            failed = True

    width = max(len(name) for name, *_ in rows)
    print("\nPython tool test suites:", flush=True)
    for name, ran, fails, errors, skipped in rows:
        passed = ran - fails - errors - skipped
        print(f"  {name.ljust(width)}  {passed:>3} passed / {ran:>3} run"
              + (f", {fails} failed, {errors} errored" if fails or errors else "")
              + (f", {skipped} skipped" if skipped else ""), flush=True)
    total_ran = sum(ran for _, ran, _, _, _ in rows)
    total_bad = sum(fails + errors for _, _, fails, errors, _ in rows)
    total_skipped = sum(skipped for _, _, _, _, skipped in rows)
    total_passed = total_ran - total_bad - total_skipped
    print(f"  {'TOTAL'.ljust(width)}  {total_passed:>3} passed / {total_ran:>3} run"
          + (f", {total_bad} not passing" if total_bad else "")
          + (f", {total_skipped} skipped" if total_skipped else ""), flush=True)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
