#!/usr/bin/env python3
"""Does a generated reference page still match the KDoc it was generated from?

The voice pass edits KDoc and then runs `./gradlew generateCodebaseReference` to
republish the `.md`. That costs a Gradle run, and a firing under the disk floor
cannot afford one -- which has meant, until now, that a night's work either
skipped the generated surface or shipped a page whose relationship to its source
was unverified.

This checks the relationship directly, from the two files, with no build.

For each file-overview block it compares the de-prefixed KDoc against the page
section that publishes it, line for line. A difference is reported unless one of
the generator's two documented transformations explains it:

  - a KDoc `[Symbol]` it can resolve becomes a markdown link;
  - a KDoc `[Symbol]` it cannot resolve becomes a bare code span, silently. That
    second one is worth counting rather than only tolerating, so it is reported
    as a `deadlink` tally: every one is a link the author meant to make and a
    reader does not get.

Everything else about the body is a verbatim copy -- the line breaks, the list
indentation, the emphasis, the backticks -- so anything else is a mismatch.

    check_kdoc_mirror.py <page.md> <source.kt> [<source.kt> ...]
    check_kdoc_mirror.py --sweep [public/docs/reference] [src]

Exit status is 1 if any section mismatches, so it works as a gate. A file whose
overview does not reach the page at all is reported too: that is the
blank-line-before-`package` fault, where the generator reads the overview as the
first declaration's member KDoc and publishes it somewhere else or nowhere.
"""

import re
import sys
from pathlib import Path

# The generator publishes the file name as an H2 and flattens every heading inside
# an overview to a single H3, whatever level the KDoc used. Checked against three
# source levels that all land on `###`: `#` (dynamic_graph_field.kt), `##`
# (alife_script.kt) and `####` (GlslAd.kt). So an overview's heading hierarchy is
# not published -- worth knowing before nesting one.
PAGE_HEADING_LEVEL = 3

# A bare ``` in an overview is published with the house fence treatment, per
# `DocsFenceTreatmentTest`, so the info string is the generator's and not the
# author's. Compared as equal on both sides.
FENCE = re.compile(r"^(\s*)```.*$")

LINK = re.compile(r"\[(?:`)?([A-Za-z_][\w.]*(?:\([^)]*\))?)(?:`)?\]\([^)]*\)")
KDOC_LINK = re.compile(r"\[([A-Za-z_][\w.]*(?:\([^)]*\))?)\]")


def file_overview(source: Path) -> list[str] | None:
    """The file-overview KDoc, de-prefixed and with its headings demoted.

    The discriminator is a **blank line after the closing `*/`**, not the position
    of `package`. `extractKDocs` treats a KDoc flush against the next declaration
    as that declaration's member documentation, so the blank line is the whole
    difference between an overview that publishes as the page's lead and one that
    publishes as a member's summary -- or, for an `internal` declaration, nowhere.
    That is the fault `3832b137b` repaired in four files at once, and a checker
    that inferred the overview from `package` instead would not see it.

    Returns None when the first KDoc block is flush against its declaration, which
    is the same answer the generator gives: this file has no overview.
    """
    lines = source.read_text().splitlines()
    i = 0
    while i < len(lines):
        stripped = lines[i].lstrip()
        if stripped.startswith("/**"):
            # find the end of this block, tolerating a single-line `/** ... */`
            if stripped.rstrip().endswith("*/") and len(stripped) > 5:
                end = i
            else:
                end = None
                for j in range(i + 1, len(lines)):
                    if lines[j].strip().endswith("*/"):
                        end = j
                        break
                if end is None:
                    return None
            after = lines[end + 1] if end + 1 < len(lines) else ""
            if after.strip() == "":
                return _body(lines[i:end])
            # flush against a declaration: a member KDoc, so there is no overview
            return None
        i += 1
    return None


def _body(block: list[str]) -> list[str]:
    # de-prefix first, so a `#` comment inside a fence can be told from a heading
    plain = []
    for raw in block[1:]:  # drop the `/**`
        line = raw.rstrip()
        stripped = line.lstrip()
        if stripped.startswith("*"):
            line = stripped[1:]
            line = line[1:] if line.startswith(" ") else line
        plain.append(line.rstrip())

    # Which lines are headings, and which are `#` comments inside a code fence --
    # `RectSLFit.kt`'s overview has a fenced `# a bar whose height is unknown`.
    fenced, heading_at = False, {}
    for i, line in enumerate(plain):
        if FENCE.match(line):
            fenced = not fenced
            continue
        if fenced:
            continue
        m = re.match(r"^(#+)\s", line)
        if m:
            heading_at[i] = len(m.group(1))

    # The generator anchors the shallowest heading in an overview at `###` and
    # keeps the relative depth below it, so a `##`/`###` pair publishes as
    # `###`/`####`. Verified over four source levels across the reference surface.
    shift = PAGE_HEADING_LEVEL - min(heading_at.values()) if heading_at else 0
    out = []
    for i, line in enumerate(plain):
        # KDoc block tags are trailing and are not published as overview prose --
        # `@see` becomes a cross-reference elsewhere on the page, not a line here.
        if re.match(r"^@\w+", line):
            break
        if i in heading_at:
            level = max(1, heading_at[i] + shift)
            line = "#" * level + line[heading_at[i]:]
        out.append(line)
    while out and not out[0]:
        out.pop(0)
    while out and not out[-1]:
        out.pop()
    return out


