#!/usr/bin/env python3
"""Fail when a test run writes into the Git working tree.

A test that renders a figure, a golden file or a "proof" artifact has to put it *somewhere*, and
the somewhere kept being the repository. Three separate rounds of that bug are on record here:

1. Relative-path proof writes (``File("sparkline-proof")``, ``File("chebyshev-proof.svg")``),
   which landed junk in whatever tree the suite ran in — every nightly agent worktree included.
   Fixed by ``proofDir()`` in ``src/jvmTest/kotlin/afield/TestProofDir.kt``.
2. ``ArraySparklineVisualizerTest``, the same thing again in a class the first fix missed.
3. ``ValueVisualizerDocCaptureTest`` and ``VectorAlgebraDocCaptureTest``, which wrote their
   docs-capture PNGs straight into ``public/docs/assets/…`` on every ``./gradlew jvmTest``, so a
   plain full-suite run always ended with a dirty tree. Fixed on 2026-09-04 by ``docsAssetDir()``.

Each was found by a human noticing a dirty ``git status``, and fixed one class at a time, with
nothing in the build to stop the fourth. This is that missing piece, and it is deliberately a
*tree diff* rather than a scan of the test sources: it does not care how a test names its
destination, so it catches ``File(root, computeName())`` and a path assembled at runtime exactly
as well as a literal. A static scan cannot — ``GateInputDeclarationTest`` counts
non-literal ``File(root, x)`` sites precisely because it cannot see through them.

Usage is two calls around whatever runs the tests:

    python3 tools/check_test_tree_writes.py --snapshot build/test-tree-writes/baseline.json
    ./gradlew jvmTest
    python3 tools/check_test_tree_writes.py --check build/test-tree-writes/baseline.json

Both halves matter, because content diffs are only half the problem. Before the 2026-09-04 fix all
five ``public/docs/assets/vector_algebra`` PNGs were rewritten on every run, and only two of them
differed in bytes: the other three unconditional writes into committed paths were invisible to
``git status``, and stayed invisible for however long the renderer happened to be deterministic.
So the snapshot records the mtime and size of every tracked file as well as the porcelain status,
and a rewrite-with-identical-bytes is a failure with its own message. It is the *write* that is
the defect; the diff is just the symptom that occasionally makes it visible.

Regeneration is a real need, so an explicit opt-in escape (see ``ESCAPES``) is honoured — but only
for the paths that variable is documented to rewrite. A regenerate run that writes somewhere else
still fails. An escape nobody has registered here also fails, which is the safe direction: the
cost is one loud failure in a run somebody deliberately asked for, rather than a silent hole.
``AFIELD_ALLOW_TREE_WRITES=path,path`` is the way through for a one-off that no entry covers, and
it still names its paths.

Some of those gates are JVM system properties rather than environment variables
(``-Dafield.rectsl.headingfigure=write`` and five siblings), which this process cannot see. The
Gradle task forwards them with ``--property``, mirroring the ``-Dafield.*`` forwarding
``build.gradle.kts`` already does for ``Test`` tasks.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

SNAPSHOT_VERSION = 1


class Escape:
    """A variable that authorizes writes into the tree, and where to.

    ``source`` is ``"env"`` for an environment variable and ``"prop"`` for a JVM system property.
    The property ones are real — `-Dafield.rectsl.headingfigure=write` and five siblings gate
    committed-asset rewrites — and they reach the test JVM because `build.gradle.kts` forwards
    every `-Dafield.*` to `Test` tasks. The check runs in its own process, so the Gradle task has
    to forward them here too, with `--property`.

    ``kind`` says how the allowed locations are derived from the variable:

    ``"flag"``
        The variable must equal ``value``; the allowed paths are the literal ``paths``.
    ``"set"``
        The variable need only be set to something non-blank — what ``!= null`` means on the
        Kotlin side — and the allowed paths are the literal ``paths``.
    ``"dir"``
        The variable's own value *is* the destination directory. Pointing one of these at the
        repository is a deliberate act by whoever exported it, so it authorizes that directory
        and nothing else.
    ``"list"``
        The variable's value is a comma-separated list of repo-relative paths to rewrite.
    """

    def __init__(self, name: str, kind: str, value: Optional[str] = None,
                 paths: Sequence[str] = (), note: str = "", source: str = "env") -> None:
        self.name = name
        self.kind = kind
        self.value = value
        self.paths = tuple(paths)
        self.note = note
        self.source = source

    def label(self) -> str:
        return ("-D" if self.source == "prop" else "") + self.name

    def allowed_prefixes(self, settings: Dict[str, str], root: Path) -> Optional[Tuple[str, ...]]:
        """The repo-relative prefixes this escape authorizes, or None when it is not active."""
        raw = settings.get(self.name)
        if raw is None or not raw.strip():
            return None
        if self.kind == "flag":
            return self.paths if raw == self.value else None
        if self.kind == "set":
            return self.paths
        if self.kind == "dir":
            return _relative_prefixes([raw], root)
        if self.kind == "list":
            return _relative_prefixes([p for p in raw.split(",") if p.strip()], root)
        raise ValueError("unknown escape kind {!r}".format(self.kind))


def _relative_prefixes(values: Sequence[str], root: Path) -> Tuple[str, ...]:
    """Repo-relative forms of `values`, dropping any that land outside the repository.

    A destination outside the tree cannot dirty it, so it needs no allowance — and silently
    turning an absolute ``/tmp/...`` into a ``../..``-style prefix would authorize the wrong thing.
    """
    prefixes: List[str] = []
    for value in values:
        candidate = Path(value.strip())
        if not candidate.is_absolute():
            candidate = root / candidate
        try:
            relative = candidate.resolve().relative_to(root.resolve())
        except ValueError:
            continue
        prefixes.append(relative.as_posix())
    return tuple(prefixes)


# The registry. Every variable here is read by a test in src/jvmTest that writes when it is set;
# `git grep 'System.getenv'` over src/jvmTest is how to check for a new one.
ESCAPES = (
    Escape(
        "AFIELD_DOCS_ASSETS", "flag", value="1", paths=("public/docs/assets",),
        note="refreshes the committed docs-capture figures (docsAssetDir in TestProofDir.kt)",
    ),
    Escape(
        "FIELD_CITATIONS_WRITE", "flag", value="1", paths=("public/docs", "dev_docs"),
        note="rewrites advisory citation line numbers on the guarded pages",
    ),
    Escape(
        "FIELD_CITATIONS_ANCHOR", "list",
        note="anchors bare citations on the pages named in the variable",
    ),
    Escape(
        # `!= null` on the Kotlin side, so any value counts.
        "AFIELD_CALLER_AUDIT_WRITE", "set",
        paths=("src/jvmTest/resources/afield/graphics/close_node_caller_audit.txt",),
        note="rewrites the close-node caller-audit golden",
    ),
    # Directory-valued: the destination is whatever the operator exported.
    Escape("AFIELD_PROOF_DIR", "dir", note="collects proofDir() artifacts"),
    Escape("AFIELD_POLYTOPE_PROOF_DIR", "dir", note="collects polytope proofs"),
    Escape("AFIELD_POINTER_CAPTURE_DIR", "dir", note="collects pointer-capture frames"),
    Escape("AFIELD_IMAGING_VIZ_DIR", "dir", note="imaging-visualizer capture destination"),
    Escape("AFIELD_TESSELLATION_RENDER_OUT", "dir", note="tessellation render destination"),
    Escape("AFIELD_FIGURES_OUT", "dir", note="figure-render destination"),
    # System properties, forwarded to the check by the Gradle task. `build.gradle.kts` already
    # forwards every `-Dafield.*` to `Test` tasks, so these are reachable from the command line.
    Escape(
        "afield.rectsl.headingfigure", "flag", value="write", source="prop",
        paths=("public/docs/assets/rectsl/rectsl-heading-lowering.png",),
        note="rewrites the heading-lowering figure",
    ),
    Escape(
        "afield.rectsl.fitfigure", "flag", value="write", source="prop",
        paths=("public/docs/assets/rectsl/rectsl-inverse-animation.png",),
        note="rewrites the inverse-animation figure",
    ),
    Escape(
        "afield.rectsl.characterization", "flag", value="write", source="prop",
        paths=("src/jvmTest/resources/rectsl/track_characterization.txt",),
        note="rewrites the track-sampling golden",
    ),
    Escape("afield.rectsl.softRasterOut", "dir", source="prop",
           note="soft-raster figure destination, resolved against the repo root"),
    Escape("afield.tessellation.render.out", "dir", source="prop",
           note="tessellation render destination"),
    Escape("afield.physics.captureDir", "dir", source="prop",
           note="physics capture destination"),
    # The general escape, for a deliberate one-off that no register entry covers — a new
    # `-D`-gated writer, or a regeneration being tried out before it has a name. Naming the paths
    # is the point: it authorizes those and nothing else, so it cannot become a blanket "off".
    Escape(
        "AFIELD_ALLOW_TREE_WRITES", "list",
        note="explicitly authorized paths for this run",
    ),
)


def _escape_prefixes(environ: Dict[str, str], root: Path,
                     properties: Optional[Dict[str, str]] = None) -> Dict[str, Tuple[str, ...]]:
    """Every active escape, mapped by its display label to the prefixes it authorizes."""
    sources = {"env": environ, "prop": properties or {}}
    active: Dict[str, Tuple[str, ...]] = {}
    for escape in ESCAPES:
        prefixes = escape.allowed_prefixes(sources[escape.source], root)
        if prefixes is not None:
            active[escape.label()] = prefixes
    return active


def _covered(path: str, prefixes: Sequence[str]) -> bool:
    return any(path == prefix or path.startswith(prefix + "/") for prefix in prefixes)


def repo_root(explicit: Optional[str] = None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    out = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        check=True, stdout=subprocess.PIPE, text=True,
    )
    return Path(out.stdout.strip()).resolve()


def _git(root: Path, *args: str) -> bytes:
    return subprocess.run(
        ["git"] + list(args), cwd=str(root), check=True, stdout=subprocess.PIPE
    ).stdout


def gitlink_paths(root: Path) -> List[str]:
    """Submodule paths, which this guard deliberately ignores.

    A gitlink is a pointer into another repository with its own build outputs; `services/field-audio-ai`
    goes dirty whenever anything runs in it, and that is that checkout's business, not a test writing
    into this tree. Its directory mtime moves for the same reason.
    """
    paths: List[str] = []
    for line in _git(root, "ls-files", "-s", "-z").split(b"\0"):
        if not line:
            continue
        meta, _, name = line.partition(b"\t")
        if meta.split(b" ")[0] == b"160000":
            paths.append(name.decode("utf-8", "surrogateescape"))
    return paths


def porcelain_status(root: Path) -> Dict[str, str]:
    """Repo-relative path -> two-letter status code, from `git status --porcelain -uall`.

    `-uall` rather than the default: an untracked *directory* collapses to `dir/`, and the whole
    point of the report is to name the file a test wrote.
    """
    raw = _git(root, "status", "--porcelain", "-uall", "-z")
    fields = raw.split(b"\0")
    status: Dict[str, str] = {}
    index = 0
    while index < len(fields):
        entry = fields[index]
        index += 1
        if not entry:
            continue
        code = entry[:2].decode("ascii", "replace")
        path = entry[3:].decode("utf-8", "surrogateescape")
        # A rename or copy carries its origin path in the following NUL-separated field.
        if code and code[0] in ("R", "C"):
            index += 1
        status[path] = code
    return status


def tracked_stats(root: Path, skip: Sequence[str] = ()) -> Dict[str, str]:
    """Repo-relative path -> "mtime_ns:size" for every tracked file.

    This is the half that sees a rewrite whose bytes did not change. ~5k files, ~60ms.
    """
    skipped = frozenset(skip)
    stats: Dict[str, str] = {}
    for name in _git(root, "ls-files", "-z").split(b"\0"):
        if not name:
            continue
        path = name.decode("utf-8", "surrogateescape")
        if path in skipped:
            continue
        try:
            info = os.lstat(str(root / path))
        except OSError:
            stats[path] = "missing"
            continue
        stats[path] = "{}:{}".format(info.st_mtime_ns, info.st_size)
    return stats


def snapshot(root: Path) -> dict:
    gitlinks = gitlink_paths(root)
    return {
        "version": SNAPSHOT_VERSION,
        "root": str(root),
        "gitlinks": gitlinks,
        "status": porcelain_status(root),
        "tracked": tracked_stats(root, skip=gitlinks),
    }


class Finding:
    def __init__(self, path: str, kind: str, detail: str) -> None:
        self.path = path
        self.kind = kind
        self.detail = detail

    def describe(self) -> str:
        return "  {:<64} {}".format(self.path, self.detail)


def compare(before: dict, after: dict, ignore: Sequence[str] = ()) -> List[Finding]:
    """Every write into the tree between the two snapshots, most-diagnosable kind first.

    `ignore` holds repo-relative paths the guard writes itself. The baseline lands in `build/`,
    which this repository ignores, so in practice it is never seen — but a guard that can accuse
    a caller of its own bookkeeping is one confusing failure away from being switched off, and
    the exclusion costs a line.
    """
    skipped = (frozenset(before.get("gitlinks", ())) | frozenset(after.get("gitlinks", ()))
               | frozenset(ignore))
    before_status = before["status"]
    after_status = after["status"]
    before_tracked = before["tracked"]
    after_tracked = after["tracked"]

    findings: List[Finding] = []
    for path in sorted(after_status):
        if path in skipped:
            continue
        code = after_status[path]
        was = before_status.get(path)
        if was == code:
            continue
        if code == "??":
            findings.append(Finding(path, "untracked", "new untracked file"))
        elif was is None:
            findings.append(Finding(path, "dirtied", "now dirty ({}), was clean".format(code.strip())))
        else:
            findings.append(Finding(
                path, "dirtied", "status changed {} -> {}".format(was.strip(), code.strip())))

    dirtied = {f.path for f in findings}
    for path in sorted(after_tracked):
        if path in dirtied or path in skipped:
            continue
        was = before_tracked.get(path)
        now = after_tracked[path]
        if was is None or was == now:
            continue
        if now == "missing":
            findings.append(Finding(path, "deleted", "tracked file disappeared"))
        elif path in before_status:
            # Already dirty at the baseline and rewritten since. The porcelain code did not move,
            # so this says nothing about the *content* — claiming "identical bytes" here would be
            # a lie, because a file that was `M` before and is `M` now may hold anything.
            findings.append(Finding(
                path, "rewritten-dirty",
                "rewritten while already dirty ({}) — git status cannot say what changed".format(
                    before_status[path].strip())))
        else:
            # The invisible case: clean before, clean after, and written anyway. The bytes match
            # what is committed, so no diff exists to notice.
            findings.append(Finding(
                path, "rewritten", "rewritten with identical bytes (invisible to git status)"))

    # A tracked path that was dirty before and is clean now was also written to.
    for path in sorted(before_status):
        if path in skipped or path in dirtied or path in after_status:
            continue
        if path in after_tracked:
            findings.append(Finding(path, "reverted", "was dirty ({}), now matches HEAD".format(
                before_status[path].strip())))
    return findings


def report(findings: Sequence[Finding], escapes: Dict[str, Tuple[str, ...]], label: str) -> str:
    lines = [
        "{} wrote into the Git working tree.".format(label),
        "",
        "A test must not write into the repository it is running in. Render into a temp directory"
        " and inspect it there — see proofDir()/docsAssetDir() in"
        " src/jvmTest/kotlin/afield/TestProofDir.kt — or, if the artifact really is a committed one,"
        " gate the write behind an opt-in variable and register it in tools/check_test_tree_writes.py.",
        "",
    ]
    kinds = [
        ("untracked", "left behind ({} file(s)):"),
        ("dirtied", "modified committed files ({}):"),
        ("rewritten", "rewrote committed files with no change in bytes ({}):"),
        ("rewritten-dirty", "rewrote files that were already modified ({}):"),
        ("reverted", "wrote committed files back to their HEAD contents ({}):"),
        ("deleted", "deleted committed files ({}):"),
    ]
    for kind, heading in kinds:
        rows = [f for f in findings if f.kind == kind]
        if not rows:
            continue
        lines.append(heading.format(len(rows)))
        lines.extend(f.describe() for f in rows)
        lines.append("")
    if any(f.kind == "rewritten" for f in findings):
        lines.extend([
            "The 'no change in bytes' rows are the reason this guard reads mtimes and not just"
            " `git status`: an unconditional write is a defect whether or not the renderer happens"
            " to be deterministic today. Three of the five vector_algebra PNGs were exactly this"
            " before 2026-09-04, and no `git status` would ever have shown them.",
            "",
        ])
    if escapes:
        notes = {escape.label(): escape.note for escape in ESCAPES}
        lines.append("Active write escapes (their paths were allowed):")
        for name in sorted(escapes):
            lines.append("  {} -> {}  [{}]".format(
                name, ", ".join(escapes[name]) or "(nothing in this tree)",
                notes.get(name, "")))
        lines.append("")
    lines.append(
        "If a path above is *yours* — an edit made while the tests were running — this guard cannot"
        " tell that apart from a test write. Re-run the check to confirm."
    )
    return "\n".join(lines)


def _parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--snapshot", metavar="FILE", help="record the pre-test tree state")
    mode.add_argument("--check", metavar="FILE", help="compare the tree against a recorded snapshot")
    parser.add_argument("--root", help="repository root (default: git rev-parse --show-toplevel)")
    parser.add_argument("--label", default="the test run",
                        help="what ran between the two snapshots, for the failure message")
    parser.add_argument("--property", metavar="NAME=VALUE", action="append", default=[],
                        dest="properties",
                        help="a JVM system property the test run was given; the Gradle task "
                             "forwards every -Dafield.* so a property-gated regeneration can be "
                             "recognized from this process")
    parser.add_argument("--ran-dir", metavar="DIR",
                        help="directory holding one marker file per test task that executed; "
                             "with --snapshot it is emptied, with --check it names the offender "
                             "and distinguishes a real pass from a build that ran no tests")
    return parser.parse_args(argv)


def ran_labels(ran_dir: Optional[str], root: Path) -> Optional[List[str]]:
    """The test tasks that executed, or None when the caller did not track them."""
    if not ran_dir:
        return None
    directory = Path(ran_dir)
    if not directory.is_absolute():
        directory = root / directory
    if not directory.is_dir():
        return []
    return sorted(entry.name for entry in directory.iterdir() if entry.is_file())


def main(argv: Optional[List[str]] = None) -> int:
    args = _parse_args(list(sys.argv[1:] if argv is None else argv))
    root = repo_root(args.root)

    if args.snapshot:
        target = Path(args.snapshot)
        if not target.is_absolute():
            target = root / target
        target.parent.mkdir(parents=True, exist_ok=True)
        # Clear last build's markers first: a stale one would name a task that did not run.
        if args.ran_dir:
            marker_dir = Path(args.ran_dir)
            if not marker_dir.is_absolute():
                marker_dir = root / marker_dir
            if marker_dir.is_dir():
                for entry in marker_dir.iterdir():
                    if entry.is_file():
                        entry.unlink()
            else:
                marker_dir.mkdir(parents=True, exist_ok=True)
        state = snapshot(root)
        target.write_text(json.dumps(state, sort_keys=True))
        print("tree-write guard: baseline of {} tracked file(s), {} already dirty -> {}".format(
            len(state["tracked"]), len(state["status"]), target))
        return 0

    source = Path(args.check)
    if not source.is_absolute():
        source = root / source
    if not source.is_file():
        print("tree-write guard: no baseline at {} — nothing ran before the check, so it cannot "
              "tell a test write from a pre-existing one. This is a wiring failure, not a pass."
              .format(source))
        return 1
    before = json.loads(source.read_text())
    if before.get("version") != SNAPSHOT_VERSION:
        print("tree-write guard: baseline at {} is version {}, this build speaks {}. Delete it and "
              "re-run.".format(source, before.get("version"), SNAPSHOT_VERSION))
        return 1

    ran = ran_labels(args.ran_dir, root)
    if ran is not None and not ran:
        # Not a failure and not a pass: no test task executed, so there was nothing to observe.
        # Saying so is the whole point — this repository has been misled more than once by a gate
        # whose green was a verdict about a run that never happened.
        print("tree-write guard: no test task ran in this build, so this check observed nothing.")
        return 0
    label = ", ".join(ran) if ran else args.label

    after = snapshot(root)
    findings = compare(before, after, ignore=_relative_prefixes([str(source)], root))
    properties: Dict[str, str] = {}
    for item in args.properties:
        name, _, value = item.partition("=")
        properties[name] = value
    escapes = _escape_prefixes(dict(os.environ), root, properties)
    allowed = tuple(prefix for prefixes in escapes.values() for prefix in prefixes)
    blocked = [f for f in findings if not _covered(f.path, allowed)]
    excused = len(findings) - len(blocked)

    if not blocked:
        print("tree-write guard: {} left the working tree as it found it{}.".format(
            label,
            " ({} write(s) allowed by an active escape)".format(excused) if excused else ""))
        return 0

    print(report(blocked, escapes, label))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
