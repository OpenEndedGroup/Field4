#!/usr/bin/env python3
"""Answer "does that published figure still match the code?" — per pixel, above the noise floor.

Why this exists
---------------
A normal docs build cannot answer that question. The capture cache digest
(`public/docs_hooks.py`, `compute_digest`) is keyed on the fence's code text, its options, the page,
`SCRIPT_VERSION`, `sceneInputs`, and `runnerSourceDigest` — and `runnerSourceDigest` hashes a fixed
list of runner-machinery files. Edit a stdlib file a fence actually *calls* and (before the
`sourceRefsDigest` work in the same commit series as this tool) every digest was unchanged, so an
incremental build was a 100% cache hit and re-executed nothing. 54 published `functions_examples`
figures were wrong on `main` for two weeks and no docs build ever said so.

This tool re-derives captures from the real code (`FIELD_DOCS_RUNNER_MODE=always`, which sets
`cache_allowed = False` per capture) and then classifies what came out.

Why the comparison is not `cmp`
-------------------------------
Captures are neither byte- nor pixel-reproducible. Measured (see `dev_docs/docs_capture_staleness.md`):
only 5 of 121 captures are byte-stable run to run; the rest carry text/antialiasing jitter of
~0.005–0.01% of pixels at a maximum channel delta of ≤19. Genuine re-renders sit at 0.25–1.5% of
pixels at Δ~247. A few plot captures are *legitimately* nondeterministic in that upper band, and four
`capture=box` shots have an unstable output height. So:

* compare per pixel with a threshold, never by bytes;
* handle differing image dimensions as its own signal;
* with `--repeat 2`, also compare run 1 against run 2, which separates "this capture is
  nondeterministic" from "this capture no longer matches the code". That distinction is the whole
  point — without it the nondeterministic plots read as stale and the report is noise.

Pillow trap, from the same doc: `Image.getbbox()` defaults to `alpha_only=True` on RGBA, so it
returns `None` — indistinguishable from "identical" — whenever only RGB moved. This uses
`ImageChops.difference(...)` on images converted to `RGB`, reduced across bands, and reads an exact
histogram.

Usage
-----
    # audit one page, rigorously (two capture runs)
    uv run --group docs python tools/docs_capture_audit.py --page functions_examples --repeat 2

    # what is oldest, and therefore most suspect? (no JVM, instant)
    uv run --group docs python tools/docs_capture_audit.py --inventory

    # which source files does each fence reach, and what would touching one invalidate?
    uv run --group docs python tools/docs_capture_audit.py --plan --page functions_examples

The tree is restored after every run (tracked files via `git checkout --`, untracked by deletion)
unless `--keep` is passed; every regenerated file is kept under the run directory either way, so a
human can look at the actual before/after.
"""

from __future__ import annotations

import argparse
import difflib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence

REPO_ROOT = Path(__file__).resolve().parent.parent
PUBLIC_DIR = REPO_ROOT / "public"
DOCS_DIR = PUBLIC_DIR / "docs"

# The measured noise floor, straight from dev_docs/docs_capture_staleness.md: run-to-run
# text/antialiasing jitter is ~0.005-0.01% of pixels at a maximum channel delta of <=19.
DEFAULT_JITTER_PX_PCT = 0.02
DEFAULT_JITTER_DELTA = 19
# The lower edge of the band where the same doc saw genuine re-renders. Only used to label a
# delta in the report ("grey zone" between the noise floor and a confident re-render).
DRIFT_PX_PCT = 0.25

PIXEL_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
# First-frame-only for Pillow; flagged in the note so nobody reads a clean animation as proof.
ANIMATION_SUFFIXES = {".apng", ".gif"}
OPAQUE_BINARY_SUFFIXES = {".mp4", ".mov", ".wav", ".exr", ".glb"}

FAILURE_RE = re.compile(
    r"capture FAILED(?:, falling back to committed asset on disk| with NO asset on disk[^:]*): "
    r"(?P<page>[^ ]+) -> (?P<path>[^:]+):"
)


