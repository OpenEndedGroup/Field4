#!/usr/bin/env python3
"""Record the popup's real candidate payloads for a whole caret corpus, at corpus scale.

The rank a person SEES is not the recall the payload has. Measured 2026-08-07: 96% payload recall
while `moveTo` at a bare `f.` sat at response index 159 of 249 and was not on screen at all. The only
thing in this tree that measured a *visible* rank was a hand-cut 7-case fixture
(`src/jvmTest/resources/fieldSuggest/rank-payloads.json`), which is not enough evidence to rebalance
anything — `dev_docs/completion_aa_sidecar.md` has said so for ten days: widen first, then rebalance.

This is the widening. It drives every caret in a corpus TSV through the **real** completion server —
the code-use semantic sidecar's own `/complete`, the same endpoint `afield.AaCompletionClient` POSTs to
per keystroke, with the same request shape and the same `limit` — and records what came back, verbatim.

    ./gradlew -p tools/code-use-semantic-sidecar run --args="--port 9481 --workspace $PWD"
    python3 tools/record_rank_corpus.py --port 9481 \
        --corpus tools/completion-corpus/rank-carets.tsv \
        --out src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz

### The format, and why it is not JSON

Gzipped, line-oriented, tab-separated, with an interned row table:

    #meta   key value
    R       text  displayText  tail  icon  declaredIn  proximity        (one per distinct row)
    C       label form receiver truth base64(code with ‸) i,i,i,…       (one per caret)

Interned because the row vocabulary repeats enormously across cases — every `String` receiver in the
corpus ships the same 299 rows — and line-oriented because the consumer,
`afield.FieldSuggestPresentationTest`, has no JSON library on its test classpath and already
hand-writes the probe spec for the 7-case fixture. Measured on the first full recording: 8.6 MB of
raw payload becomes 355 KB on disk, a 24× saving, and 96% of that is the interning rather than the
gzip.

### What is recorded that the 7-case fixture does not record

`proximity` (`own`/`supertype`/`universal`). `fieldSuggest.js` scores it at 0 / −20 / **−85** — the
largest single negative term in the ranker — and `rank-payloads.json` has never carried it, so every
rank ever asserted in this repo was computed with that term silently zero for all rows. That makes the
old numbers *optimistic* for the universal extensions (`also`, `apply`, `let`) and pessimistic for a
receiver's own members. Recorded here, so the wide numbers are the popup's.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CARET = '‸'


def classpath_roots(workspace: Path, cp_file: Path) -> list[str]:
    """Field's binary classpath, the way `AaCompletionClient.classpathRoots` computes it: the jars
    plus Field's OWN compiled output, which `jvmRuntimeClasspath` does not include (deps only) and
    without which no `afield.*` type resolves a single member."""
    roots = [ln.strip() for ln in cp_file.read_text().splitlines() if ln.strip()]
    roots += [str(workspace / p) for p in ("build/classes/kotlin/jvm/main", "build/processedResources/jvm/main")]
    return [r for r in dict.fromkeys(roots) if os.path.exists(r)]


def post(url: str, payload: dict, timeout: float) -> dict:
    body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode())


def clean(value: str) -> str:
    """Tabs and newlines are the record separators, so they cannot survive in a field. Neither appears
    in a Kotlin signature the sidecar renders; this is a guard, not a transformation, and it reports."""
    return str(value or "").replace("\t", " ").replace("\r", " ").replace("\n", " ")


def receiver_of(candidates: list[dict]) -> str:
    """The receiver the caret is on, as the popup's own prior resolves it: `receiverQualifiedName`,
    which the sidecar stamps on every dot-member row. Taken as the most common non-blank value rather
    than the first, because a handful of rows on a generic receiver carry a type-variable bound."""
    seen: dict[str, int] = {}
    for c in candidates:
        q = str(c.get("receiverQualifiedName") or "").strip()
        if q:
            seen[q] = seen.get(q, 0) + 1
    if not seen:
        return ""
    return max(seen.items(), key=lambda kv: kv[1])[0]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workspace", default=os.getcwd())
    ap.add_argument("--port", type=int, default=9481)
    ap.add_argument("--corpus", default="tools/completion-corpus/rank-carets.tsv")
    ap.add_argument("--out", default="src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz")
    ap.add_argument("--classpath-file", default="build/completion-corpus/fieldcp.txt")
    # 1200 is what AaCompletionClient sends. Anything smaller deletes rows the ranker never sees and
    # would make this measurement kinder than the popup.
    ap.add_argument("--limit", type=int, default=1200)
    ap.add_argument("--timeout", type=float, default=90.0)
    ap.add_argument("--max-cases", type=int, default=0, help="stop after N carets (0 = all)")
    args = ap.parse_args()

    workspace = Path(args.workspace).resolve()
    url = f"http://127.0.0.1:{args.port}/complete"
    roots = classpath_roots(workspace, workspace / args.classpath_file)
    print(f"[record] {len(roots)} classpath roots, sidecar {url}", flush=True)

    fixtures = []
    for line in (workspace / args.corpus).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        label, b64, member = line.split("\t")[:3]
        code = base64.b64decode(b64).decode()
        fixtures.append((label, code, member))
    if args.max_cases:
        fixtures = fixtures[: args.max_cases]
    print(f"[record] {len(fixtures)} carets from {args.corpus}", flush=True)

    # Wait for the AA session: the first request over a cold classpath returns "session building" (it
    # takes ~1 min) rather than stalling a keystroke, so a recorder that trusted the first reply would
    # write an empty corpus and a green-looking gate over nothing.
    probe = {"snippet": "val s = \"x\"\ns.", "cursor": 14, "classpathRoots": roots, "limit": 20}
    for attempt in range(60):
        reply = post(url, probe, args.timeout)
        if reply.get("candidates"):
            print(f"[record] AA session warm after {attempt * 5}s ({reply.get('backend')})", flush=True)
            break
        print(f"  … {reply.get('message', '')!r}", flush=True)
        time.sleep(5)
    else:
        sys.exit("sidecar never produced candidates — see its log")

    row_index: dict[tuple, int] = {}
    rows: list[tuple] = []
    cases = []
    skipped_pipe = skipped_empty = 0
    sanitised = 0
    sizes, times = [], []
    for i, (label, code, member) in enumerate(fixtures):
        snippet = code.replace(CARET, "")
        cursor = code.index(CARET)
        # `probe.mjs` marks the caret with `|`, so a snippet containing one cannot be replayed through
        # it. Skipped and counted rather than mangled.
        if "|" in snippet:
            skipped_pipe += 1
            continue
        t0 = time.time()
        try:
            reply = post(url, {"snippet": snippet, "cursor": cursor, "classpathRoots": roots,
                               "limit": args.limit}, args.timeout)
        except (urllib.error.URLError, TimeoutError) as error:
            print(f"  !! {label}: {error}", flush=True)
            skipped_empty += 1
            continue
        times.append(time.time() - t0)
        candidates = reply.get("candidates") or []
        if not candidates:
            skipped_empty += 1
            continue
        indices = []
        for c in candidates:
            fields = (clean(c.get("text")), clean(c.get("displayText")), clean(c.get("tail")),
                      clean(c.get("icon")), clean(c.get("declaredIn")), clean(c.get("proximity")))
            if any("\t" in str(c.get(k) or "") or "\n" in str(c.get(k) or "")
                   for k in ("text", "displayText", "tail", "icon", "declaredIn", "proximity")):
                sanitised += 1
            idx = row_index.get(fields)
            if idx is None:
                idx = len(rows)
                row_index[fields] = idx
                rows.append(fields)
            indices.append(idx)
        form = "bare" if label.endswith("@bare") else "prefixed"
        cases.append({
            "label": label, "form": form, "receiver": receiver_of(candidates),
            "truth": member, "code": base64.b64encode(code.encode()).decode(), "rows": indices,
        })
        sizes.append(len(candidates))
        if (i + 1) % 50 == 0:
            print(f"  [{i + 1}/{len(fixtures)}] {len(cases)} recorded, {len(rows)} distinct rows", flush=True)

    out = workspace / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write(f"#meta\tversion\t1\n")
        f.write(f"#meta\tcorpus\t{args.corpus}\n")
        f.write(f"#meta\tlimit\t{args.limit}\n")
        f.write(f"#meta\tcases\t{len(cases)}\n")
        f.write(f"#meta\trows\t{len(rows)}\n")
        for r in rows:
            f.write("R\t" + "\t".join(r) + "\n")
        for c in cases:
            joined = ",".join(str(i) for i in c["rows"])
            f.write(f"C\t{c['label']}\t{c['form']}\t{c['receiver']}\t{c['truth']}\t{c['code']}\t{joined}\n")

    total_rows = sum(sizes)
    print("─" * 78)
    print(f"recorded {len(cases)} carets, {total_rows} payload rows, {len(rows)} distinct "
          f"({total_rows / max(1, len(rows)):.1f}× interning)")
    print(f"  rows per caret: median {statistics.median(sizes) if sizes else 0:.0f}, "
          f"max {max(sizes) if sizes else 0}")
    print(f"  server time per caret: median {1000 * statistics.median(times):.0f}ms" if times else "")
    print(f"  skipped: {skipped_pipe} with a literal `|` in the snippet, {skipped_empty} with no candidates")
    print(f"  fields needing sanitising (tab/newline): {sanitised}")
    print(f"→ {out}  ({out.stat().st_size / 1024:.0f} KB gzipped)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
