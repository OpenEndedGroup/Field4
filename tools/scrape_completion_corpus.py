#!/usr/bin/env python3
"""Scrape Kotlin `diffstatic{_runner}` doc code blocks into completion corpus fixtures.

Emits tools/completion-corpus/scraped.tsv (label<TAB>base64(code with ‸ caret)<TAB>intendedMember).
Each fixture drops a caret mid-member-access (keeps 2 chars after a `.`) and deletes the remainder
of the line — simulating typing up to that point in real doc code. Consumed by
afield.CompletionDocScrapeProbe (FIELD_TEST kotlin.completion-docscrape.probe).
"""
import re, os, base64, sys

CARET = '‸'
COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 22
FENCE = re.compile(r'^```diffstatic(_runner)?\s*\{[^}]*data-lang=kotlin')
DOT = re.compile(r'\.([a-z][A-Za-z0-9]{2,})')
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

def sites(lines):
    fx = []
    for li, line in enumerate(lines):
        s = line.strip()
        if s.startswith('import ') or s.startswith('//'):
            continue
        for m in DOT.finditer(line):
            ident, cut = m.group(1), m.start(1) + 2
            fixture = '\n'.join(lines[:li] + [line[:cut]])
            if 8 <= len(fixture) <= 380:
                fx.append((ident, fixture + CARET))
    return fx

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    picked, seen = [], set()
    for d in DOCS:
        p = os.path.join(root, 'public/docs', f'{d}.md')
        if not os.path.exists(p):
            continue
        for bi, body in enumerate(blocks(p)):
            for member, fx in sites(clean(body)):
                caretline = fx.split(CARET)[0].split('\n')[-1].strip()
                key = (d, member)  # one fixture per (doc, member) — avoid near-duplicate sites
                if key in seen or caretline in seen:
                    continue
                seen.add(key); seen.add(caretline)
                picked.append((d, bi, member, fx))
    from collections import defaultdict
    bydoc = defaultdict(list)
    for it in picked:
        bydoc[it[0]].append(it)
    chosen = []
    for rnd in range(8):
        for d in DOCS:
            if len(bydoc[d]) > rnd:
                chosen.append(bydoc[d][rnd])
    chosen = chosen[:COUNT]
    outp = os.path.join(root, 'tools/completion-corpus/scraped.tsv')
    with open(outp, 'w', encoding='utf-8') as f:
        for (d, bi, member, fx) in chosen:
            f.write(f'{d}#{bi} .{member}\t{base64.b64encode(fx.encode()).decode()}\t{member}\n')
    print(f'wrote {len(chosen)} fixtures to {outp} (of {len(picked)} candidates across {len(bydoc)} docs)')
    nl = '\n'
    for (d, bi, member, fx) in chosen:
        disp = fx.replace(nl, '\\n')[:90]
        print(f'  {d}#{bi} .{member:20} | {disp}')

if __name__ == '__main__':
    main()
