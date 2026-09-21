#!/usr/bin/env python3
"""Check every *relative file* link in the docs against the filesystem.

Why this exists: on 2026-09-18 the twenty-second firing found `AGENT_GUIDE.md` referenced nine
times across six files. That file has never existed in this repository. `check_anchors.py` could
not see any of it, because it checks the `#fragment` half of a link and skips a link that has no
fragment at all. `citationGates` checks `file.kt:NNN` citations. Nothing checks whether
`](some/page.md)` names a file on disk.

So this is one `os.path.exists` per relative link target, plus the two rules MkDocs/Zensical adds:

  * `](dir/)` and `](dir)` resolve to `dir/index.md` (or the directory itself, for an asset dir).
  * a link may name a page with or without its `.md`, so `](faust)` finds `faust.md`.

What is deliberately NOT checked, and why:

  * external URLs (`http:`, `https:`, `mailto:`, `tel:`, `//host`) -- not ours, and a network
    check in a docs gate is a flake generator.
  * site-absolute links (`](/x)`), which resolve against the built site root and not the source
    tree. They are reported separately under --absolute so a firing can eyeball them, because the
    house style is relative and a stray absolute is usually a mistake -- but they are not failures.
  * bare `](#slug)` -- that is `check_anchors.py`'s job.
  * anything inside a ``` / ~~~ fence, an indented code block, or an inline `code span`. A link
    inside code is an illustration of syntax, not a link. `patterns.md:2508`'s
    `pat("[bd cp](3,8) hh!2")` is the reason the indented case is handled: Euclidean-rhythm
    syntax is link-shaped.
  * targets the docs *build* creates, listed in GENERATED below. They are absent from the source
    tree by design and present in the built site.

Calibration: on 2026-09-18 this script's first run reported 10151 of 14281 dead. Every one was a
detector bug -- 10020 generated source-browser links, 130 generated `offline.html` viewer embeds,
1 Euclidean rhythm. If a run reports dead links in the thousands, suspect this file first.

THIS IS A SOURCE-LEVEL CHECK AND IT IS NOT THE AUTHORITY. Later the same night, `docsGates`'
`tools/check_doc_site_links.py` -- which reads the *rendered HTML* of the built site -- failed on
`/Field4/reference/3,8`, written as `../3,8` from `patterns.md:2508`. That is the very Euclidean
rhythm this file skips as an indented code block, and plain python-markdown agrees with the skip
(rendering `patterns.md` yields zero hrefs containing `3,8`). Zensical does not. So a link can be
code at source, code under python-markdown, and a live dead link on the published page.

Use this script for what it is good at -- a fast, build-free check that a referenced *file* exists,
which is how nine `AGENT_GUIDE.md` references survived every other gate. Do not read a clean run
here as "the published site has no dead links": only `check_doc_site_links.py` against a real build
can say that, and it is already wired into `docsGates`.

Usage:
    python3 check_links.py public/docs            # every page under a root
    python3 check_links.py public/docs templates  # several roots
    python3 check_links.py --absolute public/docs # also list site-absolute links
"""

import pathlib
import re
import sys
import urllib.parse

FENCE = re.compile(r"^\s*(```|~~~)")
# Inline code spans, longest-run-first so ``a ` b`` is one span and not two.
CODE_SPAN = re.compile(r"(`+)(?:(?!\1).)*\1")
# Inline links and images: `](target)` / `](target "title")`. Angle-bracket targets too.
INLINE_LINK = re.compile(r"\]\(\s*(<[^>]*>|[^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)")
# Reference definitions: `[label]: target "title"` at the head of a line.
REF_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(<[^>]*>|\S+)")

EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//)", re.I)
LIST_ITEM = re.compile(r"^\s{0,3}(?:[-*+]\s|\d+[.)]\s)")

# Template sources are written from the point of view of the workspace they are seeded INTO, not
# of `templates/`. `ensureAgentGuides` (saving.kt:1748) copies WORKSPACE_README.md -> README.md and
# WORKSPACE_AGENTS.md -> AGENTS.md side by side, so `./AGENTS.md` in WORKSPACE_README.md is correct
# at every one of the 243 destinations and dead only at the source. Checking these would need the
# seeded name map; the 243 copies are checked directly instead, which is the stronger check.
DESTINATION_RELATIVE = ("templates/WORKSPACE_README.md", "templates/WORKSPACE_AGENTS.md")

