#!/usr/bin/env python3
"""Inventory collection-literal examples and prepare real-compiler inputs.

This is a coverage report, not a regex compilation verdict. Run the selected transcript
owners through checkBoxCodeCompiles; compile unowned.json with CollectionLiteralCompileAudit
via run-jvm-main. Fragments needing earlier declarations remain explicit diagnostics.
Generated copies, document mirrors, retained history and non-Kotlin guests are not new owners.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys

from inventory_box_code import Inventory, digest
from lint_tautological_guards import strip_noise

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "public"))
from docs_hooks import extract_effective_code


def literal_lines(code: str) -> list[int]:
    # Keep only expression starts, excluding ordinary xs[index]. This deliberately selects
    # candidates rather than claiming to replace Kotlin PSI or overload resolution.
    masked = strip_noise(code)
    return sorted({masked.count("\n", 0, m.end() - 1) + 1 for m in re.finditer(
        r"(?:^|[=,(>:{+\-]|\b(?:return|in))\s*\[", masked, re.M)})


def transcript_owner(path: str) -> bool:
    return path.startswith("dev_docs/agents/transcripts/") and path.count("/") == 3 and path.endswith(".yaml")


class SourceInventory(Inventory):
    def add(self, path, locator, code, *args, **kwargs):
        before = len(self.rows)
        super().add(path, locator, code, *args, **kwargs)
        if len(self.rows) > before:
            self.rows[-1]["code"] = code


def select(rows: list[dict]) -> tuple[list[dict], list[str], list[dict]]:
    candidates = []
    for row in rows:
        if row["language"] != "kotlin" or row["role"] not in {"source", "docs-fence"}:
            continue
        code = row["code"]
        if row["role"] == "docs-fence":
            code = extract_effective_code(lines=code.splitlines(),
                fence_name=row.get("fence", "kotlin").split()[0],
                page_path=Path(row["path"]), line_number=row.get("line", 1))
        locations = literal_lines(code)
        if locations:
            candidates.append({**row, "code": code, "sha256": digest(code),
                               "rawCodeSha256": row["sha256"], "literalLines": locations})
    owners = sorted({r["path"] for r in candidates if transcript_owner(r["path"])})
    owned = {r["sha256"] for r in candidates if transcript_owner(r["path"])}
    unowned = {}
    for row in candidates:
        if row["sha256"] not in owned:
            unowned.setdefault(row["sha256"], row)
    return candidates, owners, list(unowned.values())


def summarize_results(inputs: list[dict], results: list[dict]) -> dict:
    expected = {r["sha256"] for r in inputs}
    actual = [r["sha256"] for r in results]
    if not expected or len(actual) != len(set(actual)) or set(actual) != expected:
        raise ValueError("Compiler results are empty, duplicated, incomplete or stale; rerun the current manifest")
    for result in results:
        if not isinstance(result.get("compiled"), bool) or not isinstance(result.get("errors"), list):
            raise ValueError("Every source body needs an explicit compiler verdict and diagnostics")
        if result["compiled"] != (not result["errors"]):
            raise ValueError("A compiler verdict contradicts its diagnostics")
    review = [r for r in results if not r["compiled"]]
    return {"checkedUniqueBodies": len(inputs), "compiledStandalone": len(results) - len(review),
            "needsContextOrReview": len(review), "review": review}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=ROOT / "build/example-literals")
    ap.add_argument("--results", type=Path, help="Summarize a complete compiler report against current source hashes")
    args = ap.parse_args()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    inventory = SourceInventory(ROOT, filter(None, tracked))
    inventory.run()
    candidates, owners, unowned = select(inventory.rows)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "transcripts.txt").write_text("\n".join(owners) + "\n")
    (args.out / "unowned.json").write_text(json.dumps(unowned, indent=2) + "\n")
    summary = {"sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
               "candidateOccurrences": len(candidates), "transcriptOwners": len(owners),
               "unownedUniqueBodies": len(unowned), "roles": dict(Counter(r["role"] for r in candidates)),
               "compilationVerdict": "not established by inventory"}
    (args.out / "inventory.json").write_text(json.dumps({"summary": summary, "candidates": candidates,
                                                       "parseFindings": inventory.findings}, indent=2) + "\n")
    if args.results:
        try:
            compiled = summarize_results(unowned, json.loads(args.results.read_text()))
        except ValueError as error:
            print(str(error), file=sys.stderr)
            return 1
        (args.out / "compile-summary.json").write_text(json.dumps(compiled, indent=2) + "\n")
        summary.update({k: v for k, v in compiled.items() if k != "review"})
        summary["compilationVerdict"] = "per-body results; context-dependent fragments are not certified"
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
