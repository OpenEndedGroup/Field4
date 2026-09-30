#!/usr/bin/env python3
"""Documentation notes as an asynchronous, git-aware revision queue.

A human writes notes on the rendered documentation (the panel `/field-docs/…` injects, see
`src/commonMain/resources/docsAnnotations.js`); the notes land in the annotation store
(`~/.afield/docs-annotations.json`, or `AFIELD_DOCS_ANNOTATIONS_STORE`). This tool is the other
half, for agents and for the human driving them:

  sync      associate every note with the Markdown (or KDoc) it was written against, and carry
            that association forward through later commits using `git blame` line identity
  queue     the open notes, grouped by the page an agent would edit
  brief     everything an agent needs for one page: open notes with current line numbers and
            text, the page's memories, and what was resolved there recently
  resolve / question / memory / reply / reopen
            record an outcome on a note (agents: inside a run these go to a journal the
            harness applies; humans: straight to the store)
  handoff   queue a larger task — a code change, or a cross-cutting docs change such as
            merging two pages — as a document in dev_docs/handoffs/, optionally blocking
            the page until it is closed; `handoffs` lists them for a separate, interactive
            pass, `handoff-close` finishes one
  revise    the harness: sync, partition the queue per page, run one agent per page in
            parallel, apply what they recorded, optionally commit one commit per page

Design notes, in the order they bite:

* **Line identity is a blame origin.** A note is anchored to the lines of its source file as the
  triple ``(commit, path, line)`` that `git blame` attributes each line to. An unchanged line keeps
  its origin however much the rest of the file is edited, reordered or grows, so re-blaming the
  current file and looking the origins up again finds the note's lines wherever they went. A line
  that is edited gets a new origin; when every anchored line has changed the note falls back to
  matching its quote against the current text, and says so in ``source.status``.
* **The text a human read is the text we match.** The docs build writes
  ``field-docs-build.json`` (the commit it was built from) into the site; the panel copies that
  commit into the note's anchor. Matching happens against ``git show <that commit>:<page>.md``,
  which is what makes a note written days before a sync still land on the right sentence.
* **The store is shared with the Kotlin server**, which rewrites the whole file from its own data
  classes. Any top-level key this tool writes must therefore be declared on `DocsAnnotation` in
  `docsAnnotations.kt`, or the server silently drops it on its next write.
  `test_docs_notes.KotlinSchemaTest` enforces that; Python-only detail lives inside ``source``
  (a free-form JSON object on the Kotlin side). Both sides lock ``<store>.lock`` with POSIX
  record locks (``fcntl.lockf`` here, ``FileChannel.lock`` there) around read-modify-write.
* **Agents never write the store.** A sandboxed agent may not be able to reach ``~/.afield`` at
  all, and parallel writers are how notes get lost. Inside a run the CLI appends to a journal in
  the run directory; the harness validates each entry against the notes that agent was given and
  applies them serially.

Stdlib only, so it runs under whatever Python the caller has.
"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import difflib
import fcntl
import hashlib
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator
from urllib.parse import unquote, urlsplit

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_ORIGIN = "https://openendedgroup.com"
DOCS_ROOT = "/field4/"
DOCS_SOURCE_DIR = "public/docs"
BUILD_INFO_NAME = "field-docs-build.json"
STORE_ENV = "AFIELD_DOCS_ANNOTATIONS_STORE"
JOURNAL_ENV = "DOCS_NOTES_JOURNAL"
PARTITION_ENV = "DOCS_NOTES_PARTITION"
AUTHOR_ENV = "DOCS_NOTES_AUTHOR"
ZERO_SHA = "0" * 40
HISTORY_LIMIT = 12

# Top-level annotation keys this tool may write. Each must be a property of `DocsAnnotation` in
# docsAnnotations.kt — see the module docstring and KotlinSchemaTest.
WRITTEN_KEYS = frozenset({"id", "pageUrl", "note", "tags", "anchor", "kind", "thread", "source",
                          "resolvedAtEpochMs", "resolution", "updatedAtEpochMs", "createdAtEpochMs",
                          "recordedInCheckout", "recordedAtCommit", "handoff"})

ENTRY_KINDS = ("comment", "memory", "question", "reply", "resolution", "reopen", "run", "handoff", "blocked", "unblocked")
HANDOFF_DIR = "dev_docs/handoffs"
HANDOFF_SCOPES = ("project", "crosscutting")
PAGE_URL_ENV = "DOCS_NOTES_PAGE_URL"
RUN_DIR_ENV = "DOCS_NOTES_RUN_DIR"


def now_ms() -> int:
    return int(time.time() * 1000)


def new_entry_id() -> str:
    return uuid.uuid4().hex[:12]


# ─────────────────────────────────────────────────────────────────────────────
# The store
# ─────────────────────────────────────────────────────────────────────────────


def resolve_store_path(explicit: str | None = None, *, environ: dict[str, str] | None = None,
                       home: Path | None = None, cwd: Path | None = None) -> Path:
    """The same rule as `resolveDocsAnnotationStorePath`: explicit, then env, then `~/.afield`.

    A relative path resolves against *cwd*, which for the server is its working directory and for
    this tool is wherever you ran it — run it from the checkout the server was started in.
    """
    environ = os.environ if environ is None else environ
    configured = (explicit or "").strip() or environ.get(STORE_ENV, "").strip()
    path = Path(configured) if configured else (home or Path.home()) / ".afield" / "docs-annotations.json"
    if not path.is_absolute():
        path = (cwd or Path.cwd()) / path
    return Path(os.path.normpath(path))


class Store:
    """The annotation JSON, read and written as plain dicts so unknown fields survive."""

    def __init__(self, path: Path):
        self.path = path
        self.lock_path = path.with_name(path.name + ".lock")
        self._thread_lock = threading.RLock()

    @contextlib.contextmanager
    def locked(self) -> Iterator[None]:
        with self._thread_lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.lock_path, "a+b") as handle:
                fcntl.lockf(handle, fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    fcntl.lockf(handle, fcntl.LOCK_UN)

    def _read(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"version": 1, "annotations": []}
        data = json.loads(self.path.read_text(encoding="utf-8") or "{}")
        data.setdefault("version", 1)
        data.setdefault("annotations", [])
        return data

    def _write(self, data: dict[str, Any]) -> None:
        fd, temp = tempfile.mkstemp(prefix="docs-annotations-", suffix=".json", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(data, handle, indent=2, ensure_ascii=False)
                handle.write("\n")
            os.replace(temp, self.path)
        except BaseException:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(temp)
            raise

    def notes(self) -> list[dict[str, Any]]:
        with self.locked():
            return [normalize_note(n) for n in self._read()["annotations"]]

    def mutate(self, change: Callable[[list[dict[str, Any]]], Any]) -> Any:
        """Read, let *change* edit the list in place, write — all under the lock."""
        with self.locked():
            data = self._read()
            notes = [normalize_note(n) for n in data["annotations"]]
            result = change(notes)
            data["annotations"] = notes
            self._write(data)
            return result


def normalize_note(note: dict[str, Any]) -> dict[str, Any]:
    note.setdefault("kind", "note")
    note.setdefault("thread", [])
    note.setdefault("tags", [])
    note.setdefault("anchor", {})
    return note


def find_note(notes: list[dict[str, Any]], note_id: str) -> dict[str, Any]:
    exact = [n for n in notes if n.get("id") == note_id]
    if exact:
        return exact[0]
    prefixed = [n for n in notes if str(n.get("id", "")).startswith(note_id)]
    if len(prefixed) == 1:
        return prefixed[0]
    if not prefixed:
        raise SystemExit(f"no note with id {note_id!r}")
    raise SystemExit(f"id prefix {note_id!r} is ambiguous: {', '.join(n['id'] for n in prefixed)}")


def is_resolved(note: dict[str, Any]) -> bool:
    return note.get("resolvedAtEpochMs") is not None


def awaiting_human(note: dict[str, Any]) -> bool:
    """True when an agent has asked a question that nothing has answered since.

    Computed, not stored: a human reply (in the panel or with `reply`) or a reopen after the
    question puts the note back in the queue with nothing else to remember.
    """
    pending = False
    for entry in note.get("thread", []):
        if entry.get("kind") == "question":
            pending = True
        elif entry.get("kind") in ("reply", "reopen"):
            pending = False
    return pending


def blocked_by(note: dict[str, Any]) -> str | None:
    """The handoff this note is waiting on, if a blocking one was filed and not yet closed."""
    handoff = None
    for entry in note.get("thread", []):
        if entry.get("kind") == "blocked":
            handoff = entry.get("handoff") or "?"
        elif entry.get("kind") in ("unblocked", "reopen"):
            handoff = None
    return handoff


def note_state(note: dict[str, Any]) -> str:
    if note.get("kind") == "memory":
        return "memory"
    if note.get("kind") == "handoff":
        return "handoff-" + ((note.get("handoff") or {}).get("state") or "open")
    if is_resolved(note):
        return "resolved"
    if blocked_by(note):
        return "blocked"
    if awaiting_human(note):
        return "question"
    return "open"


def in_queue(note: dict[str, Any], tags: Iterable[str] = ()) -> bool:
    wanted = {t.lower().lstrip("@") for t in tags}
    if note_state(note) != "open":
        return False
    return not wanted or bool(wanted & {t.lower() for t in note.get("tags", [])})


def add_entry(note: dict[str, Any], kind: str, text: str, author: str, **extra: Any) -> dict[str, Any]:
    if kind not in ENTRY_KINDS:
        raise ValueError(f"unknown entry kind {kind!r}")
    entry = {"id": new_entry_id(), "kind": kind, "author": author, "text": text, "atEpochMs": now_ms()}
    entry.update({k: v for k, v in extra.items() if v not in (None, "", [], {})})
    note.setdefault("thread", []).append(entry)
    note["updatedAtEpochMs"] = entry["atEpochMs"]
    return entry


# ─────────────────────────────────────────────────────────────────────────────
# Git
# ─────────────────────────────────────────────────────────────────────────────


class Git:
    def __init__(self, root: Path):
        self.root = root
        self._blame_cache: dict[tuple[str, str], list[BlameLine]] = {}
        self._show_cache: dict[tuple[str, str], str | None] = {}

    def run(self, *args: str, check: bool = True, input_text: str | None = None) -> str:
        result = subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True,
                                input=input_text, check=False)
        if check and result.returncode != 0:
            raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout if result.returncode == 0 else ""

    def forget(self) -> None:
        """Drop cached file text and blame; call after anything may have edited or committed."""
        self._blame_cache.clear()
        self._show_cache.clear()

    def head(self) -> str:
        return self.run("rev-parse", "HEAD").strip()

    def commit_exists(self, sha: str) -> bool:
        if not sha or not re.fullmatch(r"[0-9a-fA-F]{7,40}", sha):
            return False
        return subprocess.run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], cwd=self.root,
                              capture_output=True).returncode == 0

    def show(self, rev: str | None, path: str) -> str | None:
        """File text at *rev*, or the working tree when *rev* is None."""
        key = (rev or "", path)
        if key not in self._show_cache:
            if rev is None:
                file = self.root / path
                self._show_cache[key] = file.read_text(encoding="utf-8") if file.is_file() else None
            else:
                result = subprocess.run(["git", "show", f"{rev}:{path}"], cwd=self.root,
                                        capture_output=True, check=False)
                self._show_cache[key] = result.stdout.decode("utf-8", "replace") if result.returncode == 0 else None
        return self._show_cache[key]

    def is_tracked(self, path: str) -> bool:
        return bool(self.run("ls-files", "--", path, check=False).strip())

    def blame(self, rev: str | None, path: str) -> list["BlameLine"]:
        """Per-line origins of *path* at *rev* (None = the working tree, uncommitted lines zero)."""
        key = (rev or "", path)
        if key not in self._blame_cache:
            args = ["blame", "--line-porcelain", "-M"]
            if rev:
                args.append(rev)
            args += ["--", path]
            output = self.run(*args, check=False)
            self._blame_cache[key] = parse_line_porcelain(output)
        return self._blame_cache[key]

    def renamed_to(self, since: str, path: str) -> str | None:
        """Where *path* lives now, if it was renamed after *since*; None if it vanished."""
        if (self.root / path).is_file():
            return path
        output = self.run("diff", "-M", "--name-status", since, "--", check=False)
        current = path
        for line in output.splitlines():
            parts = line.split("\t")
            if parts and parts[0].startswith("R") and len(parts) == 3 and parts[1] == current:
                current = parts[2]
        return current if (self.root / current).is_file() else None

    def status_paths(self) -> dict[str, str]:
        """Working-tree status, path → two-letter code, including untracked files."""
        out = self.run("status", "--porcelain=v1", "-z", "--untracked-files=all", check=False)
        paths: dict[str, str] = {}
        items = out.split("\0")
        index = 0
        while index < len(items):
            item = items[index]
            index += 1
            if len(item) < 4:
                continue
            code, path = item[:2], item[3:]
            if code[0] in "RC":
                index += 1  # the rename's source follows
            paths[path] = code
        return paths

    def staged_paths(self) -> list[str]:
        return [p for p in self.run("diff", "--cached", "--name-only", check=False).splitlines() if p]


@dataclasses.dataclass(frozen=True)
class BlameLine:
    sha: str
    orig_path: str
    orig_line: int
    final_line: int
    text: str

    @property
    def origin(self) -> tuple[str, str, int] | None:
        return None if self.sha == ZERO_SHA else (self.sha, self.orig_path, self.orig_line)


def parse_line_porcelain(output: str) -> list[BlameLine]:
    lines: list[BlameLine] = []
    header: list[str] | None = None
    filename = ""
    for raw in output.split("\n"):
        if header is None:
            parts = raw.split(" ")
            if len(parts) >= 3 and re.fullmatch(r"[0-9a-f]{40}", parts[0]):
                header = parts
                filename = ""
            continue
        if raw.startswith("\t"):
            lines.append(BlameLine(header[0], filename, int(header[1]), int(header[2]), raw[1:]))
            header = None
        elif raw.startswith("filename "):
            filename = raw[len("filename "):]
    return lines


# ─────────────────────────────────────────────────────────────────────────────
# Pages → source files
# ─────────────────────────────────────────────────────────────────────────────


GENERATED_HEADER = re.compile(r"<!--\s*DO NOT EDIT\s*[—-]+\s*generated by (?P<generator>[^\s]+)(?P<rest>[^>]*)-->")
REGENERATE_HEADER = re.compile(r"<!--\s*Regenerate:\s*(?P<command>.+?)\s*-->")


@dataclasses.dataclass
class PageSource:
    page_url: str
    relative: str                  # docs-relative page, e.g. "reference/graphics.md"
    path: str | None               # repo-relative file a human would edit, if there is one
    generated: dict[str, str] | None = None

    @property
    def kind(self) -> str:
        if self.path is None:
            return "missing"
        return "generated" if self.generated else "markdown"


def page_relative_candidates(page_url: str) -> list[str]:
    """Docs-relative Markdown paths a published page URL could have been built from."""
    parts = urlsplit(page_url)
    path = unquote(parts.path)
    if not path.startswith(DOCS_ROOT):
        return []
    rel = path[len(DOCS_ROOT):]
    if rel in ("", "index.html"):
        return ["index.md"]
    if rel.endswith("/index.html"):
        rel = rel[: -len("/index.html")]
    elif rel.endswith(".html"):
        rel = rel[: -len(".html")]
    rel = rel.rstrip("/")
    return [f"{rel}.md", f"{rel}/index.md", f"{rel}/README.md"]


def resolve_page_source(page_url: str, git: Git) -> PageSource:
    candidates = page_relative_candidates(page_url)
    for rel in candidates:
        repo_path = f"{DOCS_SOURCE_DIR}/{rel}"
        file = git.root / repo_path
        if file.is_file():
            head = file.read_text(encoding="utf-8", errors="replace")[:600]
            generated = None
            match = GENERATED_HEADER.search(head)
            if match:
                regenerate = REGENERATE_HEADER.search(head)
                generated = {"generator": match.group("generator"),
                             "from": match.group("rest").strip().removeprefix("from").strip(" ."),
                             "regenerate": regenerate.group("command") if regenerate else ""}
            return PageSource(page_url, rel, repo_path, generated)
        # `reference/source/…` pages are rendered from a Kotlin file at build time and never
        # committed; the file to edit is the Kotlin itself (SourcePageGenerator.pagePathFor).
        if rel.startswith("reference/source/") and rel.endswith(".md"):
            trimmed = rel[len("reference/source/"):-len(".md")]
            source_set, _, rest = trimmed.partition("/")
            kotlin = f"src/{source_set}/kotlin/{rest}.kt"
            if (git.root / kotlin).is_file():
                return PageSource(page_url, rel, kotlin, {"generator": "afield.docs.SourcePageGenerator",
                                                          "from": kotlin, "regenerate": "./gradlew generateSourcePages"})
    return PageSource(page_url, candidates[0] if candidates else "", None)


# ─────────────────────────────────────────────────────────────────────────────
# Rendered text ↔ Markdown source
# ─────────────────────────────────────────────────────────────────────────────


@dataclasses.dataclass
class Projection:
    """The text a reader sees, with each character's offset in the source it came from."""
    text: str
    offsets: list[int]
    source: str

    def line_of(self, source_offset: int) -> int:
        return self.source.count("\n", 0, max(0, source_offset)) + 1


