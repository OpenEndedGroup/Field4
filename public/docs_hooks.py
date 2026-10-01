from __future__ import annotations

import atexit
import ast
import hashlib
import html
import json
import logging
import math
import os
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import zlib
from dataclasses import dataclass, replace
from contextlib import contextmanager
from functools import wraps
from types import SimpleNamespace
from pathlib import Path
from typing import Any
import urllib.error
import urllib.request
import ssl

TOOLS_DIR = Path(__file__).resolve().parent.parent / "tools"
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))
from verification_runtime import (
    automation_server_environment,
    automation_workspace,
    field_instance_info,
    field_instance_problem,
    run_with_watchdog,
    select_automation_port,
    waive_execution_verdict,
)
from field_languages import MAIN_LANGUAGE_ROOT_LANGUAGES
from verification_environment import VerificationEnvironment, VerificationEnvironmentError
from capture_transaction import CaptureTransaction

# Shared with tools/discover_field_tests.py; see public/fabricate_docs_scenes.py.
# public/ is already on sys.path when the Zensical build imports this module, so
# this stays a clean leaf import with no sys.path manipulation.
from fabricate_docs_scenes import (
    FABRICATE_DOCS_SCENES,
    compute_scene_inputs_digest,
    validate_scenes_against_harness,
)


LOG = logging.getLogger("zensical.field_runner")


def transactional_capture(method):
    @wraps(method)
    def capture(self, **kwargs):
        with self._capture_transaction():
            return method(self, **kwargs)
    return capture


RUNNER_TARGETS = {
    "diffstatic_runner": "diffstatic",
    "diff_runner": "diff",
    "diffstatic": "diffstatic",
    "diff": "diff",
}
RUNNER_FENCES = {"diffstatic_runner", "diff_runner"}
DIFF_FENCES = {"diff", "diff_runner"}
STATIC_FENCES = {"diffstatic", "diffstatic_runner"}
FENCE_NAMES = sorted(RUNNER_TARGETS, key=len, reverse=True)
SCRIPT_VERSION = 18
# One invocation is the verification contract. Repeating an unchanged capture can turn an
# infrastructure defect into a green build and destroy the first failure's evidence.
# What this covers is *process overhead*, not capture work: interpreter start, the Playwright
# attach, the WebSocket connect, and — for a graphics-context capture — the shared Field server
# restart each one performs. `options.timeout` budgets the per-command work inside the child; this
# is everything outside it, and at 5.0 it did not cover it.
#
# Measured 2026-08-21 on `afield_four_graphics.md`, six consecutive graphics-context captures with
# `options.timeout = 10.0` (so an outer limit of 15.2s): 14s, 12s, 12s, **15s**, 11s, 13s. The 15s
# one is `02-camera-matrices.png`, which cleared the old budget by about 0.2s — and which failed
# the nightly CI docs build twice that morning, once at 0/7 command results and once at 0/7 again
# from a different position in the run. A 1.01x margin on a gate that runs ~486 times is not a
# margin, and the symptom is a false red on correct code: the same six captures pass, and produce
# byte-identical PNGs, when the budget is not the binding constraint.
#
# Why it hid for so long: `PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC` raises the outer limit to 3m05s while
# `.field-backdoor/playwright-ready` is absent, so a fresh worktree's *first* capture — the one that
# creates the marker — always has room, and only captures 2..N feel the real budget.
#
# 20.0 keeps the fail-fast property this file cares about (a genuine hang is killed in ~30s, not
# 180s) while leaving ~2x headroom over the worst measured capture. It is deliberately not derived
# from the child's Playwright attach budget: `playwright_attach_budget` honours the caller's timeout
# once the browser is warm, so the two are consistent, and the remaining question is only how much
# non-command overhead a capture is allowed.
RUNNER_SUBPROCESS_GRACE_SEC = float(os.environ.get("FIELD_DOCS_SUBPROCESS_GRACE", "20"))
# Frame-synchronous video is intentionally allowed to run slower than playback. Budget aggregate
# wall time from frame count, not media seconds: sustained GPU/CI load may make one exact browser
# screenshot take substantially longer than 1/fps without making the capture unhealthy.
VIDEO_CAPTURE_SETUP_HEADROOM_SEC = float(os.environ.get("FIELD_DOCS_VIDEO_SETUP_HEADROOM", "150"))
VIDEO_CAPTURE_PER_FRAME_BUDGET_SEC = float(os.environ.get("FIELD_DOCS_VIDEO_PER_FRAME_BUDGET", "2"))
# A shared Field server gets one generous startup deadline. If it cannot become healthy inside that
# window, fail with its log rather than concealing the lifecycle defect behind another cold boot.
SERVER_START_DEADLINE_SEC = float(os.environ.get("AFIELD_DOCS_SERVER_START_TIMEOUT", "120"))
# Kept consistent with tools/field_backdoor.py: the first Playwright attach on a
# machine may download browser binaries. While that marker is absent, give the
# capture subprocess enough headroom to finish the download instead of killing it
# at ~50s during a legitimate first-time browser download.
PLAYWRIGHT_READY_MARKER = Path(__file__).resolve().parent.parent / ".field-backdoor" / "playwright-ready"
PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC = float(os.environ.get("AFIELD_PLAYWRIGHT_ATTACH_TIMEOUT", "180"))
# Opt-in warm-session capture path: reuse ONE persistent field_session daemon
# (JVM + WebSocket + Playwright page attached once) across non-graphics captures
# instead of spawning a fresh field_backdoor.py process per capture. Measured
# ~3.8x faster on non-graphics captures. Default OFF; the default docs build is
# byte-for-byte unchanged. Graphics-context and video captures always use the
# per-process path (a graphics-context warm session needs the renderer-dispose
# fix first). Reset the shared page every N captures to bound box accumulation.
RUNNER_SESSION_ENV = "FIELD_DOCS_RUNNER_SESSION"
# Every warm-session capture now creates its box at the ORIGIN, runs, screenshots,
# then deletes it (via the `delete-box` backdoor command) so exactly zero capture
# boxes linger between captures. That keeps every box inside the browser viewport
# render-zone (so its output area actually renders) and removes the old need to
# stagger boxes off-screen. Retained only as the origin all captures share.
SESSION_BOX_ORIGIN_X = 0.0
SESSION_BOX_ORIGIN_Y = 0.0
# Cap each warm-session ATTEMPT low so a genuinely-incompatible capture (a snippet
# that errors or emits no output -- which also fails the per-process path) fails
# fast and falls back to the per-process path, which re-runs with the full
# options.timeout. Keeps a failed fast-path attempt cheap.
SESSION_ATTEMPT_TIMEOUT_SEC = float(os.environ.get("FIELD_DOCS_RUNNER_SESSION_ATTEMPT_TIMEOUT", "30"))
# Circuit breaker: after this many warm-session misses in a run, stop the daemon
# and use the (authoritative) per-process path for the rest of the build. This
# MUST stay at 1: the warm daemon and a per-process capture cannot safely share
# one Field -- a per-process fallback opens its own scratch document on the same
# server, which breaks the display-output pipeline for BOTH (and, once the shared
# server is wedged, later fallbacks cold-boot their own JVMs). So the first miss
# has to free the Field by stopping the daemon before any fallback runs. delete-box
# cleanup removes the *box-accumulation* constraint (each capture's box is created
# at the origin and deleted after the shot, so an unbounded run of compatible
# captures reuses one never-re-reset doc), but display-channel wait-output captures
# still miss in a warm session, and each such miss must end the daemon cleanly.
# Measured: raising this let the daemon coexist with fallbacks and made a full
# forth+fline build SLOWER and crash; at 1 the warm path accelerates the
# compatible-capture prefix and then falls back safely. See docs-capture-perf-lever.
SESSION_MAX_FAILURES = int(os.environ.get("FIELD_DOCS_RUNNER_SESSION_MAX_FAILURES", "1"))
RUNNER_MODE_ENV = "FIELD_DOCS_RUNNER_MODE"
RUNNER_MEDIA_ENV = "FIELD_DOCS_RUNNER_MEDIA"
RUNNER_CONTINUE_ON_ERROR_ENV = "FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR"
RUNNER_PORT_ENV = "FIELD_DOCS_RUNNER_PORT"
RUNNER_WORKSPACE_ENV = "FIELD_DOCS_RUNNER_WORKSPACE"
RUNNER_GRADLE_ARGS_ENV = "FIELD_DOCS_RUNNER_GRADLE_ARGS"
DEFAULT_RUNNER_PORT = 9477


def resolve_runner_port() -> tuple[int, bool]:
    """Return the docs port and whether the caller explicitly fixed it."""
    configured = os.environ.get(RUNNER_PORT_ENV)
    explicit = configured is not None
    requested = int(configured) if explicit else DEFAULT_RUNNER_PORT
    return select_automation_port(requested, explicit=explicit), explicit


# The code-use semantic sidecar, which is what makes the DEFAULT completion backend exist.
#
# `KotlinEngineConfig.activeCompletionBackend` is `AA`, but `AaCompletionClient` engages only when a
# sidecar URL is configured; without one `KotlinInterface2.complete` falls through to K1 and says so in
# the log. `field.sh` is the only other thing in the tree that starts a sidecar and exports the URL, and
# this runner does not go through it -- it launches `./gradlew runJvm` directly. So until 2026-08-18 every
# completion figure in the docs was a picture of the K1 fallback, and the runner's own Field log said
# `completion backend=K1 box='Untitled'(__docs_drive__) 2ms (AA→K1: no-sidecar-url)` on every one.
#
# Set `FIELD_DOCS_RUNNER_SIDECAR=off` to build without one (completion captures then fail the guard in
# `_assert_completion_served_by_aa`, which is the point -- a silently-K1 figure is a wrong figure).
RUNNER_SIDECAR_ENV = "FIELD_DOCS_RUNNER_SIDECAR"
SIDECAR_URL_ENV = "AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL"
SIDECAR_PORT_ENV = "AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT"
# Starting one means a Gradle run plus the sidecar's own boot. The Analysis-API session it then builds is
# a separate wait, paid per capture by the `drive` op (`warmCompletionBackend`), because until that session
# exists the sidecar answers "session building" and `complete` falls through to K1 anyway.
SIDECAR_START_DEADLINE_SEC = float(os.environ.get("AFIELD_DOCS_SIDECAR_START_TIMEOUT", "300"))
# What Field logs for every completion request, in the `completion` channel. The reason clause is what
# distinguishes a deliberate `-Dafield.kotlin.completion=k1` (`(backend=k1)`) from AA selected and not
# serving (`(AA→K1: <why>)`) -- only the latter is a defect in a capture.
COMPLETION_AA_FALLBACK_RE = re.compile(r"^.*completion backend=K1.*\(AA→K1:.*$", re.MULTILINE)
COMPLETION_BACKEND_RE = re.compile(r"completion backend=(\w+)")
ANSI_ESCAPE_RE = re.compile(r"\x1b\[[0-9;]*m")
RUNNER_MODE_ALIASES = {
    "incremental": "incremental",
    "cache": "incremental",
    "cached": "incremental",
    "strict": "strict",
    "fresh": "strict",
    "rebuild": "strict",
    "always": "always",
    "never": "never",
    "skip": "never",
    "off": "never",
}
FENCE_OPEN_RE = re.compile(
    rf"^(?P<indent>[ ]{{0,3}})(?P<fence>`{{3,}})(?P<runner>{'|'.join(re.escape(name) for name in FENCE_NAMES)})"
    r"(?P<rest>.*?)(?P<newline>\r?\n)?$"
)
GENERIC_FENCE_OPEN_RE = re.compile(r"^(?P<indent>[ ]{0,3})(?P<fence>`{3,}|~{3,}).*(?:\r?\n)?$")
MARKER_PREFIXES = (":::static", ":::before", ":::after", ":::")

# The markdown inside a workspace under docs/assets (`README.md` / `AGENTS.md`, copied verbatim
# into every workspace by ensureAgentGuides, and the `document.md` mirror of `metadata.edn`) used
# to be rendered as pages here, with its plain fences rewritten to `diffstatic` on the way in.
# `build_zensical_docs.copy_workspace_markdown` now ships those files raw instead -- they were 312
# orphan pages whose only reader was the search index -- so nothing rewrites their fences.


# A citation written `path:line#anchor` names a source site twice: the line number is for the
# reader, the anchor is the substring CitationGuard searches for so that somebody inserting lines
# above the site cannot break the reference. Only the first half is any use to a reader, and until
# now both halves were published -- `public/docs/vision.md` renders
# `VisionPose.kt:82#const val VISION_POSE_CHANNEL` verbatim, six times over, machine-readable
# apparatus and all. So the anchor is folded into a title attribute here, on the way into the
# generated site only: the markdown on disk keeps it where the guard reads it, and the page shows
# the short form a reader wants.
#
# CITATION_EXTENSIONS must stay in step with `CitationGuard.EXT`
# (src/jvmTest/kotlin/afield/docs/CitationGuard.kt) -- an extension the guard checks and this does
# not is a citation published with its apparatus showing. test_docs_hooks_citations.py reads the
# Kotlin constant and asserts the two agree, so the drift is a test failure rather than a surprise
# on a page.
CITATION_EXTENSIONS = "kt|js|md|py|kts|html|swift|glsl|json|yml|yaml"
SOURCE_CITATION_RE = re.compile(
    r"`(?P<path>[A-Za-z0-9_./-]+\.(?:" + CITATION_EXTENSIONS + r"))(?P<line>:\d+)?"
    r"#(?P<anchor>[^`]+)`"
)


# Set by tools/build_zensical_docs.py when the source pages were rendered this run. Absent means
# they were not, and a citation then stays an unlinked chip -- the same "links only appear when they
# resolve" rule the KDoc linkifier in StdlibReferenceGenerator follows.
SOURCE_PAGE_ANCHORS_ENV = "FIELD_DOCS_SOURCE_PAGE_ANCHORS"
_source_page_anchors: dict[str, Any] | None = None


def source_page_anchors() -> dict[str, Any]:
    """The basename -> {page, lines} map written by afield.docs.SourcePageGenerator, or empty."""
    global _source_page_anchors
    if _source_page_anchors is None:
        path = os.environ.get(SOURCE_PAGE_ANCHORS_ENV, "").strip()
        candidate = Path(path) if path else None
        if candidate is not None and candidate.is_file():
            _source_page_anchors = json.loads(candidate.read_text(encoding="utf-8"))
        else:
            _source_page_anchors = {}
    return _source_page_anchors


def citation_href(*, path: str, line: int | None, depth: int) -> str | None:
    """The URL a citation should point at, or None when nothing can resolve it.

    A citation frequently points *into* a body rather than at a signature, and there is no anchor on
    line 1096 of `stats.kt` to jump to -- so the target is the greatest declaration line at or below
    the cited one, which lands on the section containing it with the fence numbered so the exact line
    is visible. `depth` is how deep the citing page's *directory* is: the href goes into raw HTML,
    which zensical rebases against the published URL for us, so it must be written file-relative.
    """
    entry = source_page_anchors().get(path.rsplit("/", 1)[-1])
    if not entry:
        return None
    url = "../" * depth + entry["page"].removesuffix(".md") + "/"
    if line is not None:
        candidates = [n for n in entry.get("lines", []) if n <= line]
        if candidates:
            url += f"#L{max(candidates)}"
    return url


def page_directory_depth(page_path: Path, docs_dir: Path) -> int:
    """How many `../` steps reach the docs root from the directory [page_path] lives in.

    The *directory*, not the published URL, and the difference is not academic: zensical rebases
    relative hrefs inside raw HTML the same way it rebases markdown links, so a link written from the
    URL's point of view gets a second `../` added to it and lands one level above the site. Measured
    on the first build with links in it — `vision.md` emitted `../reference/…` and the page served
    `../../reference/…`. Writing them file-relative lets that rebasing do its job.
    """
    try:
        relative = page_path.resolve().relative_to(docs_dir.resolve())
    except ValueError:
        return 0
    return len(relative.parts) - 1


def rewrite_source_citations(markdown: str, depth: int | None = None) -> str:
    """Publish `path:line#anchor` as `path:line`, keeping the anchor as a tooltip.

    With `depth` given and the source pages present, the chip also becomes a link to the declaration
    that contains the cited line. Without either it is the plain chip, which is what a docs build with
    no compiled Kotlin tree produces.

    Fenced blocks are skipped: a fence showing what a citation looks like should keep showing it,
    and a runner fence's code is compiled rather than read.
    """

    def replace(match: re.Match[str]) -> str:
        path = match.group("path")
        line_text = match.group("line") or ""
        shown = path + line_text
        anchor = match.group("anchor")
        escaped_anchor = html.escape(anchor, quote=True)
        chip = (
            '<code class="source-citation" data-anchor="{anchor}" title="{anchor}">{shown}</code>'
        ).format(anchor=escaped_anchor, shown=html.escape(shown, quote=True))
        if depth is None:
            return chip
        href = citation_href(
            path=path,
            line=int(line_text[1:]) if line_text else None,
            depth=depth,
        )
        if href is None:
            return chip
        return '<a class="source-citation-link" href="{href}">{chip}</a>'.format(
            href=html.escape(href, quote=True), chip=chip
        )

    out: list[str] = []
    closing: re.Pattern[str] | None = None
    for line in markdown.splitlines(keepends=True):
        if closing is not None:
            out.append(line)
            if closing.match(line):
                closing = None
            continue
        generic = GENERIC_FENCE_OPEN_RE.match(line)
        if generic is not None:
            out.append(line)
            closing = re.compile(rf"^[ ]{{0,3}}{re.escape(generic.group('fence'))}\s*(?:\r?\n)?$")
            continue
        out.append(SOURCE_CITATION_RE.sub(replace, line))
    return "".join(out)


# ── Inline code in prose: Kotlin by default ────────────────────────────────────────────────────
#
# Every inline `code` span in a page's prose is highlighted as Kotlin unless it is plainly not code
# (Marc, 2026-10-01, handoffs 400854a2 and b4bdc0a0: "the highlighting used in the reference tables,
# everywhere inline code appears in prose"). There is ONE highlighter: `highlightReferences()` in
# public/docs/assets/code-diff-player/code-diff-player.js, the highlight.js `kotlin-reference` pass the
# `data-code-lang` reference tables already use, with its palette from code-diff-player.css. This
# pass only decides, at build time, which spans it gets: it runs over the built HTML and gives each
# qualifying bare `<code>` the attributes in INLINE_CODE_MARK, so the decision is in the published
# page (and testable here) while the tokens are the reference tables' own.
#
# `inline_code_language` is the classifier. It errs towards plain: a path, URL, flag, shell command,
# key chord, markup, a single bare word, or a span in one of the site's other notations (EDN, StackSL
# stack effects, Petri arcs, rewrite rules, FieldML type variables, Clojure forms) is left alone,
# because confidently mis-highlighted text is worse than none. tools/test_docs_hooks_inline_code.py
# pins both halves.

INLINE_CODE_LANGUAGE = "kotlin"
INLINE_CODE_MARK = f'data-code-lang="{INLINE_CODE_LANGUAGE}" data-inline-code="auto"'

# File extensions that make `name.ext` a file name rather than a Kotlin member access. Deliberately
# missing the ones that are also common member names (`lock`, `log`, `properties`, `env`, `bin`,
# `mid`, `ts`, `go`, single letters), so `mutex.lock` and `math.log` still read as code.
INLINE_CODE_FILE_EXTENSIONS = frozenset(
    """kt kts java md edn js mjs cjs py ipynb glsl vert frag comp geom tesc tese rgen rchit rmiss rahit
    slang hlsl metal wgsl spv json jsonl yaml yml toml xml html htm css scss svg png jpg jpeg gif webp
    webm mp4 mov wav mp3 flac ogg aif aiff midi txt csv tsv sh bash zsh ps1 bat gradle jar zip gz tgz
    tar dylib so dll exe p12 pem obj stl ply gltf glb fbx usdz hdr exr ktx ktx2 onnx safetensors cpp
    cc hpp mm swift rs rb lua scd pd dsp wasm wat clj cljs ini cfg plist entitlements pdf mjs tsv
    com org net io edu example test local""".split()
)

# Leading words of a shell command line. `open` is absent on purpose: it is a Kotlin modifier.
INLINE_CODE_SHELL_COMMANDS = frozenset(
    """git uv uvx python python3 pip pip3 npm npx node yarn cd ls curl wget brew java javac gradle
    gradlew kill lsof pgrep ps zensical export sudo echo cat grep rg sed awk make cmake cargo ssh
    scp tar unzip rm cp mv mkdir touch source env xcrun xcodebuild adb clang chmod docker
    GET POST PUT DELETE PATCH HEAD OPTIONS""".split()
)

# Kotlin's hard and soft keywords plus the standard infix words, for the "three bare words in a
# row" rule below: `a until b` and `private data class` are Kotlin, `fbm palette saturate` is not.
INLINE_CODE_KOTLIN_WORDS = frozenset(
    """as break class continue do else false for fun if in interface is null object package return
    super this throw true try typealias typeof val var when while by catch constructor delegate
    dynamic field file finally get import init param property receiver set setparam value where
    abstract actual annotation companion const crossinline data enum expect external final infix
    inline inner internal lateinit noinline open operator out override private protected public
    reified sealed suspend tailrec vararg to until downTo step and or xor shl shr ushr it""".split()
)
# Words that make an all-words span (`val x`, `return result`) Kotlin rather than English.
INLINE_CODE_LEADING_KEYWORDS = frozenset(
    """val var fun class object interface data enum sealed import return if when for while suspend
    override private internal public protected typealias inline operator infix abstract open
    companion const lateinit external tailrec annotation value package throw try do""".split()
)

_INLINE_STRING_RE = re.compile(r'"(?:[^"\\]|\\.)*"|\'(?:\\.|[^\'\\])\'')
_INLINE_SINGLE_TOKEN_RE = re.compile(r"[@#$]?\w+[?!]?")
_INLINE_URL_RE = re.compile(r"://|^(?:www\.|localhost\b|https?:|mailto:)|^[\w.-]+:\d+(?:/|$)")
_INLINE_FILE_RE = re.compile(r"[\w.@+~*-]*\.(?P<ext>[A-Za-z0-9]+)(?::\d+(?:-\d+)?)?")
_INLINE_FLAG_RE = re.compile(r"(?:^|\s)--?[A-Za-z]")
_INLINE_ENV_RE = re.compile(r"^[A-Z][A-Z0-9_]*=")
_INLINE_KEY_CHORD_RE = re.compile(
    r"^(?:(?:ctrl|control|cmd|command|alt|option|opt|shift|meta|super|fn|win|mod|[⌘⌥⇧⌃])\s*[-+]\s*)+\S+$",
    re.I,
)
_INLINE_EDN_KEYWORD_RE = re.compile(r"(?:^|[\s{\[(]):(?!:)[A-Za-z]")
_INLINE_STACK_EFFECT_RE = re.compile(r"(?:^|\s)--(?:\s|$)")
_INLINE_REWRITE_VARIABLE_RE = re.compile(r"(?:^|[\s(,\[])\?[A-Za-z_]")
_INLINE_KEBAB_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9]*-[A-Za-z][A-Za-z0-9]+")
_INLINE_LISP_FORM_RE = re.compile(r"^\((?:[^\s,()]+)(?:\s+[^\s,()]+){2,}\)$")
_INLINE_WORD_ONLY_RE = re.compile(r"[\w\s]+")
# Two names side by side with only a space between them. Kotlin puts a space between names only
# around a keyword or an infix call (`a until b`, `gain(0.6) then lowPass(0.08)`); the site's other
# notations do it all the time: StackSL and FieldML application (`sin 0.0`, `propOr "x" "y"`),
# Strudel mini-notation (`bd sd, hh*4`), and English. The lookbehinds let an infix call after a
# closing bracket through, and the lookahead lets one whose right side is a call or member through.
_INLINE_JUXTAPOSED_RE = re.compile(
    r"(?<![\w.)\]@])(?<![)\]] )(?P<left>[A-Za-z_]\w*)[ \t]+"
    r"(?=(?P<right>[A-Za-z_]\w*(?![\w(.])|\d|\"))"
)
_INLINE_MAVEN_RE = re.compile(r"[a-z][\w-]*(?:\.[\w-]+)+:[\w.-]+(?::[\w.-]+)*")


