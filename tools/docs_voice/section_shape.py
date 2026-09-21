#!/usr/bin/env python3
"""Rank pages by STRUCTURE, not by wording — the two routes the twenty-seventh firing
(2026-09-18) found six pages with and recommended adding to the routine.

`score_voice.py` scores sentences and headings. It cannot see a page whose API is buried
under the verification of the API, because every sentence in the verification is fine.
These two filters see exactly that:

  verification mass   the fraction of a page's words sitting under a `##` whose heading
                      names measurement, checking, agreement, provenance or proof.
  tail verification   whether the LAST or second-last `##` names verification — a page
                      that ends on how it checks itself rather than on what to do next.

Both are targeting tools. A high fraction is not a fault: `tetgen.md`'s 17 data tables
ARE the deliverable, and a measurement page is allowed to be about measurement. Read the
page. What you are looking for is a page whose SUBJECT is an API and whose MASS is proof.

Usage:
    section_shape.py public/docs [--assets] [--min-words N] [--top N]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Words that make a heading a verification heading. Kept deliberately narrow: these are
# the ones that mean "here is how I know", not the ones that mean "here is a number".
# `measured`/`measurement` yes; `performance`/`cost`/`budget` NO — a reader acts on those.
VERIFY_WORDS = re.compile(
    r"\b("
    r"measur\w*|verif\w*|check\w*|checked|cross-check\w*|agree\w*|agreement"
    r"|provenance|proof|proven|prove[sd]?|assert\w*|guard\w*|guarantee[sd]?"
    # NOT bare `reproduc\w*`: "Reproduction with modification" is this tree's standard
    # heading for "here is the code behind the figure, go edit it" — a teaching section,
    # and it put rectsl_examples.md's 1589-word gallery at 29% on the first run.
    # Only the non-determinism sense counts.
    r"|evidence|corrobor\w*|reproducib\w*|residual\w*|census|censuses"
    r"|convergence|accuracy|error\s+table|how\s+(we|i|this|the\s+page)\s+know"
    r"|taken\s+on\s+trust|on\s+trust|independently\s+known|share\s+no\s+code"
    r"|test(s|ed|ing)?|validat\w*|calibrat\w*"
    r")\b",
    re.I,
)

FENCE = re.compile(r"^\s*(```|~~~)")
COMMENT_OPEN = re.compile(r"<!--")
COMMENT_CLOSE = re.compile(r"-->")


def sections(text: str):
    """Yield (heading, word_count) per `##`-level section, fences and HTML comments
    excluded from the word count. Front matter above the first `##` is heading None."""
    out = []
    heading = None
    words = 0
    in_fence = False
    in_comment = False
    for line in text.splitlines():
        # Comments first: one of them contains a ``` line, which is how the first
        # firing's fence tracker got confused.
        if in_comment:
            if COMMENT_CLOSE.search(line):
                in_comment = False
            continue
        if COMMENT_OPEN.search(line) and not COMMENT_CLOSE.search(line):
            in_comment = True
            continue
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = re.match(r"^##\s+(.*?)\s*$", line)
        if m:
            out.append((heading, words))
            heading = m.group(1)
            words = 0
            continue
        words += len(line.split())
    out.append((heading, words))
    return out


# The body-text half of what VERIFY_WORDS sees in headings: a section whose PROSE is dense in
# harness identifiers is telling a reader what the tests are called, which is an inventory nobody
# can act on. Folded in 2026-09-19 at the twenty-eighth firing's request — it found
# `morpho_examples.md`'s step 6 (6 identifiers in 116 words, 51.7/1k) on a page every other route
# scored 0. Deliberately NOT `\w*Test` bare: `TestKit`, `TestDouble` and the like are API a reader
# uses. The suffix form only, plus the two build words.
HARNESS_IDENT = re.compile(
    r"\b(?:[A-Z]\w*(?:Test|Guard|Contract)|gradlew|jvmTest|commonTest|assert[A-Z]\w*|"
    r"assertThat|assertEquals|assertTrue|assertFalse)\b"
)


def harness_sections(text: str, min_words: int = 60):
    """Yield (heading, words, identifier_count) per `##`/`###` section, fences and HTML
    comments excluded — the same exclusions `sections()` makes, for the same reasons.

    Collapsed `??? aside` bodies are excluded too, on the same grounds the scorer counts only
    `!!!`: a collapsed aside costs a reader nothing. Measured when this route was folded in —
    `fieldml_host_bridge.md`'s "Where to look" led the board at 41.4/1k and every one of its
    seven identifiers was inside `??? aside "Which test holds which claim"`, which is exactly
    where an inventory of test classes is supposed to live.
    """
    out = []
    heading, body, in_fence, in_comment = None, [], False, False
    aside_indent = None

    def flush():
        joined = " ".join(body)
        n = len(joined.split())
        if heading and n >= min_words:
            out.append((heading, n, len(HARNESS_IDENT.findall(joined))))

    for line in text.splitlines():
        if in_comment:
            if COMMENT_CLOSE.search(line):
                in_comment = False
            continue
        if COMMENT_OPEN.search(line) and not COMMENT_CLOSE.search(line):
            in_comment = True
            continue
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if aside_indent is not None:
            if not line.strip():
                continue
            if len(line) - len(line.lstrip()) > aside_indent:
                continue
            aside_indent = None
        if re.match(r"^\s*\?\?\?", line):
            aside_indent = len(line) - len(line.lstrip())
            continue
        m = re.match(r"^#{2,3}\s+(.*?)\s*$", line)
        if m:
            flush()
            heading, body = m.group(1), []
            continue
        body.append(line)
    flush()
    return out


def collect(root: Path, assets: bool):
    for p in sorted(root.rglob("*.md")):
        rel = p.relative_to(root.parent.parent) if root.is_absolute() else p
        s = str(p)
        is_asset = "/assets/" in s
        if is_asset != assets:
            continue
        if "/reference/" in s or s.endswith("linear_algebra_api.md"):
            continue
        yield p


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--assets", action="store_true")
    ap.add_argument("--min-words", type=int, default=400)
    ap.add_argument("--top", type=int, default=25)
    a = ap.parse_args()

    rows = []
    tail = []
    dense = []
    for p in collect(Path(a.root), a.assets):
        text = p.read_text(encoding="utf-8", errors="replace")
        for h, n, k in harness_sections(text):
            if k >= 3:
                dense.append((k * 1000.0 / n, k, n, p, h))
        secs = sections(text)
        total = sum(w for _, w in secs)
        if total < a.min_words:
            continue
        vmass = sum(w for h, w in secs if h and VERIFY_WORDS.search(h))
        heads = [h for h, _ in secs if h]
        if heads:
            last_two = heads[-2:]
            hit = [h for h in last_two if VERIFY_WORDS.search(h)]
            if hit:
                tail.append((len(heads) - len(last_two) + last_two.index(hit[0]) + 1,
                             len(heads), p, hit[0]))
        rows.append((vmass / total if total else 0.0, vmass, total, p))

    rows.sort(reverse=True)
    print("verification MASS by section — fraction of the page's words under a "
          "verification `##`\n")
    print(f"{'frac':>6}  {'v-words':>7}  {'words':>6}  path")
    for frac, vmass, total, p in rows[: a.top]:
        if frac <= 0:
            break
        print(f"{frac*100:5.1f}%  {vmass:7d}  {total:6d}  {p}")

    print("\n\nTAIL verification — the page's LAST or second-last `##` names verification\n")
    for idx, n, p, h in sorted(tail, key=lambda r: -r[1]):
        print(f"  {p}   (`##` {idx}/{n}) {h!r}")
    dense.sort(reverse=True)
    print("\n\nHARNESS-IDENTIFIER DENSITY — `##`/`###` sections whose PROSE names test classes,\n"
          "guards and build tasks. Per 1000 words, 3 identifiers minimum.\n")
    print(f"{'per1k':>6}  {'n':>3}  {'words':>5}  page / section")
    for per1k, k, n, p, h in dense[: a.top]:
        print(f"{per1k:6.1f}  {k:3d}  {n:5d}  {p}\n{'':>17}{h!r}")

    print(f"\n{len(rows)} pages over {a.min_words} words; {len(tail)} end on verification; "
          f"{len(dense)} section(s) over the harness-density floor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
