# Nightly 2026-08-01 — branch 3 — `keyframer-worked-example`

Kind: **C — documentation / explainer, with real evidence.**
Worktree: `/Users/marc/field4-worktrees/2026-08-01-3-keyframer-worked-example`
Base: `7abe8736`.

## Plan

### Subject check (done first, as instructed)

`src/jvmMain/kotlin/afield/stdlib/keyframer.kt` (1161 lines) — read end to end before planning.

Findings that shape the plan:

1. **The KDoc is unusually good.** Unlike the usual "undocumented code with no
   tests", this file's KDoc is dense, honest, and already names its own traps
   (`Long` > 2^53 loses precision, unsupported types drop silently, a corrupt
   version manifest restarts numbering, `stackAuto` swallows compile failures).
   So the gap is **not** KDoc — it is (a) zero tests, so none of those documented
   claims is enforced, and (b) no public worked example of the parts that matter.

2. **The subject is NOT untouched publicly** — contrary to the brief. There is
   already a keyframes section on `public/docs/interpolants_examples.md:168-257`
   with one `interpolate` fence covering numbers + vectors + `StackSLString`
   stepping. **A new page must not duplicate it.** Verified by grep: the only
   public-docs files mentioning `KeyframeScope` are `interpolants_examples.md`,
   `staging.md`, and the generated `reference/graphics.md` + `reference/index.md`.

   What is publicly undocumented, and therefore what the new page is about:
   - **targets and the capture/apply loop** — `target(name, Structure)`,
     `@`-qualified names (`"glow@radius"`), lazily-resolved targets, `capture()`,
     and invoking a `Key` to write state back. This is the actual core of the
     class.
   - **camera poses** — `KeyframeScope.camera`, and the fact that camera
     interpolation is a *screw motion* (sclerp) rather than a position lerp.
   - **persistence** — `saveKey`/`loadKey`, the `.versions` history + manifest,
     `loadLatestKey`, `loadKeyVersion`, `keyVersions`, and `missingKey`
     schema migration.
   - **`diff` and `updateCode`** — the "a look you fiddled into being becomes
     source you can commit" loop. One sentence alludes to it today; nothing
     shows it.

   Decision: **keep keyframer as the subject**, but scope the page to the four
   areas above and cross-link the existing `interpolate` fence rather than
   restate it.

3. **The whole core is CPU-testable — no GPU needed.** `afield.four.buffers.Structure`
   is constructible bare (`Structure()`, `Structure.kt:112`, all four ctor params
   defaulted) and `bind`/`bindings` (`Structure.kt:176`, `:136`) are plain map
   operations that do not touch a device. So `target` → `capture()` → `key()` is
   genuinely exercisable in a unit test and in a docs fence. This is what lets the
   page stand on its own without the GPU stretch goal.

### Candidate defect (to confirm by test)

`kotlinRawStringLiteral` (`keyframer.kt:1046`) emits a Kotlin **raw** string for a
`SourceBackedBlock`'s source:

```kotlin
if (!text.contains("\"\"\"") && !text.endsWith("\"")) return "\"\"\"$text\"\"\""
```

It guards against `"""` and a trailing quote but **not against `$`**. Kotlin raw
strings still interpolate `$`, so a source block containing `${...}` or `$name`
round-trips through `updateCode` into code that either fails to compile or
silently interpolates something else. `kotlinStringLiteral` (`:1030`) *does*
escape `$`, so the fix is to widen the guard and fall through to it. Confirm with
a test before claiming it.

Second, weaker find: `Key.invoke()`'s third fallback
(`target.resolve()?.let { it.bindings[k] = binding }`, `:369`) indexes by the raw
key name `k`, not the `@`-stripped property name that `bind` uses. It is
unreachable today (every registered target has a `bind` or a `set`), so this is a
latent inconsistency to note, not to fix blind.

### Work items

