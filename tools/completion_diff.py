#!/usr/bin/env python3
"""Stage B — K1-vs-AA Kotlin-completion differential.

Reads the two raw candidate dumps produced over the SAME fixture corpora:
  - K1: build/completion-corpus/k1-candidates.json  (jvm-main probe kotlin.completion-diffdump.probe)
  - AA: build/completion-corpus/aa-candidates.json   (sidecar spike --json-out)
and diffs them per fixture on:
  - RECALL: is the intended member present in the candidate set (rank shown, order not asserted)?
  - ANTI-GOALS: no synthetic-wrapper leak; no keyword-after-dot; return-type shown for eyeballing.

Prints a readable side-by-side + a machine summary, and a verdict (AA matches-or-beats K1 recall and
passes all anti-goals). Report-only by default (exit 0); pass --gate to exit nonzero on a failing
verdict (for Stage D). Pass --run to (re)generate both dumps first.

This is the "single runner" of Stage B: `python3 tools/completion_diff.py --run`.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

# Kotlin hard keywords that must never appear as members after a `.` (a classic K1 wart).
KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
    "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set",
    "setparam", "value", "where", "it",
}

# Fixtures where AA is expected to under-recall vs K1 because the receiver is a *dynamic* GPU-DSL type
# that only resolves inside the live box's runtime context (the `defaultBuffer{}` / ComputeMesh receiver
# chain). K1 gets them for free by running in-process in the box; the headless AA session has no box
# preamble. Tracked as known-hard (plan Stage A/C) so the gate flags only *unexpected* regressions.
# Stage C (real inherited preamble/receivers) is where these should be revisited.
KNOWN_HARD_REGRESSIONS = {
    "field_probes#0 .vec4s",
    "field_probes#0 .buffer",
}


# ---------- K1 matchers (over SourceCodeCompletionVariant: text/displayText/tail/icon) ----------
def k1_rank(cands: list[dict], member: str) -> int:
    if not member:
        return -1
    m = member
    for i, c in enumerate(cands):
        text, disp = c.get("text", ""), c.get("displayText", "")
        if (text == m or disp.startswith(f"{m}(") or disp.startswith(f"{m} ")
                or disp.startswith(f"{m} =") or disp.startswith(f"{m}=")
                or (c.get("icon") == "class" and text.lower() == m.lower())):
            return i
    return -1


def k1_leak(cands: list[dict]) -> int:
    for i, c in enumerate(cands):
        blob = f"{c.get('text','')} {c.get('displayText','')} {c.get('tail','')}"
        low = blob.lower()
        if "_completion" in low or "__code_" in low or "diffdump" in low or "eval_" in low:
            return i
    return -1


def k1_keyword_after_dot(cands: list[dict]) -> list[str]:
    return sorted({c.get("text", "") for c in cands if c.get("text", "") in KOTLIN_KEYWORDS})


def k1_type_of(cands: list[dict], rank: int) -> str:
    if rank < 0:
        return ""
    return (cands[rank].get("tail", "") or "").strip()


# ---------- AA matchers (over AaCandidate: name/kind/returnType/container) ----------
AA_WRAPPER_NAMES = {"__c", "__ctx__", "AaCompletion", "Ctx", "Spike"}


def aa_rank(cands: list[dict], member: str) -> int:
    if not member:
        return -1
    for i, c in enumerate(cands):
        if c.get("name", "") == member:
            return i
    return -1


def aa_leak(cands: list[dict]) -> int:
    for i, c in enumerate(cands):
        name = c.get("name", "")
        cont = str(c.get("container", "") or "")
        if name in AA_WRAPPER_NAMES or name.startswith("Spike") or "__ctx__" in cont or "AaCompletion" in cont:
            return i
    return -1


def aa_keyword_after_dot(cands: list[dict]) -> list[str]:
    return sorted({c.get("name", "") for c in cands if c.get("name", "") in KOTLIN_KEYWORDS})


def aa_type_of(cands: list[dict], rank: int) -> str:
    if rank < 0:
        return ""
    return (cands[rank].get("returnType", "") or "").strip()


def load(path: Path) -> dict[str, dict]:
    if not path.exists():
        sys.exit(f"missing dump: {path}\n  (run with --run to generate it)")
    return {f["id"]: f for f in json.loads(path.read_text())}


def regenerate(workspace: Path) -> None:
    """Recompile, regen classpath, and rerun both dumpers."""
    env = os.environ.copy()
    classpath_file = workspace / "build/completion-corpus/fieldcp.txt"
    classpath_file.parent.mkdir(parents=True, exist_ok=True)
    print("[run] compileKotlinJvm …", flush=True)
    subprocess.run(["./gradlew", "compileKotlinJvm", "--console=plain"], cwd=workspace, check=True)
    print("[run] regenerating field classpath …", flush=True)
    cp = subprocess.run(
        ["./gradlew", "-q", "--init-script", "tools/print_field_classpath.init.gradle.kts", "printFieldCp"],
        cwd=workspace, check=True, capture_output=True, text=True,
    )
    lines = [ln[len("CP\t"):] for ln in cp.stdout.splitlines() if ln.startswith("CP\t")]
    classpath_file.write_text("\n".join(lines) + "\n")
    print(f"[run] classpath: {len(lines)} entries → {classpath_file}", flush=True)
    print("[run] AA dump (sidecar spike) …", flush=True)
    subprocess.run(
        ["./gradlew", "-p", "tools/code-use-semantic-sidecar", "runSpike", "--console=plain",
         "-PspikeArgs=--workspace {ws} --corpus tools/completion-corpus/scraped.tsv,tools/completion-corpus/name-fixtures.tsv --classpath-file {cp} --json-out build/completion-corpus/aa-candidates.json".format(ws=workspace, cp=classpath_file)],
        cwd=workspace, check=True,
    )
    print("[run] K1 dump (jvm-main probe) …", flush=True)
    subprocess.run([sys.executable, "tools/run_field_tests.py", "--refresh-catalog", "--id", "kotlin.completion-diffdump.probe"],
                   cwd=workspace, check=True, env=env)


def main() -> int:
    ap = argparse.ArgumentParser(description="K1-vs-AA Kotlin completion differential")
    ap.add_argument("--workspace", default=os.getcwd())
    ap.add_argument("--k1", default="build/completion-corpus/k1-candidates.json")
    ap.add_argument("--aa", default="build/completion-corpus/aa-candidates.json")
    ap.add_argument("--run", action="store_true", help="regenerate both dumps before diffing")
    ap.add_argument("--gate", action="store_true", help="exit nonzero if the verdict fails (Stage D)")
    ap.add_argument("--json-summary", default="", help="also write the machine summary to this path")
    args = ap.parse_args()

    workspace = Path(args.workspace).resolve()
    if args.run:
        regenerate(workspace)

    k1 = load(workspace / args.k1)
    aa = load(workspace / args.aa)
    ids = [i for i in aa if i in k1]  # diff only fixtures present in both dumps
    only_k1 = sorted(set(k1) - set(aa))
    only_aa = sorted(set(aa) - set(k1))

    print("═" * 100)
    print(f"K1-vs-AA COMPLETION DIFFERENTIAL   ({len(ids)} shared fixtures)")
    if only_k1:
        print(f"  ! only in K1 dump: {only_k1}")
    if only_aa:
        print(f"  ! only in AA dump: {only_aa}")
    print("═" * 100)
    hdr = f"{'FIXTURE':<34}{'POS':<5}{'MEMBER':<16}{'K1':<10}{'AA':<10}  NOTES"
    print(hdr)
    print("─" * 100)

    k1_hits = aa_hits = 0
    regressions: list[str] = []   # K1 had it, AA lost it
    wins: list[str] = []          # AA has it, K1 didn't
    k1_leaks: list[str] = []
    aa_leaks: list[str] = []
    k1_kw: list[str] = []
    aa_kw: list[str] = []
    type_notes: list[str] = []

    for fid in ids:
        f_k1, f_aa = k1[fid], aa[fid]
        member = f_aa.get("member", "")
        is_dot = f_aa.get("isDot", False)
        c_k1, c_aa = f_k1.get("candidates", []), f_aa.get("candidates", [])

        rk, ra = k1_rank(c_k1, member), aa_rank(c_aa, member)
        if rk >= 0:
            k1_hits += 1
        if ra >= 0:
            aa_hits += 1
        if rk >= 0 and ra < 0:
            regressions.append(fid)
        if ra >= 0 and rk < 0:
            wins.append(fid)

        # anti-goals
        if k1_leak(c_k1) >= 0:
            k1_leaks.append(fid)
        if aa_leak(c_aa) >= 0:
            aa_leaks.append(fid)
        if is_dot:
            kws = k1_keyword_after_dot(c_k1)
            if kws:
                k1_kw.append(f"{fid}({','.join(kws[:5])})")
            awa = aa_keyword_after_dot(c_aa)
            if awa:
                aa_kw.append(f"{fid}({','.join(awa[:5])})")

        # return type eyeball (only when both resolve the member)
        note = ""
        if rk >= 0 and ra >= 0:
            t1, t2 = k1_type_of(c_k1, rk), aa_type_of(c_aa, ra)
            if t1 and t2:
                note = f"type K1[{t1}] AA[{t2}]"
                type_notes.append(f"{fid}: K1={t1!r} AA={t2!r}")

        k1_cell = f"✓#{rk+1}" if rk >= 0 else "✗"
        aa_cell = f"✓#{ra+1}" if ra >= 0 else "✗"
        flag = "  ⟵ REGRESSION" if fid in regressions else ("  ⟵ win" if fid in wins else "")
        print(f"{fid:<34}{('DOT' if is_dot else 'NAME'):<5}{member:<16}{k1_cell:<10}{aa_cell:<10}  {note}{flag}")

    n = len(ids)
    unexpected_regressions = [r for r in regressions if r not in KNOWN_HARD_REGRESSIONS]
    known_hard_hit = [r for r in regressions if r in KNOWN_HARD_REGRESSIONS]
    print("─" * 100)
    print(f"RECALL:   K1 {k1_hits}/{n}   AA {aa_hits}/{n}")
    print(f"REGRESSIONS (K1 had, AA lost): {len(regressions)}  {regressions}")
    print(f"  ├─ known-hard (dynamic GPU-DSL, Stage C): {len(known_hard_hit)}  {known_hard_hit}")
    print(f"  └─ UNEXPECTED: {len(unexpected_regressions)}  {unexpected_regressions}")
    print(f"WINS (AA has, K1 lacked):      {len(wins)}  {wins}")
    print(f"ANTI-GOAL leaks:      K1 {len(k1_leaks)} {k1_leaks}   |   AA {len(aa_leaks)} {aa_leaks}")
    print(f"ANTI-GOAL kw-after-.: K1 {len(k1_kw)} {k1_kw}   |   AA {len(aa_kw)} {aa_kw}")

    # Acceptance per plan Stage D: AA matches-or-beats K1 recall AND passes all anti-goals; plus no
    # regression outside the documented known-hard set (a compensating win must not mask a real loss).
    passed = aa_hits >= k1_hits and not aa_leaks and not aa_kw and not unexpected_regressions
    print("═" * 100)
    print(f"VERDICT: {'PASS' if passed else 'FAIL'}  "
          f"(AA recall {aa_hits} {'>=' if aa_hits >= k1_hits else '<'} K1 {k1_hits}; "
          f"AA anti-goals {'clean' if not aa_leaks and not aa_kw else 'VIOLATED'}; "
          f"unexpected regressions {len(unexpected_regressions)})")

    if args.json_summary:
        summary = {
            "shared": n, "k1_recall": k1_hits, "aa_recall": aa_hits,
            "regressions": regressions, "unexpected_regressions": unexpected_regressions,
            "known_hard_regressions": known_hard_hit, "wins": wins,
            "k1_leaks": k1_leaks, "aa_leaks": aa_leaks,
            "k1_keyword_after_dot": k1_kw, "aa_keyword_after_dot": aa_kw,
            "type_notes": type_notes, "passed": passed,
        }
        Path(workspace / args.json_summary).write_text(json.dumps(summary, indent=2))
        print(f"[summary] wrote {args.json_summary}")

    return (0 if passed or not args.gate else 1)


if __name__ == "__main__":
    sys.exit(main())