# ── deltas ──────────────────────────────────────────────────────────────────────────────────────


@dataclass
class Delta:
    """How far apart two versions of one asset are."""

    kind: str  # identical | pixel | size | binary | text | missing
    changed_px_pct: float = 0.0
    above_band_px_pct: float = 0.0
    max_delta: int = 0
    old_size: tuple[int, int] | None = None
    new_size: tuple[int, int] | None = None
    old_bytes: int = 0
    new_bytes: int = 0
    text_lines_changed: int = 0
    note: str = ""

    def summary(self) -> str:
        if self.kind == "identical":
            return "identical"
        if self.kind == "size":
            return f"size {self.old_size} -> {self.new_size}"
        if self.kind == "pixel":
            return f"{self.changed_px_pct:.4f}% px changed, maxΔ{self.max_delta}"
        if self.kind == "text":
            return f"{self.text_lines_changed} line(s) changed"
        if self.kind == "binary":
            return f"bytes {self.old_bytes} -> {self.new_bytes}"
        return self.kind

    def within(self, *, px_pct: float, max_delta: int) -> bool:
        """True when this delta is inside the measured jitter band (i.e. not evidence of anything)."""
        if self.kind == "identical":
            return True
        if self.kind != "pixel":
            return False
        return self.changed_px_pct <= px_pct and self.max_delta <= max_delta


def compare_bytes(old: bytes | None, new: bytes | None, *, suffix: str) -> Delta:
    if old is None or new is None:
        return Delta("missing", note="no committed version" if old is None else "no regenerated version")
    if old == new:
        return Delta("identical", old_bytes=len(old), new_bytes=len(new))
    lowered = suffix.lower()
    if lowered in PIXEL_SUFFIXES or lowered in ANIMATION_SUFFIXES:
        delta = compare_images(old, new)
        if lowered in ANIMATION_SUFFIXES and delta.kind in {"pixel", "identical"}:
            delta.note = (delta.note + " first frame only").strip()
        delta.old_bytes, delta.new_bytes = len(old), len(new)
        return delta
    if lowered in OPAQUE_BINARY_SUFFIXES:
        return Delta("binary", old_bytes=len(old), new_bytes=len(new), note="no pixel comparison for this format")
    return compare_text(old, new)


def compare_images(old: bytes, new: bytes) -> Delta:
    from PIL import Image, ImageChops  # imported lazily so --inventory/--plan need no Pillow

    with Image.open(io.BytesIO(old)) as old_image, Image.open(io.BytesIO(new)) as new_image:
        old_size, new_size = old_image.size, new_image.size
        if old_size != new_size:
            return Delta("size", old_size=old_size, new_size=new_size)
        old_rgb = old_image.convert("RGB")
        new_rgb = new_image.convert("RGB")

    diff = ImageChops.difference(old_rgb, new_rgb)
    bands = diff.split()
    worst = bands[0]
    for extra in bands[1:]:
        worst = ImageChops.lighter(worst, extra)
    histogram = worst.histogram()
    total = max(1, old_size[0] * old_size[1])
    changed = total - histogram[0]
    max_delta = max((value for value, count in enumerate(histogram) if count), default=0)
    return Delta(
        "identical" if changed == 0 else "pixel",
        changed_px_pct=100.0 * changed / total,
        max_delta=max_delta,
        old_size=old_size,
        new_size=new_size,
    )


def compare_text(old: bytes, new: bytes) -> Delta:
    old_lines = old.decode("utf-8", errors="replace").splitlines()
    new_lines = new.decode("utf-8", errors="replace").splitlines()
    changed = sum(
        1
        for line in difflib.unified_diff(old_lines, new_lines, n=0)
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    )
    return Delta("text", text_lines_changed=changed, old_bytes=len(old), new_bytes=len(new))


# ── classification ──────────────────────────────────────────────────────────────────────────────

# Ordered worst-first; the report and the exit code follow this order.
VERDICT_ORDER = (
    "stale",
    "stale-size",
    "stale-text",
    "stale-binary",
    "suspect",
    "unknown-failed",
    "nondeterministic",
    "nondeterministic-size",
    "nondeterministic-binary",
    "jitter",
    "identical",
    "new",
)