1. `src/jvmTest/kotlin/afield/stdlib/KeyframerTest.kt` — first tests for the file.
   Every claim the page makes gets an assertion, plus the KDoc's own documented
   traps, plus independent oracles:
   - slerp/sclerp checked against a *second, different* computation, at
     ≥3 points (t=0, midpoint, t=1) — endpoints exact, midpoint against an
     independently-derived rotation.
   - JSON persistence checked as serialize→deserialize→serialize **byte-identical**.
   - `Long` > 2^53, silent drops, `Int`+`Int`→`Double`, unclamped extrapolation,
     `Quat` not re-normalised vs `DualQuat` re-normalised, `missingKey` precedence,
     version history + `loadLatestKey`, `updateCode` `f`-suffix round trip.
2. The `$` fix in `kotlinRawStringLiteral`, if the test confirms it, with a
   regression test.
3. `public/docs/keyframer_examples.md` — worked example in house style
   (`spatial_geometry_examples.md` is the model: page + test file, tests assert
   the page's numbers). **Vector figures only**: `FLine`s → `showPlaneGrid` →
   `planeFigureHtml` → `display()`. No rasterization; `rasterizeFLinesFramed` is
   for pinning mp4 coordinate frames and is not to be used for stills.
   Planned figures, all CPU-real:
   - the camera screw-motion figure — sclerp path vs a naive position lerp,
     drawn in plan view, which is the one picture that makes dual quaternions
     worth the trouble;
   - a `remap` easing figure — several per-key re-timings on one axis;
   - a capture/apply state figure or diff table from a real `Structure` round trip.
4. Nav entry in `public/zensical.yml`.
5. A loadable demo transcript under `dev_docs/agents/transcripts/`, **replayed
   through the real engine** to `applied=N errors=0`.
6. Build the page single-page + strict; open every figure and check it against
   its caption.

### Risks / constraints

- **Disk is tight** (11 GiB free at start, data volume 100%). Single-page builds
  only; `df -h /` before anything heavy.
- The GPU still (`capture="native-window"`) is a **stretch goal only**, attempted
  after the page stands without it. If it does not come out, it is omitted and
  said so. No synthesized stand-in.

## Progress — final

Status: **complete**. Recommendation: **merge**.

### Files

| file | what | why |
| --- | --- | --- |
| `src/jvmTest/kotlin/afield/stdlib/KeyframerTest.kt` | **new**, 53 tests | `keyframer.kt` had none, so none of its KDoc's honest self-warnings was enforced |
| `src/jvmMain/kotlin/afield/stdlib/keyframer.kt` | 1-line fix + 2 KDoc lines | `updateCode` mangled a source block containing `$` |
| `public/docs/keyframer_examples.md` | **new** public page, 4 real captures | the four areas with no public coverage |
| `public/docs/assets/keyframer_examples/*.png` | 4 captures | all four opened and read; two were changed as a result |
| `public/zensical.yml` | nav entry | |
| `dev_docs/agents/transcripts/keyframer_worked_example.yaml` | **new** loadable demo, 4 boxes | the shippable artifact |
| `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` | registered in `EXECUTE` | an unclassified transcript fails that test on purpose |
| `public/docs/assets/agent_transcripts/keyframer_worked_example/` | materialized starting point | else the drift guard fails the build |
| `public/docs/agent_transcripts.md` | gallery entry | |
| `public/docs/reference/{graphics,index,workspace}.md`, `dev_docs/kdoc_worklist.md` | regenerated | `checkCodebaseReference` was red — see caveat below |

### Verification

- `./gradlew jvmTest` — **986 tests, 0 failures, 0 errors** across 88 classes (not just my module).
  `KeyframerTest` = 53, confirmed three ways (XML report, `@Test` count, published claim).
- `./gradlew checkKDocAttachment` — ok, 702 files / 8275 KDoc blocks.
- `./gradlew checkCodebaseReference` — green (was red; see caveat).
- `run_field_tests.py --id examples.demo-transcript.exec` — PASS,
  `[execute] PASS keyframer_worked_example (applied=8, output lines=20)`,
  `DemoTranscriptExecSmokeTest ok — 35 demos verified`. That harness asserts both `run.success`
  **and** zero error-kind output lines, which is the check that matters: a box compile error does
  not throw, it only shows up as an error-kind output line. **The demo's box code really ran.**
- `run_field_tests.py --id examples.agent-transcripts.materialize` — PASS (drift guard).
- Docs: `FIELD_DOCS_RUNNER_MODE=strict … --page keyframer_examples` →
  `regenerated=4 … failures=0`, then `OK — every referenced asset is committed`, `No issues found`.

**Figures I personally opened (all four):**

| figure | verdict |
| --- | --- |
| `capture_apply.png` | matches its caption exactly; no change needed |
| `camera_screw.png` | **refuted its own draft prose** — printed 0.000003, prose claimed "zero to six decimal places". Prose corrected and the test bound tightened 1e-3 → 1e-5 so it pins the published number |
| `persistence.png` | **came out clipped mid-word.** Re-shot and re-read: legible |
| `update_code.png` | perfect, and visibly shows the `\$total` fix |

### Defect found and fixed

`kotlinRawStringLiteral` emitted a Kotlin **raw** string for a `SourceBackedBlock`, guarding against
`"""` and a trailing quote but **not** `$` — which a raw string still interpolates. `updateCode()`
on a block mentioning `$name` or `${…}` produced code that either failed to compile or silently
interpolated something else, defeating the exact round trip the function exists for. Guard widened;
regression test added; the `updateCode` KDoc, which stated the raw-string behaviour
unconditionally, corrected **in the same commit**.

Worth recording *how it nearly escaped*: the first version of the regression test **passed green**.
Its sample source ended with a quote, so it tripped the pre-existing trailing-quote guard and never
reached the defect. The test now asserts the sample takes the raw-string path before asserting
anything about the output.

### Two behaviours the KDoc never mentioned, now pinned and published

- `capture()` reads `Structure.bindings` and **never** `Structure.values`, so a property written by
  value rather than bound snapshots as `null` with nothing logged.
- A corrupt version manifest is swallowed by a `runCatching`, so the next `saveKey` renumbers from 0
  and **overwrites `000000.json`** — the oldest snapshot in the history.

### GPU stretch goal — OMITTED, deliberately

Not attempted and quietly dropped: **declined on stated grounds.** Load average was **20.59** (two
sibling nightly agents), and `dev_docs/DOCS_RUNNER_GPU_CAPTURES.md` records that a `native-window`
capture "fails outright when the machine is busy (load average ≳10)"; free disk was down to 8.8 GiB
from 11. A capture produced under those conditions can come out corrupt while still looking
plausible, which is worse than not having one.

The page therefore states the resulting gap outright: `target(ModulePointer, …)` and its
`ComputeModulePointer` / `MeshModulePointer` siblings need a live renderer and are covered by
neither a fence nor a test. The note also records what *reading* the source shows — all three pass
one `@`-suffixed string to both the `liveUniformStructures` lookup and the member `bind`
(`keyframer.kt:562`, `:566-567`, `:580`, `:584`, `:588`), so the key addresses a member named after
the uniform block rather than an arbitrary field, unlike the plain `Structure` case. That is
labelled in the published text as a reading of the code and **explicitly not a measurement**,
because nothing here ran it. It is the obvious next task for a branch with a quiet machine.

### Risks / caveats

- **One commit contains pre-existing drift that is not mine.** `checkCodebaseReference` was already
  red at the `7abe8736` base: `workspace.md` was missing `Marker.removePropertyRaw`, which landed in
  `6acaa999` without a regenerate (this branch never touches `stdlib.kt`; verified). Regeneration is
  all-or-nothing, so that correction rides along in `a8f888b6`, called out in its message. A
  reviewer should expect the `5032 → 5033` / `77 → 78` count moves and the one new `workspace.md`
  row, none of which come from keyframer.
- The scoping call is a judgement: `interpolants_examples.md:168-257` already had one `interpolate`
  fence, so the new page deliberately cross-links rather than restates it. If the human would rather
  have had one merged page, this is the thing to change.
- Only the plain-`Structure` target family is exercised anywhere. See above.

### Demo artifact

`dev_docs/agents/transcripts/keyframer_worked_example.yaml` — replay into a fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'macro write keyframer_worked_example --file dev_docs/agents/transcripts/keyframer_worked_example.yaml' \
  --command 'macro run keyframer_worked_example'
```

or open the materialized starting point directly:
`public/docs/assets/agent_transcripts/keyframer_worked_example/metadata.edn`.
