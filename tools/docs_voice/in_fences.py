#!/usr/bin/env python3
"""The surface `score_voice.py` is structurally blind to: prose INSIDE a code fence.

The twenty-seventh firing (2026-09-18) found `chain_polytope_render.md:125` carrying
`#### The same surface, measured by two implementations that share no code` — a markdown
heading, rendered into the box's output, written inside a `diffstatic_runner` fence as part
of a Kotlin `markdown("chain", \"\"\"…\"\"\")` call. Every reader of that page sees it. It is
the exact heading fault this task exists to remove, and `score_voice.py` scores it **0**,
because `split_prose` strips fences before it looks at anything — correctly, for scoring
prose, and fatally for this.

A second instance turned up the same night: `rectsl_inverse_animation.md:341`, a code
comment, matched by the widened `epistemic-boast` and then discarded with the fence.

Two things a reader actually reads live inside fences:

  embedded markdown   `markdown(...)`/`display(...)` raw strings whose headings are rendered
                      into box output. Scored as HEADINGS only — see below.
  code comments       `//` and `#` lines. A reader reads these before running the code.
                      Scored as prose only — a comment is not a heading.

MEASURED 2026-09-19 (thirty-third firing), correcting this docstring: `scan()` reaches a
raw string's `#` HEADING lines and its `//` comment lines, and nothing else. A plain prose
line inside `markdown("chan", \"\"\" … \"\"\")` matches neither `MD_HEADING` nor `COMMENT`
and is dropped, so for three firings this file claimed a surface it did not cover.
`embedded_prose.py` beside this one extracts exactly that surface — non-heading,
non-comment lines of a triple-quoted raw string opened by an output sink — and scores it
with the same `sv.BODY_TELLS`. **579 lines across the tree, 0 pages nonzero, sum 0.**
Live-fired against Marc's own two quotes ("the left panel is the sanity check", "two ways
the numbers will surprise you") inside a synthetic fence, which score `slide-label` and
`carries-a-trap`, so the zero is a clean surface and not a broken extractor. NOT adopted
into `scan()` on that evidence; re-run `embedded_prose.py public/docs` rather than
rebuilding it.

Scored with `score_voice.py`'s own families, imported rather than copied, so this surface
cannot drift from the calibrated one.

## Landing a fix here is NOT a prose edit

`tools/completion-corpus/rank-carets.tsv` indexes each page's Kotlin fences by ordinal AND
stores a base64 prefix of each fence's text, so editing fence text invalidates the fixture.
The repair is three coupled edits, in this order:

  1. the Kotlin source the fence was copied from (if any — check with `check_fences.py`
     and `grep -rF` on a distinctive line; some fences are hand-written in the .md),
  2. the fence in the `.md`, kept in the same ORDINAL position,
  3. `python3 tools/scrape_completion_corpus.py --wide --out tools/completion-corpus/rank-carets.tsv`
     to re-derive the fixture, then `./gradlew --no-daemon checkCompletionCorpusFixtures`.

Step 3 is the risk and the reason this is not an unattended job: `rank-carets.tsv` also
carries RECORDED completion ranks (`tools/record_rank_corpus.py`), and a naive re-scrape can
drop them. Read both tools before running either. A firing that finds a hit here and reports
it without editing has done the right thing.

MEASURED 2026-09-19, and it makes step 3 much rarer than the paragraph above implies:
`rank-carets.tsv` indexes **65 page stems**, not all 119 fenced pages. `check_fences.py` now
splits its report by whether a page is indexed, so a changed fence body on an unindexed page
(`tetgen`, `rectsl_inverse_animation`) is reported and owes nothing. Editing a `.md` fence
does **not** drift `tools/completion-corpus/boxes/manifest.json` either — that is harvested
from workspace *documents*, not from page fences, and `checkCompletionCorpusFixtures` stayed
green across two published-page fence edits.

## The document surfaces DO have a fourth coupled edit

A workspace document (`assets/agent_transcripts/*/`, `assets/solidsl_gallery/`, …) carries the
same comment in three or four files at once — `document.md`, `metadata.edn`, each
`*_code_.kt` sidecar — and its boxes ARE harvested by `codeSha256`. So:

  agent transcripts   edit `dev_docs/agents/transcripts/<name>.yaml`, then
                      `./gradlew --no-daemon regenerateAgentTranscriptStartingPoints`, then
                      `AFIELD_HARVEST_WRITE=1 ./gradlew --no-daemon checkCompletionCorpusFixtures`.
                      Review the manifest diff: it should be one `codeSha256` line per box you
                      touched, with `codeLines` unchanged unless you changed the line count.
  other documents     no yaml and no materializer. Edit every copy by hand, with an
                      `assert count == 1` per file — `grep -c` the sentence first to find them all.

## Two whole classes of row here are not editable as a page

`hidden=true` runner fences are never emitted into the built page (see `HIDDEN_ATTR` below) and
are skipped by default; `--include-hidden` brings them back if you are auditing the capture
scripts themselves. And `public/docs/assets/agent_transcripts/*/document.md` is **generated**
from `dev_docs/agents/transcripts/*.yaml` by `materializeAgentTranscripts` and drift-checked by
`checkAgentTranscripts` — editing the `document.md` is reverted. The banner says so.

Usage:
    in_fences.py public/docs [--why <page>] [--top N] [--include-hidden]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

FENCE_OPEN = re.compile(r"^\s*(```|~~~)(.*)$")
MD_HEADING = re.compile(r"^\s*(#{1,6})\s+(\S.*?)\s*$")
COMMENT = re.compile(r"^\s*(//+|#+)\s?(.*)$")
# A `#` line is embedded markdown ONLY in a language where `#` cannot be a comment.
# Stated as an allow-list, because the deny-list form got this backwards on the first run:
# RectSL, pat and the boolean-network notation all comment with `#`, and their fences put
# `# lane 0 is a sine-derived square wave; lanes 1-4 are the network's response` in front of
# `head-semicolon`, which scored three pages 24, 16 and 8 on nothing. A comment is allowed a
# semicolon; a heading is not. Anything not on this list is read as a comment.
HASH_IS_MARKDOWN = (
    "kotlin", "kt", "java", "javascript", "js", "typescript", "ts",
    "glsl", "c", "cpp", "c++", "rust", "swift", "scala", "groovy", "json",
)


# A runner fence carrying `hidden=true` (or `hide-source`) is NOT emitted into the built page:
# `public/docs_hooks.py:2622` computes `hide_source` and then skips the open line, the rewritten
# body and the closing line, leaving only the screenshot/video/audio embed. Nobody reads its
# comments. These are the docs-capture scripts — `ui.click`, polling loops, crop markers — and
# their comments address whoever maintains the capture, which is the one audience this task does
# not serve. Measured on 2026-09-19 over 115 such fences: skipping them drops `spatial_navigation`
# (12) and `completion_ranking` (6) off the board entirely and nothing else, so it is 2 pages and
# 18 of 326 points — small, but both were in that night's batch before the filter existed.
HIDDEN_ATTR = re.compile(r"\b(?:hidden|hide-source|hide_source)\s*=\s*\"?(?:true|yes|1)\"?", re.I)

# A runner fence carrying `screenshot=` / `video=` captures its BOX OUTPUT into a committed
# asset. An embedded markdown heading in such a fence is not source a reader skims — it is
# emitted by `markdown(...)` and rendered INTO the PNG. Reworded on the page alone, the source
# and the committed figure then disagree, which is the fault the task file says to open the PNG
# and check for. Found on 2026-09-19 on `boolean_network_music_examples.md:283`, whose
# `#### lane 0 is a sine-derived square wave; ...` is legible in `oscillator_driven.png`.
# `//` comments are NOT baked: `capture="box"` photographs the output panel, not the source.
CAPTURE_ATTR = re.compile(r"\b(?:screenshot|video|audio)\s*=\s*\"", re.I)
BAKED_NOTE = "   [BAKED INTO THE COMMITTED FIGURE — a reword owes a re-capture]"


def fence_blocks(text: str):
    """Yield (start_lineno, lang, hidden, baked, [(lineno, line)]) for each fenced block."""
    lines = text.split("\n")
    i = 0
    in_comment = False
    while i < len(lines):
        ln = lines[i]
        if in_comment:
            if "-->" in ln:
                in_comment = False
            i += 1
            continue
        if ln.lstrip().startswith("<!--") and "-->" not in ln:
            in_comment = True
            i += 1
            continue
        m = FENCE_OPEN.match(ln)
        if m:
            info = m.group(2)
            lang = ""
            lm = re.search(r"data-lang=([a-zA-Z0-9_+-]+)", info)
            if lm:
                lang = lm.group(1).lower()
            else:
                lw = info.strip().split()
                if lw and re.match(r"^[a-zA-Z0-9_+-]+$", lw[0]):
                    lang = lw[0].lower()
            body, j = [], i + 1
            while j < len(lines) and not FENCE_OPEN.match(lines[j]):
                body.append((j + 1, lines[j]))
                j += 1
            yield (i + 1, lang, bool(HIDDEN_ATTR.search(info)),
                   bool(CAPTURE_ATTR.search(info)), body)
            i = j + 1
            continue
        i += 1


def scan(text: str, include_hidden: bool = False):
    """Return [(kind, lineno, family, weight, snippet)].

    Fences the built page does not emit are skipped unless `include_hidden` is set.
    """
    out = []
    for _start, lang, hidden, baked, body in fence_blocks(text):
        if hidden and not include_hidden:
            continue
        note = BAKED_NOTE if baked else ""
        hash_is_markdown = lang in HASH_IS_MARKDOWN
        for lineno, raw in body:
            hm = MD_HEADING.match(raw)
            if hm and hash_is_markdown:
                title = hm.group(2)
                title = re.sub(r"\{#[\w-]+\}", "", title).strip()
                if title.endswith("?"):
                    out.append(("md-heading", lineno, "head-question", 3, title + note))
                for name, pat, w in sv.HEADING_TELLS:
                    if re.search(pat, title, re.I):
                        out.append(("md-heading", lineno, name, w, title + note))
                for name, pat, w in sv.BODY_TELLS:
                    if re.search(pat, title, re.I):
                        out.append(("md-heading", lineno, name, w, title + note))
                continue
            cm = COMMENT.match(raw)
            if not cm:
                continue
            # Same inline-code sentinel score_voice uses: deleting a span joins the words
            # either side and can manufacture a tic nobody wrote.
            probe = re.sub(r"`[^`]+`", "§", cm.group(2))
            if len(probe.split()) < 4:
                continue
            for name, pat, w in sv.BODY_TELLS:
                for mm in re.finditer(pat, probe, re.I):
                    out.append(("comment", lineno, name, w, mm.group(0)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--why")
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--include-hidden", action="store_true",
                    help="also score runner fences the built page never emits (capture scripts)")
    a = ap.parse_args()

    if a.calibrate:
        # Same contract as score_voice.py --calibrate: a detector that fires on the target
        # voice is a broken detector. These four are Marc's own. On the surface's first run
        # (2026-09-19) all four scored 0, and they should stay there — his fences carry
        # comments, so this is a real test and not a vacuous one.
        bad = 0
        for stem in ("kotlin_overview", "survivalGuide", "index", "funFeatures"):
            p = Path(a.root) / f"{stem}.md"
            rows = scan(p.read_text(encoding="utf-8", errors="replace"), a.include_hidden)
            sc = sum(r[3] for r in rows)
            print(f"  {sc:4d}  {p}")
            for kind, lineno, fam, w, snip in rows:
                print(f"          {fam} [{kind}] :{lineno}  {snip[:80]}")
            bad += sc
        if bad:
            print("\nFAIL: a target-voice page scores on this surface. Fix the detector, "
                  "not the page.")
            return 1
        print("\nOK: every target-voice page is at 0 in its fences.")
        return 0

    if a.why:
        p = Path(a.why)
        rows = scan(p.read_text(encoding="utf-8", errors="replace"), a.include_hidden)
        print(f"{p}  in-fence score {sum(r[3] for r in rows)}")
        for kind, lineno, fam, w, snip in rows:
            print(f"  {fam} ({w})  [{kind}]\n      :{lineno}  {snip}")
        return 0

    rows = []
    for p in sorted(Path(a.root).rglob("*.md")):
        hits = scan(p.read_text(encoding="utf-8", errors="replace"), a.include_hidden)
        if hits:
            rows.append((sum(h[3] for h in hits), len(hits), p, hits))
    rows.sort(reverse=True)
    print("PROSE INSIDE CODE FENCES — embedded markdown headings and code comments.")
    print("A fix here is three coupled edits, not a prose edit. See this file's docstring.")
    if a.include_hidden:
        print("INCLUDING hidden runner fences. Those are capture scripts the built page never")
        print("emits; a comment in one is read by nobody. Do not open a commit on them.")
    print("A row under assets/agent_transcripts/ is GENERATED from dev_docs/agents/transcripts/")
    print("*.yaml — fix the yaml, then `./gradlew materializeAgentTranscripts`.\n")
    print(f"{'score':>5} {'hits':>4}  page")
    for sc, n, p, hits in rows[: a.top]:
        print(f"{sc:5d} {n:4d}  {p}")
        for kind, lineno, fam, w, snip in hits:
            print(f"                 {fam} [{kind}] :{lineno}  {snip[:88]}")
    print(f"\npages={sum(1 for _ in Path(a.root).rglob('*.md'))} "
          f"nonzero={len(rows)} sum={sum(r[0] for r in rows)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