_TYPOGRAPHY = str.maketrans({"‘": "'", "’": "'", "“": '"', "”": '"', " ": " ",
                             " ": " ", " ": " ", "¶": " ", "​": ""})

_INLINE_DELETIONS = [
    re.compile(r"!\[[^\]]*\]\([^)]*\)"),                 # images render no text
    re.compile(r"<!--.*?-->"),                           # single-line HTML comments
    re.compile(r"</?[A-Za-z][^>]*>"),                    # HTML tags
    re.compile(r"\{\s*[:.#][^}]*\}"),                    # attribute lists
    re.compile(r"\[\^[^\]]+\]"),                         # footnote references
]
_LINK = re.compile(r"(\[)([^\]]+)(\]\([^)]*\)|\]\[[^\]]*\])")
_CODE_SPAN = re.compile(r"(`+)(.+?)\1")
_BLOCK_PREFIX = re.compile(r"^(\s*(?:>\s*)*)(#{1,6}\s+|[-*+]\s+(?:\[[ xX]\]\s+)?|\d+[.)]\s+)?")
_ADMONITION = re.compile(r"^(\s*(?:!!!|\?\?\?\+?)\s*[\w-]+(?:\s+[\w-]+)*\s*)(\"(.*)\")?\s*$")
_FENCE = re.compile(r"^\s*(`{3,}|~{3,})")


def project_markdown(source: str, *, kotlin: bool = False) -> Projection:
    """Strip Markdown (or KDoc comment) syntax, keeping a map back to source offsets.

    Deliberately approximate: it only has to make the rendered sentence findable, and the matcher
    tolerates what this misses. Fenced code keeps its content (a reader can select code) and loses
    its delimiters; HTML comments, front matter and markup characters go.
    """
    kept_chars: list[str] = []
    kept_offsets: list[int] = []
    offset = 0
    in_fence: str | None = None
    in_comment = False
    in_front_matter = False
    for line_number, line in enumerate(source.split("\n")):
        line_start = offset
        offset += len(line) + 1
        if line_number == 0 and line.strip() == "---" and not kotlin:
            in_front_matter = True
            continue
        if in_front_matter:
            if line.strip() in ("---", "..."):
                in_front_matter = False
            continue
        if in_comment:
            if "-->" in line:
                in_comment = False
            continue
        if not in_fence and line.lstrip().startswith("<!--") and "-->" not in line:
            in_comment = True
            continue
        fence = _FENCE.match(line)
        if fence:
            marker = fence.group(1)[0] * 3
            if in_fence is None:
                in_fence = marker
                continue
            if marker == in_fence:
                in_fence = None
                continue
        spans = _line_spans(line, in_fence is not None, kotlin)
        for start, end in spans:
            for index in range(start, end):
                kept_chars.append(line[index])
                kept_offsets.append(line_start + index)
        kept_chars.append("\n")
        kept_offsets.append(line_start + len(line))
    return _normalize(Projection("".join(kept_chars), kept_offsets, source))


def _line_spans(line: str, in_code: bool, kotlin: bool) -> list[tuple[int, int]]:
    """The [start, end) ranges of *line* that a reader would see."""
    if kotlin:
        comment = re.match(r"^\s*(/\*\*|\*/|\*(?!/))?\s?", line)
        start = comment.end() if comment else 0
        line_end = line.rfind("*/") if line.rstrip().endswith("*/") else len(line)
        deleted = [False] * len(line)
        for i in range(0, start):
            deleted[i] = True
        for i in range(line_end, len(line)):
            deleted[i] = True
        if not in_code:
            _delete_inline_markup(line, deleted)
        return _runs(deleted)
    if in_code:
        return [(0, len(line))]
    deleted = [False] * len(line)
    admonition = _ADMONITION.match(line)
    if admonition:
        for i in range(0, len(line)):
            deleted[i] = True
        if admonition.group(2):
            title_start = admonition.start(3)
            for i in range(title_start, title_start + len(admonition.group(3))):
                deleted[i] = False
        return _runs(deleted)
    prefix = _BLOCK_PREFIX.match(line)
    if prefix:
        for i in range(prefix.end()):
            deleted[i] = True
    _delete_inline_markup(line, deleted)
    return _runs(deleted)


def _delete_inline_markup(line: str, deleted: list[bool]) -> None:
    protected = [False] * len(line)
    for match in _CODE_SPAN.finditer(line):
        for i in range(match.start(1), match.end(1)):
            deleted[i] = True
        for i in range(match.end(2), match.end()):
            deleted[i] = True
        for i in range(match.start(2), match.end(2)):
            protected[i] = True
    for pattern in _INLINE_DELETIONS:
        for match in pattern.finditer(line):
            if any(protected[match.start():match.end()]):
                continue
            for i in range(match.start(), match.end()):
                deleted[i] = True
    for match in _LINK.finditer(line):
        if protected[match.start()]:
            continue
        for i in range(match.start(1), match.end(1)):
            deleted[i] = True
        for i in range(match.start(3), match.end(3)):
            deleted[i] = True
    for index, char in enumerate(line):
        if protected[index] or deleted[index]:
            continue
        if char == "*" or (char == "~" and "~~" in line[max(0, index - 1):index + 2]):
            deleted[index] = True
        elif char == "_":
            before = line[index - 1] if index > 0 else " "
            after = line[index + 1] if index + 1 < len(line) else " "
            if not (before.isalnum() and after.isalnum()):
                deleted[index] = True
        elif char == "\\" and index + 1 < len(line) and not line[index + 1].isalnum():
            deleted[index] = True


def _runs(deleted: list[bool]) -> list[tuple[int, int]]:
    runs: list[tuple[int, int]] = []
    start = None
    for index, gone in enumerate(deleted + [True]):
        if not gone and start is None:
            start = index
        elif gone and start is not None:
            runs.append((start, index))
            start = None
    return runs


def _normalize(projection: Projection) -> Projection:
    """Collapse whitespace and fold typography, keeping the offset map aligned."""
    chars: list[str] = []
    offsets: list[int] = []
    for char, offset in zip(projection.text, projection.offsets):
        char = char.translate(_TYPOGRAPHY)
        if char == "|":
            char = " "
        if not char:
            continue
        if char.isspace():
            if chars and chars[-1] == " ":
                continue
            char = " "
        chars.append(char)
        offsets.append(offset)
    return Projection("".join(chars), offsets, projection.source)


