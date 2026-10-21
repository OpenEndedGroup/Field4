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

import faulthandler
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent

# Fallback only. The real floor is `requires-python` in pyproject.toml, read at runtime by
# declared_python_floor() so this constant cannot drift away from the project's own declaration.
FALLBACK_PYTHON_FLOOR = (3, 12)

# JUnit XML, one file per suite, where the lander already reads Gradle's (`_collect_failures` in
# tools/land.py globs build/test-results/*/TEST-*.xml). Without it a red here reached the lander's
# fixer as "uv exited 1": the traceback was in the log, 54,000 lines above the 200-line tail it got.
RESULTS_DIR = REPO_ROOT / "build" / "test-results" / "pythonTools"

# Per-module wall-clock budget. On 2026-10-21 `tools.test_refactor_pass` sat at 0% CPU for 1 h 53 min
# inside six landing merges at once, and all the log said was its last line of output: nobody could
# tell which lock it was in, and every branch behind it waited. A module past its budget now dumps
# every thread's Python stack into the log, kills the processes it started, and fails by name.
# Measured 2026-10-21 on an otherwise idle machine (the summary prints each module's time): the
# slowest were test_docs_voice_detectors 190 s, test_land 118 s, test_reap_merged_worktrees 79 s; the
# whole run 10 min. Fifteen minutes is ~5x the slowest, room for a landing host running six merges.
MODULE_BUDGET_SECONDS = float(os.environ.get("FIELD_PYTHON_TEST_MODULE_BUDGET", "900"))
# After the stacks are dumped and the children killed, how long the module gets to unwind on its own
# (a test blocked on a child's pipe usually does) before the runner gives up on the process.
UNWIND_GRACE_SECONDS = 30.0


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


def _test_identity(test: unittest.TestCase) -> tuple[str, str]:
    """(classname, name) for a failing test, a failing subTest, or a setUpClass/import holder."""
    case = getattr(test, "test_case", test)  # a _SubTest wraps the real case
    method = getattr(case, "_testMethodName", None)
    if method is None:  # unittest's _ErrorHolder: setUpClass / setUpModule raised
        return type(case).__module__, str(test)
    name = method
    if case is not test:
        name += " " + test._subDescription()  # noqa: SLF001 — the only public form is str(test)
    return f"{type(case).__module__}.{type(case).__qualname__}", name


def _exception_line(trace: str) -> str:
    return next((line.strip() for line in reversed(trace.splitlines()) if line.strip()), "")


def write_junit(module: str, ran: int, failures: list, errors: list, skipped: int) -> None:
    suite = ET.Element("testsuite", name=module, tests=str(ran), failures=str(len(failures)),
                       errors=str(len(errors)), skipped=str(skipped))
    for kind, entries in (("failure", failures), ("error", errors)):
        for test, trace in entries:
            classname, name = _test_identity(test)
            case = ET.SubElement(suite, "testcase", classname=classname, name=name)
            ET.SubElement(case, kind, message=_exception_line(trace)).text = trace
    ET.ElementTree(suite).write(RESULTS_DIR / f"TEST-{module}.xml", encoding="utf-8", xml_declaration=True)


def write_timeout_junit(module: str, budget: float, dump: str) -> None:
    """The bounce reason for a module that hung: its name, its budget, and every thread's stack."""
    suite = ET.Element("testsuite", name=module, tests="1", failures="0", errors="1", skipped="0")
    case = ET.SubElement(suite, "testcase", classname=module, name="module timed out")
    message = f"{module} ran past its {budget:.0f} s budget; all threads' stacks follow"
    ET.SubElement(case, "error", message=message).text = message + "\n\n" + dump
    ET.ElementTree(suite).write(RESULTS_DIR / f"TEST-{module}.xml", encoding="utf-8", xml_declaration=True)


def descendants(pid: int) -> list[int]:
    """Every live process below *pid*, children first-found; from `ps`, so no psutil."""
    ps = subprocess.Popen(["ps", "-Ao", "pid=,ppid="], stdout=subprocess.PIPE, text=True)
    listing, _ = ps.communicate()
    children: dict[int, list[int]] = {}
    for line in listing.splitlines():
        parts = line.split()
        # `ps` lists itself, as a child of the caller.
        if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit() and int(parts[0]) != ps.pid:
            children.setdefault(int(parts[1]), []).append(int(parts[0]))
    found, todo = [], list(children.get(pid, []))
    while todo:
        child = todo.pop()
        found.append(child)
        todo.extend(children.get(child, []))
    return found