def inline_code_language(text: str) -> str | None:
    """The language a prose inline code span is highlighted in: `"kotlin"`, or None for plain.

    Kotlin is the default for anything that reads as code; the exceptions are the things on this
    site that are written in backticks but are not Kotlin, in the order they are tested here.
    """
    s = text.strip()
    if not s or "\n" in s:
        return None
    # String and char literals are opaque: a URL or `--flag` inside `"..."` does not make the span
    # a URL or a command line, so most rules look at `bare`.
    bare = _INLINE_STRING_RE.sub('""', s)
    if not re.search(r"[A-Za-z_]", s):
        return None  # numbers, versions, maths with no identifier: `0.5`, `28 / 28 / 29`
    if _INLINE_SINGLE_TOKEN_RE.fullmatch(s):
        return None  # a single bare word, identifier or hash: `frame`, `FLine`, `@last`, `601f63d73`
    if any(ord(ch) > 127 and ch != "…" for ch in bare):
        return None  # maths and prose: `x̂ × ẑ = −ŷ`, `b → c`, `3·V`
    if _INLINE_URL_RE.search(bare):
        return None
    if bare[0] in "/~" or bare.startswith(("./", "../")):
        return None  # a path: `/field-docs/`, `~/Dirt-Samples`, `./field.sh`
    if not re.search(r"\s", bare) and "/" in bare and "(" not in bare:
        return None  # a relative path or glob: `src/jvmMain/...`, `tools/test_*.py`
    file_match = _INLINE_FILE_RE.fullmatch(s)
    if file_match is not None and file_match.group("ext").lower() in INLINE_CODE_FILE_EXTENSIONS:
        return None  # a file or host name: `metadata.edn`, `Boxes.kt:54`, `openendedgroup.com`
    if re.fullmatch(r"\.\w+", s) or s.startswith("*"):
        return None  # an extension or glob: `.kt`, `*.md`
    if _INLINE_FLAG_RE.search(bare):
        return None  # a flag, or a command line carrying one: `--workspace`, `kill -INT`
    first_word = re.split(r"\s", s, maxsplit=1)[0]
    if first_word in INLINE_CODE_SHELL_COMMANDS and " " in s:
        return None  # a command line: `git merge main`, `npm ci`
    if _INLINE_MAVEN_RE.fullmatch(s):
        return None  # a Maven coordinate: `com.microsoft.onnxruntime:onnxruntime`
    if s.endswith(("=", ":")) and not re.search(r"\s", s):
        return None  # an option or field name: `audio=`, `from:`
    if re.match(r"[A-Z][\w-]*: \S", s):
        return None  # an HTTP header: `Host: localhost`, `Origin: null`
    if re.search(r"(?:^|\s)<[a-z][\w-]*>", bare):
        return None  # a usage line with placeholders: `why <box> <name>`
    if _INLINE_ENV_RE.match(s):
        return None  # an environment assignment: `AFIELD_OFFLINE=1`
    if _INLINE_KEY_CHORD_RE.fullmatch(s):
        return None  # a key chord: `Ctrl+Space`, `Alt+D`, `Shift-Enter`
    if s.startswith("<") or "</" in bare or "/>" in bare:
        return None  # markup: `<div>hello</div>`
    if bare[0] in "#:$" or bare.startswith("->"):
        return None  # EDN tags, directives, keywords, shell variables, threading forms
    if _INLINE_EDN_KEYWORD_RE.search(bare):
        return None  # EDN maps: `{ :id "..." }`
    if _INLINE_STACK_EFFECT_RE.search(bare):
        return None  # StackSL stack effects: `lo hi x -- c`
    if any(mark in bare for mark in ("-[", "]->", "=>", ":=", "{{", "}}")):
        return None  # Petri arcs, rewrite rules, templates: `pre -[ move ]-> post`, `{{ name }}`
    if _INLINE_REWRITE_VARIABLE_RE.search(bare):
        return None  # rewrite pattern variables: `multiply(?x, 1)`
    if "'" in bare or "`" in bare or "\\" in bare or "~" in bare:
        return None  # type variables, English, Pd objects: `('a -> 'b)`, `osc~`
    if re.search(r"(?<!\|)\|(?!\|)", bare) or "$" in bare or "^" in bare:
        return None  # pipes, shell expansions, powers: Kotlin has `||`, not `|`, and no `^`
    if _INLINE_KEBAB_RE.search(bare):
        return None  # kebab-case names are commands and options: `new-box`, `wait-output-channel=`
    if _INLINE_LISP_FORM_RE.fullmatch(bare):
        return None  # a Clojure form: `(display channel v)`
    if _INLINE_WORD_ONLY_RE.fullmatch(bare) and first_word not in INLINE_CODE_LEADING_KEYWORDS:
        return None  # words and numbers only: `outWidth x outHeight`, `add 5`
    if "->" in bare and "{" not in bare and not re.search(r"\)\s*->", bare):
        return None  # an arrow type outside a lambda or a `(A) -> B`: FieldML's `Float -> Float`
    for pair in _INLINE_JUXTAPOSED_RE.finditer(bare):
        left, right = pair.group("left"), pair.group("right")
        if left not in INLINE_CODE_KOTLIN_WORDS and right not in INLINE_CODE_KOTLIN_WORDS:
            return None  # names side by side: `sin 0.0`, `bd sd, hh*4`, `animateFor seconds f`
    return INLINE_CODE_LANGUAGE