def normalize_quote(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").translate(_TYPOGRAPHY)).strip()


@dataclasses.dataclass
class Match:
    start_line: int
    end_line: int
    score: float
    method: str
    source_start: int
    source_end: int
    context: float | None = None   # 0..1 agreement with the recorded prefix/suffix; None if none was recorded


def locate_quote(projection: Projection, exact: str, prefix: str = "", suffix: str = "",
                 heading: str = "") -> Match | None:
    """Find a rendered quote in a projection: exact, then case-folded, then fuzzy.

    Several exact hits are ranked by how well the text around each agrees with the note's recorded
    prefix and suffix (and the heading above it), which is what separates the fourth "continue" on
    a page from the other three.
    """
    quote = normalize_quote(exact)
    if not quote:
        return None
    text = projection.text
    prefix_n, suffix_n = normalize_quote(prefix)[-48:], normalize_quote(suffix)[:48]
    for method, haystack, needle in (("exact", text, quote), ("casefold", text.lower(), quote.lower())):
        hits = [m.start() for m in re.finditer(re.escape(needle), haystack)]
        if hits:
            best = max(hits, key=lambda start: _context_score(text, start, len(quote), prefix_n, suffix_n,
                                                            heading, projection))
            score = 1.0 if method == "exact" else 0.95
            match = _match_at(projection, best, best + len(quote), score, method if len(hits) == 1 else f"{method}+context")
            parts = bool(prefix_n) + bool(suffix_n)
            if parts:
                match.context = round(_context_score(text, best, len(quote), prefix_n, suffix_n, "", projection) / parts, 3)
            return match
    return _fuzzy_locate(projection, quote, prefix_n, suffix_n)


def _context_score(text: str, start: int, length: int, prefix: str, suffix: str, heading: str,
                   projection: Projection) -> float:
    score = 0.0
    if prefix:
        before = text[max(0, start - len(prefix) - 4):start]
        score += difflib.SequenceMatcher(None, before, prefix, autojunk=False).ratio()
    if suffix:
        after = text[start + length:start + length + len(suffix) + 4]
        score += difflib.SequenceMatcher(None, after, suffix, autojunk=False).ratio()
    if heading:
        line = projection.line_of(projection.offsets[start])
        if nearest_heading(projection.source, line).strip("# ").lower() == normalize_quote(heading).lower():
            score += 0.5
    return score


def _fuzzy_locate(projection: Projection, quote: str, prefix: str, suffix: str) -> Match | None:
    text = projection.text
    if len(text) == 0:
        return None
    matcher = difflib.SequenceMatcher(None, text, quote, autojunk=False)
    blocks = sorted((b for b in matcher.get_matching_blocks() if b.size >= 8), key=lambda b: -b.size)[:6]
    best: tuple[float, int, int] | None = None
    for block in blocks:
        start = max(0, block.a - block.b)
        end = min(len(text), start + len(quote))
        ratio = difflib.SequenceMatcher(None, text[start:end], quote, autojunk=False).ratio()
        if best is None or ratio > best[0]:
            best = (ratio, start, end)
    if best is None or best[0] < 0.6:
        return None
    ratio, start, end = best
    # The window was placed by one block's offset in the quote, so an inserted or dropped word
    # shifts it into the neighbouring text (a heading above, say). Trim it to what matches.
    inner = [b for b in difflib.SequenceMatcher(None, text[start:end], quote, autojunk=False).get_matching_blocks()
             if b.size >= 3]
    if inner:
        start, end = start + inner[0].a, start + inner[-1].a + inner[-1].size
    return _match_at(projection, start, end, round(ratio, 3), "fuzzy")


def _match_at(projection: Projection, start: int, end: int, score: float, method: str) -> Match:
    source_start = projection.offsets[start]
    source_end = projection.offsets[max(start, end - 1)] + 1
    return Match(projection.line_of(source_start), projection.line_of(source_end - 1), score, method,
                 source_start, source_end)


def nearest_heading(source: str, line: int) -> str:
    lines = source.split("\n")
    in_fence = False
    heading = ""
    for index, text in enumerate(lines[:max(0, line)]):
        if _FENCE.match(text):
            in_fence = not in_fence
        elif not in_fence and re.match(r"^#{1,6}\s", text):
            heading = text.strip()
    return heading


def locate_heading(source: str, heading: str) -> int | None:
    wanted = normalize_quote(heading).lower().strip("# ")
    if not wanted:
        return None
    for index, text in enumerate(source.split("\n"), start=1):
        if re.match(r"^#{1,6}\s", text) and normalize_quote(text.lstrip("#")).lower().rstrip(" #") == wanted:
            return index
    return None


def kotlin_candidates(git: Git, quote: str, limit: int = 8) -> list[str]:
    """Kotlin files whose KDoc probably holds *quote*: the ones containing its longest trigrams."""
    words = re.findall(r"[A-Za-z0-9][\w'.-]*", quote)
    grams = sorted({" ".join(words[i:i + 3]) for i in range(max(0, len(words) - 2))}, key=len, reverse=True)[:4]
    if not grams:
        return []
    counts: dict[str, int] = {}
    for gram in grams:
        for path in git.run("grep", "-l", "-F", "-e", gram, "--", "src/*.kt", check=False).splitlines():
            counts[path] = counts.get(path, 0) + 1
    return [p for p, _ in sorted(counts.items(), key=lambda item: -item[1])][:limit]


# ─────────────────────────────────────────────────────────────────────────────
# Anchoring and forward tracking
# ─────────────────────────────────────────────────────────────────────────────


@dataclasses.dataclass
class Anchoring:
    path: str
    rev: str | None           # the commit the lines were found in; None = working tree
    start_line: int
    end_line: int
    method: str
    score: float


SHORT_QUOTE = 24


def chrome_selection(note: dict[str, Any]) -> bool:
    """A selection in the site header or navigation rather than in the page's own content.

    The panel says so in `anchor.region` since 2026-09-30. Notes from before then measured their
    context in `document.body.innerText`, which begins with the theme chrome, so a prefix holding
    "Skip to content" means the selection sat in the first few dozen characters of it.
    """
    anchor = note.get("anchor") or {}
    if anchor.get("region") == "chrome":
        return True
    return not anchor.get("region") and "Skip to content" in (anchor.get("prefix") or "")


def unanchored_reason(note: dict[str, Any], page: PageSource) -> str:
    anchor = note.get("anchor") or {}
    if page.path is None:
        return "no source file for this page"
    if chrome_selection(note):
        return "selected in the site header or navigation (public/zensical.yml, theme), not in the page"
    if not anchor.get("exact"):
        return "position-only note"
    if len(normalize_quote(anchor["exact"])) < SHORT_QUOTE and (anchor.get("prefix") or anchor.get("suffix")):
        return "short quote whose recorded context matches nowhere in the source"
    return "quote not found in source (captured output, or text since rewritten)"


def anchor_note(note: dict[str, Any], page: PageSource, git: Git,
                revs: list[str | None] | None = None) -> Anchoring | None:
    """First association of a note with source lines, against the text the human read."""
    if page.path is None or chrome_selection(note):
        return None
    anchor = note.get("anchor", {})
    if revs is None:
        revs = []
        for candidate in (anchor.get("sourceCommit"), note.get("recordedAtCommit")):
            if candidate and git.commit_exists(candidate) and candidate not in revs:
                revs.append(candidate)
        revs.append(None)
    exact = anchor.get("exact", "")
    kotlin_page = page.path.endswith(".kt")
    paths = [page.path]
    if page.kind == "generated" and not kotlin_page and exact:
        # The reference pages mirror KDoc; the sentence to change lives in the Kotlin.
        paths = kotlin_candidates(git, normalize_quote(exact)) + [page.path]
    for path in paths:
        is_kotlin = path.endswith(".kt")
        for rev in revs:
            text = git.show(rev, path)
            if text is None:
                continue
            if exact:
                match = locate_quote(project_markdown(text, kotlin=is_kotlin), exact, anchor.get("prefix", ""),
                                     anchor.get("suffix", ""), anchor.get("heading", ""))
                # A word like "Field" occurs everywhere; finding it proves nothing unless the text
                # around it agrees with what the human saw around it.
                if match and match.context is not None and match.context < 0.35 and len(normalize_quote(exact)) < SHORT_QUOTE:
                    match = None
                if match:
                    return Anchoring(path, rev, match.start_line, match.end_line, match.method, match.score)
            elif anchor.get("heading"):
                line = locate_heading(text, anchor["heading"])
                if line:
                    return Anchoring(path, rev, line, line, "heading", 0.8)
    return None


def origins_for(git: Git, rev: str | None, path: str, start: int, end: int) -> list[list[Any]]:
    """Blame origins of lines [start, end] at *rev*, one per committed line."""
    origins: list[list[Any]] = []
    for line in git.blame(rev, path)[start - 1:end]:
        if line.origin:
            origins.append(list(line.origin))
    return origins


@dataclasses.dataclass
class Tracked:
    path: str
    start_line: int
    end_line: int
    status: str
    origins: list[list[Any]]
    survived: int


def track_forward(git: Git, source: dict[str, Any]) -> Tracked | None:
    """Where the anchored lines are in the working tree now, by blame origin."""
    path = source.get("path")
    origins = [tuple(o) for o in source.get("origins", [])]
    if not path:
        return None
    since = source.get("anchoredAt") or ""
    current_path = git.renamed_to(since, path) if since and git.commit_exists(since) else (
        path if (git.root / path).is_file() else None)
    if current_path is None or not origins:
        return None
    wanted = set(origins)
    blame = git.blame(None, current_path)
    hits = [(line.final_line, line.origin) for line in blame if line.origin in wanted]
    if not hits:
        return None
    width = max(1, int(source.get("endLine", 0) or 0) - int(source.get("startLine", 0) or 0) + 1)
    # `blame -M` lets a paragraph keep its identity when it is moved within the file, and the
    # price is that a *copy* of an anchored line can carry the same origin as the original. So
    # the hits are split into runs, and the note stays with the run holding the most of its
    # lines — the nearest to where it was, when two runs tie.
    cluster = _best_cluster(hits, width, int(source.get("startLine", 0) or 0))
    start, end = cluster[0][0], cluster[-1][0]
    survived = len({origin for _, origin in cluster})
    if survived == len(wanted) and end - start + 1 == max(width, survived):
        status = "exact" if current_path == path and start == source.get("startLine") else "moved"
    else:
        status = "edited"
    new_origins = [list(l.origin) for l in blame[start - 1:end] if l.origin]
    return Tracked(current_path, start, end, status, new_origins or [list(o) for o in origins], survived)


def _best_cluster(hits: list[tuple[int, Any]], width: int, previous_start: int) -> list[tuple[int, Any]]:
    gap = max(2, width // 2 + 1)
    clusters: list[list[tuple[int, Any]]] = []
    for hit in sorted(hits):
        if clusters and hit[0] - clusters[-1][-1][0] <= gap:
            clusters[-1].append(hit)
        else:
            clusters.append([hit])
    return max(clusters, key=lambda c: (len({o for _, o in c}), -abs(c[0][0] - previous_start)))


def excerpt(text: str, start: int, end: int, context: int = 0) -> str:
    lines = text.split("\n")
    lo, hi = max(1, start - context), min(len(lines), end + context)
    return "\n".join(lines[lo - 1:hi])


def sync_note(note: dict[str, Any], git: Git, *, page_cache: dict[str, PageSource] | None = None,
              reanchor: bool = False) -> dict[str, Any]:
    """Compute the note's new `source` object. Pure with respect to the store.

    *reanchor* discards the blame origins and associates the note from its quote again, for a
    note that was anchored wrongly in the first place (forward tracking would faithfully carry
    the mistake along).
    """
    page_cache = {} if page_cache is None else page_cache
    url = note.get("pageUrl", "")
    if url not in page_cache:
        page_cache[url] = resolve_page_source(url, git)
    page = page_cache[url]
    previous = {} if reanchor or chrome_selection(note) else dict(note.get("source") or {})
    head = git.head()
    base = {"page": page.relative, "pageKind": page.kind, "trackedAtEpochMs": now_ms(), "trackedHead": head}
    if page.generated:
        base["generated"] = page.generated
    history = list(previous.get("history", []))

    tracked = track_forward(git, previous) if previous.get("origins") else None
    if tracked:
        text = git.show(None, tracked.path) or ""
        status = tracked.status
        # All anchored lines edited away but the quote still reads somewhere: prefer the quote.
        source = {**base, "path": tracked.path, "startLine": tracked.start_line, "endLine": tracked.end_line,
                  "status": status, "origins": tracked.origins, "anchoredAt": head,
                  "firstAnchoredAt": previous.get("firstAnchoredAt") or previous.get("anchoredAt"),
                  "method": previous.get("method", ""), "excerpt": excerpt(text, tracked.start_line, tracked.end_line),
                  "heading": nearest_heading(text, tracked.start_line)}
        if tracked.status == "edited" and previous.get("excerpt"):
            source["previousExcerpt"] = previous["excerpt"]
    else:
        # Lost by blame (every anchored line was edited): re-find the quote in today's text
        # only. Matching it at the old commit again would find the old lines and lose them again.
        lost = bool(previous.get("origins"))
        anchoring = anchor_note(note, page, git, revs=[None] if lost else None)
        if anchoring is None:
            reason = unanchored_reason(note, page)
            source = {**base, "path": page.path, "status": "unanchored" if not previous.get("origins") else "orphaned",
                      "reason": reason}
            if previous.get("origins"):
                source["lastKnown"] = {k: previous.get(k) for k in ("path", "startLine", "endLine", "excerpt")}
        else:
            text = git.show(None, anchoring.path) or ""
            if anchoring.rev is None:
                start, end, origins = anchoring.start_line, anchoring.end_line, origins_for(
                    git, None, anchoring.path, anchoring.start_line, anchoring.end_line)
                status = "anchored" if origins else "uncommitted"
                anchored_at = head
            else:
                origins = origins_for(git, anchoring.rev, anchoring.path, anchoring.start_line, anchoring.end_line)
                start, end, anchored_at = anchoring.start_line, anchoring.end_line, anchoring.rev
                status = "anchored"
                if origins:
                    tracked = track_forward(git, {"path": anchoring.path, "origins": origins, "anchoredAt": anchoring.rev,
                                                  "startLine": start, "endLine": end})
                    if tracked:
                        start, end, origins, status = tracked.start_line, tracked.end_line, tracked.origins, (
                            "anchored" if tracked.status in ("exact", "moved") else "edited")
                        anchoring.path = tracked.path
                        anchored_at = head
                        text = git.show(None, anchoring.path) or ""
                    else:
                        status = "orphaned"
            if lost and status != "orphaned":
                status = "reanchored"
            source = {**base, "path": anchoring.path, "startLine": start, "endLine": end, "status": status,
                      "origins": origins, "anchoredAt": anchored_at, "firstAnchoredAt": anchoring.rev or head,
                      "method": anchoring.method, "score": anchoring.score,
                      "excerpt": excerpt(text, start, end) if status != "orphaned" else "",
                      "heading": nearest_heading(text, start) if status != "orphaned" else ""}
            if lost:
                source["firstAnchoredAt"] = previous.get("firstAnchoredAt") or previous.get("anchoredAt")
                if previous.get("excerpt"):
                    source["previousExcerpt"] = previous["excerpt"]
    if previous.get("path") and (previous.get("startLine"), previous.get("path")) != (source.get("startLine"), source.get("path")):
        history.append({k: previous.get(k) for k in ("path", "startLine", "endLine", "status", "trackedHead")})
    if history:
        source["history"] = history[-HISTORY_LIMIT:]
    return source


def sync_store(store: Store, git: Git, *, ids: Iterable[str] = (), include_resolved: bool = True,
               reanchor: bool = False, log: Callable[[str], None] = lambda _m: None) -> dict[str, dict[str, Any]]:
    """Recompute `source` for notes, outside the lock, then merge under it."""
    notes = store.notes()
    wanted = set(ids)
    targets = [n for n in notes if (not wanted or n["id"] in wanted) and (include_resolved or not is_resolved(n))]
    pages: dict[str, PageSource] = {}
    results: dict[str, dict[str, Any]] = {}
    for note in targets:
        try:
            results[note["id"]] = sync_note(note, git, page_cache=pages, reanchor=reanchor)
        except Exception as error:  # one bad note must not stop the queue
            results[note["id"]] = {**(note.get("source") or {}), "status": "error", "reason": str(error)}
        source = results[note["id"]]
        log(f"{note['id'][:8]} {source.get('status', '?'):11} {source.get('path') or '-'}"
            f"{':' + str(source['startLine']) if source.get('startLine') else ''}")

    def merge(current: list[dict[str, Any]]) -> None:
        for note in current:
            if note.get("id") in results:
                note["source"] = results[note["id"]]

    store.mutate(merge)
    return results


# ─────────────────────────────────────────────────────────────────────────────
# Partitioning
# ─────────────────────────────────────────────────────────────────────────────


@dataclasses.dataclass
class Partition:
    key: str
    pages: list[str]
    files: list[str]
    notes: list[dict[str, Any]]
    generated: list[dict[str, str]]

    @property
    def slug(self) -> str:
        base = re.sub(r"[^A-Za-z0-9]+", "-", self.key).strip("-")[-60:] or "page"
        return f"{base}-{hashlib.sha1(self.key.encode()).hexdigest()[:6]}"


def owned_files(note: dict[str, Any]) -> list[str]:
    source = note.get("source") or {}
    # For a generated page whose quote was found in KDoc this is the Kotlin file; when it was not
    # found it is the generated page itself, which the agent is told not to edit — the partition
    # still owns it, so no other agent is sent at the same page.
    return [source["path"]] if source.get("path") else []


def partition_notes(notes: list[dict[str, Any]]) -> list[Partition]:
    """One partition per page, merged where two pages would edit the same file.

    Two reference pages built from the same Kotlin file, or a page whose note anchored into a KDoc
    another page also owns, would otherwise send two agents at one file. Union-find over (page,
    file) makes each file belong to exactly one agent. Largest partitions come first so a parallel
    pool finishes as early as it can (longest-processing-time scheduling).
    """
    parent: dict[str, str] = {}

    def find(key: str) -> str:
        parent.setdefault(key, key)
        while parent[key] != key:
            parent[key] = parent[parent[key]]
            key = parent[key]
        return key

    def union(a: str, b: str) -> None:
        parent[find(a)] = find(b)

    for note in notes:
        page_key = "page:" + ((note.get("source") or {}).get("page") or note.get("pageUrl", ""))
        find(page_key)
        for file in owned_files(note):
            union(page_key, "file:" + file)

    groups: dict[str, list[dict[str, Any]]] = {}
    for note in notes:
        page_key = "page:" + ((note.get("source") or {}).get("page") or note.get("pageUrl", ""))
        groups.setdefault(find(page_key), []).append(note)

    partitions: list[Partition] = []
    for members in groups.values():
        pages = sorted({(n.get("source") or {}).get("page") or n.get("pageUrl", "") for n in members})
        files = sorted({f for n in members for f in owned_files(n)})
        generated = [dict(g) for g in {json.dumps((n.get("source") or {}).get("generated"), sort_keys=True)
                                       for n in members if (n.get("source") or {}).get("generated")}
                     for g in [json.loads(g)]]
        key = files[0] if files else pages[0]
        partitions.append(Partition(key, pages, files, sorted(members, key=_note_order), generated))
    partitions.sort(key=lambda p: (-len(p.notes), p.key))
    return partitions


def _note_order(note: dict[str, Any]) -> tuple[str, int, int]:
    source = note.get("source") or {}
    return (source.get("path") or "", int(source.get("startLine") or 0), int(note.get("createdAtEpochMs") or 0))


# ─────────────────────────────────────────────────────────────────────────────
# Briefs
# ─────────────────────────────────────────────────────────────────────────────


def fmt_time(ms: int | None) -> str:
    if not ms:
        return "?"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ms / 1000))


