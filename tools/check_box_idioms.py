#!/usr/bin/env python3
"""Flag (and with --fix, rewrite) box-code idioms that agents keep writing and Field does not need.

Box code is read by every agent that starts from it, so a redundant line in a transcript or a
lesson is copied into the next fifty boxes. AGENTS.md said so in prose; agents kept writing them.
This is the check that retires the prose. The rules:

- ``redundant-renderer``: ``val renderer: Renderer = fromContext()`` (or ``fromContext<Renderer>()``).
  The renderer box publishes ``prop<Renderer>(renderer)``, so ``renderer`` already resolves in every
  box beneath it through the box's implicit receiver. The one place a local copy earns its line is
  coroutine work: code inside ``start { }`` / ``launch { }`` runs after an async boundary and must
  capture the renderer first. In a box that starts coroutines the line is therefore only a note:
  printed, never rewritten, and not a failure.
- ``bind-push-constant``: ``mesh.push.bindPushConstant("n", { e }) { throw ... }``. ``moduleHere``
  generates ``var Mesh.n`` for every push-constant member it sees, so this is ``mesh.n = { e }``.
- ``box-context-lookup``: ``Box.context.get()!!`` (or ``afield.document.graph.Box.context.get() ?: throw ...``). That
  is ``thisBox``, and ``thisBox`` itself is rarely needed: the box is the implicit receiver, so
  ``thisBox.foo`` is ``foo``. (It cannot be spelled ``this``.) Rewritten to ``thisBox``. An alias
  (``val historyOwner = thisBox``) is usually a smell but not always wrong, so it is a note: printed,
  never rewritten, and not a failure. Removing one means rewriting each use (``historyOwner.foo`` is
  ``foo``, ``historyOwner`` as an argument is ``thisBox``), which the revision pass or a person does.
- ``spaced-fragment-marker``: ``// %-``. The splitter accepts it (``isFragmentBreak``), but until
  2026-10-05 it matched ``//%-`` exactly, so older Field ran a spaced marker as one fragment;
  published code uses the one documented spelling.
- ``list-literal`` (hand-written ``public/docs`` pages only, until the transcript refactor lands): ``listOf(a, b, c)``. Field compiles with collection literals
  (``-Xcollection-literals``), so a list is written ``[a, b, c]``; with a declared type the same
  brackets build a ``Set``, ``MutableList`` or array. A call on one line is rewritten. One spanning
  lines is a note, since a literal takes no spread and this line-by-line check cannot see one on a
  later line. ``listOf()``, ``listOf<T>(...)``, a spread (``listOf(*xs)``) and an operand of an
  arithmetic operator (``xs + listOf(a)``, ``xs -= listOf(a)``: no expected type, so ``plus`` is
  ambiguous), a `to` operand (a heterogeneous map can infer a numeric vector for the literal),
  a receiver (``listOf(a, b).map { ... }``), and a call that is a lambda's last
  expression (inline or before its closing ``}``: no expected type, so ``[a, b]`` does not compile)
  are left alone.

- Approved helpers (``dev_docs/agents/box_code_helpers/ADOPTION.md``), each replacing the verbose form
  the corpus used before the helper existed:

  - ``key-pairs``: ``scope.Key(mutableMapOf<String, Any?>("a" to x))`` is ``scope.Key("a" to x)``.
  - ``fixed-format``: ``"%.3f".format(x)`` is ``x.f3`` (``Number.f0`` .. ``f9``, ``Locale.ROOT``). A
    compound argument is parenthesised: ``(a / b).f2``. Only a single-argument call of exactly
    ``"%.Nf"`` is touched; ``"%6.2f"``, ``"%.2f%%"`` and multi-argument formats are left alone.
  - ``starting-value-cast``: ``(q as NeedsStartingValue<T>).startAt(v)`` is ``q.startAt(v)``:
    ``smoothTo`` and ``inertialTo`` return ``Interpolant<T>``.
  - ``uint-push-lambda``: ``m.count = { n.toUInt() }`` is ``m.count = { n }``: generated ``uint``
    push members take an ``Int`` lambda. Rewritten only in a block that builds a module or mesh
    (where such members come from); elsewhere a note, since a plain ``UInt`` property needs it.
  - ``compute-barrier``: a ``computeTask { _, cmd, _ -> ... }`` holding nothing but
    ``computeMemoryBarrier(cmd)`` (and ``graphicsMemoryBarrier(cmd)``) is ``computeBarrier()``
    (``computeBarrier(graphics = true)``).
  - ``wall-projection``: a RectSL ``m.push["worldToClip"] = Matrix4f().ortho(...)`` built by hand from
    the wall's size is ``program.wall.worldToClip()`` (``import afield.lang.rectsl.worldToClip``),
    which also honours a top-left origin. Not rewritten: the wall is named differently per box.
  - ``fullscreen-triangle``: ``tri.resize(3, 0, module)`` is ``fullscreenTriangle(module)`` when
    the vertex shader draws from ``gl_VertexIndex``. A note: a three-vertex mesh with attributes
    is legitimate.

Use ``--collection-literals --paths <files>`` during the code revision pass to include
those selected transcripts/demos/templates in the list-literal audit. Default landing scope stays
limited to handwritten docs until the wider corpus conversion finishes.

Where it looks: Kotlin in ``code: |`` block scalars of ``dev_docs/agents/transcripts/**.yaml`` and
of each demo's ``build.yaml`` / ``verify.yaml``, ``*.kt`` files under the published corpora,
```` ```kotlin ```` fences in ``public/docs/**.md``, and the Field-written ``document.md`` mirror of
each demo. Generated copies (``public/docs/assets/agent_transcripts``) are skipped: fix the
transcript and regenerate. So are ``backups/`` and a demo's ``operations.yaml``, which record what
the code used to be. With no scope the corpus is what git tracks, so an ignored local document
under ``demos/`` is not reported.

A fixed demo sidecar leaves two copies behind: ``metadata.edn``'s inline fallback (refresh it with
``./gradlew regenerateAgentDemos -Ponly=<demo>``) and ``document.md``. ``--fix`` rewrites the
``document.md`` fence like any other and then refreshes the ``lines`` / ``characters`` / ``sha256``
of every block whose fence now matches its sidecar.

Scope: with ``--paths`` (or ``AFIELD_GUARD_CHANGED_PATHS_FILE``, one repo-relative path per line,
which the landing guard writes) only those files are checked; this is how existing debt stays out
of the way of a landing while every file a landing touches must come out clean. With no scope, the
whole corpus is reported.

``--fix`` rewrites in place, line by line, so YAML indentation and Markdown fences are untouched.
A rewrite is a claim about resolution, not a proof: check transcripts with
``./gradlew checkBoxCodeCompiles`` afterwards.

Exit status: 0 clean, 1 findings (listed), 2 on a usage or I/O problem.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

CORPUS_ROOTS = (
    "dev_docs/agents/transcripts",
    "dev_docs/agents/demos",
    "public/docs",
    "templates",
)
GENERATED_ROOTS = ("public/docs/assets/agent_transcripts",)

RENDERER_LINE = re.compile(
    r"^\s*va[lr]\s+renderer\s*(?::\s*Renderer\s*)?=\s*fromContext(?:<Renderer>)?\(\)\s*(?://.*)?$"
)
# A call of the box's own start/launch, not a method of the same name (`streamer.start()`).
COROUTINE_START = re.compile(r"(?<![.\w])(?:start|launch)\s*(?:\(|\{)")
BIND_PUSH = re.compile(
    r"^(?P<indent>\s*)(?P<target>[A-Za-z_][\w.]*)\.push\.bindPushConstant\(\s*\"(?P<name>\w+)\"\s*,\s*"
    r"(?P<lambda>\{.*\})\s*\)\s*\{\s*throw\b[^{}]*\}\s*$"
)
BOX_CONTEXT = re.compile(r"(?<![\w.])(?:afield\.(?:document\.graph\.)?)?Box\.context\.get\(\)(?:!!)?")
# `val owner = Box.context.get() ?: throw IllegalStateException("...")`: the lookup and its guard are one
# `thisBox`, which cannot be null.
LOOKUP_ALIAS = re.compile(
    r"^(?P<head>\s*va[lr]\s+(?P<name>\w+)\s*(?::\s*Box\??\s*)?=\s*)(?:afield\.(?:document\.graph\.)?)?Box\.context\.get\(\)"
    r"(?:!!|\s*\?:\s*(?:throw\s+\w+\(.*\)|error\(.*\)))?(?P<tail>\s*(?://.*)?)$"
)
BOX_ALIAS = re.compile(r"^\s*va[lr]\s+(?P<name>\w+)\s*(?::\s*Box\??\s*)?=\s*thisBox\s*(?://.*)?$")
SPACED_MARKER = re.compile(r"^(?P<indent>\s*)//\s+%-(?P<rest>.*)$")
# Where `list-literal` applies: the hand-written docs pages. Transcripts, demos, templates and the lesson
# and demo sidecars under public/docs/assets still use listOf and are about to be refactored; the
# reference pages are generated from KDoc. Widen this once the refactor lands.
LIST_LITERAL_ROOT = "public/docs"
LIST_LITERAL_EXCLUDED = ("public/docs/assets", "public/docs/reference")


def lists_as_literals(path: str) -> bool:
    return (_under(path, LIST_LITERAL_ROOT) and path.endswith(".md")
            and not any(_under(path, root) for root in LIST_LITERAL_EXCLUDED))

LIST_OF = re.compile(r"(?<![\w.])listOf\(")
# Operator operands have no reliable expected collection type. `+` is ambiguous, and `to` can
# infer a numeric vector in a heterogeneous map. Keep factories, including across a line break.
OPERATOR_BEFORE = re.compile(r"(?:[-+*/%]=?|\bto)\s*$")
KEY_MAP = re.compile(r"(?<!\w)Key\(\s*mutableMapOf(?:<\s*String\s*,\s*Any\?\s*>)?\(")
FIXED_FORMAT = re.compile(r'(?<!\\)"%\.(?P<digits>\d)f"\.format\(')
SIMPLE_RECEIVER = re.compile(r"^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*|\(\)|\[[^\[\]()]*\])*$|^\d+(?:\.\d+)?$")
STARTING_CAST = re.compile(r"\((?P<name>[A-Za-z_]\w*)\s+as\s+(?:afield\.animation\.interpolation\.)?NeedsStartingValue<[^()]*>\)\.startAt\(")
UINT_PUSH = re.compile(
    r"^(?P<head>\s*[A-Za-z_][\w.]*\.\w+\s*=\s*\{\s*)(?P<expr>.+?)\.toUInt\(\)(?P<tail>\s*\}\s*(?://.*)?)$")
MAKES_PUSH_MEMBERS = re.compile(r"\b(?:moduleHere|computeModuleHere|meshModuleHere|ComputeMesh|Mesh|fullscreenTriangle)\b")
BARRIER_TASK = re.compile(r"^(?P<head>.*?)computeTask\s*\{\s*_\s*,\s*cmd\s*,\s*_\s*->\s*$")
BARRIER_ARG = r"cmd(?:\s*,\s*null(?:\s+as\s+VulkanBuffer\?)?)?"
COMPUTE_BARRIER_LINE = re.compile(rf"^\s*computeMemoryBarrier\({BARRIER_ARG}\)\s*$")
GRAPHICS_BARRIER_LINE = re.compile(rf"^\s*graphicsMemoryBarrier\({BARRIER_ARG}\)\s*$")
CLOSE_BRACE = re.compile(r"^\s*\}(?P<rest>.*)$")
HAND_WALL_ORTHO = re.compile(r"""push\[\s*"worldToClip"\s*\]\s*=\s*(?:afield\.math\.linalg\.)?Matrix4f\(\)\.ortho\(""")
TRIANGLE_RESIZE = re.compile(r"(?<![\w.])(?P<mesh>[A-Za-z_]\w*)\.resize\(\s*3\s*,\s*0\s*,\s*(?P<module>[^,()]+?)\s*\)")


def key_pairs(line: str) -> str | None:
    """The line with each one-line ``Key(mutableMapOf(...))`` reduced to ``Key(...)``, or None."""
    out, pos, changed = [], 0, False
    while m := KEY_MAP.search(line, pos):
        inner_open = m.end() - 1
        inner_close = _closing_paren(line, inner_open)
        outer_close = _closing_paren(line, line.index("(", m.start()))
        if inner_close < 0 or outer_close < 0 or line[inner_close + 1:outer_close].strip():
            out.append(line[pos:m.end()])
            pos = m.end()
            continue
        args = line[inner_open + 1:inner_close]
        if not args.strip() or _spread(args):
            out.append(line[pos:m.end()])
            pos = m.end()
            continue
        out.append(line[pos:m.start()] + "Key(" + args + ")")
        pos = outer_close + 1
        changed = True
    if not changed:
        return None
    out.append(line[pos:])
    return "".join(out)


def _top_level_comma(args: str) -> bool:
    depth, j = 0, 0
    while j < len(args):
        c = args[j]
        if c == '"':
            j += 1
            while j < len(args) and args[j] != '"':
                j += 2 if args[j] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            return True
        j += 1
    return False


def _strip_parens(expr: str) -> str:
    expr = expr.strip()
    while expr.startswith("(") and _closing_paren(expr, 0) == len(expr) - 1:
        expr = expr[1:-1].strip()
    return expr


def _postfix(expr: str) -> bool:
    """Whether ``expr`` is one postfix chain (``a.b(c)[d] { e }.f``), so ``.fN`` binds to all of it."""
    if not re.match(r"[A-Za-z_]|\d+(?:\.\d+)?$", expr):
        return False
    depth, j = 0, 0
    while j < len(expr):
        c = expr[j]
        if c == '"':
            j += 1
            while j < len(expr) and expr[j] != '"':
                j += 2 if expr[j] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and c == " " and re.match(r" *\{", expr[j:]):
            pass  # a trailing lambda: `xs.maxOf { ... }`
        elif depth == 0 and not (c.isalnum() or c in "_.!"):
            return False
        j += 1
    return depth == 0


def fixed_formats(line: str) -> str | None:
    """The line with each single-argument ``"%.Nf".format(x)`` written ``x.fN``, or None."""
    out, pos, changed = [], 0, False
    while m := FIXED_FORMAT.search(line, pos):
        close = _closing_paren(line, m.end() - 1)
        args = line[m.end():close] if close >= 0 else ""
        if close < 0 or not args.strip() or _top_level_comma(args) or _spread(args):
            out.append(line[pos:m.end()])
            pos = m.end()
            continue
        inner = fixed_formats(args)
        expr = _strip_parens(inner if inner is not None else args)
        receiver = expr if _postfix(expr) else f"({expr})"
        out.append(line[pos:m.start()] + f"{receiver}.f{m['digits']}")
        pos = close + 1
        changed = True
    if not changed:
        return None
    out.append(line[pos:])
    return "".join(out)


def uint_lambda(line: str) -> str | None:
    if not (m := UINT_PUSH.match(line)):
        return None
    expr = m["expr"].strip()
    if expr.startswith("(") and _closing_paren(expr, 0) == len(expr) - 1:
        expr = expr[1:-1].strip()
    elif not SIMPLE_RECEIVER.match(expr):
        return None
    return f"{m['head']}{expr}{m['tail']}"


def barrier_task(lines: list[str], i: int, end: int) -> tuple[int, str] | None:
    """For a ``computeTask { _, cmd, _ ->`` at ``i`` whose body is only memory barriers:
    (index of its closing line, the replacement line)."""
    if not (m := BARRIER_TASK.match(lines[i])):
        return None
    j, compute, graphics = i + 1, False, False
    while j < end and (COMPUTE_BARRIER_LINE.match(lines[j]) or GRAPHICS_BARRIER_LINE.match(lines[j])):
        compute |= bool(COMPUTE_BARRIER_LINE.match(lines[j]))
        graphics |= bool(GRAPHICS_BARRIER_LINE.match(lines[j]))
        j += 1
    if not compute or j >= end or not (c := CLOSE_BRACE.match(lines[j])):
        return None
    call = "computeBarrier(graphics = true)" if graphics else "computeBarrier()"
    return j, f"{m['head']}{call}{c['rest']}"


UNUSED_IMPORTS = {
    "import afield.graphics.vulkan.Renderer": re.compile(r"\bRenderer\b"),
    "import afield.document.context.fromContext": re.compile(r"\bfromContext\b"),
}


# Rules whose unfixable findings are judgement calls: printed as notes, never a failure.
NOTE_RULES = {"redundant-renderer", "box-alias", "list-literal", "uint-push-lambda", "fullscreen-triangle"}


@dataclass(frozen=True)
class Finding:
    path: str
    line: int  # 1-based, in the file
    rule: str
    message: str
    fixable: bool
    blocking: bool = True  # False: printed as a note, does not fail the check


DEMOS = "dev_docs/agents/demos"


def _under(path: str, root: str) -> bool:
    return path == root or path.startswith(root + "/")


def _is_demo_file(path: str, *names: str) -> bool:
    parts = path.split("/")
    return _under(path, DEMOS) and len(parts) == 5 and parts[-1] in names


def in_corpus(path: str) -> bool:
    if any(_under(path, root) for root in GENERATED_ROOTS):
        return False
    if not any(_under(path, root) for root in CORPUS_ROOTS):
        return False
    if "backups" in path.split("/")[:-1]:
        return False
    if path.endswith(".kt"):
        return True
    if path.endswith(".md"):
        return _under(path, "public/docs") or _is_demo_file(path, "document.md")
    if path.endswith((".yaml", ".yml")):
        return _under(path, "dev_docs/agents/transcripts") or _is_demo_file(path, "build.yaml", "verify.yaml")
    return False


def kotlin_blocks(path: str, lines: list[str]) -> list[tuple[int, int]]:
    """Half-open [start, end) line ranges holding Kotlin, by file kind."""
    if path.endswith(".kt"):
        return [(0, len(lines))]
    blocks: list[tuple[int, int]] = []
    if path.endswith(".md"):
        start = None
        for i, line in enumerate(lines):
            stripped = line.strip()
            # ```kotlin, ```{.kotlin}, and the docs runner's ```diffstatic {data-lang=kotlin ...}.
            if start is None and re.match(r"^`{3,}\s*(?:\{?\s*\.?kotlin\b|\S*\s*\{[^}]*\bdata-lang=kotlin\b)", stripped):
                start = i + 1
            elif start is not None and re.match(r"^`{3,}\s*$", stripped):
                blocks.append((start, i))
                start = None
        return blocks
    # YAML: a `code: |` (or `|-`, `|+`) key, then every line indented deeper than the key or blank.
    i = 0
    while i < len(lines):
        m = re.match(r"^(\s*)(?:-\s+)?code:\s*\|[-+]?\s*$", lines[i])
        if not m:
            i += 1
            continue
        key_indent = len(m.group(1))
        j = i + 1
        while j < len(lines):
            line = lines[j]
            if line.strip() and len(line) - len(line.lstrip()) <= key_indent:
                break
            j += 1
        blocks.append((i + 1, j))
        i = j
    return blocks


def _closing_paren(line: str, open_at: int) -> int:
    """Index of the `)` matching `line[open_at]`, or -1 if it is not on this line. Skips string and
    char literals; a `//` comment ends the search."""
    depth, j = 0, open_at
    while j < len(line):
        c = line[j]
        if c == '"':
            j += 1
            while j < len(line) and line[j] != '"':
                j += 2 if line[j] == "\\" else 1
        elif c == "'" and (m := re.match(r"'(\\.|[^\\'])'", line[j:])):
            j += m.end() - 1
        elif line.startswith("//", j):
            return -1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return j if c == ")" else -1
        j += 1
    return -1


def _spread(args: str) -> bool:
    depth = 0
    for k, c in enumerate(args):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "*" and depth == 0 and not re.search(r"[\w)\]]\s*$", args[:k]):
            return True
    return False


def list_literals(line: str, prev: str = "", next: str = "") -> tuple[str, bool] | None:
    """`(rewritten line, every call converted)` if the line holds a `listOf(...)` with arguments.
    `prev` is the line before, whose trailing operator makes a call opening this line an operand.
    `next` is the line after: a call that is the whole statement right before a closing `}` is a
    lambda's result, which has no expected type, so a literal there does not compile (11-02's
    flow_matching capture failure, reintroduced by a lander fixup on 11-03)."""
    out, pos, found, complete = [], 0, False, True
    while m := LIST_OF.search(line, pos):
        close = _closing_paren(line, m.end() - 1)
        args = line[m.end():close] if close >= 0 else line[m.end():]
        before = line[:m.start()]
        after = line[close + 1:].split("//")[0].strip() if close >= 0 else ""
        operand = bool(OPERATOR_BEFORE.search(before) or
                       (not before.strip() and OPERATOR_BEFORE.search(prev.split("//")[0])) or
                       (close >= 0 and re.match(r"[-+*/%]", after)))
        receiver = close >= 0 and (after.startswith((".", "?.", "!!")) or
                                   (not after and next.strip().startswith((".", "?."))))
        result = close >= 0 and (
            (not before.strip() and not after and next.strip().startswith("}")) or
            (re.search(r"(?:->|\{)\s*$", before) and after.startswith("}"))
        )
        if operand or receiver or result or (close >= 0 and (not args.strip() or _spread(args))):
            out.append(line[pos:m.end()])
            pos = m.end()
            continue
        found = True
        if close < 0:
            complete = False
            break
        out.append(line[pos:m.start()] + "[")
        pos = m.end()
        inner = list_literals(args)
        out.append((inner[0] if inner else args) + "]")
        complete = complete and (inner is None or inner[1])
        pos = close + 1
    if not found:
        return None
    out.append(line[pos:])
    return "".join(out), complete


def _helper_findings(lines: list[str], i: int, end: int, makes_push: bool) -> list[tuple[int, str, str, bool]]:
    """Findings of the approved-helper rules on line ``i``."""
    line, out = lines[i], []
    if key_pairs(line) is not None:
        out.append((i, "key-pairs",
                    "write `scope.Key(\"name\" to value, ...)`: `Key` takes the pairs itself", True))
    if fixed_formats(line) is not None:
        out.append((i, "fixed-format",
                    "write `x.f3` for `\"%.3f\".format(x)` (Number.f0..f9, Locale.ROOT)", True))
    if STARTING_CAST.search(line):
        out.append((i, "starting-value-cast",
                    "write `q.startAt(v)`: smoothTo/inertialTo return Interpolant<T>, no cast needed", True))
    if uint_lambda(line) is not None:
        if makes_push:
            out.append((i, "uint-push-lambda",
                        "write `{ n }`: generated uint push members take an Int lambda", True))
        else:
            out.append((i, "uint-push-lambda",
                        "if this is a generated uint push member, drop `.toUInt()`: it takes an Int lambda", False))
    if barrier_task(lines, i, end) is not None:
        out.append((i, "compute-barrier",
                    "write `computeBarrier()` (`computeBarrier(graphics = true)` when it also publishes to "
                    "graphics) instead of a computeTask of memory barriers", True))
    if HAND_WALL_ORTHO.search(line):
        out.append((i, "wall-projection",
                    "write `m.push[\"worldToClip\"] = program.wall.worldToClip()` (import "
                    "afield.lang.rectsl.worldToClip): it also honours a top-left wall origin", False))
    if m := TRIANGLE_RESIZE.search(line):
        out.append((i, "fullscreen-triangle",
                    f"if the vertex shader draws from gl_VertexIndex with no attributes, write "
                    f"`val {m['mesh']} = fullscreenTriangle({m['module'].strip()})` instead of a Mesh resized to 3", False))
    return out


def check_block(lines: list[str], start: int, end: int, literals: bool = False,
                helpers: bool = True) -> list[tuple[int, str, str, bool]]:
    """(line index, rule, message, fixable) for one Kotlin block. ``helpers`` False skips the
    approved-helper rules (a listing quoting recorded source)."""
    text = "\n".join(lines[start:end])
    starts_coroutines = bool(COROUTINE_START.search(text))
    makes_push = bool(MAKES_PUSH_MEMBERS.search(text))
    out = []
    for i in range(start, end):
        line = lines[i]
        if RENDERER_LINE.match(line):
            if starts_coroutines:
                out.append((i, "redundant-renderer",
                            "`renderer` is inherited from the renderer box; this box starts coroutines, so keep the "
                            "copy only if a `start { }` body uses it, and otherwise delete it", False))
            else:
                out.append((i, "redundant-renderer",
                            "delete: `renderer` is inherited from the renderer box (prop<Renderer>(renderer))", True))
        elif m := BIND_PUSH.match(line):
            out.append((i, "bind-push-constant",
                        f"write `{m['target']}.{m['name']} = {m['lambda']}`: moduleHere generates the property", True))
        elif m := LOOKUP_ALIAS.match(line):
            out.append((i, "box-context-lookup",
                        f"write `val {m['name']} = thisBox`, and consider removing `{m['name']}`: the box is the "
                        f"implicit receiver", True))
        elif m := BOX_ALIAS.match(line):
            out.append((i, "box-alias",
                        f"`{m['name']}` is the box this code runs in, already the implicit receiver; unless it earns "
                        f"its line, write `foo` for `{m['name']}.foo` and `thisBox` where the box is an argument", False))
        elif BOX_CONTEXT.search(line):
            out.append((i, "box-context-lookup",
                        "write `thisBox` (and usually not even that: the box is the implicit receiver)", True))
        elif SPACED_MARKER.match(line):
            out.append((i, "spaced-fragment-marker",
                        "write `//%-`, the documented spelling of a fragment break", True))
        if helpers:
            out.extend(_helper_findings(lines, i, end, makes_push))
        if literals and (lit := list_literals(line, lines[i - 1] if i > start else "",
                                                    lines[i + 1] if i + 1 < end else "")):
            out.append((i, "list-literal",
                        "write the list as a collection literal, `[a, b, c]`" if lit[1] else
                        "if no argument is a spread (`*xs`), write the list as a collection literal: change "
                        "`listOf(` to `[` and its `)` to `]`", lit[1]))
    return out


def _fix_helpers(line: str, makes_push: bool) -> str:
    if (rewritten := key_pairs(line)) is not None:
        line = rewritten
    if (rewritten := fixed_formats(line)) is not None:
        line = rewritten
    line = STARTING_CAST.sub(lambda m: f"{m['name']}.startAt(", line)
    if makes_push and (rewritten := uint_lambda(line)) is not None:
        line = rewritten
    return line


def fix_block(lines: list[str], start: int, end: int, literals: bool = False, helpers: bool = True) -> list[str]:
    """The block's lines with every fixable finding rewritten, and imports it orphaned removed."""
    block = lines[start:end]
    text = "\n".join(block)
    starts_coroutines = bool(COROUTINE_START.search(text))
    makes_push = bool(MAKES_PUSH_MEMBERS.search(text))
    out: list[str] = []
    removed_renderer = False
    skip_to = -1
    for k, line in enumerate(block):
        if k <= skip_to:
            continue
        if helpers and (barrier := barrier_task(block, k, len(block))):
            skip_to, line = barrier
            out.append(line)
            continue
        if helpers:
            line = _fix_helpers(line, makes_push)
        if RENDERER_LINE.match(line) and not starts_coroutines:
            removed_renderer = True
            continue
        if m := BIND_PUSH.match(line):
            out.append(f"{m['indent']}{m['target']}.{m['name']} = {m['lambda']}")
            continue
        if m := SPACED_MARKER.match(line):
            out.append(f"{m['indent']}//%-{m['rest']}")
            continue
        if m := LOOKUP_ALIAS.match(line):
            out.append(f"{m['head']}thisBox{m['tail']}")
            continue
        if BOX_CONTEXT.search(line):
            out.append(BOX_CONTEXT.sub("thisBox", line))
            continue
        if literals and (lit := list_literals(line, block[k - 1] if k else "",
                                                    block[k + 1] if k + 1 < len(block) else "")) and lit[1]:
            line = lit[0]
        out.append(line)
    if removed_renderer:
        for imp, use in UNUSED_IMPORTS.items():
            rest = [l for l in out if l.strip() != imp]
            if len(rest) != len(out) and not use.search("\n".join(rest)):
                out = rest
        # A deleted line between two blank lines leaves a double blank; keep one.
        collapsed: list[str] = []
        for line in out:
            if not line.strip() and collapsed and not collapsed[-1].strip():
                continue
            collapsed.append(line)
        # Nor may the block now open on a blank line where the imports were.
        while collapsed and not collapsed[0].strip():
            collapsed.pop(0)
        out = collapsed
    return out


RECORDED_OPEN = re.compile(r"^<!-- (?P<marker>[\w-]+-source):(?P<name>[\w-]+) -->\s*$")


def recorded_regions(lines: list[str]) -> list[tuple[int, int]]:
    """Line ranges between `<!-- X-source:N -->` and `<!-- /X-source:N -->`, which `conversation_demo.sync_blocks` fills."""
    regions, open_at = [], {}
    for i, line in enumerate(lines):
        if (m := RECORDED_OPEN.match(line)):
            open_at[(m["marker"], m["name"])] = i
        elif line.startswith("<!-- /"):
            key = tuple(line[6:].removesuffix(" -->").strip().split(":", 1))
            if key in open_at:
                regions.append((open_at.pop(key), i))
    return regions


def process(path: str, root: Path, fix: bool, collection_literals: bool = False) -> list[Finding]:
    file = root / path
    try:
        original = file.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []
    lines = original.split("\n")
    blocks = kotlin_blocks(path, lines)
    literals = collection_literals or lists_as_literals(path)
    recorded = recorded_regions(lines)
    # A listing a packager copies from saved source (between `<!-- NAME-source:BLOCK -->` markers) shows the
    # recorded bytes; rewriting its lists would make the page disagree with the record it quotes.
    quoted = lambda start: any(a <= start < b for a, b in recorded)
    spelled = lambda start: literals and not quoted(start)
    findings = [
        Finding(path, i + 1, rule, message, fixable, blocking=fixable or rule not in NOTE_RULES)
        for start, end in blocks
        for i, rule, message, fixable in check_block(lines, start, end, spelled(start), not quoted(start))
    ]
    if fix and any(f.fixable for f in findings):
        for start, end in reversed(blocks):
            lines[start:end] = fix_block(lines, start, end, spelled(start), not quoted(start))
        file.write_text("\n".join(lines), encoding="utf-8")
    return findings


MIRROR_FENCE = re.compile(r'^(`{3,})\w* \[field-property box="[^"]+" name="[^"]+" path="(?P<path>[^"]+)"\]\n'
                          r'(?P<body>.*?)^\1[ \t]*$', re.M | re.S)


def refresh_mirror_digests(document_md: Path) -> None:
    """Re-derive document.md's ``lines``/``characters``/``sha256`` for blocks whose fence is their sidecar.

    The counts are ``SavingContext``'s: characters is the Kotlin string's length (UTF-16 units), lines
    counts newlines plus an unterminated last line. Blocks that already disagree with their sidecar are
    left alone: that drift predates this edit and is a re-save in Field, not a digest.
    """
    demo = document_md.parent
    original = text = document_md.read_text(encoding="utf-8")
    for m in MIRROR_FENCE.finditer(original):
        sidecar = demo / m["path"]
        if not sidecar.is_file():
            continue
        source = sidecar.read_text(encoding="utf-8")
        if m["body"].rstrip("\n") != source.rstrip("\n"):
            continue
        lines = source.count("\n") + (0 if not source or source.endswith("\n") else 1)
        characters = len(source.encode("utf-16-le")) // 2
        digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
        text = re.sub(
            r'("path" "%s" "lines" )\d+( "characters" )\d+( "sha256" ")\w+(")' % re.escape(m["path"]),
            lambda d: f"{d[1]}{lines}{d[2]}{characters}{d[3]}{digest}{d[4]}", text,
        )
    if text != original:
        document_md.write_text(text, encoding="utf-8")


def tracked_paths(root: Path) -> list[str] | None:
    """What git tracks under the corpus roots, or None when ``root`` is not itself a checkout's top.

    An inherited ``GIT_DIR`` / ``GIT_WORK_TREE`` (a hook, the lander) would point git at some other
    repository than ``root``; the bounce of 2026-10-08 was the tests' fixture directory answered
    with the real repository's files, so neither is passed on and the top level must be ``root``.
    """
    env = {k: v for k, v in os.environ.items() if k not in ("GIT_DIR", "GIT_WORK_TREE")}

    def git(*args: str) -> str:
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=True,
                              env=env).stdout.decode("utf-8")

    try:
        if Path(git("rev-parse", "--show-toplevel").strip()).resolve() != root.resolve():
            return None
        return [p for p in git("ls-files", "-z", "--", *CORPUS_ROOTS).split("\0") if p]
    except (OSError, subprocess.CalledProcessError):
        return None