def classify(*, vs_head: Delta, self_delta: Delta | None, px_pct: float, max_delta: int) -> tuple[str, str]:
    """Return (verdict, why). `self_delta` is run1-vs-run2 and is None with --repeat 1."""
    if vs_head.kind == "missing":
        return "new", "asset is not committed at HEAD"
    if vs_head.kind == "identical":
        return "identical", "byte-identical to the committed asset"
    if vs_head.within(px_pct=px_pct, max_delta=max_delta):
        return "jitter", f"inside the measured noise band ({vs_head.summary()})"

    unstable = self_delta is not None and not self_delta.within(px_pct=px_pct, max_delta=max_delta)
    if vs_head.kind == "size":
        if unstable:
            return "nondeterministic-size", f"height/width unstable run to run ({self_delta.summary()})"
        return "stale-size", f"dimensions moved and are stable run to run ({vs_head.summary()})"
    if vs_head.kind == "binary":
        if self_delta is None:
            return "suspect", f"binary format, single run ({vs_head.summary()})"
        if self_delta.kind != "identical":
            return "nondeterministic-binary", "re-encodes differently run to run"
        return "stale-binary", "two runs agree with each other and disagree with the committed asset"
    if vs_head.kind == "text":
        if unstable or (self_delta is not None and self_delta.kind != "identical"):
            return "nondeterministic", f"text output unstable run to run ({self_delta.summary()})"
        return "stale-text", f"deterministic text output moved ({vs_head.summary()})"

    if unstable:
        return (
            "nondeterministic",
            f"this capture does not reproduce itself (run1 vs run2: {self_delta.summary()})",
        )
    if self_delta is None:
        band = "above the noise band" if vs_head.changed_px_pct < DRIFT_PX_PCT else "a different render"
        return "suspect", f"{band} on a single run ({vs_head.summary()}); re-run with --repeat 2 to confirm"
    return "stale", f"reproducible re-render disagreeing with the committed asset ({vs_head.summary()})"


# ── fence enumeration ───────────────────────────────────────────────────────────────────────────


@dataclass
class Fence:
    page: Path
    block_index: int
    line_number: int
    code: str
    language_key: str
    outputs: tuple[Path, ...]


def import_docs_hooks() -> Any:
    if str(PUBLIC_DIR) not in sys.path:
        sys.path.insert(0, str(PUBLIC_DIR))
    import docs_hooks  # type: ignore[import-not-found]

    return docs_hooks


def iter_fences(pages: Sequence[Path]) -> Iterator[Fence]:
    """Yield every runner fence on `pages`, with the asset paths it writes.

    The scanning loop mirrors `docs_hooks.process_markdown`; everything semantic (the fence regexes,
    option parsing, code extraction, output-path resolution) is imported from `docs_hooks` rather
    than reimplemented. An audit run cross-checks the enumerated output set against the set the real
    build actually rewrote and warns on any file the enumeration missed, so drift here is loud.
    """
    hooks = import_docs_hooks()
    for page in pages:
        lines = page.read_text(encoding="utf-8").splitlines(keepends=True)
        block_index = 0
        passthrough: str | None = None
        previous_by_language: dict[str, str] = {}
        index = 0
        while index < len(lines):
            line = lines[index]
            if passthrough is not None:
                if re.match(rf"^[ ]{{0,3}}{re.escape(passthrough)}\s*(?:\r?\n)?$", line):
                    passthrough = None
                index += 1
                continue
            open_match = hooks.FENCE_OPEN_RE.match(line)
            if open_match is None:
                generic = hooks.GENERIC_FENCE_OPEN_RE.match(line)
                if generic is not None:
                    passthrough = generic.group("fence")
                index += 1
                continue

            start_line = index + 1
            block_index += 1
            fence_name = open_match.group("runner")
            index += 1
            body: list[str] = []
            closing_re = re.compile(rf"^[ ]{{0,3}}{re.escape(open_match.group('fence'))}\s*(?:\r?\n)?$")
            closed = False
            while index < len(lines):
                candidate = lines[index]
                index += 1
                if closing_re.match(candidate):
                    closed = True
                    break
                body.append(candidate)
            if not closed:
                raise RuntimeError(f"Unclosed {fence_name} fence in {page}:{start_line}")
            if fence_name not in hooks.RUNNER_FENCES:
                continue

            language_key = hooks.fence_language_key(open_line=line, fence_name=fence_name)
            body = hooks.maybe_inherit_before(
                lines=body,
                fence_name=fence_name,
                previous_code=previous_by_language.get(language_key),
            )
            options = hooks.parse_runner_options(
                runner_name=fence_name,
                open_line=line,
                page_path=page,
                line_number=start_line,
                block_index=block_index,
            )
            code = hooks.extract_runnable_code(
                lines=body,
                fence_name=fence_name,
                page_path=page,
                line_number=start_line,
                options=options,
            )
            code = hooks.wrap_runnable_code_for_language(code=code, language_key=language_key)
            previous_by_language[language_key] = code
            outputs = tuple(
                hooks.resolve_output_path(page, value)
                for value in (options.screenshot, options.video, options.audio)
                if value
            )
            yield Fence(
                page=page,
                block_index=block_index,
                line_number=start_line,
                code=code,
                language_key=language_key,
                outputs=outputs,
            )


