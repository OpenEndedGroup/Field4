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

## Progress

(appended as work lands)
