# Nightly worklog — completion-aa-sidecar-explainer (kind C: doc/explainer)

Branch: `nightly/2026-07-20/3-completion-aa-sidecar-explainer`
Worktree: `/Users/marc/field4-worktrees/2026-07-20-3-completion-aa-sidecar-explainer`

## Mission

Write `dev_docs/completion_aa_sidecar.md` — the missing developer-facing architecture map for the
K2 Analysis-API (AA) completion backend that became the DEFAULT in commit `fd8f660b`. The existing
`dev_docs/completion_k2_migration_plan.md` is a migration PLAN, not an architecture explainer.

## Plan (outline of the new doc)

1. **Why** — K1 vs K2/Analysis-API; why out-of-process (AA pulls an IntelliJ-style classpath that must
   not be mixed into the live-coding runtime; separate JVM keeps the runtime clean).
2. **Request path end-to-end** — editor → `Server.kt` `complete` handler → `execution.kt` `complete()`
   → `KotlinInterface2.complete()` → `AaCompletionClient.complete()` → sidecar `/complete`
   → `K2CompletionAnalyzer` → `AaCompleter.complete()` (Analysis API) → structured candidates back →
   client-side ranking (`fieldSuggest.js`). Cite `file.kt:line`.
3. **Sidecar process/lifecycle** — `tools/code-use-semantic-sidecar/`, `field.sh` launch, port
   derivation, warmup (background session build; empty→K1 during warmup), session/index caching.
4. **Backend selection + fallback** — `KotlinEngineConfig.activeCompletionBackend`,
   `-Dafield.kotlin.completion=k1`, empty/error/timeout/no-URL → K1 fallback.
5. **Verification tooling** — `CompletionAaE2EProbe`, `CompletionDiffK1Probe`,
   `tools/completion_diff.py [--run|--gate]`, corpus under `tools/completion-corpus/`.

## Steps

- [x] Read the real code (AaCompletion.kt, CodeUseSemanticSidecar.kt, AaCompletionClient.kt,
  KotlinInterface2.kt, KotlinScriptEngine.kt, execution.kt, Server.kt, probes, completion_diff.py,
  fieldSuggest.js, field.sh, sidecar build.gradle.kts).
- [ ] Write `dev_docs/completion_aa_sidecar.md`.
- [ ] Add a pointer from the plan doc to the new architecture doc.
- [ ] Grep-confirm every named file/flag/function (evidence below).
- [ ] Run a real probe (`python3 tools/completion_diff.py --run`) as runnable evidence.
- [ ] Finish worklog + recommendation.

## What I wrote

- `dev_docs/completion_aa_sidecar.md` (new) — the architecture explainer. Sections: TL;DR + ASCII flow;
  §1 Why (K1 vs AA, why out-of-process); §2 end-to-end request path with `file.kt:line` citations at
  every hop; §3 sidecar process/lifecycle; §4 backend selection + fallback table; §5 verification
  tooling + a real captured probe run; §6 debugging playbook; §7 related docs.
- `dev_docs/completion_k2_migration_plan.md` — added a short pointer (near the top) to the new doc,
  framing the plan as the migration *narrative* and the new doc as the *architecture map*.
- Committed as `926e1595`.

## Grep-confirmation of every named claim (all PASS)