def kill_descendants(pid: int) -> list[int]:
    """TERM everything below *pid*, then KILL what is left after five seconds. Returns who was signalled."""
    victims = descendants(pid)
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for victim in victims:
            try:
                os.kill(victim, sig)
            except (ProcessLookupError, PermissionError):
                pass
        if sig == signal.SIGTERM and victims:
            deadline = time.time() + 5
            while time.time() < deadline and any(_alive(v) for v in victims):
                time.sleep(0.2)
    return victims


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except (ProcessLookupError, PermissionError):
        return False
    return True


class ModuleWatchdog:
    """Fails one module that runs past *budget*: stacks to the log, children killed, JUnit by name.

    A thread, because the main thread is the one that is stuck. It cannot interrupt that thread, so
    it does the three things that do not need it to: dump (`faulthandler` reads every thread's frame
    from C, without the GIL-holder's help), kill the children (a test waiting on a hung child usually
    unwinds then), and record the failure. If the module has not come back after
    UNWIND_GRACE_SECONDS, *on_abandon* runs and the process exits, since nothing after it would run.
    """

    def __init__(self, module: str, budget: float, on_abandon, grace: float = UNWIND_GRACE_SECONDS,
                 out=None, exit=os._exit):
        self.module, self.budget, self.grace = module, budget, grace
        self.on_abandon, self.out, self.exit = on_abandon, out or sys.stdout, exit
        self.done = threading.Event()
        self.timed_out = False
        self.dump = ""
        self._thread = threading.Thread(target=self._watch, name=f"watchdog:{module}", daemon=True)

    def __enter__(self) -> ModuleWatchdog:
        self._thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self.done.set()
        self._thread.join(timeout=5)

    def _watch(self) -> None:
        if self.done.wait(self.budget):
            return
        self.timed_out = True
        with tempfile.TemporaryFile("w+") as sink:
            faulthandler.dump_traceback(file=sink, all_threads=True)
            sink.seek(0)
            self.dump = sink.read()
        print(f"\n!!! {self.module} ran past its {self.budget:.0f} s budget; every thread's stack:\n"
              f"{self.dump}", file=self.out, flush=True)
        killed = kill_descendants(os.getpid())
        print(f"!!! {self.module}: signalled {len(killed)} child process(es) {killed}", file=self.out, flush=True)
        write_timeout_junit(self.module, self.budget, self.dump)
        if self.done.wait(self.grace):
            return
        print(f"!!! {self.module} did not unwind {self.grace:.0f} s after its children were killed; "
              "abandoning the remaining suites", file=self.out, flush=True)
        self.on_abandon()
        self.exit(1)


def clear_repository_env(environ=os.environ) -> list[str]:
    """Drop the variables that tell git which repository it is in. Returns the names it dropped.

    This runner is started from inside merges (`merge_regen.py`, the lander), and git exports
    `GIT_DIR` and friends to what a merge runs. Several suites build throwaway repositories with
    plain `git init` / `git -C <tmp> ...`, which honour those variables over `-C`. On 2026-10-21 one
    run with `GIT_DIR` set re-initialised the real repository as bare (`core.bare = true` in the
    shared .git/config), and every worktree and the lander stopped working until it was put back.
    The list is git's own (`git rev-parse --local-env-vars`, what it clears for a submodule).
    """
    listed = subprocess.run(["git", "rev-parse", "--local-env-vars"], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True, check=False).stdout.split()
    names = listed or ["GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                       "GIT_COMMON_DIR", "GIT_PREFIX", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS"]
    return [name for name in names if environ.pop(name, None) is not None]