def page_memories(all_notes: list[dict[str, Any]], pages: Iterable[str], files: Iterable[str]) -> list[str]:
    """Memories anyone left on these pages or files, newest last, whatever state their note is in."""
    pages, files = set(pages), set(files)
    lines = []
    for note in sorted(all_notes, key=lambda n: n.get("createdAtEpochMs", 0)):
        source = note.get("source") or {}
        if source.get("page") not in pages and source.get("path") not in files:
            continue
        where = f"{source['path']}:{source['startLine']}" if source.get("startLine") else (source.get("page") or "")
        if note.get("kind") == "memory":
            lines.append(f"- [{fmt_time(note.get('createdAtEpochMs'))}] ({where}) {note.get('note', '').strip()}")
        for entry in note.get("thread", []):
            if entry.get("kind") == "memory":
                lines.append(f"- [{fmt_time(entry.get('atEpochMs'))}] on note {note['id'][:8]} ({where}), "
                             f"{entry.get('author', '?')}: {entry.get('text', '').strip()}")
    return lines


def render_note(note: dict[str, Any], git: Git | None, *, context: int = 3) -> str:
    source = note.get("source") or {}
    anchor = note.get("anchor") or {}
    out = [f"### note {note['id']}  [{note_state(note)}]  " + " ".join("@" + t for t in note.get("tags", []))]
    if source.get("startLine"):
        out.append(f"Location: {source['path']}:{source['startLine']}-{source['endLine']}  "
                   f"(anchor {source.get('status')}, via {source.get('method') or 'blame'})")
    else:
        out.append(f"Location: {source.get('path') or source.get('page') or note.get('pageUrl')}  "
                   f"(anchor {source.get('status', 'unsynced')}: {source.get('reason', '')})")
        if source.get("lastKnown"):
            last = source["lastKnown"]
            out.append(f"Last known: {last.get('path')}:{last.get('startLine')}-{last.get('endLine')}")
    if source.get("generated"):
        gen = source["generated"]
        out.append(f"Generated page: by {gen.get('generator')} from {gen.get('from') or 'source'}; "
                   f"edit the source, not the page (regenerate: {gen.get('regenerate') or 'see header'}).")
    if source.get("heading"):
        out.append(f"Section: {source['heading']}")
    out.append("Note:\n" + indent(note.get("note", "").strip()))
    if anchor.get("exact"):
        out.append("Quote (as rendered):\n" + indent(anchor["exact"].strip()))
    if git and source.get("startLine"):
        text = git.show(None, source["path"]) or ""
        lines = text.split("\n")
        lo, hi = max(1, source["startLine"] - context), min(len(lines), source["endLine"] + context)
        numbered = "\n".join(f"{n:5}{'>' if source['startLine'] <= n <= source['endLine'] else ' '} {lines[n - 1]}"
                             for n in range(lo, hi + 1))
        out.append(f"Current text ({source['path']}):\n{numbered}")
    if source.get("previousExcerpt"):
        out.append("Before its last edit these lines read:\n" + indent(source["previousExcerpt"]))
    thread = [e for e in note.get("thread", []) if e.get("kind") != "run"]
    if thread:
        out.append("Thread:")
        for entry in thread:
            out.append(f"  - {fmt_time(entry.get('atEpochMs'))} {entry.get('kind')} by {entry.get('author')}: "
                       f"{entry.get('text', '').strip()}")
    return "\n".join(out)


def indent(text: str, prefix: str = "    ") -> str:
    return "\n".join(prefix + line for line in text.split("\n"))


def render_brief(partition: Partition, all_notes: list[dict[str, Any]], git: Git | None) -> str:
    out = [f"# Pages: {', '.join(partition.pages)}"]
    if partition.files:
        out.append(f"Files this partition owns: {', '.join(partition.files)}")
    for gen in partition.generated:
        out.append(f"Generated: {gen.get('generator')} from {gen.get('from')}; regenerate with "
                   f"`{gen.get('regenerate')}`. Edit the KDoc/Kotlin it comes from, never the generated Markdown.")
    out.append(f"\n## Open notes ({len(partition.notes)})\n")
    out.extend(render_note(n, git) + "\n" for n in partition.notes)
    memories = page_memories(all_notes, partition.pages, partition.files)
    out.append("## Memories left on these pages\n")
    out.append("\n".join(memories) if memories else "(none yet)")
    handoffs = [n for n in all_notes if n.get("kind") == "handoff" and _handoff_touches(n, partition.pages)]
    if handoffs:
        out.append("\n## Handoffs already filed from these pages (do not file them again)\n")
        for item in handoffs:
            info = item.get("handoff") or {}
            out.append(f"- [{info.get('state', 'open')}{', blocking' if info.get('blocking') else ''}] "
                       f"{info.get('scope', 'project')}: {item.get('note', '').strip()} ({info.get('path', '')})"
                       + (f"\n    → {item.get('resolution', '').strip()[:240]}" if item.get("resolution") else ""))
    recent = [n for n in all_notes if is_resolved(n) and n.get("kind") not in ("memory", "handoff")
              and ((n.get("source") or {}).get("page") in partition.pages
                   or (n.get("source") or {}).get("path") in partition.files)]
    recent.sort(key=lambda n: n.get("resolvedAtEpochMs") or 0)
    if recent:
        out.append("\n## Recently resolved here (for context; do not redo)\n")
        for note in recent[-8:]:
            out.append(f"- {fmt_time(note.get('resolvedAtEpochMs'))} {note['id'][:8]}: {note.get('note', '').strip()[:160]}"
                       f"\n    → {note.get('resolution', '').strip()[:240]}")
    return "\n".join(out)


# ─────────────────────────────────────────────────────────────────────────────
# Recording outcomes: straight to the store, or to a run journal
# ─────────────────────────────────────────────────────────────────────────────


def current_author() -> str:
    return os.environ.get(AUTHOR_ENV, "").strip() or "human"


def record(store: Store, op: dict[str, Any]) -> dict[str, Any]:
    """Apply one outcome, or journal it when running inside a harness partition."""
    journal = os.environ.get(JOURNAL_ENV, "").strip()
    op = {**op, "author": op.get("author") or current_author(), "atEpochMs": now_ms()}
    if journal:
        allowed = {i for i in os.environ.get(PARTITION_ENV, "").split(",") if i}
        if op.get("id") and allowed and not any(op["id"] == a or a.startswith(op["id"]) for a in allowed):
            raise SystemExit(f"note {op['id']} is not in this partition; record outcomes only for the notes you were given")
        if op.get("id") and allowed:
            op["id"] = next(a for a in allowed if a == op["id"] or a.startswith(op["id"]))
        if allowed and op.get("notes"):
            expanded = []
            for wanted in op["notes"]:
                match = [a for a in allowed if a == wanted or a.startswith(wanted)]
                if len(match) != 1:
                    raise SystemExit(f"note {wanted} is not in this partition")
                expanded.append(match[0])
            op["notes"] = expanded
        with open(journal, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(op, ensure_ascii=False) + "\n")
        return {"journaled": journal, **op}
    return store.mutate(lambda notes: apply_op(notes, op))


def apply_op(notes: list[dict[str, Any]], op: dict[str, Any]) -> dict[str, Any]:
    action, author, text = op["op"], op.get("author", "human"), (op.get("text") or "").strip()
    extra = {k: op[k] for k in ("runId", "changedFiles", "commit") if op.get(k)}
    if action == "page-memory":
        created = now_ms()
        note = normalize_note({"id": str(uuid.uuid4()), "pageUrl": op["pageUrl"], "note": text, "kind": "memory",
                               "tags": op.get("tags", []), "anchor": op.get("anchor", {}),
                               "createdAtEpochMs": created, "updatedAtEpochMs": created,
                               "recordedInCheckout": str(REPO_ROOT)})
        if op.get("source"):
            note["source"] = op["source"]
        add_entry(note, "memory", text, author, **extra)
        notes.append(note)
        return note
    if action == "handoff":
        return _apply_handoff(notes, op, author, extra)
    if action == "handoff-close":
        return _close_handoff(notes, op, author, text)
    note = find_note(notes, op["id"])
    if action == "resolve":
        note["resolvedAtEpochMs"] = op.get("atEpochMs", now_ms())
        note["resolution"] = text
        add_entry(note, "resolution", text, author, **extra)
        if op.get("memory"):
            add_entry(note, "memory", op["memory"].strip(), author, **extra)
    elif action == "reopen":
        note["resolvedAtEpochMs"] = None
        note["resolution"] = ""
        add_entry(note, "reopen", text, author, **extra)
    elif action in ("memory", "question", "reply", "comment", "run"):
        add_entry(note, action, text, author, **extra)
    else:
        raise ValueError(f"unknown op {action!r}")
    return note


def _page_key(note: dict[str, Any]) -> str:
    return (note.get("source") or {}).get("page") or note.get("pageUrl", "")


def _handoff_touches(handoff: dict[str, Any], pages: Iterable[str]) -> bool:
    info = handoff.get("handoff") or {}
    wanted = set(pages)
    return bool(wanted & ({info.get("page", "")} | set(info.get("pages", [])) | {handoff.get("pageUrl", "")}))


def _apply_handoff(notes: list[dict[str, Any]], op: dict[str, Any], author: str, extra: dict[str, Any]) -> dict[str, Any]:
    """File a handoff: its own `kind: handoff` note, and a pointer and a `blocked` entry on each
    note it came from — on every open note of its page, when blocking."""
    created = now_ms()
    page = op.get("page") or ""
    handoff = normalize_note({
        "id": op.get("handoffId") or str(uuid.uuid4()), "pageUrl": op["pageUrl"], "note": op["title"], "kind": "handoff",
        "createdAtEpochMs": created, "updatedAtEpochMs": created, "recordedInCheckout": str(REPO_ROOT),
        "handoff": {"path": op["path"], "scope": op.get("scope", "project"), "blocking": bool(op.get("blocking")),
                    "state": "open", "page": page, "pages": op.get("pages", []), "fromNotes": op.get("notes", []),
                    **({"runId": op["runId"]} if op.get("runId") else {})},
    })
    add_entry(handoff, "handoff", f"filed: {op['path']}", author, handoff=handoff["id"], **extra)
    notes.append(handoff)
    for note_id in op.get("notes", []):
        add_entry(find_note(notes, note_id), "handoff", f"{op['title']} ({op['path']})", author, handoff=handoff["id"], **extra)
    # The notes a handoff came from wait on it either way, and return to the queue when it closes
    # so the page can follow whatever was done. A blocking handoff holds the rest of its page too.
    for note in notes:
        if note.get("kind") != "note" or note_state(note) not in ("open", "question"):
            continue
        on_page = bool(op.get("blocking")) and ((page and _page_key(note) == page) or note.get("pageUrl") == op["pageUrl"])
        if note["id"] in op.get("notes", []) or on_page:
            add_entry(note, "blocked", f"waiting on handoff: {op['title']}", author, handoff=handoff["id"], **extra)
    return handoff


def _close_handoff(notes: list[dict[str, Any]], op: dict[str, Any], author: str, text: str) -> dict[str, Any]:
    handoff = find_note(notes, op["id"])
    if handoff.get("kind") != "handoff":
        raise SystemExit(f"{handoff['id']} is a {handoff.get('kind')}, not a handoff")
    handoff["handoff"] = {**(handoff.get("handoff") or {}), "state": op.get("state", "done")}
    if op.get("state", "done") == "done":
        handoff["resolvedAtEpochMs"] = now_ms()
        handoff["resolution"] = text
    add_entry(handoff, "resolution" if op.get("state", "done") == "done" else "comment", text or op.get("state", ""), author)
    if op.get("state", "done") in ("done", "dropped"):
        for note in notes:
            if blocked_by(note) == handoff["id"]:
                add_entry(note, "unblocked", f"handoff closed: {text}".strip(), author, handoff=handoff["id"])
    return handoff


def release_stale_blocks(notes: list[dict[str, Any]]) -> int:
    """Unblock notes whose handoff was deleted, resolved from the panel, or closed by hand.

    `handoff-close` releases its notes itself; this catches every other way a handoff can stop
    being open, so no note waits on something that no longer exists.
    """
    open_ids = {n["id"] for n in notes if n.get("kind") == "handoff" and not is_resolved(n)
                and (n.get("handoff") or {}).get("state", "open") in ("open", "taken")}
    released = 0
    for note in notes:
        handoff = blocked_by(note)
        if handoff and handoff not in open_ids:
            add_entry(note, "unblocked", "the handoff it waited on is no longer open", "harness", handoff=handoff)
            released += 1
    return released


def handoff_slug(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:60] or "handoff"


def write_handoff_document(root: Path, *, handoff_id: str, title: str, body: str, scope: str, blocking: bool,
                           page_url: str, pages: list[str], notes: list[dict[str, Any]], author: str) -> str:
    """The document the interactive pass starts from, in the checkout so it can be read, edited and committed."""
    directory = root / HANDOFF_DIR
    directory.mkdir(parents=True, exist_ok=True)
    stem = f"{time.strftime('%Y-%m-%d')}-{handoff_slug(title)}"
    path = directory / f"{stem}.md"
    counter = 2
    while path.exists():
        path = directory / f"{stem}-{counter}.md"
        counter += 1
    rel = path.relative_to(root).as_posix()
    lines = [f"# {title}", "",
             f"- **Handoff:** `{handoff_id}` ({scope}{', blocking: notes on the page wait for this' if blocking else ''})",
             f"- **Filed from:** {page_url}" + (f"; also affects {', '.join(pages)}" if pages else ""),
             f"- **Filed by:** {author}, {time.strftime('%Y-%m-%d %H:%M')}",
             f"- **Close with:** `python tools/docs_notes.py handoff-close {handoff_id[:8]} \"what happened\"`"]
    if notes:
        lines += ["", "## The notes that led here", ""]
        for note in notes:
            source = note.get("source") or {}
            where = f"{source.get('path')}:{source.get('startLine')}" if source.get("startLine") else (source.get("page") or note.get("pageUrl"))
            lines.append(f"- `{note['id'][:8]}` ({where}): {note.get('note', '').strip()}")
            if (note.get("anchor") or {}).get("exact"):
                lines.append(f"  > {note['anchor']['exact'].strip()}")
    # The header above carries the title; an agent's own `# Title` would make a second one.
    text = body.strip()
    if text.startswith("# "):
        text = text.split("\n", 1)[1].strip() if "\n" in text else ""
    lines += ["", text, ""]
    path.write_text("\n".join(lines), encoding="utf-8")
    return rel


