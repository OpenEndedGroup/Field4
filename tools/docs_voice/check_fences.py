#!/usr/bin/env python3
"""Does this branch still index the same Kotlin fences as its base?

`tools/completion-corpus/rank-carets.tsv` names each docs page's Kotlin
`diffstatic` / `diffstatic_runner` fences **by ordinal** — `solid_notation#1`,
`faust#2`. An ordinal is a position in the sequence of Kotlin fences on that
page, so the invariant a prose pass must not break is:

    for every page, the ordered list of Kotlin fence bodies is unchanged.

The recipe the ninth and tenth firings used for this was

    git diff main -- public/docs/ | grep -c '^[-+].*```'

and on 2026-09-15 it reported **46** for a branch whose per-commit deltas summed
to 6 and whose fence sequences were in fact identical. A cumulative diff over
many commits merges hunks, so an *unmoved* fence sitting inside a hunk whose
surrounding prose changed is emitted as a `-`/`+` pair. The grep counts diff
lines; the invariant is about sequences. They are not the same question, and the
grep answers the wrong one in the alarming direction.

This compares the sequences directly, per page, and also reports any fence whose
body changed while staying in position — which the ordinal survives but the
caret inside it may not.

    python3 check_fences.py                 # HEAD against main
    python3 check_fences.py <base> [<head>]
"""

import base64
import re
import subprocess
import sys

FENCE = re.compile(r"^```diffstatic(?:_runner)?\s*\{[^}]*data-lang=kotlin")
CLOSE = re.compile(r"^```\s*$")
MARKER = re.compile(r"^:::")


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def kotlin_fences(rev: str) -> dict[str, list[str]]:
    """Every page's Kotlin fence bodies, in page order."""
    out: dict[str, list[str]] = {}
    for path in run("git", "ls-tree", "-r", "--name-only", rev, "public/docs/").split("\n"):
        if not path.endswith(".md"):
            continue
        lines = run("git", "show", f"{rev}:{path}").splitlines()
        bodies, i = [], 0
        while i < len(lines):
            if FENCE.match(lines[i]):
                j = i + 1
                while j < len(lines) and not CLOSE.match(lines[j]):
                    j += 1
                bodies.append(lines[i] + "\n" + "\n".join(lines[i + 1 : j]))
                i = j
            i += 1
        if bodies:
            out[path] = bodies
    return out


def indexed_stems(rev: str) -> set[str]:
    """The page stems `rank-carets.tsv` actually indexes, as of `rev`.

    It indexes 65 of the tree's ~460 fenced pages, not all of them. Until 2026-09-19 this
    script said "`rank-carets.tsv` needs regenerating" for *any* changed fence body, which
    on that night fired on `tetgen.md` — a page named in neither `rank-carets.tsv` nor
    `boxes/manifest.json`. That is a false alarm pointing at the one operation this task's
    own notes call the reason fence work stays attended, so it is worth the six lines.
    """
    try:
        tsv = run("git", "show", f"{rev}:tools/completion-corpus/rank-carets.tsv")
    except subprocess.CalledProcessError:
        return set()
    return {ln.split("\t", 1)[0].split("#", 1)[0] for ln in tsv.splitlines() if ln.strip()}


def caret_prefixes(rev: str) -> dict[str, list[tuple[str, str]]]:
    """Every recorded caret, as (label, the source text before the caret), by page stem.

    A `rank-carets.tsv` row is `<stem>#<ord> <token>\\t<base64 prefix>\\t<token>`, and the
    base64 is the fence source up to the caret, with U+2038 marking the caret itself. The
    fixture therefore does not depend on the whole fence body — only on the text *before*
    each recorded caret. An edit after every caret in a fence changes the body and owes
    nothing.
    """
    try:
        tsv = run("git", "show", f"{rev}:tools/completion-corpus/rank-carets.tsv")
    except subprocess.CalledProcessError:
        return {}
    out: dict[str, list[tuple[str, str]]] = {}
    for ln in tsv.splitlines():
        if not ln.strip():
            continue
        f = ln.split("\t")
        if len(f) < 2:
            continue
        label = f[0]
        stem = label.split(" ", 1)[0].split("#", 1)[0]
        try:
            pref = base64.b64decode(f[1]).decode("utf8").replace("‸", "")
        except Exception:
            continue
        out.setdefault(stem, []).append((label, pref))
    return out