def select_pages(filters: Sequence[str]) -> list[Path]:
    hooks = import_docs_hooks()
    sys.path.insert(0, str(REPO_ROOT / "tools"))
    import build_zensical_docs  # type: ignore[import-not-found]

    del hooks
    pages = []
    for path in sorted(DOCS_DIR.rglob("*.md")):
        relative = path.relative_to(DOCS_DIR)
        if filters and not build_zensical_docs.matches_page_filter(relative, tuple(filters)):
            continue
        pages.append(path)
    return pages


def pages_with_fences(filters: Sequence[str]) -> list[Path]:
    return sorted({fence.page for fence in iter_fences(select_pages(filters))})


# ── git helpers ─────────────────────────────────────────────────────────────────────────────────


def git(*args: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if check and completed.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout


def head_blob(relative: str) -> bytes | None:
    completed = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "show", f"HEAD:{relative}"],
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout


def dirty_paths(scope: str) -> tuple[list[str], list[str]]:
    """(tracked-modified, untracked) repo-relative paths under `scope`."""
    modified: list[str] = []
    untracked: list[str] = []
    for raw in git("status", "--porcelain", "--", scope).splitlines():
        if not raw.strip():
            continue
        status, path = raw[:2], raw[3:].strip()
        if path.startswith('"') and path.endswith('"'):
            path = json.loads(path)
        if status == "??":
            untracked.append(path)
        else:
            modified.append(path)
    return modified, untracked


# ── the audit run ───────────────────────────────────────────────────────────────────────────────


@dataclass
class RunResult:
    index: int
    log_path: Path
    returncode: int
    modified: list[str]
    untracked: list[str]
    failures: dict[str, str]
    seconds: float
    captured: dict[str, Path] = field(default_factory=dict)


def uv_executable() -> str:
    found = shutil.which("uv")
    if found:
        return found
    fallback = Path("/opt/homebrew/Caskroom/miniconda/base/bin/uv")
    if fallback.exists():
        return str(fallback)
    return ""


def build_command(pages: Sequence[str]) -> list[str]:
    script = "tools/build_zensical_docs.py"
    uv = uv_executable()
    base = [uv, "run", "--group", "docs", "python", script] if uv else [sys.executable, script]
    command = [*base, "--skip-build"]
    for page in pages:
        command += ["--page", page]
    return command