# Elements whose `<code>` is not prose inline code, or is someone else's to colour.
_INLINE_CODE_SKIP_TAGS = frozenset({"pre", "script", "style", "textarea", "svg", "nav", "kbd", "samp"})
_INLINE_CODE_VOID_TAGS = frozenset(
    {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
)
_HTML_TAG_RE = re.compile(r"<!--.*?-->|<(/?)([A-Za-z][\w:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>", re.S)
_INLINE_CODE_AUTO_OPEN = f"<code {INLINE_CODE_MARK}>"


def _rewrite_article_code(page_html: str, rewrite: Any, *, skip_code_lang_containers: bool) -> str:
    """Offer each text-only `<code>` element in the page's `<article>` to `rewrite`.

    `rewrite(open_tag, inner_html, in_link)` returns the replacement for the whole element, from
    `<code` to `</code>`, or None to leave it. A `<code>` inside `pre` or the other skipped elements
    is never offered, nor (with `skip_code_lang_containers`) one inside a `[data-code-lang]`
    container. Script and style bodies are not markup and are skipped whole.
    """
    out: list[str] = []
    stack: list[tuple[str, bool]] = []  # (tag, makes its contents ineligible)
    article_depth = 0
    link_depth = 0
    blocked = 0
    copied = 0  # page_html[:copied] is already in `out`
    skip_until = 0
    for match in _HTML_TAG_RE.finditer(page_html):
        if match.start() < skip_until:
            continue
        closing, raw_name, attrs = match.group(1), match.group(2), match.group(3)
        if raw_name is None:
            continue  # a comment
        name = raw_name.lower()
        if not closing:
            if name in ("script", "style"):
                end = re.compile(rf"</{name}", re.I).search(page_html, match.end())
                skip_until = len(page_html) if end is None else end.start()
                continue
            if name == "code" and article_depth and not blocked:
                close = page_html.find("</code>", match.end())
                inner = page_html[match.end():close] if close >= 0 else "<"
                if "<" not in inner:
                    replacement = rewrite(match.group(0), inner, link_depth > 0)
                    if replacement is not None:
                        out.append(page_html[copied:match.start()])
                        out.append(replacement)
                        copied = skip_until = close + len("</code>")
                        continue
            if name in _INLINE_CODE_VOID_TAGS or attrs.rstrip().endswith("/"):
                continue
            ineligible = name in _INLINE_CODE_SKIP_TAGS or (skip_code_lang_containers and "data-code-lang" in attrs)
            stack.append((name, ineligible))
            blocked += ineligible
            article_depth += name == "article"
            link_depth += name == "a"
        else:
            for index in range(len(stack) - 1, -1, -1):
                if stack[index][0] == name:
                    for popped, ineligible in stack[index:]:
                        blocked -= ineligible
                        article_depth -= popped == "article"
                        link_depth -= popped == "a"
                    del stack[index:]
                    break
    out.append(page_html[copied:])
    return "".join(out)


def mark_inline_code_html(page_html: str) -> str:
    """Mark the prose inline code in one built page for Kotlin highlighting.

    Only a bare `<code>` (or one this pass marked on an earlier build, which is re-decided) inside the
    page's `<article>` is considered, and only when it holds text alone. A code element with any other
    attribute is someone else's: a generated signature (`code.highlight`), a source citation, an
    inlinehilite span. So is one inside `pre`, a `[data-code-lang]` container (the reference tables,
    which carry their author's language), or the other skipped elements above.
    """

    def rewrite(tag: str, inner: str, in_link: bool) -> str | None:
        del in_link
        if tag != "<code>" and tag != _INLINE_CODE_AUTO_OPEN:
            return None
        opened = _INLINE_CODE_AUTO_OPEN if inline_code_language(html.unescape(inner)) else "<code>"
        return f"{opened}{inner}</code>"

    return _rewrite_article_code(page_html, rewrite, skip_code_lang_containers=True)


# ── Repo source paths become GitHub links ──────────────────────────────────────────────────────
#
# Marc, 2026-10-01 (handoff 9b508085): a code span that is exactly an existing repo path under
# src/, tools/, server/ or public/ links to it on GitHub, at blob/main. The repo is private today
# and will be public at this URL. dev_docs/ paths stay unlinked: they are notes, not source. Only
# tracked files and directories link (`git ls-files`), so nothing generated or ignored does. A path
# under a linked root that names nothing in the checkout stays plain and is reported by the build,
# so a moved file shows up as a warning rather than a dead link. Inside Field's own docs pane
# (/field-docs/), docsAnnotations.js intercepts a click on one of these links and shows the file
# from the local checkout instead (GET /api/docs-source-file, docsAnnotations.kt).

REPO_SOURCE_GITHUB = "https://github.com/OpenEndedGroup/Field4"
REPO_SOURCE_BRANCH = "main"
REPO_SOURCE_LINKED_ROOTS = ("src", "tools", "server", "public")
REPO_SOURCE_UNLINKED_ROOTS = ("dev_docs",)
REPO_SOURCE_LINK_CLASS = "repo-source-link"
_REPO_SOURCE_PATH_RE = re.compile(
    r"(?P<path>(?P<root>[A-Za-z_]+)/[A-Za-z0-9_.@+-]+(?:/[A-Za-z0-9_.@+-]+)*/?)(?::(?P<line>\d+)(?:-(?P<end>\d+))?)?"
)


@dataclass(frozen=True)
class RepoTree:
    """The repo's tracked files and the directories that hold them, as `/`-separated paths."""

    files: frozenset[str]
    directories: frozenset[str]

    @classmethod
    def from_paths(cls, paths: Any) -> "RepoTree":
        files = frozenset(paths)
        directories = {parent for path in files for parent in _parents(path)}
        return cls(files, frozenset(directories))

    @classmethod
    def from_git(cls, repo_root: Path) -> "RepoTree":
        listed = subprocess.run(
            ["git", "ls-files", "-z"], cwd=repo_root, capture_output=True, check=True
        ).stdout.decode("utf-8", "replace")
        return cls.from_paths(path for path in listed.split("\0") if path)


def _parents(path: str) -> list[str]:
    parts = path.split("/")[:-1]
    return ["/".join(parts[: index + 1]) for index in range(len(parts))]


def repo_source_href(text: str, tree: RepoTree) -> tuple[str | None, str | None]:
    """`(href, None)` for a span that is a tracked repo path under a linked root, `(None, path)` for
    one that looks like such a path but names nothing tracked (stale), and `(None, None)` otherwise.

    `path:LINE` and `path:LINE-END` keep their lines as a GitHub `#L` anchor. A directory links to
    its `tree/` view.
    """
    match = _REPO_SOURCE_PATH_RE.fullmatch(text.strip())
    if match is None or match.group("root") not in REPO_SOURCE_LINKED_ROOTS:
        return None, None
    path = match.group("path").rstrip("/")
    if path in tree.files:
        kind = "blob"
    elif path in tree.directories and match.group("line") is None:
        kind = "tree"
    else:
        return None, match.group("path")
    href = f"{REPO_SOURCE_GITHUB}/{kind}/{REPO_SOURCE_BRANCH}/{path}"
    if match.group("line"):
        href += f"#L{match.group('line')}"
        if match.group("end"):
            href += f"-L{match.group('end')}"
    return href, None


def link_repo_source_paths_html(page_html: str, tree: RepoTree, stale: list[str] | None = None) -> str:
    """Wrap each prose code span that is a tracked repo path in a GitHub link.

    A span already inside a link is left alone, so an author's own link wins. Stale paths are
    appended to `stale` when it is given.
    """

    def rewrite(tag: str, inner: str, in_link: bool) -> str | None:
        if in_link or (tag != "<code>" and tag != _INLINE_CODE_AUTO_OPEN):
            return None
        href, missing = repo_source_href(html.unescape(inner), tree)
        if missing is not None and stale is not None:
            stale.append(missing)
        if href is None:
            return None
        path = _REPO_SOURCE_PATH_RE.fullmatch(html.unescape(inner).strip())
        assert path is not None
        line = path.group("line")
        return (
            f'<a class="{REPO_SOURCE_LINK_CLASS}" href="{html.escape(href, quote=True)}"'
            f' data-repo-path="{html.escape(path.group("path").rstrip("/"), quote=True)}"'
            + (f' data-repo-line="{line}"' if line else "")
            + f">{tag}{inner}</code></a>"
        )

    return _rewrite_article_code(page_html, rewrite, skip_code_lang_containers=False)


@dataclass
class BuiltSiteReport:
    pages_rewritten: int = 0
    spans_marked: int = 0
    source_links: int = 0
    stale_paths: dict[str, list[str]] | None = None  # page -> stale repo paths, in page order


def postprocess_built_site(site_dir: Path, repo_root: Path) -> BuiltSiteReport:
    """The passes that need a page's rendered HTML: GitHub links for repo source paths, then the
    Kotlin marking of prose inline code. Run by tools/build_zensical_docs.py after Zensical."""
    tree = RepoTree.from_git(repo_root)
    report = BuiltSiteReport(stale_paths={})
    for page in sorted(site_dir.rglob("*.html")):
        try:
            text = page.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if "<article" not in text:
            continue
        stale: list[str] = []
        updated = mark_inline_code_html(link_repo_source_paths_html(text, tree, stale))
        report.spans_marked += updated.count(_INLINE_CODE_AUTO_OPEN)
        report.source_links += updated.count(f'<a class="{REPO_SOURCE_LINK_CLASS}"')
        # A path that exists but is not tracked (the built site under public/field4/, the
        # untracked src/personal/) is a real place, just not one GitHub has: unlinked, not stale.
        stale = [path for path in stale if not (repo_root / path).exists()]
        if stale:
            report.stale_paths[page.relative_to(site_dir).as_posix()] = list(dict.fromkeys(stale))
        if updated != text:
            page.write_text(updated, encoding="utf-8")
            report.pages_rewritten += 1
    return report


def is_marker_line(stripped: str) -> bool:
    """True for any `:::` section/fold marker line. Fold markers (`:::fold [label]` /
    `:::endfold`) mark boilerplate that the code-diff player collapses in the rendered
    page; they are stripped from the EXECUTED code here but the enclosed lines are kept
    (they still run). See public/docs/assets/code-diff-player for the client side."""
    if stripped in MARKER_PREFIXES:
        return True
    if stripped == ":::endfold" or stripped == ":::fold" or stripped.startswith(":::fold "):
        return True
    return False
TRUE_VALUES = {"1", "true", "yes", "on"}
FALSE_VALUES = {"0", "false", "no", "off"}
RUNNER_DIGEST_SOURCE_FILES = (
    "public/docs_hooks.py",
    "src/jsMain/kotlin/afield/Main.kt",
    "src/jvmMain/kotlin/afield/docs/screenshot.kt",
    "src/jvmMain/kotlin/afield/graphics/FLine.kt",
    "src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt",
    "src/jvmMain/kotlin/afield/four/fabricated/StandardRendererDocsHarness.kt",
    "src/jvmMain/kotlin/afield/nano/NanoVisualization.kt",
    "src/jvmMain/kotlin/afield/graphics/someSkiko.kt",
    "src/commonMain/resources/outputareas2.js",
    "tools/field_backdoor.py",
    "tools/capture_transaction.py",
    "tools/run_field_tests.py",
)
# Successful capture bytes cannot depend on these helpers: they only count, explain, persist, or
# summarize a failure after one has already occurred. The docs-hooks digest omits their AST so making
# a post-mortem more useful does not force ~50 minutes of unrelated recaptures. Their own focused
# Python tests remain the verification surface for diagnostic behavior.
RUNNER_DIAGNOSTIC_ONLY_DEFINITIONS = frozenset({
    "handle_capture_error",
    "diagnose_capture_failure",
    "_collect_jvm_crash_dumps",
    "_remember_capture_child_pid",
    "_error_lines_in",
    "_backdoor_client_log_for_pid",
    "_latest_backdoor_client_error",
    "_save_failure_report",
    "_timeout_failure",
    "log_summary",
    "tail_text",
    "head_text",
    "tail_inline_text",
    "process_output_text",
    "format_completed_failure_details",
    "format_timeout_failure_details",
    "cache_status_label",
    "format_cache_reason",
    "compare_cache_metadata",
})


@dataclass(frozen=True)
class RunnerOptions:
    screenshot: str
    source_image: str | None
    capture: str
    execution: str
    wait: float
    padding: float
    timeout: float
    property_name: str
    embed: bool
    alt: str | None
    document: str | None
    source_box: str | None
    source_box_properties: tuple[str, ...]
    source_box_wait: float
    run_box: str | None
    capture_box: str | None
    setup_boxes: tuple[str, ...]
    macro_file: str | None
    macro_name: str | None
    macro_selection: tuple[str, ...]
    fabricate_scene: str | None
    native_wait_frames: int
    trim_native: bool
    trim: bool
    box_name: str
    box_x: float
    box_y: float
    box_w: float
    box_h: float
    open_outputs: bool
    expect_outputs: str
    wait_output: bool
    wait_output_kind: str
    wait_output_channel: str | None
    wait_output_contains: str | None
    with_editor: bool
    graphics_context: bool
    force: bool
    restart_target: bool  # restart the captured launch at frame zero; disable for ambient/multi-box drivers
    video: str | None  # path to .mp4 output; when set, records video instead of screenshot
    duration: float  # video duration in seconds (default 5.0)
    fps: int  # video frame rate (default 30)
    region: str  # for capture="ui": what the drive recording/still is clipped to
    clip_selector: str | None  # for capture="ui": clip to a CSS selector (overlays not backed by a box)
    warm_completion: bool  # for capture="ui": pre-warm the K1 completion engine (autocomplete captures)
    audio: str | None  # path to .wav output; offline-renders a registered synth scene to WAV
    audio_spec: str | None  # name of the scene to offline-render for audio=
    audio_renderer: str  # object providing renderWav(name, path) — DoughDspSoundDocs by default
    hidden: bool
    language_key: str
    # `expect-error=true`: the box is *supposed* to fail, because the error is what the figure
    # shows. Asserted in both directions -- a fence carrying it whose box runs cleanly fails too,
    # so it cannot become a way of muting a capture that quietly stopped demonstrating anything.
    expects_error: bool
    # Hardware-specific reference captures are retained unless explicitly requested on their host.
    capture_profile: str | None = None
    # `clip="x,y,w,h"`: crop the finished screenshot to this rectangle, in CSS pixels of the page
    # (the canvas's world coordinates when it is unpanned and unzoomed). For `capture="window"`
    # figures of something drawn on the canvas, where the capture viewport is far larger than the
    # drawing and `trim` cannot see past the dot grid.
    clip: tuple[float, float, float, float] | None = None
    # `viewport="WxH"` (capture="ui" only): the capture page's size in CSS pixels for this drive,
    # set before the recording starts and restored after it. Field's layout follows the window
    # (the editor pane is 40vw), so a smaller window is how a figure shows box and editor together
    # at a legible scale.
    viewport: tuple[int, int] | None = None
    # `scale="3"` (capture="box" only): device pixels per CSS pixel for this still; the capture
    # page's own is 2. For a narrow output panel the page wants to show wider than its CSS size
    # without upscaling the raster.
    scale: float | None = None


def capture_profile_enabled(profile: str | None) -> bool:
    if profile is None:
        return True
    if profile != "linux-nvidia":
        raise RuntimeError(f"Unknown docs capture profile {profile!r}")
    requested = {p.strip() for p in os.environ.get("FIELD_DOCS_CAPTURE_PROFILES", "").split(",")}
    return sys.platform.startswith("linux") and profile in requested


def derive_sidecar_port(repo_root: Path) -> int:
    """The checkout-derived sidecar port, computed exactly as `field.sh derive_sidecar_port` does.

    Byte-for-byte agreement is the whole point: a different formula here would start a SECOND sidecar
    for the same checkout, so a docs build and an interactive `./field.sh` would each pay a cold
    Analysis-API session instead of sharing one. `cksum` is shelled out to rather than reimplemented
    because POSIX `cksum` is CRC-32/CKSUM, which is *not* `zlib.crc32` -- a hand-rolled copy would agree
    on nothing and drift silently.
    """
    override = os.environ.get(SIDECAR_PORT_ENV, "").strip()
    if override:
        return int(override)
    try:
        completed = subprocess.run(
            ["cksum"], input=str(repo_root).encode("utf-8"), capture_output=True, check=True
        )
        checksum = int(completed.stdout.split()[0])
    except (OSError, ValueError, IndexError, subprocess.CalledProcessError):
        # No `cksum` (Windows): any stable port will do, it just won't be field.sh's.
        checksum = zlib.crc32(str(repo_root).encode("utf-8"))
    return 17841 + (checksum % 1000)


def sidecar_workspace_status(url: str, repo_root: Path) -> str:
    """`ok` / `wrong` / `down` for a sidecar URL, matching `field.sh sidecar_status`.

    A sidecar analyses one workspace, so one serving a *different* checkout is not reusable -- it is
    `wrong`, and reusing it would silently complete against another tree's sources.
    """
    base = url.rstrip("/")
    if base.endswith("/analyze"):
        base = base[: -len("/analyze")]
    try:
        with urllib.request.urlopen(base + "/health", timeout=1.0) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception:
        return "down"
    reported = os.path.realpath(str(payload.get("workspaceRoot", "")))
    return "ok" if reported == os.path.realpath(str(repo_root)) else "wrong"


@dataclass(frozen=True)
class CacheEntry:
    digest: str
    metadata: dict[str, Any] | None = None


class RunnerState:
    def __init__(self, config: Any) -> None:
        self.config = config
        config_path = Path(config.config_file_path).resolve()
        self.repo_root = config_path.parent.parent
        self.docs_dir = Path(config["docs_dir"]).resolve()
        self.cache_path = config_path.parent / ".field-docs-runner-cache.json"
        self.shared_log_path = config_path.parent / ".field-docs-runner-field.log"
        self.runner_mode = resolve_runner_mode(os.environ.get(RUNNER_MODE_ENV))
        self.runner_media = os.environ.get(RUNNER_MEDIA_ENV, "all").strip().lower()
        if self.runner_media not in {"all", "video"}:
            raise RuntimeError(
                f"Unsupported {RUNNER_MEDIA_ENV}={self.runner_media!r}; expected 'all' or 'video'"
            )
        self.backdoor_port, port_was_explicit = resolve_runner_port()
        # Capture command builders are leaf helpers and intentionally do not depend on STATE.
        # Publish the once-per-build choice so they all use the same automatically selected port.
        os.environ[RUNNER_PORT_ENV] = str(self.backdoor_port)
        self.continue_on_error = resolve_continue_on_error(
            os.environ.get(RUNNER_CONTINUE_ON_ERROR_ENV),
            runner_mode=self.runner_mode,
        )
        self._shared_field_process: subprocess.Popen[str] | None = None
        self._shared_field_started_by_runner = False
        self._shared_field_started_at: float | None = None
        self._shared_field_jvm_pid: int | None = None
        # Set the moment WE start signalling the shared server down (teardown, restart, or a
        # failed-start cleanup). Without it, `diagnose_capture_failure` reads any exited process
        # as "the JVM died mid-capture" and reports the runner's own SIGKILL as a crash --
        # which on 2026-07-28 aimed triage at an OOM that never happened. See Backlog #18.
        self._shared_field_stop_reason: str | None = None
        # Resolved once, before the first shared Field starts, because the completion backend is chosen
        # from the environment at JVM start. None = no AA backend for this build.
        self._sidecar_url: str | None = None
        self._sidecar_checked = False
        self._sidecar_started_by_runner = False
        self._sidecar_process: subprocess.Popen[str] | None = None
        self._closed = False
        self.environment: VerificationEnvironment | None = None
        self.environment_outcomes: list[dict[str, Any]] = []
        self._capture_environment_check = None
        self.failures: list[str] = []
        # Captures that failed but had a committed asset already on disk to fall back
        # to (HTML still references a real, previously-captured image) vs. failures
        # with no asset at all (the HTML will carry a broken reference). Tracked
        # separately so continue-on-error runs report the difference honestly.
        self.fallback_failures: list[str] = []
        self.broken_failures: list[str] = []
        validate_scenes_against_harness(self.repo_root)
        self.runner_source_digest = compute_runner_source_digest(self.repo_root)
        # Per-fence source coverage: RUNNER_DIGEST_SOURCE_FILES only covers the runner
        # machinery, so a stdlib edit used to leave every digest identical. See
        # load_source_symbol_index / compute_source_refs_digest.
        self.source_refs_mode = resolve_source_refs_mode(os.environ.get(SOURCE_REFS_MODE_ENV))
        self.source_symbol_index = load_source_symbol_index(self.repo_root)
        self._source_file_digests: dict[str, str] = {}
        self.cache, self.cache_metadata = self._load_cache()
        self.dirty = False
        self.started_at = time.monotonic()
        self.summary_logged = False
        self.screenshots_run = 0
        self.videos_run = 0
        self.audio_run = 0
        self.ui_drives_run = 0
        self.captures_started = 0
        self.cache_hits = 0
        self.assets_reused = 0
        self.missing_assets = 0
        self.server_starts = 0
        self.server_restarts = 0
        # Warm-session (opt-in) state.
        self.session_enabled = (
            os.environ.get(RUNNER_SESSION_ENV, "").strip().lower() in TRUE_VALUES
            and self.runner_mode != "never"
        )
        self._session_process: subprocess.Popen[str] | None = None
        self._last_capture_child_pid: int | None = None
        self._session_sock_path = self.repo_root / ".field-backdoor" / f"session-{self.backdoor_port}.sock"
        self._session_box_count = 0
        self._session_failures = 0
        self.session_captures = 0
        self._captures_since_server_restart = 0
        LOG.info("Field docs runner mode: %s", self.runner_mode)
        LOG.info(
            "Field docs runner automation port: %s (%s)",
            self.backdoor_port,
            "explicit" if port_was_explicit else "automatically selected",
        )
        LOG.info(
            "Field docs runner per-fence source coverage: %s (%s symbol(s) over %s file(s))",
            self.source_refs_mode,
            len(self.source_symbol_index),
            len({source for files in self.source_symbol_index.values() for source in files}),
        )
        LOG.info("Field docs runner continue on error: %s", self.continue_on_error)
        if self.session_enabled:
            LOG.info("Field docs runner warm-session path ENABLED (experimental; fast path with per-process fallback)")
        # Startup belongs behind the first capture's environment checkpoint.
        # Construction alone must not boot Field before power/display are checked.
        atexit.register(self.close)

    def source_refs_digest(self, code: str) -> str:
        """Digest of the stdlib source this fence's code text reaches. `""` when it reaches none."""
        return compute_source_refs_digest(
            repo_root=self.repo_root,
            code=code,
            symbol_index=self.source_symbol_index,
            file_digest_cache=self._source_file_digests,
            mode=self.source_refs_mode,
        )

    def handle_capture_error(
        self, *, page_path: Path, output_path: Path, error: Exception, continuing: bool = True
    ) -> None:
        """Record one failed capture.

        `continuing` says whether the caller will swallow the error and keep building
        (`continue_on_error`) or re-raise and abort. **Either way the failure is recorded
        here**, and that is the point: until 2026-08-07 the fail-fast path re-raised
        *before* calling this, so `self.failures` stayed empty while `log_summary` still
        ran during teardown and printed `failures=0` on a build that exited 1. The one
        number a reader checks was wrong exactly when it mattered.
        """
        message = f"{page_path.name} -> {output_path}: {error}"
        if isinstance(error, VerificationEnvironmentError):
            LOG.warning("Field docs runner environment-incomplete: %s", message)
            return
        self.failures.append(message)
        # A committed asset already on disk means the generated HTML still points at a
        # real, previously-captured image -- degraded (stale) but not broken. No asset
        # at all means the HTML will carry a broken reference: a louder problem.
        # That split describes a *continuing* build; when we are about to abort there is
        # no HTML to degrade, so do not promise a fallback no reader will ever see.
        if output_path.exists():
            self.fallback_failures.append(message)
            if continuing:
                LOG.warning(
                    "Field docs runner capture FAILED, falling back to committed asset on disk: %s",
                    message,
                )
            else:
                LOG.error(
                    "Field docs runner capture FAILED (a committed asset exists, but "
                    "continue-on-error is off, so the build aborts): %s",
                    message,
                )
        else:
            self.broken_failures.append(message)
            LOG.error(
                "Field docs runner capture FAILED with NO asset on disk (HTML will have a "
                "broken reference): %s",
                message,
            )

    def diagnose_capture_failure(self, *, page_path: Path, output_path: Path) -> str:
        """Capture BOTH sides of the WebSocket after a capture failed.

        A backdoor error like "no close frame received or sent" only reports the
        *client* side of a dropped socket -- it means the connection was severed with
        no WebSocket close handshake, i.e. the Field JVM almost certainly died. The
        real cause lives on the server. This gathers, correlates, and PERSISTS both
        ends so an unattended CI run leaves a full post-mortem behind rather than an
        opaque one-liner:

          - whether the shared Field process is still alive (or its exit code),
          - the Field server (JVM stdout+stderr) log tail,
          - any JVM crash dumps (hs_err_pid*/replay_pid*) written during THIS server's
            lifetime, matched to the JVM PID when possible,
          - the newest backdoor client session log's error/exception events.

        Returns a concise summary (with the path to the full saved report) suitable for
        embedding in the raised RuntimeError / failure message.
        """
        sections: list[str] = []

        # --- server process liveness ---
        # A single poll() here races the death it is trying to observe. "No close frame" means
        # the socket was severed, and when the cause is a SIGKILL from *outside* this run --
        # a sibling agent on the machine, a stray `killall java` -- the signal and the drop land
        # together, so the child is dying but not yet reaped and poll() still returns None.
        # The reader then gets "still alive", which is the one branch of the three that points
        # away from the truth: a healthy server plus a dead socket reads as "the page hung", and
        # whichever page happened to be capturing takes the blame. Give the child a moment to be
        # reaped before believing it survived. 2026-09-14's nightly CI run lost its docs gate
        # exactly this way, and the page the post-mortem framed was a two-day-old one that had
        # never been certified -- innocent, and it re-captured clean on the next pass.
        # Local, not module-level, on purpose: `diagnose_capture_failure` is in
        # RUNNER_DIAGNOSTIC_ONLY_DEFINITIONS, so everything inside it is invisible to the runner
        # source digest. A module constant would invalidate every cached capture (Backlog #32).
        liveness_grace_seconds = 5.0

        def still_alive_after_grace(child) -> bool:
            """True only if [child] outlives a short grace window, not merely this instant."""
            try:
                child.wait(timeout=liveness_grace_seconds)
            except subprocess.TimeoutExpired:
                return True
            except Exception:
                # A stub or a handle that cannot wait must not break the post-mortem; fall
                # back to the single poll this replaced.
                return child.poll() is None
            return False

        def signal_suffix(code) -> str:
            """`-9` -> " killed by SIGKILL". Popen reports a signal death as a negative code."""
            if not isinstance(code, int) or code >= 0:
                return ""
            try:
                return f" killed by {signal.Signals(-code).name}"
            except ValueError:
                return f" killed by signal {-code}"

        process = self._shared_field_process
        if process is None:
            sections.append("Field server process: not tracked (no runner-owned server handle).")
        elif still_alive_after_grace(process):
            sections.append(
                f"Field server process: still alive (pid {process.pid}, jvm pid "
                f"{self._shared_field_jvm_pid}) -- confirmed still running "
                f"{liveness_grace_seconds:g}s after the failure, so the JVM is not the cause."
            )
        elif self._shared_field_stop_reason is not None:
            # OUR kill, not a crash. `_kill_shared_field_process` / `_stop_shared_field_server`
            # escalate SIGTERM -> SIGKILL, so the exit code is typically 143 or 137 -- and Gradle
            # helpfully appends "often caused by the system running out of memory" to 137, which
            # sent 2026-07-28's triage after an OOM that never happened (Backlog #18). Say plainly
            # that the exit is ours, and that the capture had already failed before it.
            sections.append(
                f"Field server process: exited with code {process.returncode} "
                f"(pid {process.pid}, jvm pid {self._shared_field_jvm_pid}) -- "
                f"{self._shared_field_stop_reason}, NOT a mid-capture crash. "
                "The capture had already failed by then, so this exit is a consequence and not the "
                "cause. Ignore any 'non-zero exit value 137 / out of memory' hint below: 137 is this "
                "runner's own SIGKILL during cleanup. Look instead at what the capture was waiting "
                "for in the log tails."
            )
        else:
            sections.append(
                f"Field server process: EXITED with code {process.returncode}"
                f"{signal_suffix(process.returncode)} "
                f"(pid {process.pid}, jvm pid {self._shared_field_jvm_pid}) -- the JVM died mid-capture "
                "(no runner-initiated stop was in progress, so this exit was NOT ours). A negative "
                "code is a signal rather than an exit status: SIGKILL means something outside this "
                "run killed the server, and the capture that was in flight is not implicated."
            )

        # --- JVM crash dumps written during this server's lifetime ---
        crash_reports = self._collect_jvm_crash_dumps()
        if crash_reports:
            for path, head in crash_reports:
                sections.append(f"JVM crash dump {path}:\n{head}")
        else:
            sections.append("JVM crash dumps: none found for this server (no hs_err/replay file since start).")

        # --- server-side log (JVM stdout+stderr) ---
        server_log = tail_text(self.shared_log_path, max_lines=120)
        if server_log:
            sections.append(f"Field server log tail ({self.shared_log_path}):\n{server_log}")
        else:
            sections.append(f"Field server log: empty or unreadable ({self.shared_log_path}).")

        # --- client-side backdoor log (the other end of the WebSocket) ---
        client = self._latest_backdoor_client_error()
        if client is not None:
            client_path, client_tail = client
            sections.append(f"Backdoor client log ({client_path}):\n{client_tail}")
        else:
            sections.append("Backdoor client log: no recent error/exception events found.")

        full_report = (
            f"Capture failure diagnosis for {page_path.name} -> {output_path}\n"
            + "\n\n".join(sections)
            + "\n"
        )

        # Persist the full post-mortem so an unattended run leaves evidence behind even
        # when continue-on-error keeps going and the exception message is truncated.
        saved_path = self._save_failure_report(output_path=output_path, report=full_report)
        summary_lines = [f"Both-sides-of-WebSocket diagnosis saved to {saved_path}" if saved_path else full_report]
        summary_lines.append(sections[0])  # liveness -- the single most telling line
        if crash_reports:
            summary_lines.append(f"JVM crash dump present: {crash_reports[0][0]}")
        return "\n".join(summary_lines)

    def _shared_log_size(self) -> int:
        try:
            return self.shared_log_path.stat().st_size
        except OSError:
            return 0

    def _shared_log_since(self, offset: int) -> str:
        """The Field log written after [offset] bytes -- i.e. by one capture and nothing else.

        Clamped rather than trusted: a mid-build server restart truncates the log, after which an older
        offset is past the end and would read nothing.
        """
        size = self._shared_log_size()
        start = offset if 0 <= offset <= size else 0
        try:
            with self.shared_log_path.open("rb") as handle:
                handle.seek(start)
                return handle.read().decode("utf-8", errors="replace")
        except OSError:
            return ""

    def _assert_completion_served_by_aa(
        self, *, page_path: Path, output_path: Path, log_offset: int, command: list[str]
    ) -> None:
        """Fail a completion capture that photographed the K1 fallback instead of the real backend.

        Field logs the serving backend for every completion request, so this asks the capture's own log
        slice rather than inferring anything from configuration -- which is exactly the distinction that
        hid this for as long as it did: `activeCompletionBackend` reads `AA` in a build where every
        completion was served by K1.

        Only an `AA→K1` fallback fails. A build that deliberately selects K1
        (`-Dafield.kotlin.completion=k1`) logs `(backend=k1)` and is left alone.
        """
        slice_text = self._shared_log_since(log_offset)
        fallbacks = COMPLETION_AA_FALLBACK_RE.findall(slice_text)
        if fallbacks:
            # Field's log is written for a terminal, so the channel tag arrives wrapped in ANSI. Strip it:
            # this text is going into an exception a person reads in a build log or a saved report.
            reasons = "\n".join("  " + ANSI_ESCAPE_RE.sub("", line).strip() for line in fallbacks[:5])
            raise RuntimeError(
                f"Field docs runner completion capture for {page_path.name} -> {output_path.name} was "
                f"served by the K1 fallback, not the default (AA) backend, so the figure does not show "
                f"what a user gets.\nField's own log says:\n{reasons}\n"
                f"Sidecar for this build: {self._sidecar_url or 'none'}\n"
                f"Start one (or let the runner: unset {RUNNER_SIDECAR_ENV}) and re-capture. Note that a "
                f"shared Field this runner REUSED rather than started carries whatever environment it was "
                f"launched with, not this one's.\n"
                f"Command: {shlex.join(command)}"
            )
        if not COMPLETION_BACKEND_RE.search(slice_text):
            LOG.warning(
                "Field docs runner: %s -> %s set warm-completion but requested no completion (nothing in "
                "the log's `completion` channel), so the backend could not be checked",
                page_path.name, output_path.name,
            )

    def _collect_jvm_crash_dumps(self) -> list[tuple[str, str]]:
        """Find JVM crash artifacts written since this server started, newest first.

        The JVM writes hs_err_pid<PID>.log (and replay_pid<PID>.log) to its working
        directory (repo_root) on a fatal native error. We match by PID when known, and
        otherwise fall back to any such file modified after the server started.
        """
        started = self._shared_field_started_at
        found: list[tuple[float, str, str]] = []
        try:
            candidates = list(self.repo_root.glob("hs_err_pid*.log")) + list(self.repo_root.glob("replay_pid*.log"))
        except OSError:
            return []
        for path in candidates:
            try:
                mtime = path.stat().st_mtime
            except OSError:
                continue
            pid_match = self._shared_field_jvm_pid is not None and f"pid{self._shared_field_jvm_pid}." in path.name
            # Accept a dump if it matches this JVM's PID, or was written after the server
            # started (a small negative slack absorbs clock/mtime granularity).
            if not pid_match and started is not None and mtime < started - 2.0:
                continue
            found.append((mtime, str(path), head_text(path, max_lines=60)))
        found.sort(key=lambda item: item[0], reverse=True)
        return [(path, head) for _mtime, path, head in found]

    def _remember_capture_child_pid(self, pid: int) -> None:
        """Record the PID of the backdoor invocation currently being run."""
        self._last_capture_child_pid = pid

    @staticmethod
    def _error_lines_in(log_path: Path) -> list[str]:
        try:
            with log_path.open("r", encoding="utf-8", errors="replace") as handle:
                return [
                    raw.rstrip("\n")
                    for raw in handle
                    if '"error"' in raw or '"traceback"' in raw or "_error" in raw
                ]
        except OSError:
            return []

    def _backdoor_client_log_for_pid(self, pid: int) -> Path | None:
        """The backdoor session log written by child `pid`, if it exists.

        `field_backdoor.py`'s SessionLogger names each file
        `<timestamp>-pid<PID>-<token>.jsonl`, so the PID identifies the invocation
        exactly.
        """
        log_dir = self.repo_root / ".field-backdoor" / "logs"
        try:
            matches = sorted(log_dir.glob(f"*-pid{pid}-*.jsonl"), key=lambda p: p.stat().st_mtime)
        except OSError:
            return None
        return matches[-1] if matches else None

    def _latest_backdoor_client_error(self) -> tuple[str, str] | None:
        """Return (path, tail) from the backdoor log belonging to the FAILING capture.

        The backdoor client (field_backdoor.py SessionLogger) writes one JSONL file per
        invocation under .field-backdoor/logs. On a dropped socket it records a
        `receiver_loop_error` (or websocket_*_error) event with the full client-side
        traceback -- the other end of the WebSocket.

        **Prefer the PID of the child we just ran.** This used to return "the newest of
        the last four logs that contains an error-shaped line", which is a guess, and on
        2026-08-07 it guessed wrong in the way that costs the most time: the failing
        capture's `stop` timed out and reported a clean `ok:false` with no error-shaped
        line at all, so the scan fell through to an *older, unrelated* invocation and the
        saved post-mortem led with a Playwright timeout from a different capture. A
        failure whose own log is quiet is a real and informative state -- say so, rather
        than substituting someone else's log for it.
        """
        pid = self._last_capture_child_pid
        if pid is not None:
            own = self._backdoor_client_log_for_pid(pid)
            if own is not None:
                error_lines = self._error_lines_in(own)
                if error_lines:
                    return str(own), "\n".join(error_lines[-12:])
                return (
                    str(own),
                    "(no error/exception events -- this capture's client log is clean, so the "
                    "failure was reported through a command result rather than a dropped socket; "
                    "read the command results and the server log above.)",
                )

        # No PID (or its log never appeared): fall back to the old newest-with-an-error
        # scan, but label it, because it may belong to a different invocation.
        log_dir = self.repo_root / ".field-backdoor" / "logs"
        try:
            logs = sorted(log_dir.glob("*.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)
        except OSError:
            return None
        started = self._shared_field_started_at
        for log_path in logs[:4]:  # only the few most-recent invocations
            try:
                if started is not None and log_path.stat().st_mtime < started - 2.0:
                    continue
            except OSError:
                continue
            error_lines = self._error_lines_in(log_path)
            if error_lines:
                return (
                    f"{log_path} -- NOT PID-MATCHED to this capture, may belong to another "
                    "invocation",
                    "\n".join(error_lines[-12:]),
                )
        return None

    def _timeout_failure(
        self,
        *,
        exc: subprocess.TimeoutExpired,
        page_path: Path,
        output_path: Path,
        command: list[str],
        what: str,
        restart_reason: str,
    ) -> RuntimeError:
        """Diagnose a capture timeout, *then* restart, then build the error to raise.

        The order is the whole point. `_restart_shared_field_server` reopens
        `shared_log_path` in "w" mode, so restarting first erases the server-side log for
        the capture that just failed — which is why every non-timeout failure branch in
        this class already diagnoses before restarting, with a comment saying so. Until
        2026-08-21 none of the four *timeout* branches did, so a timeout — the most common
        capture failure there is — was the single mode that destroyed its own evidence and
        then reported, in the run summary, that a post-mortem had been saved. On that date a
        nightly CI docs build aborted on `02-camera-matrices.png` and could not be graded:
        no post-mortem was written, and the server log had been truncated eight seconds
        after the failure.

        `_run_capture_child` puts the watchdog's verdict in `exc.stderr` — "only N/M command
        results arrived before the outer limit" versus "all command results arrived, but
        field_backdoor.py did not exit within 10s". That one line is what separates a slow
        capture from a child that finished its work and would not exit, i.e. whether the
        budget or the lifecycle is at fault, so carry it into the message rather than
        dropping it with the exception.

        `format_timeout_failure_details` is what surfaces it, and it already existed for
        precisely this: it tails the backdoor stdout/stderr and the Field server log. It had
        **zero callers** until this function — the same shape the 2026-08-07 build hit and
        that `tools/test_docs_runner_failure_reporting.py` was written about, "the underlying
        diagnosis was sitting in a file it declined to open". Note it must run before the
        restart too, since it tails `shared_log_path`.
        """
        details = format_timeout_failure_details(
            exc, command=command, field_log_path=self.shared_log_path
        )
        diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=output_path)
        self._restart_shared_field_server(reason=restart_reason)
        subject = " ".join(part for part in ("Field docs runner", what, "timed out") if part)
        return RuntimeError(
            f"{subject} for {page_path.name} -> {output_path}.\n{details}\n\n{diagnosis}"
        )

    def _save_failure_report(self, *, output_path: Path, report: str) -> str | None:
        """Write the full both-sides post-mortem to a per-failure file; best-effort."""
        try:
            failures_dir = self.shared_log_path.parent / "docs-runner-failures"
            failures_dir.mkdir(parents=True, exist_ok=True)
            safe = re.sub(r"[^A-Za-z0-9._-]+", "_", output_path.name) or "capture"
            report_path = failures_dir / f"{safe}.log"
            report_path.write_text(report, encoding="utf-8")
            return str(report_path)
        except OSError as exc:
            LOG.warning("Field docs runner could not save failure report: %s", exc)
            return None

    def _http_health_ok(self, port: int, timeout: float = 1.5) -> bool:
        url = f"https://localhost:{port}/health"
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            with urllib.request.urlopen(url, timeout=timeout, context=ctx) as response:
                body = response.read().decode("utf-8", errors="replace").strip().lower()
                return body == "ok"
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            return False

    def _listener_pids(self) -> list[int]:
        if os.name == "nt":
            return []
        try:
            result = subprocess.run(
                ["lsof", f"-tiTCP:{self.backdoor_port}", "-sTCP:LISTEN"],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return []
        return [int(token) for token in result.stdout.split() if token.isdigit()]

    def _request_shared_field_shutdown(self) -> None:
        url = f"https://localhost:{self.backdoor_port}/api/shutdown-instance"
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        request = urllib.request.Request(url, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=3.0, context=ctx) as response:
                response.read()
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
            LOG.debug("Field docs runner shutdown request failed: %s", exc)

    def _ensure_shared_field_server(self) -> None:
        if self._http_health_ok(self.backdoor_port):
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            instance = field_instance_info(self.backdoor_port, ctx)
            problem = field_instance_problem(instance, self.repo_root)
            if problem is not None:
                raise RuntimeError(
                    f"Refusing to reuse Field docs server on port {self.backdoor_port}: {problem}. "
                    "Choose a fresh FIELD_DOCS_RUNNER_PORT or stop that server explicitly."
                )
            LOG.info("Field docs runner using existing Field server on port %s", self.backdoor_port)
            return

        outcome = self._start_shared_field_server_once()
        if outcome is None:
            return
        self._kill_shared_field_process()
        raise RuntimeError(
            f"Field docs runner could not start shared Field server on port {self.backdoor_port} "
            f"in one attempt: {outcome}. See {self.shared_log_path}"
        )

    def _ensure_semantic_sidecar(self) -> str | None:
        """Make the default completion backend actually available to the shared Field, and return its URL.

        Reuse a healthy sidecar for THIS checkout on the checkout-derived port, otherwise start one there.
        A reused sidecar remains externally owned. A process started by this docs run is owned by the run
        and is stopped from [close], including fail-fast exits; leaving it behind made the docs port look
        clean while a Gradle launcher and JVM continued running on the sidecar port.
        """
        if self._sidecar_url is not None or self._sidecar_checked:
            return self._sidecar_url
        self._sidecar_checked = True

        if os.environ.get(RUNNER_SIDECAR_ENV, "").strip().lower() in FALSE_VALUES:
            LOG.info("Field docs runner completion sidecar disabled by %s", RUNNER_SIDECAR_ENV)
            return None
        if not (self.repo_root / "tools" / "code-use-semantic-sidecar").is_dir():
            LOG.warning("Field docs runner found no tools/code-use-semantic-sidecar in %s", self.repo_root)
            return None

        # An inherited URL wins if it is serving this checkout -- someone who exported one meant it.
        configured = os.environ.get(SIDECAR_URL_ENV, "").strip()
        if configured and sidecar_workspace_status(configured, self.repo_root) == "ok":
            LOG.info("Field docs runner using configured completion sidecar %s", configured)
            self._sidecar_url = configured
            return self._sidecar_url

        port = derive_sidecar_port(self.repo_root)
        url = f"http://127.0.0.1:{port}"
        status = sidecar_workspace_status(url, self.repo_root)
        if status == "ok":
            LOG.info("Field docs runner reusing completion sidecar %s", url)
            self._sidecar_url = url
            return self._sidecar_url
        if status == "wrong":
            LOG.warning(
                "Field docs runner: %s serves a different workspace; completion captures will have no "
                "AA backend. Set %s to a sidecar for %s.", url, SIDECAR_PORT_ENV, self.repo_root
            )
            return None

        log_path = self.repo_root / "build" / "code-use-semantic-sidecar" / "docs-runner-sidecar.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        command = [
            str(self.repo_root / gradle_wrapper), "--no-daemon", "-p", "tools/code-use-semantic-sidecar", "run",
            f"--args=--port {port} --workspace {self.repo_root}",
        ]
        LOG.info("Field docs runner starting completion sidecar on port %s (log: %s)", port, log_path)
        popen_kwargs: dict[str, Any] = {
            "cwd": str(self.repo_root),
            "stdout": log_path.open("w", encoding="utf-8"),
            "stdin": subprocess.DEVNULL,
            "text": True,
        }
        popen_kwargs["stderr"] = subprocess.STDOUT
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True
        try:
            process = subprocess.Popen(command, **popen_kwargs)
        except OSError as exc:
            LOG.warning("Field docs runner could not start the completion sidecar: %s", exc)
            return None

        deadline = time.monotonic() + SIDECAR_START_DEADLINE_SEC
        while time.monotonic() < deadline:
            if process.poll() is not None:
                LOG.warning(
                    "Field docs runner completion sidecar exited with code %s before becoming healthy; "
                    "see %s", process.returncode, log_path
                )
                return None
            if sidecar_workspace_status(url, self.repo_root) == "ok":
                LOG.info("Field docs runner completion sidecar ready on %s", url)
                self._sidecar_url = url
                self._sidecar_started_by_runner = True
                self._sidecar_process = process
                return self._sidecar_url
            time.sleep(1.0)
        LOG.warning(
            "Field docs runner completion sidecar did not become healthy in %.0fs; see %s",
            SIDECAR_START_DEADLINE_SEC, log_path
        )
        return None

    def _stop_semantic_sidecar(self) -> None:
        """Stop only the completion sidecar this RunnerState started."""
        process = self._sidecar_process
        if not self._sidecar_started_by_runner or process is None:
            return
        LOG.info("Field docs runner stopping completion sidecar at %s", self._sidecar_url)
        if process.poll() is None:
            try:
                if os.name == "nt":
                    process.terminate()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)
        self._sidecar_process = None
        self._sidecar_started_by_runner = False

    def _start_shared_field_server_once(self) -> str | None:
        """Start one shared Field server and wait for health.

        Returns None on success; otherwise a short reason string describing why the
        attempt failed so the caller can preserve and surface it.
        """
        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        extra_args = shlex.split(os.environ.get(RUNNER_GRADLE_ARGS_ENV, ""))
        cmd = [str(self.repo_root / gradle_wrapper), "runJvm", *extra_args]
        if extra_args:
            LOG.info("Field docs runner extra Gradle arguments from %s: %s",
                     RUNNER_GRADLE_ARGS_ENV, shlex.join(extra_args))
        workspace = os.environ.get(RUNNER_WORKSPACE_ENV) or str(automation_workspace(self.repo_root))
        cmd.append(f"--args=--workspace {shlex.quote(workspace)}")

        self.shared_log_path.parent.mkdir(parents=True, exist_ok=True)
        log_handle = self.shared_log_path.open("w", encoding="utf-8")

        # The completion backend is decided by this env, at JVM start, and cannot be changed later: the
        # sidecar URL is read from `System.getenv`. So it has to be resolved BEFORE the server starts,
        # not lazily at the first completion capture.
        server_env = automation_server_environment(self.backdoor_port)
        sidecar_url = self._ensure_semantic_sidecar()
        if sidecar_url:
            server_env[SIDECAR_URL_ENV] = sidecar_url

        popen_kwargs: dict[str, Any] = {
            "cwd": str(self.repo_root),
            "stdout": log_handle,
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
            "text": True,
            "env": server_env,
        }
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True

        LOG.info("Field docs runner starting shared Field server on port %s", self.backdoor_port)
        self._shared_field_process = subprocess.Popen(cmd, **popen_kwargs)
        self._shared_field_started_by_runner = True
        self._shared_field_started_at = time.time()
        self._shared_field_jvm_pid = None
        # A fresh process: any earlier runner-initiated stop no longer explains an exit.
        self._shared_field_stop_reason = None
        self.server_starts += 1

        deadline = time.monotonic() + SERVER_START_DEADLINE_SEC
        while time.monotonic() < deadline:
            if self._capture_environment_check is not None:
                self._capture_environment_check()
            process = self._shared_field_process
            if process is None:
                return "start process handle was cleared"
            if process.poll() is not None:
                return f"process exited with code {process.returncode}"
            if self._http_health_ok(self.backdoor_port):
                LOG.info("Field docs runner shared Field server ready on port %s", self.backdoor_port)
                # The JVM is a child of the gradle wrapper, so its PID differs from
                # self._shared_field_process.pid. Capture the port listener's PID now,
                # while it is alive, so a later crash dump (hs_err_pid<PID>.log) can be
                # matched to this exact server even after the port listener is gone.
                listeners = self._listener_pids()
                self._shared_field_jvm_pid = listeners[0] if listeners else None
                return None
            time.sleep(0.5)

        return f"timed out after {SERVER_START_DEADLINE_SEC:.0f}s waiting for health"

    def _kill_shared_field_process(self) -> None:
        """Best-effort teardown of a shared Field process whose start failed.

        Unlike `_stop_shared_field_server`, this does not require a graceful backdoor
        shutdown or assert the port is released -- it just makes sure the process group
        we launched is gone before the failure is returned.
        """
        process = self._shared_field_process
        self._shared_field_process = None
        if process is None:
            return
        self._shared_field_stop_reason = "killed by the runner while cleaning up a failed server start"
        if process.poll() is None:
            try:
                if os.name == "nt":
                    process.terminate()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            except (ProcessLookupError, OSError):
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except (ProcessLookupError, OSError):
                    pass
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass

    def _ensure_runner_owned_field_server(self, *, reason: str) -> None:
        if self._shared_field_started_by_runner and self._http_health_ok(self.backdoor_port):
            return

        if self._shared_field_started_by_runner:
            self._stop_shared_field_server()
            self._ensure_shared_field_server()
            return

        if self._http_health_ok(self.backdoor_port):
            LOG.warning(
                "Field docs runner needs a fresh runner-owned Field server for %s; "
                "requesting shutdown of existing server on port %s",
                reason,
                self.backdoor_port,
            )
            self._request_shared_field_shutdown()
            deadline = time.monotonic() + 12.0
            while time.monotonic() < deadline:
                if not self._http_health_ok(self.backdoor_port, timeout=0.5):
                    break
                time.sleep(0.25)
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                raise RuntimeError(
                    "Field docs runner could not take ownership of the Field server on "
                    f"port {self.backdoor_port} for {reason}. Stop the stale Field process "
                    "or choose a fresh FIELD_DOCS_RUNNER_PORT."
                )

        self._ensure_shared_field_server()

    def _stop_shared_field_server(self) -> None:
        if not self._shared_field_started_by_runner:
            return
        process = self._shared_field_process
        if process is None:
            return
        self._shared_field_stop_reason = "stopped by the runner during teardown"
        LOG.info("Field docs runner stopping shared Field server")
        self._request_shared_field_shutdown()
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            if process.poll() is not None and not self._http_health_ok(self.backdoor_port, timeout=0.5):
                self._shared_field_process = None
                return
            if not self._http_health_ok(self.backdoor_port, timeout=0.5):
                self._shared_field_process = None
                return
            time.sleep(0.25)
        if process.poll() is None:
            try:
                if os.name == "nt":
                    process.terminate()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)

        # A Gradle daemon can parent the actual Field JVM outside the wrapper's
        # process group. Never report shutdown merely because the wrapper exited.
        if self._http_health_ok(self.backdoor_port, timeout=0.5):
            if os.name == "nt":
                raise RuntimeError(
                    f"Field docs runner wrapper exited but port {self.backdoor_port} is still serving; "
                    "stop that Field instance manually."
                )
            for pid in self._listener_pids():
                try:
                    os.kill(pid, signal.SIGINT)
                except ProcessLookupError:
                    pass
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline and self._http_health_ok(self.backdoor_port, timeout=0.5):
                time.sleep(0.25)
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                for pid in self._listener_pids():
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                raise RuntimeError(f"Field docs runner could not release port {self.backdoor_port}")
        self._shared_field_process = None

    def _restart_shared_field_server(self, *, reason: str) -> None:
        if not self._shared_field_started_by_runner:
            return
        LOG.warning("Field docs runner restarting shared Field server: %s", reason)
        self.server_restarts += 1
        self._captures_since_server_restart = 0
        # The warm-session daemon holds a WebSocket to this server; it is now stale.
        self._stop_session(reason="shared server restart")
        self._stop_shared_field_server()
        self._ensure_shared_field_server()

    # ----- Warm-session daemon (opt-in) -------------------------------------
    def _ensure_session(self) -> Path | None:
        """Start (once) and return the socket path for the warm-session daemon.

        The daemon reuses the already-running shared Field on backdoor_port and
        keeps one Playwright page attached, so eligible captures become cheap
        socket batches instead of fresh field_backdoor.py processes. Returns None
        (disabling the fast path) if it cannot be brought up.
        """
        if not self.session_enabled:
            return None
        if self._session_process is not None and self._session_process.poll() is None:
            return self._session_sock_path
        field_session = self.repo_root / "tools" / "field_session.py"
        if not field_session.exists():
            LOG.warning("Field docs runner warm-session disabled: %s not found", field_session)
            self.session_enabled = False
            return None
        if self._session_sock_path.exists():
            try:
                self._session_sock_path.unlink()
            except OSError:
                pass
        cmd = [
            sys.executable, str(field_session), "serve",
            "--port", str(self.backdoor_port),
            "--with-playwright",
            "--timeout", "45",
        ]
        self._session_log_path = self.repo_root / ".field-backdoor" / "logs" / f"docs-session-{self.backdoor_port}.log"
        self._session_log_path.parent.mkdir(parents=True, exist_ok=True)
        session_log = self._session_log_path.open("w", encoding="utf-8")
        LOG.info("Field docs runner starting warm-session daemon on port %s (log: %s)", self.backdoor_port, self._session_log_path)
        self._session_process = subprocess.Popen(
            cmd, cwd=str(self.repo_root),
            stdout=session_log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            text=True,
            **({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}),
        )
        deadline = time.monotonic() + PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC + 30.0
        while time.monotonic() < deadline:
            if self._session_process.poll() is not None:
                LOG.warning(
                    "Field docs runner warm-session daemon exited early; disabling fast path (see %s)",
                    self._session_log_path,
                )
                self._session_process = None
                self.session_enabled = False
                return None
            if self._session_sock_path.exists():
                try:
                    pong = self._session_request({"command": "ping"}, timeout=5.0)
                    if pong.get("pong"):
                        self._session_box_count = 0
                        return self._session_sock_path
                except OSError:
                    pass
            time.sleep(0.25)
        LOG.warning("Field docs runner warm-session daemon did not become ready; disabling fast path")
        self._stop_session(reason="daemon startup timeout")
        self.session_enabled = False
        return None

    def _session_request(self, request: dict[str, Any], *, timeout: float) -> dict[str, Any]:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            sock.connect(str(self._session_sock_path))
            sock.sendall((json.dumps(request) + "\n").encode("utf-8"))
            chunks: list[bytes] = []
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                chunks.append(chunk)
                if b"\n" in chunk:
                    break
        raw = b"".join(chunks)
        if not raw:
            raise OSError("empty response from warm-session daemon")
        return json.loads(raw.split(b"\n", 1)[0].decode("utf-8"))

    def _stop_session(self, *, reason: str) -> None:
        process = self._session_process
        if process is None:
            return
        LOG.info("Field docs runner stopping warm-session daemon: %s", reason)
        try:
            if process.poll() is None and self._session_sock_path.exists():
                self._session_request({"command": "shutdown"}, timeout=5.0)
        except OSError:
            pass
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            try:
                if os.name == "nt":
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
        self._session_process = None
        if self._session_sock_path.exists():
            try:
                self._session_sock_path.unlink()
            except OSError:
                pass

    def _try_session_capture(
        self,
        *,
        page_path: Path,
        block_index: int,
        screenshot_path: Path,
        options: RunnerOptions,
        source_image_path: Path | None,
        source_image_min_mtime: float | None,
        code: str,
    ) -> Any | None:
        """Run one eligible capture through the warm session.

        Returns a subprocess.CompletedProcess-like object ONLY when the capture
        is fully clean (rc 0, no execution error, no output gap, file written);
        otherwise returns None so the caller falls back to the authoritative
        per-process path. This never lets warm-session/page-reuse state produce a
        false failure.
        """
        sock = self._ensure_session()
        if sock is None:
            return None
        # One scratch doc for the whole session (the daemon opened it at startup;
        # re-resetting mid-session breaks the output pipeline). Every capture's box
        # is created at the ORIGIN -- in the viewport render-zone -- then deleted
        # right after the screenshot, so exactly zero capture boxes accumulate and
        # an unbounded number of captures reuse one never-re-reset doc. Cap the
        # per-command timeout low so a capture the warm page can't produce fails
        # fast and the process fallback re-runs it with the full options.timeout.
        attempt_timeout = min(options.timeout, SESSION_ATTEMPT_TIMEOUT_SEC)
        session_options = replace(
            options, box_x=SESSION_BOX_ORIGIN_X, box_y=SESSION_BOX_ORIGIN_Y, timeout=attempt_timeout,
        )
        invocation = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=screenshot_path,
            options=session_options,
            source_image_path=source_image_path,
            source_image_min_mtime=source_image_min_mtime,
        )
        # Reset the scratch doc only on the very first capture of the run (this
        # establishes the output pipeline). Never re-reset mid-run -- a second
        # open_document in a warm session breaks display output entirely.
        request = {
            "commands": invocation.backdoor_commands,
            "stdin": code,
            "withPlaywright": True,
            "resetDoc": self._session_box_count == 0,
            "timeout": attempt_timeout,
            "document": invocation.document,
        }
        try:
            resp = self._session_request(
                request, timeout=runner_subprocess_timeout(options=session_options, is_video=False) + 10.0,
            )
        except (OSError, ValueError) as exc:
            self._stop_session(reason="request failure")
            return self._session_fallback(f"request failed: {exc}")
        self._session_box_count += 1
        results = resp.get("results") or []
        # Delete every box this capture created (parsed from the batch results) so
        # the board is clean before the next capture stamps its box at the same
        # origin -- otherwise a leftover box there would ghost the next screenshot.
        # Runs on success AND on a miss (the batch may have stopped before an
        # in-band delete), so overlap is impossible either way.
        self._session_cleanup_boxes(self._session_created_box_uids(results))
        stdout = "\n".join(json.dumps(r) for r in results)
        completed = SimpleNamespace(returncode=0 if resp.get("ok") else 1, stdout=stdout, stderr=resp.get("error", "") or "")
        if completed.returncode != 0:
            failing = next((r for r in results if not r.get("ok")), None)
            detail = resp.get("error") or (
                f"{failing.get('command')}: {(failing.get('data') or {}).get('error', 'failed')}"
                if isinstance(failing, dict) else "unknown"
            )
            return self._session_fallback(f"batch not ok ({detail})")
        if extract_backdoor_execution_error(stdout) is not None:
            return self._session_fallback("execution error in batch")
        if extract_backdoor_output_gap(stdout=stdout, code=code, options=options) is not None:
            return self._session_fallback("output gap in batch")
        if not screenshot_path.exists():
            return self._session_fallback("no output file produced")
        self._session_failures = 0
        self.session_captures += 1
        return completed

    @staticmethod
    def _session_created_box_uids(results: list[Any]) -> list[str]:
        """Uids of the boxes a batch created, in order, parsed from new-box results."""
        uids: list[str] = []
        for result in results:
            if not isinstance(result, dict) or not str(result.get("command", "")).startswith("new-box"):
                continue
            data = result.get("data")
            box = data.get("box") if isinstance(data, dict) else None
            uid = box.get("uid") if isinstance(box, dict) else None
            if isinstance(uid, str) and uid:
                uids.append(uid)
        return uids

    def _session_cleanup_boxes(self, uids: list[str]) -> None:
        """Best-effort delete of a capture's boxes so nothing lingers at the origin.

        A separate, cheap socket batch (no Playwright, no doc reset). Failure here
        never fails the capture -- the screenshot is already on disk -- but a clean
        board is required for the next origin box not to overlap, so we log misses.
        """
        if not uids:
            return
        if self._session_process is None or self._session_process.poll() is not None:
            return
        try:
            resp = self._session_request(
                {
                    "commands": [shlex.join(["delete-box", *uids, "--timeout", "15"])],
                    "withPlaywright": False,
                    "resetDoc": False,
                    "timeout": 15.0,
                },
                timeout=30.0,
            )
        except OSError as exc:
            LOG.warning("Field docs runner warm-session cleanup delete failed: %s", exc)
            return
        if not resp.get("ok"):
            LOG.warning("Field docs runner warm-session cleanup delete not ok: %s", json.dumps(resp)[:300])

    def _session_fallback(self, reason: str) -> None:
        """Record a warm-session miss and trip the circuit breaker if needed.

        A failed warm batch can leave more than its daemon in a bad state: the
        shared Field JVM, scratch document, or Playwright page may also be
        poisoned.  Recycle the runner-owned server before the authoritative
        per-process retry instead of letting that retry inherit warm state.
        """
        self._session_failures += 1
        LOG.warning(
            "Field docs runner warm-session miss (%s/%s): %s; using per-process path",
            self._session_failures, SESSION_MAX_FAILURES, reason,
        )
        if self._session_failures >= SESSION_MAX_FAILURES:
            LOG.warning(
                "Field docs runner disabling warm-session fast path after %s misses; "
                "remaining captures use the per-process path",
                self._session_failures,
            )
            self.session_enabled = False
            self._stop_session(reason="circuit breaker tripped")
            self._restart_shared_field_server(reason=f"warm-session miss: {reason}")
        return None

    def _load_cache(self) -> tuple[dict[str, str], dict[str, dict[str, Any]]]:
        try:
            payload = json.loads(self.cache_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}, {}
        except Exception as exc:
            LOG.warning("Field docs runner ignored unreadable cache %s: %s", self.cache_path, exc)
            return {}, {}
        if not isinstance(payload, dict):
            return {}, {}
        entries = payload.get("entries")
        if not isinstance(entries, dict):
            return {}, {}

        cache: dict[str, str] = {}
        metadata: dict[str, dict[str, Any]] = {}
        for key, value in entries.items():
            cache_key = str(key)
            if isinstance(value, str):
                cache[cache_key] = value
                continue
            if not isinstance(value, dict):
                continue
            digest = value.get("digest")
            if not isinstance(digest, str):
                continue
            cache[cache_key] = digest
            entry_metadata = value.get("metadata")
            if isinstance(entry_metadata, dict):
                metadata[cache_key] = entry_metadata
        return cache, metadata

    def save(self) -> None:
        if getattr(self, "_active_capture_transaction", None) is not None:
            return  # Cache certification follows publication, never precedes it.
        if not self.dirty:
            return
        entries: dict[str, Any] = {}
        for cache_key, digest in self.cache.items():
            metadata = self.cache_metadata.get(cache_key)
            if metadata is None:
                entries[cache_key] = digest
            else:
                entries[cache_key] = {
                    "digest": digest,
                    "metadata": metadata,
                }
        payload = {
            "version": SCRIPT_VERSION,
            "mode": self.runner_mode,
            "entries": entries,
        }
        self.cache_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        self.dirty = False

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self.environment is not None:
            self.environment.close()
        try:
            self.log_summary()
            self.save()
        finally:
            try:
                self._stop_session(reason="runner close")
            finally:
                try:
                    self._stop_shared_field_server()
                finally:
                    self._stop_semantic_sidecar()
                    if self.environment is not None:
                        self.environment.write_report(self.repo_root / "build/docs-environment.json", self.environment_outcomes)

    @contextmanager
    def _capture_transaction(self):
        if getattr(self, "_active_capture_transaction", None) is not None:
            yield
            return
        transaction = CaptureTransaction()
        fields = ("cache", "cache_metadata", "dirty", "screenshots_run", "videos_run",
                  "audio_run", "ui_drives_run", "cache_hits", "assets_reused")
        previous = {name: value.copy() if isinstance(value, dict) else value
                    for name in fields if (value := getattr(self, name, None)) is not None}
        self._active_capture_transaction = transaction
        try:
            yield
            transaction.publish()
        except BaseException:
            for name, value in previous.items():
                setattr(self, name, value)
            raise
        finally:
            self._active_capture_transaction = None
            transaction.close()
        self.save()

    def _stage_capture_output(self, destination: Path) -> Path:
        return self._active_capture_transaction.stage(destination)

    def capture_with_environment(self, method, **kwargs):
        if self.environment is None:
            self.environment = VerificationEnvironment(disk_path=self.repo_root).start()
        options = kwargs["options"]
        needs_display = options.graphics_context or options.capture == "native-window"
        started = False
        try:
            token = self.environment.checkpoint(require_display=needs_display)
            self._capture_environment_check = lambda: self.environment.check(token, require_display=needs_display)
            started = True
            with self._capture_transaction():
                try:
                    self._ensure_runner_owned_field_server(reason="environment-checked docs capture")
                    method(**kwargs)
                except Exception:
                    self.environment.check(token, require_display=needs_display, force=True)
                    raise
                self.environment.check(token, require_display=needs_display, force=True)
        except VerificationEnvironmentError as exc:
            self.environment_outcomes.append({"page": str(kwargs["page_path"]),
                "block": kwargs["block_index"], "status": "interrupted" if started else "not-run",
                "reason": str(exc)})
            if started:
                # A result produced across a lost prerequisite is not a certified cache hit.
                for value in (options.screenshot, options.video, options.audio):
                    if value:
                        key = str(resolve_output_path(kwargs["page_path"], value))
                        self.cache.pop(key, None)
                        self.cache_metadata.pop(key, None)
                self.dirty = True
                self.save()
                self._stop_session(reason="verification environment interrupted")
                self._stop_shared_field_server()
            raise
        finally:
            self._capture_environment_check = None

    def log_summary(self) -> None:
        if self.summary_logged:
            return
        self.summary_logged = True
        elapsed = time.monotonic() - self.started_at
        regenerated = self.screenshots_run + self.videos_run + self.audio_run + self.ui_drives_run
        skipped = self.cache_hits + self.assets_reused + self.missing_assets
        LOG.info(
            "Field docs runner summary: regenerated=%s (screenshots=%s videos=%s audio=%s ui_drives=%s), "
            "skipped=%s (cache_hits=%s reused_existing=%s missing=%s), failures=%s, "
            "warm_session_captures=%s server_starts=%s server_restarts=%s elapsed=%.1fs",
            regenerated,
            self.screenshots_run,
            self.videos_run,
            self.audio_run,
            self.ui_drives_run,
            skipped,
            self.cache_hits,
            self.assets_reused,
            self.missing_assets,
            len(self.failures),
            self.session_captures,
            self.server_starts,
            self.server_restarts,
            elapsed,
        )
        if self.failures:
            # Distinguish honestly: fallbacks still render a real (committed) image;
            # broken failures leave the HTML pointing at a missing asset.
            LOG.warning(
                "Field docs runner had %s capture failure(s): %s used a committed fallback, "
                "%s left NO asset (broken HTML reference). Per-failure diagnoses saved under %s.",
                len(self.failures),
                len(self.fallback_failures),
                len(self.broken_failures),
                self.shared_log_path.parent / "docs-runner-failures",
            )
            for message in self.broken_failures:
                LOG.error("Field docs runner BROKEN asset (no fallback): %s", message)

    def _run_capture_child(
        self,
        command: list[str],
        *,
        options: RunnerOptions,
        label: str,
        is_video: bool,
        input_text: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """Run one backdoor capture with process-group and exit-grace safety."""
        expected_results = command.count("--command")

        def commands_complete(stdout: str) -> bool:
            return len(parse_backdoor_results(stdout)) >= expected_results

        outer_timeout = runner_subprocess_timeout(options=options, is_video=is_video)
        # Remember which backdoor invocation this was, so a failure diagnosis can find
        # *its* client log instead of whichever recent one happens to contain an error.
        self._last_capture_child_pid = None
        completed, duration_ms, timed_out = run_with_watchdog(
            command,
            cwd=self.repo_root,
            timeout=outer_timeout,
            heartbeat_interval=8.0,
            heartbeat=lambda message: LOG.info("Field docs runner%s", message),
            label=label,
            completion_probe=commands_complete,
            exit_grace=10.0,
            input_text=input_text,
            on_start=self._remember_capture_child_pid,
            check_environment=getattr(self, "_capture_environment_check", None),
        )
        if timed_out:
            emitted = len(parse_backdoor_results(completed.stdout))
            reason = (
                "all command results arrived, but field_backdoor.py did not exit within 10s"
                if emitted >= expected_results
                else f"only {emitted}/{expected_results} command results arrived before the outer limit"
            )
            stderr = completed.stderr + f"\nDocs capture watchdog: {reason}.\n"
            raise subprocess.TimeoutExpired(
                command,
                duration_ms / 1000.0,
                output=completed.stdout,
                stderr=stderr,
            )
        return completed

    @transactional_capture
    def ensure_video(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """Record a short video clip instead of taking a screenshot."""
        assert options.video is not None
        # Docs videos historically always recorded the editor pane. Respect a fence
        # that explicitly asks for a different surface — a box output-area card
        # (capture="box"), a native window (capture="native-window"), or a window —
        # so, e.g., a live GPU-probe card can be recorded directly. Fences that do not
        # name a video surface still record the editor.
        video_capture = options.capture if options.capture in {"box", "window", "native-window"} else "editor"
        video_options = replace(options, capture=video_capture)
        video_path = resolve_output_path(page_path, options.video)
        video_path.parent.mkdir(parents=True, exist_ok=True)
        cache_allowed = self.runner_mode != "always"
        scene_inputs = compute_scene_inputs_digest(self.repo_root, options.fabricate_scene)
        source_refs = self.source_refs_digest(code)
        cache_metadata = compute_cache_metadata(
            code=code,
            options=video_options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs,
            scene_inputs_digest=scene_inputs,
        )
        digest = compute_digest(
            code=code,
            options=video_options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs,
            scene_inputs_digest=scene_inputs,
        )
        cache_key = str(video_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and video_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit (video): %s", video_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=video_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing video asset after runner-only cache change: %s", video_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and video_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in video asset without cache entry: %s", video_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s (video): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=video_path,
                output_exists=video_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )
        video_path = self._stage_capture_output(video_path)
        if video_options.graphics_context:
            self._ensure_runner_owned_field_server(
                reason=f"graphics-context video for {page_path.name} -> {video_path}"
            )
        command = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=video_path,
            options=video_options,
            is_video=True,
        ).process_command
        LOG.info("Field docs runner recording video %s for %s", page_path.name, video_path)
        try:
            completed = self._run_capture_child(
                command,
                options=video_options,
                label=f"video {video_path.name}",
                is_video=True,
                input_text=code,
            )
        except subprocess.TimeoutExpired as exc:
            raise self._timeout_failure(
                exc=exc,
                page_path=page_path,
                output_path=video_path,
                command=command,
                what="video",
                restart_reason=f"timeout while recording {page_path.name} -> {video_path}",
            ) from exc
        deterministic = deterministic_execution_error(
            completed,
            page_path=page_path,
            output_path=video_path,
            command=command,
            expects_error=options.expects_error,
        )
        if deterministic is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=video_path)
            self._restart_shared_field_server(
                reason=f"execution error while recording {page_path.name} -> {video_path}"
            )
            raise RuntimeError(f"{deterministic}\n\n{diagnosis}")
        if completed.returncode != 0:
            details = format_completed_failure_details(
                completed,
                command=command,
                field_log_path=self.shared_log_path,
            )
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=video_path)
            self._restart_shared_field_server(
                reason=f"failed video capture for {page_path.name} -> {video_path}"
            )
            raise RuntimeError(
                f"Field docs runner video failed for {page_path.name} -> {video_path}.\n"
                f"{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=video_path)
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {video_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{execution_error}\n\n{diagnosis}"
            )
        output_gap = extract_backdoor_output_gap(stdout=completed.stdout, code=code, options=video_options)
        if output_gap is not None:
            raise RuntimeError(
                f"Field docs runner output validation failed for {page_path.name} -> {video_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{output_gap}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.videos_run += 1
        self.save()

    @transactional_capture
    def ensure_audio(self, *, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """audio="…": OFFLINE-render a registered synth scene to a real 16-bit PCM WAV.

        Parallel to `ensure_video`, but the "capture" is a deterministic, headless
        DSP render rather than a browser recording. The runner generates the box code
        (`<audio-renderer>.renderWav("<scene>", "<abs wav>")`), runs it in the shared
        Field JVM, and `wait-file`s the WAV into existence (with --fail-on-error-uid so
        a box error surfaces fast). No realtime engine, no audio device, no browser —
        the WAV is produced by the same offline block-render the plot gallery uses, so
        the committed asset regenerates byte-stably at docs-build.
        """
        assert options.audio is not None and options.audio_spec is not None
        audio_path = resolve_output_path(page_path, options.audio)
        audio_path.parent.mkdir(parents=True, exist_ok=True)

        # The box code the runner generates: an offline render straight to the WAV path.
        generated_code = (
            f"{options.audio_renderer}.renderWav("
            f"{json.dumps(options.audio_spec)}, {json.dumps(str(audio_path))})"
        )

        cache_allowed = self.runner_mode != "always"
        source_refs = self.source_refs_digest(generated_code)
        cache_metadata = compute_cache_metadata(
            code=generated_code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs,
        )
        digest = compute_digest(
            code=generated_code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs,
        )
        cache_key = str(audio_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and audio_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit (audio): %s", audio_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=audio_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing audio asset after runner-only cache change: %s", audio_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and audio_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in audio asset without cache entry: %s", audio_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s (audio): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=audio_path,
                output_exists=audio_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )

        audio_path = self._stage_capture_output(audio_path)
        # Keep the cache keyed on the authored path, but make the actual renderer
        # write only the staged file that wait-file and WAV validation inspect.
        generated_code = (
            f"{options.audio_renderer}.renderWav("
            f"{json.dumps(options.audio_spec)}, {json.dumps(str(audio_path))})"
        )
        command = build_audio_backdoor_command(
            repo_root=self.repo_root,
            audio_path=audio_path,
            options=options,
            newer_than=time.time(),
        )
        LOG.info("Field docs runner rendering audio %s -> %s (scene %r)", page_path.name, audio_path, options.audio_spec)
        try:
            completed = self._run_capture_child(
                command,
                options=options,
                label=f"audio {audio_path.name}",
                is_video=False,
                input_text=generated_code,
            )
        except subprocess.TimeoutExpired as exc:
            raise self._timeout_failure(
                exc=exc,
                page_path=page_path,
                output_path=audio_path,
                command=command,
                what="audio",
                restart_reason=f"timeout while rendering audio {audio_path}",
            ) from exc
        if completed.returncode != 0:
            details = format_completed_failure_details(completed, command=command, field_log_path=self.shared_log_path)
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=audio_path)
            self._restart_shared_field_server(reason=f"failed audio render for {audio_path}")
            raise RuntimeError(
                f"Field docs runner audio failed for {page_path.name} -> {audio_path}.\n{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=audio_path)
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {audio_path}.\n"
                f"Command: {shlex.join(command)}\n{execution_error}\n\n{diagnosis}"
            )
        # A real WAV must exist and carry a valid RIFF/WAVE header with a non-empty
        # data chunk — a zero-length or headerless file is a failed capture.
        if not audio_path.exists():
            raise RuntimeError(f"Field docs runner audio produced no file for {page_path.name} -> {audio_path}.")
        validate_wav_file(audio_path)
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.audio_run += 1
        self.save()

    @transactional_capture
    def ensure_ui_drive(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """capture="ui": drive the live editor with the fence's Kotlin script and
        capture a video and/or a still in one run. The `drive` transcript op does the
        recording + screenshotting itself, so both outputs come from a single Field run.
        """
        outputs: list[Path] = []
        if options.video:
            outputs.append(resolve_output_path(page_path, options.video))
        if options.screenshot:
            outputs.append(resolve_output_path(page_path, options.screenshot))
        if not outputs:
            raise RuntimeError(f"{page_path.name} capture=ui requires screenshot= and/or video=")
        for out in outputs:
            out.parent.mkdir(parents=True, exist_ok=True)
        primary = outputs[0]

        cache_allowed = self.runner_mode != "always"
        scene_inputs = compute_scene_inputs_digest(self.repo_root, options.fabricate_scene)
        source_refs = self.source_refs_digest(code)
        cache_metadata = compute_cache_metadata(
            code=code, options=options, page_path=page_path, block_index=block_index,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs, scene_inputs_digest=scene_inputs,
        )
        digest = compute_digest(
            code=code, options=options, page_path=page_path, block_index=block_index,
            runner_mode=self.runner_mode, runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs, scene_inputs_digest=scene_inputs,
        )
        cache_key = str(primary)
        cached_digest = self.cache.get(cache_key)
        if (cache_allowed and not options.force and cached_digest == digest
                and all(out.exists() for out in outputs)):
            LOG.info("Field docs runner cache hit (ui drive): %s", primary)
            self.cache_hits += 1
            return
        LOG.info(
            "Field docs runner cache %s (ui drive): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            primary,
        )
        staged = {out: self._stage_capture_output(out) for out in outputs}
        options = replace(options,
            video=str(staged[resolve_output_path(page_path, options.video)]) if options.video else None,
            screenshot=str(staged[resolve_output_path(page_path, options.screenshot)]) if options.screenshot else None)
        outputs = list(staged.values())
        primary = outputs[0]
        command = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=primary,
            options=options,
            code=code,
        ).process_command
        LOG.info("Field docs runner driving UI %s for %s", page_path.name, primary)
        # Remember where the Field log ends, so the completion guard below reads only THIS capture's lines.
        completion_log_offset = self._shared_log_size() if options.warm_completion else 0
        try:
            completed = self._run_capture_child(
                command,
                options=options,
                label=f"ui drive {primary.name}",
                is_video=True,
            )
        except subprocess.TimeoutExpired as exc:
            raise self._timeout_failure(
                exc=exc,
                page_path=page_path,
                output_path=primary,
                command=command,
                what="ui drive",
                restart_reason=f"timeout ui drive {primary}",
            ) from exc
        deterministic = deterministic_execution_error(
            completed,
            page_path=page_path,
            output_path=primary,
            command=command,
            expects_error=options.expects_error,
        )
        if deterministic is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=primary)
            self._restart_shared_field_server(reason=f"execution error ui drive {primary}")
            raise RuntimeError(f"{deterministic}\n\n{diagnosis}")
        if completed.returncode != 0:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=primary)
            details = format_completed_failure_details(
                completed, command=command, field_log_path=self.shared_log_path
            )
            self._restart_shared_field_server(reason=f"failed ui drive for {primary}")
            raise RuntimeError(
                f"Field docs runner ui drive failed for {page_path.name} -> {primary}.\n"
                f"{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=primary)
            raise RuntimeError(
                f"Field docs runner ui drive execution error for {page_path.name} -> {primary}.\n"
                f"Command: {shlex.join(command)}\n{execution_error}\n\n{diagnosis}"
            )
        missing = [str(out) for out in outputs if not out.exists()]
        if missing:
            raise RuntimeError(
                f"Field docs runner ui drive produced no output for {page_path.name}: missing {', '.join(missing)}.\n"
                f"Command: {shlex.join(command)}"
            )
        if options.warm_completion:
            self._assert_completion_served_by_aa(
                page_path=page_path, output_path=primary,
                log_offset=completion_log_offset, command=command,
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.ui_drives_run += 1
        self.save()

    @transactional_capture
    def ensure_screenshot(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        screenshot_path = resolve_output_path(page_path, options.screenshot)
        screenshot_path.parent.mkdir(parents=True, exist_ok=True)
        cache_allowed = self.runner_mode != "always"
        scene_inputs = compute_scene_inputs_digest(self.repo_root, options.fabricate_scene)
        source_refs = self.source_refs_digest(code)
        cache_metadata = compute_cache_metadata(
            code=code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs,
            scene_inputs_digest=scene_inputs,
        )
        digest = compute_digest(
            code=code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
            source_refs_digest=source_refs,
            scene_inputs_digest=scene_inputs,
        )
        cache_key = str(screenshot_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and screenshot_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit: %s", screenshot_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=screenshot_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing screenshot asset after runner-only cache change: %s", screenshot_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and screenshot_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in screenshot asset without cache entry: %s", screenshot_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s: %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=screenshot_path,
                output_exists=screenshot_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )
        screenshot_path = self._stage_capture_output(screenshot_path)
        source_image_path = (
            resolve_auxiliary_output_path(page_path, options.source_image)
            if options.source_image
            else None
        )
        source_image_min_mtime: float | None = None
        if source_image_path is not None:
            if path_is_relative_to(source_image_path, self.docs_dir):
                source_image_min_mtime = time.time()
            else:
                try:
                    source_image_path.unlink()
                except FileNotFoundError:
                    pass
        if not options.graphics_context and self._captures_since_server_restart >= 50:
            self._restart_shared_field_server(
                reason=f"periodic restart after {self._captures_since_server_restart} captures"
            )
        if options.graphics_context:
            self._ensure_runner_owned_field_server(
                reason=f"graphics-context capture for {page_path.name} -> {screenshot_path}"
            )
        invocation = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=screenshot_path,
            options=options,
            source_image_path=source_image_path,
            source_image_min_mtime=source_image_min_mtime,
        )
        command = invocation.process_command
        self.captures_started += 1
        LOG.info(
            "Field docs runner [%d] capturing %s%s -> %s",
            self.captures_started,
            page_path.name,
            " (graphics-context)" if options.graphics_context else "",
            screenshot_path.name,
        )
        capture_label = f"{page_path.name} -> {screenshot_path.name}"
        completed: subprocess.CompletedProcess[str] | None = None
        # Warm-session fast path (opt-in): reuse one persistent daemon + attached
        # page across non-graphics captures. Certification leaves this disabled.
        if self.session_enabled and invocation.session_eligible:
            completed = self._try_session_capture(
                page_path=page_path,
                block_index=block_index,
                screenshot_path=screenshot_path,
                options=options,
                source_image_path=source_image_path,
                source_image_min_mtime=source_image_min_mtime,
                code=code,
            )
            if completed is not None:
                LOG.info("Field docs runner warm-session capture ok: %s", screenshot_path)
        if completed is None:
            try:
                completed = self._run_capture_child(
                    command,
                    options=options,
                    label=capture_label,
                    is_video=False,
                    input_text=code,
                )
            except subprocess.TimeoutExpired as exc:
                raise self._timeout_failure(
                    exc=exc,
                    page_path=page_path,
                    output_path=screenshot_path,
                    command=command,
                    what="",
                    restart_reason=f"timeout while capturing {page_path.name} -> {screenshot_path}",
                ) from exc
        deterministic = deterministic_execution_error(
            completed,
            page_path=page_path,
            output_path=screenshot_path,
            command=command,
            expects_error=options.expects_error,
        )
        if deterministic is not None:
            # Diagnose BEFORE restarting: the restart reopens shared_log_path in
            # "w" mode, erasing the crashed server's log. Capture both ends first.
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=screenshot_path)
            self._restart_shared_field_server(
                reason=f"execution error while capturing {page_path.name} -> {screenshot_path}"
            )
            raise RuntimeError(f"{deterministic}\n\n{diagnosis}")
        if completed.returncode != 0:
            details = format_completed_failure_details(
                completed,
                command=command,
                field_log_path=self.shared_log_path,
            )
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=screenshot_path)
            self._restart_shared_field_server(
                reason=f"failed capture for {page_path.name} -> {screenshot_path}"
            )
            raise RuntimeError(
                f"Field docs runner failed for {page_path.name} -> {screenshot_path}.\n"
                f"{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if options.expects_error:
            # The other half of `expect-error=true`. A fence that claims to photograph a diagnostic
            # and photographs a clean run instead is a page making a promise its figure does not
            # keep, which is the failure mode this option would otherwise introduce.
            if execution_error is None:
                raise RuntimeError(
                    f"Field docs runner expected an execution error for {page_path.name} -> "
                    f"{screenshot_path} (the fence carries expect-error), but the box ran cleanly.\n"
                    f"Command: {shlex.join(command)}"
                )
        elif execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=screenshot_path)
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{execution_error}\n\n{diagnosis}"
            )
        output_gap = extract_backdoor_output_gap(stdout=completed.stdout, code=code, options=options)
        if output_gap is not None:
            field_log = tail_text(self.shared_log_path, max_lines=80)
            if field_log:
                output_gap = f"{output_gap}\n\nField server log tail:\n{field_log}"
            raise RuntimeError(
                f"Field docs runner output validation failed for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{output_gap}"
            )
        if options.source_image:
            copy_source_image(
                source_path=resolve_auxiliary_output_path(page_path, options.source_image),
                destination_path=screenshot_path,
                timeout_sec=options.timeout,
                min_mtime=source_image_min_mtime,
            )
        clip_screenshot(screenshot_path=screenshot_path, options=options, backdoor_stdout=completed.stdout)
        trim_screenshot_border(screenshot_path=screenshot_path, options=options)
        visual_gap = validate_screenshot_pixels(screenshot_path=screenshot_path, options=options)
        if visual_gap is not None:
            raise RuntimeError(
                f"Field docs runner visual validation failed for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{visual_gap}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.screenshots_run += 1
        self._captures_since_server_restart += 1
        self.save()
        if options.graphics_context:
            # A graphics capture owns a disposable GPU context. Recycling it is part of the
            # lifecycle contract, not optional cleanup: if teardown/startup fails, preserve that
            # first failure instead of letting the next capture inherit ambiguous GPU state.
            self._restart_shared_field_server(
                reason=f"completed graphics-context capture for {page_path.name} -> {screenshot_path}"
            )


STATE: RunnerState | None = None


def on_post_build(config: Any) -> None:
    del config
    if STATE is not None:
        if STATE.failures:
            LOG.warning("Field docs runner skipped %s asset(s) due to capture failures", len(STATE.failures))
            for failure in STATE.failures:
                LOG.warning("Field docs runner skipped asset detail: %s", failure)
        STATE.save()
        STATE.close()
        if STATE.environment_outcomes:
            raise VerificationEnvironmentError(
                f"Docs verification incomplete: {len(STATE.environment_outcomes)} capture(s) not run or interrupted; "
                f"see {STATE.repo_root / 'build/docs-environment.json'}"
            )


def ensure_state(config: Any) -> RunnerState:
    global STATE
    if STATE is None:
        STATE = RunnerState(config)
    return STATE


def process_markdown(*, markdown: str, page_path: Path, state: RunnerState, files: Any) -> str:
    markdown = rewrite_source_citations(
        markdown,
        depth=page_directory_depth(page_path, state.docs_dir),
    )
    lines = markdown.splitlines(keepends=True)
    out: list[str] = []
    block_index = 0
    passthrough_fence: str | None = None
    previous_code_by_language: dict[str, str] = {}
    i = 0
    while i < len(lines):
        line = lines[i]
        if passthrough_fence is not None:
            out.append(line)
            if re.match(rf"^[ ]{{0,3}}{re.escape(passthrough_fence)}\s*(?:\r?\n)?$", line):
                passthrough_fence = None
            i += 1
            continue

        open_match = FENCE_OPEN_RE.match(line)
        if open_match is None:
            out.append(line)
            generic_match = GENERIC_FENCE_OPEN_RE.match(line)
            if generic_match is not None:
                passthrough_fence = generic_match.group("fence")
            i += 1
            continue

        start_line = i + 1
        block_index += 1
        open_line = line
        fence_name = open_match.group("runner")
        i += 1
        body: list[str] = []
        closing_line: str | None = None
        closing_re = re.compile(rf"^[ ]{{0,3}}{re.escape(open_match.group('fence'))}\s*(?:\r?\n)?$")
        while i < len(lines):
            candidate = lines[i]
            if closing_re.match(candidate):
                closing_line = candidate
                i += 1
                break
            body.append(candidate)
            i += 1
        if closing_line is None:
            raise RuntimeError(f"Unclosed {fence_name} fence in {page_path}:{start_line}")

        language_key = fence_language_key(open_line=open_line, fence_name=fence_name)
        previous_code = previous_code_by_language.get(language_key)
        rewritten_body = maybe_inherit_before(
            lines=body,
            fence_name=fence_name,
            previous_code=previous_code,
        )

        if fence_name in RUNNER_FENCES:
            options = parse_runner_options(
                runner_name=fence_name,
                open_line=open_line,
                page_path=page_path,
                line_number=start_line,
                block_index=block_index,
            )
            retained_profile = not capture_profile_enabled(options.capture_profile)
            reuse_only = state.runner_mode == "never" or retained_profile
            if retained_profile:
                LOG.info("Retaining %s capture in %s (explicit profile not enabled on this host)", options.capture_profile, page_path.name)
                for asset in (options.screenshot, options.video, options.audio):
                    if asset and not resolve_output_path(page_path, asset).is_file():
                        raise RuntimeError(f"Missing retained {options.capture_profile} capture {asset}; restore the committed asset or recapture on its supported host")
            code = extract_runnable_code(
                lines=rewritten_body,
                fence_name=fence_name,
                page_path=page_path,
                line_number=start_line,
                options=options,
            )
            code = wrap_runnable_code_for_language(code=code, language_key=language_key)
            options = maybe_enable_output_wait(options=options, code=code)
            runner_code = code_with_macro_digest(
                code=code,
                options=options,
                repo_root=state.repo_root,
                page_path=page_path,
            )
            screenshot_available = False
            video_available = False
            audio_available = False
            if options.capture == "ui":
                # One drive run produces the video and/or the still together.
                ui_screenshot_path = resolve_output_path(page_path, options.screenshot) if options.screenshot else None
                ui_video_path = resolve_output_path(page_path, options.video) if options.video else None
                if reuse_only or (state.runner_media == "video" and not options.video):
                    screenshot_available = bool(ui_screenshot_path and ui_screenshot_path.exists())
                    video_available = bool(ui_video_path and ui_video_path.exists())
                    for ok, path in ((screenshot_available, ui_screenshot_path), (video_available, ui_video_path)):
                        if path is None:
                            continue
                        state.assets_reused += 1 if ok else 0
                        state.missing_assets += 0 if ok else 1
                else:
                    primary_ui_path = ui_video_path or ui_screenshot_path
                    try:
                        state.capture_with_environment(state.ensure_ui_drive, code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        screenshot_available = bool(options.screenshot)
                        video_available = bool(options.video)
                    except RuntimeError as exc:
                        state.handle_capture_error(page_path=page_path, output_path=primary_ui_path, error=exc, continuing=state.continue_on_error)
                        if not state.continue_on_error and not isinstance(exc, VerificationEnvironmentError):
                            raise
                        # Readiness for embedding is separate from capture success: retain
                        # saved media in continuing builds without erasing the failed verdict.
                        screenshot_available = bool(ui_screenshot_path and ui_screenshot_path.is_file())
                        video_available = bool(ui_video_path and ui_video_path.is_file())
                site_dir = Path(state.config["site_dir"]).resolve()
                use_dir_urls = bool(state.config["use_directory_urls"])
                if screenshot_available and ui_screenshot_path:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=site_dir, use_directory_urls=use_dir_urls, output_path=ui_screenshot_path)
                if video_available and ui_video_path:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=site_dir, use_directory_urls=use_dir_urls, output_path=ui_video_path)
            elif options.screenshot:
                screenshot_path = resolve_output_path(page_path, options.screenshot)
                if reuse_only or state.runner_media == "video":
                    screenshot_available = screenshot_path.exists()
                    if screenshot_available:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner %s: %s screenshot asset %s",
                        "retained profile" if retained_profile else ("mode=never" if state.runner_mode == "never" else "media=video"),
                        "using existing" if screenshot_available else "skipping missing",
                        screenshot_path,
                    )
                else:
                    try:
                        state.capture_with_environment(state.ensure_screenshot, code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        screenshot_available = True
                    except RuntimeError as exc:
                        state.handle_capture_error(page_path=page_path, output_path=screenshot_path, error=exc, continuing=state.continue_on_error)
                        if not state.continue_on_error and not isinstance(exc, VerificationEnvironmentError):
                            raise
                        screenshot_available = screenshot_path.is_file()
                if screenshot_available:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=screenshot_path)
            if options.video and options.capture != "ui":
                video_path = resolve_output_path(page_path, options.video)
                if reuse_only:
                    video_available = video_path.exists()
                    if video_available:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner mode=never: %s video asset %s",
                        "using existing" if video_available else "skipping missing",
                        video_path,
                    )
                else:
                    try:
                        state.capture_with_environment(state.ensure_video, code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        video_available = True
                    except RuntimeError as exc:
                        state.handle_capture_error(page_path=page_path, output_path=video_path, error=exc, continuing=state.continue_on_error)
                        if not state.continue_on_error and not isinstance(exc, VerificationEnvironmentError):
                            raise
                        video_available = video_path.is_file()
                if video_available:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=video_path)
            if options.audio and options.capture != "ui":
                audio_path = resolve_output_path(page_path, options.audio)
                if reuse_only or state.runner_media == "video":
                    audio_available = audio_path.exists()
                    if audio_available:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner %s: %s audio asset %s",
                        "mode=never" if state.runner_mode == "never" else "media=video",
                        "using existing" if audio_available else "skipping missing",
                        audio_path,
                    )
                else:
                    try:
                        state.capture_with_environment(state.ensure_audio, options=options, page_path=page_path, block_index=block_index)
                        audio_available = True
                    except RuntimeError as exc:
                        state.handle_capture_error(page_path=page_path, output_path=audio_path, error=exc, continuing=state.continue_on_error)
                        if not state.continue_on_error and not isinstance(exc, VerificationEnvironmentError):
                            raise
                        audio_available = audio_path.is_file()
                if audio_available:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=audio_path)

        hide_source = fence_name in RUNNER_FENCES and options.hidden
        if not hide_source:
            out.append(open_line.replace(fence_name, RUNNER_TARGETS[fence_name], 1))
            out.extend(rewritten_body)
            out.append(closing_line)
        if fence_name in RUNNER_FENCES and options.embed:
            if options.screenshot and screenshot_available:
                out.append("\n")
                out.append(f"![{options.alt or options.box_name}]({markdown_path_for(page_path, options.screenshot)})\n")
            if options.video and video_available:
                # Emit a docs-root-relative path (e.g. "assets/foo.mp4"), the same
                # convention used for the screenshot embed above. zensical rewrites
                # raw-HTML src attributes for the page's URL depth just like it does
                # markdown links; handing it an already page-relative "../assets/..."
                # gets double-rewritten to "../../assets/..." and 404s on a site
                # served under a base path (e.g. /field4/).
                video_rel = markdown_path_for(page_path, options.video)
                out.append("\n")
                out.append(f'<video controls autoplay loop muted playsinline style="max-width:100%">\n')
                out.append(f'  <source src="{video_rel}" type="video/mp4">\n')
                out.append(f'</video>\n')
            if options.audio and audio_available:
                # Page-relative path, same convention as the screenshot/video embeds above;
                # zensical rewrites raw-HTML src attributes for the page's URL depth.
                audio_rel = markdown_path_for(page_path, options.audio)
                out.append("\n")
                out.append('<audio controls preload="metadata" style="width:100%">\n')
                out.append(f'  <source src="{audio_rel}" type="audio/wav">\n')
                out.append('</audio>\n')

        if not hide_source:
            previous_code_by_language[language_key] = extract_effective_code(
                lines=rewritten_body,
                fence_name=fence_name,
                page_path=page_path,
                line_number=start_line,
            )
        continue

    return "".join(out)


