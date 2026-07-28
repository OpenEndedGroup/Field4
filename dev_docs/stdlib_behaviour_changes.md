# Stdlib behaviour changes — a running ledger

Some fixes in `afield.stdlib` change what already-written box code *computes*, not just what the docs
*say*. Those cannot be quietly absorbed: a saved workspace, a shipped transcript or a committed docs
capture may be sitting on the old numbers. This file is the durable record — the nightly worklog is
routinely stripped at merge time, so release notes do not belong there.

Entries are newest first. Each one says what changed, in which direction, and what was swept for
callers.

---

## 2026-07-28 — the KDoc-truth pass (`nightly/2026-07-28/2-kdoc-truth-and-guard`)

Twenty-two code defects were fixed by making the code keep a promise its published KDoc was already
making. Most are pure bug fixes with no reachable old behaviour worth preserving; the ones below
change output for input that used to "work".

### The output-changing set

| Symbol | Change | Magnitude | Old behaviour reachable from |
| --- | --- | --- | --- |
| `Functions.fbm`, `Functions.fbmPerlin` | the `amplitude` parameter is now honoured. It used to cancel out of `Σ aᵢvᵢ / Σ aᵢ` exactly, so the output was always the unit-amplitude shape. | **bit-identical at the default `amplitude = 1.0`** (the loop accumulates at unit amplitude and multiplies by exactly 1.0 at the end); scales linearly otherwise, so `amplitude = 0.01` is now 100× quieter than `amplitude = 1.0` instead of identical to it | any explicit non-1.0 amplitude |
| `Fn.convolve(normalize = true)` | divides the step-scaled sum by the step-scaled weight. It used to divide a scaled sum by an unscaled weight. | ~63× **up** at the defaults; exactly `1.0` for a flat unit signal at every width × sample-count pair tested | any `normalize = true` call |
| `Fn.movingAverage` | is now a mean. A constant `1.0` came back as `2.0` at `samples = 2` and `1.0158…` at the default 64. | up to 2×; 1.6% at the default. `Functions.lowPass` / `highPass` move with it | every call |
| `List<Fn>.palindrome` | `[a, b, c]` now plays `a b c b a` — five slots, domain `0..5`. It used to drop both ends of the return leg and span `0..4`. | one slot longer for lists of three or more; any timeline arithmetic downstream of the domain shifts | every call on a list of 3+ |
| `Fn.tile` | declares the span its copies occupy rather than its source's domain, so `f.tile(n).plot()` draws all of it | declared domain only; sample values unchanged | every call |
| `Fn.sampleZeroCrossings` | interpolates from the correct side of the step (`x - step*(1-t)`, not `x - step*t`) | crossings move by up to one step, mirrored about the step's midpoint; correct at `t = 0.5` before and after | every call |
| `Fn.toDoubleArray(step, …)` | samples every `step` units. It used to forward to the count-based overload, which re-spaces to land on `max`. | a step of 0.3 over `0..1` used to sample at 0, ⅓, ⅔, 1 and now samples at 0, 0.3, 0.6, 0.9 (+1.0 with `includeEnd`) | every call with a step that does not divide the domain |
| `PeakDetector` | holds at the current sample instead of decaying below it | the envelope no longer sawtooths between `A` and `A·decay` on a constant input | every call |
| `DynamicRangeCompressor(kneeWidthDb = 0f)` | a hard knee is a hard knee. It used to compute `0 / (2*0)` and return **NaN** for a sample exactly on the threshold — which digital silence reaches, because the envelope floors at exactly −100 dB. | NaN → a real number | `kneeWidthDb = 0f` |
| `Collection<Vec3>.obbBounds` | returns a box that contains its own points (it used to use half-widths about the centroid) and no longer NaNs its axes on a collinear cloud | box is up to 2× larger per axis | every call |
| `PairContext.axis` (interaction nets) | a *coincident* pair falls back to `(1, 0)` instead of normalising a zero vector into `(NaN, NaN)`. The old guard compared the floored length against the same literal it was floored with, so the fallback was dead code. | only for a pair whose two nodes are at the same position — every node in a `net { }` built without explicit positions starts at the origin. **Separated pairs take a bit-identical path.** | a positionless `net { }` that fires a rule using `axis` |
| `FragmentBuilder.node` / `RewriteBuilder.node` / `AutoRuleBuilder.node` | the duplicate-name `require` now actually fires (it compared a fresh identity-keyed `NodeRef` against the map's keys and never matched) | a rule that reused a node name used to quietly build two nodes and now throws | a rule set with a repeated node name |
| `keyframer`'s Kotlin export | a captured `Float` emits `1.5f`, not `1.5` | pasted code stores a `Float` where it used to store a `Double`; a `1.5f` literal will not compile where a `Double` is required | copying a Float-valued keyframe as Kotlin |
| `File.safeSave` call sites in `saved.kt` | both now pass `atomic = true`, as three KDoc blocks already promised. The non-atomic path renames the existing file away *before* writing, so an interruption left nothing at the path. | no output change; a different failure mode | crash during a `saved` write |
| `Fn.stretchBy` | the `outside` parameter is **gone** — it was unreachable under the `EdgeMode.CLAMP` the function uses | source-incompatible for anyone passing it (no in-repo caller did) | `stretchBy(outside = …)` |
| `String.abslutePath` | `@Deprecated` with `ReplaceWith("absolutePath")`, still working | a deprecation warning | the misspelling |
| `UKFOptions` | rejects a non-finite `kappa` at construction | an exception instead of silent NaN | `kappa = NaN` |

### What was swept, and the verdict

Whole-repository sweep for call sites of every symbol above (`src/`, `dev_docs/`, `public/docs/`,
`templates/`, `config/`, `server/`), reading each hit rather than counting it:

- **`src/` Kotlin** — the only production call sites of `fbm`/`fbmPerlin` are `RectSL.kt`'s `fbm` word
  (always the default amplitude, so bit-identical) and `FunctionOps.buildFbm`/`buildFbmPerlin`, which
  forward whatever the graph carries and are therefore *supposed* to move. `jvmTest` covers the rest.
  StackSL's `fbm` and `ImagingGallery`'s `tile`/`tiles` are unrelated names in other languages.
  `TidalPatterns.palindrome` and `dynamic_graph_systems.kNearest` are different types entirely.
- **Committed docs captures** — every `fbm`/`fbmPerlin` call in `public/docs` was read for its
  amplitude. Twenty of them pass a non-1.0 amplitude or a changed function and all twenty have been
  regenerated through the real capture pipeline (17 + 2 + `diff_runner_after.png`). The rest pass
  `amplitude = 1.0` explicitly or by default and are bit-identical.
- **`public/docs/rectsl_examples.md`** and the RectSL gallery stills — the RectSL `fbm` word never
  passes an amplitude, so nothing in that gallery can move.
- **`public/docs/interaction_nets_examples.md`** — all four examples position every node explicitly
  with distinct `at =` vectors, so no pair is coincident and the `axis` fix takes the identical
  branch. Captures unaffected; verified by re-running `stdlib.interaction-nets.smoke`.
- **`dev_docs/agents/transcripts/`** (28 transcripts) and their materialized starting points — **no
  hits at all** for any changed symbol.
- **`templates/field.sketch2_examples.edn`** — one hit, `pointsOut`, inside a commented-out line; the
  box hand-rolls the same loop below it.
