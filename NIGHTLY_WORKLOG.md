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
  *(Pass 2: still true of the unit tests, but a real GPU fence now exercises that family against a
  live module uniform — see below.)*

---

## Pass 2 — adversarial audit, measured coverage, second defect, GPU still

Status: **complete**. Recommendation still **merge**, more confidently.

### 1. I audited the page trying to get it retracted, and found five false claims

All five were the page contradicting the branch's own work. Each is fixed in the commit that found
it, in the prose a reader actually lands on.

| claim the page made | verdict |
| --- | --- |
| "The whole surface is covered by `KeyframerTest` (53 tests)" | **FALSE**, and contradicted by *this page's own note* two screens earlier saying the module-pointer targets are not exercised. Replaced with a measured 93/101 and a table of the gap |
| "Every public declaration used below is listed … in the codebase reference" | **FALSE** for the two supporting types the examples lean on. `Structure` is `afield.four.buffers`, `CameraPose` is `afield.four.util`; the reference covers `afield.stdlib` + `afield.visualization` only, as `reference/index.md` states at the foot of its file table |
| "The one case where the `Double` really does lose information is a `Long` past 2^53" | **FALSE** — and disproved by a test I had *already written in pass 1*. Any `Number` outside the six `NumKind`s (`BigDecimal`, `BigInteger`) is stored as `DOUBLE` and returns a `Double`, so the type is lost too. Now two cases, with a new test |
| "Everything below runs on the CPU" | **FALSE** once the GPU fence landed. Now "almost everything", with links |
| "the registration is the one part of this file that cannot be exercised headlessly" | **FALSE** — it is one of eight. Now says so and links the table |

Two more were imprecise rather than wrong and were tightened: "that name is unbound and *always will
be*" (now states the mechanism), and "these numbers are asserted at eleven sample points" (only the
constant distance/height/aim are 11-point; the midpoint figures are once each).

### 2. Coverage is now measured, not asserted

`src/jvmTest/kotlin/afield/stdlib/KeyframerCoverageTest.kt` enumerates `keyframer.kt`'s public
surface **by reflection** and requires every declaration to be classified covered-or-not **with a
reason** — the `FnOpCoverageTest` shape. Adding a public declaration fails the build until it is
accounted for, and a third test enforces that "unreachable" can *only* mean a live `Renderer` or the
AWT clipboard, so the category cannot become a dumping ground.

**93 of 101 public behavioural declarations (92.1%).** Data-class generated members excluded, on the
same grounds the codebase reference excludes them. The 8 uncovered are the 3 `target(…Pointer)`
overloads, the 3 `stackAuto` overloads, `camera(…)`, and `copyUpdateCodeToClipboard`.

**Writing the ledger caught it about to lie on four entries** this suite claimed to reach and did
not: `applyKey`, the schema-qualified `missingKey` lambda overload, `StackSLString.toString`, and
the individual components of `DualQuat`/`CameraPoseValue` — the byte-identical round-trip test would
happily agree with itself about a consistently swapped field. All four closed with real tests rather
than by downgrading the ledger.

### 3. A second defect class, found by probing before asserting

`keyValuesEqual` applies two comparison rules that are **exact opposites** on the two values where
floating point is awkward:

| | bare number (`==` on `Any?` → `Double.equals`, bitwise) | vector component (primitive `Float ==`, IEEE) |
| --- | --- | --- |
| `NaN` vs `NaN` | equal | **not** equal → phantom change |
| `-0.0` vs `0.0` | **not** equal → reported change | equal → sign flip hidden |

So a key holding `NaN` in a `Vector3f` diffs as changed against an identical copy of itself.
Confirmed by a throwaway probe *before* any assertion was written — deliberately, because in pass 1
my first regression test passed for the wrong reason. Pinned as a characterization test and
documented in both the `KeyDiff` KDoc and the page. **Deliberately not "fixed":** either rule is
defensible alone, and changing one would move what every `KeyDiff` table shows. That is the human's
call, not a drive-by.

