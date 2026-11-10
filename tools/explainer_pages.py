#!/usr/bin/env python3
"""Keep the explainer pages' code fences identical to the figure boxes their decks run.

    uv run python tools/explainer_pages.py check   # exit 1 naming every fence that differs
    uv run python tools/explainer_pages.py sync    # rewrite each fence body from its transcript

`public/docs/explainer_<deck>.md` shows, for every figure of a talk, the figure box's code in a
`diffstatic_runner` fence. The docs runner runs that fence body in a fresh box and captures its
output panels, so the picture is of the code on the page. The decks themselves live in
`dev_docs/agents/transcripts/explainer_<deck>.yaml`, one `create` per figure box. Two copies of one
program drift unless something compares them, and this is that comparison: a fence named
`explainer-<deck>-<figure>` must, with its `:::fold` / `:::endfold` marker lines removed, be
byte-for-byte the `code` of the box named `<figure>` in that deck's transcript.

`sync` writes the transcript's code back into each fence and folds the boilerplate again (imports
and the small drawing helpers listed in `FOLDED_HELPERS`), so after a deck edit the pages follow
with one command and a recapture (`FIELD_DOCS_RUNNER_MODE=incremental ... --page explainer_<deck>.md`).
`tools/test_explainer_pages.py` runs `check` over the committed tree.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "public/docs"
TRANSCRIPTS = ROOT / "dev_docs/agents/transcripts"
DECKS = ("sound", "cameras", "lights")

# Top-level helpers that only put pixels or lines on screen. Folding them leaves the physics in view.
FOLDED_HELPERS = {
    "dot": "draws a filled disc into the pixel array",
    "paint": "colours the pixel array from the pressure field",
    "segment": "a straight line segment as an FLine",
    "ring": "one wavefront circle as an FLine",
    "scenery": "the fixed ends and guide lines of the string",
    "ray": "one ray as an FLine whose width shows its share of the light",
    "px": "pixel x of a point in metres",
    "py": "pixel y of a point in metres",
}

FENCE = re.compile(
    r"^(?P<open>```+)diffstatic_runner \{(?P<attrs>[^\n]*)\}\n(?P<body>.*?)^(?P=open)[ \t]*$",
    re.S | re.M,
)
NAME = re.compile(r'\bname="explainer-(?P<deck>[a-z]+)-(?P<figure>[a-z0-9_]+)"')


def figure_code(deck: str) -> dict[str, str]:
    """The `code` of every box in a deck's transcript, by box name."""
    data = yaml.safe_load((TRANSCRIPTS / f"explainer_{deck}.yaml").read_text(encoding="utf-8"))
    return {
        op["create"]["name"]: op["create"]["properties"]["code"]
        for op in data["operations"]
        if "create" in op and "code" in op["create"].get("properties", {})
    }


def strip_folds(body: str) -> str:
    """The fence body as the runner runs it: fold marker lines removed, everything else kept."""
    kept = [
        line for line in body.splitlines(keepends=True)
        if not (line.strip() in (":::fold", ":::endfold") or line.strip().startswith(":::fold "))
    ]
    return "".join(kept)


def _depth(line: str) -> int:
    # Brackets outside string literals; good enough for box code, which never nests quotes oddly.
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', line.split("//", 1)[0])
    return sum(text.count(c) for c in "({[") - sum(text.count(c) for c in ")}]")


def _block_end(lines: list[str], start: int) -> int:
    """Index of the last line of the top-level declaration starting at `start`."""
    depth = 0
    for i in range(start, len(lines)):
        depth += _depth(lines[i])
        if depth <= 0 and not lines[i].rstrip().endswith(("=", "(", ",", "{")):
            return i
    return len(lines) - 1


def fold(code: str) -> str:
    """Box code with its imports and the helpers in FOLDED_HELPERS wrapped in fold markers."""
    lines = code.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    imports = [n for n, line in enumerate(lines) if line.startswith("import ")]
    while i < len(lines):
        line = lines[i]
        if imports and i == imports[0]:
            last = imports[-1]
            out.append(":::fold imports\n")
            out.extend(lines[i:last + 1])
            out.append(":::endfold\n")
            i = last + 1
            continue
        match = re.match(r"fun (\w+)\(", line)
        if match and match.group(1) in FOLDED_HELPERS:
            end = _block_end(lines, i)
            out.append(f":::fold {match.group(1)}: {FOLDED_HELPERS[match.group(1)]}\n")
            out.extend(lines[i:end + 1])
            out.append(":::endfold\n")
            i = end + 1
            continue
        out.append(line)
        i += 1
    return "".join(out)


def fences(page: Path):
    text = page.read_text(encoding="utf-8")
    for match in FENCE.finditer(text):
        name = NAME.search(match.group("attrs"))
        if name:
            yield match, name.group("deck"), name.group("figure")


def _shown(page: Path) -> str:
    return f"public/docs/{page.name}"


def problems() -> list[str]:
    found: list[str] = []
    for deck in DECKS:
        page = DOCS / f"explainer_{deck}.md"
        if not page.exists():
            found.append(f"{_shown(page)}: missing")
            continue
        code = figure_code(deck)
        shown = set()
        text = page.read_text(encoding="utf-8")
        for match, fence_deck, figure in fences(page):
            line = text.count("\n", 0, match.start()) + 1
            where = f"{_shown(page)}:{line} ({figure})"
            if fence_deck != deck:
                found.append(f"{where}: names deck {fence_deck!r} on the {deck} page")
                continue
            if figure not in code:
                found.append(f"{where}: no box named {figure!r} in explainer_{deck}.yaml")
                continue
            shown.add(figure)
            body = strip_folds(match.group("body"))
            if body.rstrip("\n") != code[figure].rstrip("\n"):
                found.append(f"{where}: differs from box {figure!r} in explainer_{deck}.yaml "
                             "(run `uv run python tools/explainer_pages.py sync`, then recapture the page)")
        missing = sorted(set(code) - shown - {"deck"})
        if missing:
            found.append(f"{_shown(page)}: no fence for figure box(es) {', '.join(missing)}")
    return found


def sync() -> int:
    changed = 0
    for deck in DECKS:
        page = DOCS / f"explainer_{deck}.md"
        code = figure_code(deck)
        text = page.read_text(encoding="utf-8")

        def replace(match: re.Match) -> str:
            name = NAME.search(match.group("attrs"))
            if not name or name.group("figure") not in code:
                return match.group(0)
            if strip_folds(match.group("body")).rstrip("\n") == code[name.group("figure")].rstrip("\n"):
                return match.group(0)
            body = fold(code[name.group("figure")].rstrip("\n") + "\n")
            return f"{match.group('open')}diffstatic_runner {{{match.group('attrs')}}}\n{body}{match.group('open')}"

        updated = FENCE.sub(replace, text)
        if updated != text:
            page.write_text(updated, encoding="utf-8")
            changed += 1
    print(f"explainer pages: {changed} page(s) rewritten")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("check", "sync"))
    args = parser.parse_args(argv)
    if args.command == "sync":
        return sync()
    found = problems()
    for problem in found:
        print(problem)
    if not found:
        print("explainer pages: every figure fence matches its deck")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
