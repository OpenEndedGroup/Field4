#!/usr/bin/env python3
"""Build the link graph of the documentation site, for reflection rather than enforcement.

What it reads
-------------
* **Pages** — every Markdown page under ``public/docs`` (not ``assets/``). An edge is a link a
  reader can follow: ``[text](other.md#x)``, a raw-HTML ``href``, a pretty-URL ``other/``. Links
  inside code fences are not links. The nav in ``public/zensical.yml`` gives each page its
  *system* (the top-level section it is filed under) and says which pages are listed at all.
* **Transcripts** (``--transcripts``) — every ``dev_docs/agents/transcripts/*.yaml``. A
  transcript's ``docs:`` list gives transcript→page edges; a page's
  ``offline.html?file=assets/agent_transcripts/<name>/…`` link gives page→transcript edges.
* **Code** (``--code``) — source files under ``src/``. A page reaches a file by naming it (a
  GitHub blob link, a ``path#anchor`` citation, a ``src/…`` code span) or by calling a symbol the
  file declares in its Kotlin fences (the language is the fence's ``data-lang``); a transcript
  reaches one through the code of its boxes. A *use* is not a word: comments and string text are
  dropped (``${…}`` templates kept), a lowercase name counts only as ``x.name``, ``name(`` or
  ``name {``, and a name the code declares itself is not a use — the index holds data-class
  properties as bare names, and ``// absolute record timecode`` is not a call of
  ``PolynomialLeafGapStyle.absolute``.
  Symbol→file attribution is ``docs_hooks.load_source_symbol_index``, the same drift-guarded
  read of the codebase reference that keys the capture digests — not a second Kotlin parser, and
  it keeps only names declared in exactly one file, so ``size`` or ``name`` attach to nothing.
* **Reference** (``--reference``) — the generated ``public/docs/reference/*.md`` pages as nodes,
  with an edge to each source file a section documents and their "Used in" back-links. Without
  it those pages and every link into them are left out, so a hand-written page's degree is not
  inflated by the generator. Code nodes still carry ``inReference`` either way.
* **History** — one ``git log --numstat`` pass, cached per HEAD under the output directory and
  extended incrementally. A commit counts as a *sweep* when it touches more than
  ``SWEEP_FILES`` tracked files in scope (a voice pass, a literal sweep, a regeneration) and as
  *substantive* otherwise; "last edited" means last substantive edit, so a sweep does not make
  an untended page look fresh.

What it writes (``build/docs_link_graph/`` by default)
------------------------------------------------------
* ``report.md`` — hubs (in-degree, PageRank, HITS hub/authority), bridges (betweenness), orphans
  and dead ends, the system×system link matrix and the systems that keep to themselves, code
  with public API that no page or transcript reaches, short and long pages, and age: pages the
  code they cite has moved on from, and pages nothing newer links to.
* ``graph.html`` — the graph drawn in 3D, self-contained (it inlines Field's vendored three.js and
  d3-force-3d-webgpu, and opens from disk). The page lays itself out live: a force simulation
  (link springs, octree Barnes–Hut repulsion, gravity) over every visible node, in a Web Worker
  on the CPU, posting every tick. (Not the package's WebGPU path: its asynchronous readback lands
  only a few times a second, which showed as motion in pulses.) Pages are held by the links
  switched on, each source file hangs from its directory and each directory from its parent,
  and the code edge kinds (cites, calls, mentions, ...) tie files to the pages that use them;
  toggling a layer or an edge kind reheats it. ``layout`` gives only the starting positions.
  Coloured by section, last edit or code drift; click a node for what it links and what links
  it, double-click to frame it with its neighbours. The page is ``tools/docs_link_graph.html``
  with the data and positions filled in.
* The published site carries the same view as ``assets/docs_map/graph.html``, embedded by
  ``docs_map.md``: ``tools/build_zensical_docs.py`` calls ``write_site_map``, which links each
  node to its built page (a source file to its reference section) and skips the git history.
* ``graph.json`` (node-link) and ``graph.graphml`` (opens in Gephi, yEd, Cytoscape).

What it cannot see
------------------
* A call reached indirectly (a helper that calls the cited function): depth 1 only, the same
  limit ``docs_hooks.fence_source_files`` documents.
* A symbol declared in more than one file: dropped, not guessed.
* Renames: ``--no-renames`` history, so a moved page's "created" date is the move.
* The reference's own "Used in" matching by name (see that generator) — its errors come through
  ``--reference`` unchanged.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_RELATIVE = "public/docs"
NAV_RELATIVE = "public/zensical.yml"
TRANSCRIPTS_RELATIVE = "dev_docs/agents/transcripts"
DEFAULT_OUT = "build/docs_link_graph"
GIT_SCOPE = ("public/docs", "dev_docs/agents/transcripts", "src/jvmMain", "src/jsMain", "src/commonMain")
SWEEP_FILES = 30
NON_SUBSTANTIVE_SUBJECT_RE = re.compile(r"^(Regenerate|Land |Merge )")
DAY = 86400.0

FENCE_OPEN_RE = re.compile(r"^(?P<indent>[ \t]*)(?P<fence>`{3,}|~{3,})\s*(?P<lang>[^\s{`]*)\s*(?P<attrs>\{[^}\n]*\}?)?")
# Nearly every docs fence opens as ``diffstatic {data-lang=kotlin}``: the language is the attribute.
FENCE_LANG_ATTR_RE = re.compile(r"\bdata-lang\s*=\s*[\"']?(?P<lang>[\w+#-]+)")
LINK_RE = re.compile(r"\]\(\s*<?(?P<target>[^)\s>]+)")
REF_DEF_RE = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(?P<target>\S+?)>?(?:\s|$)", re.M)
HTML_ATTR_RE = re.compile(r"\bhref\s*=\s*[\"'](?P<target>[^\"']+)[\"']")
INLINE_CODE_RE = re.compile(r"(?<!`)`(?P<code>[^`\n]+)`(?!`)")
GITHUB_REPO_RE = re.compile(r"^https?://github\.com/OpenEndedGroup/Field4/(?:blob|tree)/[^/]+/(?P<path>[^#?]+)")
TRANSCRIPT_LINK_RE = re.compile(r"agent_transcripts/(?P<name>[A-Za-z0-9_.-]+)/")
REPO_PATH_SPAN_RE = re.compile(r"^(?P<path>src/[A-Za-z0-9_./@+-]+?)(?::\d+(?:-\d+)?)?(?:#.*)?$")
IMPORT_RE = re.compile(r"^\s*import\s+(?P<pkg>afield(?:\.[a-z][A-Za-z0-9_]*)+)", re.M)
H1_RE = re.compile(r"^#\s+(?P<title>.+?)\s*(?:\{[^}]*\})?\s*$", re.M)
WORD_RE = re.compile(r"[A-Za-z][A-Za-z'’-]*")
# Only Kotlin calls Field's Kotlin API; a bash, Python or GLSL fence that says `import` or `under`
# names nothing in it.
SYMBOL_FENCES = frozenset({"kotlin", "kt", "kts"})
REFERENCE_PREFIX = "reference/"
# Catalogues: they link (nearly) every page by construction, so they are left out of the editorial
# degree counts. changelog.md is written from main's history; agent_transcripts.md lists every
# transcript's page.
REFERENCE_ANCHOR_RE = re.compile(r"^[^\n]*\{\s*#(?P<anchor>[\w-]+)\s*\}")
INDEX_PAGES = ("changelog.md", "agent_transcripts.md")


# --------------------------------------------------------------------------------------------
# Markdown


@dataclass
class PageText:
    prose: str
    fences: list[tuple[str, str]]  # (language, code)


def split_fences(markdown: str) -> PageText:
    """Separate fenced code from prose. Unterminated fences run to the end, as Markdown does."""
    prose: list[str] = []
    fences: list[tuple[str, str]] = []
    lines = markdown.splitlines()
    index = 0
    while index < len(lines):
        match = FENCE_OPEN_RE.match(lines[index])
        if match is None:
            prose.append(lines[index])
            index += 1
            continue
        fence = match.group("fence")
        attr_lang = FENCE_LANG_ATTR_RE.search(match.group("attrs") or "")
        language = (attr_lang.group("lang") if attr_lang else match.group("lang")).strip().lower()
        body: list[str] = []
        index += 1
        while index < len(lines):
            stripped = lines[index].strip()
            if stripped.startswith(fence[0] * len(fence)) and stripped.strip(fence[0]) == "":
                index += 1
                break
            body.append(lines[index])
            index += 1
        fences.append((language, "\n".join(body)))
        prose.append("")
    return PageText("\n".join(prose), fences)


def link_targets(prose: str) -> list[str]:
    targets = [m.group("target") for m in LINK_RE.finditer(prose)]
    targets += [m.group("target") for m in REF_DEF_RE.finditer(prose)]
    targets += [m.group("target") for m in HTML_ATTR_RE.finditer(prose)]
    return targets


def resolve_page_target(target: str, page: str, pages: set[str]) -> str | None:
    """A docs-root-relative ``.md`` path for ``target`` written on ``page``, or None."""
    path = target.split("#", 1)[0].split("?", 1)[0]
    if not path or "://" in path or path.startswith(("mailto:", "/")):
        return None
    base = page.rsplit("/", 1)[0] + "/" if "/" in page else ""
    candidates = []
    if path.endswith(".md"):
        candidates.append(path)
    elif path.endswith("/"):
        candidates += [path.rstrip("/") + ".md", path + "index.md"]
    elif "." not in path.rsplit("/", 1)[-1]:
        candidates.append(path + ".md")
    for candidate in candidates:
        for anchored in (base + candidate, candidate):
            normal = normalize(anchored)
            if normal in pages:
                return normal
        # Pretty URLs are one directory deeper than the source page, so `../other/` from
        # `page/` names `other.md` beside `page.md`.
        if candidate.startswith("../"):
            normal = normalize(base + candidate[3:])
            if normal in pages:
                return normal
    return None


def normalize(path: str) -> str | None:
    parts: list[str] = []
    for part in path.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                return None
            parts.pop()
        else:
            parts.append(part)
    return "/".join(parts)


def word_count(prose: str) -> int:
    text = re.sub(r"<[^>]+>", " ", prose)
    text = re.sub(r"\]\([^)]*\)", "]", text)
    return len(WORD_RE.findall(text))


# --------------------------------------------------------------------------------------------
# Nav


def load_nav(repo_root: Path) -> dict[str, tuple[str, ...]]:
    """Page → its nav path (section titles, outermost first). Unlisted pages are absent."""
    import yaml

    class Loader(yaml.SafeLoader):
        pass

    Loader.add_multi_constructor("", lambda loader, suffix, node: None)
    config = yaml.load((repo_root / NAV_RELATIVE).read_text(encoding="utf-8"), Loader=Loader)
    placed: dict[str, tuple[str, ...]] = {}

    def walk(items: Any, trail: tuple[str, ...]) -> None:
        for item in items or []:
            if isinstance(item, str):
                placed.setdefault(item, trail)
            elif isinstance(item, dict):
                for title, value in item.items():
                    if isinstance(value, str):
                        placed.setdefault(value, trail or (str(title),))
                    else:
                        walk(value, trail + (str(title),))

    walk(config.get("nav"), ())
    return placed


# --------------------------------------------------------------------------------------------
# Symbols


USED_IN_RE = re.compile(r"^\*\*Used in:\*\*(?P<links>.*)$", re.M)


def import_docs_hooks() -> Any:
    if str(REPO_ROOT / "public") not in sys.path:
        sys.path.insert(0, str(REPO_ROOT / "public"))
    import docs_hooks  # type: ignore[import-not-found]

    return docs_hooks


def used_in_by_file(repo_root: Path, pages: set[str]) -> dict[str, list[str]]:
    """Source file → the pages its reference section lists under "Used in" (the generator's own,
    name-matched, attribution — a second detector beside the fence-symbol one)."""
    hooks = import_docs_hooks()
    found: dict[str, list[str]] = collections.defaultdict(list)
    for page in sorted((repo_root / DOCS_RELATIVE / "reference").glob("*.md")):
        for section in page.read_text(encoding="utf-8").split("\n## ")[1:]:
            path = hooks.REFERENCE_SOURCE_PATH_RE.search(section)
            used = USED_IN_RE.search(section)
            if path is None or used is None:
                continue
            for target in link_targets(used.group("links")):
                resolved = resolve_page_target(target, REFERENCE_PREFIX + page.name, pages)
                if resolved is not None:
                    found[path.group(1)].append(resolved)
    return found


# Strings and comments, matched together so a "//" inside a string is not a comment. A string keeps
# its templates (`"${Resolve.markers().size}"` calls `markers`); its words do not ("Edit 2" is a
# timeline name, not `Edit`).
KOTLIN_NOISE_RE = re.compile(r'"""(?P<raw>.*?)"""|"(?P<str>(?:\\.|[^"\\\n])*)"|//[^\n]*|/\*.*?\*/|\'(?:\\.|[^\'\\\n])\'', re.S)
TEMPLATE_RE = re.compile(r"\$\{(?P<expr>[^{}]*)\}|\$(?P<name>[A-Za-z_]\w*)")
DECLARED_HERE_RE = re.compile(r"\b(?:val|var|fun|class|object|interface)\s+(?:<[^>]*>\s*)?(?:\w+\.)?(?P<name>[A-Za-z_]\w*)")
IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
GENERIC_CALL_RE = re.compile(r"<[\w\s,.<>?*]*>\s*\(")


def kotlin_code_only(code: str) -> str:
    def keep(match: re.Match[str]) -> str:
        body = match.group("raw") if match.group("raw") is not None else match.group("str")
        if body is None:
            return " "
        return " " + " ; ".join(m.group("expr") or m.group("name") for m in TEMPLATE_RE.finditer(body)) + " "

    return KOTLIN_NOISE_RE.sub(keep, code)


def used_symbols(code: str) -> set[str]:
    """The names Kotlin ``code`` uses, as opposed to the words it happens to contain.

    Comments and string text go; templates stay. A capitalised name (a type, an object) counts
    wherever it stands. A lowercase one counts only in a call or member position — ``x.name``,
    ``name(``, ``name {`` — because the index holds data-class properties and parameters as bare
    names, and a fence's own ``val under = …`` is not a use of ``ThreeTintBand.under``. Names the
    code declares itself are not uses either.
    """
    text = kotlin_code_only(code)
    declared = {m.group("name") for m in DECLARED_HERE_RE.finditer(text)}
    used: set[str] = set()
    for match in IDENTIFIER_RE.finditer(text):
        name = match.group()
        if name in declared:
            continue
        if name[0].isupper():
            used.add(name)
            continue
        i = match.start() - 1
        while i >= 0 and text[i] in " \t\n":
            i -= 1
        j = match.end()
        while j < len(text) and text[j] in " \t":
            j += 1
        nxt = text[j:j + 1]
        if (i >= 0 and text[i] == ".") or nxt in ("(", "{") or (nxt == "<" and GENERIC_CALL_RE.match(text, j)):
            used.add(name)
    return used


def code_files_for(code: str, symbol_index: dict[str, tuple[str, ...]], *, mention: bool = False) -> collections.Counter:
    """Source file → number of distinct indexed symbols ``code`` uses from it.

    ``mention``: ``code`` is an inline span in prose, where a lone name (`` `markersAt` ``) is a
    deliberate mention and counts in any case.
    """
    names = used_symbols(code)
    if mention and IDENTIFIER_RE.fullmatch(code.strip()):
        names.add(code.strip())
    reached: collections.Counter = collections.Counter()
    for token in names:
        for source in symbol_index.get(token, ()):
            reached[source] += 1
    return reached


def reference_sections(repo_root: Path) -> dict[str, list[str]]:
    """Reference page → the source files its sections document."""
    hooks = import_docs_hooks()
    found: dict[str, list[str]] = {}
    for page in sorted((repo_root / DOCS_RELATIVE / "reference").glob("*.md")):
        text = page.read_text(encoding="utf-8")
        files = [m.group(1) for m in hooks.REFERENCE_SOURCE_PATH_RE.finditer(text)]
        found[REFERENCE_PREFIX + page.name] = files
    return found


def api_size_by_file(repo_root: Path) -> collections.Counter:
    """Source file → number of declared names the reference lists for it (ambiguous ones too)."""
    hooks = import_docs_hooks()
    sizes: collections.Counter = collections.Counter()
    for page in sorted((repo_root / DOCS_RELATIVE / "reference").glob("*.md")):
        text = page.read_text(encoding="utf-8")
        for section in text.split("\n## ")[1:]:
            match = hooks.REFERENCE_SOURCE_PATH_RE.search(section)
            if match is None:
                continue
            sizes[match.group(1)] += len(hooks.HIGHLIGHT_CODE_RE.findall(section))
    return sizes


def code_system(path: str) -> str:
    parts = path.split("/")
    if len(parts) >= 2 and parts[1] == "jsMain":
        return "code:frontend"
    if len(parts) >= 2 and parts[1] == "commonMain":
        return "code:common"
    if "afield" in parts:
        rest = parts[parts.index("afield") + 1 :]
        return "code:" + (rest[0] if len(rest) > 1 else "afield")
    return "code:" + (parts[1] if len(parts) > 1 else "src")


# --------------------------------------------------------------------------------------------
# History


@dataclass
class FileHistory:
    created: float | None = None
    last_touch: float | None = None
    last_substantive: float | None = None
    commits: int = 0
    substantive_commits: int = 0
    lines_changed: int = 0


def git(repo_root: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=repo_root, capture_output=True, text=True, check=True).stdout


def cached_git_log(repo_root: Path, out_dir: Path, *, quiet: bool) -> str:
    """The scoped ``git log --numstat`` text, cached per HEAD and extended from an older cache."""
    head = git(repo_root, "rev-parse", "HEAD").strip()
    cache_dir = out_dir / "gitlog"
    cache_dir.mkdir(parents=True, exist_ok=True)
    target = cache_dir / f"{head}.txt"
    if target.exists():
        return target.read_text(encoding="utf-8")
    base_args = ["log", "--no-merges", "--no-renames", "--format=%x00%H%x09%ct%x09%s", "--numstat"]
    older = sorted(cache_dir.glob("*.txt"), key=lambda p: p.stat().st_mtime, reverse=True)
    text = None
    for candidate in older:
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", candidate.stem, head], cwd=repo_root, capture_output=True
        )
        if ancestor.returncode == 0:
            if not quiet:
                print(f"history: extending cache from {candidate.stem[:10]}", file=sys.stderr)
            newer = git(repo_root, *base_args, f"{candidate.stem}..{head}", "--", *GIT_SCOPE)
            text = newer + candidate.read_text(encoding="utf-8")
            break
    if text is None:
        if not quiet:
            print("history: reading full git log once (about 80 s; cached per HEAD)", file=sys.stderr)
        text = git(repo_root, *base_args, "--", *GIT_SCOPE)
    target.write_text(text, encoding="utf-8")
    for stale in older[3:]:
        stale.unlink(missing_ok=True)
    return text


def parse_history(log_text: str) -> dict[str, FileHistory]:
    histories: dict[str, FileHistory] = collections.defaultdict(FileHistory)
    for record in log_text.split("\0"):
        if not record.strip():
            continue
        header, _, body = record.partition("\n")
        fields = header.split("\t", 2)
        if len(fields) < 2:
            continue
        timestamp = float(fields[1])
        subject = fields[2] if len(fields) > 2 else ""
        changes = []
        for line in body.splitlines():
            parts = line.split("\t")
            if len(parts) != 3:
                continue
            added, removed, path = parts
            lines = (int(added) if added.isdigit() else 0) + (int(removed) if removed.isdigit() else 0)
            changes.append((path, lines))
        substantive = len(changes) <= SWEEP_FILES and not NON_SUBSTANTIVE_SUBJECT_RE.match(subject)
        for path, lines in changes:
            history = histories[path]
            history.commits += 1
            history.lines_changed += lines
            # The log is newest first: the first sighting is the latest, the last the earliest.
            history.last_touch = history.last_touch or timestamp
            history.created = timestamp
            if substantive:
                history.substantive_commits += 1
                history.last_substantive = history.last_substantive or timestamp
    return dict(histories)


# --------------------------------------------------------------------------------------------
# Graph


@dataclass
class Graph:
    nodes: dict[str, dict[str, Any]] = field(default_factory=dict)
    edges: dict[tuple[str, str], dict[str, Any]] = field(default_factory=dict)

    def node(self, key: str, **attrs: Any) -> None:
        self.nodes.setdefault(key, {}).update({k: v for k, v in attrs.items() if v is not None})

    def edge(self, source: str, target: str, kind: str, weight: int = 1) -> None:
        if source == target:
            return
        entry = self.edges.setdefault((source, target), {"kinds": collections.Counter(), "weight": 0})
        entry["kinds"][kind] += weight
        entry["weight"] += weight


def page_key(page: str) -> str:
    return "page:" + page


def build_graph(
    repo_root: Path,
    *,
    transcripts: bool,
    code: bool,
    reference: bool,
    history: dict[str, FileHistory] | None,
) -> Graph:
    docs_dir = repo_root / DOCS_RELATIVE
    all_pages = sorted(
        str(p.relative_to(docs_dir)) for p in docs_dir.rglob("*.md") if "assets" not in p.relative_to(docs_dir).parts
    )
    pages = [p for p in all_pages if reference or not p.startswith(REFERENCE_PREFIX)]
    page_set = set(pages)
    nav = load_nav(repo_root)
    graph = Graph()
    hooks = import_docs_hooks() if code or transcripts else None
    symbol_index = hooks.load_source_symbol_index(repo_root) if hooks else {}
    if hooks and not symbol_index:
        raise SystemExit("the symbol index read out of public/docs/reference is empty; is the reference generated?")
    tracked_src = {line for line in git(repo_root, "ls-files", "src").splitlines()} if code else set()
    transcript_dir = repo_root / TRANSCRIPTS_RELATIVE
    transcript_names = {p.stem for p in transcript_dir.glob("*.yaml")} if transcripts else set()

    def add_code_node(path: str) -> str:
        key = "code:" + path
        graph.node(key, kind="code", label=path.rsplit("/", 1)[-1], path=path, system=code_system(path))
        return key

    for page in pages:
        text = (docs_dir / page).read_text(encoding="utf-8", errors="replace")
        split = split_fences(text)
        title = H1_RE.search(text)
        trail = nav.get(page)
        is_reference = page.startswith(REFERENCE_PREFIX)
        system = "Reference" if is_reference else (trail[0] if trail else "(unlisted)")
        graph.node(
            page_key(page),
            kind="reference" if is_reference else "page",
            label=title.group("title") if title else page,
            path=f"{DOCS_RELATIVE}/{page}",
            system=system,
            navPath=" › ".join(trail) if trail else None,
            inNav=trail is not None,
            words=word_count(split.prose),
            fences=len(split.fences),
        )
        external = 0
        for target in link_targets(split.prose):
            if transcripts:
                transcript = TRANSCRIPT_LINK_RE.search(target)
                if transcript and transcript.group("name") in transcript_names:
                    graph.edge(page_key(page), "transcript:" + transcript.group("name"), "opens")
                    continue
            github = GITHUB_REPO_RE.match(target)
            if github:
                if code and github.group("path") in tracked_src:
                    graph.edge(page_key(page), add_code_node(github.group("path")), "cites")
                continue
            if "://" in target:
                external += 1
                continue
            resolved = resolve_page_target(target, page, page_set)
            if resolved is not None:
                graph.edge(page_key(page), page_key(resolved), "links")
        graph.nodes[page_key(page)]["externalLinks"] = external
        if code:
            for span in INLINE_CODE_RE.finditer(split.prose):
                repo_path = REPO_PATH_SPAN_RE.match(span.group("code").strip())
                if repo_path and repo_path.group("path") in tracked_src:
                    graph.edge(page_key(page), add_code_node(repo_path.group("path")), "cites")
                    continue
                for source, count in code_files_for(span.group("code"), symbol_index, mention=True).items():
                    graph.edge(page_key(page), add_code_node(source), "mentions", count)
            for language, fence_code in split.fences:
                if language not in SYMBOL_FENCES:
                    continue
                for source, count in code_files_for(fence_code, symbol_index).items():
                    graph.edge(page_key(page), add_code_node(source), "calls", count)

    if transcripts:
        import yaml

        for path in sorted(transcript_dir.glob("*.yaml")):
            try:
                data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            except yaml.YAMLError as error:
                print(f"skipping {path.name}: {error}", file=sys.stderr)
                continue
            key = "transcript:" + path.stem
            documented = [d.get("page") for d in data.get("docs") or [] if isinstance(d, dict)]
            documented = [d for d in documented if d in page_set]
            codes = list(strings_in(data.get("operations")))
            packages = sorted({m.group("pkg") for c in codes for m in IMPORT_RE.finditer(c)})
            owner_system = graph.nodes.get(page_key(documented[0]), {}).get("system") if documented else None
            graph.node(
                key,
                kind="transcript",
                label=path.stem,
                path=f"{TRANSCRIPTS_RELATIVE}/{path.name}",
                system=owner_system or "(transcripts)",
                boxes=sum(1 for op in data.get("operations") or [] if isinstance(op, dict) and "create" in op),
                imports=" ".join(packages),
                words=sum(len(c.split()) for c in codes),
            )
            for page in documented:
                graph.edge(key, page_key(page), "documents")
            if code:
                reached: collections.Counter = collections.Counter()
                for snippet in codes:
                    reached.update(code_files_for(snippet, symbol_index))
                for source, count in reached.items():
                    graph.edge(key, add_code_node(source), "calls", count)

    if code:
        api = api_size_by_file(repo_root)
        sections = reference_sections(repo_root)
        documented_by = collections.defaultdict(list)
        for ref_page, files in sections.items():
            for source in files:
                documented_by[source].append(ref_page)
        all_page_set = set(all_pages)
        for source, users in used_in_by_file(repo_root, all_page_set).items():
            for user in users:
                if page_key(user) in graph.nodes:
                    graph.edge(page_key(user), add_code_node(source), "usedIn")
        for source in set(api) | {n["path"] for n in graph.nodes.values() if n.get("kind") == "code"}:
            key = add_code_node(source)
            graph.node(key, inReference=source in api, apiSize=api.get(source, 0))
            if reference:
                for ref_page in documented_by.get(source, ()):
                    if page_key(ref_page) in graph.nodes:
                        graph.edge(page_key(ref_page), key, "reference")
        for key, attrs in graph.nodes.items():
            if attrs.get("kind") == "code":
                try:
                    attrs["lines"] = sum(1 for _ in (repo_root / attrs["path"]).open(encoding="utf-8", errors="replace"))
                except OSError:
                    attrs["lines"] = 0

    if history is not None:
        for attrs in graph.nodes.values():
            item = history.get(attrs.get("path", ""))
            if item is None:
                continue
            attrs.update(
                created=item.created,
                lastTouch=item.last_touch,
                lastEdit=item.last_substantive,
                commits=item.commits,
                substantiveCommits=item.substantive_commits,
            )
    return graph


def strings_in(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings_in(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings_in(item)


# --------------------------------------------------------------------------------------------
# Analysis


def to_networkx(graph: Graph) -> Any:
    import networkx as nx

    g = nx.DiGraph()
    for key, attrs in graph.nodes.items():
        g.add_node(key, **attrs)
    for (source, target), attrs in graph.edges.items():
        g.add_edge(source, target, weight=attrs["weight"], kinds=" ".join(sorted(attrs["kinds"])))
    return g


# --------------------------------------------------------------------------------------------
# Drawing


VIEW_EDGE_KINDS = ("links", "opens", "documents", "cites", "calls", "mentions", "reference", "usedIn")
VIEW_NODE_FIELDS = (
    "system", "navPath", "inNav", "words", "fences", "created", "lastEdit", "substantiveCommits", "inLinks",
    "outLinks", "pagerank", "codeDriftDays", "apiSize", "lines", "inReference",
)
VIEW_TEMPLATE = Path(__file__).with_name("docs_link_graph.html")
# Field's vendored three.js, inlined into graph.html so the page opens from disk with no server.
THREE_VENDOR_GLOB = "src/commonMain/resources/vendor/three/*/three.module.js"
# The live layout (d3-force-3d on WebGPU, CPU fallback): its d3 dependencies first, then the package.
FONT_DIR = "src/jsMain/resources/woff2"
FONTS = {"FONT_LIGHT": "iosevka-semicondensedlight.woff2", "FONT_SEMIBOLD": "iosevka-semicondensedsemibold.woff2"}
FORCE_VENDOR = "src/commonMain/resources/vendor/d3-force-3d-webgpu/3.2.0"
FORCE_SCRIPTS = ("deps/d3-dispatch.js", "deps/d3-timer.js", "deps/d3-quadtree.js", "deps/d3-binarytree.js",
                 "deps/d3-octree.js", "d3-force-3d-webgpu.js")


def stable_unit(key: str, salt: str) -> float:
    """A deterministic number in [-1, 1) for ``key``, so layouts do not move between runs."""
    import hashlib

    return int.from_bytes(hashlib.blake2b((salt + key).encode(), digest_size=4).digest(), "big") / 2**31 - 1.0


# What places a page: the links a reader can follow, and the transcripts a page opens. Code does
# not pull pages around; it is placed afterwards, outside, in the direction of whatever calls it.
CORE_WEIGHTS = {"links": 1.0, "opens": 1.0, "documents": 1.0}
# How strongly each edge kind says "this page uses this file", for aiming the code shell.
SHELL_WEIGHTS = {"cites": 1.0, "calls": 1.0, "mentions": 0.3}
CORE_ITERATIONS = 800
SHELL_GAP = 1.6  # shell radius, as a multiple of the core's 95th-percentile radius
FILE_SPACING = 0.13  # distance between neighbouring files in a directory's sunflower


def package_of(path: str) -> str:
    return "package:" + path.rsplit("/", 1)[0]


def parent_package(package: str) -> str | None:
    head = package.removeprefix("package:")
    return "package:" + head.rsplit("/", 1)[0] if head.count("/") >= 4 else None


def normalised(v: Any) -> Any:
    import numpy as np

    n = np.linalg.norm(v, axis=-1, keepdims=True)
    return v / np.where(n == 0, 1, n)


def fibonacci_directions(count: int) -> Any:
    import numpy as np

    i = np.arange(count) + 0.5
    y = 1 - 2 * i / max(count, 1)
    r = np.sqrt(np.clip(1 - y * y, 0, None))
    a = np.pi * (3 - np.sqrt(5)) * np.arange(count)
    return np.stack([r * np.cos(a), y, r * np.sin(a)], axis=1)


def layout(graph: Graph) -> tuple[dict[str, tuple[float, float, float]], dict[str, dict[str, Any]]]:
    """Starting 3D positions for ``graph.html`` (which then runs its own force simulation from
    them), and the directory nodes the code shell is built from.

    **The core** is the documentation: pages and transcripts, placed by ForceAtlas2 in LinLog
    mode with hub dissuasion (Noack's energy model, which pulls clusters apart instead of
    averaging them into a ball) on what a reader can follow — page links, and the transcripts a
    page opens. Never the nav: a section that comes out as one cluster does so because its pages
    link each other. Distances are scaled so linked pages sit about 1 apart.

    **The shell** is the code, on a sphere around the core. Each source directory is aimed at the
    pages and transcripts that cite or call its files, so a page's lines to its code run outward
    in its own direction; a directory nothing reaches takes its parent's direction, so unreached
    code sits beside its relatives rather than in a heap. Directories then push apart on the
    sphere, and each one's files spread as a small sunflower disc lying on it.

    Catalogue and reference pages, which list everything, sit at the centre of what they list.
    """
    import networkx as nx
    import numpy as np

    nodes = graph.nodes
    index_keys = {page_key(p) for p in INDEX_PAGES}
    late = index_keys | {k for k, a in nodes.items() if a["kind"] == "reference"}
    core_keys = sorted(k for k, a in nodes.items() if a["kind"] in ("page", "transcript") and k not in late)
    code_files = {k: a["path"] for k, a in nodes.items() if a["kind"] == "code"}

    core = nx.Graph()
    core.add_nodes_from(core_keys)
    for (s, t), attrs in graph.edges.items():
        if s in core and t in core:
            w = sum(CORE_WEIGHTS.get(kind, 0.0) for kind in attrs["kinds"])
            if w:
                core.add_edge(s, t, weight=core.get_edge_data(s, t, {"weight": 0.0})["weight"] + w)
    with np.errstate(invalid="ignore", divide="ignore"):  # networkx divides each node's 0 distance to itself
        raw = nx.forceatlas2_layout(core, max_iter=CORE_ITERATIONS, linlog=True, distributed_action=True, dim=3, weight="weight", seed=1)
    pos: dict[str, Any] = {k: np.asarray(v, dtype=float) for k, v in raw.items()}
    centre = np.mean([pos[k] for k in core_keys], axis=0) if core_keys else np.zeros(3)
    pages_linked = [float(np.linalg.norm(pos[s] - pos[t])) for s, t in core.edges]
    unit = float(np.median(pages_linked)) if pages_linked else 1.0
    pos = {k: (v - centre) / unit for k, v in pos.items()}

    # ---- the shell
    packages: dict[str, dict[str, Any]] = {}
    files_in: dict[str, list[str]] = collections.defaultdict(list)
    for key, path in sorted(code_files.items()):
        package = package_of(path)
        files_in[package].append(key)
        p: str | None = package
        while p is not None and p not in packages:
            packages[p] = {"kind": "package", "label": p.split("/", 4)[-1], "path": p.removeprefix("package:"), "files": 0}
            p = parent_package(p)
        packages[package]["files"] += 1
    aim: dict[str, Any] = {p: np.zeros(3) for p in packages}
    for (s, t), attrs in graph.edges.items():
        w = sum(SHELL_WEIGHTS.get(kind, 0.0) for kind in attrs["kinds"])
        if not w:
            continue
        for user, used in ((s, t), (t, s)):
            if used in code_files and user in pos:
                aim[package_of(code_files[used])] += w * normalised(pos[user])
    # Bottom-up, a directory also leans the way its subdirectories do; top-down, one with no
    # lean of its own borrows its parent's.
    for p in sorted(packages, key=lambda p: -p.count("/")):
        parent = parent_package(p)
        if parent in aim:
            aim[parent] += 0.5 * aim[p]
    roots = sorted(p for p in packages if parent_package(p) not in packages)
    spare = iter(fibonacci_directions(max(len(roots), 1)))
    for p in roots:
        if not np.any(aim[p]):
            aim[p] = next(spare)
    for p in sorted(packages, key=lambda p: p.count("/")):
        parent = parent_package(p)
        if not np.any(aim[p]) and parent in aim:
            aim[p] = aim[parent] + 0.15 * np.array([stable_unit(p, "x"), stable_unit(p, "y"), stable_unit(p, "z")])
    leaves = sorted(files_in)
    # A whisker of per-directory jitter: two directories aimed at exactly the same pages would
    # otherwise coincide, and coincident points have no direction to push apart in.
    jitter = np.array([[stable_unit(p, axis) for axis in "xyz"] for p in leaves]) * 1e-3
    target = normalised(normalised(np.array([aim[p] for p in leaves])) + jitter)
    # Relax on the sphere: each directory is pulled to its aim and pushed off its neighbours in
    # proportion to the disc its files need.
    core_radius = float(np.percentile([np.linalg.norm(pos[k]) for k in core_keys], 95)) if core_keys else 1.0
    shell = SHELL_GAP * core_radius
    disc = np.array([FILE_SPACING * math.sqrt(len(files_in[p])) + FILE_SPACING for p in leaves]) / shell
    d = target.copy()
    for step in range(300):
        diff = d[:, None, :] - d[None, :, :]
        dist = np.linalg.norm(diff, axis=2) + np.eye(len(d)) + 1e-9
        reach = disc[:, None] + disc[None, :]
        push = np.clip(reach - dist, 0, None) / dist
        np.fill_diagonal(push, 0)
        d = normalised(d + 0.5 * (push[:, :, None] * diff).sum(1) + 0.05 * (target - d))
    for p, direction in zip(leaves, d):
        pos[p] = direction * shell
        # The disc lies in the sphere's tangent plane at the directory.
        u = normalised(np.cross(direction, [0.0, 1.0, 0.0] if abs(direction[1]) < 0.9 else [1.0, 0.0, 0.0]))
        v = np.cross(direction, u)
        for i, key in enumerate(files_in[p]):
            r = FILE_SPACING * math.sqrt(i + 0.5)
            a = i * math.pi * (3 - math.sqrt(5))
            pos[key] = pos[p] + r * (math.cos(a) * u + math.sin(a) * v)
    for p in packages:  # an inner directory with no files of its own: the middle of what it holds
        if p not in pos:
            inside = [pos[q] for q in leaves if q.startswith(p + "/")]
            pos[p] = normalised(np.mean(inside, axis=0)) * shell if inside else normalised(aim[p]) * shell

    for key in sorted(late):
        around = [pos[t if s == key else s] for s, t in graph.edges if key in (s, t) and (t if s == key else s) in pos]
        pos[key] = np.mean(around, axis=0) if around else np.zeros(3)
    return {k: tuple(float(c) for c in pos.get(k, np.zeros(3))) for k in [*nodes, *packages]}, packages


def reference_anchors(repo_root: Path) -> dict[str, str]:
    """Source file → its section on the reference pages, as ``reference/<page>.md#<anchor>``."""
    hooks = import_docs_hooks()
    found: dict[str, str] = {}
    for page in sorted((repo_root / DOCS_RELATIVE / "reference").glob("*.md")):
        for section in page.read_text(encoding="utf-8").split("\n## ")[1:]:
            path = hooks.REFERENCE_SOURCE_PATH_RE.search(section)
            anchor = REFERENCE_ANCHOR_RE.match(section)
            if path and anchor:
                found.setdefault(path.group(1), f"{REFERENCE_PREFIX}{page.name}#{anchor.group('anchor')}")
    return found


def site_url(page: str, site_root: str) -> str:
    """A docs page's built URL (directory URLs: ``a/b.md`` → ``a/b/``, ``a/index.md`` → ``a/``)."""
    page, _, anchor = page.partition("#")
    stem = page.removesuffix(".md")
    url = stem.removesuffix("index") if stem == "index" or stem.endswith("/index") else stem + "/"
    return site_root + url + (f"#{anchor}" if anchor else "")


def site_hrefs(graph: Graph, repo_root: Path, site_root: str) -> dict[str, str]:
    """Where each node opens on the published site, from a page ``site_root`` below its root.

    Pages open themselves; a source file opens its section of the reference (GitHub when the
    reference has none); a transcript opens in the offline viewer when its document ships."""
    anchors = reference_anchors(repo_root)
    hooks = import_docs_hooks()
    github = f"{hooks.REPO_SOURCE_GITHUB}/blob/{hooks.REPO_SOURCE_BRANCH}/"
    hrefs: dict[str, str] = {}
    for key, attrs in graph.nodes.items():
        path = attrs.get("path") or ""
        if attrs["kind"] in ("page", "reference"):
            hrefs[key] = site_url(path.removeprefix(DOCS_RELATIVE + "/"), site_root)
        elif attrs["kind"] == "code":
            hrefs[key] = site_url(anchors[path], site_root) if path in anchors else github + path
        elif attrs["kind"] == "transcript":
            document = f"assets/agent_transcripts/{attrs['label']}/metadata.edn"
            if (repo_root / DOCS_RELATIVE / document).is_file():
                hrefs[key] = f"{site_root}offline.html?file={document}"
    return hrefs


def view_payload(
    graph: Graph, positions: dict[str, tuple[float, float, float]], packages: dict[str, dict[str, Any]], out_dir: Path,
    hrefs: dict[str, str] | None = None,
) -> dict[str, Any]:
    import os

    keys = list(graph.nodes)
    index = {k: i for i, k in enumerate(keys)}
    index_keys = {page_key(p) for p in INDEX_PAGES}
    view_nodes = []
    for key in keys:
        attrs = graph.nodes[key]
        x, y, z = positions[key]
        entry: dict[str, Any] = {"id": key, "label": attrs.get("label", key), "kind": attrs["kind"], "p": [round(x, 3), round(y, 3), round(z, 3)]}
        if key in index_keys:
            entry["index"] = True
        if hrefs is not None:
            if key in hrefs:
                entry["href"] = hrefs[key]
        elif attrs.get("path"):
            entry["href"] = Path(os.path.relpath(REPO_ROOT / attrs["path"], out_dir)).as_posix()
        for name in VIEW_NODE_FIELDS:
            value = attrs.get(name)
            if value is not None and not (isinstance(value, float) and math.isnan(value)):
                entry[name] = value
        view_nodes.append(entry)
    bit = {kind: 1 << i for i, kind in enumerate(VIEW_EDGE_KINDS)}
    links = [
        [index[s], index[t], sum(bit.get(kind, 0) for kind in attrs["kinds"])]
        for (s, t), attrs in graph.edges.items()
    ]
    view_packages = [
        {"id": key, "label": attrs["label"], "files": attrs["files"], "p": [round(c, 3) for c in positions[key]]}
        for key, attrs in sorted(packages.items()) if key in positions
    ]
    return {"edgeKinds": list(VIEW_EDGE_KINDS), "nodes": view_nodes, "links": links, "packages": view_packages, "generated": day(time.time())}


def write_view(graph: Graph, out_dir: Path, *, site_root: str | None = None, name: str = "graph.html") -> Path:
    """Write the 3D viewer. With ``site_root`` (the path from ``out_dir`` up to the built site's
    root, e.g. ``"../../"``) nodes link to the published pages instead of the checkout's files."""
    positions, packages = layout(graph)
    hrefs = site_hrefs(graph, REPO_ROOT, site_root) if site_root is not None else None
    view = view_payload(graph, positions, packages, out_dir, hrefs)
    view["site"] = site_root is not None
    payload = json.dumps(view, separators=(",", ":"), default=str)
    vendor = sorted(REPO_ROOT.glob(THREE_VENDOR_GLOB))
    if not vendor:
        raise SystemExit(f"graph.html needs Field's vendored three.js ({THREE_VENDOR_GLOB}) and found none")
    three = vendor[-1].parent
    html = VIEW_TEMPLATE.read_text(encoding="utf-8")
    for marker, path in (("THREE_CORE", "three.core.js"), ("THREE_MODULE", "three.module.js"), ("THREE_ORBIT", "addons/controls/OrbitControls.js")):
        html = html.replace(f"/*{marker}*/", (three / path).read_text(encoding="utf-8").replace("</script", "<\\/script"), 1)
    force = "\n".join((REPO_ROOT / FORCE_VENDOR / script).read_text(encoding="utf-8") for script in FORCE_SCRIPTS)
    html = html.replace("/*FORCE*/", force.replace("</script", "<\\/script"), 1)
    out_dir.mkdir(parents=True, exist_ok=True)
    # Field's own UI face (semi-condensed Iosevka, light and semibold): copied beside a published
    # map, which must not reach into the checkout; pointed at in the checkout otherwise.
    for marker, font in FONTS.items():
        source = REPO_ROOT / FONT_DIR / font
        if site_root is not None:
            (out_dir / "fonts").mkdir(exist_ok=True)
            shutil.copyfile(source, out_dir / "fonts" / font)
            url = f"fonts/{font}"
        else:
            url = Path(os.path.relpath(source, out_dir)).as_posix()
        html = html.replace(f"/*{marker}*/", url, 1)
    html = html.replace("/*GRAPH*/null", payload.replace("</", "<\\/"), 1)
    target = out_dir / name
    target.write_text(html, encoding="utf-8")
    return target


SITE_MAP_DIR = "assets/docs_map"


def write_site_map(site_dir: Path, repo_root: Path = REPO_ROOT) -> Path:
    """The published site's map (``docs_map.md`` embeds it): every page, transcript, reference
    page and reached source file, linking to the built pages. Written into a built site by
    ``tools/build_zensical_docs.py``. No git history: a clean checkout's full ``--numstat`` log
    takes minutes, and the age colourings are for us, not for a reader."""
    out_dir = site_dir / SITE_MAP_DIR
    graph = build_graph(repo_root, transcripts=True, code=True, reference=True, history=None)
    analyse(graph, out_dir, top=1, now=time.time(), options={"transcripts": True, "code": True, "reference": True})
    return write_view(graph, out_dir, site_root="../../")


def day(ts: float | None) -> str:
    return time.strftime("%Y-%m-%d", time.localtime(ts)) if ts else "—"


def link(attrs: dict[str, Any], out_dir: Path) -> str:
    label = str(attrs.get("label", "?")).replace("|", "\\|").replace("[", "(").replace("]", ")")
    path = attrs.get("path")
    if not path:
        return label
    relative = Path("../" * len(out_dir.relative_to(REPO_ROOT).parts)) / path if out_dir.is_relative_to(REPO_ROOT) else REPO_ROOT / path
    return f"[{label}]({relative.as_posix()})"


def table(headers: list[str], rows: list[list[Any]]) -> list[str]:
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return lines


def analyse(graph: Graph, out_dir: Path, *, top: int, now: float, options: dict[str, bool]) -> str:
    import networkx as nx

    g = to_networkx(graph)
    nodes = graph.nodes
    index_keys = {page_key(p) for p in INDEX_PAGES}
    pages = [k for k, a in nodes.items() if a["kind"] == "page" and k not in index_keys]
    doc_nodes = [k for k, a in nodes.items() if a["kind"] in ("page", "reference")]
    # The editorial graph: pages linking pages by hand. Catalogue pages and the generated
    # reference link everything and would hide every orphan; their links stay in graph.json.
    pg = nx.DiGraph()
    pg.add_nodes_from(pages)
    pg.add_edges_from((s, t) for s, t in g.subgraph(pages).edges())
    catalogued = {t: [s for s in g.predecessors(t) if s in index_keys or nodes[s]["kind"] == "reference"] for t in pages}
    code_nodes = [k for k, a in nodes.items() if a["kind"] == "code"]
    transcripts = [k for k, a in nodes.items() if a["kind"] == "transcript"]
    L = lambda key: link(nodes[key], out_dir)  # noqa: E731

    pagerank = nx.pagerank(pg, weight=None) if pg.number_of_edges() else {k: 0 for k in pg}
    try:
        hubs, authorities = nx.hits(pg, max_iter=500, normalized=True)
    except nx.PowerIterationFailedConvergence:
        hubs, authorities = {k: 0 for k in pg}, {k: 0 for k in pg}
    betweenness = nx.betweenness_centrality(pg.to_undirected(), k=min(len(pg), 400), seed=7)
    for key in pg:
        nodes[key].update(
            inLinks=pg.in_degree(key),
            outLinks=pg.out_degree(key),
            pagerank=round(pagerank.get(key, 0), 6),
            hub=round(hubs.get(key, 0), 6),
            authority=round(authorities.get(key, 0), 6),
            betweenness=round(betweenness.get(key, 0), 6),
        )

    out: list[str] = [
        "# Documentation link graph",
        "",
        f"Generated {day(now)} by `tools/docs_link_graph.py`"
        + "".join(f" `--{name}`" for name, on in options.items() if on)
        + ". Degrees count distinct linking pages; nav listing is not a link.",
        "",
        f"- {len(pages)} pages"
        + (f", {len(doc_nodes) - len(pages)} reference pages" if len(doc_nodes) > len(pages) else "")
        + f", {pg.number_of_edges()} hand-written page→page links (from pages other than "
        + ", ".join(INDEX_PAGES) + " and the reference, which link everything)",
    ]
    if transcripts:
        out.append(f"- {len(transcripts)} transcripts, {sum(1 for t in transcripts if g.out_degree(t) and any(nodes[v]['kind'] != 'code' for v in g.successors(t)))} attached to a page")
    if code_nodes:
        reached = [c for c in code_nodes if any(nodes[p]["kind"] in ("page", "transcript") for p in g.predecessors(c))]
        out.append(f"- {len(code_nodes)} source files in view, {len(reached)} reached by a page or transcript")
    out.append("")

    def ranked(metric: str, keys: list[str], n: int = top) -> list[str]:
        return sorted(keys, key=lambda k: nodes[k].get(metric, 0), reverse=True)[:n]

    # ---- Hubs and spokes
    out += ["## Hubs and authorities", "", "**Most linked-to** (authorities: where the site sends readers).", ""]
    out += table(
        ["page", "system", "in", "out", "PageRank"],
        [[L(k), nodes[k]["system"], nodes[k]["inLinks"], nodes[k]["outLinks"], f"{nodes[k]['pagerank']:.4f}"] for k in ranked("pagerank", pages)],
    )
    out += ["", "**Best hubs** (HITS hub score: pages that send readers to the authorities).", ""]
    out += table(
        ["page", "system", "out", "hub"],
        [[L(k), nodes[k]["system"], nodes[k]["outLinks"], f"{nodes[k]['hub']:.4f}"] for k in ranked("hub", pages)],
    )
    out += ["", "**Bridges** (betweenness: on the most shortest paths between other pages — remove one and the site falls apart into rooms).", ""]
    out += table(
        ["page", "system", "in", "out", "betweenness"],
        [[L(k), nodes[k]["system"], nodes[k]["inLinks"], nodes[k]["outLinks"], f"{nodes[k]['betweenness']:.4f}"] for k in ranked("betweenness", pages)],
    )

    orphans = [k for k in pages if nodes[k]["inLinks"] == 0]
    dead_ends = [k for k in pages if nodes[k]["outLinks"] == 0]
    spokes = [k for k in pages if nodes[k]["inLinks"] == 1]
    out += ["", "## Spokes, orphans and dead ends", ""]
    out += [f"**Orphans** — no other page links here ({len(orphans)}; unlisted ones are reachable only by URL):", ""]
    out += table(
        ["page", "system", "in nav", "only listed by", "out", "words"],
        [[L(k), nodes[k]["system"], "yes" if nodes[k]["inNav"] else "**no**",
          ", ".join(sorted({nodes[s]["path"].removeprefix(DOCS_RELATIVE + "/") for s in catalogued[k]})) or "nothing", nodes[k]["outLinks"], nodes[k]["words"]]
         for k in sorted(orphans, key=lambda k: (nodes[k]["inNav"], nodes[k]["system"]))],
    )
    out += ["", f"**Spokes** — exactly one page links here ({len(spokes)}), grouped by that one parent:", ""]
    by_parent = collections.defaultdict(list)
    for k in spokes:
        by_parent[next(iter(pg.predecessors(k)))].append(k)
    out += table(
        ["only parent", "spokes"],
        [[L(p), ", ".join(L(k) for k in sorted(ks))] for p, ks in sorted(by_parent.items(), key=lambda kv: -len(kv[1]))[:top]],
    )
    out += ["", f"**Dead ends** — link to no other page ({len(dead_ends)}):", ""]
    out += [", ".join(L(k) for k in sorted(dead_ends, key=lambda k: nodes[k]["system"])) or "none", ""]

    # ---- Systems
    systems = sorted({nodes[k]["system"] for k in pages})
    cross = collections.Counter()
    for source, target in pg.edges():
        cross[(nodes[source]["system"], nodes[target]["system"])] += 1
    out += ["## Systems", "", "A system is the top-level nav section a page is filed under. Rows link to columns.", ""]
    short = {s: "".join(w[0] for w in re.findall(r"[A-Za-z(]+", s))[:4] for s in systems}
    out += table(["from \\ to"] + [short[s] for s in systems], [[f"{s} ({short[s]})"] + [cross[(s, t)] or "·" for t in systems] for s in systems])
    out += ["", "**How much each system keeps to itself** — share of its links (both directions) that stay inside it.", ""]
    rows = []
    for s in systems:
        members = [k for k in pages if nodes[k]["system"] == s]
        internal = cross[(s, s)]
        outward = sum(v for (a, b), v in cross.items() if a == s and b != s)
        inward = sum(v for (a, b), v in cross.items() if b == s and a != s)
        total = internal + outward + inward
        partners = collections.Counter()
        for (a, b), v in cross.items():
            if a != b and s in (a, b):
                partners[b if a == s else a] += v
        rows.append([s, len(members), internal, outward, inward, f"{internal / total:.0%}" if total else "—",
                     ", ".join(f"{short[p]} {v}" for p, v in partners.most_common(3)) or "none",
                     day(max((nodes[k].get("created") or 0 for k in members), default=0) or None)])
    out += table(["system", "pages", "internal", "out", "in", "insular", "main partners", "newest page"], sorted(rows, key=lambda r: r[5], reverse=True))

    components = sorted(nx.weakly_connected_components(g.subgraph(pages)), key=len, reverse=True)
    if len(components) > 1:
        out += ["", f"**Islands** — {len(components)} weakly connected groups of pages; outside the main one:", ""]
        out += ["- " + ", ".join(L(k) for k in sorted(c)) for c in components[1:] if len(c) > 1][:top]
        singles = sorted(next(iter(c)) for c in components[1:] if len(c) == 1)
        if singles:
            out += [f"- {len(singles)} pages with no hand-written link in or out: " + ", ".join(L(k) for k in singles)]
    out.append("")

    # ---- Code
    if code_nodes:
        def readers(c: str) -> list[str]:
            return [p for p in g.predecessors(c) if nodes[p]["kind"] in ("page", "transcript")]

        out += ["## Code the documentation reaches", "",
                "A page or transcript reaches a file by citing it or calling a symbol only it declares.", ""]
        by_system = collections.defaultdict(list)
        for c in code_nodes:
            by_system[nodes[c]["system"]].append(c)
        rows = []
        for s, files in by_system.items():
            api_files = [c for c in files if nodes[c].get("inReference")]
            reached = [c for c in api_files if readers(c)]
            pages_reaching = {p for c in files for p in readers(c) if nodes[p]["kind"] == "page"}
            rows.append([s, len(api_files), len(reached), f"{len(reached) / len(api_files):.0%}" if api_files else "—",
                         len(pages_reaching), sum(nodes[c].get("apiSize", 0) for c in api_files if not readers(c))])
        out += table(["code system", "files with API", "reached", "coverage", "pages", "unreached declarations"], sorted(rows, key=lambda r: -r[5]))
        unreached = sorted((c for c in code_nodes if nodes[c].get("inReference") and not readers(c)), key=lambda c: -nodes[c].get("apiSize", 0))
        out += ["", f"**Largest unreached API** — public declarations no page or transcript calls ({len(unreached)} files):", ""]
        out += table(["file", "system", "declarations", "lines", "last edit"],
                     [[L(c), nodes[c]["system"], nodes[c].get("apiSize", 0), nodes[c].get("lines", 0), day(nodes[c].get("lastEdit"))] for c in unreached[:top]])
        most = sorted(code_nodes, key=lambda c: -len(readers(c)))[:top]
        out += ["", "**Most-read code** — files the most pages and transcripts reach:", ""]
        out += table(["file", "pages", "transcripts"], [[L(c), sum(nodes[p]["kind"] == "page" for p in readers(c)), sum(nodes[p]["kind"] == "transcript" for p in readers(c))] for c in most])
        out.append("")

    # ---- Transcripts
    if transcripts:
        unattached = [t for t in transcripts if not any(nodes[v]["kind"] in ("page", "reference") for v in g.successors(t))]
        unopened = [t for t in transcripts if not any(nodes[u]["kind"] == "page" for u in g.predecessors(t))]
        pages_without = [p for p in pages if not any(nodes[u]["kind"] == "transcript" for u in g.predecessors(p)) and nodes[p]["fences"] > 0]
        out += ["## Transcripts", "",
                f"- {len(unattached)} transcripts name no docs page: " + ", ".join(L(t) for t in sorted(unattached)),
                f"- {len(unopened)} transcripts no page links to (no *Browse the starting point*)",
                f"- {len(pages_without)} pages with code fences that no transcript documents", ""]

    # ---- Length
    measured = sorted(pages, key=lambda k: nodes[k]["words"])
    out += ["## Length", "", f"Prose words, fences excluded. Median {nodes[measured[len(measured) // 2]]['words']}.", "",
            "**Shortest** (thin, or a stub waiting for its page):", ""]
    out += table(["page", "system", "words", "fences", "in"], [[L(k), nodes[k]["system"], nodes[k]["words"], nodes[k]["fences"], nodes[k]["inLinks"]] for k in measured[:top]])
    out += ["", "**Longest** (candidates to split, if they hold more than one topic):", ""]
    out += table(["page", "system", "words", "fences", "out"], [[L(k), nodes[k]["system"], nodes[k]["words"], nodes[k]["fences"], nodes[k]["outLinks"]] for k in measured[::-1][:top]])
    out.append("")

    # ---- Age
    dated = [k for k in pages if nodes[k].get("lastEdit")]
    if dated:
        out += ["## Age", "", f"\"Edited\" ignores sweeps (commits touching more than {SWEEP_FILES} files) and regenerations.", ""]
        out += ["**Longest untended** — oldest last substantive edit:", ""]
        out += table(["page", "system", "created", "last edit", "edits", "in"],
                     [[L(k), nodes[k]["system"], day(nodes[k].get("created")), day(nodes[k]["lastEdit"]), nodes[k].get("substantiveCommits", 0), nodes[k]["inLinks"]]
                      for k in sorted(dated, key=lambda k: nodes[k]["lastEdit"])[:top]])
        if code_nodes:
            drift = []
            for k in dated:
                # Citations and fence calls only: a prose code span ("`dir`") names a file by accident.
                cited = [c for c in g.successors(k) if nodes[c]["kind"] == "code" and nodes[c].get("lastEdit")
                         and ({"cites", "calls"} & set(graph.edges[(k, c)]["kinds"]))]
                if not cited:
                    continue
                newest = max(cited, key=lambda c: nodes[c]["lastEdit"])
                lag = (nodes[newest]["lastEdit"] - nodes[k]["lastEdit"]) / DAY
                moved = sum(1 for c in cited if nodes[c]["lastEdit"] > nodes[k]["lastEdit"])
                if lag > 0:
                    nodes[k]["codeDriftDays"] = round(lag, 1)
                    drift.append((lag, k, newest, moved, len(cited)))
            out += ["", "**Code moved on** — source the page cites or its fences call has been edited since the page was:", ""]
            out += table(["page", "last edit", "files moved / cited", "newest code edit", "lag (days)"],
                         [[L(k), day(nodes[k]["lastEdit"]), f"{moved}/{n}", f"{L(c)} {day(nodes[c]['lastEdit'])}", f"{lag:.0f}"]
                          for lag, k, c, moved, n in sorted(drift, reverse=True)[:top]])
        never = []
        for k in dated:
            created = nodes[k].get("created") or 0
            if now - created < 30 * DAY:
                continue
            newer = [u for u in pg.predecessors(k) if (nodes[u].get("created") or 0) > created + 7 * DAY]
            nodes[k]["newerInLinks"] = len(newer)
            if not newer:
                never.append(k)
        out += ["", f"**Not integrated** — over 30 days old and no page written since links to it ({len(never)}):", ""]
        out += table(["page", "system", "created", "in", "last edit"],
                     [[L(k), nodes[k]["system"], day(nodes[k].get("created")), nodes[k]["inLinks"], day(nodes[k]["lastEdit"])]
                      for k in sorted(never, key=lambda k: nodes[k].get("created") or 0)[:top]])
        out.append("")
    return "\n".join(line for line in out if line is not None) + "\n"


def write_outputs(graph: Graph, out_dir: Path, report: str) -> None:
    import networkx as nx

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "report.md").write_text(report, encoding="utf-8")
    payload = {
        "nodes": [{"id": k, **a} for k, a in graph.nodes.items()],
        "links": [{"source": s, "target": t, "weight": a["weight"], "kinds": dict(a["kinds"])} for (s, t), a in graph.edges.items()],
    }
    (out_dir / "graph.json").write_text(json.dumps(payload, indent=1, default=str), encoding="utf-8")
    g = to_networkx(graph)
    for _, attrs in g.nodes(data=True):
        for key, value in list(attrs.items()):
            if value is None or (isinstance(value, float) and math.isnan(value)):
                del attrs[key]
            elif isinstance(value, bool):
                attrs[key] = int(value)
    nx.write_graphml(g, out_dir / "graph.graphml")
    write_view(graph, out_dir)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--transcripts", action="store_true", help="include agent transcripts")
    parser.add_argument("--code", action="store_true", help="include source files reached by pages and transcripts")
    parser.add_argument("--reference", action="store_true", help="include the generated reference pages")
    parser.add_argument("--all", action="store_true", help="--transcripts --code --reference")
    parser.add_argument("--no-history", action="store_true", help="skip git history (no age section)")
    parser.add_argument("--top", type=int, default=25, help="rows per table (default 25)")
    parser.add_argument("--out", default=DEFAULT_OUT, help=f"output directory (default {DEFAULT_OUT})")
    parser.add_argument("--repo-root", default=str(REPO_ROOT), help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.all:
        args.transcripts = args.code = args.reference = True
    repo_root = Path(args.repo_root).resolve()
    out_dir = Path(args.out)
    out_dir = (out_dir if out_dir.is_absolute() else repo_root / out_dir).resolve()
    history = None if args.no_history else parse_history(cached_git_log(repo_root, out_dir, quiet=False))
    graph = build_graph(repo_root, transcripts=args.transcripts, code=args.code, reference=args.reference, history=history)
    options = {"transcripts": args.transcripts, "code": args.code, "reference": args.reference}
    report = analyse(graph, out_dir, top=args.top, now=time.time(), options=options)
    write_outputs(graph, out_dir, report)
    print(f"wrote {out_dir / 'report.md'}, graph.html, graph.json, graph.graphml ({len(graph.nodes)} nodes, {len(graph.edges)} edges)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