Hostile inputs additionally probed and pinned, all of which turned out **fine** (no defect): keys
named `type` (the JSON discriminator) and `values`/`schemaVersion` (`KeyDTO`'s own fields); a note
with quotes, a backslash and a newline; an empty key; `interpolate` at `t = NaN`; a key name ending
in `@`. Also corrected a KDoc overstatement — the schema-specific `missingKey` lambda is evaluated
*only when the file lacks the name*, never otherwise (now tested: `lambdaCalls == 0`).

### 4. The GPU stretch goal landed

Load fell to 7.85 as the sibling agents finished, clearing the ≳10 threshold I declined on in pass 1,
so I retried — on a `zz_` scratch page first, per the debugging recipe, so a failure could not touch
the real page.

New shader pair `assets/canonical_graphics/keyframer-look_{vertex,fragment}_.glsl`: one UBO block
carrying `p`, `m` and a `vec4 tint`, and a fan of 48 triangles whose vertex colours are all
`(1,1,1,1)` — so the only colour in the frame comes from the uniform, and the only writer of that
uniform is a `Key`.

**The first capture refuted its own purpose.** It used `interpolate(cool, warm, 0.5)`, but the two
are near-complementary, so the midpoint is `(0.60, 0.575, 0.56)` — a grey indistinguishable from an
untinted fan. The picture was equally consistent with "the keyframe worked" and "no tint at all", so
it proved nothing. Re-shot at `t = 0.25`.

Verified quantitatively, not by eye: the capture holds exactly two colours, black and
`rgb(170, 205, 229)` over 497,142 pixels; the keyframed linear value is `(0.40, 0.6125, 0.78)` and
its sRGB encoding is `(170, 205, 229)` on all three channels, to the byte. The fence is a **cache
hit** on rebuild (`regenerated=0, cache_hits=5`), so it does not re-run the GPU on every docs build.

This does **not** move the coverage number: it uses the plain `Structure` overload against
`module.look`, not `target(ModulePointer, name)`. The module-pointer note is strengthened (now
citing `Module.kt:559` and `ComputeModule.kt:163`, which show `liveUniformStructures` is keyed by
*block* name while its members are the block's fields) but still labelled a source reading, because
nothing executes those overloads.

### 5. The drift claim from pass 1 — verified, not withdrawn

Confirmed at `file:line`. At my base `7abe8736`, `src/jvmMain/kotlin/afield/stdlib/stdlib.kt:846`
declares `override fun removePropertyRaw(name: String, doResync: Boolean)`, while
`public/docs/reference/workspace.md` contained **zero** occurrences of `removePropertyRaw` and
claimed "77 public symbol(s), 74 of them carrying KDoc". `6acaa999` ("assigning null to a nullable
property erases it", 2026-07-31) **is an ancestor** of my base. This branch never touches
`stdlib.kt`. So `checkCodebaseReference` was already red before I started, and the `77 → 78` /
`74 → 75` moves in commit `a8f888b6` are that pre-existing gap, not my work. Claim stands as written.

A later reference regeneration in this pass *is* mine and is explicable: the GPU section uses
`stdlib.kt` symbols (`vec`, `Mesh`), so `keyframer_examples` now appears in that file's "Used in"
list. Checked; no unrelated churn.

### Pass 2 verification

- Full `./gradlew jvmTest`: **1002 tests, 0 failures** (was 986). `KeyframerTest` 66,
  `KeyframerCoverageTest` 3.
- `checkKDocAttachment` ok (703 files, 8282 blocks); `checkCodebaseReference` green.
- `run_field_tests.py --id examples.demo-transcript.exec` re-run after all changes: PASS,
  `[execute] PASS keyframer_worked_example (applied=8, output lines=20)`.
- Page rebuilds strict: `OK — every referenced asset is committed`, `No issues found`.
- **Figures opened this pass:** `gpu_tint.png` twice — the grey first attempt (rejected) and the blue
  re-shoot (accepted, and pixel-measured). The four pass-1 figures were not re-captured; their
  fences are unchanged and reported as cache hits.
- Rendered HTML checked directly for: the two new tables, both new anchors resolving, no dangling
  internal links, and the absence of the stale "53 tests".
- All branch commit messages scanned for backtick command-substitution damage after one was found
  and amended; no others affected.

### Pass 2 residual risks

- The `NaN` / signed-zero inconsistency is documented and tested but **not fixed** — a deliberate
  deferral to the human, since either rule is defensible and the change is user-visible.
- The `target(ModulePointer, …)` reading is now supported by three source files but is still not
  executed anywhere. It is the one claim on the page resting on reading rather than running, and it
  is labelled as such.
- Disk fell from 11 GiB to ~7.7 GiB over both passes, mostly Gradle and docs output. Well above the
  3 GiB floor, but a third pass on this machine should check first.

### Demo artifact

`dev_docs/agents/transcripts/keyframer_worked_example.yaml` — replay into a fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'macro write keyframer_worked_example --file dev_docs/agents/transcripts/keyframer_worked_example.yaml' \
  --command 'macro run keyframer_worked_example'
```

or open the materialized starting point directly:
`public/docs/assets/agent_transcripts/keyframer_worked_example/metadata.edn`.