# Paths the docs build writes into the site and the source tree therefore does not carry.
# `reference/source/`: `generateCodebaseReference`, via build_zensical_docs.py:501-522.
# `offline.html`: the read-only viewer bundle, copied in at build_zensical_docs.py:691-695.
GENERATED = ("reference/source/", "offline.html")


def targets_of(path):
    """Every link target on the page, as (lineno, raw_target)."""
    out = []
    in_fence = False
    # An indented code block is 4+ spaces of indent that is *not* the continuation of a list
    # item, so track the content indent of the open list: a line indented less than 4 past it is
    # continuation (prose), a line indented 4 or more past it is code. `None` means no list.
    # Tracking a bare boolean instead is not enough -- on a page whose whole body sits at indent
    # 4+ (the generated `reference/` pages do), a list opened once never closes and every code
    # block after it gets scanned. That is how `patterns.md`'s `pat("[bd cp](3,8) ...")` read as
    # a link to `3,8`.
    list_indent = None
    for i, ln in enumerate(path.read_text(encoding="utf-8", errors="replace").split("\n"), 1):
        if FENCE.match(ln):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if ln.strip() == "":
            continue
        indent = len(ln) - len(ln.lstrip(" "))
        marker = LIST_ITEM.match(ln)
        if marker:
            list_indent = len(marker.group(0))
        elif indent < 4:
            list_indent = None
        if not marker and indent >= 4 and (list_indent is None or indent >= list_indent + 4):
            continue   # indented code block
        # Blank out inline code spans, keeping column count irrelevant (we only report lines).
        bare = CODE_SPAN.sub(lambda m: " " * len(m.group(0)), ln)
        for m in INLINE_LINK.finditer(bare):
            out.append((i, m.group(1)))
        m = REF_DEF.match(bare)
        if m:
            out.append((i, m.group(1)))
    return out


def resolve(page, target, root):
    """Return (kind, path_or_None).

    kind is 'ok', 'dead', 'external', 'absolute', 'anchor' or 'generated'.
    """
    target = target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    # Drop the fragment and any query; `check_anchors.py` owns fragments.
    target = target.split("#", 1)[0].split("?", 1)[0]
    if target == "":
        return "anchor", None
    if EXTERNAL.match(target):
        return "external", None
    if target.startswith("/"):
        return "absolute", target
    who = urllib.parse.unquote(target)
    here = page.parent
    cand = (here / who)
    try:
        rel = cand.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        rel = ""
    if any(rel == g or rel.startswith(g) for g in GENERATED):
        return "generated", cand
    # MkDocs page resolution, in the order it tries them.
    tries = [cand]
    if who.endswith("/"):
        tries.append(cand / "index.md")
    else:
        tries += [cand.with_name(cand.name + ".md"), cand / "index.md"]
    for t in tries:
        if t.exists():
            return "ok", t
    return "dead", cand


def main(argv):
    show_absolute = "--absolute" in argv
    roots = [a for a in argv[1:] if not a.startswith("-")]
    if not roots:
        print(__doc__)
        return 2

    pages = []
    for r in roots:
        p = pathlib.Path(r)
        root = p if p.is_dir() else p.parent
        pages += [(q, root) for q in (sorted(p.rglob("*.md")) if p.is_dir() else [p])]

    dead, absolute, checked, generated = [], [], 0, 0
    seeded = 0
    for page, root in pages:
        if page.as_posix().endswith(DESTINATION_RELATIVE):
            seeded += 1
            continue
        for lineno, raw in targets_of(page):
            kind, where = resolve(page, raw, root)
            if kind in ("external", "anchor"):
                continue
            if kind == "generated":
                generated += 1
                continue
            if kind == "absolute":
                absolute.append((page, lineno, raw))
                continue
            checked += 1
            if kind == "dead":
                dead.append((page, lineno, raw))

    for page, lineno, raw in dead:
        print("%s:%d  dead link -> %s" % (page, lineno, raw))
    print("\n%d relative link target(s) checked across %d page(s), %d dead"
          % (checked, len(pages), len(dead)))
    if generated:
        print("%d build-generated target(s) skipped (%s)" % (generated, ", ".join(GENERATED)))
    if seeded:
        print("%d template source(s) skipped; their links resolve at the seeded destination"
              % seeded)
    if show_absolute:
        print("\n%d site-absolute link(s) (not checked against the source tree):" % len(absolute))
        for page, lineno, raw in absolute:
            print("  %s:%d  %s" % (page, lineno, raw))
    elif absolute:
        print("%d site-absolute link(s) skipped; pass --absolute to list them" % len(absolute))
    if not dead:
        print("every relative link target resolves")
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
