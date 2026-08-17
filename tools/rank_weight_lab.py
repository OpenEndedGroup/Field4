#!/usr/bin/env python3
"""Measure a proposed change to a `fieldSuggest.js` ranking constant over the whole caret corpus.

This exists so that "should `CATEGORY_WEIGHT` learn the server's category vocabulary?" is answered by
running it rather than by arguing about it — and so that the answer recorded in
`dev_docs/completion_rank_at_scale.md` can be re-checked by anyone in ten seconds.

    ./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'   # writes the spec, once
    python3 tools/rank_weight_lab.py                                  # the recorded experiments
    python3 tools/rank_weight_lab.py --set math=24,audio=24 --universal -40

It stages the shipped `fieldSuggest.js` into a temp directory with one constant rewritten, next to the
probe harness, and runs `probe.mjs rankmetrics` over the spec the test dropped in
`build/completion-corpus/rank-spec.json`. Nothing in the repository is modified: a weight table that
only exists in a temp directory cannot accidentally ship.

The spec has to come from the test because the two server-side ranking inputs on every row — the
category from `KotlinCompletionSupport.classifyMetadata` and the prior from `CommonMemberPrior` — are
the server's answers, and a tool that recomputed them would be measuring its own opinion.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# The client's own eleven buckets, unchanged — the left column of `CATEGORY_WEIGHT` as shipped.
BASE = [("local", 28), ("graphics", 26), ("functions", 22), ("values", 20), ("types", 18),
        ("shader", 16), ("metadata", 14), ("imports", 10), ("snippets", 8), ("ai", 6), ("other", 0)]

# The categories the *server* sends that the table above does not know, lowercased the way
# `normalizeCategory` lowercases them. `graphics` is already in the table, which is exactly why
# `FLine.moveTo` was the one bare-dot case that could be fixed by classification alone.
SERVER_ONLY = ["math", "audio", "data", "visualization", "polytopes", "alife", "function", "imaging",
               "machine learning", "solvers", "patterns", "synthesis", "audio ai", "geometry"]

# The experiments behind the refusal recorded in dev_docs/completion_rank_at_scale.md. Each is
# (name, extra weights, universal-proximity override).
EXPERIMENTS = [
    ("R0 as shipped", [], None),
    ("R1 server vocabulary at 24, kotlin/external 0",
     [(c, 24) for c in SERVER_ONLY] + [("kotlin", 0), ("external", 0)], None),
    ("R2 server vocabulary at 12, kotlin 8, external 4",
     [(c, 12) for c in SERVER_ONLY] + [("kotlin", 8), ("external", 4)], None),
    ("R3 kotlin 24 only", [("kotlin", 24)], None),
    ("R4 kotlin 24, external 24", [("kotlin", 24), ("external", 24)], None),
    ("R5 universal proximity -85 -> -40", [], -40),
    ("R6 universal proximity -85 -> -140", [], -140),
]


def rewrite(js: str, extra: list[tuple[str, int]], universal: int | None) -> str:
    table = BASE + list(extra)
    new = ("const CATEGORY_WEIGHT = new Map([\n"
           + "".join(f'  ["{k}", {v}],\n' for k, v in table) + "]);")
    out, n = re.subn(r"const CATEGORY_WEIGHT = new Map\(\[[\s\S]*?\]\);", new, js, count=1)
    if n != 1:
        sys.exit("could not find CATEGORY_WEIGHT in fieldSuggest.js — this tool has rotted")
    if universal is not None:
        out, n = re.subn(r'\["universal", -?\d+\]', f'["universal", {universal}]', out, count=1)
        if n != 1:
            sys.exit("could not find PROXIMITY_SCORE's `universal` entry in fieldSuggest.js")
    return out


def run(workspace: Path, spec: Path, name: str, extra, universal, quiet: bool) -> None:
    js = (workspace / "src/commonMain/resources/fieldSuggest.js").read_text()
    with tempfile.TemporaryDirectory(prefix="rank-weight-lab-") as tmp:
        stage = Path(tmp)
        for f in ("host.mjs", "afield.mjs", "probe.mjs"):
            shutil.copy(workspace / "src/jvmTest/resources/fieldSuggest" / f, stage / f)
        (stage / "fieldSuggest.js").write_text(rewrite(js, extra, universal))
        (stage / "package.json").write_text('{"type": "module"}\n')
        result = subprocess.run(["node", str(stage / "probe.mjs"), "rankmetrics", str(spec)],
                                cwd=stage, capture_output=True, text=True)
    print(f"### {name}")
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if quiet and not (stripped.startswith(("form", "prefixed", "bare", "all"))
                          or "CATEGORY_WEIGHT exposure" in line):
            continue
        print(line)
    verdict = "PASS" if "PASS rankmetrics" in result.stdout else "FAIL"
    fails = " | ".join(x.strip() for x in result.stdout.splitlines() if x.startswith("FAIL"))
    print(f"  {verdict} {fails}".rstrip())
    sys.stdout.flush()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workspace", default=os.getcwd())
    ap.add_argument("--spec", default="build/completion-corpus/rank-spec.json")
    ap.add_argument("--set", default="", help="extra CATEGORY_WEIGHT entries, e.g. math=24,audio=24")
    ap.add_argument("--universal", type=int, default=None, help="override PROXIMITY_SCORE's universal")
    ap.add_argument("--full", action="store_true", help="print every line the probe prints")
    args = ap.parse_args()

    workspace = Path(args.workspace).resolve()
    spec = workspace / args.spec
    if not spec.exists():
        sys.exit(f"{spec} is missing — run `./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'` "
                 "once to have the test drop it")

    if args.set or args.universal is not None:
        extra = [(k.strip(), int(v)) for k, v in
                 (p.split("=") for p in args.set.split(",") if p.strip())]
        run(workspace, spec, "as asked", extra, args.universal, not args.full)
        return 0
    for name, extra, universal in EXPERIMENTS:
        run(workspace, spec, name, extra, universal, not args.full)
    return 0


if __name__ == "__main__":
    sys.exit(main())