def run_captures(*, pages: Sequence[str], port: int, run_dir: Path, index: int, echo: bool) -> RunResult:
    log_path = run_dir / f"run{index}.log"
    command = build_command(pages)
    env = dict(os.environ)
    env.update(
        {
            "FIELD_DOCS_RUNNER_MODE": "always",
            "FIELD_DOCS_RUNNER_PORT": str(port),
            # One broken capture must not abort a sweep; a failure is reported as `unknown-failed`.
            "FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR": "1",
            "FIELD_DOCS_LOG_LEVEL": "INFO",
        }
    )
    started = time.monotonic()
    failures: dict[str, str] = {}
    print(f"[audit] run {index}: {' '.join(command)}", flush=True)
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command,
            cwd=REPO_ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            log.write(line)
            match = FAILURE_RE.search(line)
            if match:
                path = match.group("path").strip()
                try:
                    failures[str(Path(path).resolve().relative_to(REPO_ROOT))] = line.strip()
                except ValueError:
                    failures[path] = line.strip()
            if echo:
                print(f"    | {line.rstrip()}", flush=True)
        returncode = process.wait()
    seconds = time.monotonic() - started
    modified, untracked = dirty_paths("public/docs")
    return RunResult(
        index=index,
        log_path=log_path,
        returncode=returncode,
        modified=modified,
        untracked=untracked,
        failures=failures,
        seconds=seconds,
    )


def stash_run_outputs(result: RunResult, run_dir: Path) -> None:
    target_root = run_dir / f"run{result.index}"
    for relative in [*result.modified, *result.untracked]:
        source = REPO_ROOT / relative
        if not source.is_file():
            continue
        destination = target_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        result.captured[relative] = destination


def restore(result: RunResult, *, protected: Iterable[str]) -> None:
    protected_set = set(protected)
    tracked = [path for path in result.modified if path not in protected_set]
    if tracked:
        for start in range(0, len(tracked), 200):
            git("checkout", "--", *tracked[start : start + 200])
    for path in result.untracked:
        if path in protected_set:
            continue
        target = REPO_ROOT / path
        if target.is_file():
            target.unlink()


# ── reporting ───────────────────────────────────────────────────────────────────────────────────


def build_report(
    *,
    runs: list[RunResult],
    expected: dict[str, list[str]],
    px_pct: float,
    max_delta: int,
) -> dict[str, Any]:
    primary = runs[0]
    second = runs[1] if len(runs) > 1 else None
    touched = sorted({*primary.modified, *primary.untracked, *(second.modified if second else []), *(second.untracked if second else [])})
    owner = {asset: page for page, assets in expected.items() for asset in assets}
    all_expected = sorted(owner)

    entries: list[dict[str, Any]] = []
    for relative in sorted(set(touched) | set(all_expected)):
        suffix = Path(relative).suffix
        failed = primary.failures.get(relative)
        committed = head_blob(relative)
        regenerated_path = primary.captured.get(relative)
        regenerated = regenerated_path.read_bytes() if regenerated_path else None

        if regenerated is None:
            # Not rewritten by the run. Either the capture is byte-stable, or it never ran.
            if failed:
                verdict, why = "unknown-failed", f"capture failed; committed asset left in place ({failed[:160]})"
                vs_head = Delta("missing", note="capture failed")
                self_delta = None
            elif committed is None:
                continue
            else:
                verdict, why = "identical", "byte-identical to the committed asset"
                vs_head = Delta("identical", old_bytes=len(committed), new_bytes=len(committed))
                self_delta = None
        else:
            vs_head = compare_bytes(committed, regenerated, suffix=suffix)
            self_delta = None
            if second is not None:
                other = second.captured.get(relative)
                if other is not None:
                    self_delta = compare_bytes(regenerated, other.read_bytes(), suffix=suffix)
                elif committed is not None:
                    # Run 2 did not rewrite it, so run 2's output == the committed asset.
                    self_delta = compare_bytes(regenerated, committed, suffix=suffix)
            verdict, why = classify(vs_head=vs_head, self_delta=self_delta, px_pct=px_pct, max_delta=max_delta)
            if failed:
                verdict, why = "unknown-failed", f"capture failed ({failed[:160]})"

        entries.append(
            {
                "asset": relative,
                "page": owner.get(relative, "?"),
                "verdict": verdict,
                "why": why,
                "vsHead": vs_head.__dict__,
                "selfDelta": self_delta.__dict__ if self_delta else None,
                "regeneratedCopy": str(regenerated_path) if regenerated_path else None,
            }
        )

    unexpected = sorted(set(touched) - set(all_expected))
    entries.sort(key=lambda item: (VERDICT_ORDER.index(item["verdict"]), item["asset"]))
    counts: dict[str, int] = {}
    for item in entries:
        counts[item["verdict"]] = counts.get(item["verdict"], 0) + 1
    return {
        "runs": [
            {"index": run.index, "returncode": run.returncode, "seconds": round(run.seconds, 1), "log": str(run.log_path), "rewrote": len(run.modified) + len(run.untracked), "failures": len(run.failures)}
            for run in runs
        ],
        "thresholds": {"jitterPxPct": px_pct, "jitterMaxDelta": max_delta},
        "counts": counts,
        "entries": entries,
        "assetsNotEnumerated": unexpected,
    }