def main() -> int:
    dropped = clear_repository_env()
    if dropped:
        print(f"run_python_tests: cleared {', '.join(dropped)} so no suite's scratch repository resolves "
              "to this one", flush=True)
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

    shutil.rmtree(RESULTS_DIR, ignore_errors=True)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    loader = unittest.TestLoader()
    rows: list[tuple[str, int, int, int, int]] = []
    not_passing: list[str] = []
    durations: dict[str, float] = {}
    failed = False

    for name in names:
        try:
            suite = loader.loadTestsFromName(name)
        except Exception as exc:  # noqa: BLE001 — any import-time explosion is a real failure
            print(f"\n=== {name}: could not be loaded: {exc!r}", flush=True)
            rows.append((name, 0, 0, 1, 0))
            not_passing.append(f"{name}: could not be loaded: {exc!r}")
            write_junit(name, 0, [], [(unittest.case._ErrorHolder(name),  # noqa: SLF001
                                       f"could not be loaded: {exc!r}")], 0)
            failed = True
            continue

        print(f"\n=== {name}", flush=True)
        started = time.monotonic()

        def abandon(name: str = name) -> None:
            rows.append((name, 0, 0, 1, 0))
            not_passing.append(f"ERROR {name}: ran past its {MODULE_BUDGET_SECONDS:.0f} s budget and never "
                               "unwound (stacks above)")
            summarize(rows, not_passing, durations, failed=True)

        # Everything on one stream: Gradle buffers stdout and stderr separately, so a summary
        # on stdout and unittest on stderr arrive interleaved and unreadable.
        with ModuleWatchdog(name, MODULE_BUDGET_SECONDS, abandon) as watchdog:
            result = unittest.TextTestRunner(stream=sys.stdout, verbosity=1).run(suite)
        durations[name] = time.monotonic() - started
        ran = result.testsRun
        fails = len(result.failures)
        errors = len(result.errors)
        skipped = len(result.skipped)
        if watchdog.timed_out:
            # It came back once its children were killed; what it reported is not its verdict.
            rows.append((name, ran, fails, errors + 1, skipped))
            not_passing.append(f"ERROR {name}: ran past its {MODULE_BUDGET_SECONDS:.0f} s budget "
                               "(stacks above; its child processes were killed)")
            failed = True
            continue
        rows.append((name, ran, fails, errors, skipped))
        write_junit(name, ran, result.failures, result.errors, skipped)
        for kind, entries in (("FAIL", result.failures), ("ERROR", result.errors)):
            for test, trace in entries:
                classname, method = _test_identity(test)
                not_passing.append(f"{kind} {classname}.{method}: {_exception_line(trace)[:300]}")
        if fails or errors or not result.wasSuccessful():
            failed = True

    return summarize(rows, not_passing, durations, failed)


def summarize(rows: list[tuple[str, int, int, int, int]], not_passing: list[str],
              durations: dict[str, float], failed: bool) -> int:
    width = max(len(name) for name, *_ in rows)
    print("\nPython tool test suites:", flush=True)
    for name, ran, fails, errors, skipped in rows:
        passed = ran - fails - errors - skipped
        print(f"  {name.ljust(width)}  {passed:>3} passed / {ran:>3} run"
              + (f", {fails} failed, {errors} errored" if fails or errors else "")
              + (f", {skipped} skipped" if skipped else "")
              + (f"  ({durations[name]:.1f} s)" if name in durations else ""), flush=True)
    total_ran = sum(ran for _, ran, _, _, _ in rows)
    total_bad = sum(fails + errors for _, _, fails, errors, _ in rows)
    total_skipped = sum(skipped for _, _, _, _, skipped in rows)
    total_passed = total_ran - total_bad - total_skipped
    print(f"  {'TOTAL'.ljust(width)}  {total_passed:>3} passed / {total_ran:>3} run"
          + (f", {total_bad} not passing" if total_bad else "")
          + (f", {total_skipped} skipped" if total_skipped else ""), flush=True)

    # Last, so it is what a log tail shows: Gradle keeps running other tasks after this one fails,
    # and the summary above can end up tens of thousands of lines from the end of the log.
    if not_passing:
        print("\nNot passing (tracebacks above, and in " + str(RESULTS_DIR.relative_to(REPO_ROOT)) + "):",
              flush=True)
        for line in not_passing:
            print(f"  {line}", flush=True)
    elif failed:
        print("\nA suite reported failure without a failing test; see its output above.", flush=True)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
