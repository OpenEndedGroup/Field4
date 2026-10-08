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
- ``box-context-lookup``: ``Box.context.get()!!`` (or ``afield.Box.context.get() ?: throw ...``). That
  is ``thisBox``, and ``thisBox`` itself is rarely needed: the box is the implicit receiver, so
  ``thisBox.foo`` is ``foo``. (It cannot be spelled ``this``.) Rewritten to ``thisBox``. An alias
  (``val historyOwner = thisBox``) is usually a smell but not always wrong, so it is a note: printed,
  never rewritten, and not a failure. Removing one means rewriting each use (``historyOwner.foo`` is
  ``foo``, ``historyOwner`` as an argument is ``thisBox``), which the revision pass or a person does.
- ``spaced-fragment-marker``: ``// %-``. The splitter accepts it (``isFragmentBreak``), but until
  2026-10-05 it matched ``//%-`` exactly, so older Field ran a spaced marker as one fragment;
  published code uses the one documented spelling.

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
    "metadata_store",
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
BOX_CONTEXT = re.compile(r"(?<![\w.])(?:afield\.)?Box\.context\.get\(\)(?:!!)?")
# `val owner = Box.context.get() ?: throw IllegalStateException("...")`: the lookup and its guard are one
# `thisBox`, which cannot be null.
LOOKUP_ALIAS = re.compile(
    r"^(?P<head>\s*va[lr]\s+(?P<name>\w+)\s*(?::\s*Box\??\s*)?=\s*)(?:afield\.)?Box\.context\.get\(\)"
    r"(?:!!|\s*\?:\s*(?:throw\s+\w+\(.*\)|error\(.*\)))?(?P<tail>\s*(?://.*)?)$"
)
BOX_ALIAS = re.compile(r"^\s*va[lr]\s+(?P<name>\w+)\s*(?::\s*Box\??\s*)?=\s*thisBox\s*(?://.*)?$")
SPACED_MARKER = re.compile(r"^(?P<indent>\s*)//\s+%-(?P<rest>.*)$")
UNUSED_IMPORTS = {
    "import afield.four.Renderer": re.compile(r"\bRenderer\b"),
    "import afield.four.modules.fromContext": re.compile(r"\bfromContext\b"),
}


# Rules whose unfixable findings are judgement calls: printed as notes, never a failure.
NOTE_RULES = {"redundant-renderer", "box-alias"}


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


def check_block(lines: list[str], start: int, end: int) -> list[tuple[int, str, str, bool]]:
    """(line index, rule, message, fixable) for one Kotlin block."""
    text = "\n".join(lines[start:end])
    starts_coroutines = bool(COROUTINE_START.search(text))
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
    return out


def fix_block(lines: list[str], start: int, end: int) -> list[str]:
    """The block's lines with every fixable finding rewritten, and imports it orphaned removed."""
    block = lines[start:end]
    text = "\n".join(block)
    starts_coroutines = bool(COROUTINE_START.search(text))
    out: list[str] = []
    removed_renderer = False
    for line in block:
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


def process(path: str, root: Path, fix: bool) -> list[Finding]:
    file = root / path
    try:
        original = file.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []
    lines = original.split("\n")
    blocks = kotlin_blocks(path, lines)
    findings = [
        Finding(path, i + 1, rule, message, fixable, blocking=fixable or rule not in NOTE_RULES)
        for start, end in blocks
        for i, rule, message, fixable in check_block(lines, start, end)
    ]
    if fix and any(f.fixable for f in findings):
        for start, end in reversed(blocks):
            lines[start:end] = fix_block(lines, start, end)
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


def corpus_paths(root: Path) -> list[str]:
    try:
        listed = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", *CORPUS_ROOTS],
                                capture_output=True, check=True).stdout.decode("utf-8")
        tracked = [p for p in listed.split("\0") if p]
    except (OSError, subprocess.CalledProcessError):
        tracked = None
    if tracked:
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

    findings = [f for path in paths for f in process(path, args.root, args.fix)]
    if args.fix:
        # A fixed demo file has copies in its document.md mirror: fix those fences too (even when the
        # mirror was not in scope), then bring the mirror's digests up to date.
        demos = sorted({"/".join(f.path.split("/")[:4]) for f in findings if f.fixable and _under(f.path, DEMOS)})
        for demo in demos:
            mirror = f"{demo}/document.md"
            if (args.root / mirror).is_file():
                if mirror not in paths:
                    process(mirror, args.root, True)
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