def page_section(page_lines: list[str], stem: str) -> list[str] | None:
    head = re.compile(r"^## " + re.escape(stem) + r"(\s|$|\s*\{)")
    try:
        start = next(i for i, l in enumerate(page_lines) if head.match(l))
    except StopIteration:
        return None
    body = []
    for line in page_lines[start + 1:]:
        if line.startswith("## "):
            break
        # the generator's trailing provenance line closes every section
        if re.match(r"^`src/.*` — \[read the source\]", line):
            break
        body.append(line.rstrip())
    while body and not body[0]:
        body.pop(0)
    while body and not body[-1]:
        body.pop()
    return body


def normalise(line: str) -> str:
    """Collapse both sides' link syntax to the bare symbol name."""
    if FENCE.match(line):
        return FENCE.sub(r"\1```", line)
    line = LINK.sub(r"\1", line)
    line = KDOC_LINK.sub(r"\1", line)
    return line


def undecorate(line: str) -> str:
    """As `normalise`, and also drop a code span's backticks.

    Equal here but not under `normalise` means the generator could not resolve a
    `[Symbol]` and published it as `` `Symbol` ``.
    """
    return normalise(line).replace("`", "")


def sweep(root: Path, src_root: Path) -> int:
    """Every published overview under `root`, against the source that owns it.

    Resolves a `## <name>.kt` section to its source by file name, which is exact
    for all but a handful of duplicated names (reported, not guessed at).
    """
    import collections

    byname: dict[str, list[Path]] = collections.defaultdict(list)
    for p in src_root.rglob("*.kt"):
        byname[p.name].append(p)

    checked = exact = mismatch = skipped = ambiguous = 0
    linked = unlinked = 0
    deadby: collections.Counter = collections.Counter()
    bad = []
    for page in sorted(root.glob("*.md")):
        lines = page.read_text().splitlines()
        for line in lines:
            m = re.match(r"^## (\S+\.kt)", line)
            if not m:
                continue
            name = m.group(1)
            cands = byname.get(name, [])
            if len(cands) != 1:
                ambiguous += 1
                continue
            kdoc = file_overview(cands[0])
            section = page_section(lines, name)
            if kdoc is None or section is None:
                skipped += 1
                continue
            checked += 1
            diffs = 0
            for a, b in zip(kdoc, section):
                for sym in KDOC_LINK.findall(a):
                    if re.search(r"\[`?" + re.escape(sym) + r"`?\]\(", b):
                        linked += 1
                    else:
                        unlinked += 1
                        deadby[name] += 1
                if a == b or normalise(a) == normalise(b) or undecorate(a) == undecorate(b):
                    continue
                diffs += 1
            if diffs or len(kdoc) != len(section):
                mismatch += 1
                bad.append((page.name, name, diffs, len(kdoc), len(section)))
            else:
                exact += 1

    print(f"{checked} published file overview(s) across {len(list(root.glob('*.md')))} page(s)")
    print(f"  mirror exactly : {exact}")
    print(f"  MISMATCH       : {mismatch}")
    for p, name, d, lk, lp in bad[:20]:
        print(f"      {p} :: {name} -- {d} line(s) differ, kdoc {lk} vs page {lp}")
    print(f"  skipped        : {skipped} with no overview or no section, "
          f"{ambiguous} with a duplicated file name")
    total = linked + unlinked
    if total:
        print(f"\n  [Symbol] cross-references written in those overviews: {total}")
        print(f"    resolved into a link : {linked} ({linked / total * 100:.0f}%)")
        print(f"    published unlinked   : {unlinked} ({unlinked / total * 100:.0f}%), "
              f"in {len(deadby)} file(s)")
        print("    worst: " + ", ".join(f"{k} {v}" for k, v in deadby.most_common(8)))
    return 1 if mismatch else 0