def parse_runner_options(*, runner_name: str, open_line: str, page_path: Path, line_number: int, block_index: int) -> RunnerOptions:
    raw_attrs = open_line.split(runner_name, 1)[1].strip()
    attrs = parse_attrs(raw_attrs)
    screenshot = first_attr(attrs, "screenshot", "path")
    source_image = first_attr(attrs, "source-image", "source_image", "native-image", "native_image")
    video = first_attr(attrs, "video")
    audio = first_attr(attrs, "audio")
    audio_spec = first_attr(attrs, "audio-spec", "audio_spec", "audioSpec", "sound", "scene")
    # Which harness owns the scene. Any object with `renderWav(name, outPath)` qualifies; the
    # default keeps every existing dough_dsp_sound.md fence hashing exactly as it did.
    audio_renderer = (
        first_attr(attrs, "audio-renderer", "audio_renderer", "audioRenderer")
        or "afield.four.synth.DoughDspSoundDocs"
    ).strip()
    if not screenshot and not video and not audio:
        raise RuntimeError(f"{page_path}:{line_number} {runner_name} requires screenshot=\"...\", video=\"...\" and/or audio=\"...\"")
    if audio and not audio_spec:
        raise RuntimeError(
            f"{page_path}:{line_number} audio=\"...\" requires audio-spec=\"<scene>\" "
            f"(a scene registered with {audio_renderer})"
        )

    capture = (first_attr(attrs, "capture", "mode") or "box").strip().lower()
    if capture not in {"box", "window", "window-editor", "editor", "native-window", "ui"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported capture={capture!r}")

    # capture="ui" runs the fence body as a `drive` transcript that scripts the live
    # editor (keypresses/clicks) via Playwright and captures a video and/or still. The
    # region= attribute selects what the recording/still is clipped to.
    region = (first_attr(attrs, "region") or "window-editor").strip().lower()
    if region not in {"box", "window", "window-editor", "editor"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported region={region!r}")

    execution = (first_attr(attrs, "execution", "execute", "run-mode") or "run").strip().lower()
    if execution not in {"run", "launch"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported execution={execution!r}")

    with_editor = capture == "window-editor" or parse_bool(first_attr(attrs, "with-editor", "with_editor"), default=False)
    # What this fence says its box's output IS, so readiness is checked against a stated
    # expectation instead of a default nobody chose. `panel` keeps the historical behaviour --
    # reveal the output panels and require visible, settled content in them. `inline` is for a
    # figure whose output appears beside the code in the editor: there is no panel, so demanding
    # one can never be satisfied and the capture can never pass. `none` asserts emptiness.
    #
    # `open-outputs` remains as the older spelling and still wins when a page sets it explicitly;
    # otherwise it follows from `outputs`, because "reveal the panels" is meaningless without one.
    expect_outputs = (first_attr(attrs, "outputs") or "panel").strip().lower()
    if expect_outputs not in {"panel", "inline", "none"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported outputs={expect_outputs!r}")
    open_outputs = parse_bool(
        first_attr(attrs, "open-outputs", "open_outputs"), default=expect_outputs == "panel"
    )
    if first_attr(attrs, "scale") is not None and capture != "box":
        raise RuntimeError(f"{page_path}:{line_number} scale= applies to capture=\"box\" stills only, not capture={capture!r}")
    wait_output = parse_bool(first_attr(attrs, "wait-output", "wait_output", "waitForOutput", "wait-for-output"), default=False)
    wait_output_kind = (first_attr(attrs, "wait-output-kind", "wait_output_kind") or "display").strip().lower()
    if wait_output_kind not in {"inline", "display", "all"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported wait-output-kind={wait_output_kind!r}")

    if capture == "window-editor":
        default_box_w = 1200.0
        default_box_h = 720.0
    elif capture == "window":
        if open_outputs:
            default_box_w = 960.0
            default_box_h = 180.0
        else:
            default_box_w = 420.0
            default_box_h = 120.0
    elif capture == "editor":
        default_box_w = 820.0
        default_box_h = 720.0
    else:
        # Keep box captures compact by default; output-aware clipping handles content sizing.
        if open_outputs:
            default_box_w = 960.0
            default_box_h = 180.0
        else:
            default_box_w = 420.0
            default_box_h = 120.0

    return RunnerOptions(
        capture_profile=first_attr(attrs, "capture-profile", "capture_profile"),
        screenshot=screenshot,
        source_image=source_image,
        capture=capture,
        execution=execution,
        wait=parse_float(first_attr(attrs, "wait"), default=0.75),
        padding=parse_float(first_attr(attrs, "padding"), default=16.0),
        timeout=parse_float(first_attr(attrs, "timeout"), default=45.0),
        property_name=first_attr(attrs, "property") or "code",
        embed=parse_bool(first_attr(attrs, "embed"), default=False),
        alt=first_attr(attrs, "alt"),
        document=first_attr(attrs, "document"),
        source_box=first_attr(attrs, "source-box", "source_box"),
        source_box_properties=parse_box_list(first_attr(attrs, "source-box-properties", "source_box_properties", "source-properties", "source_properties")),
        source_box_wait=parse_float(first_attr(attrs, "source-box-wait", "source_box_wait"), default=0.0),
        run_box=first_attr(attrs, "run-box", "run_box", "box-uid", "box_uid", "uid"),
        capture_box=first_attr(attrs, "capture-box", "capture_box", "target-box", "target_box", "target-uid", "target_uid"),
        setup_boxes=parse_box_list(first_attr(attrs, "setup-boxes", "setup_boxes", "setup")),
        macro_file=first_attr(attrs, "macro-file", "macro_file", "transcript-file", "transcript_file", "transcript"),
        macro_name=first_attr(attrs, "macro-name", "macro_name", "transcript-name", "transcript_name"),
        macro_selection=parse_box_list(first_attr(attrs, "macro-selection", "macro_selection", "selection")),
        fabricate_scene=parse_fabricate_scene(
            first_attr(attrs, "fabricate-scene", "fabricate_scene", "fabricateScene"),
            source_image=source_image,
            page_path=page_path,
            line_number=line_number,
        ),
        native_wait_frames=int(parse_float(first_attr(attrs, "native-wait-frames", "native_wait_frames"), default=30.0)),
        trim_native=parse_bool(first_attr(attrs, "trim-native", "trim_native", "native-trim", "native_trim", "auto-trim", "auto_trim"), default=False),
        trim=parse_bool(first_attr(attrs, "trim", "crop", "auto-crop", "auto_crop"), default=False),
        clip=parse_clip(first_attr(attrs, "clip")),
        viewport=parse_viewport(first_attr(attrs, "viewport")),
        scale=parse_capture_scale(first_attr(attrs, "scale")),
        box_name=first_attr(attrs, "name") or f"{page_path.stem}-{block_index}",
        box_x=parse_float(first_attr(attrs, "x"), default=24.0),
        box_y=parse_float(first_attr(attrs, "y"), default=24.0),
        box_w=parse_float(first_attr(attrs, "w"), default=default_box_w),
        box_h=parse_float(first_attr(attrs, "h"), default=default_box_h),
        open_outputs=open_outputs,
        expect_outputs=expect_outputs,
        wait_output=wait_output,
        wait_output_kind=wait_output_kind,
        wait_output_channel=first_attr(attrs, "wait-output-channel", "wait_output_channel"),
        wait_output_contains=first_attr(attrs, "wait-output-contains", "wait_output_contains", "output-contains", "contains"),
        with_editor=with_editor,
        graphics_context=parse_bool(
            first_attr(attrs, "graphics-context", "graphics_context", "graphicsContext"),
            default=False,
        ),
        force=parse_bool(first_attr(attrs, "force"), default=False),
        restart_target=parse_bool(
            first_attr(attrs, "restart-target", "restart_target", "restartTarget"),
            default=True,
        ),
        video=video,
        duration=parse_float(first_attr(attrs, "duration"), default=5.0),
        fps=int(parse_float(first_attr(attrs, "fps"), default=30.0)),
        region=region,
        clip_selector=first_attr(attrs, "clip-selector", "clip_selector", "clipSelector"),
        warm_completion=parse_bool(first_attr(attrs, "warm-completion", "warm_completion", "warmCompletion"), default=False),
        audio=audio,
        audio_spec=audio_spec,
        audio_renderer=audio_renderer,
        hidden=parse_bool(first_attr(attrs, "hidden", "hide-source", "hide_source"), default=False),
        language_key=fence_language_key(open_line=open_line, fence_name=runner_name),
        expects_error=parse_bool(
            first_attr(attrs, "expect-error", "expects-error", "expect_error", "expects_error"),
            default=False,
        ),
    )


def parse_attrs(raw: str) -> dict[str, str]:
    flattened = raw.replace("{", " ").replace("}", " ")
    attrs: dict[str, str] = {}
    for token in shlex.split(flattened):
        if token.startswith(".") or token.startswith("#"):
            continue
        if "=" in token:
            key, value = token.split("=", 1)
            attrs[key.strip()] = value.strip()
        elif token:
            attrs[token.strip()] = "true"
    return attrs


def first_attr(attrs: dict[str, str], *names: str) -> str | None:
    for name in names:
        if name in attrs:
            return attrs[name]
    return None


def parse_bool(raw: str | None, *, default: bool) -> bool:
    if raw is None:
        return default
    lowered = raw.strip().lower()
    if lowered in TRUE_VALUES:
        return True
    if lowered in FALSE_VALUES:
        return False
    raise RuntimeError(f"Unsupported boolean value {raw!r}")


def parse_float(raw: str | None, *, default: float) -> float:
    if raw is None:
        return default
    return float(raw)


def parse_box_list(raw: str | None) -> tuple[str, ...]:
    if raw is None:
        return ()
    return tuple(part for part in re.split(r"[\s,]+", raw.strip()) if part)


def resolve_runner_mode(raw: str | None) -> str:
    if raw is None or not raw.strip():
        return "incremental"
    lowered = raw.strip().lower()
    resolved = RUNNER_MODE_ALIASES.get(lowered)
    if resolved is None:
        supported = ", ".join(sorted(RUNNER_MODE_ALIASES))
        raise RuntimeError(f"Unsupported {RUNNER_MODE_ENV}={raw!r}; expected one of: {supported}")
    return resolved


def resolve_continue_on_error(raw: str | None, *, runner_mode: str) -> bool:
    if raw is None or not raw.strip():
        return runner_mode == "incremental"
    return parse_bool(raw, default=runner_mode == "incremental")


def fence_language_key(*, open_line: str, fence_name: str) -> str:
    attrs = parse_attrs(open_line.split(fence_name, 1)[1].strip())
    return first_attr(attrs, "data-lang", "data-language", "lang", "language") or "__default__"


def wrap_runnable_code_for_language(*, code: str, language_key: str) -> str:
    language = language_key.strip().lower()
    if language == "nano":
        source_literal = json.dumps(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: false
import afield.nano.nanoRunReportHtml

val __nanoSource = {source_literal}

display(
    "nano",
    nanoRunReportHtml(
        source = __nanoSource,
        title = "Nano state",
        includeSource = false,
        includeDisassembly = true,
        includeProgramBytes = true,
        rngSeed = 1
    )
)
""".strip()
        return wrapped + "\n"

    if language == "morpho":
        source_literal = json.dumps(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: true
import afield.docs.renderMorphoDocsSnippet

val __morphoSource = {source_literal}
renderMorphoDocsSnippet(__morphoSource)
""".strip()
        return wrapped + "\n"

    if language == "forth":
        source_literal = json.dumps(code)
        expects_svg = forth_source_likely_produces_svg(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: {"true" if expects_svg else "false"}
import afield.docs.renderForthDocsSnippet

val __forthSource = {source_literal}
renderForthDocsSnippet(__forthSource)
""".strip()
        return wrapped + "\n"

    return code


def forth_source_likely_produces_svg(source: str) -> bool:
    lowered = source.lower()
    direct_tokens = (
        " fline",
        "\nfline",
        " moveto",
        " moveto3",
        " lineto",
        " lineto3",
        " cubicto",
        " cubicto3",
        "fline-",
        's"fline"',
        " moveto ",
        " lineto ",
        " cubicto ",
    )
    return any(token in lowered for token in direct_tokens)


def parse_fabricate_scene(
    raw_scene: str | None,
    *,
    source_image: str | None,
    page_path: Path,
    line_number: int,
) -> str | None:
    if raw_scene is None or not raw_scene.strip():
        return None
    scene = raw_scene.strip()
    if scene not in FABRICATE_DOCS_SCENES:
        supported = ", ".join(sorted(FABRICATE_DOCS_SCENES))
        raise RuntimeError(f"{page_path}:{line_number} unsupported fabricate-scene={scene!r}; expected one of: {supported}")
    if not source_image:
        raise RuntimeError(f"{page_path}:{line_number} fabricate-scene requires source-image=\"...\"")
    return scene


def fabricate_docs_harness_code(*, scene: str, output_path: Path) -> str:
    method_name, ready_message = FABRICATE_DOCS_SCENES[scene]
    output_literal = json.dumps(str(output_path))
    ready_literal = json.dumps(ready_message)
    return f"""
import afield.four.Renderer
import afield.four.fabricated.StandardRendererDocsHarness
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()

val captureScene: suspend () -> Unit = {{
    StandardRendererDocsHarness.{method_name}({output_literal}, renderer = renderer)
    println({ready_literal})
}}
start(captureScene)
""".strip() + "\n"


def extract_runnable_code(*, lines: list[str], fence_name: str, page_path: Path, line_number: int, options: RunnerOptions) -> str:
    if options.fabricate_scene:
        assert options.source_image is not None
        return fabricate_docs_harness_code(
            scene=options.fabricate_scene,
            output_path=resolve_auxiliary_output_path(page_path, options.source_image),
        )
    if fence_name == "diff_runner":
        code = extract_diff_after_code(lines=lines, page_path=page_path, line_number=line_number)
    else:
        code = extract_effective_code(lines=lines, fence_name=fence_name, page_path=page_path, line_number=line_number)
    if not code.strip():
        raise RuntimeError(f"{page_path}:{line_number} {fence_name} fence did not contain runnable code")
    return code + "\n"


def extract_effective_code(*, lines: list[str], fence_name: str, page_path: Path, line_number: int) -> str:
    if fence_name in DIFF_FENCES:
        return extract_diff_result_code(lines=lines)
    return extract_static_code(lines=lines)


def extract_static_code(*, lines: list[str]) -> str:
    code_lines = [line.rstrip("\r\n") for line in lines]
    # Drop section AND fold markers; keep the code inside a fold region (it must run).
    filtered = [line for line in code_lines if not is_marker_line(line.strip())]
    return normalize_code_lines(filtered)


def extract_diff_result_code(*, lines: list[str]) -> str:
    before_lines, after_lines, _has_before, has_after = split_diff_sections(lines)
    selected = after_lines if has_after else before_lines
    return normalize_code_lines([line.rstrip("\r\n") for line in selected])


def extract_diff_after_code(*, lines: list[str], page_path: Path, line_number: int) -> str:
    code_lines = [line.rstrip("\r\n") for line in lines]
    mode = "before"
    saw_after = False
    after_lines: list[str] = []
    for line in code_lines:
        stripped = line.strip()
        if stripped == ":::before":
            mode = "before"
            continue
        if stripped == ":::after":
            mode = "after"
            saw_after = True
            continue
        if stripped in {":::static", ":::"} or stripped == ":::endfold" or stripped == ":::fold" or stripped.startswith(":::fold "):
            continue
        if mode == "after":
            after_lines.append(line)

    while after_lines and not after_lines[0].strip():
        after_lines.pop(0)
    while after_lines and not after_lines[-1].strip():
        after_lines.pop()

    code = "\n".join(after_lines).rstrip()
    if not saw_after or not code.strip():
        raise RuntimeError(f"{page_path}:{line_number} diff-style fences require a non-empty :::after section")
    return code


def maybe_inherit_before(*, lines: list[str], fence_name: str, previous_code: str | None) -> list[str]:
    if fence_name not in DIFF_FENCES or previous_code is None:
        return lines

    before_lines, after_lines, has_before, has_after = split_diff_sections(lines)
    if has_before or not has_after:
        return lines
    if any(line.strip() for line in before_lines):
        return lines

    return [":::before\n", *code_to_lines(previous_code), ":::after\n", *after_lines]


def split_diff_sections(lines: list[str]) -> tuple[list[str], list[str], bool, bool]:
    before_lines: list[str] = []
    after_lines: list[str] = []
    has_before = False
    has_after = False
    mode = "before"
    for line in lines:
        stripped = line.strip()
        if stripped == ":::before":
            has_before = True
            mode = "before"
            continue
        if stripped == ":::after":
            has_after = True
            mode = "after"
            continue
        if stripped in {":::static", ":::"} or stripped == ":::endfold" or stripped == ":::fold" or stripped.startswith(":::fold "):
            continue
        if mode == "after":
            after_lines.append(line)
        else:
            before_lines.append(line)
    return before_lines, after_lines, has_before, has_after


def code_to_lines(code: str) -> list[str]:
    if not code:
        return []
    return [f"{line}\n" for line in code.split("\n")]


def normalize_code_lines(lines: list[str]) -> str:
    trimmed = list(lines)
    while trimmed and not trimmed[0].strip():
        trimmed.pop(0)
    while trimmed and not trimmed[-1].strip():
        trimmed.pop()
    return "\n".join(trimmed).rstrip()


def resolve_output_path(page_path: Path, screenshot: str) -> Path:
    path = Path(screenshot)
    if path.is_absolute():
        return path
    return (page_path.parent / path).resolve()


def resolve_auxiliary_output_path(page_path: Path, path_value: str) -> Path:
    path = Path(path_value)
    if path.is_absolute():
        return path
    return (page_path.parent / path).resolve()


def markdown_path_for(page_path: Path, screenshot: str) -> str:
    absolute = resolve_output_path(page_path, screenshot)
    return Path(os.path.relpath(absolute, start=page_path.parent)).as_posix()



def register_generated_file(*, files: Any, docs_dir: Path, site_dir: Path, use_directory_urls: bool, output_path: Path) -> None:
    # Obsolete under Zensical: generated capture assets are copied into the built
    # site by tools/build_zensical_docs.py:copy_non_markdown_files, so there is no
    # live Files collection to register with (the build passes a no-op stub). Kept
    # as a no-op so the capture call sites need no change. The retired hook-based
    # build path used this to register each generated asset with the served site.
    del files, docs_dir, site_dir, use_directory_urls, output_path


def path_is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


class _RunnerDigestNormalizer(ast.NodeTransformer):
    """Keep capture-affecting Python semantics while discarding diagnostics and prose."""

    def visit_FunctionDef(self, node: ast.FunctionDef) -> Any:
        if node.name in RUNNER_DIAGNOSTIC_ONLY_DEFINITIONS:
            return None
        return self.generic_visit(node)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> Any:
        if node.name in RUNNER_DIAGNOSTIC_ONLY_DEFINITIONS:
            return None
        return self.generic_visit(node)

    def visit_Expr(self, node: ast.Expr) -> Any:
        # Docstrings and standalone explanatory strings do not affect execution.
        if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            return None
        # LOG calls are diagnostics even when embedded in an operational method. Their arguments are
        # intentionally ignored too: computing text for a log must not become capture semantics.
        if isinstance(node.value, ast.Call):
            function = node.value.func
            if (
                isinstance(function, ast.Attribute)
                and isinstance(function.value, ast.Name)
                and function.value.id == "LOG"
                and function.attr in {"debug", "info", "warning", "error", "exception"}
            ):
                return None
        return self.generic_visit(node)


def runner_digest_material(relative: str, data: bytes) -> bytes:
    """Bytes hashed for one runner source, normalized only for the large Python hook module."""
    if relative != "public/docs_hooks.py":
        return data
    try:
        tree = ast.parse(data.decode("utf-8"), filename=relative)
    except (UnicodeDecodeError, SyntaxError):
        # Broken source must still invalidate every cache entry; the later import will report the
        # useful syntax error. Falling back to raw bytes is safer than hiding an unparseable edit.
        return data
    normalized = _RunnerDigestNormalizer().visit(tree)
    return ast.dump(normalized, annotate_fields=True, include_attributes=False).encode("utf-8")


def compute_runner_source_digest(repo_root: Path) -> str:
    hasher = hashlib.sha256()
    for relative in RUNNER_DIGEST_SOURCE_FILES:
        path = (repo_root / relative).resolve()
        hasher.update(relative.encode("utf-8"))
        hasher.update(b"\0")
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            hasher.update(b"<missing>")
            continue
        hasher.update(hashlib.sha256(runner_digest_material(relative, data)).digest())
    return hasher.hexdigest()


REFERENCE_DIR_RELATIVE = "public/docs/reference"
# Below this length a name is too generic to attribute to a file ("of", "run", "map"); the
# codebase-reference generator applies the same kind of floor when it looks for page evidence.
SOURCE_REF_MIN_SYMBOL_LENGTH = 4
# A name declared in more than this many source files is dropped rather than mapped to all of them.
# The reference lists data-class members too, so `name`, `value`, `size`, `index`, `toString`,
# `step`, `points` … each appear in a dozen files; mapping those would attach nearly every fence to
# nearly every file. Measured over all 304 runner fences: keeping only *unambiguous* names gives
# 2.7 source files per fence (mean, max 10); allowing two gives 5.8; allowing all gives 10.1/max 44.
# Unambiguous still maps all 106 `functions_examples` fences onto `Functions.kt`.
SOURCE_REF_MAX_FILES_PER_SYMBOL = 1
# `direct` (default) hashes only the files a fence's code text names. `stdlib` hashes the whole
# indexed stdlib for every fence — the paranoid mode, which detects an *indirect* change (a callee of
# a callee) at the cost of re-capturing the entire site after any stdlib edit. Measured: the
# transitive file-reference closure of a typical fence is 72 of the 75 indexed files, i.e. depth > 1
# IS `stdlib`, which is why the default stops at depth 1 and `tools/docs_capture_audit.py` exists to
# catch what the digest cannot. `off` restores the pre-2026-07-29 digests exactly.
SOURCE_REFS_MODE_ENV = "FIELD_DOCS_SOURCE_REFS"
SOURCE_REFS_MODES = ("direct", "stdlib", "off")
IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
HTML_TAG_RE = re.compile(r"<[^>]+>")
HIGHLIGHT_CODE_RE = re.compile(r'<code class="highlight">(.*?)</code>', re.S)
REFERENCE_SOURCE_PATH_RE = re.compile(r"^`(src/[^`]+\.kt)`\s*$", re.M)
REFERENCE_TYPE_HEADING_RE = re.compile(r"^###+ (?!Top-level)(?P<name>[^{\n]+?)\s*\{", re.M)
DECLARED_FUNCTION_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s+)?(?:[\w.<>,?\s]+\.)?(?P<name>\w+)\s*\(")
DECLARED_VALUE_RE = re.compile(r"\b(?:const\s+)?va[lr]\s+(?:<[^>]*>\s+)?(?:[\w.<>,?\s]+\.)?(?P<name>\w+)\s*:")
DECLARED_TYPE_RE = re.compile(r"\b(?:class|object|interface)\s+(?P<name>\w+)")


def strip_reference_markup(fragment: str) -> str:
    """One `<code class="highlight">` declaration back to plain Kotlin signature text."""
    text = HTML_TAG_RE.sub("", fragment)
    for entity, replacement in (("&#95;", "_"), ("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&"), ("&quot;", '"')):
        text = text.replace(entity, replacement)
    return text


def load_source_symbol_index(repo_root: Path) -> dict[str, tuple[str, ...]]:
    """Map each public stdlib symbol name to the source file(s) declaring it.

    The map is read out of the **committed codebase reference**
    (`public/docs/reference/*.md`), which `afield.docs.StdlibReferenceGenerator` emits from
    `KotlinSourceIndex.parseTopLevelDefinitions` — the same scan that feeds the in-editor completion
    doc browser — and which the `docs.codebase-reference.generate` smoke keeps from drifting from
    the source. So this is not a second parser of Kotlin: it is a cheap read of an existing,
    drift-guarded index, which is why no Kotlin has to run to compute a digest.

    Declaration names are recovered by stripping the reference's syntax-highlight markup back to
    plain Kotlin and matching `fun` / `val` / `class` forms, plus each type's own `###` heading.
    Deliberately *narrow*: only declared names, never the types merely mentioned in a signature —
    mapping `Double` to every file that returns one would make the digest invalidate everything.

    Returns `{}` when the reference has not been generated; the digest then degrades to exactly the
    old runner-machinery behaviour rather than failing a build.
    """
    reference_dir = repo_root / REFERENCE_DIR_RELATIVE
    index: dict[str, set[str]] = {}
    if not reference_dir.is_dir():
        return {}
    for page in sorted(reference_dir.glob("*.md")):
        try:
            text = page.read_text(encoding="utf-8")
        except OSError:
            continue
        for section in text.split("\n## ")[1:]:
            path_match = REFERENCE_SOURCE_PATH_RE.search(section)
            if path_match is None:
                continue
            source_file = path_match.group(1)
            names: set[str] = set()
            for heading in REFERENCE_TYPE_HEADING_RE.finditer(section):
                candidate = heading.group("name").strip()
                if IDENTIFIER_RE.fullmatch(candidate):
                    names.add(candidate)
            for fragment in HIGHLIGHT_CODE_RE.finditer(section):
                declaration = strip_reference_markup(fragment.group(1))
                for pattern in (DECLARED_FUNCTION_RE, DECLARED_VALUE_RE, DECLARED_TYPE_RE):
                    for match in pattern.finditer(declaration):
                        names.add(match.group("name"))
            for name in names:
                if len(name) >= SOURCE_REF_MIN_SYMBOL_LENGTH:
                    index.setdefault(name, set()).add(source_file)
    return {
        name: tuple(sorted(files))
        for name, files in index.items()
        if len(files) <= SOURCE_REF_MAX_FILES_PER_SYMBOL
    }


def resolve_source_refs_mode(raw: str | None) -> str:
    lowered = (raw or "direct").strip().lower()
    if lowered in SOURCE_REFS_MODES:
        return lowered
    supported = ", ".join(SOURCE_REFS_MODES)
    raise RuntimeError(f"Unsupported {SOURCE_REFS_MODE_ENV}={raw!r}; supported: {supported}")


def fence_source_files(
    *,
    code: str,
    symbol_index: dict[str, tuple[str, ...]],
    mode: str = "direct",
) -> tuple[str, ...]:
    """The stdlib source files this fence's code text names (or all of them, in `stdlib` mode)."""
    if not symbol_index or mode == "off":
        return ()
    if mode == "stdlib":
        return tuple(sorted({source for files in symbol_index.values() for source in files}))
    reached: set[str] = set()
    for token in set(IDENTIFIER_RE.findall(code)):
        reached.update(symbol_index.get(token, ()))
    return tuple(sorted(reached))


def compute_source_refs_digest(
    *,
    repo_root: Path,
    code: str,
    symbol_index: dict[str, tuple[str, ...]],
    file_digest_cache: dict[str, str] | None = None,
    mode: str = "direct",
) -> str:
    """Hash the contents of every source file this fence reaches.

    Returns `""` when the fence reaches nothing (a pure-UI or forth/rectsl fence, say), which keeps
    the key out of the digest payload entirely so such a fence hashes exactly as it did before this
    existed.
    """
    files = fence_source_files(code=code, symbol_index=symbol_index, mode=mode)
    if not files:
        return ""
    cache = file_digest_cache if file_digest_cache is not None else {}
    hasher = hashlib.sha256()
    for relative in files:
        digest = cache.get(relative)
        if digest is None:
            try:
                digest = hashlib.sha256((repo_root / relative).read_bytes()).hexdigest()
            except OSError:
                digest = "<missing>"
            cache[relative] = digest
        hasher.update(relative.encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(digest.encode("utf-8"))
    return hasher.hexdigest()


def options_digest_payload(options: RunnerOptions) -> dict[str, Any]:
    payload = {
        "sourceImage": options.source_image,
        "capture": options.capture,
        "effectiveCapture": effective_capture_mode(options),
        "execution": options.execution,
        "wait": options.wait,
        "padding": options.padding,
        "timeout": options.timeout,
        "property": options.property_name,
        "document": options.document,
        "sourceBox": options.source_box,
        "sourceBoxProperties": list(options.source_box_properties),
        "sourceBoxWait": options.source_box_wait,
        "runBox": options.run_box,
        "captureBox": options.capture_box,
        "setupBoxes": list(options.setup_boxes),
        "macroFile": options.macro_file,
        "macroName": options.macro_name,
        "macroSelection": list(options.macro_selection),
        "fabricateScene": options.fabricate_scene,
        "nativeWaitFrames": options.native_wait_frames,
        "trimNative": options.trim_native,
        "boxName": options.box_name,
        "boxX": options.box_x,
        "boxY": options.box_y,
        "boxW": options.box_w,
        "boxH": options.box_h,
        "openOutputs": options.open_outputs,
        "expectOutputs": options.expect_outputs,
        "withEditor": options.with_editor,
        "graphicsContext": options.graphics_context,
        "video": options.video,
        "duration": options.duration,
        "fps": options.fps,
        "region": options.region,
        "clipSelector": options.clip_selector,
        "warmCompletion": options.warm_completion,
    }
    # Present only when set, so every other fence hashes byte-identically.
    if options.clip is not None:
        payload["clip"] = list(options.clip)
    if options.viewport is not None:
        payload["viewport"] = list(options.viewport)
    if options.scale is not None:
        payload["scale"] = options.scale
    if options.wait_output:
        payload.update({
            "waitOutput": True,
            "waitOutputKind": options.wait_output_kind,
            "waitOutputChannel": options.wait_output_channel,
            "waitOutputContains": options.wait_output_contains,
        })
    # Present only for audio= fences, so every other fence hashes byte-identically.
    if options.audio:
        payload["audio"] = options.audio
        payload["audioSpec"] = options.audio_spec
        # Only when it is not the historic default, so existing fences keep their digest and
        # do not all re-render on this change.
        if options.audio_renderer != "afield.four.synth.DoughDspSoundDocs":
            payload["audioRenderer"] = options.audio_renderer
    return payload


def digest_string(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def digest_json(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode("utf-8")).hexdigest()


def compute_cache_metadata(
    *,
    code: str,
    options: RunnerOptions,
    page_path: Path,
    block_index: int,
    runner_source_digest: str,
    scene_inputs_digest: str = "",
    source_refs_digest: str = "",
) -> dict[str, Any]:
    options_payload = options_digest_payload(options)
    metadata = {
        "page": str(page_path),
        "block": block_index,
        "scriptVersion": SCRIPT_VERSION,
        "runnerSourceDigest": runner_source_digest,
        "codeDigest": digest_string(code),
        "optionsDigest": digest_json(options_payload),
        "options": options_payload,
    }
    # Only bulk-render gallery scenes declare inputs; omitting the key otherwise
    # keeps every other fence's metadata byte-for-byte identical.
    if scene_inputs_digest:
        metadata["sceneInputs"] = scene_inputs_digest
    # Present only when the fence's code text actually names a symbol the codebase reference can
    # attribute to a source file, so a fence that reaches nothing hashes exactly as before.
    if source_refs_digest:
        metadata["sourceRefsDigest"] = source_refs_digest
    return metadata


def compute_digest(
    *,
    code: str,
    options: RunnerOptions,
    page_path: Path,
    block_index: int,
    runner_mode: str,
    runner_source_digest: str,
    scene_inputs_digest: str = "",
    source_refs_digest: str = "",
) -> str:
    payload = {
        "page": str(page_path),
        "block": block_index,
        "scriptVersion": SCRIPT_VERSION,
        "runnerSourceDigest": runner_source_digest,
        "code": code,
        "options": options_digest_payload(options),
    }
    # See compute_cache_metadata: the key is present only for gallery scenes, so
    # non-gallery fences hash to exactly the same digest as before this feature.
    if scene_inputs_digest:
        payload["sceneInputs"] = scene_inputs_digest
    # Same rule for the per-fence source coverage: absent unless the fence names something
    # attributable, so a fence that reaches no stdlib file keeps its historical digest.
    if source_refs_digest:
        payload["sourceRefs"] = source_refs_digest
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()


def short_digest(value: str | None) -> str:
    if not value:
        return "missing"
    return value[:12]


def copy_source_image(
    *,
    source_path: Path,
    destination_path: Path,
    timeout_sec: float,
    min_mtime: float | None = None,
) -> None:
    deadline = time.monotonic() + max(timeout_sec, 1.0)
    last_size = -1
    stable_reads = 0
    while time.monotonic() < deadline:
        if source_path.exists():
            stat = source_path.stat()
            size = stat.st_size
            if size > 0:
                if min_mtime is not None and stat.st_mtime < min_mtime:
                    time.sleep(0.1)
                    continue
                if size == last_size:
                    stable_reads += 1
                else:
                    stable_reads = 0
                last_size = size
                if stable_reads >= 1:
                    destination_path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source_path, destination_path)
                    return
        time.sleep(0.1)
    raise RuntimeError(f"Timed out waiting for source image {source_path}")


def tail_text(path: Path, *, max_lines: int) -> str:
    try:
        text = path.read_text(errors="replace")
    except FileNotFoundError:
        return ""
    lines = text.splitlines()
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def head_text(path: Path, *, max_lines: int) -> str:
    """First `max_lines` lines of a file (for crash-dump headers). Best-effort."""
    try:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            lines = [next_line.rstrip("\n") for _, next_line in zip(range(max_lines), handle)]
    except OSError:
        return ""
    return "\n".join(lines)


def tail_inline_text(text: str, *, max_lines: int) -> str:
    lines = text.strip().splitlines()
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def process_output_text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def format_completed_failure_details(
    completed: subprocess.CompletedProcess[str],
    *,
    command: list[str],
    field_log_path: Path | None,
    max_lines: int = 80,
) -> str:
    parts = [
        f"Return code: {completed.returncode}",
        f"Command: {shlex.join(command)}",
    ]
    stdout = tail_inline_text(process_output_text(completed.stdout), max_lines=max_lines)
    stderr = tail_inline_text(process_output_text(completed.stderr), max_lines=max_lines)
    if stdout:
        parts.append(f"Backdoor stdout tail:\n{stdout}")
    if stderr:
        parts.append(f"Backdoor stderr tail:\n{stderr}")
    field_log = tail_text(field_log_path, max_lines=max_lines) if field_log_path is not None else ""
    if field_log:
        parts.append(f"Field server log tail:\n{field_log}")
    return "\n\n".join(parts)


def format_timeout_failure_details(
    exc: subprocess.TimeoutExpired,
    *,
    command: list[str],
    field_log_path: Path | None,
    max_lines: int = 80,
) -> str:
    parts = [
        f"Timed out after {exc.timeout:.1f}s",
        f"Command: {shlex.join(command)}",
    ]
    stdout = tail_inline_text(process_output_text(exc.stdout), max_lines=max_lines)
    stderr = tail_inline_text(process_output_text(exc.stderr), max_lines=max_lines)
    if stdout:
        parts.append(f"Backdoor stdout tail before timeout:\n{stdout}")
    if stderr:
        parts.append(f"Backdoor stderr tail before timeout:\n{stderr}")
    field_log = tail_text(field_log_path, max_lines=max_lines) if field_log_path is not None else ""
    if field_log:
        parts.append(f"Field server log tail:\n{field_log}")
    return "\n\n".join(parts)


def cache_status_label(*, cache_allowed: bool, force: bool) -> str:
    if not cache_allowed:
        return "bypassed"
    if force:
        return "forced"
    return "miss"


def format_cache_reason(
    *,
    output_path: Path,
    output_exists: bool,
    cached_digest: str | None,
    computed_digest: str,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
    cache_allowed: bool,
    force: bool,
    runner_mode: str,
) -> str:
    reasons: list[str] = []
    if not cache_allowed:
        reasons.append(f"runner mode {runner_mode}")
    if force:
        reasons.append("force=true")
    if not output_exists:
        reasons.append("output file missing")
    if cached_digest is None:
        reasons.append("no cache entry")
    elif cached_digest != computed_digest:
        reasons.extend(compare_cache_metadata(cached_metadata=cached_metadata, current_metadata=current_metadata))
        reasons.append(f"digest {short_digest(cached_digest)} -> {short_digest(computed_digest)}")
    if not reasons:
        reasons.append("cache digest changed")
    return f"{output_path} ({', '.join(dict.fromkeys(reasons))})"


def compare_cache_metadata(
    *,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
) -> list[str]:
    if not isinstance(cached_metadata, dict):
        return ["digest changed", "previous cache metadata unavailable"]

    reasons: list[str] = []
    if cached_metadata.get("scriptVersion") != current_metadata.get("scriptVersion"):
        reasons.append("script version changed")
    if cached_metadata.get("runnerSourceDigest") != current_metadata.get("runnerSourceDigest"):
        reasons.append("runner source changed")
    if cached_metadata.get("sourceRefsDigest") != current_metadata.get("sourceRefsDigest"):
        reasons.append(
            "source this fence reaches changed"
            if cached_metadata.get("sourceRefsDigest") is not None
            else "per-fence source coverage adopted"
        )
    if cached_metadata.get("codeDigest") != current_metadata.get("codeDigest"):
        reasons.append("runnable code changed")
    if cached_metadata.get("optionsDigest") != current_metadata.get("optionsDigest"):
        reasons.append("capture options changed")
    if cached_metadata.get("page") != current_metadata.get("page"):
        reasons.append("page path changed")
    if cached_metadata.get("block") != current_metadata.get("block"):
        reasons.append("block index changed")
    if not reasons:
        reasons.append("digest changed")
    return reasons


def reuse_existing_asset_for_incremental(
    *,
    runner_mode: str,
    output_path: Path,
    options: RunnerOptions,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
) -> bool:
    if runner_mode != "incremental" or options.force or not output_path.exists():
        return False
    if not isinstance(cached_metadata, dict):
        return False
    stable_keys = ("page", "block", "scriptVersion", "codeDigest", "optionsDigest", "sceneInputs")
    if any(cached_metadata.get(key) != current_metadata.get(key) for key in stable_keys):
        return False
    cached_refs = cached_metadata.get("sourceRefsDigest")
    current_refs = current_metadata.get("sourceRefsDigest")
    if cached_refs is not None and cached_refs != current_refs:
        # A source file this fence actually *calls* moved. This is exactly the case that used to be
        # reused away, which is how 54 functions_examples figures stayed wrong on main for two weeks.
        # Never reuse here: re-capture.
        return False
    if cached_refs is None and current_refs is not None:
        # First build after per-fence source coverage was introduced: the cache predates the key, so
        # there is nothing to compare against and re-capturing the whole site would prove nothing.
        # Adopt the new digest against the existing asset; the NEXT source move is detected.
        return True
    return cached_metadata.get("runnerSourceDigest") != current_metadata.get("runnerSourceDigest")


def parse_backdoor_results(stdout: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    idx = 0
    size = len(stdout)
    results: list[dict[str, Any]] = []
    while idx < size:
        while idx < size and stdout[idx].isspace():
            idx += 1
        if idx >= size:
            break
        try:
            payload, next_idx = decoder.raw_decode(stdout, idx)
        except json.JSONDecodeError:
            next_newline = stdout.find("\n", idx)
            if next_newline < 0:
                break
            idx = next_newline + 1
            continue
        if isinstance(payload, dict) and "command" in payload and "ok" in payload:
            results.append(payload)
        idx = next_idx
    return results


def deterministic_execution_error(
    completed: subprocess.CompletedProcess[str],
    *,
    page_path: Path,
    output_path: Path,
    command: list[str],
    expects_error: bool = False,
) -> RuntimeError | None:
    """A clean error to raise when a capture failed on a box compile/runtime error.

    When backdoor stdout carries a diagnosed execution error, return the more useful
    RuntimeError immediately. Opaque failures still fail the same invocation; they use
    the general capture diagnostic assembled by the caller.

    [expects_error] is the `expect-error=true` fence, whose whole subject is the diagnostic: a page
    that says "an error is reported against the form that threw" needs a picture of one. The caller
    asserts the other direction (that an error did in fact arrive), so this only declines to elevate.
    """
    if expects_error:
        return None
    if completed.returncode == 0:
        return None
    # Only a *diagnosed* failure is deterministic: an error output/notify event, or a real
    # command error message. Bare per-command timeouts and opaque "command failed" results use
    # the caller's general failure report because there is no execution diagnostic to elevate.
    error = extract_backdoor_execution_error(completed.stdout, require_diagnostic=True)
    if error is None:
        return None
    return RuntimeError(
        f"Field docs runner execution error for {page_path.name} -> {output_path}.\n"
        f"Command: {shlex.join(command)}\n"
        f"{error}"
    )


def extract_backdoor_execution_error(stdout: str, *, require_diagnostic: bool = False) -> str | None:
    """Summarize execution problems found in backdoor stdout, or None if clean.

    With `require_diagnostic=True`, only *diagnosed* problems count -- a real command
    error message (`data.error`) or an error output/notify event. Bare timeouts and bare
    "command failed" results (no diagnostic) are excluded, so callers do not mislabel an
    unexplained infrastructure failure as broken example code. The default (False) reports
    every problem, including bare ones.
    """
    results = parse_backdoor_results(stdout)
    if not results:
        return None

    shader_clear_properties = {
        "vertex",
        "fragment",
        "compute",
        "task",
        "mesh",
        "raygen",
        "miss",
        "closesthit",
        "anyhit",
        "intersection",
        "callable",
        "vertex_fragment",
        "vertex_s",
        "fragment_s",
        "compute_s",
        "task_s",
        "mesh_s",
        "raygen_s",
        "miss_s",
        "closesthit_s",
        "anyhit_s",
        "intersection_s",
        "callable_s",
    }
    problems: list[str] = []
    for result in results:
        command = str(result.get("command", "<unknown command>"))
        data = result.get("data")
        best_effort_timeout = (
            isinstance(data, dict)
            and command.startswith("stop ")
            and "--best-effort" in command
            and bool(data.get("bestEffort"))
        )
        timed_out = bool(result.get("timedOut")) and not best_effort_timeout
        if timed_out and not require_diagnostic:
            problems.append(f"{command}: timed out")
        if not bool(result.get("ok", False)):
            error_text = None
            if isinstance(data, dict):
                raw_error = data.get("error")
                if raw_error is not None:
                    error_text = str(raw_error)
            if error_text:
                # A real command error message is a genuine (diagnosed) failure.
                problems.append(f"{command}: {error_text}")
            elif not require_diagnostic:
                # A bare "command failed" with no diagnostic (opaque or timeout-only) is not
                # proof of a broken example; exclude it when a diagnostic is required so a
                # transient hiccup isn't misread as a deterministic execution error.
                problems.append(f"{command}: command failed")

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
            if name in {"output", "peer-output"}:
                if payload.get("kind") not in {"error", "err"}:
                    continue
                line = payload.get("line")
                line_suffix = f" line {line}" if line is not None else ""
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                property_name = str(payload.get("property", ""))
                if not html and (property_name in shader_clear_properties or not property_name):
                    continue
                problems.append(f"{command}{line_suffix}: {html or 'execution error'}")
                continue
            if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
                text = str(payload.get("text", "")).replace("\n", " ").strip()
                if text:
                    problems.append(f"{command}: {text}")

    if not problems:
        return None

    deduped = list(dict.fromkeys(problems))
    return "\n".join(deduped)


def extract_backdoor_output_gap(*, stdout: str, code: str, options: RunnerOptions) -> str | None:
    if options.graphics_context:
        return None

    results = parse_backdoor_results(stdout)
    if not results:
        return None

    events: list[dict[str, Any]] = []
    for result in results:
        result_events = result.get("events")
        if isinstance(result_events, list):
            events.extend(e for e in result_events if isinstance(e, dict))

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

    expects_svg = runner_code_expects_svg(code)
    expects_output_area = runner_code_expects_output_area(code)

    if expects_output_area and not has_output_area:
        return (
            "No output-area events were emitted (missing set-output messages). "
            "Likely causes: code did not call display/lines, output proxy failed, or execution stopped before rendering."
        )

    if expects_svg and not has_svg_output:
        channels = sorted({str(payload.get("channel", "")) for payload in set_output_events if payload.get("channel") is not None})
        channel_text = f" Channels seen: {', '.join(channels)}." if channels else ""
        return "Code appears to call lines(...) but no SVG output was observed in set-output payloads." + channel_text

    return None


def maybe_enable_output_wait(*, options: RunnerOptions, code: str) -> RunnerOptions:
    if options.graphics_context or options.wait_output:
        return options
    if not runner_code_expects_output_area(code):
        return options
    return replace(options, wait_output=True, wait_output_kind="display")


def runner_code_expects_svg(code: str) -> bool:
    expects_svg_override = parse_codegen_expectation(code=code, key="FIELD_DOCS_EXPECT_SVG")
    if expects_svg_override is not None:
        return expects_svg_override
    # NOT r"\blines\s*\(": that also matches Kotlin's String.lines(), which is everywhere, and a
    # snippet that splits a string into lines was then required to emit an <svg> it never promised.
    # Only a bare call to Field's own `lines(...)` FLine renderer counts, so a member call is excluded.
    # The cost is that an explicit `someBox.lines(...)` no longer implies SVG; a snippet written that way
    # can say so with a FIELD_DOCS_EXPECT_SVG codegen expectation.
    return re.search(r"(?<![.\w])lines\s*\(", code) is not None


def runner_code_expects_output_area(code: str) -> bool:
    expects_output_area_override = parse_codegen_expectation(code=code, key="FIELD_DOCS_EXPECT_OUTPUT_AREA")
    if expects_output_area_override is not None:
        return expects_output_area_override
    expects_display = re.search(r"\bdisplay\s*\(", code) is not None
    return runner_code_expects_svg(code) or expects_display


def validate_screenshot_pixels(*, screenshot_path: Path, options: RunnerOptions) -> str | None:
    if options.source_image:
        return None
    try:
        from PIL import Image
    except Exception:
        LOG.warning("Field docs runner skipped native screenshot pixel validation; Pillow is unavailable")
        return None
    try:
        with Image.open(screenshot_path) as image:
            width, height = image.size
            extrema = image.convert("RGB").getextrema()
    except Exception as exc:
        return f"Could not inspect screenshot pixels: {exc}"
    if options.capture == "box" and (width < 96 or height < 80):
        return (
            f"Box screenshot is implausibly small ({width}x{height}). This usually means the box "
            "or its expected output was not present when the crop was measured."
        )
    if not options.graphics_context or options.capture != "native-window":
        return None
    if all(high <= 1 for _low, high in extrema):
        return (
            "Native graphics screenshot is all black. This usually means the capture ran before "
            "the shader reflection/code execution finished, or the graphics example did not draw."
        )
    return None


def _content_profile_crop(image, tolerance: int = 16, min_fraction: float = 0.005, step: int = 4):
    """Crop box for the region that actually carries ink, by row/column profile.

    A bounding box of "any pixel unlike the corners" does not survive Field's canvas, whose empty
    area carries a faint dot grid: on the signal-panels capture that mask reached row 1855 of 1984
    and the crop was skipped as pointless. Counting per row and per column instead, and requiring a
    row to be at least [min_fraction] ink before it counts, puts the content boundary at row 950 --
    which is where the drawing actually ends.

    The tolerance is measured, not guessed. At 6 the dot grid reads as content (rows 0..1855); at 28
    real content is clipped (36..887); at 16 the grid sits below and the ink above (0..919).
    """
    from PIL import Image, ImageChops, ImageStat

    width, height = image.size
    if width <= 4 or height <= 4:
        return None
    rgb = image.convert("RGB")
    sample = max(1, min(32, width // 12, height // 12))
    corners = (
        (0, 0, sample, sample),
        (width - sample, 0, width, sample),
        (0, height - sample, sample, height),
        (width - sample, height - sample, width, height),
    )
    medians = [tuple(int(v) for v in ImageStat.Stat(rgb.crop(box)).median[:3]) for box in corners]
    background = tuple(sorted(m[i] for m in medians)[len(medians) // 2] for i in range(3))
    diff = ImageChops.difference(rgb, Image.new("RGB", rgb.size, background))
    red, green, blue = diff.split()
    peak = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    pixels = peak.load()

    rows = [
        y for y in range(height)
        if sum(1 for x in range(0, width, step) if pixels[x, y] > tolerance) * step > width * min_fraction
    ]
    cols = [
        x for x in range(width)
        if sum(1 for y in range(0, height, step) if pixels[x, y] > tolerance) * step > height * min_fraction
    ]
    if not rows or not cols:
        return None
    margin = max(16, min(96, int(round(max(width, height) * 0.015))))
    left = max(0, min(cols) - margin)
    right = min(width, max(cols) + 1 + margin)
    top = max(0, min(rows) - margin)
    bottom = min(height, max(rows) + 1 + margin)
    if right - left <= 4 or bottom - top <= 4:
        return None
    if (right - left) * (bottom - top) >= width * height * 0.96:
        return None
    return left, top, right, bottom


def parse_clip(value: str | None) -> tuple[float, float, float, float] | None:
    """`clip="x,y,w,h"` in CSS pixels; None when absent."""
    if value is None or not str(value).strip():
        return None
    parts = [p.strip() for p in str(value).split(",")]
    if len(parts) != 4:
        raise ValueError(f"clip must be x,y,w,h (got {value!r})")
    x, y, w, h = (float(p) for p in parts)
    if w <= 0 or h <= 0:
        raise ValueError(f"clip width and height must be positive (got {value!r})")
    return (x, y, w, h)


def parse_capture_scale(value: str | None) -> float | None:
    """`scale="3"` -> 3.0 device pixels per CSS pixel, between 1 and 4."""
    if value is None or not value.strip():
        return None
    try:
        scale = float(value)
    except ValueError:
        raise RuntimeError(f"scale={value!r} must be a number of device pixels per CSS pixel, e.g. 3")
    if not (1.0 <= scale <= 4.0):
        raise RuntimeError(f"scale={value!r} must be between 1 and 4")
    return scale


def parse_viewport(value: str | None) -> tuple[int, int] | None:
    """`viewport="1280x800"` -> (1280, 800), in CSS pixels."""
    if value is None or not value.strip():
        return None
    match = re.fullmatch(r"\s*(\d+)\s*[x,]\s*(\d+)\s*", value)
    if not match:
        raise RuntimeError(f"viewport={value!r} must be WIDTHxHEIGHT in CSS pixels, e.g. 1280x800")
    width, height = int(match.group(1)), int(match.group(2))
    if width < 320 or height < 240:
        raise RuntimeError(f"viewport={value!r} is smaller than 320x240")
    return width, height


def clip_screenshot(*, screenshot_path: Path, options: RunnerOptions, backdoor_stdout: str) -> None:
    """Crop a finished capture to `clip`, a rectangle in CSS pixels.

    The PNG is in device pixels, so the scale is the image width over the viewport width the
    screenshot command reported (its JSON result carries `"viewport": {..., "w": ...}`). The crop is
    of the real screenshot; nothing is drawn or composited.
    """
    if options.clip is None or options.source_image:
        return
    try:
        from PIL import Image
    except Exception:
        raise RuntimeError("clip= needs Pillow to crop the screenshot")
    matches = re.findall(r'"viewport":\s*\{[^}]*?"w":\s*([0-9.]+)', backdoor_stdout or "")
    with Image.open(screenshot_path) as source:
        image = source.copy()
    if not matches:
        raise RuntimeError(f"clip= could not read the capture viewport width for {screenshot_path}")
    scale = image.width / float(matches[-1])
    x, y, w, h = options.clip
    box = (
        max(0, round(x * scale)), max(0, round(y * scale)),
        min(image.width, round((x + w) * scale)), min(image.height, round((y + h) * scale)),
    )
    if box[2] <= box[0] or box[3] <= box[1]:
        raise RuntimeError(f"clip={options.clip} lies outside the {image.width}x{image.height} capture {screenshot_path}")
    image.crop(box).save(screenshot_path)
    LOG.info("Field docs runner clipped screenshot %s to %s css px (scale %.2f)", screenshot_path, options.clip, scale)


def trim_screenshot_border(*, screenshot_path: Path, options: RunnerOptions) -> None:
    """Crop uniform background border off a finished capture.

    Two ways in. `trim-native` is the original, still limited to native-window graphics captures.
    `trim` (also `crop`, `auto-crop`) is general and works for any capture mode, which is the cheaper
    answer to a clip measured from a container rather than its content: the harness can be wrong about
    how big the DOM is, and the pixels cannot. The 'Signal and synth visualizers' figure shipped
    1164x1984 for ~950px of drawing because collectScreenshotRects unions the box's own rect; trimming
    the finished PNG fixes the published figure without teaching the DOM measurement new tricks.

    Background is the median of the four corners rather than an assumed colour, so a themed or
    gradient-ish backdrop still trims. The 96%-area guard means a capture that is already tight is
    left byte-identical instead of being rewritten every build.
    """
    if options.source_image:
        return
    native_trim = options.trim_native and options.graphics_context and effective_capture_mode(options) == "native-window"
    if not native_trim and not options.trim:
        return
    if not native_trim:
        # General `trim`: profile-based, and deliberately a separate path so enabling it here cannot
        # move a single committed native-graphics capture.
        try:
            from PIL import Image
        except Exception:
            LOG.warning("Field docs runner skipped screenshot trim; Pillow is unavailable")
            return
        try:
            with Image.open(screenshot_path) as source:
                image = source.copy()
        except Exception as exc:
            LOG.warning("Field docs runner could not open screenshot for trimming %s: %s", screenshot_path, exc)
            return
        box = _content_profile_crop(image)
        if box is None:
            return
        try:
            image.crop(box).save(screenshot_path)
        except Exception as exc:
            LOG.warning("Field docs runner failed to trim screenshot %s: %s", screenshot_path, exc)
            return
        LOG.info(
            "Field docs runner trimmed screenshot to content: %s (%dx%d -> %dx%d)",
            screenshot_path, image.width, image.height, box[2] - box[0], box[3] - box[1],
        )
        return
    try:
        from PIL import Image, ImageChops, ImageStat
    except Exception:
        LOG.warning("Field docs runner skipped screenshot trim; Pillow is unavailable")
        return

    try:
        with Image.open(screenshot_path) as source:
            image = source.copy()
    except Exception as exc:
        LOG.warning("Field docs runner could not open screenshot for trimming %s: %s", screenshot_path, exc)
        return

    if image.width <= 4 or image.height <= 4:
        return

    rgb = image.convert("RGB")
    sample = max(1, min(32, image.width // 12, image.height // 12))
    corner_boxes = (
        (0, 0, sample, sample),
        (image.width - sample, 0, image.width, sample),
        (0, image.height - sample, sample, image.height),
        (image.width - sample, image.height - sample, image.width, image.height),
    )
    corner_medians: list[tuple[int, int, int]] = []
    for box in corner_boxes:
        stat = ImageStat.Stat(rgb.crop(box))
        median = stat.median
        corner_medians.append((int(median[0]), int(median[1]), int(median[2])))

    def median_channel(index: int) -> int:
        values = sorted(sample[index] for sample in corner_medians)
        return values[len(values) // 2]

    background = (median_channel(0), median_channel(1), median_channel(2))
    diff = ImageChops.difference(rgb, Image.new("RGB", rgb.size, background))
    red, green, blue = diff.split()
    max_diff = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    tolerance = 6
    mask = max_diff.point(lambda value: 255 if value > tolerance else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return

    left, top, right, bottom = bbox
    margin = max(16, min(96, int(round(max(image.width, image.height) * 0.015))))
    left = max(0, left - margin)
    top = max(0, top - margin)
    right = min(image.width, right + margin)
    bottom = min(image.height, bottom + margin)
    crop_width = right - left
    crop_height = bottom - top
    if crop_width <= 4 or crop_height <= 4:
        return

    original_area = image.width * image.height
    crop_area = crop_width * crop_height
    if crop_area >= original_area * 0.96:
        return

    try:
        image.crop((left, top, right, bottom)).save(screenshot_path)
    except Exception as exc:
        LOG.warning("Field docs runner failed to trim screenshot %s: %s", screenshot_path, exc)
        return
    LOG.info(
        "Field docs runner trimmed screenshot border: %s (%dx%d -> %dx%d)",
        screenshot_path,
        image.width,
        image.height,
        crop_width,
        crop_height,
    )


def parse_codegen_expectation(*, code: str, key: str) -> bool | None:
    match = re.search(rf"{re.escape(key)}:\s*(true|false)", code, re.IGNORECASE)
    if match is None:
        return None
    return match.group(1).lower() == "true"


def effective_capture_mode(options: RunnerOptions) -> str:
    if options.capture == "window" and options.open_outputs and not options.with_editor:
        return "box"
    return options.capture


SOURCE_BOX_PROPERTIES: tuple[tuple[str, str], ...] = (
    ("preamble", "kt"),
    ("code", "kt"),
    ("stackSL", "stacksl"),
    ("vertex", "glsl"),
    ("fragment", "glsl"),
    ("compute", "glsl"),
    ("vertex_fragment", "slang"),
    ("vertex_s", "slang"),
    ("fragment_s", "slang"),
    ("compute_s", "slang"),
)


def source_box_set_commands(
    *,
    repo_root: Path,
    source_box: str,
    target_uid: str,
    source_box_properties: tuple[str, ...] = (),
) -> list[str]:
    source_ref = Path(source_box)
    if not source_ref.is_absolute():
        source_ref = repo_root / "public" / "docs" / source_ref
    source_dir = source_ref.parent
    source_uid = source_ref.name
    allowed = set(source_box_properties) if source_box_properties else None
    commands: list[str] = []
    for property_name, extension in SOURCE_BOX_PROPERTIES:
        if allowed is not None and property_name not in allowed:
            continue
        path = source_dir / f"{source_uid}_{property_name}_.{extension}"
        if path.exists():
            commands.append(shlex.join([
                "set",
                target_uid,
                property_name,
                "--file", str(path),
            ]))
    return commands


def resolve_macro_file_path(*, repo_root: Path, page_path: Path, macro_file: str) -> Path:
    raw = Path(macro_file)
    if raw.is_absolute():
        return raw
    page_relative = (page_path.parent / raw).resolve()
    if page_relative.exists():
        return page_relative
    return (repo_root / raw).resolve()


def macro_name_for_options(*, options: RunnerOptions, page_path: Path, block_index: int) -> str:
    if options.macro_name:
        return options.macro_name
    if options.macro_file:
        return Path(options.macro_file).stem
    return f"{page_path.stem}-{block_index}"


def safe_transcript_id(text: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]+", "_", text.strip())
    cleaned = re.sub(r"_+", "_", cleaned).strip("_")
    if not cleaned:
        cleaned = "box"
    if cleaned[0].isdigit():
        cleaned = f"box_{cleaned}"
    return cleaned[:80]


def docs_runner_target_uid(*, page_path: Path, block_index: int, options: RunnerOptions) -> str:
    if options.run_box:
        return options.run_box
    return safe_transcript_id(f"docs_{page_path.stem}_{block_index}_{options.box_name}")


def docs_runner_fieldml_root_alias(*, target_uid: str) -> str:
    return docs_runner_language_root_alias(target_uid=target_uid, language="fieldml")


# Languages whose boxes only execute as that language when they are a *child* of a box carrying
# `mainLanguage`: the fence body has to live one level down, so a fence in one of them builds two
# boxes. The list itself lives in `tools/field_languages.py`, shared with `tools/run_field_tests.py`
# so that a docs example and the FIELD_TEST that guards it cannot disagree about whether its
# language needs a root. `fieldml` predates the set and produces byte-identical commands through it.


def docs_runner_language_root_alias(*, target_uid: str, language: str) -> str:
    return safe_transcript_id(f"{target_uid}_{language}_root")


def graphics_renderer_transcript_path(repo_root: Path) -> Path:
    return repo_root / "dev_docs" / "agents" / "transcripts" / "graphics_renderer_launch.yaml"


# A fence's `timeout` is what the author allows *their own example*. The three commands that
# bring up the native renderer -- `macro write/run graphics_renderer_launch` and
# `wait-native-renderer` -- are not the author's example: they are a cold Vulkan boot this file
# generates on the author's behalf, and their cost is a property of the machine, not of the page.
# Threading the fence budget into them made one attribute govern both, so the tightest page in the
# corpus set the budget for the slowest thing in the run. `afield_four_graphics.md` declares
# `timeout="10"` on all ten of its fences -- below even the 45.0 default -- and that killed three
# consecutive certifying docs builds at capture 3-8 of ~544 with a `TimeoutError` that *moved*
# between runs on a byte-identical tree: no fence was broken, the budget was simply insufficient
# wherever it landed.
#
# 90s is not a guess: it is `puppet_browser.md`'s own declared budget, an author's working number
# for this same class of work (boot a native window, wait for frames). Every other
# graphics-context page already sits at 45s or above, so this floor binds only on
# `afield_four_graphics.md` (10 -> 90), `keyframer_examples.md` (20/25 -> 90) and the three pages
# that declare nothing (45 -> 90). A timeout is a ceiling, never a sleep, so raising it cannot
# slow a capture that succeeds -- it only stops one being abandoned early.
GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS = 90.0


def graphics_launch_timeout(options: RunnerOptions) -> float:
    """The budget for the runner's own renderer bring-up, floored away from the fence's.

    A fence may still ask for *more* than the floor -- `stacksl_examples.md` asks for 600 -- and
    that is honoured, because a page whose example needs a long budget needs its renderer to
    survive at least as long.
    """
    return max(float(options.timeout), GRAPHICS_LAUNCH_TIMEOUT_FLOOR_SECONDS)


def generated_graphics_child_transcript(*, target_uid: str, options: RunnerOptions) -> str:
    transcript: dict[str, Any] = {
        "name": f"{target_uid}_setup",
        "description": "Generated by public/docs_hooks.py for a native graphics docs capture.",
        "operations": [
            {
                "create": {
                    "box": target_uid,
                    "name": options.box_name,
                    "frame": {
                        "x": options.box_x,
                        "y": options.box_y,
                        "w": options.box_w,
                        "h": options.box_h,
                    },
                }
            },
            {
                "connect": {
                    "parent": "agent_graphics_context",
                    "child": target_uid,
                }
            },
        ],
    }
    return json.dumps(transcript, separators=(",", ":"))


def code_with_macro_digest(*, code: str, options: RunnerOptions, repo_root: Path, page_path: Path) -> str:
    digest_lines: list[str] = []
    if options.graphics_context:
        renderer_path = graphics_renderer_transcript_path(repo_root)
        try:
            renderer_digest = hashlib.sha256(renderer_path.read_bytes()).hexdigest()
        except FileNotFoundError:
            renderer_digest = "<missing>"
        digest_lines.extend([
            f"// FIELD_DOCS_RENDERER_MACRO_FILE: {renderer_path.relative_to(repo_root)}",
            f"// FIELD_DOCS_RENDERER_MACRO_DIGEST: {renderer_digest}",
        ])
    if options.macro_file:
        macro_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
        try:
            macro_bytes = macro_path.read_bytes()
        except FileNotFoundError:
            macro_digest = "<missing>"
        else:
            macro_digest = hashlib.sha256(macro_bytes).hexdigest()
        digest_lines.extend([
            f"// FIELD_DOCS_MACRO_FILE: {options.macro_file}",
            f"// FIELD_DOCS_MACRO_DIGEST: {macro_digest}",
        ])
    if not digest_lines:
        return code
    return "\n".join([code, *digest_lines])


def runner_subprocess_timeout(*, options: RunnerOptions, is_video: bool) -> float:
    base = video_capture_timeout(options) if is_video else options.timeout
    budget = base + RUNNER_SUBPROCESS_GRACE_SEC
    if not PLAYWRIGHT_READY_MARKER.exists():
        budget = max(budget, PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC + RUNNER_SUBPROCESS_GRACE_SEC)
    return budget


def video_capture_timeout(options: RunnerOptions) -> float:
    expected_frames = max(1, math.ceil(options.duration * max(options.fps, 1)))
    frame_budget = VIDEO_CAPTURE_SETUP_HEADROOM_SEC + expected_frames * VIDEO_CAPTURE_PER_FRAME_BUDGET_SEC
    return max(options.timeout, frame_budget)


@dataclass
class BackdoorInvocation:
    process_command: list[str]        # full `python field_backdoor.py --command ...` argv
    backdoor_commands: list[str]      # just the backdoor command lines (for the warm-session path)
    session_eligible: bool            # safe to run through the warm-session daemon?
    port: int
    document: str | None


def validate_wav_file(path: Path) -> None:
    """Assert [path] is a real, non-empty 16-bit PCM WAV (not a silent/headerless stub)."""
    data = path.read_bytes()
    if len(data) <= 44:
        raise RuntimeError(f"WAV {path} too small ({len(data)} bytes) — not a real audio capture")
    if data[0:4] != b"RIFF" or data[8:12] != b"WAVE" or data[12:16] != b"fmt ":
        raise RuntimeError(f"WAV {path} missing RIFF/WAVE/fmt header — not a real audio capture")


def build_audio_backdoor_command(
    *,
    repo_root: Path,
    audio_path: Path,
    options: RunnerOptions,
    newer_than: float,
) -> list[str]:
    """Backdoor argv that creates a box, runs the generated offline-render code (piped on
    stdin), and waits for the WAV to land. No Playwright/screenshot — a pure JVM render."""
    port = int(os.environ.get(RUNNER_PORT_ENV, str(DEFAULT_RUNNER_PORT)))
    command = [
        sys.executable,
        str((repo_root / "tools" / "field_backdoor.py").resolve()),
        "--json",
        "--port", str(port),
        "--timeout", str(options.timeout),
    ]
    if options.document:
        command.extend(["--document", options.document])
    backdoor_commands = [
        shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]),
        shlex.join([
            "run", "@last",
            "--property", options.property_name,
            "--file", "-",
            "--timeout", str(options.timeout),
        ]),
        shlex.join([
            "wait-file", str(audio_path),
            "--timeout", str(options.timeout),
            "--newer-than", str(newer_than),
            "--fail-on-error-uid", "@last",
        ]),
    ]
    for command_line in backdoor_commands:
        command.extend(["--command", command_line])
    return command


def build_backdoor_command(
    *,
    repo_root: Path,
    page_path: Path,
    block_index: int,
    screenshot_path: Path,
    options: RunnerOptions,
    is_video: bool = False,
    source_image_path: Path | None = None,
    source_image_min_mtime: float | None = None,
    code: str | None = None,
) -> BackdoorInvocation:
    port = int(os.environ.get(RUNNER_PORT_ENV, str(DEFAULT_RUNNER_PORT)))
    capture_mode = effective_capture_mode(options)
    capture_timeout = video_capture_timeout(options) if is_video else options.timeout
    command = [
        sys.executable,
        str((repo_root / "tools" / "field_backdoor.py").resolve()),
        "--json",
        "--with-playwright",
        "--port",
        str(port),
        "--timeout",
        str(capture_timeout),
    ]
    if options.document:
        command.extend(["--document", options.document])
    backdoor_commands: list[str] = []

    if options.capture == "ui":
        # Optional setup: run a transcript= file of create/launch ops FIRST, in the same
        # backdoor session, so its boxes exist when the drive runs. Needed for captures
        # that require scene setup a drive fragment can't do itself (a drive fragment runs
        # on the main runloop thread where thisSessionInfo is unset, so box(...) throws) —
        # e.g. code-driven UI like showLayers(), or a separate demo box to type into.
        if options.macro_file:
            setup_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
            setup_name = f"{safe_transcript_id(options.box_name)}_ui_setup"
            backdoor_commands.append(shlex.join([
                "macro", "write", setup_name, "--file", str(setup_path), "--timeout", str(options.timeout),
            ]))
            backdoor_commands.append(shlex.join([
                "macro", "run", setup_name, "--timeout", str(options.timeout),
            ]))
        # Run the fence body as a single `drive` transcript op: it scripts the live
        # editor via Playwright (the `ui` driver is in scope) and produces the video
        # and/or still itself, so no separate screenshot-/record- command is appended.
        drive_op: dict[str, Any] = {
            "script": code or "",
            "capture": options.region,
            "fps": options.fps,
            "padding": options.padding,
        }
        if options.video:
            drive_op["video"] = str(resolve_output_path(page_path, options.video))
        if options.screenshot:
            drive_op["screenshot"] = str(resolve_output_path(page_path, options.screenshot))
        capture_uid = options.capture_box or options.run_box
        if capture_uid:
            drive_op["captureUid"] = capture_uid
        if options.clip_selector:
            drive_op["clipSelector"] = options.clip_selector
        if options.clip is not None:
            # capture="ui" clips in Field, at capture time, to this CSS-px viewport rectangle (the
            # recording too); clip_screenshot's post-crop is the screenshot path's, not this one's.
            drive_op["clipRect"] = list(options.clip)
        if options.viewport is not None:
            drive_op["viewport"] = list(options.viewport)
        if options.warm_completion:
            drive_op["warmCompletion"] = True
        transcript_name = f"{safe_transcript_id(options.box_name)}_ui_drive"
        transcript = {
            "name": transcript_name,
            "description": "Generated by public/docs_hooks.py capture=ui drive.",
            "operations": [{"drive": drive_op}],
        }
        backdoor_commands.append(shlex.join([
            "macro", "write", transcript_name,
            "--content", json.dumps(transcript, separators=(",", ":")),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro", "run", transcript_name,
            "--timeout", str(options.timeout),
        ]))
        for command_line in backdoor_commands:
            command.extend(["--command", command_line])
        return BackdoorInvocation(
            process_command=command,
            backdoor_commands=backdoor_commands,
            session_eligible=False,
            port=port,
            document=options.document or None,
        )
    generated_target_uid = docs_runner_target_uid(page_path=page_path, block_index=block_index, options=options)
    default_target_uid = options.capture_box or options.run_box or (generated_target_uid if options.graphics_context else "@last")
    language = options.language_key.strip().lower()
    if options.graphics_context:
        renderer_macro_path = graphics_renderer_transcript_path(repo_root)
        launch_timeout = graphics_launch_timeout(options)
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            "graphics_renderer_launch",
            "--file", str(renderer_macro_path),
            "--timeout", str(launch_timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro",
            "run",
            "graphics_renderer_launch",
            "--timeout", str(launch_timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "wait-native-renderer",
            "agent_graphics_context",
            "--frames", "3",
            "--timeout", str(launch_timeout),
        ]))
    if options.macro_file:
        macro_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
        macro_name = macro_name_for_options(options=options, page_path=page_path, block_index=block_index)
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            macro_name,
            "--file", str(macro_path),
            "--timeout", str(options.timeout),
        ]))
        macro_run_parts = [
            "macro",
            "run",
            macro_name,
            "--timeout", str(options.timeout),
        ]
        for selection in options.macro_selection:
            macro_run_parts.extend(["--selection", selection])
        backdoor_commands.append(shlex.join(macro_run_parts))
    elif options.run_box:
        if options.graphics_context:
            connect_transcript = {
                "name": f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "description": "Generated by public/docs_hooks.py to connect an existing box to the docs renderer.",
                "operations": [
                    {"connect": {"parent": "agent_graphics_context", "child": options.run_box}},
                ],
            }
            backdoor_commands.append(shlex.join([
                "macro",
                "write",
                f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "--content", json.dumps(connect_transcript, separators=(",", ":")),
                "--timeout", str(options.timeout),
            ]))
            backdoor_commands.append(shlex.join([
                "macro",
                "run",
                f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "--timeout", str(options.timeout),
            ]))
        for setup_box in options.setup_boxes:
            backdoor_commands.append(shlex.join([
                "run",
                setup_box,
                "--timeout", str(options.timeout),
            ]))
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            options.run_box,
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    elif options.graphics_context:
        setup_macro_name = f"{generated_target_uid}_setup"
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            setup_macro_name,
            "--content", generated_graphics_child_transcript(target_uid=generated_target_uid, options=options),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro",
            "run",
            setup_macro_name,
            "--timeout", str(options.timeout),
        ]))
    elif language in MAIN_LANGUAGE_ROOT_LANGUAGES:
        root_alias = docs_runner_language_root_alias(target_uid=generated_target_uid, language=language)
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", f"{options.box_name}-root",
            "--alias", root_alias,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", "120",
            "--h", "80",
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "set",
            root_alias,
            "mainLanguage",
            language,
        ]))
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y + 120.0),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "connect",
            root_alias,
            "@last",
            "--timeout", str(options.timeout),
        ]))
    else:
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]))
    if options.source_box and not options.macro_file:
        backdoor_commands.extend(source_box_set_commands(
            repo_root=repo_root,
            source_box=options.source_box,
            target_uid=options.run_box or (generated_target_uid if options.graphics_context else "@last"),
            source_box_properties=options.source_box_properties,
        ))
        if options.source_box_wait > 0:
            backdoor_commands.append(shlex.join(["sleep", str(options.source_box_wait)]))
    if options.macro_file:
        pass
    elif options.run_box is None and capture_mode == "editor":
        # Target the box explicitly rather than "@last": under graphics-context the
        # box is created via a macro (so "@last" is stale) and must match the box
        # that record-editor records below (default_target_uid). In the non
        # graphics-context case default_target_uid is "@last", preserving behaviour.
        editor_target = default_target_uid
        backdoor_commands.append(shlex.join([
            "set",
            editor_target,
            options.property_name,
            "--file", "-",
        ]))
        backdoor_commands.append(shlex.join([
            "editor-open",
            editor_target,
            "--property", options.property_name,
            "--padding", str(options.padding),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            editor_target,
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    elif options.run_box is None and (
        options.source_box is None
        or (options.source_box_properties and "code" not in options.source_box_properties)
    ):
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            generated_target_uid if options.graphics_context else "@last",
            "--property", options.property_name,
            "--file", "-",
            "--timeout", str(options.timeout),
        ]))
    elif options.run_box is None:
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            generated_target_uid if options.graphics_context else "@last",
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    if options.wait > 0 and source_image_path is None:
        backdoor_commands.append(shlex.join(["sleep", str(options.wait)]))
    if options.wait_output:
        wait_output_parts = [
            "wait-output",
            default_target_uid,
            "--kind", options.wait_output_kind,
            "--timeout", str(options.timeout),
        ]
        if options.wait_output_channel:
            wait_output_parts.extend(["--channel", options.wait_output_channel])
        if options.wait_output_contains:
            wait_output_parts.extend(["--contains", options.wait_output_contains])
        backdoor_commands.append(shlex.join(wait_output_parts))
    if source_image_path is not None:
        wait_file_parts = [
            "wait-file",
            str(source_image_path),
            "--timeout",
            str(options.timeout),
            "--fail-on-error-uid",
            default_target_uid,
        ]
        if source_image_min_mtime is not None:
            wait_file_parts.extend(["--newer-than", str(source_image_min_mtime)])
        backdoor_commands.append(shlex.join(wait_file_parts))

    if is_video:
        # Use record-* commands instead of screenshot-*
        if capture_mode == "box":
            target_uid = default_target_uid
            parts = [
                "record-box",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--frame-synchronous",
                "--timeout", str(capture_timeout),
            ]
            if options.restart_target:
                parts.append("--restart-target")
            backdoor_commands.append(shlex.join(parts))
        elif capture_mode == "editor":
            target_uid = default_target_uid
            parts = [
                "record-editor",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--frame-synchronous",
                "--timeout", str(capture_timeout),
            ]
            if options.restart_target:
                parts.append("--restart-target")
            if options.open_outputs:
                parts.append("--open-outputs")
            parts.extend(["--expect-outputs", options.expect_outputs])
            backdoor_commands.append(shlex.join(parts))
        else:
            parts = [
                "record-window",
                str(screenshot_path),
                "--uid", default_target_uid,
                "--property", options.property_name,
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--frame-synchronous",
                "--timeout", str(capture_timeout),
            ]
            if options.restart_target:
                parts.append("--restart-target")
            if options.open_outputs:
                parts.append("--open-outputs")
            parts.extend(["--expect-outputs", options.expect_outputs])
            if options.with_editor:
                parts.append("--with-editor")
            backdoor_commands.append(shlex.join(parts))
    else:
        if options.source_image:
            pass
        elif capture_mode == "native-window":
            backdoor_commands.append(shlex.join([
                "native-screenshot",
                str(screenshot_path),
                "--uid", default_target_uid,
                "--wait-frames", str(options.native_wait_frames),
                "--timeout", str(options.timeout),
            ]))
        elif capture_mode == "box":
            target_uid = default_target_uid
            parts = [
                "screenshot-box",
                target_uid,
                str(screenshot_path),
                "--padding", str(options.padding),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            parts.extend(["--expect-outputs", options.expect_outputs])
            if options.scale is not None:
                parts.extend(["--scale", str(options.scale)])
            backdoor_commands.append(shlex.join(parts))
        elif capture_mode == "editor":
            target_uid = default_target_uid
            parts = [
                "screenshot-editor",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            parts.extend(["--expect-outputs", options.expect_outputs])
            backdoor_commands.append(shlex.join(parts))
        else:
            target_uid = default_target_uid
            parts = [
                "screenshot-window",
                str(screenshot_path),
                "--uid", target_uid,
                "--property", options.property_name,
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            parts.extend(["--expect-outputs", options.expect_outputs])
            if options.with_editor:
                parts.append("--with-editor")
            backdoor_commands.append(shlex.join(parts))

    if options.execution == "launch" and not options.macro_file:
        backdoor_commands.append(shlex.join([
            "stop",
            default_target_uid,
            "--timeout", str(options.timeout),
        ]))
    if options.expects_error:
        backdoor_commands = [waive_execution_verdict(line) for line in backdoor_commands]
    for command_line in backdoor_commands:
        command.extend(["--command", command_line])
    session_eligible = not options.graphics_context and not is_video and not options.source_image
    return BackdoorInvocation(
        process_command=command,
        backdoor_commands=backdoor_commands,
        session_eligible=session_eligible,
        port=port,
        document=options.document or None,
    )