def print_report(report: dict[str, Any], *, limit: int) -> None:
    print()
    print("=" * 96)
    for run in report["runs"]:
        print(f"run {run['index']}: exit {run['returncode']}  {run['seconds']}s  rewrote {run['rewrote']} asset(s)  failures {run['failures']}  log {run['log']}")
    thresholds = report["thresholds"]
    print(f"noise band: <= {thresholds['jitterPxPct']}% of pixels at maxΔ <= {thresholds['jitterMaxDelta']}")
    print("-" * 96)
    for verdict in VERDICT_ORDER:
        count = report["counts"].get(verdict)
        if count:
            print(f"{verdict:>24}  {count}")
    print("-" * 96)
    shown = 0
    for item in report["entries"]:
        if item["verdict"] in {"identical", "jitter"}:
            continue
        if shown >= limit:
            print(f"    … {len([e for e in report['entries'] if e['verdict'] not in {'identical', 'jitter'}]) - shown} more (see the JSON report)")
            break
        print(f"  [{item['verdict']}] {item['asset']}")
        print(f"      {item['why']}")
        shown += 1
    if report["assetsNotEnumerated"]:
        print(f"  NOTE: {len(report['assetsNotEnumerated'])} rewritten asset(s) were not predicted by fence enumeration:")
        for path in report["assetsNotEnumerated"][:10]:
            print(f"      {path}")
    print("=" * 96)


