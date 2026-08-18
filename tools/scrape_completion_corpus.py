#!/usr/bin/env python3
"""Scrape Kotlin `diffstatic{_runner}` doc code blocks into completion corpus fixtures.

Emits tools/completion-corpus/scraped.tsv (label<TAB>base64(code with ‸ caret)<TAB>intendedMember).
Each fixture drops a caret mid-member-access (keeps 2 chars after a `.`) and deletes the remainder
of the line — simulating typing up to that point in real doc code. Consumed by
afield.CompletionDocScrapeProbe (FIELD_TEST kotlin.completion-docscrape.probe).

The ground truth is free and honest: for `expr.member` in code a human actually wrote and shipped in
a doc, the member they wrote *is* the label. Nobody had to judge it.

**Two sizes on purpose.** The default is unchanged — 22 fixtures, 8 named docs, 2 chars of prefix —
because that set is the input to the K1-vs-AA *recall* differential (`tools/completion_diff.py`),
whose per-fixture cost is a full AA analysis and whose `KNOWN_HARD_REGRESSIONS` keys on these exact
labels. Regenerating with no arguments must reproduce `scraped.tsv` byte for byte.

`--wide` is the *rank* corpus: every doc in the tree that has a Kotlin block, and both caret forms
per site (see `--forms`). Rank is measured over recorded payloads rather than by re-analysing, so its
per-fixture cost after recording is milliseconds and there is no reason to keep it small:

    python3 tools/scrape_completion_corpus.py --wide --out tools/completion-corpus/rank-carets.tsv

Why two forms. A bare `expr.` and a typed `expr.ab` are different questions for the popup, not two
samples of one: `fuzzyScoreText` returns 0 for an empty query, so the bare form is decided almost
entirely by category weight and the receiver's `@CommonMembers` prior, while the prefixed form is
decided by fuzzy match. Measuring only one of them measures half the ranker — and the 22-fixture
corpus is all prefixed, so until now the bare form had six hand-cut cases in the whole repo.
"""
import argparse
import base64
import os
import re
from collections import defaultdict

CARET = '‸'
FENCE = re.compile(r'^```diffstatic(_runner)?\s*\{[^}]*data-lang=kotlin')
DOT = re.compile(r'\.([a-z][A-Za-z0-9]{2,})')
# The default corpus: eight named docs, chosen for breadth of receiver rather than size.
DOCS = ['linear_algebra', 'functions_examples', 'colors_examples',
        'boolean_networks_examples', 'boolean_network_music_examples',
        'field_probes', 'rectsl', 'stacksl']  # extra docs for breadth


def blocks(path):
    out, L, i = [], open(path, encoding='utf-8').read().split('\n'), 0
    while i < len(L):
        if FENCE.search(L[i]):
            j = i + 1; b = []
            while j < len(L) and not L[j].strip().startswith('```'):
                b.append(L[j]); j += 1
            out.append(b); i = j + 1
        else:
            i += 1
    return out


def clean(body):
    out = [ln.rstrip() for ln in body if not ln.strip().startswith(':::')]
    while out and not out[0].strip(): out.pop(0)
    while out and not out[-1].strip(): out.pop()
    return out


def sites(lines, keep=2):
    """Caret sites in one block. `keep` = how many characters of the member survive the cut, so
    keep=2 is "typed two letters" and keep=0 is a bare `expr.` with nothing to fuzzy-match."""
    fx = []
    for li, line in enumerate(lines):
        s = line.strip()
        if s.startswith('import ') or s.startswith('//'):
            continue
        for m in DOT.finditer(line):
            ident, cut = m.group(1), m.start(1) + keep
            fixture = '\n'.join(lines[:li] + [line[:cut]])
            if 8 <= len(fixture) <= 380:
                fx.append((ident, fixture + CARET))
    return fx


def doc_names(root, wide):
    if not wide:
        return DOCS
    docs = []
    for name in sorted(os.listdir(os.path.join(root, 'public/docs'))):
        if not name.endswith('.md'):
            continue
        path = os.path.join(root, 'public/docs', name)
        if any(FENCE.search(line) for line in open(path, encoding='utf-8')):
            docs.append(name[:-3])
    return docs


def harvest(root, docs, keep):
    """(doc, blockIndex, member, fixture) per accepted site, deduped per (doc, member) and per
    caret line — near-duplicate sites in one doc measure the same receiver twice."""
    picked, seen = [], set()
    for d in docs:
        p = os.path.join(root, 'public/docs', f'{d}.md')
        if not os.path.exists(p):
            continue
        for bi, body in enumerate(blocks(p)):
            for member, fx in sites(clean(body), keep):
                caretline = fx.split(CARET)[0].split('\n')[-1].strip()
                key = (d, member)  # one fixture per (doc, member) — avoid near-duplicate sites
                if key in seen or caretline in seen:
                    continue
                seen.add(key); seen.add(caretline)
                picked.append((d, bi, member, fx))
    return picked


def round_robin(picked, docs, count):
    """Take one site per doc per pass, so a doc with 200 blocks cannot crowd out one with three."""
    bydoc = defaultdict(list)
    for it in picked:
        bydoc[it[0]].append(it)
    chosen, rnd = [], 0
    depth = max((len(v) for v in bydoc.values()), default=0)
    while len(chosen) < count and rnd < depth:
        for d in docs:
            if len(bydoc[d]) > rnd:
                chosen.append(bydoc[d][rnd])
        rnd += 1
    return chosen[:count], len(bydoc)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('count', nargs='?', type=int, default=None, help='fixtures per form (default 22, or 400 with --wide)')
    ap.add_argument('--wide', action='store_true', help='every doc with a Kotlin block, both caret forms')
    ap.add_argument('--forms', default='', help='comma-separated: prefixed,bare (default: prefixed; --wide: both)')
    ap.add_argument('--out', default='tools/completion-corpus/scraped.tsv')
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    docs = doc_names(root, args.wide)
    count = args.count if args.count is not None else (400 if args.wide else 22)
    forms = [f for f in args.forms.split(',') if f] or (['prefixed', 'bare'] if args.wide else ['prefixed'])

    rows, report = [], []
    for form in forms:
        keep = 0 if form == 'bare' else 2
        picked = harvest(root, docs, keep)
        chosen, ndocs = round_robin(picked, docs, count)
        report.append((form, len(chosen), len(picked), ndocs))
        for (d, bi, member, fx) in chosen:
            # The prefixed label is the historical one, unsuffixed, because completion_diff.py's
            # KNOWN_HARD_REGRESSIONS names two of them.
            label = f'{d}#{bi} .{member}' + ('' if form == 'prefixed' else f'@{form}')
            rows.append((label, fx, member, form))

    outp = os.path.join(root, args.out)
    with open(outp, 'w', encoding='utf-8') as f:
        for (label, fx, member, form) in rows:
            f.write(f'{label}\t{base64.b64encode(fx.encode()).decode()}\t{member}\n')
    for (form, n, cand, ndocs) in report:
        print(f'{form:9} {n:4} fixtures (of {cand} candidates across {ndocs} docs)')
    print(f'wrote {len(rows)} fixtures to {outp}')
    nl = '\n'
    for (label, fx, member, form) in rows[:40 if args.wide else len(rows)]:
        disp = fx.replace(nl, '\\n')[:90]
        print(f'  {label:44} | {disp}')
    if args.wide and len(rows) > 40:
        print(f'  … {len(rows) - 40} more')


if __name__ == '__main__':
    main()