def read_journal(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    ops = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            with contextlib.suppress(json.JSONDecodeError):
                ops.append(json.loads(line))
    return ops


# ─────────────────────────────────────────────────────────────────────────────
# The harness
# ─────────────────────────────────────────────────────────────────────────────


AGENT_PROMPT = """You are revising one part of the Field·4 documentation, in the checkout at {root}.
Several agents are working in this checkout at once, one per page. You own exactly these files:

{owned}

Edit nothing else. Do not commit, stash, reset, or run the docs build or Gradle: the harness that
started you commits and verifies after every agent has finished. Read {root}/AGENTS.md (section 8,
"Docs authoring") before writing prose.

The brief below lists the human's open notes on your page(s), each with its current location in
the source (line numbers are live — they were computed from `git blame` against the working tree
just before you started) and the memories earlier agents left here.

How to work:
- Make targeted, source-grounded edits that address each note. Check claims against the code
  (`src/`) rather than trusting the prose. Change as little as the note needs.
- Text inside a code fence is executed and captured by the docs runner; edit a fence only if the
  note is about that code, and keep it runnable.
- A generated page ("DO NOT EDIT") is fixed in the Kotlin/KDoc it is generated from.

Record an outcome for EVERY note, with this command (it writes to your run journal, not the store):

  {cli} resolve <note-id> "what you changed, in one or two sentences" [--memory "..."]
  {cli} question <note-id> "the specific thing you need the human to decide"
  {cli} memory <note-id> "something the next agent on this page should know"
  {cli} memory --page {page_url} "a page-wide fact worth remembering" [--line N]

Some notes ask for more than an edit to your files. File a HANDOFF for those instead of doing
them — a later, interactive pass with a human works through handoffs one at a time:
- scope "project": a significant code change, a new feature or tool, a bug in Field itself, a
  capture that needs the runtime fixed — anything that is real engineering rather than prose;
- scope "crosscutting": anything that reaches past your files — merge, split, move or rename
  pages, reorganise the navigation, a terminology or structure change across several pages,
  a concern you see recurring on other pages.
Write the handoff document with your Write tool under {draft_dir}/ (no title line; the
command adds one). Say what was asked and why it is bigger than a docs edit; give the evidence,
with file:line references; propose an approach and its alternatives; list the questions a human
has to answer; and say what the page needs once it is done. Then file it:

  {cli} handoff "short imperative title" --scope project|crosscutting --body-file {draft_dir}/<name>.md \\
      --note <note-id> [--note <note-id> ...] [--page <other affected page> ...] [--blocking]

Use --blocking when the rest of this page should not be revised until the handoff is done
(for example: the page is about to be merged away, or its subject is about to change). After
filing a blocking handoff, STOP: record nothing more and end your turn; the harness marks the
page's remaining notes as waiting. A non-blocking handoff is filed and you carry on with the
other notes. A note you handed off needs no other outcome: it waits on the handoff, and comes
back to this page's queue when the handoff is closed.

Resolve only what you actually fixed. Ask a question rather than guessing when a note is
ambiguous or asks for a decision that is the human's. Leave a memory when you learned something
non-obvious about this page — a constraint, a trap, why a sentence is the way it is — that would
save the next agent time; do not leave memories that merely restate your change.

{brief}
"""


@dataclasses.dataclass
class AgentSpec:
    name: str
    argv: Callable[[str, Path, "Partition", str], list[str]]


def find_executable(name: str) -> str:
    """*name* on PATH, or in the usual install places: a Field started from the Dock has a PATH
    without ~/.local/bin (claude) or /opt/homebrew/bin (codex, uv)."""
    for candidate in (shutil.which(name), str(Path.home() / ".local" / "bin" / name), f"/opt/homebrew/bin/{name}",
                      f"/usr/local/bin/{name}"):
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    return name


def _claude_argv(prompt: str, root: Path, partition: Partition, cli: str) -> list[str]:
    allowed = ["Read", "Edit", "Write", "Glob", "Grep", f"Bash({cli}:*)", "Bash(git diff:*)", "Bash(git log:*)",
               "Bash(git blame:*)", "Bash(git show:*)", "Bash(git grep:*)", "Bash(rg:*)", "Bash(grep:*)"]
    return [find_executable("claude"), "-p", prompt, "--permission-mode", "acceptEdits", "--allowedTools", ",".join(allowed)]


def _codex_argv(prompt: str, root: Path, partition: Partition, cli: str) -> list[str]:
    return [find_executable("codex"), "exec", "--sandbox", "workspace-write", prompt]


AGENTS = {"claude": AgentSpec("claude", _claude_argv), "codex": AgentSpec("codex", _codex_argv)}


def agent_from_template(template: str) -> AgentSpec:
    """`--agent-command 'mytool --prompt-file {prompt_file}'` — {prompt}, {prompt_file}, {root}."""
    def argv(prompt: str, root: Path, partition: Partition, cli: str) -> list[str]:
        prompt_file = root / ".field-backdoor" / "docs-notes" / "prompts" / f"{partition.slug}.md"
        prompt_file.parent.mkdir(parents=True, exist_ok=True)
        prompt_file.write_text(prompt, encoding="utf-8")
        return [part.format(prompt=prompt, prompt_file=str(prompt_file), root=str(root)) for part in shlex.split(template)]
    return AgentSpec("custom", argv)


@dataclasses.dataclass
class RunResult:
    partition: Partition
    exit_code: int
    changed: list[str]
    ops: list[dict[str, Any]]
    rejected: list[str]
    log_path: Path
    seconds: float
    interrupted: bool = False


class RunControl:
    """Stopping a run, two ways.

    *Stop after current pages*: a `STOP` file in the checkout's `.field-backdoor/docs-notes/`
    (written by `stop`, or by the /docs-pass.html page); no new page is started, and pages already
    running finish normally. *Stop now*: `kill()` (SIGTERM or SIGINT to the harness, or
    `stop --now`) terminates every running agent's whole process group. Either way the harness
    then applies whatever each agent had already journaled, so no recorded outcome is lost, and
    notes nobody got to stay open.
    """

    def __init__(self, stop_file: Path):
        self.stop_file = stop_file
        self.killed = False
        self._processes: set[subprocess.Popen] = set()
        self._lock = threading.Lock()

    def stop_requested(self) -> bool:
        return self.killed or self.stop_file.exists()

    def register(self, process: subprocess.Popen) -> None:
        with self._lock:
            self._processes.add(process)
            if self.killed:
                _terminate_group(process)

    def unregister(self, process: subprocess.Popen) -> None:
        with self._lock:
            self._processes.discard(process)

    def kill(self) -> None:
        with self._lock:
            self.killed = True
            for process in list(self._processes):
                _terminate_group(process)


def _terminate_group(process: subprocess.Popen) -> None:
    with contextlib.suppress(ProcessLookupError, PermissionError, OSError):
        os.killpg(process.pid, signal.SIGTERM)


def notes_state_dir(root: Path) -> Path:
    return root / ".field-backdoor" / "docs-notes"


class Progress:
    """`progress.json` in the run directory, rewritten on every change, for anything watching."""

    def __init__(self, run_dir: Path, run_id: str, agent: str, jobs: int, partitions: list["Partition"]):
        self.path = run_dir / "progress.json"
        self._lock = threading.Lock()
        self.data: dict[str, Any] = {
            "runId": run_id, "pid": os.getpid(), "agent": agent, "jobs": jobs, "state": "running",
            "startedAtEpochMs": now_ms(), "partitions": [
                {"key": p.key, "slug": p.slug, "pages": p.pages, "notes": len(p.notes), "state": "pending"}
                for p in partitions]}
        self._write()

    def update(self, slug: str | None = None, **fields: Any) -> None:
        with self._lock:
            if slug is None:
                self.data.update(fields)
            else:
                for item in self.data["partitions"]:
                    if item["slug"] == slug:
                        item.update(fields)
            self.data["updatedAtEpochMs"] = now_ms()
            self._write()

    def _write(self) -> None:
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.data, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(temp, self.path)


def file_digest(path: Path) -> str | None:
    return hashlib.sha1(path.read_bytes()).hexdigest() if path.is_file() else None


def run_partition(partition: Partition, *, agent: AgentSpec, run_dir: Path, store: Store, all_notes: list[dict[str, Any]],
                  git: Git, timeout: int, cli: str, control: RunControl | None = None) -> RunResult:
    brief = render_brief(partition, all_notes, git)
    owned = "\n".join(f"  - {f}" for f in partition.files) or "  - (none located: find the source for the pages below; edit only that)"
    draft_dir = run_dir / f"{partition.slug}.handoffs"
    prompt = AGENT_PROMPT.format(root=git.root, owned=owned, cli=cli, brief=brief, draft_dir=draft_dir,
                                 page_url=partition.notes[0].get("pageUrl", ""))
    journal = run_dir / f"{partition.slug}.journal.jsonl"
    log_path = run_dir / f"{partition.slug}.log"
    (run_dir / f"{partition.slug}.prompt.md").write_text(prompt, encoding="utf-8")
    before = {f: file_digest(git.root / f) for f in partition.files}
    env = {**os.environ, JOURNAL_ENV: str(journal), PARTITION_ENV: ",".join(n["id"] for n in partition.notes),
           AUTHOR_ENV: f"agent:{agent.name}", STORE_ENV: str(store.path),
           PAGE_URL_ENV: partition.notes[0].get("pageUrl", ""), RUN_DIR_ENV: str(run_dir),
           "DOCS_NOTES_PAGE": partition.pages[0]}
    started = time.monotonic()
    exit_code = -1
    with open(log_path, "w", encoding="utf-8") as log:
        log.write(f"$ {agent.name} (partition {partition.key}, {len(partition.notes)} notes)\n")
        log.flush()
        # Its own session, so stopping kills the agent's whole process tree, not just its launcher.
        try:
            process = subprocess.Popen(agent.argv(prompt, git.root, partition, cli), cwd=git.root, env=env,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        except FileNotFoundError as error:
            log.write(f"\n[docs_notes] could not start the agent: {error}\n")
            process = None
            exit_code = 127
        if process is not None:
            if control:
                control.register(process)
            try:
                exit_code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                _terminate_group(process)
                process.wait()
                log.write(f"\n[docs_notes] timed out after {timeout}s\n")
                exit_code = 124
            finally:
                if control:
                    control.unregister(process)
            if control and control.killed:
                log.write("\n[docs_notes] stopped by request\n")
    changed = [f for f in partition.files if file_digest(git.root / f) != before[f]]
    ids = {n["id"] for n in partition.notes}
    ops, rejected = [], []
    for op in read_journal(journal):
        if op.get("op") == "handoff" and set(op.get("notes", [])) <= ids:
            ops.append(op)
            if op.get("path") and op["path"] not in changed:
                changed.append(op["path"])
        elif op.get("op") == "page-memory" or op.get("id") in ids:
            ops.append(op)
        else:
            rejected.append(f"{op.get('op')} on {op.get('id')} (not in this partition)")
    return RunResult(partition, exit_code, changed, ops, rejected, log_path, time.monotonic() - started,
                     interrupted=bool(control and control.killed))


def apply_run(store: Store, result: RunResult, run_id: str, agent_name: str) -> None:
    touched = {op.get("id") for op in result.ops if op.get("id")}
    touched |= {i for op in result.ops if op.get("op") == "handoff" for i in op.get("notes", [])}
    blocking = any(op.get("op") == "handoff" and op.get("blocking") for op in result.ops)

    def change(notes: list[dict[str, Any]]) -> None:
        for op in result.ops:
            apply_op(notes, {**op, "runId": run_id, "changedFiles": result.changed if op.get("op") == "resolve" else None})
        for note in result.partition.notes:
            if note["id"] not in touched and not blocking:
                why = "was stopped before recording an outcome" if result.interrupted else \
                    f"left no outcome (exit {result.exit_code})"
                apply_op(notes, {"op": "run", "id": note["id"], "author": "harness", "runId": run_id,
                                 "text": f"{agent_name} {why}; still open",
                                 "changedFiles": result.changed})

    store.mutate(change)


def commit_partition(git: Git, result: RunResult, run_id: str) -> str | None:
    if not result.changed:
        return None
    resolved = [op["id"] for op in result.ops if op.get("op") == "resolve"]
    title = f"docs: revise {', '.join(result.partition.pages)[:60]} from {len(resolved)} note(s)"
    body = "\n".join(f"- {op['id'][:8]}: {op.get('text', '').strip()}" for op in result.ops if op.get("op") == "resolve")
    handoffs = [op for op in result.ops if op.get("op") == "handoff"]
    body += "".join(f"\n- handoff ({op.get('scope')}{', blocking' if op.get('blocking') else ''}): {op.get('title')} "
                    f"→ {op.get('path')}" for op in handoffs)
    trailers = "\n".join([f"Docs-Note: {i}" for i in resolved] + [f"Docs-Handoff: {op['handoffId']}" for op in handoffs]) \
        + f"\nDocs-Notes-Run: {run_id}"
    git.run("add", "--", *result.changed)
    git.run("commit", "-m", f"{title}\n\n{body}\n\n{trailers}".replace("\n\n\n", "\n\n"))
    return git.head()


def revise(args: argparse.Namespace, store: Store, git: Git, control: RunControl | None = None) -> int:
    log = lambda message: print(message, flush=True)  # noqa: E731 — a watcher reads this live
    if not args.no_sync:
        log("sync: associating notes with source …")
        sync_store(store, git, log=(lambda m: log("  " + m)) if args.verbose else (lambda _m: None))
    released = store.mutate(release_stale_blocks)
    if released:
        log(f"released {released} note(s) whose handoff is no longer open")
    notes = store.notes()
    # A page with an open blocking handoff waits, including notes written after it was filed.
    held = [n for n in notes if n.get("kind") == "handoff" and (n.get("handoff") or {}).get("blocking")
            and not is_resolved(n) and (n.get("handoff") or {}).get("state", "open") in ("open", "taken")]
    held_pages = {(h.get("handoff") or {}).get("page") or h.get("pageUrl", "") for h in held} | {h.get("pageUrl", "") for h in held}
    queue = [n for n in notes if in_queue(n, args.tag) and _matches_page(n, args.page)]
    waiting = [n for n in queue if _page_key(n) in held_pages or n.get("pageUrl") in held_pages]
    queue = [n for n in queue if n not in waiting]
    if waiting:
        log(f"{len(waiting)} note(s) wait on blocking handoffs ({len(held)} open): "
            + ", ".join(sorted({_page_key(n) for n in waiting})))
    partitions = partition_notes(queue)
    if args.max_pages:
        partitions = partitions[:args.max_pages]
    if not partitions:
        log("Nothing in the queue matches.")
        return 0
    log(f"{sum(len(p.notes) for p in partitions)} note(s) in {len(partitions)} partition(s), {args.jobs} at a time:")
    for partition in partitions:
        log(f"  {len(partition.notes):3} × {partition.key}" + (f"  (+{len(partition.pages) - 1} page)" if len(partition.pages) > 1 else ""))
    if args.dry_run:
        return 0
    if args.commit and git.staged_paths():
        raise SystemExit("--commit needs an empty index; something is already staged.")

    agent = agent_from_template(args.agent_command) if args.agent_command else AGENTS[args.agent]
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    run_dir = git.root / ".field-backdoor" / "docs-notes" / "runs" / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    # --repo explicitly: an agent's own commands must resolve pages and write handoffs in the
    # checkout being revised, whatever checkout this file happens to live in.
    cli = f"{shlex.quote(sys.executable)} {shlex.quote(str(Path(__file__).resolve()))} --repo {shlex.quote(str(git.root))}"
    dirty_before = git.status_paths()
    owned_all = {f for p in partitions for f in p.files}
    busy = sorted(f for f in owned_all if f in dirty_before)
    if busy and not args.allow_dirty:
        raise SystemExit("These files already have uncommitted changes; commit them or pass --allow-dirty:\n  " + "\n  ".join(busy))
    log(f"run {run_id}: logs in {run_dir.relative_to(git.root)}")

    state_dir = notes_state_dir(git.root)
    control = control or RunControl(state_dir / "STOP")
    with contextlib.suppress(FileNotFoundError):
        control.stop_file.unlink()  # a STOP left over from an earlier run is not a request to stop this one
    (state_dir / "latest-run").write_text(run_id, encoding="utf-8")
    pid_file = state_dir / "revise.pid"
    pid_file.write_text(str(os.getpid()), encoding="utf-8")
    progress = Progress(run_dir, run_id, agent.name, args.jobs, partitions)
    previous_handlers = _install_stop_signals(control)

    results: list[RunResult] = []
    pending = list(partitions)
    try:
        with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
            running: dict[Any, Partition] = {}
            while pending or running:
                while pending and len(running) < max(1, args.jobs) and not control.stop_requested():
                    partition = pending.pop(0)
                    progress.update(partition.slug, state="running", startedAtEpochMs=now_ms())
                    running[pool.submit(run_partition, partition, agent=agent, run_dir=run_dir, store=store,
                                        all_notes=notes, git=git, timeout=args.timeout, cli=cli, control=control)] = partition
                if control.stop_requested() and progress.data["state"] == "running":
                    progress.update(state="stopping")
                    log(f"stop requested: {len(running)} page(s) finishing, {len(pending)} not started")
                if not running:
                    break
                done, _ = wait(list(running), timeout=1.0, return_when=FIRST_COMPLETED)
                for future in done:
                    partition = running.pop(future)
                    result = future.result()
                    apply_run(store, result, run_id, agent.name)
                    results.append(result)
                    resolved = sum(1 for op in result.ops if op.get("op") == "resolve")
                    asked = sum(1 for op in result.ops if op.get("op") == "question")
                    handed = sum(1 for op in result.ops if op.get("op") == "handoff")
                    progress.update(partition.slug, state="interrupted" if result.interrupted else "done",
                                    exit=result.exit_code, resolved=resolved, questions=asked, handoffs=handed,
                                    changed=result.changed, seconds=round(result.seconds), log=str(result.log_path))
                    log(f"  done {result.partition.key}: exit {result.exit_code}, {resolved}/{len(result.partition.notes)} resolved, "
                        f"{asked} question(s), {handed} handoff(s), {len(result.changed)} file(s) changed, {result.seconds:.0f}s"
                        + (" (stopped)" if result.interrupted else ""))
                    for problem in result.rejected:
                        log(f"    rejected journal entry: {problem}")
                    for op in result.ops:
                        if op.get("op") == "handoff":
                            log(f"    handoff ({op.get('scope')}{', blocking' if op.get('blocking') else ''}): {op.get('title')} → {op.get('path')}")
    finally:
        _restore_stop_signals(previous_handlers)
        with contextlib.suppress(FileNotFoundError):
            pid_file.unlink()
    for partition in pending:
        progress.update(partition.slug, state="not started")
    if pending:
        log(f"{len(pending)} page(s) not started; their notes stay in the queue")

    git.forget()
    dirty_after = git.status_paths()
    stray = sorted(p for p in dirty_after if p not in dirty_before and p not in owned_all
                   and not p.startswith(".field-backdoor/") and not p.startswith(HANDOFF_DIR + "/"))
    if stray:
        log("Changes outside every partition's files (not committed, review by hand):\n  " + "\n  ".join(stray))

    if args.commit:
        for result in sorted(results, key=lambda r: r.partition.key):
            sha = commit_partition(git, result, run_id)
            if sha:
                resolved_ids = [op["id"] for op in result.ops if op.get("op") == "resolve"]

                def stamp(current: list[dict[str, Any]], sha: str = sha, ids: list[str] = resolved_ids) -> None:
                    for note in current:
                        if note["id"] in ids:
                            for entry in reversed(note.get("thread", [])):
                                if entry.get("kind") == "resolution" and entry.get("runId") == run_id:
                                    entry["commit"] = sha
                                    break

                store.mutate(stamp)
                log(f"  committed {sha[:10]} {result.partition.key}")
    # Re-anchor now that the text has moved, so the next run (and the panel) sees current lines.
    git.forget()
    sync_store(store, git, ids=[n["id"] for p in partitions for n in p.notes])
    generated = {g.get("regenerate") for r in results if r.changed for g in r.partition.generated if g.get("regenerate")}
    if generated:
        log("Generated pages had their sources edited; regenerate with:\n  " + "\n  ".join(sorted(generated)))
    summary = {"runId": run_id, "partitions": [
        {"key": r.partition.key, "exit": r.exit_code, "changed": r.changed, "log": str(r.log_path),
         "ops": [{k: op.get(k) for k in ("op", "id", "text")} for op in r.ops]} for r in results]}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    progress.update(state="killed" if control.killed else "stopped" if pending else "finished", finishedAtEpochMs=now_ms())
    with contextlib.suppress(FileNotFoundError):
        control.stop_file.unlink()
    if control.killed or pending:
        return 3
    return 0 if all(r.exit_code == 0 for r in results) else 1


def _install_stop_signals(control: RunControl) -> dict[int, Any]:
    """SIGTERM/SIGINT mean *stop now*; only possible from the main thread, which is where the CLI runs."""
    if threading.current_thread() is not threading.main_thread():
        return {}
    previous = {}
    for number in (signal.SIGTERM, signal.SIGINT):
        previous[number] = signal.signal(number, lambda *_: control.kill())
    return previous


def _restore_stop_signals(previous: dict[int, Any]) -> None:
    for number, handler in previous.items():
        signal.signal(number, handler)


def _matches_page(note: dict[str, Any], pages: list[str] | None) -> bool:
    if not pages:
        return True
    source = note.get("source") or {}
    haystack = [note.get("pageUrl", ""), source.get("page") or "", source.get("path") or ""]
    return any(p in h for p in pages for h in haystack)


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────


def _stop_here(root: Path, now: bool) -> None:
    """`stop` for a revise running in this checkout itself (not a pass worktree)."""
    state_dir = notes_state_dir(root)
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "STOP").write_text(str(now_ms()), encoding="utf-8")
    print("stop requested: no new page will start", flush=True)
    pid_file = state_dir / "revise.pid"
    if now and pid_file.is_file() and _pid_alive(int(pid_file.read_text().strip() or 0)):
        os.kill(int(pid_file.read_text().strip()), signal.SIGTERM)
        print("sent SIGTERM to the harness; running agents are being stopped", flush=True)


def cmd_handoff(args: argparse.Namespace, store: Store, git: Git) -> int:
    body = (Path(args.body_file).read_text(encoding="utf-8") if args.body_file and args.body_file != "-"
            else sys.stdin.read())
    if not body.strip():
        raise SystemExit("a handoff needs a document body (--body-file, or Markdown on stdin)")
    in_run = bool(os.environ.get(JOURNAL_ENV))
    notes = store.notes()
    allowed = [i for i in os.environ.get(PARTITION_ENV, "").split(",") if i]
    from_notes = []
    for wanted in args.note:
        pool = [n for n in notes if n["id"] in allowed] if in_run and allowed else notes
        from_notes.append(find_note(pool, wanted))
    if args.from_page:
        page_url = page_url_for(args.from_page, git)
    elif os.environ.get(PAGE_URL_ENV):
        page_url = os.environ[PAGE_URL_ENV]
    elif from_notes:
        page_url = from_notes[0].get("pageUrl", "")
    else:
        raise SystemExit("say which page this comes from: --from-page, or --note")
    page = resolve_page_source(page_url, git).relative
    source_pages = {_page_key(n) for n in from_notes}
    if from_notes and page not in source_pages:
        page = next(iter(source_pages))
    handoff_id = str(uuid.uuid4())
    pages = [resolve_page_source(page_url_for(p, git), git).relative or p for p in args.page]
    path = write_handoff_document(git.root, handoff_id=handoff_id, title=args.title.strip(), body=body,
                                  scope=args.scope, blocking=args.blocking, page_url=page_url, pages=pages,
                                  notes=from_notes, author=current_author())
    result = record(store, {"op": "handoff", "handoffId": handoff_id, "title": args.title.strip(), "path": path,
                            "scope": args.scope, "blocking": args.blocking, "notes": [n["id"] for n in from_notes],
                            "pages": pages, "page": page, "pageUrl": page_url})
    print(f"handoff {handoff_id[:8]} written to {path}" + (" (journaled)" if "journaled" in result else ""))
    if args.blocking and in_run:
        print("This handoff blocks the page. Stop now: record nothing further and end your turn.")
    return 0


def page_url_for(spec: str, git: Git) -> str:
    """Accept a page URL, a docs-relative page, or a repo path to a docs Markdown file."""
    if spec.startswith("http"):
        return spec
    rel = spec.removeprefix(DOCS_SOURCE_DIR + "/").removeprefix("/")
    if rel.endswith(".md"):
        rel = rel[:-3]
        rel = "index.html" if rel == "index" else (rel.removesuffix("/index") + "/")
    return DOCS_ORIGIN + DOCS_ROOT + rel



# ─────────────────────────────────────────────────────────────────────────────
# A revision pass: a worktree, drained, reviewed, rebuilt, committed and landed
# ─────────────────────────────────────────────────────────────────────────────
#
# `pass …` is the lifecycle /docs-pass.html drives, and it works the same from a terminal. The
# pass lives in its own worktree on its own branch, so agents editing for an hour never share a
# working tree with anything else that lands on main. Its state is one JSON file in the *primary*
# checkout (the one --repo names), so the page can find the pass again after a restart.


def pass_state_path(root: Path) -> Path:
    return root / ".field-backdoor" / "docs-pass" / "current.json"


def load_pass(root: Path) -> dict[str, Any] | None:
    path = pass_state_path(root)
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def save_pass(root: Path, state: dict[str, Any] | None) -> None:
    path = pass_state_path(root)
    if state is None:
        with contextlib.suppress(FileNotFoundError):
            path.unlink()
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(state, indent=2), encoding="utf-8")
    os.replace(temp, path)