| Claim | Confirmed at |
|---|---|
| Server `complete` handler decodes `CompletionRequest` | `Server.kt:2162`, `:2164` |
| `execution.kt` `complete()` impl + `inside.ki.complete(allCode, position)` | `execution.kt:2757`, `:3125` |
| KI2 `complete()` + AA branch + K1 fallback | `KotlinInterface2.kt:552`, `:561`, `:566`, `:577` |
| `warmCompletion` | `KotlinInterface2.kt:587` |
| `enum CompletionBackend { K1, AA }` + default `"aa"` | `KotlinScriptEngine.kt:697`, `:699` |
| `AaCompletionClient` object + `endpoint()` + all 6 fallback details | `AaCompletionClient.kt:21,65,91,92,110,112,116,144` |
| Sidecar `/health` `/complete` `/analyze`; `K2CompletionAnalyzer.complete`; warmup empty-success; `buildStandaloneAnalysisAPISession`; `AaSymbolIndex.buildOrLoad` | `CodeUseSemanticSidecar.kt:190,194,212,658,667,713,740` |
| `AaCompleter`, `complete()`, `splitPreamble`, `completeAt`, `AaSymbolIndex`, `buildOrLoad` | `AaCompletion.kt:271,295,349,381,90,112` |
| `field.sh` port derivation, `ensure_semantic_sidecar`, nohup gradlew run | `field.sh:19,71,102`, `off` switch `:72` |
| client-side ranking score composition (`fuzzy+category+recency+contextScore`) | `fieldSuggest.js:662,685,692-699` |
| probe FIELD_TEST ids | `CompletionAaE2EProbe.kt:11` (`kotlin.completion-aa-e2e.probe`), `CompletionDiffK1Probe.kt:12` (`kotlin.completion-diffdump.probe`) |
| AA IntelliJ classpath deps + `runSpike` task | `code-use-semantic-sidecar/build.gradle.kts:14,54,67` |

## Runnable probe evidence (REAL, not fabricated)

Ran the canonical single runner end-to-end on this branch:

```
python3 tools/completion_diff.py --run --json-summary build/completion-corpus/diff-summary.json
```

This recompiled `compileKotlinJvm`, regenerated the Field classpath, ran the **AA sidecar spike**
(exercising the real `AaCompleter` + `AaSymbolIndex` against `tools/completion-corpus/*.tsv`), ran the
**K1 jvm-main probe** (`kotlin.completion-diffdump.probe`), and diffed. Real observed result:

```
RECALL:   K1 23/31   AA 23/31
REGRESSIONS (K1 had, AA lost): 2  ['field_probes#0 .vec4s', 'field_probes#0 .buffer']
  ├─ known-hard (dynamic GPU-DSL, Stage C): 2
  └─ UNEXPECTED: 0
WINS (AA has, K1 lacked):      2  ['arg#Vector3f-empty', 'arg#Vector3f-afterX']   (named-arg completion)
ANTI-GOAL leaks:      K1 2   |   AA 0
ANTI-GOAL kw-after-.: K1 4   |   AA 0
VERDICT: PASS  (AA recall 23 >= K1 23; AA anti-goals clean; unexpected regressions 0)
```

Artifacts produced: `build/completion-corpus/{aa-candidates.json, k1-candidates.json, diff-summary.json,
aa-symbol-index-*.json}` (all gitignored). Disk stayed healthy (8.4Gi → 7.5Gi free, no ENOSPC).

This run substantiates the doc's central claims: AA is a real out-of-process K2 backend, it matches K1
recall, adds named-argument completion, and is clean on the anti-goals K1 violates.

## Could NOT verify (honest gaps)

- **`CompletionAaE2EProbe` (the live KI2→sidecar chain in a running Field JVM) was not run.** It needs a
  running sidecar + a live box runtime; standing up a full Field JVM + sidecar headless was out of scope
  for a doc task and higher disk risk. The probe is designed to SKIP (exit 0) when no sidecar URL is set,
  so it wouldn't have failed anyway. The differential run above exercises the same `AaCompleter` code the
  E2E chain uses, so the AA candidate-generation claims are still runtime-substantiated; only the HTTP
  hop and in-Field enrichment are grep-confirmed rather than executed.
- The `execution.kt` KDoc-enrichment line numbers (`~2886`, `~3012`, `~3060`) are cited approximately
  ("~") in the doc because that block is long; the anchor calls (`inside.ki.complete` at `:3125`, reply
  at the `Message("complete")` sites) are exact.

## Risks

- Low. Doc-only change; no code touched. The one risk is line-number drift as the cited `.kt` files
  evolve — mitigated by citing stable symbol names alongside every line number.

## Recommendation

**MERGE.** The doc is accurate (every claim grep-confirmed), fills a real gap (there was an architecture
map for the migration *plan* but none for the shipped default backend), and is backed by a real green
differential run. Optional follow-up: run `CompletionAaE2EProbe` against a live sidecar once to capture a
real latency/named-arg transcript for §5, and wire `completion_diff.py --gate` into the nightly CI loop.