# --- the "Used in" evidence index, which the mirror sweep cannot see -------------------------
#
# `checkCodebaseReference` has two halves. The mirror sweep above covers the file-overview prose.
# The other half is the reference's **"Used in" evidence index**, and it is scored by
# `StdlibReferenceGenerator.kt`'s own `codeTextOf`:
#
#     private fun codeTextOf(markdown: String): String {
#         val fences = FENCED_BLOCK.findAll(markdown).map { it.value }.toList()
#         val prose  = FENCED_BLOCK.replace(markdown, " ")
#         val inline = INLINE_CODE.findAll(prose).map { it.value }
#                          .filterNot { FILENAME_SPAN.matches(it) }.toList()
#         return (fences + inline).joinToString("\n")
#     }
#
# Fences plus inline spans; **prose discarded**. So a prose-only docs edit cannot move the index,
# and that is provable without Gradle by extracting the same two lists from the committed blob and
# from the working file and comparing them. Asked for by the twenty-fifth firing (2026-09-18),
# which established the argument and ran it by hand; this is that check, kept.
#
# It matters most where a prose edit changes bare words that are *also* symbol names.
FENCED_BLOCK = re.compile(r"```.*?```", re.S)
INLINE_CODE = re.compile(r"`[^`\n]+`")
FILENAME_SPAN = re.compile(r"^`[\w./-]+\.(kt|md|kts|py|json|yaml|yml|glsl)`$")


def code_text(markdown: str) -> list[str]:
    """Reproduce `StdlibReferenceGenerator.codeTextOf`, as a list rather than a joined string."""
    fences = [m.group(0) for m in FENCED_BLOCK.finditer(markdown)]
    prose = FENCED_BLOCK.sub(" ", markdown)
    inline = [m.group(0) for m in INLINE_CODE.finditer(prose)
              if not FILENAME_SPAN.match(m.group(0))]
    return fences + inline


def check_code_text(ref: str, paths: list[Path]) -> int:
    """Compare each path's code text against the same file at a git ref. 0 if none moved."""
    import subprocess

    bad = 0
    for p in paths:
        try:
            before = subprocess.run(["git", "show", f"{ref}:{p}"], capture_output=True,
                                    text=True, check=True).stdout
        except subprocess.CalledProcessError:
            print(f"NEW               {p}: not at {ref}, nothing to compare")
            continue
        a, b = code_text(before), code_text(p.read_text(errors="replace"))
        if a == b:
            nf = sum(1 for x in a if x.startswith("```"))
            print(f"ok                {p}: {nf} fence(s), {len(a) - nf} inline span(s), identical")
            continue
        bad += 1
        sa, sb = set(a), set(b)
        print(f"CODE TEXT MOVED   {p}: {len(a)} -> {len(b)} item(s)")
        for x in sorted(sa - sb)[:8]:
            print(f"    only at {ref}: {x[:100]}")
        for x in sorted(sb - sa)[:8]:
            print(f"    only now      : {x[:100]}")
    if bad:
        print(f"\n{bad} file(s) changed their code text, so the reference's name-matched "
              f'"Used in" index can move. This needs `./gradlew checkCodebaseReference`.')
    else:
        print(f"\nevery file's code text is identical to {ref}, so the name-matched "
              f'"Used in" evidence index cannot have moved')
    return 1 if bad else 0


def main() -> int:
    if len(sys.argv) >= 3 and sys.argv[1] == "--code-text":
        return check_code_text(sys.argv[2], [Path(a) for a in sys.argv[3:]])
    if len(sys.argv) >= 2 and sys.argv[1] == "--sweep":
        root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("public/docs/reference")
        src_root = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("src")
        return sweep(root, src_root)
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    page = Path(sys.argv[1])
    page_lines = page.read_text().splitlines()
    bad = 0
    for arg in sys.argv[2:]:
        source = Path(arg)
        kdoc = file_overview(source)
        section = page_section(page_lines, source.name)
        if kdoc is None:
            print(f"MISSING OVERVIEW  {source}: no file-overview KDoc found")
            bad += 1
            continue
        if section is None:
            print(f"UNPUBLISHED       {source.name}: no `## {source.name}` section on {page.name}")
            bad += 1
            continue
        diffs = []
        dead = 0
        for n, (a, b) in enumerate(zip(kdoc, section), 1):
            if a == b:
                continue
            if normalise(a) == normalise(b):
                continue  # the generator resolved a `[Symbol]` into a link
            if undecorate(a) == undecorate(b):
                dead += len(KDOC_LINK.findall(a)) or 1
                continue  # it could not, and published a code span instead
            diffs.append((n, a, b))
        if len(kdoc) != len(section):
            diffs.append((0, f"<{len(kdoc)} KDoc lines>", f"<{len(section)} page lines>"))
        if diffs:
            bad += 1
            print(f"MISMATCH          {source.name}: {len(diffs)} line(s)")
            for n, a, b in diffs[:12]:
                print(f"    line {n}")
                print(f"      kdoc: {a}")
                print(f"      page: {b}")
        else:
            note = f", {dead} unresolved [Symbol] published as a code span" if dead else ""
            print(f"ok                {source.name}: {len(kdoc)} lines mirror{note}")
    if bad:
        print(f"\n{bad} file(s) do not mirror. Run `./gradlew generateCodebaseReference`.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