def require_pass(root: Path) -> dict[str, Any]:
    state = load_pass(root)
    if not state:
        raise SystemExit("There is no revision pass; start one with `pass create`.")
    if not Path(state["worktree"]).is_dir():
        raise SystemExit(f"The pass's worktree {state['worktree']} is gone; `pass finish --abandon` clears it.")
    return state


def default_pass_path(root: Path, stamp: str) -> Path:
    siblings = root.parent / f"{root.name}-worktrees"
    if siblings.is_dir():
        return siblings / f"docs-pass-{stamp}"
    return root.parent / f"{root.name}-docs-pass-{stamp}"


def pass_create(git: Git, *, base: str, path: str | None) -> dict[str, Any]:
    existing = load_pass(git.root)
    if existing and Path(existing["worktree"]).is_dir():
        raise SystemExit(f"A pass is already open in {existing['worktree']} ({existing['branch']}).")
    stamp = time.strftime("%Y%m%d-%H%M")
    branch = f"docs-pass/{stamp}"
    worktree = Path(path).expanduser().resolve() if path else default_pass_path(git.root, stamp)
    base_commit = git.run("rev-parse", base).strip()
    print(f"creating {worktree} on {branch} from {base} ({base_commit[:10]})", flush=True)
    git.run("worktree", "add", "-b", branch, str(worktree), base_commit)
    # Two things a fresh worktree of this repo lacks and a docs build or commit guard needs: the
    # LFS jars (checked out as pointers) and kotlin-js-store/yarn.lock (ignored, so never checked
    # out). Neither is needed to revise, and both failures are reported rather than fatal.
    lfs = subprocess.run(["git", "lfs", "checkout"], cwd=worktree, capture_output=True, text=True, check=False)
    print("git lfs checkout: " + ("ok" if lfs.returncode == 0 else (lfs.stderr.strip() or "unavailable")), flush=True)
    lock = git.root / "kotlin-js-store" / "yarn.lock"
    if lock.is_file() and not (worktree / "kotlin-js-store" / "yarn.lock").exists():
        (worktree / "kotlin-js-store").mkdir(exist_ok=True)
        shutil.copy2(lock, worktree / "kotlin-js-store" / "yarn.lock")
        print("copied kotlin-js-store/yarn.lock", flush=True)
    state = {"worktree": str(worktree), "branch": branch, "base": base, "baseCommit": base_commit,
             "primary": str(git.root), "createdAtEpochMs": now_ms(), "runs": []}
    save_pass(git.root, state)
    print(f"pass open: {worktree}", flush=True)
    return state


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except (ProcessLookupError, PermissionError):
        return False
    except OSError:
        return False
    return True