def corpus_paths(root: Path) -> list[str]:
    tracked = tracked_paths(root)
    if tracked is not None:
        return sorted(p for p in tracked if in_corpus(p) and (root / p).is_file())
    # Not a checkout (the tests' fixture directories): every file on disk.
    paths = []
    for corpus in CORPUS_ROOTS:
        base = root / corpus
        if not base.exists():
            continue
        for file in base.rglob("*"):
            if file.is_file():
                rel = file.relative_to(root).as_posix()
                if in_corpus(rel):
                    paths.append(rel)
    return sorted(paths)


def scoped_paths(args: argparse.Namespace) -> list[str] | None:
    if args.paths:
        return args.paths
    listing = os.environ.get("AFIELD_GUARD_CHANGED_PATHS_FILE")
    if listing:
        return [l.strip() for l in Path(listing).read_text(encoding="utf-8").splitlines() if l.strip()]
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--paths", nargs="*", help="repo-relative files to check (default: the whole corpus)")
    parser.add_argument("--collection-literals", action="store_true",
                        help="also audit collection literals in selected transcripts, demos and templates")
    parser.add_argument("--fix", action="store_true", help="rewrite fixable findings in place")
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    try:
        scope = scoped_paths(args)
    except OSError as e:
        print(f"check_box_idioms: {e}", file=sys.stderr)
        return 2
    paths = corpus_paths(args.root) if scope is None else sorted(
        p for p in scope if in_corpus(p) and (args.root / p).is_file()
    )

    findings = [f for path in paths for f in process(path, args.root, args.fix, args.collection_literals)]
    if args.fix:
        # A fixed demo file has copies in its document.md mirror: fix those fences too (even when the
        # mirror was not in scope), then bring the mirror's digests up to date.
        demos = sorted({"/".join(f.path.split("/")[:4]) for f in findings if f.fixable and _under(f.path, DEMOS)})
        for demo in demos:
            mirror = f"{demo}/document.md"
            if (args.root / mirror).is_file():
                if mirror not in paths:
                    process(mirror, args.root, True, args.collection_literals)
                refresh_mirror_digests(args.root / mirror)
        if demos:
            print("check_box_idioms: refresh the inline copies with ./gradlew regenerateAgentDemos -Ponly="
                  + ",".join(d.split("/")[-1] for d in demos))
    remaining = [f for f in findings if not (args.fix and f.fixable)]
    notes = [f for f in remaining if not f.blocking]
    remaining = [f for f in remaining if f.blocking]
    for f in notes:
        print(f"{f.path}:{f.line}: note [{f.rule}] {f.message}")
    if args.fix:
        fixed = len(findings) - len(remaining)
        if fixed:
            print(f"check_box_idioms: fixed {fixed} finding(s); now run ./gradlew checkBoxCodeCompiles")
    for f in remaining:
        print(f"{f.path}:{f.line}: [{f.rule}] {f.message}")
    if remaining:
        hint = " (re-run with --fix)" if any(f.fixable for f in remaining) else ""
        print(f"check_box_idioms: {len(remaining)} finding(s) in {len({f.path for f in remaining})} file(s){hint}",
              file=sys.stderr)
        return 1
    scope_word = "the corpus" if scope is None else f"{len(paths)} file(s)"
    print(f"check_box_idioms: clean ({scope_word})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
