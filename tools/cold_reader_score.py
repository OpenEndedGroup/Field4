"""Score an AI4 cold-reader pass against a committed cold-reader ledger.

A ledger (`dev_docs/agents/COLD_READER_<date>.findings.jsonl`) and AI4's stalls (the JSONL the
`report_stall` tool in `dev_docs/agents/transcripts/ai4_cold_reader.yaml` writes) share one row
shape: `page`, `line`, `category`, `exact`, `note`.

The matching rule was fixed before the first AI4 run (dev_docs/agents/AI4_COLD_READER.md):
on the same page, a stall matches a ledger row when (a) their normalised quotes overlap — one
contains the other, or they share a run of at least four consecutive words — or (b) they are at
most one line apart and have the same category. Matching is one-to-one and greedy, quote matches
before location matches, nearer lines first.

    uv run python tools/cold_reader_score.py --ledger dev_docs/agents/COLD_READER_2026-10-30.findings.jsonl \
        --stalls build/cold_reader/stalls.jsonl [--page staging.md ...] [--json]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

RUN_WORDS = 4
LINE_SLACK = 1


def normalise(text: str) -> str:
    text = re.sub(r"[*_`\[\]()]", " ", text.lower())
    return re.sub(r"\s+", " ", text).strip()


def quotes_overlap(a: str, b: str, run: int = RUN_WORDS) -> bool:
    na, nb = normalise(a), normalise(b)
    if not na or not nb:
        return False
    if na in nb or nb in na:
        return True
    wa, wb = na.split(" "), nb.split(" ")
    if len(wa) < run or len(wb) < run:
        return False
    grams = {tuple(wa[i:i + run]) for i in range(len(wa) - run + 1)}
    return any(tuple(wb[i:i + run]) in grams for i in range(len(wb) - run + 1))


def located(stall: dict[str, Any], row: dict[str, Any]) -> bool:
    try:
        close = abs(int(stall.get("line", -99)) - int(row.get("line", 99))) <= LINE_SLACK
    except (TypeError, ValueError):
        return False
    return close and stall.get("category") == row.get("category")


@dataclass
class PageScore:
    page: str
    stalls: int
    ledger: int
    matched: int
    by_quote: int
    same_category: int
    pairs: list[tuple[int, int, str]]

    @property
    def precision(self) -> float:
        return self.matched / self.stalls if self.stalls else 0.0

    @property
    def recall(self) -> float:
        return self.matched / self.ledger if self.ledger else 0.0


def match_page(stalls: list[dict[str, Any]], ledger: list[dict[str, Any]]) -> list[tuple[int, int, str]]:
    candidates: list[tuple[int, int, int, int]] = []
    for i, s in enumerate(stalls):
        for j, row in enumerate(ledger):
            try:
                distance = abs(int(s.get("line", 0)) - int(row.get("line", 0)))
            except (TypeError, ValueError):
                distance = 10**6
            if quotes_overlap(str(s.get("exact", "")), str(row.get("exact", ""))):
                candidates.append((0, distance, i, j))
            elif located(s, row):
                candidates.append((1, distance, i, j))
    candidates.sort()
    used_s: set[int] = set()
    used_l: set[int] = set()
    pairs: list[tuple[int, int, str]] = []
    for kind, _, i, j in candidates:
        if i in used_s or j in used_l:
            continue
        used_s.add(i)
        used_l.add(j)
        pairs.append((i, j, "quote" if kind == 0 else "line"))
    return pairs


def score(stalls: list[dict[str, Any]], ledger: list[dict[str, Any]], pages: list[str]) -> list[PageScore]:
    out = []
    for page in pages:
        s = [r for r in stalls if r.get("page") == page]
        l_rows = [r for r in ledger if r.get("page") == page]
        pairs = match_page(s, l_rows)
        out.append(PageScore(
            page=page, stalls=len(s), ledger=len(l_rows), matched=len(pairs),
            by_quote=sum(1 for p in pairs if p[2] == "quote"),
            same_category=sum(1 for i, j, _ in pairs if s[i].get("category") == l_rows[j].get("category")),
            pairs=pairs,
        ))
    return out


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def table(scores: list[PageScore]) -> str:
    lines = ["| page | AI4 stalls | ledger | matched (quote) | precision | recall | same category |",
             "| --- | --- | --- | --- | --- | --- | --- |"]
    for p in scores:
        lines.append(f"| `{p.page}` | {p.stalls} | {p.ledger} | {p.matched} ({p.by_quote}) | "
                     f"{p.precision:.2f} | {p.recall:.2f} | {p.same_category} |")
    total = PageScore("all", sum(p.stalls for p in scores), sum(p.ledger for p in scores),
                      sum(p.matched for p in scores), sum(p.by_quote for p in scores),
                      sum(p.same_category for p in scores), [])
    lines.append(f"| **pooled** | {total.stalls} | {total.ledger} | {total.matched} ({total.by_quote}) | "
                 f"{total.precision:.2f} | {total.recall:.2f} | {total.same_category} |")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--ledger", required=True, type=Path)
    ap.add_argument("--stalls", required=True, type=Path)
    ap.add_argument("--page", action="append", default=[])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    stalls, ledger = load_jsonl(args.stalls), load_jsonl(args.ledger)
    pages = args.page or sorted({r["page"] for r in stalls})
    scores = score(stalls, ledger, pages)
    if args.json:
        print(json.dumps([{**{k: v for k, v in vars(p).items()}, "precision": p.precision, "recall": p.recall}
                          for p in scores], indent=2))
    else:
        print(table(scores))
    return 0


if __name__ == "__main__":
    sys.exit(main())