def latest_progress(worktree: Path) -> dict[str, Any] | None:
    state_dir = notes_state_dir(worktree)
    latest = state_dir / "latest-run"
    if not latest.is_file():
        return None
    path = state_dir / "runs" / latest.read_text(encoding="utf-8").strip() / "progress.json"
    if not path.is_file():
        return None
    progress = json.loads(path.read_text(encoding="utf-8"))
    pid_file = state_dir / "revise.pid"
    alive = pid_file.is_file() and _pid_alive(int(pid_file.read_text().strip() or 0))
    if progress.get("state") in ("running", "stopping") and not alive:
        progress["state"] = "died"  # the harness is gone without writing its own ending
    progress["alive"] = alive
    progress["stopRequested"] = (state_dir / "STOP").exists()
    return progress


def changed_files(wt: Git, base_commit: str) -> list[dict[str, Any]]:
    """Everything that differs from the pass's base, committed on the branch or not, untracked too."""
    files: dict[str, dict[str, Any]] = {}
    for line in wt.run("diff", "--numstat", base_commit, check=False).splitlines():
        added, removed, path = line.split("\t", 2)
        files[path] = {"path": path, "status": "modified", "added": _int(added), "removed": _int(removed)}
    for line in wt.run("diff", "--name-status", base_commit, check=False).splitlines():
        code, _, path = line.partition("\t")
        if path in files:
            files[path]["status"] = {"A": "added", "D": "deleted"}.get(code[:1], "modified")
    for path, code in wt.status_paths().items():
        if code == "??" and path not in files and not path.startswith(".field-backdoor/"):
            text = (wt.root / path).read_text(encoding="utf-8", errors="replace") if (wt.root / path).is_file() else ""
            files[path] = {"path": path, "status": "untracked", "added": text.count("\n") + (1 if text and not text.endswith("\n") else 0), "removed": 0}
    return sorted(files.values(), key=lambda f: f["path"])


def _int(value: str) -> int:
    return int(value) if value.isdigit() else 0


def pass_status(git: Git, store: Store) -> dict[str, Any]:
    notes = store.notes()
    counts: dict[str, int] = {}
    for note in notes:
        state = note_state(note)
        counts[state] = counts.get(state, 0) + 1
    handoffs = []
    for item in notes:
        if item.get("kind") != "handoff":
            continue
        info = item.get("handoff") or {}
        handoffs.append({"id": item["id"], "title": item.get("note", ""), "scope": info.get("scope"),
                         "blocking": bool(info.get("blocking")), "state": info.get("state", "open"),
                         "path": info.get("path"), "page": info.get("page") or item.get("pageUrl"),
                         "resolution": item.get("resolution", ""), "createdAtEpochMs": item.get("createdAtEpochMs"),
                         "waiting": sum(1 for n in notes if blocked_by(n) == item["id"])})
    result: dict[str, Any] = {"primary": str(git.root), "notes": counts, "handoffs": handoffs,
                              "primaryBranch": git.run("branch", "--show-current", check=False).strip(),
                              "pass": load_pass(git.root)}
    state = result["pass"]
    if state and Path(state["worktree"]).is_dir():
        wt = Git(Path(state["worktree"]))
        base = state["base"]
        result["worktree"] = {
            "exists": True, "head": wt.head(),
            "ahead": _int(wt.run("rev-list", "--count", f"{state['baseCommit']}..HEAD", check=False).strip()),
            "baseAdvanced": _int(git.run("rev-list", "--count", f"{state['baseCommit']}..{base}", check=False).strip()),
            "landed": subprocess.run(["git", "merge-base", "--is-ancestor", "HEAD", base], cwd=wt.root).returncode == 0
                      and wt.head() != state["baseCommit"],
            "changed": changed_files(wt, state["baseCommit"]),
            "uncommitted": len([p for p in wt.status_paths() if not p.startswith(".field-backdoor/")]),
            "handoffDocuments": sorted(p.relative_to(wt.root).as_posix() for p in (wt.root / HANDOFF_DIR).glob("*.md")
                                       if p.name != "README.md") if (wt.root / HANDOFF_DIR).is_dir() else [],
        }
        result["progress"] = latest_progress(wt.root)
    elif state:
        result["worktree"] = {"exists": False}
    return result


def safe_relative(path: str) -> str:
    candidate = Path(path)
    if candidate.is_absolute() or ".." in candidate.parts or not path.strip():
        raise SystemExit(f"not a path inside the checkout: {path!r}")
    return candidate.as_posix()


def pass_diff(git: Git, path: str | None) -> list[dict[str, Any]]:
    state = require_pass(git.root)
    wt = Git(Path(state["worktree"]))
    files = changed_files(wt, state["baseCommit"])
    if path:
        path = safe_relative(path)
        files = [f for f in files if f["path"] == path]
    for item in files:
        if item["status"] == "untracked":
            text = (wt.root / item["path"]).read_text(encoding="utf-8", errors="replace")
            item["patch"] = f"--- /dev/null\n+++ b/{item['path']}\n" + "".join(f"+{line}\n" for line in text.split("\n"))
        else:
            item["patch"] = wt.run("diff", state["baseCommit"], "--", item["path"], check=False)
        if len(item["patch"]) > 400_000:
            item["patch"] = item["patch"][:400_000] + "\n… (truncated; see git diff in the worktree)\n"
    return files


def pass_discard(git: Git, store: Store, path: str) -> None:
    """Throw one file's changes away, and put back in the queue what they claimed to resolve."""
    state = require_pass(git.root)
    wt = Git(Path(state["worktree"]))
    path = safe_relative(path)
    tracked_at_base = bool(wt.run("ls-tree", "--name-only", state["baseCommit"], "--", path, check=False).strip())
    if tracked_at_base:
        wt.run("restore", f"--source={state['baseCommit']}", "--staged", "--worktree", "--", path)
    else:
        with contextlib.suppress(FileNotFoundError):
            (wt.root / path).unlink()
        wt.run("rm", "--cached", "--quiet", "--ignore-unmatch", "--", path, check=False)
    runs = set(state.get("runs", []))

    def reopen(notes: list[dict[str, Any]]) -> int:
        count = 0
        for note in notes:
            if note.get("kind") == "handoff" and (note.get("handoff") or {}).get("path") == path \
                    and (note.get("handoff") or {}).get("state", "open") in ("open", "taken"):
                _close_handoff(notes, {"id": note["id"], "state": "dropped"}, "human", "handoff document discarded in the pass")
                count += 1
                continue
            if not is_resolved(note):
                continue
            last = next((e for e in reversed(note.get("thread", [])) if e.get("kind") == "resolution"), None)
            if last and last.get("runId") in runs and path in (last.get("changedFiles") or []):
                apply_op(notes, {"op": "reopen", "id": note["id"], "author": "human",
                                 "text": f"its edit to {path} was discarded in the docs pass"})
                count += 1
        return count

    count = store.mutate(reopen)
    print(f"discarded {path}; {count} note(s) or handoff(s) back in the queue", flush=True)


def pass_revise(args: argparse.Namespace, git: Git, store: Store) -> int:
    state = require_pass(git.root)
    wt = Git(Path(state["worktree"]))
    state_dir = notes_state_dir(wt.root)
    state_dir.mkdir(parents=True, exist_ok=True)
    before = (state_dir / "latest-run").read_text().strip() if (state_dir / "latest-run").is_file() else ""
    try:
        return revise(args, store, wt)
    finally:
        after = (state_dir / "latest-run").read_text().strip() if (state_dir / "latest-run").is_file() else ""
        if after and after != before:
            current = load_pass(git.root) or state
            current.setdefault("runs", []).append(after)
            save_pass(git.root, current)


def pass_stop(root: Path, now: bool) -> None:
    state = load_pass(root)
    _stop_here(Path(state["worktree"]) if state else root, now)


def uv_command() -> list[str]:
    uv = find_executable("uv")
    if not Path(uv).is_file():
        raise SystemExit("uv not found; the docs build runs through it (see AGENTS.md section 3)")
    return [uv]


def pass_rebuild(git: Git, mode: str, all_pages: bool) -> int:
    state = require_pass(git.root)
    wt = Git(Path(state["worktree"]))
    pages = sorted({f["path"][len(DOCS_SOURCE_DIR) + 1:] for f in changed_files(wt, state["baseCommit"])
                    if f["path"].startswith(DOCS_SOURCE_DIR + "/") and f["path"].endswith(".md") and f["status"] != "deleted"})
    command = uv_command() + ["run", "--project", str(wt.root), "--group", "docs", "python", "tools/build_zensical_docs.py",
                              "--no-source-pages"]
    if pages and not all_pages and mode != "never":
        for page in pages:
            command += ["--page", page]
    print(f"$ FIELD_DOCS_RUNNER_MODE={mode} {' '.join(shlex.quote(c) for c in command)}", flush=True)
    print(f"changed pages: {', '.join(pages) or '(none)'}", flush=True)
    result = subprocess.run(command, cwd=wt.root, env={**os.environ, "FIELD_DOCS_RUNNER_MODE": mode}, check=False)
    print(f"docs build exited {result.returncode}; the site is in {wt.root / 'public' / 'field4'}", flush=True)
    return result.returncode


def pass_commit_message(state: dict[str, Any], notes: list[dict[str, Any]], files: list[dict[str, Any]]) -> str:
    runs = set(state.get("runs", []))
    resolved: dict[str, list[dict[str, Any]]] = {}
    for note in notes:
        if not is_resolved(note) or note.get("kind") != "note":
            continue
        last = next((e for e in reversed(note.get("thread", [])) if e.get("kind") == "resolution"), None)
        if last and last.get("runId") in runs:
            resolved.setdefault(_page_key(note), []).append(note)
    handoffs = [n for n in notes if n.get("kind") == "handoff" and (n.get("handoff") or {}).get("runId") in runs]
    total = sum(len(v) for v in resolved.values())
    lines = [f"docs: revision pass {state['branch'].split('/')[-1]}: {total} note(s) on {len(resolved)} page(s)"
             + (f", {len(handoffs)} handoff(s)" if handoffs else ""), ""]
    for page in sorted(resolved):
        lines.append(f"{page}:")
        for note in resolved[page]:
            lines.append(f"- {note.get('resolution', '').strip()}")
        lines.append("")
    for item in handoffs:
        info = item.get("handoff") or {}
        lines.append(f"handoff ({info.get('scope')}{', blocking' if info.get('blocking') else ''}): {item.get('note')} → {info.get('path')}")
    if handoffs:
        lines.append("")
    lines.append(f"{len(files)} file(s) changed. Written by tools/docs_notes.py revise, reviewed in /docs-pass.html.")
    lines.append("")
    lines += [f"Docs-Note: {n['id']}" for page in sorted(resolved) for n in resolved[page]]
    lines += [f"Docs-Handoff: {n['id']}" for n in handoffs]
    lines += [f"Docs-Notes-Run: {r}" for r in state.get("runs", [])]
    return "\n".join(lines).rstrip() + "\n"


def pass_commit(git: Git, store: Store) -> int:
    state = require_pass(git.root)
    wt = Git(Path(state["worktree"]))
    progress = latest_progress(wt.root)
    if progress and progress.get("alive"):
        raise SystemExit("Agents are still running in the pass; stop or wait for them before committing.")
    uncommitted = [p for p in wt.status_paths() if not p.startswith(".field-backdoor/")]
    if not uncommitted:
        print("nothing to commit", flush=True)
        return 0
    wt.run("add", "-A")  # .field-backdoor/ (run logs) is gitignored
    message = pass_commit_message(state, store.notes(), changed_files(wt, state["baseCommit"]))
    print(message, flush=True)
    print("committing; the pre-commit guard runs Gradle and takes a few minutes …", flush=True)
    result = subprocess.run(["git", "commit", "-F", "-"], cwd=wt.root, input=message, text=True, check=False)
    if result.returncode != 0:
        print("commit refused (see the guard's output above); the changes are still staged in the worktree", flush=True)
        return result.returncode
    sha = Git(wt.root).head()
    runs = set(state.get("runs", []))

    def stamp(notes: list[dict[str, Any]]) -> None:
        for note in notes:
            for entry in note.get("thread", []):
                if entry.get("kind") == "resolution" and entry.get("runId") in runs and not entry.get("commit"):
                    entry["commit"] = sha

    store.mutate(stamp)
    print(f"committed {sha[:10]} on {state['branch']}", flush=True)
    return 0


def pass_land(git: Git) -> int:
    """Merge the base into the pass branch (in the worktree), then fast-forward the base to it."""
    state = require_pass(git.root)
    wt = Git(Path(state["worktree"]))
    base, branch = state["base"], state["branch"]
    if [p for p in wt.status_paths() if not p.startswith(".field-backdoor/")]:
        raise SystemExit("The worktree has uncommitted changes; commit them first.")
    if git.run("branch", "--show-current", check=False).strip() != base:
        raise SystemExit(f"{git.root} is not on {base}; landing fast-forwards {base} there.")
    if subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"], cwd=wt.root).returncode != 0:
        print(f"{base} has moved on; merging it into {branch} first (in the worktree) …", flush=True)
        merge = subprocess.run(["git", "merge", "--no-ff", "--no-edit", base], cwd=wt.root, check=False)
        if merge.returncode != 0:
            conflicts = wt.run("diff", "--name-only", "--diff-filter=U", check=False).split()
            subprocess.run(["git", "merge", "--abort"], cwd=wt.root, check=False)
            print(f"merge failed and was aborted; conflicts in: {', '.join(conflicts) or '(see above)'}", flush=True)
            return 1
    result = subprocess.run(["git", "merge", "--ff-only", branch], cwd=git.root, check=False)
    if result.returncode != 0:
        print(f"fast-forward of {base} failed (see above); nothing was changed in {git.root}", flush=True)
        return result.returncode
    print(f"{base} is now {git.head()[:10]} in {git.root}. Not pushed.", flush=True)
    return 0


