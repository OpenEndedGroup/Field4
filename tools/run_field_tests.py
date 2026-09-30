#!/usr/bin/env python3
"""Run FIELD_TEST catalog entries through tools/field_backdoor.py."""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

try:
    from .field_python_env import ensure_repo_python
except ImportError:  # Executed as tools/run_field_tests.py rather than imported as a package.
    from field_python_env import ensure_repo_python

ensure_repo_python(("websockets", "PIL"))

from PIL import Image, ImageChops, ImageStat

from completion_sidecar import SIDECAR_URL_ENV, SidecarLease
from discover_field_tests import DEFAULT_ROOTS, discover
from field_backdoor import gradle_command, health_check, make_unverified_ssl_context
from field_languages import needs_language_root
from verification_environment import VerificationEnvironment, VerificationEnvironmentError
from verification_runtime import (
    automation_server_environment,
    automation_workspace,
    field_instance_info,
    field_instance_problem,
    run_with_watchdog,
    select_automation_port,
    terminate_process_tree,
    verification_lock,
    waive_execution_verdict,
)


TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
DEFAULT_CATALOG_PATH = REPO_ROOT / "build" / "field-tests" / "catalog.json"
DEFAULT_RESULTS_PATH = REPO_ROOT / "build" / "field-tests" / "results.json"
DEFAULT_COVERAGE_STATE_PATH = REPO_ROOT / "build" / "field-tests" / "coverage-state.json"
DEFAULT_EXPECTED_FAILURES_PATH = REPO_ROOT / "dev_docs" / "testing" / "expected_smoke_failures.json"
GENERATED_SNIPPETS_ROOT = REPO_ROOT / "build" / "field-tests" / "generated"
CAPTURES_ROOT = REPO_ROOT / "build" / "field-tests" / "captures"
GENERATED_DOC_ASSETS_ROOT = REPO_ROOT / "build" / "field-tests" / "generated-doc-assets"
# Every prewarm Field log this run wrote, in boot order; the last is the one serving now.
SERVER_LOGS: list[str] = []
BACKDOOR_PATH = TOOLS_ROOT / "field_backdoor.py"

PLAYWRIGHT_TIMEOUT_FLOOR = 180.0
DEFAULT_AUTOMATION_PORT = 9477

# The node-backed tests emit `globalThis` (node 12+) and optional chaining (node 14+).
# An older `node` first on PATH makes them fail with a bare SyntaxError/ReferenceError
# from inside a temp .js file, which reads like a code regression and has twice been
# misdiagnosed as one. 14 is the floor; warn once up front instead.
MIN_NODE_MAJOR = 14


class RunnerError(RuntimeError):
    pass


def load_expected_smoke_failures(
    path: Path, *, catalog_ids: set[str], root: Path = REPO_ROOT
) -> dict[str, dict[str, str]]:
    """Load exact smoke ids whose current failure is deliberately accepted."""
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RunnerError(f"Cannot read expected smoke failure registry {path}: {exc}") from exc
    if payload.get("version") != 1 or not isinstance(payload.get("expectedFailures"), list):
        raise RunnerError(f"Expected smoke failure registry {path} must have version 1 and expectedFailures")

    entries: dict[str, dict[str, str]] = {}
    for raw in payload["expectedFailures"]:
        if not isinstance(raw, dict):
            raise RunnerError(f"Expected smoke failure entry must be an object: {raw!r}")
        test_id = str(raw.get("id") or "")
        reason = str(raw.get("reason") or "")
        documentation = str(raw.get("documentation") or "")
        if not test_id or any(token in test_id for token in ("*", "?", "[")):
            raise RunnerError(f"Expected smoke failure id must be exact: {test_id!r}")
        if test_id in entries:
            raise RunnerError(f"Duplicate expected smoke failure id: {test_id}")
        if test_id not in catalog_ids:
            raise RunnerError(f"Registered smoke failure is absent from the catalog: {test_id}")
        if not reason:
            raise RunnerError(f"Expected smoke failure {test_id} has no reason")
        doc_path = (root / documentation.partition("#")[0]).resolve()
        if not documentation or not doc_path.is_relative_to(root.resolve()) or not doc_path.is_file():
            raise RunnerError(f"Expected smoke failure {test_id} has invalid documentation: {documentation}")
        entries[test_id] = {
            "id": test_id,
            "reason": reason,
            "documentation": documentation,
        }
    return entries


def apply_expected_smoke_failure(
    result: dict[str, Any], entries: dict[str, dict[str, str]]
) -> None:
    """Accept only a registered assertion failure; passing or infrastructure remain red."""
    entry = entries.get(str(result.get("id") or ""))
    result["expectedFailure"] = entry
    result["acceptedFailure"] = False
    if entry is None:
        return
    if result.get("ok"):
        result["ok"] = False
        result["failureKind"] = "contract"
        result.setdefault("failures", []).append(
            f"{result['id']}: registered smoke failure passed; remove or update its registry entry"
        )
    elif result.get("failureKind") == "test":
        result["acceptedFailure"] = True


