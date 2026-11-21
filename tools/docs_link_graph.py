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
  file declares in its code fences; a transcript reaches one through the code of its boxes.
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
import re
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

FENCE_OPEN_RE = re.compile(r"^(?P<indent>[ \t]*)(?P<fence>`{3,}|~{3,})\s*(?P<lang>[^\s{`]*)")
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
NON_SYMBOL_FENCES = frozenset({"bash", "sh", "shell", "console", "zsh", "text", "txt", "json", "yaml", "yml", "edn", "toml", "mermaid", "d2"})
REFERENCE_PREFIX = "reference/"
# Catalogues: they link (nearly) every page by construction, so they are left out of the editorial
# degree counts. changelog.md is written from main's history; agent_transcripts.md lists every
# transcript's page.
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
        language = match.group("lang").strip().lower()
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


def code_files_for(code: str, symbol_index: dict[str, tuple[str, ...]]) -> collections.Counter:
    """Source file → number of distinct indexed symbols ``code`` names from it."""
    reached: collections.Counter = collections.Counter()
    for token in set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", code)):
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
                for source, count in code_files_for(span.group("code"), symbol_index).items():
                    graph.edge(page_key(page), add_code_node(source), "mentions", count)
            for language, fence_code in split.fences:
                if language in NON_SYMBOL_FENCES:
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
    print(f"wrote {out_dir / 'report.md'}, graph.json, graph.graphml ({len(graph.nodes)} nodes, {len(graph.edges)} edges)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