def pass_finish(git: Git, abandon: bool) -> int:
    state = load_pass(git.root)
    if not state:
        print("no pass open", flush=True)
        return 0
    worktree, branch = Path(state["worktree"]), state["branch"]
    landed = subprocess.run(["git", "merge-base", "--is-ancestor", branch, state["base"]], cwd=git.root).returncode == 0
    dirty = worktree.is_dir() and any(not p.startswith(".field-backdoor/") for p in Git(worktree).status_paths())
    if (not landed or dirty) and not abandon:
        why = "has uncommitted changes" if landed else f"has not landed on {state['base']}"
        raise SystemExit(f"The pass {why}; commit and land it first, or finish with --abandon to throw it away.")
    if worktree.is_dir():
        remove = ["worktree", "remove"] + (["--force"] if abandon else []) + [str(worktree)]
        git.run(*remove)
    git.run("worktree", "prune", check=False)
    git.run("branch", "-D" if abandon else "-d", branch, check=False)
    save_pass(git.root, None)
    print(f"pass closed: removed {worktree} and {branch}" + (" (abandoned)" if abandon else ""), flush=True)
    return 0


def run_pass(args: argparse.Namespace, store: Store, git: Git) -> int:
    action = args.pass_command
    if action == "create":
        pass_create(git, base=args.base, path=args.path)
        return 0
    if action == "status":
        print(json.dumps(pass_status(git, store), indent=2, ensure_ascii=False))
        return 0
    if action == "diff":
        print(json.dumps(pass_diff(git, args.path), ensure_ascii=False))
        return 0
    if action == "discard":
        pass_discard(git, store, args.path)
        return 0
    if action == "handoff-text":
        item = find_note([n for n in store.notes() if n.get("kind") == "handoff"], args.id)
        info = item.get("handoff") or {}
        state = load_pass(git.root)
        roots = [Path(state["worktree"])] if state else []
        roots.append(git.root)
        path = next((r / info.get("path", "") for r in roots if (r / info.get("path", "")).is_file()), None)
        print(json.dumps({"id": item["id"], "title": item.get("note"), "handoff": info,
                          "text": path.read_text(encoding="utf-8") if path else "(the document is not in this checkout or the pass worktree)",
                          "file": str(path) if path else ""}, ensure_ascii=False))
        return 0
    if action == "revise":
        return pass_revise(args, git, store)
    if action == "stop":
        pass_stop(git.root, args.now)
        return 0
    if action == "rebuild":
        return pass_rebuild(git, args.mode, args.all)
    if action == "commit":
        return pass_commit(git, store)
    if action == "land":
        return pass_land(git)
    if action == "finish":
        return pass_finish(git, args.abandon)
    return 2


def add_revise_arguments(p: argparse.ArgumentParser) -> None:
    p.add_argument("--agent", choices=sorted(AGENTS), default=os.environ.get("DOCS_NOTES_AGENT", "claude"))
    p.add_argument("--agent-command", help="custom agent argv template: {prompt}, {prompt_file}, {root}")
    p.add_argument("--jobs", "-j", type=int, default=4)
    p.add_argument("--tag", action="append", default=[])
    p.add_argument("--page", action="append", default=[])
    p.add_argument("--max-pages", type=int, default=0)
    p.add_argument("--timeout", type=int, default=1800, help="seconds per agent")
    p.add_argument("--commit", action="store_true", help="one commit per page after every agent has finished")
    p.add_argument("--allow-dirty", action="store_true", help="run even if an owned file has uncommitted changes")
    p.add_argument("--dry-run", action="store_true", help="show the partition plan and stop")
    p.add_argument("--no-sync", action="store_true")
    p.add_argument("--verbose", "-v", action="store_true")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--store", help=f"annotation store (default: ${STORE_ENV} or ~/.afield/docs-annotations.json)")
    parser.add_argument("--repo", default=str(REPO_ROOT), help="checkout the notes' sources live in")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("sync", help="associate notes with source lines and track them forward")
    p.add_argument("ids", nargs="*")
    p.add_argument("--open-only", action="store_true", help="skip resolved notes")
    p.add_argument("--reanchor", action="store_true", help="discard blame origins and associate from the quote again")

    p = sub.add_parser("queue", help="open notes, grouped the way `revise` would partition them")
    p.add_argument("--tag", action="append", default=[])
    p.add_argument("--page", action="append", default=[])
    p.add_argument("--all", action="store_true", help="every note, with its state")
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("brief", help="the agent brief for a page (or for the notes given)")
    p.add_argument("page", nargs="?", help="page URL, docs page, or repo path")
    p.add_argument("--id", action="append", default=[])

    p = sub.add_parser("show", help="one note, fully")
    p.add_argument("id")
    p.add_argument("--json", action="store_true")

    for name, help_text in (("resolve", "mark a note resolved"), ("question", "ask the human; leaves the queue until answered"),
                            ("reply", "answer an agent's question (human)"), ("reopen", "put a resolved note back in the queue"),
                            ("comment", "add a comment to a note's thread")):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("id")
        p.add_argument("text", nargs="?", default="")
        if name == "resolve":
            p.add_argument("--memory", help="also leave a memory on the note")

    p = sub.add_parser("memory", help="leave a memory on a note, or on a page with --page")
    p.add_argument("id", nargs="?")
    p.add_argument("text", nargs="?")
    p.add_argument("--page", help="page URL, docs page or repo path, for a page-level memory")
    p.add_argument("--line", type=int, help="with --page: anchor the memory at this line of the page's source")

    p = sub.add_parser("handoff", help="queue a larger task as a document in dev_docs/handoffs/")
    p.add_argument("title", help="short imperative title")
    p.add_argument("--scope", choices=HANDOFF_SCOPES, default="project",
                   help="project: code or other engineering; crosscutting: merges, moves, changes across pages")
    p.add_argument("--body-file", help="the document body (Markdown); '-' or omitted reads stdin")
    p.add_argument("--note", action="append", default=[], help="a note this handoff comes from (repeatable)")
    p.add_argument("--page", action="append", default=[], help="another page it affects (repeatable)")
    p.add_argument("--from-page", help="the page it is filed from (default: the run's page, or the notes' page)")
    p.add_argument("--blocking", action="store_true", help="the page's other notes wait until it is closed")

    p = sub.add_parser("handoffs", help="the handoff queue, for the interactive pass")
    p.add_argument("--all", action="store_true", help="include done and dropped handoffs")
    p.add_argument("--scope", choices=HANDOFF_SCOPES)
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("handoff-close", help="finish a handoff and release the notes it blocked")
    p.add_argument("id")
    p.add_argument("text", nargs="?", default="")
    p.add_argument("--state", choices=("done", "dropped", "taken"), default="done",
                   help="taken: someone is working on it (still blocks); dropped: abandoned (releases the notes)")

    p = sub.add_parser("revise", help="run one agent per page over the queue, in parallel")
    add_revise_arguments(p)

    p = sub.add_parser("stop", help="stop a running revise: after the current pages, or --now")
    p.add_argument("--now", action="store_true", help="terminate the running agents too")

    passes = sub.add_parser("pass", help="a revision pass in its own worktree: create, revise, review, rebuild, commit, land")
    pass_sub = passes.add_subparsers(dest="pass_command", required=True)
    q = pass_sub.add_parser("create", help="a new worktree and branch from --base")
    q.add_argument("--base", default="main")
    q.add_argument("--path", help="where to put the worktree")
    pass_sub.add_parser("status", help="the pass, its changes, the queue and handoffs, as JSON")
    q = pass_sub.add_parser("diff", help="changes against the pass's base, as JSON")
    q.add_argument("--path")
    q = pass_sub.add_parser("discard", help="throw away one file's changes and reopen what they resolved")
    q.add_argument("path")
    q = pass_sub.add_parser("handoff-text", help="a handoff's document, as JSON")
    q.add_argument("id")
    q = pass_sub.add_parser("revise", help="drain the queue in the pass's worktree")
    add_revise_arguments(q)
    q = pass_sub.add_parser("stop", help="stop the pass's revise: after the current pages, or --now")
    q.add_argument("--now", action="store_true")
    q = pass_sub.add_parser("rebuild", help="build the docs in the worktree")
    q.add_argument("--mode", choices=("never", "incremental", "strict", "always"), default="never",
                   help="FIELD_DOCS_RUNNER_MODE; `never` builds HTML without re-running any capture")
    q.add_argument("--all", action="store_true", help="run captures on every page, not only changed ones")
    pass_sub.add_parser("commit", help="commit the worktree, with the resolved notes in the message")
    pass_sub.add_parser("land", help="merge the base in, then fast-forward the base in the primary checkout")
    q = pass_sub.add_parser("finish", help="remove the worktree and branch once landed")
    q.add_argument("--abandon", action="store_true", help="remove them even though they never landed")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    store = Store(resolve_store_path(args.store))
    git = Git(Path(args.repo).resolve())

    if args.command == "sync":
        if args.reanchor and not args.ids:
            raise SystemExit("--reanchor needs note ids: it throws away tracking history")
        ids = [find_note(store.notes(), i)["id"] for i in args.ids]
        sync_store(store, git, ids=ids, include_resolved=not args.open_only, reanchor=args.reanchor, log=print)
        return 0
    if args.command == "queue":
        notes = store.notes()
        chosen = [n for n in notes if n.get("kind") != "handoff"] if args.all else [n for n in notes if in_queue(n, args.tag)]
        chosen = [n for n in chosen if _matches_page(n, args.page)]
        if args.json:
            print(json.dumps([{**n, "state": note_state(n)} for n in chosen], indent=2, ensure_ascii=False))
            return 0
        for partition in partition_notes(chosen):
            print(f"{partition.key}  ({len(partition.notes)})")
            for note in partition.notes:
                source = note.get("source") or {}
                where = f":{source['startLine']}" if source.get("startLine") else f" [{source.get('status', 'unsynced')}]"
                print(f"  {note['id'][:8]} {note_state(note):8} {where:>8}  {note.get('note', '').strip().splitlines()[0][:90] if note.get('note') else ''}")
        return 0
    if args.command == "brief":
        notes = store.notes()
        if args.id:
            chosen = [find_note(notes, i) for i in args.id]
        else:
            if not args.page:
                raise SystemExit("brief needs a page or --id")
            chosen = [n for n in notes if in_queue(n) and _matches_page(n, [args.page])]
        partitions = partition_notes(chosen) or [Partition(args.page or "", [args.page or ""], [], [], [])]
        for partition in partitions:
            print(render_brief(partition, notes, git))
        return 0
    if args.command == "show":
        note = find_note(store.notes(), args.id)
        print(json.dumps(note, indent=2, ensure_ascii=False) if args.json else render_note(note, git))
        return 0
    if args.command in ("resolve", "question", "reply", "reopen", "comment"):
        if args.command in ("resolve", "question", "reply", "comment") and not args.text.strip():
            raise SystemExit(f"{args.command} needs text")
        op = {"op": args.command, "id": args.id if os.environ.get(JOURNAL_ENV) else find_note(store.notes(), args.id)["id"],
              "text": args.text}
        if args.command == "resolve" and args.memory:
            op["memory"] = args.memory
        result = record(store, op)
        print(f"{args.command}: {result.get('id', '')[:8]}" + (" (journaled)" if "journaled" in result else ""))
        return 0
    if args.command == "memory":
        if args.page:
            text = args.text if args.text is not None else args.id
            if not text:
                raise SystemExit("memory --page needs text")
            url = page_url_for(args.page, git)
            op: dict[str, Any] = {"op": "page-memory", "pageUrl": url, "text": text}
            if args.line:
                page = resolve_page_source(url, git)
                if page.path:
                    op["source"] = {"page": page.relative, "path": page.path, "startLine": args.line, "endLine": args.line,
                                    "status": "anchored", "origins": origins_for(git, None, page.path, args.line, args.line),
                                    "anchoredAt": git.head(), "method": "line"}
            result = record(store, op)
        else:
            if not args.id or not args.text:
                raise SystemExit("memory needs a note id and text (or --page)")
            op = {"op": "memory", "id": args.id if os.environ.get(JOURNAL_ENV) else find_note(store.notes(), args.id)["id"],
                  "text": args.text}
            result = record(store, op)
        print("memory recorded" + (" (journaled)" if "journaled" in result else ""))
        return 0
    if args.command == "handoff":
        return cmd_handoff(args, store, git)
    if args.command == "handoffs":
        items = [n for n in store.notes() if n.get("kind") == "handoff"]
        if not args.all:
            items = [n for n in items if (n.get("handoff") or {}).get("state", "open") in ("open", "taken")]
        if args.scope:
            items = [n for n in items if (n.get("handoff") or {}).get("scope") == args.scope]
        items.sort(key=lambda n: (not (n.get("handoff") or {}).get("blocking"), n.get("createdAtEpochMs", 0)))
        if args.json:
            print(json.dumps(items, indent=2, ensure_ascii=False))
            return 0
        for item in items:
            info = item.get("handoff") or {}
            waiting = sum(1 for n in store.notes() if blocked_by(n) == item["id"]) if info.get("blocking") else 0
            print(f"{item['id'][:8]}  {info.get('state', 'open'):7} {info.get('scope', ''):12} "
                  f"{'BLOCKING ' + str(waiting) + ' note(s)' if info.get('blocking') else '':22} {item.get('note', '')}")
            print(f"          {info.get('path', '')}   (from {info.get('page') or item.get('pageUrl')})")
        if not items:
            print("No open handoffs.")
        return 0
    if args.command == "handoff-close":
        if args.state == "done" and not args.text.strip():
            raise SystemExit("handoff-close needs a sentence saying what happened")
        result = record(store, {"op": "handoff-close", "id": find_note(store.notes(), args.id)["id"],
                                "text": args.text, "state": args.state})
        print(f"handoff {result.get('id', '')[:8]}: {args.state}")
        return 0
    if args.command == "revise":
        return revise(args, store, git)
    if args.command == "stop":
        _stop_here(git.root, args.now)
        return 0
    if args.command == "pass":
        return run_pass(args, store, git)
    return 2


if __name__ == "__main__":
    sys.exit(main())