def which_on_path(executable: str) -> str | None:
    """Resolve `executable` exactly as the Kotlin tests' own which() does.

    They scan PATH in order and take the first executable match, so this must
    too — using shutil.which or a login shell would report a different binary
    than the one the tests will actually run.
    """
    for entry in (os.environ.get("PATH") or "").split(os.pathsep):
        if not entry:
            continue
        candidate = Path(entry) / executable
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def warn_if_node_too_old(progress: Any) -> None:
    """Report the resolved `node` up front when it is too old for the JS tests."""
    node = which_on_path("node")
    if node is None:
        return  # tests that need node report their own absence
    try:
        raw = subprocess.run(
            [node, "--version"], capture_output=True, text=True, timeout=15
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return
    match = re.match(r"v?(\d+)", raw)
    if not match or int(match.group(1)) >= MIN_NODE_MAJOR:
        return
    progress(
        f"WARNING: `node` on PATH is {raw} ({node}); the JS-backed tests need "
        f"v{MIN_NODE_MAJOR}+ (globalThis, optional chaining).\n"
        "         Expect rectsl.editor-extension.workout, both remotejs.kotlinjs-*, and\n"
        "         vision.geometry-preview to fail with SyntaxError/ReferenceError from a\n"
        "         temp .js file. These are environment failures, not code regressions.\n"
        "         Put a newer node first on PATH, e.g. `nvm use 22`, and re-run."
    )


def probe_field_responsive(port: int, *, timeout: float = 20.0) -> bool:
    """Functional liveness probe.

    ``health_check`` only proves the HTTP layer answers -- a wedged long-session
    JVM (dead GPU context / stalled event loop) keeps returning ``/health`` "ok"
    while every real backdoor round-trip times out. That stale Field, reused off
    the default port, silently poisons a whole run. So drive ONE real round-trip
    (``snapshot``) with a short budget: responsive == exit 0 and a parseable ok
    result. A hang trips the subprocess timeout and reads as unresponsive.
    """
    cmd = [
        sys.executable, str(BACKDOOR_PATH), "--json",
        "--port", str(port), "--timeout", str(timeout),
        "--command", "snapshot",
    ]
    try:
        proc = subprocess.run(
            cmd, cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=timeout + 15.0,
        )
    except (subprocess.TimeoutExpired, OSError):
        return False
    if proc.returncode != 0 or not proc.stdout.strip():
        return False
    try:
        results = json_stream_loads(proc.stdout, test_id="probe")
    except RunnerError:
        return False
    return bool(results) and bool(results[-1].get("ok"))


def _pids_listening_on(port: int) -> list[int]:
    try:
        out = subprocess.run(
            ["lsof", f"-tiTCP:{port}", "-sTCP:LISTEN"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except (OSError, subprocess.TimeoutExpired):
        return []
    pids: list[int] = []
    for token in out.split():
        try:
            pids.append(int(token))
        except ValueError:
            pass
    return pids


def recycle_field(port: int, ctx: Any, *, grace: float = 12.0) -> None:
    """Retire a Field already serving the port so we can boot a fresh one.

    SIGINT (kill -2) first so it runs its shutdown hooks and disposes GPU/window
    resources (a bare SIGKILL leaks those); wait for the port to free; escalate to
    SIGKILL only if it won't go. Raise rather than reuse or double-bind the port.
    """
    if os.name == "nt":
        raise SystemExit(
            f"Field on port {port} must be recycled for a clean boot, but automatic "
            f"recycle is not implemented on Windows; kill it manually and re-run."
        )
    pids = _pids_listening_on(port)
    for pid in pids:
        try:
            os.kill(pid, signal.SIGINT)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if not health_check(port, ctx):
            return
        time.sleep(0.5)
    for pid in _pids_listening_on(port):
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if not health_check(port, ctx):
            return
        time.sleep(0.5)
    raise SystemExit(f"Could not free wedged Field on port {port} (SIGINT and SIGKILL both failed).")


# Command-line markers identifying a JVM this repo launched: the Field server
# (build.gradle.kts `runJvm` -> mainClass afield.Server), the jvm-main test
# launcher and the completion Analysis API sidecar. Matching the main class
# rather than the java binary is what keeps the reaper off Gradle/Kotlin daemons,
# IDEs and unrelated JVMs.
#
# These say "this is a Field JVM". They do NOT say "this is OUR Field JVM" -- see
# [_command_belongs_to] for that half, which is what keeps parallel agents in sibling
# worktrees from SIGKILLing each other's tests.
FIELD_JVM_MARKERS = (
    "afield.Server",
    "afield.AgentJvmMainLauncher",
    "afield.tools.codeuse.CodeUseSemanticSidecarKt",
)


def _checkout_scope_prefix(root: Path) -> str:
    """``root`` with exactly one trailing separator.

    The separator is the whole point. ``/Users/marc/field4`` is a plain string prefix of
    ``/Users/marc/field4-worktrees/2026-07-30-2-.../`` -- so a bare ``startswith`` run from
    the primary checkout claims every worktree's JVM as its own, which is the bug this
    guards. ``/Users/marc/field4/`` is a prefix of neither.
    """
    return str(root).rstrip(os.sep) + os.sep


def _checkout_build_prefixes(root: Path) -> tuple[str, str]:
    """The two build-output roots that identify a checkout: its own, and the nested sidecar's.

    The semantic sidecar is a separate Gradle build
    (``tools/code-use-semantic-sidecar/settings.gradle.kts``), so its classes live under its own
    ``build/`` directory rather than the root build's.
    """
    prefix = _checkout_scope_prefix(root)
    return (
        prefix + "build" + os.sep,
        prefix + "tools" + os.sep + "code-use-semantic-sidecar" + os.sep + "build" + os.sep,
    )


@dataclass(frozen=True)
class Attribution:
    """Why a Field JVM was, or was not, attributed to a checkout.

    The reaper used to answer this question with a bare ``bool``. On 2026-09-12 at 02:2x it
    SIGKILLed pid 654 -- a live Field 16 hours old holding 2.4 GB, whose ``lsof`` cwd was
    ``/Users/marc/field4/.claude/worktrees/serene-panini-93b3ca``, i.e. another session's nested
    checkout -- and the attribution signal could not be re-derived afterwards, because the
    process was gone and the tool had never printed its reason. A kill decision that cannot be
    audited after the fact is the defect; this record is the fix.

    ``signal`` names the rule that fired -- ``aot-cache-path``, ``classpath-entry``,
    ``unattributed`` or ``foreign-cwd`` -- and ``detail`` carries the value it fired on, so the
    evidence survives the process.
    """

    ours: bool
    signal: str
    detail: str

    @property
    def reason(self) -> str:
        return f"{self.signal}: {self.detail}" if self.detail else self.signal


def _classpath_entries(command: str) -> list[str]:
    """Every ``-classpath``/``-cp`` entry on a command line, in order.

    Tokenising with ``split()`` is good enough here and not more: ``ps`` prints argument values
    unquoted (``-Dafield.aot.detail=No AOT cache was requested. ...`` really does arrive as
    several "arguments"), so this would mis-pair a ``-cp`` whose *value* contained a space.
    Field's own paths do not.
    """
    args = command.split()
    entries: list[str] = []
    for index, arg in enumerate(args):
        if arg in {"-classpath", "-cp", "--class-path"}:
            classpath = args[index + 1] if index + 1 < len(args) else ""
        elif arg.startswith(("-classpath=", "-cp=", "--class-path=")):
            classpath = arg.partition("=")[2]
        else:
            continue
        entries.extend(entry for entry in classpath.split(os.pathsep) if entry)
    return entries


def _aot_cache_path(command: str) -> str | None:
    """The value of ``-Dafield.aot.cache.path=``, or None when absent or empty.

    ``partition`` rather than ``split()``: ``-PfieldAotCache=false`` emits the property with an
    empty value, and splitting the remainder would silently return the *next* argument as this
    one's value.
    """
    marker = "-Dafield.aot.cache.path="
    index = command.find(marker)
    if index < 0:
        return None
    return command[index + len(marker) :].partition(" ")[0] or None


def _foreign_checkout_hint(command: str) -> str:
    """What checkout this command *looks* like it came from, for the sparing message.

    Best effort and advisory only -- nothing is killed on the strength of it. Its job is to make
    a misattribution legible in the log rather than leave "why is that JVM still there" to be
    guessed at: a spared pid whose hint reads
    ``/Users/marc/field4/.claude/worktrees/serene-panini-93b3ca`` explains itself.
    """
    candidates = [path for path in (_aot_cache_path(command),) if path]
    candidates.extend(_classpath_entries(command))
    sidecar_suffix = os.sep + os.path.join("tools", "code-use-semantic-sidecar")
    for candidate in candidates:
        marker = os.sep + "build" + os.sep
        if marker in candidate:
            root = candidate[: candidate.index(marker)]
            # The sidecar is a nested Gradle build, so its build directory sits one level in;
            # report the checkout rather than the subproject.
            return f"looks like {root.removesuffix(sidecar_suffix)}"
    return "no argument names a checkout build directory"


def _attribute_command(command: str, root: Path) -> Attribution:
    """Is this ``java`` command line a JVM launched out of the checkout at ``root``, and why?

    Two independent signals, both anchored at the start of an argument rather than matched
    loosely against the whole line, and both anchored at ``<root>/build/`` rather than
    ``<root>/``:

    * ``-Dafield.aot.cache.path=<root>/build/...`` -- ``build.gradle.kts``'s
      ``fieldAotStatusJvmArgs`` puts this on the ``runJvm`` server, defaulting to
      ``<root>/build/aot/field.aot``. It is the cheapest and most direct answer.
    * a ``-classpath``/``-cp`` entry under ``<root>/build/`` or the semantic sidecar's
      ``<root>/tools/code-use-semantic-sidecar/build/``. Not a theoretical fallback: an
      observed ``afield.AgentJvmMainLauncher`` running
      ``afield.examples.DemoTranscriptExecSmokeTest`` carried **no**
      ``-Dafield.aot.cache.path`` argument at all, and its classpath was the only thing naming
      its checkout. The property is also emitted *empty* when ``-PfieldAotCache=false``
      resolves the cache file to null.

    The ``build/`` anchor is what separates a **nested** checkout. ``_checkout_scope_prefix``'s
    trailing separator separates siblings (``/Users/marc/field4/`` is not a prefix of
    ``/Users/marc/field4-worktrees/...``) but cannot separate children: a checkout at
    ``<primary>/.claude/worktrees/<name>/`` is a path *under* ``<primary>/``, so the old
    substring test ``"-Dafield.aot.cache.path=/Users/marc/field4/" in command`` claimed it. That
    is the pid-654 kill. The classpath signal was already anchored this way, for the same reason
    its docstring gave: a checkout's build output is unambiguous, whereas ``<root>/`` also
    matches incidental paths such as a worktree's ``<primary>/.git/worktrees/<name>`` gitdir.

    The cost of the tighter anchor is that a cache relocated outside the build directory
    (``-PfieldAotCache=/somewhere/else``) no longer contributes signal 1. That fails safe: the
    classpath signal still fires for a Gradle-launched JVM, and an unattributable Field JVM is
    spared. Failing to reap someone else's orphan costs memory; reaping it destroys their run
    and reports a false green.
    """
    build_prefixes = _checkout_build_prefixes(root)

    cache_path = _aot_cache_path(command)
    if cache_path is not None and cache_path.startswith(build_prefixes[0]):
        return Attribution(True, "aot-cache-path", cache_path)

    for entry in _classpath_entries(command):
        for build_prefix in build_prefixes:
            if entry.startswith(build_prefix):
                return Attribution(True, "classpath-entry", entry)

    return Attribution(False, "unattributed", _foreign_checkout_hint(command))


def _command_belongs_to(command: str, root: Path) -> bool:
    """Boolean form of [_attribute_command], kept for callers that only need the verdict."""
    return _attribute_command(command, root).ours


def _describe_pid(pid: int) -> str:
    try:
        out = subprocess.run(
            ["ps", "-o", "etime=,rss=", "-p", str(pid)],
            capture_output=True, text=True, timeout=5,
        ).stdout.split()
    except (OSError, subprocess.TimeoutExpired):
        return f"pid {pid}"
    if len(out) < 2:
        return f"pid {pid}"
    try:
        rss_mb = int(out[1]) // 1024
    except ValueError:
        return f"pid {pid} (age {out[0]})"
    return f"pid {pid} (age {out[0]}, rss {rss_mb}M)"


def _ancestor_pids() -> set[int]:
    """Our own process chain, which must never be reaped."""
    chain: set[int] = set()
    pid = os.getpid()
    for _ in range(32):
        chain.add(pid)
        try:
            out = subprocess.run(
                ["ps", "-o", "ppid=", "-p", str(pid)], capture_output=True, text=True, timeout=5,
            ).stdout.strip()
            parent = int(out)
        except (OSError, subprocess.TimeoutExpired, ValueError):
            break
        if parent <= 1 or parent in chain:
            break
        pid = parent
    return chain


def _pid_cwd(pid: int) -> str | None:
    """A process's working directory, or None when it cannot be read.

    ``lsof -a -p <pid> -d cwd -Fn`` prints one ``n<path>`` line. Anything unexpected returns
    None, and every caller treats None as "no opinion" rather than as evidence.
    """
    try:
        out = subprocess.run(
            ["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"],
            capture_output=True, text=True, timeout=5,
        ).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in out.splitlines():
        if line.startswith("n") and len(line) > 1:
            return line[1:]
    return None


def _nearest_checkout(path: str) -> Path | None:
    """The innermost directory at or above ``path`` holding a ``.git`` entry.

    A git worktree's ``.git`` is a file rather than a directory, so ``exists()`` and not
    ``is_dir()``. None when nothing on the way up looks like a checkout.
    """
    try:
        current = Path(path).resolve()
    except (OSError, ValueError):
        return None
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return None


def _registered_submodules(root: Path) -> set[Path]:
    """Absolute paths of the submodules ``root`` declares in its own ``.gitmodules``.

    The discriminator for the one false positive `_nearest_checkout` can produce. A
    submodule carries its own ``.git`` -- a file reading `gitdir: ../../.git/modules/...`
    -- so walking up from a cwd inside one stops there rather than at ``root``, and a JVM
    of OURS working in a submodule is demoted to `foreign-cwd` and then leaked forever,
    since the reaper spares what it does not claim.

    Only `.gitmodules` separates that from the case the walk exists for. A nested
    independent checkout (`<root>/.claude/worktrees/<name>/`, the shape that got pid 654
    SIGKILLed) also has a `.git` *file*, also resolves to a strict descendant of ``root``,
    and also points back into `<root>/.git` -- so neither the file-versus-directory shape
    nor the common git dir tells them apart. `.gitmodules` is the superproject saying, in
    tracked content, "this path is part of me".

    Measured population in this repository: **1** registered submodule
    (`services/field-audio-ai`, checked out in the primary tree and absent from a fresh
    worktree), against **20+** unregistered nested checkouts under the primary's
    `.claude/worktrees/`, every one of which must keep objecting. **0** Field JVMs were
    observed with a cwd inside the submodule on 2026-09-14, so this closes a latent hole
    rather than a bleeding one.

    Parsed rather than shelled out to: `git submodule status` wants a working tree and a
    subprocess per call, and this runs inside a classifier that is otherwise pure.
    """
    try:
        text = (root / ".gitmodules").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return set()
    paths: set[Path] = set()
    for line in text.splitlines():
        key, sep, value = line.partition("=")
        if not sep or key.strip() != "path":
            continue
        relative = value.strip()
        if not relative or os.path.isabs(relative):
            continue
        try:
            paths.add((root / relative).resolve())
        except OSError:
            continue
    return paths


def _cwd_disagrees(pid: int, root: Path, cwd_lookup: Callable[[int], str | None]) -> Attribution | None:
    """A second, independent opinion that can only ever SPARE a JVM.

    The command line says which checkout's *bytecode* a JVM loaded; its cwd says which checkout
    it is *working in*. They normally agree. Where they can disagree is exactly the case that
    killed pid 654: a checkout nested inside this one. If a future relocation of the AOT cache
    or the build directory re-opens that hole, this catches it without anyone having to notice.

    Conservative by construction, and that is the whole design: it never promotes a JVM to
    ours, only demotes it to foreign, and an unreadable cwd or an unrecognisable checkout
    yields None (no opinion) rather than a verdict. An agent may not have permission to
    ``lsof`` another user's process, and ``lsof`` may not exist at all.

    Deliberately NOT an age check. A 16-hour-old Field looks nothing like "orphaned by an
    earlier run", but nothing here knows when this checkout's earlier runs happened, so an age
    threshold would be a number with no referent -- and the honest fix for the message's claim
    is to state the evidence instead, which [Attribution] now does.
    """
    cwd = cwd_lookup(pid)
    if cwd is None:
        return None
    checkout = _nearest_checkout(cwd)
    if checkout is None:
        return None
    try:
        resolved_root = root.resolve()
    except OSError:
        return None
    if checkout == resolved_root:
        return None
    # A submodule of ours is ours. Without this the walk stops at the submodule's own
    # `.git`, the comparison above fails, and one of our own JVMs working in
    # `services/field-audio-ai` is demoted to foreign and leaked forever. Registered
    # submodules only -- an unregistered nested checkout is the pid-654 case and must
    # keep objecting. See `_registered_submodules`.
    if checkout in _registered_submodules(resolved_root):
        return None
    return Attribution(False, "foreign-cwd", f"{cwd} is inside checkout {checkout}")


def classify_field_jvm_lines(
    lines: list[tuple[int, str]],
    *,
    root: Path,
    protected: set[int],
    cwd_lookup: Callable[[int], str | None] | None = None,
) -> tuple[list[tuple[int, Attribution]], list[tuple[int, Attribution]]]:
    """Split ``(pid, command)`` pairs into ``(ours, other checkouts')`` with a reason each.

    Pure apart from ``cwd_lookup``, so the classification can be tested against real ``ps``
    output without a process table.
    """
    ours: list[tuple[int, Attribution]] = []
    foreign: list[tuple[int, Attribution]] = []
    for pid, command in lines:
        if pid in protected:
            continue
        argv0, _, rest = command.partition(" ")
        # argv[0] must BE a java binary. Without this the marker also matches any
        # shell, grep or editor whose command line merely mentions the class name --
        # which in testing reaped the very shell running the reaper.
        if os.path.basename(argv0) != "java":
            continue
        if not any(marker in rest for marker in FIELD_JVM_MARKERS):
            continue
        attribution = _attribute_command(command, root)
        if attribution.ours and cwd_lookup is not None:
            objection = _cwd_disagrees(pid, root, cwd_lookup)
            if objection is not None:
                foreign.append((pid, objection))
                continue
        (ours if attribution.ours else foreign).append((pid, attribution))
    return ours, foreign


def _classify_field_jvms(
    *, exclude: set[int], root: Path = REPO_ROOT, check_cwd: bool = True
) -> tuple[list[tuple[int, Attribution]], list[tuple[int, Attribution]]]:
    """Every live Field JVM, split into ``(ours, other checkouts')``, each with its reason.

    ``ours`` is what the reaper may kill; the rest belong to another clone or git worktree --
    very possibly another agent's run in progress -- and are only ever reported.
    """
    try:
        out = subprocess.run(
            ["ps", "-eo", "pid=,command="], capture_output=True, text=True, timeout=15,
        ).stdout
    except (OSError, subprocess.TimeoutExpired):
        return [], []
    lines: list[tuple[int, str]] = []
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        head, _, command = line.partition(" ")
        try:
            lines.append((int(head), command))
        except ValueError:
            continue
    return classify_field_jvm_lines(
        lines,
        root=root,
        protected=exclude | _ancestor_pids(),
        cwd_lookup=_pid_cwd if check_cwd else None,
    )


def _orphaned_nonserving_pids(candidates: set[int]) -> set[int]:
    """Checkout identity alone cannot authorize killing a sibling live session.

    Retire only adopted JVMs (parent 1) that have no TCP listener. In particular,
    preserve a same-checkout server on another port and a child smoke still owned
    by its server/launcher. Missing process evidence fails closed.
    """
    if not candidates:
        return set()
    try:
        parents = subprocess.run(["ps", "-axo", "pid=,ppid="], capture_output=True,
                                 text=True, timeout=10)
        sockets = subprocess.run(["lsof", "-nP", "-tiTCP", "-sTCP:LISTEN"],
                                 capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return set()
    if parents.returncode != 0 or sockets.returncode not in (0, 1):
        return set()
    # lsof uses exit 1 for no matches, but diagnostics mean the census is incomplete.
    if sockets.stderr.strip():
        return set()
    adopted = set()
    for line in parents.stdout.splitlines():
        words = line.split()
        if len(words) == 2 and all(w.isdigit() for w in words) and words[1] == "1":
            adopted.add(int(words[0]))
    listening = {int(word) for word in sockets.stdout.split() if word.isdigit()}
    return candidates & adopted - listening


def _stale_field_jvm_pids(*, exclude: set[int]) -> list[int]:
    mine = {pid for pid, _ in _classify_field_jvms(exclude=exclude)[0]}
    return sorted(_orphaned_nonserving_pids(mine))


def _describe_candidate(pid: int, attribution: Attribution) -> str:
    """A pid with its age, memory, and the evidence that classified it.

    The evidence is the point. Printing age and rss alone made a misattribution
    unreconstructible the moment the process died.
    """
    return f"{_describe_pid(pid)} [{attribution.reason}]"


def reap_stale_field_jvms(port: int, *, grace: float = 8.0, dry_run: bool = False) -> None:
    """Kill *this checkout's* Field JVMs orphaned by earlier runs, before booting ours.

    ``recycle_field`` only ever retires the process *listening on our port*. JVMs
    orphaned by a crashed or killed earlier run keep running without holding the
    port, and a pile of them starves the machine: runs then fail with dropped
    backdoor websockets and inflated durations rather than assertion failures.
    Sweep only the current checkout before starting the gate.

    Admit candidates only when all of these checks agree:

    * only JVMs whose command line carries one of [FIELD_JVM_MARKERS]. Gradle and Kotlin
      compile daemons are left alone: they are frequently the larger memory consumers, but
      they are also reusable and killing them makes every run pay a cold start. Use
      ``./gradlew --stop`` only after diagnosing a Gradle-daemon problem.
    * only JVMs [_attribute_command] assigns to this checkout. The marker test alone matches
      every Field JVM on the machine, so parallel agents working in sibling git worktrees used
      to SIGKILL each other's tests -- the victim's run then died mid-suite, or worse finished
      against a half-dead server and reported a green. ``--no-reap`` never helped, because it
      protects everyone else from you and nobody from them.

      The regression evidence was an in-flight server exiting with SIGINT while a sibling
      checkout started its unscoped preflight sweep. Checkout attribution prevents that
      ownership violation; a later pass is not used to excuse the interrupted one.

    * only JVMs whose cwd does not name a *different* checkout ([_cwd_disagrees]). A second,
      spare-only opinion for the case the command line got wrong on 2026-09-12: a checkout
      nested inside this one.

    * only JVMs adopted by init (parent 1) with no TCP listener. Same-checkout live
      sessions and owned child tests are not stale merely because their port differs.

    Every pid printed on either line carries the evidence that classified it
    ([_describe_candidate]), because a kill decision nobody can reconstruct after the process
    is dead is how the 2026-09-12 misattribution became unfalsifiable.

    Best effort: never fails the run. Pass ``dry_run`` (``--reap-dry-run``) to print the
    classification and kill nothing.
    """
    if os.name == "nt":
        return

    # Leave the port holder to recycle_field, which SIGINTs it first so it can
    # dispose its GPU/window resources instead of leaking them.
    keep = set(_pids_listening_on(port))
    mine, foreign = _classify_field_jvms(exclude=keep)
    orphaned = _orphaned_nonserving_pids({pid for pid, _ in mine})
    live = [(pid, why) for pid, why in mine if pid not in orphaned]
    if live:
        print("Leaving owned, serving, or unverified Field JVM(s) in this checkout alone: "
              + ", ".join(_describe_candidate(pid, why) for pid, why in live),
              file=sys.stderr, flush=True)
    mine = [(pid, why) for pid, why in mine if pid in orphaned]
    pids = [pid for pid, _ in mine]

    # Always say what was spared, so a run's log shows the scoping working rather than
    # leaving "why is that JVM still there" to be guessed at.
    if foreign:
        print(
            f"Leaving {len(foreign)} Field JVM(s) from other checkouts alone: "
            + ", ".join(_describe_candidate(pid, why) for pid, why in foreign),
            file=sys.stderr, flush=True,
        )
    if not pids:
        if dry_run:
            print(
                f"  --reap-dry-run: no unowned, non-serving Field JVM in {REPO_ROOT}; nothing to reap.",
                file=sys.stderr, flush=True,
            )
        return

    # "attributed to" rather than "orphaned by earlier runs": attribution from a command line is
    # what this actually knows, and the stronger claim is what made a 16-hour-old live Field
    # from a nested checkout read as this run's own litter. Each pid carries the evidence.
    print(
        f"Reaping {len(pids)} non-serving Field JVM(s) adopted by init and attributed to {REPO_ROOT} after "
        "earlier runs: " + ", ".join(_describe_candidate(pid, why) for pid, why in mine),
        file=sys.stderr, flush=True,
    )
    if dry_run:
        print("  --reap-dry-run: not killing anything.", file=sys.stderr, flush=True)
        return
    for pid in pids:
        try:
            os.kill(pid, signal.SIGINT)
        except (ProcessLookupError, PermissionError):
            pass

    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if not _stale_field_jvm_pids(exclude=keep):
            return
        time.sleep(0.5)

    survivors = _stale_field_jvm_pids(exclude=keep)
    for pid in survivors:
        try:
            os.kill(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
    if survivors:
        print(
            f"  escalated to SIGKILL for {len(survivors)} JVM(s) that ignored SIGINT",
            file=sys.stderr, flush=True,
        )


def ensure_persistent_field(
    port: int,
    *,
    workspace: str | None = None,
    boot_timeout: float = 180.0,
    check_environment: Any = None,
    sidecar_url: str | None = None,
) -> subprocess.Popen[Any] | None:
    """Guarantee one warm, *responsive* Field for the whole run.

    Historically the first test's backdoor process cold-booted the JVM and then
    KILLED it on close (a self-started server is torn down at exit), so every
    subsequent test cold-booted again -- ~16s of JVM boot per test. Starting one
    persistent Field up front means each test's backdoor reuses it (within THIS
    run) and does not tear it down. Returns the process we started.

    We never reuse a Field left over from a PRIOR run. Cross-run reuse is unsafe:
    even when the snapshot probe reports the event loop healthy, a reused JVM's
    GPU/raytracing pipeline wedges -- heavy renders then hang to their full timeout,
    silently corrupting the run (this is the failure the nightly kept hitting). So
    if any Field is already on the port we probe it (for telemetry only) and then
    recycle it (SIGINT + fresh boot), guaranteeing a clean GPU context every run.
    """
    ctx = make_unverified_ssl_context()
    if health_check(port, ctx):
        instance = field_instance_info(port, ctx)
        problem = field_instance_problem(instance, REPO_ROOT)
        if problem is not None:
            raise SystemExit(
                f"Refusing to recycle Field on port {port}: {problem}. "
                "Choose a fresh --port or stop that server explicitly."
            )
        state = "responsive" if probe_field_responsive(port, timeout=8.0) else "wedged (failed snapshot probe)"
        print(
            f"Field already serving port {port} ({state}); recycling it for a clean boot "
            f"(SIGINT + restart) -- never reusing a cross-run JVM.",
            file=sys.stderr, flush=True,
        )
        recycle_field(port, ctx)
    # One file per boot, never reopened: this is the only record of what box-runner tests printed
    # (their code runs in this JVM), and a run recycles Field after every graphics test. Reopening
    # one per-port file with "w" erased each boot's output with the next, and a later run's erased
    # the whole previous run's.
    boot_stamp = time.strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}-{len(SERVER_LOGS)}"
    log_path = REPO_ROOT / ".field-backdoor" / "logs" / f"smoke-prewarm-field-{port}-{boot_stamp}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    SERVER_LOGS.append(str(log_path))
    GENERATED_DOC_ASSETS_ROOT.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("w", encoding="utf-8")
    resolved_workspace = workspace or str(automation_workspace(REPO_ROOT))
    command = gradle_command("runJvm")
    command.append(f"--args=--workspace {shlex.quote(resolved_workspace)}")
    popen_kwargs: dict[str, Any] = {
        "cwd": str(REPO_ROOT),
        "stdout": log_file,
        "stderr": subprocess.STDOUT,
        "stdin": subprocess.DEVNULL,
        "text": True,
        "env": automation_server_environment(
            port,
            extra={
            # Gallery smoke tests must never rewrite tracked docs captures. The
            # production docs runner leaves this unset and continues to own
            # public/docs/assets.
                "AFIELD_GENERATED_DOC_ASSET_ROOT": str(GENERATED_DOC_ASSETS_ROOT),
            # As field.sh does for a user: Field completes with the AA sidecar, and a
            # jvm-main test (a subprocess of this JVM) inherits the same URL.
                **({SIDECAR_URL_ENV: sidecar_url} if sidecar_url else {}),
            },
        ),
    }
    if os.name == "nt":
        popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
    else:
        popen_kwargs["start_new_session"] = True
    proc = subprocess.Popen(command, **popen_kwargs)
    deadline = time.monotonic() + boot_timeout
    while time.monotonic() < deadline:
        if check_environment is not None:
            try:
                check_environment()
            except BaseException:
                stop_persistent_field(proc, port=port)
                raise
        if proc.poll() is not None:
            raise SystemExit(f"Prewarm Field exited early (code {proc.returncode}); see {log_path}")
        if health_check(port, ctx):
            return proc
        time.sleep(0.5)
    terminate_process_tree(proc)
    if health_check(port, ctx):
        recycle_field(port, ctx)
    raise SystemExit(f"Prewarm Field did not become healthy on port {port} within {boot_timeout:.0f}s; see {log_path}")


def stop_persistent_field(proc: subprocess.Popen[Any] | None, *, port: int) -> None:
    if proc is None:
        return  # Preflight refusal / --no-prewarm never grants ownership of a listener.
    if proc is not None and proc.poll() is None:
        terminate_process_tree(proc, grace=10.0)
    ctx = make_unverified_ssl_context()
    if health_check(port, ctx):
        recycle_field(port, ctx)


def load_or_refresh_catalog(
    *,
    catalog_path: Path,
    refresh_catalog: bool,
    roots: list[str] | None,
) -> dict[str, Any]:
    if refresh_catalog or not catalog_path.exists():
        catalog = discover(REPO_ROOT, roots or DEFAULT_ROOTS)
        catalog_path.parent.mkdir(parents=True, exist_ok=True)
        catalog_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        return catalog
    return json.loads(catalog_path.read_text(encoding="utf-8"))


def filter_tests(
    tests: list[dict[str, Any]],
    *,
    ids: set[str],
    tags: set[str],
    runner_names: set[str],
) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    for test in tests:
        test_id = str(test.get("id") or "")
        if ids and test_id not in ids:
            continue
        test_tags = {str(tag) for tag in test.get("tags") or []}
        if tags and not tags.issubset(test_tags):
            continue
        runner = str(test.get("runner") or "")
        if runner_names and runner not in runner_names:
            continue
        selected.append(test)
    return selected


def load_coverage_state(path: Path) -> dict[str, Any]:
    """Load the build-local smoke coverage ledger.

    The ledger is deliberately separate from ``results.json``: an interrupted run
    never writes its final report, but each completed test still needs to influence
    the next run's order. Refuse corrupt state instead of silently returning to the
    catalog prefix and recreating the coverage blind spot this file closes.
    """
    if not path.exists():
        return {"version": 1, "tests": {}}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RunnerError(f"Cannot read smoke coverage state {path}: {exc}") from exc
    if not isinstance(raw, dict) or raw.get("version") != 1:
        raise RunnerError(f"Unsupported smoke coverage state in {path}; expected version 1")
    records = raw.get("tests")
    if not isinstance(records, dict):
        raise RunnerError(f"Invalid smoke coverage state in {path}: 'tests' must be an object")
    for test_id, record in records.items():
        completed_at = record.get("completedAt") if isinstance(record, dict) else None
        if not isinstance(test_id, str) or not isinstance(completed_at, (int, float)):
            raise RunnerError(
                f"Invalid smoke coverage record in {path}: each test needs numeric completedAt"
            )
    return raw


def order_tests_by_coverage(
    tests: list[dict[str, Any]], state: dict[str, Any]
) -> list[dict[str, Any]]:
    """Put unseen tests first, then oldest completed, preserving catalog ties."""
    records = state.get("tests") or {}

    def coverage_key(item: tuple[int, dict[str, Any]]) -> tuple[int, float, int]:
        index, test = item
        record = records.get(str(test.get("id") or ""))
        if not isinstance(record, dict):
            return (0, 0.0, index)
        return (1, float(record["completedAt"]), index)

    return [test for _, test in sorted(enumerate(tests), key=coverage_key)]


def coverage_snapshot(
    tests: list[dict[str, Any]], state: dict[str, Any], *, now: float
) -> dict[str, Any]:
    records = state.get("tests") or {}
    timestamps = [
        float(records[test_id]["completedAt"])
        for test in tests
        if (test_id := str(test.get("id") or "")) in records
    ]
    return {
        "unseen": len(tests) - len(timestamps),
        "oldestCompletedAgeSec": max(0.0, now - min(timestamps)) if timestamps else None,
    }


def record_coverage_result(
    state: dict[str, Any], *, test_id: str, ok: bool, completed_at: float
) -> None:
    records = state.setdefault("tests", {})
    records[test_id] = {"completedAt": completed_at, "ok": bool(ok)}


def write_coverage_state(
    path: Path, state: dict[str, Any], *, current_test_ids: set[str]
) -> None:
    """Atomically persist current-catalog records after every completed test."""
    records = state.get("tests") or {}
    payload = {
        "version": 1,
        "tests": {
            test_id: records[test_id]
            for test_id in sorted(current_test_ids)
            if test_id in records
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    except OSError as exc:
        raise RunnerError(f"Cannot persist smoke coverage state {path}: {exc}") from exc


def catalog_ids(tests: list[dict[str, Any]]) -> set[str]:
    return {str(test.get("id") or "") for test in tests}


def unmatched_ids(catalogued: list[dict[str, Any]], requested: set[str]) -> set[str]:
    """Requested `--id`s that no test in the catalog answers to.

    Selection is a filter, so an id nobody matches contributes nothing and
    disappears without trace: ask for four tests, get "3 passed, 0 failed", and
    the run reads as green. A typo, a renamed test or a stale catalog all look
    like success. Callers use this to refuse instead.

    Measured against the *catalog*, not against the post-filter selection. It used to
    take the selection, so every `--id` combined with a `--tag` that id does not carry
    was reported as "absent from the FIELD_TEST blocks in the scanned roots" — with
    `(did you mean: <the very same id>?)` printed underneath it, because the suggester
    searched the catalog and duly found it there. Ids removed by `--tag`/`--runner`
    are still refused, by [filtered_out_ids]; they are just no longer lied about.
    """
    return requested - catalog_ids(catalogued)


def filtered_out_ids(
    catalogued: list[dict[str, Any]],
    selected: list[dict[str, Any]],
    requested: set[str],
) -> set[str]:
    """Requested `--id`s the catalog knows but `--tag`/`--runner` removed."""
    return (requested & catalog_ids(catalogued)) - catalog_ids(selected)


def describe_shadowed_ids(
    requested_ids: set[str],
    skipped: list[dict[str, Any]] | None,
) -> str | None:
    """Refuse a `--id` that names two annotations, one of which discovery dropped.

    Q8, decided deliberately. Discovery fails **open** on a duplicate id -- it warns, skips
    the second annotation, and catalogs the rest -- because failing closed is how
    `run_field_tests.py --id <anything>` stayed dead tree-wide for twelve hours over one
    malformed annotation. That reasoning is right for an *unreadable* annotation and this
    does not touch it.

    A duplicate is different in kind, and the difference is exactly tonight's subject. It is
    not a local parse problem; it is two well-formed tests claiming one name. The requested
    id still MATCHES -- the surviving copy -- so the runner never reaches the
    unmatched-id path that would have printed the skip list, runs one of the two, and
    reports **PASS**. The author of the second test is told that their test passed. That is
    a surface reporting success for something that never ran.

    So: loud and fatal for the *requested* ids only. A collision elsewhere in the tree still
    only warns, which keeps the twelve-hour lesson intact -- one duplicate cannot make every
    other id unrunnable. `--strict` discovery (what CI uses) still refuses the whole catalog.
    """
    if not requested_ids or not skipped:
        return None
    shadowed = [
        item
        for item in skipped
        if isinstance(item, dict) and str(item.get("duplicateOf") or "") in requested_ids
    ]
    if not shadowed:
        return None
    lines = [
        f"{len(shadowed)} requested --id value(s) are declared TWICE, and discovery kept only "
        "the first. Running would report a result for one annotation under a name that means "
        "two, so this refuses instead:",
    ]
    for item in sorted(shadowed, key=lambda entry: str(entry.get("duplicateOf"))):
        lines.append(
            f"  - {item.get('duplicateOf')}: dropped copy at "
            f"{item.get('sourceFile')}:{item.get('sourceLine')} — {item.get('error')}"
        )
    lines.append("Rename one of them. `python3 tools/discover_field_tests.py --strict` lists every collision.")
    return "\n".join(lines)


def describe_unmatched_ids(
    missing: set[str],
    tests: list[dict[str, Any]],
    filtered: set[str] | None = None,
    tags: set[str] | None = None,
    runner_names: set[str] | None = None,
    skipped: list[dict[str, Any]] | None = None,
) -> str:
    known = sorted(str(test.get("id") or "") for test in tests)
    lines: list[str] = []
    if missing:
        lines.append(f"{len(missing)} requested --id value(s) matched no test in the catalog:")
        for wanted in sorted(missing):
            near = difflib.get_close_matches(wanted, known, n=3, cutoff=0.6)
            suffix = f"  (did you mean: {', '.join(near)}?)" if near else ""
            lines.append(f"  - {wanted}{suffix}")
        lines.append(
            "The catalog was refreshed before this check, so these ids are absent from the "
            "FIELD_TEST blocks in the scanned roots, not merely stale."
        )
        if skipped:
            # The third reason an id can be absent, and the one that used to be invisible:
            # discovery read the annotation and could not parse it. Before discovery failed open,
            # that state took the entire catalog down instead, so nobody had to be told which
            # annotation it was.
            lines.append(
                f"{len(skipped)} FIELD_TEST annotation(s) were skipped as unreadable, so an id "
                "declared in one of these is absent for that reason rather than never written:"
            )
            lines.extend(
                f"  - {item.get('sourceFile')}:{item.get('sourceLine')}: {item.get('error')}"
                for item in skipped
            )
    if filtered:
        narrowed = ", ".join(
            part
            for part in (
                f"--tag {'/'.join(sorted(tags))}" if tags else "",
                f"--runner {'/'.join(sorted(runner_names))}" if runner_names else "",
            )
            if part
        )
        lines.append(
            f"{len(filtered)} requested --id value(s) are in the catalog but were removed by "
            f"{narrowed or 'the other filters'}:"
        )
        lines.extend(f"  - {wanted}" for wanted in sorted(filtered))
        lines.append(
            "--id and --tag/--runner intersect rather than union. Drop the narrowing filter to "
            "run these ids, or drop the ids to run the filter."
        )
    return "\n".join(lines)


def current_platform_tokens() -> set[str]:
    """Case-insensitive aliases for the running platform, matched against a test's
    `skipOn` list."""
    if sys.platform == "darwin":
        return {"darwin", "mac", "macos", "osx"}
    if sys.platform.startswith("linux"):
        return {"linux"}
    if sys.platform.startswith("win") or os.name == "nt":
        return {"win", "win32", "windows"}
    return {sys.platform}


def platform_skip_reason(test: dict[str, Any]) -> str | None:
    """A FIELD_TEST may declare `skipOn: [darwin, ...]` to opt out on platforms
    where it cannot run reliably (e.g. raytracing on macOS). Returns the matching
    token if this test should be skipped on the current platform, else None."""
    raw = test.get("skipOn")
    if raw is None:
        return None
    entries = [raw] if isinstance(raw, str) else list(raw)
    tokens = current_platform_tokens()
    for entry in entries:
        if str(entry).strip().lower() in tokens:
            return str(entry)
    return None


def sanitize_test_id(test_id: str) -> str:
    cleaned = "".join(char if char.isalnum() else "-" for char in test_id).strip("-").lower()
    return cleaned or "field-test"


def extension_for_test(test: dict[str, Any]) -> str:
    property_name = str(test.get("property") or "code")
    language = str(test.get("snippetLanguage") or "").lower()
    if property_name in {"fragment", "vertex", "compute", "task", "mesh"}:
        return "glsl"
    if language == "forth":
        return "kt"
    return {
        "kotlin": "kt",
        "kt": "kt",
        "glsl": "glsl",
        "javascript": "js",
        "js": "js",
        "python": "py",
        "clojure": "clj",
        "html": "html",
        "yaml": "yaml",
        "text": "txt",
    }.get(language, "txt")


def wrap_snippet_for_backdoor(test: dict[str, Any], snippet: str) -> str:
    language = str(test.get("snippetLanguage") or "").lower()
    if language == "forth":
        source_literal = json.dumps(snippet)
        return f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
import afield.docs.renderForthDocsSnippet

val __forthSource = {source_literal}
renderForthDocsSnippet(__forthSource)
""".strip()
    return snippet


def playwright_timeout(seconds: float) -> float:
    return max(seconds, PLAYWRIGHT_TIMEOUT_FLOOR)


def json_stream_loads(text: str, *, test_id: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    index = 0
    results: list[dict[str, Any]] = []
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        try:
            value, index = decoder.raw_decode(text, index)
        except json.JSONDecodeError as exc:
            raise RunnerError(
                f"{test_id}: backdoor returned invalid JSON: {exc}\nstdout:\n{text}"
            ) from exc
        if not isinstance(value, dict):
            raise RunnerError(f"{test_id}: backdoor returned a non-object JSON value")
        results.append(value)
    if not results:
        raise RunnerError(f"{test_id}: backdoor returned no JSON results")
    return results


def parse_backdoor_results(process: subprocess.CompletedProcess[str], *, test_id: str) -> list[dict[str, Any]]:
    stdout = process.stdout.strip()
    if not stdout:
        # Empty stdout is the case where stderr matters MOST -- the backdoor died
        # before it could emit anything, so its traceback is the only evidence of
        # why. Dropping it here hid a JVM-level JDWP port collision behind a bare
        # "no JSON result" for two full smoke runs.
        raise RunnerError(
            f"{test_id}: backdoor returned no JSON result\nstderr:\n{process.stderr}"
        )
    try:
        return json_stream_loads(process.stdout, test_id=test_id)
    except RunnerError as exc:
        raise RunnerError(f"{exc}\nstderr:\n{process.stderr}") from exc


def write_generated_artifact(filename: str, content: str) -> Path:
    GENERATED_SNIPPETS_ROOT.mkdir(parents=True, exist_ok=True)
    path = GENERATED_SNIPPETS_ROOT / filename
    path.write_text(content.rstrip() + "\n", encoding="utf-8")
    return path


def ensure_snippet_artifact(test: dict[str, Any]) -> Path:
    snippet = test.get("snippet")
    if not isinstance(snippet, str) or not snippet.strip():
        raise RunnerError(f"{test.get('id')}: backdoor-box tests require a non-empty snippet")
    return write_generated_artifact(
        f"{sanitize_test_id(str(test.get('id')))}.{extension_for_test(test)}",
        wrap_snippet_for_backdoor(test, snippet),
    )


def graphics_context_config(test: dict[str, Any]) -> dict[str, Any] | None:
    raw = test.get("graphicsContext")
    if raw is None or raw is False:
        return None
    if raw is True:
        return {}
    if isinstance(raw, dict):
        return dict(raw)
    raise RunnerError(f"{test.get('id')}: graphicsContext must be true/false or a mapping")


def screenshot_expectation(test: dict[str, Any]) -> dict[str, Any] | None:
    expect = test.get("expect")
    if not isinstance(expect, dict):
        return None
    screenshot = expect.get("screenshot")
    if screenshot is True:
        return {"kind": "box"}
    if isinstance(screenshot, dict):
        return dict(screenshot)
    return None


def image_expectation(test: dict[str, Any]) -> dict[str, Any] | None:
    expect = test.get("expect")
    if not isinstance(expect, dict):
        return None
    image = expect.get("image")
    if isinstance(image, dict):
        return dict(image)
    return None


def image_expectation_path(test: dict[str, Any]) -> Path | None:
    image = image_expectation(test)
    if not image:
        return None
    raw_path = image.get("path")
    if not raw_path:
        return None
    path = Path(str(raw_path))
    return path if path.is_absolute() else (REPO_ROOT / path)


def build_screenshot_command(test: dict[str, Any], capture_path: Path, *, timeout: float) -> str:
    screenshot = screenshot_expectation(test) or {}
    kind = str(screenshot.get("kind") or "box")
    if kind == "box":
        padding = float(screenshot.get("padding") or 12.0)
        parts = [
            "screenshot-box",
            "@last",
            str(capture_path),
            "--padding",
            str(padding),
            "--timeout",
            str(playwright_timeout(timeout)),
        ]
        if screenshot.get("panIntoView") is False:
            parts.append("--no-pan")
        return shlex.join(parts)

    if kind == "editor":
        padding = float(screenshot.get("padding") or 12.0)
        parts = [
            "screenshot-editor",
            "@last",
            str(capture_path),
            "--property",
            str(test.get("property") or "code"),
            "--padding",
            str(padding),
            "--timeout",
            str(playwright_timeout(timeout)),
        ]
        if screenshot.get("openOutputs"):
            parts.append("--open-outputs")
        if screenshot.get("panIntoView") is False:
            parts.append("--no-pan")
        return shlex.join(parts)

    parts = [
        "screenshot-window",
        str(capture_path),
        "--uid",
        "@last",
        "--timeout",
        str(playwright_timeout(timeout)),
    ]
    if kind == "window-editor" or screenshot.get("withEditor"):
        parts.append("--with-editor")
    if screenshot.get("openOutputs", True):
        parts.append("--open-outputs")
    if screenshot.get("panIntoView") is False:
        parts.append("--no-pan")
    return shlex.join(parts)


def build_backdoor_command(
    test: dict[str, Any],
    *,
    port: int,
    workspace: str | None,
) -> tuple[list[str], dict[str, Any]]:
    runner = str(test.get("runner") or "")
    timeout = float(test.get("timeoutSec") or 20.0)
    cmd = [sys.executable, str(BACKDOOR_PATH), "--json", "--port", str(port), "--timeout", str(timeout)]
    artifacts: dict[str, Any] = {}
    if workspace:
        cmd.extend(["--workspace", workspace])

    if runner == "jvm-main":
        entrypoint = test.get("entrypoint")
        if not isinstance(entrypoint, str) or not entrypoint:
            raise RunnerError(f"{test.get('id')}: missing jvm-main entrypoint")
        command_parts = ["run-jvm-main", "--timeout", str(timeout), entrypoint]
        for arg in test.get("args") or []:
            # A value that begins with a dash has to use `--arg=VALUE`: argparse rejects a
            # *separate* value starting with a dash, so `args: ["--tier", "fast"]` used to die
            # with "argument --arg: expected one argument" and a FIELD_TEST could not hand its
            # entrypoint an option at all.
            #
            # Everything else keeps the original `--arg VALUE` spelling, deliberately. Both forms
            # parse identically (`--arg` is `action="append"`, and argparse splits an inline value
            # on the first `=`), so emitting `=` unconditionally would also have been correct —
            # but this file is shared harness code that every FIELD_TEST and the 6am CI loop run
            # through, and "the emitted command for every pre-existing test is byte-for-byte what
            # it was" is a property worth more than the one-line saving. See
            # tools/test_field_backdoor.py::test_jvm_main_arg_emission.
            text = str(arg)
            if text.startswith("-"):
                command_parts.append(f"--arg={text}")
            else:
                command_parts.extend(["--arg", text])
        cmd.extend(["--command", shlex.join(command_parts)])
        return cmd, artifacts

    if runner == "backdoor-box":
        snippet_path = ensure_snippet_artifact(test)
        artifacts["snippetPath"] = str(snippet_path)
        graphics_context = graphics_context_config(test)

        screenshot = screenshot_expectation(test)
        if screenshot is not None:
            CAPTURES_ROOT.mkdir(parents=True, exist_ok=True)
            capture_path = CAPTURES_ROOT / f"{sanitize_test_id(str(test.get('id')))}.png"
            artifacts["capturePath"] = str(capture_path)
        else:
            capture_path = None

        if test.get("document"):
            cmd.extend(["--document", str(test["document"])])
        elif bool(test.get("scratch")):
            cmd.append("--scratch")

        if graphics_context is not None:
            cmd.append("--graphics-context")

        if bool(test.get("withPlaywright")) or screenshot is not None:
            cmd.append("--with-playwright")

        name = str(test.get("boxName") or str(test.get("id"))).replace('"', "'")
        x = float(test.get("x") or 0.0)
        y = float(test.get("y") or 0.0)
        w = float(test.get("w") or 140.0)
        h = float(test.get("h") or 48.0)
        property_name = str(test.get("property") or "code")
        execution = str(test.get("execution") or "run").strip().lower()
        if execution not in {"run", "launch"}:
            raise RunnerError(f"{test.get('id')}: unsupported execution '{execution}'")
        wait_seconds = float(test.get("waitSec") or test.get("wait") or 0.0)

        commands: list[str] = []
        # A snippet in a main language has to run in a *child* of a box carrying `mainLanguage`, so
        # it takes two boxes and a connect. Without this the snippet lands in a plain Kotlin box and
        # fails as Kotlin — for a Clojure snippet, an "expecting a top level declaration" on line 1,
        # which reads as a broken example rather than a harness that never set the language.
        # Everything after this still addresses the snippet's box as `@last`, because the child is
        # the last box created.
        language = str(test.get("snippetLanguage") or "").lower()
        if needs_language_root(language):
            root_alias = f"{sanitize_test_id(str(test.get('id')))}-{language}-root"
            commands.append(
                shlex.join(
                    [
                        "new-box",
                        "--name",
                        f"{name}-root",
                        "--alias",
                        root_alias,
                        "--x",
                        str(x),
                        "--y",
                        str(y - 100.0),
                        "--w",
                        "120",
                        "--h",
                        "80",
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
            commands.append(shlex.join(["set", root_alias, "mainLanguage", language]))
        commands.append(
            shlex.join(
                [
                    "new-box",
                    "--name",
                    name,
                    "--x",
                    str(x),
                    "--y",
                    str(y),
                    "--w",
                    str(w),
                    "--h",
                    str(h),
                    "--timeout",
                    str(timeout),
                ]
            )
        )
        if needs_language_root(language):
            commands.append(shlex.join(["connect", root_alias, "@last", "--timeout", str(timeout)]))
            # The topology watcher swaps the child's execution on its own poll, not on the connect,
            # so the snippet has to arrive after that has happened rather than in the same breath.
            commands.append(shlex.join(["sleep", "0.5"]))
        editor_capture = screenshot is not None and str(screenshot.get("kind") or "box") == "editor"
        if editor_capture:
            commands.append(
                shlex.join(
                    [
                        "set",
                        "@last",
                        property_name,
                        "--file",
                        str(snippet_path),
                    ]
                )
            )
            commands.append(
                shlex.join(
                    [
                        "editor-open",
                        "@last",
                        "--property",
                        property_name,
                        "--padding",
                        str(float(screenshot.get("padding") or 12.0)),
                        "--timeout",
                        str(playwright_timeout(timeout)),
                    ]
                )
            )
            commands.append(
                shlex.join(
                    [
                        "launch" if execution == "launch" else "run",
                        "@last",
                        "--property",
                        property_name,
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
        else:
            commands.append(
                shlex.join(
                    [
                        "launch" if execution == "launch" else "run",
                        "@last",
                        "--property",
                        property_name,
                        "--file",
                        str(snippet_path),
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
        image_path = image_expectation_path(test)
        if image_path is not None:
            commands.append(
                shlex.join(
                    [
                        "wait-file",
                        str(image_path),
                        "--timeout",
                        str(timeout),
                        "--fail-on-error-uid",
                        "@last",
                    ]
                )
            )
        elif wait_seconds > 0:
            # Tests without a concrete readiness artifact still need a bounded
            # settle period. Image-producing tests wait for the freshly deleted
            # output file above instead of sleeping for their worst-case budget.
            commands.append(shlex.join(["sleep", str(wait_seconds)]))
        if capture_path is not None:
            commands.append(build_screenshot_command(test, capture_path, timeout=timeout))
        if execution == "launch":
            # Collect before stopping. A launched box produces its output *after* the `launch`
            # command has returned, and `sleep` records nothing — so a launch test's events were
            # whatever arrived inside the launch call itself, which for an animation is one
            # `is-running`. `output` flushes what accumulated in between, which is what makes
            # `expect.outputContains` mean anything here: docs.clojure.animate wants the frame
            # counter its figure shows, and used to fail with "no output-area events were emitted"
            # against a box that had in fact drawn several thousand frames.
            commands.append(shlex.join(["output", "@last", "--kind", "all"]))
            # `beforeStop` / `afterStop`: backdoor command lines run either side of the stop, each
            # one's ok counting toward the test — how a lifecycle smoke asserts what a box holds
            # while it runs (`lifecycle-census @last --expect-at-least ...`) and that stop released
            # it (`lifecycle-census @last --expect-empty`).
            commands.extend(str(line) for line in (test.get("beforeStop") or []))
            commands.append(
                shlex.join(
                    [
                        "stop",
                        "@last",
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
            commands.extend(str(line) for line in (test.get("afterStop") or []))
        if bool((test.get("expect") or {}).get("allowExecutionErrors", False)):
            # `allowExecutionErrors` already suppressed `collect_execution_problems`
            # (:1651 below). Since 2026-09-14 `run`/`launch` also report `ok: false` for a
            # box that errored, and BOTH the unconditional `ok` check in
            # `evaluate_expectations` (:1593) and `field_backdoor.py`'s batch loop (which
            # returns at the first not-ok command) sit outside that gate -- so without this
            # the five tests carrying the flag would fail, and would do so by never reaching
            # their screenshot. Waiving the verdict does not hide the evidence: the
            # diagnostics stay in `data.errors` and `executionErrorsIgnored` records it.
            commands = [waive_execution_verdict(line) for line in commands]
        for command_line in commands:
            cmd.extend(["--command", command_line])
        return cmd, artifacts

    raise RunnerError(f"{test.get('id')}: unsupported runner '{runner}'")


def collect_backdoor_events(backdoor_results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for result in backdoor_results:
        result_events = result.get("events") or []
        if isinstance(result_events, list):
            events.extend(event for event in result_events if isinstance(event, dict))
    return events


def collect_execution_problems(backdoor_results: list[dict[str, Any]]) -> list[str]:
    problems: list[str] = []
    for result in backdoor_results:
        command = str(result.get("command") or "<unknown command>")
        data = result.get("data")
        best_effort_timeout = (
            bool(result.get("timedOut"))
            and bool(result.get("ok", False))
            and isinstance(data, dict)
            and bool(data.get("bestEffort"))
        )
        if result.get("timedOut") and not best_effort_timeout:
            problems.append(f"{command}: timed out")
        if not bool(result.get("ok", False)):
            error_text = None
            if isinstance(data, dict):
                raw_error = data.get("error") or data.get("message")
                if raw_error is not None:
                    error_text = str(raw_error)
            problems.append(f"{command}: {error_text or 'command failed'}")

        events = result.get("events")
        if not isinstance(events, list):
            continue
        for event in events:
            if not isinstance(event, dict):
                continue
            name = event.get("name") or event.get("messageName")
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if name in {"output", "peer-output"} and payload.get("kind") in {"error", "err"}:
                line = payload.get("line")
                line_suffix = f" line {line}" if line is not None else ""
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                # An error event with no text is not an error: it is how a runtime *clears* the
                # marker a previous failed run left on the box (see `HasOutput.error`'s "an error of
                # null,,-1 means 'clear errors'" in `src/jvmMain/kotlin/afield/outputs.kt`).
                # DatalogExecution sends one on every success and ClojureExecution now does too, so
                # reading it as a failure would fail every test whose box merely ran cleanly — and
                # report it as the bare string "execution error", with nothing to go on.
                if not html:
                    continue
                problems.append(f"{command}{line_suffix}: {html}")
                continue
            if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
                text = str(payload.get("text", "")).replace("\n", " ").strip()
                if text:
                    problems.append(f"{command}: {text}")

    return list(dict.fromkeys(problems))


def collect_output_gap(test: dict[str, Any], backdoor_results: list[dict[str, Any]]) -> str | None:
    snippet = test.get("snippet")
    if not isinstance(snippet, str) or not snippet.strip():
        return None

    events = collect_backdoor_events(backdoor_results)
    if not events:
        return None

    set_output_events: list[dict[str, Any]] = []
    for event in events:
        name = event.get("name") or event.get("messageName")
        if name != "set-output":
            continue
        payload = event.get("payload")
        if isinstance(payload, dict):
            set_output_events.append(payload)

    has_output_area = bool(set_output_events)
    has_svg_output = any("<svg" in str(payload.get("value", "")).lower() for payload in set_output_events)

    # Both call shapes, because a snippet in this catalog is not necessarily Kotlin: `lines(…)` is
    # how a Kotlin or Python box draws, `(lines …)` is how a Clojure one does. Matching only the
    # first made this check quietly vacuous for every Lisp snippet — a box that drew nothing passed.
    expects_svg = re.search(r"\blines\s*\(|\(\s*lines\b", snippet) is not None
    expects_display = re.search(r"\bdisplay\s*\(|\(\s*display\b", snippet) is not None
    expects_output_area = expects_svg or expects_display

    if expects_output_area and not has_output_area:
        return (
            "No output-area events were emitted (missing set-output messages). "
            "Likely causes: code did not call display/lines, output proxy failed, or execution stopped before rendering."
        )

    if expects_svg and not has_svg_output:
        channels = sorted(
            {
                str(payload.get("channel", ""))
                for payload in set_output_events
                if payload.get("channel") is not None
            }
        )
        channel_text = f" Channels seen: {', '.join(channels)}." if channels else ""
        return "Code appears to call lines(...) but no SVG output was observed in set-output payloads." + channel_text

    return None


def render_event_text(event: dict[str, Any]) -> str:
    # FieldBackdoor records both message-batch envelopes and their decoded children.
    # Serializing arbitrary payloads here lets the echoed `code` property satisfy
    # its own output assertions, even when the box never emits the promised value.
    # Keep eventKinds separate: it may deliberately assert bookkeeping messages.
    payload = event.get("payload")
    if not isinstance(payload, dict):
        return ""
    name = event.get("name")
    if name in {"output", "peer-output"}:
        return str(payload.get("html") or "")
    if name == "set-output":
        return str(payload.get("value") or "")
    if name == "notify":
        return str(payload.get("text") or "")
    return ""


def evaluate_expectations(
    test: dict[str, Any],
    backdoor_results: list[dict[str, Any]],
    *,
    process: subprocess.CompletedProcess[str],
    artifacts: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    expect = test.get("expect") or {}
    if not isinstance(expect, dict):
        expect = {}

    failed_results = [result for result in backdoor_results if not result.get("ok")]
    if failed_results:
        for result in failed_results:
            command = result.get("command") or "<unknown>"
            data = result.get("data") or {}
            if not isinstance(data, dict):
                data = {"value": data}
            error = data.get("error") or data.get("message") or "backdoor command failed"
            failures.append(f"{command}: {error}")
    elif process.returncode != 0:
        failures.append(f"backdoor process exited with code {process.returncode}")

    last_result = backdoor_results[-1]
    last_data = last_result.get("data") or {}
    if not isinstance(last_data, dict):
        last_data = {}

    if "exitCode" in expect:
        exit_code = last_data.get("exitCode")
        if exit_code != expect.get("exitCode"):
            failures.append(f"expected exitCode={expect.get('exitCode')}, got {exit_code}")

    stdout = str(last_data.get("stdout") or "")
    stderr = str(last_data.get("stderr") or "")

    for needle in expect.get("stdoutContains") or []:
        if str(needle) not in stdout:
            failures.append(f"stdout missing substring: {needle}")

    for needle in expect.get("stdoutNotContains") or []:
        if str(needle) in stdout:
            failures.append(f"stdout unexpectedly contained: {needle}")

    for needle in expect.get("stderrContains") or []:
        if str(needle) not in stderr:
            failures.append(f"stderr missing substring: {needle}")

    for needle in expect.get("stderrNotContains") or []:
        if str(needle) in stderr:
            failures.append(f"stderr unexpectedly contained: {needle}")

    events = collect_backdoor_events(backdoor_results)
    event_texts = [render_event_text(event) for event in events]
    output_blob = "\n".join(event_texts)
    event_names = {str(event.get("name")) for event in events}

    for needle in expect.get("outputContains") or []:
        if str(needle) not in output_blob:
            failures.append(f"output missing substring: {needle}")

    for needle in expect.get("outputNotContains") or []:
        if str(needle) in output_blob:
            failures.append(f"output unexpectedly contained: {needle}")

    for event_name in expect.get("eventKinds") or []:
        if str(event_name) not in event_names:
            failures.append(f"missing event kind: {event_name}")

    if not bool(expect.get("allowExecutionErrors", False)):
        failures.extend(collect_execution_problems(backdoor_results))

    if not bool(expect.get("allowMissingOutput", False)):
        output_gap = collect_output_gap(test, backdoor_results)
        if output_gap is not None:
            failures.append(output_gap)

    screenshot = screenshot_expectation(test)
    if screenshot is not None:
        capture_path = artifacts.get("capturePath")
        if not capture_path:
            failures.append("expected a screenshot artifact, but none was configured")
        elif not Path(capture_path).exists():
            failures.append(f"expected screenshot was not created: {capture_path}")

    image = image_expectation(test)
    if image is not None:
        failures.extend(evaluate_image_expectation(test, image, artifacts=artifacts))

    return list(dict.fromkeys(failures))


def resolve_image_path(image_expect: dict[str, Any], artifacts: dict[str, Any]) -> Path:
    raw_path = image_expect.get("path") or artifacts.get("capturePath")
    if not raw_path:
        raise RunnerError("image expectation requires a path or a screenshot artifact")
    return Path(str(raw_path))


def rect_to_box(raw: Any, *, width: int, height: int) -> tuple[int, int, int, int]:
    if not isinstance(raw, list) or len(raw) != 4:
        raise RunnerError(f"image region must be a 4-item list, got {raw!r}")
    try:
        values = [float(v) for v in raw]
    except (TypeError, ValueError) as exc:
        raise RunnerError(f"image region contains a non-numeric value: {raw!r}") from exc

    normalized = all(0.0 <= value <= 1.0 for value in values)
    if normalized:
        x = round(values[0] * width)
        y = round(values[1] * height)
        w = round(values[2] * width)
        h = round(values[3] * height)
    else:
        x, y, w, h = (round(value) for value in values)

    left = max(0, min(width - 1, x))
    top = max(0, min(height - 1, y))
    right = max(left + 1, min(width, left + max(1, w)))
    bottom = max(top + 1, min(height, top + max(1, h)))
    return left, top, right, bottom


def region_mean_rgb(image: Image.Image, raw_rect: Any) -> tuple[float, float, float]:
    box = rect_to_box(raw_rect, width=image.width, height=image.height)
    crop = image.crop(box).convert("RGB")
    mean = ImageStat.Stat(crop).mean
    return float(mean[0]), float(mean[1]), float(mean[2])


def region_mean_luma(image: Image.Image, raw_rect: Any) -> float:
    r, g, b = region_mean_rgb(image, raw_rect)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def region_mean_abs_diff(image: Image.Image, rect_a: Any, rect_b: Any) -> float:
    box_a = rect_to_box(rect_a, width=image.width, height=image.height)
    box_b = rect_to_box(rect_b, width=image.width, height=image.height)
    crop_a = image.crop(box_a).convert("RGB")
    crop_b = image.crop(box_b).convert("RGB").resize(crop_a.size)
    diff = ImageChops.difference(crop_a, crop_b)
    mean = ImageStat.Stat(diff).mean
    return float(sum(mean) / len(mean))


def evaluate_image_expectation(
    test: dict[str, Any],
    image_expect: dict[str, Any],
    *,
    artifacts: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    path = resolve_image_path(image_expect, artifacts)
    if not path.exists():
        return [f"expected image was not created: {path}"]

    with Image.open(path) as raw_image:
        image = raw_image.convert("RGBA")

        for check in image_expect.get("regionsDifferent") or []:
            if not isinstance(check, dict):
                failures.append(f"invalid regionsDifferent entry: {check!r}")
                continue
            rect_a = check.get("a")
            rect_b = check.get("b")
            if rect_a is None or rect_b is None:
                failures.append(f"regionsDifferent requires 'a' and 'b': {check!r}")
                continue
            diff = region_mean_abs_diff(image, rect_a, rect_b)
            threshold = check.get("meanAbsDiffGt")
            if threshold is not None and diff <= float(threshold):
                failures.append(
                    f"{test.get('id')}: image diff {diff:.2f} did not exceed {float(threshold):.2f}"
                )

        for check in image_expect.get("lumaComparisons") or []:
            if not isinstance(check, dict):
                failures.append(f"invalid lumaComparisons entry: {check!r}")
                continue
            darker = check.get("darker")
            lighter = check.get("lighter")
            if darker is None or lighter is None:
                failures.append(f"lumaComparisons requires 'darker' and 'lighter': {check!r}")
                continue
            margin = float(check.get("marginGt") or 0.0)
            darker_luma = region_mean_luma(image, darker)
            lighter_luma = region_mean_luma(image, lighter)
            if not darker_luma + margin < lighter_luma:
                failures.append(
                    f"{test.get('id')}: darker region luma {darker_luma:.2f} "
                    f"was not at least {margin:.2f} below lighter region {lighter_luma:.2f}"
                )

        for check in image_expect.get("regionLuma") or []:
            if not isinstance(check, dict):
                failures.append(f"invalid regionLuma entry: {check!r}")
                continue
            rect = check.get("rect")
            if rect is None:
                failures.append(f"regionLuma requires 'rect': {check!r}")
                continue
            luma = region_mean_luma(image, rect)
            if "gt" in check and luma <= float(check["gt"]):
                failures.append(
                    f"{test.get('id')}: region luma {luma:.2f} did not exceed {float(check['gt']):.2f}"
                )
            if "lt" in check and luma >= float(check["lt"]):
                failures.append(
                    f"{test.get('id')}: region luma {luma:.2f} was not below {float(check['lt']):.2f}"
                )

    return failures


def outer_process_timeout(test: dict[str, Any], command: list[str]) -> float:
    """Budget the wrapper, not just the in-Field operation.

    Commands execute serially. Give each its declared timeout plus fixed startup
    and shutdown headroom; Playwright commands already carry a 180s first-run
    floor. This watchdog is deliberately outside field_backdoor.py so it catches
    the successful-command/non-exiting-wrapper failure mode as well.
    """
    per_command = float(test.get("timeoutSec") or 20.0)
    if "--with-playwright" in command:
        per_command = max(per_command, PLAYWRIGHT_TIMEOUT_FLOOR)
    return 45.0 + per_command


def run_one_test(
    test: dict[str, Any],
    *,
    port: int,
    workspace: str | None,
    progress: Any = None,
    heartbeat_interval: float = 15.0,
    check_environment: Any = None,
) -> dict[str, Any]:
    test_id = str(test.get("id"))
    command, artifacts = build_backdoor_command(test, port=port, workspace=workspace)
    image_path = image_expectation_path(test)
    if image_path is not None:
        try:
            image_path.unlink()
        except FileNotFoundError:
            pass
    outer_timeout = outer_process_timeout(test, command)
    expected_results = command.count("--command")

    def commands_complete(stdout: str) -> bool:
        if not stdout.strip():
            return False
        try:
            return len(json_stream_loads(stdout, test_id=test_id)) >= expected_results
        except RunnerError:
            return False

    process, duration_ms, timed_out = run_with_watchdog(
        command,
        cwd=REPO_ROOT,
        timeout=outer_timeout,
        heartbeat_interval=heartbeat_interval,
        heartbeat=progress,
        label=test_id,
        completion_probe=commands_complete,
        exit_grace=10.0,
        check_environment=check_environment,
    )

    result: dict[str, Any] = {
        "id": test_id,
        "runner": test.get("runner"),
        "sourceFile": test.get("sourceFile"),
        "sourceLine": test.get("sourceLine"),
        "entrypoint": test.get("entrypoint"),
        "durationMs": duration_ms,
        "command": command,
        "artifacts": artifacts,
        "processExitCode": process.returncode,
        "stdout": process.stdout,
        "stderr": process.stderr,
        # A box test's own output (and its Vulkan validation messages) is in this server log,
        # not in stdout above.
        "serverLog": SERVER_LOGS[-1] if SERVER_LOGS else None,
        "outerTimeoutSec": outer_timeout,
        "runnerTimedOut": timed_out,
    }

    try:
        backdoor_results = parse_backdoor_results(process, test_id=test_id)
        result["backdoorResults"] = backdoor_results
        result["backdoorResult"] = backdoor_results[-1]
        failures = evaluate_expectations(test, backdoor_results, process=process, artifacts=artifacts)
        if timed_out:
            completed_text = (
                "all commands produced JSON results, but the backdoor process did not exit"
                if len(backdoor_results) >= expected_results
                else f"only {len(backdoor_results)} of {expected_results} commands produced JSON results"
            )
            failures.append(
                f"{test_id}: runner watchdog fired after {duration_ms / 1000.0:.1f}s "
                f"(outer limit {outer_timeout:.0f}s); {completed_text}"
            )
        result["failureKind"] = "test" if failures else None
    except RunnerError as exc:
        failures = [str(exc)]
        result["backdoorResults"] = []
        result["backdoorResult"] = None
        # No parseable transport result means the test's assertions never ran. It still fails the
        # catalog, but keeping it distinct prevents one server stall from masquerading as N product
        # regressions in the machine-readable summary.
        result["failureKind"] = "infrastructure"

    result["ok"] = not failures
    result["failures"] = failures
    return result


def format_result_line(result: dict[str, Any]) -> str:
    status = result.get("environmentStatus") or ("XFAIL" if result.get("acceptedFailure") else ("PASS" if result["ok"] else "FAIL"))
    duration = f"{result['durationMs']} ms" if result.get("durationMs") is not None else "runtime not certified"
    return f"{status} {result['id']} ({duration})"


class _NoTimeoutFlag(argparse.Action):
    """`--timeout` is not a smoke-runner flag, and saying so beats argparse's bare "unrecognized".

    The backdoor and the UI runner take `--timeout`, so it is the flag an agent reaches for here
    too. Per-test time limits live in each `FIELD_TEST` annotation's `timeoutSec` instead.
    """

    def __init__(self, option_strings, dest, **kwargs) -> None:
        kwargs["nargs"] = "?"
        super().__init__(option_strings, dest, **kwargs)

    def __call__(self, parser, namespace, values, option_string=None) -> None:
        parser.error(
            f"{option_string} is not accepted: per-test timeouts come from `timeoutSec` in each "
            "FIELD_TEST annotation (the backdoor and run_ui_regressions.py have their own "
            "--timeout; this runner does not). Edit the annotation to change a test's budget."
        )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", action=_NoTimeoutFlag, help=argparse.SUPPRESS)
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--results", default=str(DEFAULT_RESULTS_PATH))
    parser.add_argument("--coverage-state", default=str(DEFAULT_COVERAGE_STATE_PATH))
    parser.add_argument("--expected-failures", default=str(DEFAULT_EXPECTED_FAILURES_PATH))
    parser.add_argument("--refresh-catalog", action="store_true")
    parser.add_argument(
        "--no-reap",
        action="store_true",
        help=(
            "Skip the preflight sweep of this checkout's Field JVMs orphaned by earlier "
            "runs. This never affects Gradle/Kotlin daemons, IDEs, or other checkouts."
        ),
    )
    parser.add_argument(
        "--reap-dry-run",
        action="store_true",
        help=(
            "Run the preflight sweep's classification, print which Field JVMs it attributes "
            "to this checkout and which it would leave to other checkouts, kill nothing, "
            "and exit."
        ),
    )
    parser.add_argument("--root", action="append", dest="roots")
    parser.add_argument("--id", action="append", dest="ids")
    parser.add_argument("--tag", action="append", dest="tags")
    parser.add_argument("--runner", action="append", dest="runners")
    parser.add_argument("--workspace")
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help=(
            f"Exact Field port. When omitted, choose a free port starting at "
            f"{DEFAULT_AUTOMATION_PORT}; never uses developer port 9092."
        ),
    )
    parser.add_argument("--fail-fast", action="store_true")
    parser.add_argument(
        "--catalog-order",
        action="store_true",
        help=(
            "Preserve discovery order and do not update the build-local coverage ledger. "
            "Targeted --id/--tag/--runner runs already preserve discovery order."
        ),
    )
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--heartbeat-interval", type=float, default=15.0)
    parser.add_argument(
        "--no-prewarm",
        action="store_true",
        help="Do not start/keep a persistent Field; rely on an already-running server on --port.",
    )
    return parser.parse_args(argv)


def requires_native_display(test: dict[str, Any]) -> bool:
    # Browser screenshots use headless Playwright, not a physical display. JVM
    # tests that create their own Window declare requiresDisplay explicitly.
    return graphics_context_config(test) is not None or test.get("requiresDisplay") is True


def requires_sidecar(test: dict[str, Any]) -> bool:
    """A test that exercises the AA completion chain declares `requiresSidecar: true`.

    Until 2026-09-30 such tests found no sidecar in the catalog, printed SKIP and exited 0, so the
    smoke gate PASSED them without completing anything -- and passed straight through two days in
    which the Kotlin 2.5 uplift had left the sidecar unable to serve a single AA completion. The
    runner now provides one (see `completion_sidecar.SidecarLease`) and grades a test it cannot give
    a working one as environment-incomplete: never run, never passed.
    """
    return test.get("requiresSidecar") is True


def run(args: argparse.Namespace, environment: VerificationEnvironment | None = None) -> int:
    port_was_explicit = args.port is not None
    requested_port = args.port if port_was_explicit else DEFAULT_AUTOMATION_PORT
    args.port = select_automation_port(requested_port, explicit=port_was_explicit)

    catalog_path = Path(args.catalog)
    if not catalog_path.is_absolute():
        catalog_path = REPO_ROOT / catalog_path
    results_path = Path(args.results)
    if not results_path.is_absolute():
        results_path = REPO_ROOT / results_path
    coverage_state_path = Path(getattr(args, "coverage_state", DEFAULT_COVERAGE_STATE_PATH))
    if not coverage_state_path.is_absolute():
        coverage_state_path = REPO_ROOT / coverage_state_path

    catalog = load_or_refresh_catalog(
        catalog_path=catalog_path,
        refresh_catalog=args.refresh_catalog,
        roots=args.roots,
    )
    requested_ids = set(args.ids or [])

    def select(from_catalog: dict[str, Any]) -> list[dict[str, Any]]:
        return filter_tests(
            list(from_catalog.get("tests") or []),
            ids=requested_ids,
            tags=set(args.tags or []),
            runner_names=set(args.runners or []),
        )

    selected = select(catalog)

    # An unmatched --id is usually a stale catalog rather than a typo: the file on
    # disk is only rebuilt on --refresh-catalog or when absent, so a test added
    # since the last run is invisible. Rediscover once before believing it is gone.
    # Measured against the catalog, so an id narrowed away by --tag/--runner does not
    # trigger a pointless re-scan that then reports it as never having existed.
    catalogued = list(catalog.get("tests") or [])
    missing = unmatched_ids(catalogued, requested_ids)
    if missing and not args.refresh_catalog:
        print(
            f"Re-scanning for FIELD_TEST blocks: {len(missing)} requested id(s) "
            f"absent from {catalog_path}",
            file=sys.stderr,
            flush=True,
        )
        catalog = load_or_refresh_catalog(
            catalog_path=catalog_path,
            refresh_catalog=True,
            roots=args.roots,
        )
        catalogued = list(catalog.get("tests") or [])
        selected = select(catalog)
        missing = unmatched_ids(catalogued, requested_ids)

    # Refuse rather than run a subset: a partially-matched selection that reports
    # "N passed" for fewer tests than were asked for reads as a clean run. Both
    # reasons an id can go missing are refused; they are reported apart, because
    # "your --tag excluded it" and "it does not exist" call for different fixes.
    # Checked before the unmatched-id report, because a shadowed id is not missing: it
    # matches the copy discovery kept, so nothing below would ever mention it.
    shadowed = describe_shadowed_ids(requested_ids, list(catalog.get("skipped") or []))
    if shadowed:
        raise SystemExit(shadowed)

    filtered = filtered_out_ids(catalogued, selected, requested_ids)
    if missing or filtered:
        raise SystemExit(
            describe_unmatched_ids(
                missing,
                catalogued,
                filtered=filtered,
                tags=set(args.tags or []),
                runner_names=set(args.runners or []),
                skipped=list(catalog.get("skipped") or []),
            )
        )

    if not selected:
        raise SystemExit("No tests matched the requested filters.")

    expected_failures_path = Path(args.expected_failures)
    if not expected_failures_path.is_absolute():
        expected_failures_path = REPO_ROOT / expected_failures_path
    expected_failures = load_expected_smoke_failures(
        expected_failures_path,
        catalog_ids={str(test.get("id") or "") for test in catalog.get("tests") or []},
    )

    # In --json mode stdout is reserved for the final report blob, so per-test
    # progress goes to stderr (flushed per line) instead of being suppressed.
    # `2>&1 | tee` then streams it live; a bare stdout pipe still parses as JSON.
    def progress(message: str) -> None:
        stream = sys.stderr if args.json else sys.stdout
        print(message, file=stream, flush=True)

    if port_was_explicit:
        progress(f"Smoke automation port: {args.port} (explicit)")
    else:
        moved = (
            f", default {DEFAULT_AUTOMATION_PORT} was occupied"
            if args.port != DEFAULT_AUTOMATION_PORT
            else ""
        )
        progress(f"Smoke automation port: {args.port} (automatically selected{moved})")

    # Per-platform opt-outs (e.g. `skipOn: [darwin]` for raytracing on macOS,
    # where the GPU RT pipeline is unreliable). Skips are reported, never silently
    # dropped, and are counted separately from pass/fail.
    tests: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for test in selected:
        reason = platform_skip_reason(test)
        if reason:
            skipped.append(test)
            progress(f"SKIP {test.get('id')} (skipOn '{reason}' matches {sys.platform})")
        else:
            tests.append(test)
    skipped_expected = sorted(
        str(test.get("id")) for test in skipped if str(test.get("id")) in expected_failures
    )
    if skipped_expected:
        raise SystemExit(
            "Registered smoke failure did not execute because it was platform-skipped: "
            + ", ".join(skipped_expected)
        )

    coverage_ordering = not (
        requested_ids
        or args.tags
        or args.runners
        or getattr(args, "catalog_order", False)
    )
    coverage_state: dict[str, Any] = {"version": 1, "tests": {}}
    coverage_at_start: dict[str, Any] | None = None
    if coverage_ordering and tests:
        coverage_state = load_coverage_state(coverage_state_path)
        coverage_at_start = coverage_snapshot(tests, coverage_state, now=time.time())
        tests = order_tests_by_coverage(tests, coverage_state)
        unseen = int(coverage_at_start["unseen"])
        if unseen:
            progress(
                f"Smoke coverage order: {unseen}/{len(tests)} runnable tests have no recorded "
                f"verdict; unseen then least-recently-completed first ({coverage_state_path})"
            )
        else:
            age = float(coverage_at_start["oldestCompletedAgeSec"] or 0.0)
            progress(
                f"Smoke coverage order: least-recently-completed first; oldest runnable "
                f"verdict is {age / 3600.0:.1f}h old ({coverage_state_path})"
            )

    results: list[dict[str, Any]] = []
    passed = 0
    failed = 0
    expected_failed = 0
    environment_incomplete = 0
    total = len(tests)

    if tests:
        warn_if_node_too_old(progress)

    # One completion sidecar for the run, when anything selected needs one. It is handed to the
    # persistent Field below, so every test of such a run completes the way a user's Field does.
    sidecar: SidecarLease | None = None
    if any(requires_sidecar(test) for test in tests):
        sidecar = SidecarLease(
            REPO_ROOT,
            REPO_ROOT / "build" / "code-use-semantic-sidecar" / "smoke-runner-sidecar.log",
            log=lambda message: progress(f"Completion sidecar: {message}"),
        )
        sidecar.ensure()

    def sidecar_problem() -> str | None:
        if sidecar is None:
            return "no completion sidecar was requested for this run"
        if args.no_prewarm:
            # The URL reaches a test only through the Field JVM it runs under, and this run did not
            # start that JVM.
            return "--no-prewarm: this run did not start Field, so it could not give Field the sidecar"
        return sidecar.problem()

    field_proc: subprocess.Popen[Any] | None = None
    try:
        for index, test in enumerate(tests, start=1):
            started = False
            wall_started = time.time()
            try:
                token = environment.checkpoint(require_display=requires_native_display(test)) if environment else 0
                def check_environment():
                    if environment:
                        environment.check(token, require_display=requires_native_display(test))
                if not args.no_prewarm and field_proc is None:
                    if not args.no_reap:
                        reap_stale_field_jvms(args.port)
                    field_proc = ensure_persistent_field(
                        args.port, workspace=args.workspace, check_environment=check_environment,
                        sidecar_url=sidecar.url if sidecar else None,
                    )
                    progress(f"Started persistent Field on port {args.port} for this run")
                check_environment()
                missing_sidecar = sidecar_problem() if requires_sidecar(test) else None
                if missing_sidecar is not None:
                    result = {"id": test["id"], "ok": False, "failureKind": "environment",
                              "environmentStatus": "NOT RUN", "durationMs": None,
                              "observedWallMs": round((time.time() - wall_started) * 1000),
                              "failures": [f"requires a working completion sidecar: {missing_sidecar}"]}
                else:
                    started = True
                    result = run_one_test(
                        test, port=args.port, workspace=args.workspace, progress=progress,
                        heartbeat_interval=args.heartbeat_interval, check_environment=check_environment,
                    )
                    if requires_sidecar(test) and not result["ok"]:
                        # Say what state the backend was in; "sidecar broken" and "completion regressed"
                        # read the same from the test's own output.
                        after = sidecar_problem()
                        if after is not None:
                            result["failures"].append(f"completion sidecar after the test: {after}")
                if environment:
                    environment.check(token, require_display=requires_native_display(test), force=True)
            except VerificationEnvironmentError as exc:
                result = {"id": test["id"], "ok": False, "failureKind": "environment",
                          "environmentStatus": "INTERRUPTED" if started else "NOT RUN",
                          "durationMs": None, "observedWallMs": round((time.time() - wall_started) * 1000),
                          "failures": [str(exc)]}
                stop_persistent_field(field_proc, port=args.port)
                field_proc = None
            apply_expected_smoke_failure(result, expected_failures)
            results.append(result)
            if result.get("failureKind") == "environment":
                environment_incomplete += 1
            elif result.get("acceptedFailure"):
                expected_failed += 1
            elif result["ok"]:
                passed += 1
            else:
                failed += 1
            if coverage_ordering and result.get("failureKind") != "environment":
                record_coverage_result(
                    coverage_state,
                    test_id=str(test.get("id") or ""),
                    ok=bool(result["ok"] or result.get("acceptedFailure")),
                    completed_at=time.time(),
                )
                write_coverage_state(
                    coverage_state_path,
                    coverage_state,
                    current_test_ids={str(item.get("id") or "") for item in tests},
                )
            progress(f"[{index}/{total}] {format_result_line(result)}")
            for failure in result["failures"]:
                progress(f"  {failure}")
            if failed and args.fail_fast:
                break
            # Graphics-context tests wedge the GPU/window state in the persistent server.
            # Recycle after each such test so the next test gets a clean JVM.
            if field_proc is not None and not args.no_prewarm and graphics_context_config(test) is not None and index < total:
                progress(f"  recycling Field after graphics-context test (GPU state reset)")
                ctx = make_unverified_ssl_context()
                recycle_field(args.port, ctx)
                field_proc = None  # Recheck prerequisites before booting the next test.
    finally:
        stop_persistent_field(field_proc, port=args.port)
        if sidecar is not None:
            sidecar.stop()

    report = {
        "completionSidecar": None if sidecar is None else {
            "url": sidecar.url,
            "unavailable": sidecar.unavailable,
            "startedByRun": sidecar.started_by_lease,
        },
        "catalog": str(catalog_path),
        "automationPort": args.port,
        "automationPortExplicit": port_was_explicit,
        "selectedCount": len(tests),
        "passed": passed,
        "failed": failed,
        "expectedFailed": expected_failed,
        "environmentIncomplete": environment_incomplete,
        "complete": len(results) == len(tests) and environment_incomplete == 0,
        "environment": environment.report() if environment else None,
        "expectedFailureRegistry": str(expected_failures_path),
        "failureKinds": {
            "test": sum(
                1
                for result in results
                if result.get("failureKind") == "test" and not result.get("acceptedFailure")
            ),
            "expected": expected_failed,
            "infrastructure": sum(
                1 for result in results if result.get("failureKind") == "infrastructure"
            ),
            "contract": sum(1 for result in results if result.get("failureKind") == "contract"),
            "environment": environment_incomplete,
        },
        "skippedCount": len(skipped),
        "skipped": [
            {"id": t.get("id"), "reason": f"skipOn {platform_skip_reason(t)} on {sys.platform}"}
            for t in skipped
        ],
        "coverageOrder": {
            "enabled": coverage_ordering,
            "strategy": "unseen-then-least-recently-completed" if coverage_ordering else None,
            "state": str(coverage_state_path) if coverage_ordering else None,
            "unseenAtStart": coverage_at_start["unseen"] if coverage_at_start else None,
            "oldestCompletedAgeSecAtStart": (
                coverage_at_start["oldestCompletedAgeSec"] if coverage_at_start else None
            ),
        },
        "serverLogs": SERVER_LOGS,
        "results": results,
    }
    results_path.parent.mkdir(parents=True, exist_ok=True)
    results_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        skip_note = f", {len(skipped)} skipped" if skipped else ""
        expected_note = f", {expected_failed} expected failed" if expected_failed else ""
        print(f"Summary: {passed} passed, {failed} failed{expected_note}{skip_note}, {environment_incomplete} environment-incomplete")
        if failed:
            kinds = report["failureKinds"]
            print(
                "Failure kinds: "
                f"{kinds['test']} test/assertion, {kinds['expected']} expected, "
                f"{kinds['infrastructure']} infrastructure/transport, "
                f"{kinds['contract']} expected-failure contract"
            )
        print(f"Results: {results_path}")

    return 1 if failed else 2 if environment_incomplete else 0


def main() -> int:
    args = parse_args()
    if args.reap_dry_run:
        # Before the catalog and, deliberately, before verification_lock: this is the
        # "show me what you would kill" diagnostic, and it has to be usable while another
        # run holds the lock.
        print(f"Reaper scope: {_checkout_scope_prefix(REPO_ROOT)}", file=sys.stderr, flush=True)
        port = args.port if args.port is not None else DEFAULT_AUTOMATION_PORT
        reap_stale_field_jvms(port, dry_run=True)
        return 0
    stream = sys.stderr if args.json else sys.stdout
    with verification_lock(
        REPO_ROOT,
        label="FIELD_TEST smoke catalog",
        progress=lambda message: print(message, file=stream, flush=True),
        heartbeat_interval=args.heartbeat_interval,
    ):
        environment = VerificationEnvironment(disk_path=REPO_ROOT).start()
        try:
            return run(args, environment)
        finally:
            environment.close()
            results_path = Path(args.results)
            if not results_path.is_absolute():
                results_path = REPO_ROOT / results_path
            environment.write_report(results_path.with_suffix(".environment.json"), [])


if __name__ == "__main__":
    raise SystemExit(main())
