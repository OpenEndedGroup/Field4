#!/usr/bin/env python3
"""Parse the retained passages under src/jvmMain/resources/afield/sentences/texts/ into compact
dependency JSON beside them, so `SentenceTexts.load(...)` and the dependency-length page need no parser.

Run it with the prepared parser environment (`./gradlew prepareSentenceParser`):

    optional/sentence-parser/.venv/bin/python tools/parse_sentence_texts.py            # every passage
    optional/sentence-parser/.venv/bin/python tools/parse_sentence_texts.py gettysburg # one

A passage file is `# title: ...` and `# source: ...` lines, then paragraphs separated by blank
lines. Each paragraph is parsed on its own, so Stanza's sentence splitter never joins two. A
`# sentences: punctuation` line is for verse: Stanza's splitter starts a new sentence at a capital
that begins a line, so such a passage is split at `.`, `?` and `!` instead, before parsing. The
JSON keeps, per sentence, the words, both tag sets and the Universal Dependencies tree as one
head and one relation per word; the constituency tree and the derived Stanford representations
are left out, which keeps a chapter at tens of kilobytes rather than megabytes.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXTS = ROOT / "src/jvmMain/resources/afield/sentences/texts"
sys.path.insert(0, str(ROOT / "python"))


def read_passage(path: Path) -> tuple[dict[str, str], list[str]]:
    meta: dict[str, str] = {}
    body: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# ") and ":" in line and not body:
            key, value = line[2:].split(":", 1)
            meta[key.strip()] = value.strip()
        else:
            body.append(line)
    paragraphs = [" ".join(p.split()) for p in "\n".join(body).split("\n\n")]
    return meta, [p for p in paragraphs if p]


def split_on_punctuation(paragraph: str) -> list[str]:
    """Orthographic sentences: a sentence ends at `.`, `?` or `!` (and any closing quote) before a space."""
    return [s for s in re.split(r"(?<=[.?!])\s+|(?<=[.?!][’”'])\s+", paragraph) if s.strip()]


def write(doc: dict, out: Path) -> None:
    """The header fields, then one sentence per line, so a reparse diffs sentence by sentence."""
    head = {k: v for k, v in doc.items() if k != "sentences"}
    lines = [json.dumps(s, ensure_ascii=False) for s in doc["sentences"]]
    text = json.dumps(head, ensure_ascii=False, indent=1)[:-2]
    text += ',\n "sentences": [\n' + ",\n".join(lines) + "\n ]\n}\n"
    out.write_text(text, encoding="utf-8")


def compact(parsed: dict, paragraph: int) -> dict:
    tokens = parsed["tokens"]
    heads = [None] * len(tokens)
    rels = [None] * len(tokens)
    for edge in parsed["dependencies"]["ud"]:
        i = edge["dep"] - 1
        if heads[i] is not None:
            raise ValueError(f"token {edge['dep']} has two heads in {parsed['text']!r}")
        heads[i], rels[i] = edge["gov"], edge["rel"]
    if any(h is None for h in heads):
        raise ValueError(f"a token has no head in {parsed['text']!r}")
    if heads.count(0) != 1:
        raise ValueError(f"{heads.count(0)} roots in {parsed['text']!r}")
    return {
        "text": parsed["text"],
        "paragraph": paragraph,
        "words": [t["word"] for t in tokens],
        "upos": [t.get("upos") or "" for t in tokens],
        "xpos": [t.get("pos") or "" for t in tokens],
        "heads": heads,
        "rels": rels,
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("keys", nargs="*", help="passage names (file stems); default every .txt")
    ap.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    args = ap.parse_args(argv)

    from fieldsentences.core import analyse
    import stanza

    paths = [TEXTS / f"{k}.txt" for k in args.keys] if args.keys else sorted(TEXTS.glob("*.txt"))
    for path in paths:
        started = time.time()
        meta, paragraphs = read_passage(path)
        by_punctuation = meta.get("sentences") == "punctuation"
        sentences = []
        for p, text in enumerate(paragraphs):
            if by_punctuation:
                text = "\n\n".join(split_on_punctuation(text))
            for s in analyse(text, backend="stanza", lang="en", device=args.device,
                             split_sentences=not by_punctuation):
                sentences.append(compact(s, p))
        doc = {
            "key": path.stem,
            "title": meta.get("title", path.stem),
            "source": meta.get("source", ""),
            "parser": f"Stanza {stanza.__version__} (English default package) through fieldsentences",
            "sentences": sentences,
        }
        out = path.with_suffix(".json")
        write(doc, out)
        print(f"{path.stem}: {len(sentences)} sentences, {out.stat().st_size // 1024} KiB, "
              f"{time.time() - started:.1f}s", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