def write_markdown(report: dict[str, Any], path: Path) -> None:
    lines = ["# docs capture freshness audit", ""]
    for run in report["runs"]:
        lines.append(f"- run {run['index']}: exit {run['returncode']}, {run['seconds']}s, rewrote {run['rewrote']}, failures {run['failures']}")
    thresholds = report["thresholds"]
    lines += [
        "",
        f"Noise band: <= {thresholds['jitterPxPct']}% of pixels at max channel delta <= {thresholds['jitterMaxDelta']}.",
        "",
        "| verdict | count |",
        "| --- | --- |",
    ]
    for verdict in VERDICT_ORDER:
        count = report["counts"].get(verdict)
        if count:
            lines.append(f"| {verdict} | {count} |")
    lines += ["", "| asset | verdict | vs HEAD | run1 vs run2 |", "| --- | --- | --- | --- |"]
    for item in report["entries"]:
        if item["verdict"] in {"identical", "jitter"}:
            continue
        vs_head = Delta(**item["vsHead"]).summary()
        self_delta = Delta(**item["selfDelta"]).summary() if item["selfDelta"] else "—"
        lines.append(f"| `{item['asset']}` | {item['verdict']} | {vs_head} | {self_delta} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ── inventory mode ──────────────────────────────────────────────────────────────────────────────


def asset_last_commit_dates() -> dict[str, str]:
    """repo-relative asset path -> the date of the commit that last touched it."""
    dates: dict[str, str] = {}
    current = ""
    stream = git("log", "--format=@%ad", "--date=short", "--name-only", "--", "public/docs/assets")
    for line in stream.splitlines():
        if line.startswith("@"):
            current = line[1:]
        elif line.strip() and line not in dates:
            dates[line] = current
    return dates


def run_inventory(filters: Sequence[str]) -> int:
    dates = asset_last_commit_dates()
    expected = {}
    for fence in iter_fences(select_pages(filters)):
        for output in fence.outputs:
            try:
                relative = str(output.resolve().relative_to(REPO_ROOT))
            except ValueError:
                continue
            expected[relative] = str(fence.page.relative_to(DOCS_DIR))

    by_page: dict[str, list[tuple[str, str]]] = {}
    for asset, page in expected.items():
        by_page.setdefault(page, []).append((dates.get(asset, "untracked"), asset))
    print(f"{len(expected)} capture asset(s) declared by {len(by_page)} page(s)\n")
    rows = []
    for page, assets in by_page.items():
        assets.sort()
        oldest = assets[0][0]
        newest = assets[-1][0]
        rows.append((oldest, page, len(assets), newest))
    rows.sort()
    print(f"{'oldest':<12} {'newest':<12} {'assets':>6}  page")
    for oldest, page, count, newest in rows:
        print(f"{oldest:<12} {newest:<12} {count:>6}  {page}")
    print("\nOldest capture assets overall (most suspect first):")
    flat = sorted((date, asset) for asset, page in expected.items() for date in [dates.get(asset, "untracked")])
    for date, asset in flat[:30]:
        print(f"  {date}  {asset}")
    return 0


# ── plan mode (blast radius of the per-fence source digest) ──────────────────────────────────────


def run_plan(filters: Sequence[str], *, touched: Sequence[str], mode: str) -> int:
    """What the new per-fence digest covers, and what touching a given source file would invalidate.

    No JVM, no captures — this is the blast-radius measurement for the digest change, and the answer
    to "would a docs build have noticed if I edited this file?".
    """
    hooks = import_docs_hooks()
    index = hooks.load_source_symbol_index(REPO_ROOT)
    print(f"symbol index: {len(index)} symbol(s) -> {len({f for files in index.values() for f in files})} source file(s); mode {mode}")
    fences = list(iter_fences(select_pages(filters)))
    per_file: dict[str, list[str]] = {}
    uncovered: list[str] = []
    total_refs = 0
    for fence in fences:
        files = hooks.fence_source_files(code=fence.code, symbol_index=index, mode=mode)
        total_refs += len(files)
        label = f"{fence.page.relative_to(DOCS_DIR)}#{fence.block_index}"
        if not files:
            uncovered.append(label)
        for source in files:
            per_file.setdefault(source, []).append(label)
    print(f"{len(fences)} fence(s); {total_refs / max(1, len(fences)):.1f} source file(s) per fence on average\n")
    print("source file -> fences that reach it (top 30):")
    for source, labels in sorted(per_file.items(), key=lambda item: -len(item[1]))[:30]:
        print(f"  {len(labels):>4}  {source}")
    if touched:
        print()
        for candidate in touched:
            hits = per_file.get(candidate, [])
            print(f"touching {candidate} would invalidate {len(hits)} of {len(fences)} fence(s)")
            for label in hits[:12]:
                print(f"    {label}")
    print(f"\n{len(uncovered)} fence(s) map to NO stdlib file (runner-machinery digest only)")
    for label in uncovered[:15]:
        print(f"    {label}")
    return 0


# ── entry point ─────────────────────────────────────────────────────────────────────────────────


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--page", action="append", default=[], metavar="PATTERN", help="Scope to matching docs pages (same matcher as build_zensical_docs.py --page); repeatable.")
    parser.add_argument("--repeat", type=int, default=1, help="Capture runs. 2 separates nondeterministic captures from genuinely stale ones (recommended).")
    parser.add_argument("--port", type=int, default=9540, help="Field server port for the capture runs (default 9540).")
    parser.add_argument("--run-dir", default="", help="Where to keep regenerated assets and logs (default: a timestamped dir under .field-backdoor/docs-audit).")
    parser.add_argument("--keep", action="store_true", help="Leave the regenerated assets in the working tree instead of restoring it.")
    parser.add_argument("--allow-dirty", action="store_true", help="Proceed even if public/docs already has uncommitted changes (they are never touched).")
    parser.add_argument("--jitter-px-pct", type=float, default=DEFAULT_JITTER_PX_PCT, help=f"Noise band: percent of pixels (default {DEFAULT_JITTER_PX_PCT}).")
    parser.add_argument("--jitter-max-delta", type=int, default=DEFAULT_JITTER_DELTA, help=f"Noise band: max channel delta (default {DEFAULT_JITTER_DELTA}).")
    parser.add_argument("--limit", type=int, default=60, help="How many non-clean assets to print (default 60).")
    parser.add_argument("--inventory", action="store_true", help="No capture: list capture assets by the date they were last committed.")
    parser.add_argument("--plan", action="store_true", help="No capture: show the per-fence source set the digest now covers.")
    parser.add_argument("--touched", action="append", default=[], metavar="PATH", help="With --plan: report how many fences touching this source file invalidates.")
    parser.add_argument("--mode", default="direct", choices=("direct", "stdlib", "off"), help="With --plan: which per-fence source coverage mode to model (FIELD_DOCS_SOURCE_REFS).")
    parser.add_argument("--list-pages", action="store_true", help="No capture: list pages that carry runner fences.")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.list_pages:
        for page in pages_with_fences(args.page):
            print(page.relative_to(DOCS_DIR))
        return 0
    if args.inventory:
        return run_inventory(args.page)
    if args.plan:
        return run_plan(args.page, touched=args.touched, mode=args.mode)

    pre_modified, pre_untracked = dirty_paths("public/docs")
    protected = set(pre_modified) | set(pre_untracked)
    if protected and not args.allow_dirty:
        print(f"refusing to audit: {len(protected)} uncommitted change(s) under public/docs. Commit, stash, or pass --allow-dirty.", file=sys.stderr)
        for path in sorted(protected)[:10]:
            print(f"    {path}", file=sys.stderr)
        return 2

    pages = pages_with_fences(args.page)
    if not pages:
        print("no docs pages with runner fences matched the filter", file=sys.stderr)
        return 2
    page_filters = [str(page.relative_to(DOCS_DIR)) for page in pages]
    expected: dict[str, list[str]] = {}
    for fence in iter_fences(pages):
        page_key = str(fence.page.relative_to(DOCS_DIR))
        for output in fence.outputs:
            try:
                expected.setdefault(page_key, []).append(str(output.resolve().relative_to(REPO_ROOT)))
            except ValueError:
                continue

    run_dir = Path(args.run_dir).resolve() if args.run_dir else REPO_ROOT / ".field-backdoor" / "docs-audit" / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)
    print(f"[audit] {len(pages)} page(s), {sum(len(v) for v in expected.values())} declared capture asset(s); run dir {run_dir}")

    runs: list[RunResult] = []
    try:
        for index in range(1, max(1, args.repeat) + 1):
            result = run_captures(pages=page_filters, port=args.port, run_dir=run_dir, index=index, echo=False)
            stash_run_outputs(result, run_dir)
            runs.append(result)
            print(f"[audit] run {index} finished in {result.seconds:.0f}s: exit {result.returncode}, rewrote {len(result.modified) + len(result.untracked)}, {len(result.failures)} failure(s)")
            if index < max(1, args.repeat) or not args.keep:
                restore(result, protected=protected)
    finally:
        if runs and not args.keep:
            restore(runs[-1], protected=protected)

    report = build_report(runs=runs, expected=expected, px_pct=args.jitter_px_pct, max_delta=args.jitter_max_delta)
    (run_dir / "report.json").write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")
    write_markdown(report, run_dir / "report.md")
    print_report(report, limit=args.limit)
    print(f"[audit] reports: {run_dir / 'report.json'} , {run_dir / 'report.md'}")

    stale = sum(count for verdict, count in report["counts"].items() if verdict.startswith("stale") or verdict == "suspect")
    return 1 if stale else 0


if __name__ == "__main__":
    raise SystemExit(main())