def fence_text(rev: str, path: str) -> str:
    """A page's Kotlin fence bodies with the `:::fold` markers the scraper strips removed.

    Joined by a separator no Kotlin source contains, so a recorded prefix can be tested with
    a plain substring search without having to reproduce the scraper's fence numbering —
    which does not match this file's ordinals on every page, and does not need to.
    """
    lines = run("git", "show", f"{rev}:{path}").splitlines()
    out, i = [], 0
    while i < len(lines):
        if FENCE.match(lines[i]):
            j = i + 1
            while j < len(lines) and not CLOSE.match(lines[j]):
                j += 1
            out.append("\n".join(l for l in lines[i + 1 : j] if not MARKER.match(l)))
            i = j
        i += 1
    return "\n<<<FENCE-BREAK>>>\n".join(out)


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "main"
    head = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
    a, b = kotlin_fences(base), kotlin_fences(head)
    indexed = indexed_stems(head)

    # A changed *count* renames every ordinal from the first difference on, so it is broken
    # whatever the carets say. A changed *body* only matters if it moved a recorded caret's
    # prefix. Keeping these apart is the whole point; conflating them is how the 2026-09-19c
    # draft of this script briefly let a count mismatch exit 0.
    resequenced: list[str] = []
    changed_body: list[str] = []
    for path in sorted(set(a) | set(b)):
        before, after = a.get(path, []), b.get(path, [])
        if before == after:
            continue
        if len(before) != len(after):
            resequenced.append(
                f"{path}: {len(before)} Kotlin fence(s) at {base}, {len(after)} at {head} "
                f"— every ordinal from the first difference on now names a different fence"
            )
            continue
        for n, (x, y) in enumerate(zip(before, after)):
            if x != y:
                changed_body.append(f"{path}#{n}: same position, changed body")

    total_a = sum(len(v) for v in a.values())
    total_b = sum(len(v) for v in b.values())
    print(f"{total_a} Kotlin fence(s) across {len(a)} page(s) at {base}")
    print(f"{total_b} Kotlin fence(s) across {len(b)} page(s) at {head}")

    if not resequenced and not changed_body:
        print(f"\nOK: every page's Kotlin fence sequence is identical, so every "
              f"`rank-carets.tsv` ordinal still names the fence it named at {base}.")
        return 0

    def stem(line: str) -> str:
        return line.split(":", 1)[0].split("#", 1)[0].rsplit("/", 1)[-1][: -len(".md")]

    if resequenced:
        print(f"\n{len(resequenced)} page(s) whose Kotlin fence COUNT changed. This renames "
              f"ordinals whatever the carets say:")
        for line in resequenced:
            mark = "  (indexed)" if stem(line) in indexed else "  (not indexed)"
            print("  " + line + mark)

    on_indexed = [ln for ln in changed_body if stem(ln) in indexed]
    unindexed = [ln for ln in changed_body if stem(ln) not in indexed]

    # A changed body on an indexed page is only a problem if it moved text that a recorded
    # caret prefix actually covers. Until 2026-09-19 this script called every one of them a
    # re-scrape; that overstates the cost of a comment edit late in a fence, and a re-scrape
    # is the one operation this task's notes call risky. Differential, so the 24 prefixes
    # already stale on `main` are not charged to this branch.
    lost, stale = [], []
    if on_indexed:
        recorded = caret_prefixes(head)
        for path in sorted({ln.split(":", 1)[0].split("#", 1)[0] for ln in on_indexed}):
            st = path.rsplit("/", 1)[-1][: -len(".md")]
            if st not in recorded:
                continue
            before_txt, after_txt = fence_text(base, path), fence_text(head, path)
            for label, pref in recorded[st]:
                in_before, in_after = pref in before_txt, pref in after_txt
                if in_before and not in_after:
                    lost.append(f"{label} — the recorded caret prefix no longer matches {path}")
                elif not in_before:
                    stale.append(f"{label} — already stale at {base}, not this branch")

    if lost:
        print(f"\n{len(lost)} recorded caret(s) BROKEN — `rank-carets.tsv` needs regenerating, "
              f"or back the move out:")
        for line in lost:
            print("  " + line)
    if on_indexed and not lost:
        print(f"\n{len(on_indexed)} changed fence(s) on INDEXED pages, but every recorded caret "
              f"prefix on those pages still matches, so no re-scrape is owed:")
        for line in on_indexed:
            print("  " + line)
    if stale:
        print(f"\n{len(stale)} recorded caret(s) on those pages were ALREADY stale at {base} "
              f"(pre-existing fixture drift, listed so it is not charged to this branch):")
        for line in stale[:10]:
            print("  " + line)
        if len(stale) > 10:
            print(f"  ... and {len(stale) - 10} more")
    if unindexed:
        print(f"\n{len(unindexed)} changed fence(s) on pages `rank-carets.tsv` does not index "
              f"({len(indexed)} stems are indexed). No fixture depends on these; listed so the "
              f"change is visible, not because anything is owed:")
        for line in unindexed:
            print("  " + line)
    return 1 if (lost or any(stem(ln) in indexed for ln in resequenced)) else 0


if __name__ == "__main__":
    sys.exit(main())
