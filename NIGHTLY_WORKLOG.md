# Nightly worklog — 2026-07-12/2 — OpRegistry ↔ fnrewrite.js bidirectional mirror

Kind: B (bug/ergonomics). Close a latent drift channel between `OpRegistry`
(the single source of truth for the `Fn` graph op vocabulary) and the Monaco
`fnrewrite` editor language (`fnrewrite.js`, `opWords`).

## Plan

**Problem (confirmed against real code).** `FunctionsGraphRewriteTest`
(`editorOpVocabularyNeverNamesAnOpTheRegistryDoesNotKnow`, ~line 104) enforces
only *one* direction of the mirror:

    every editor opWord  ⟹  OpRegistry.knows(it)      (forward: no unknown ops)

There is no converse. So `OpRegistry` can gain a new op the editor is meant to
surface (e.g. a new oscillator / transmutation target) while `fnrewrite.js`
silently lags, and the test stays green — a real autocomplete/highlight gap the
user never sees fail.

**Chosen "intended set" for the reverse check: `OpRegistry.transmutableOps`.**
Rationale:
- `transmutableOps` = the canonical ops `op=` can actually rebuild a node into.
  These are exactly what a user types after `op=` and expects highlighted /
  autocompleted. Missing one = a broken editor affordance for a real feature.
- `knownOps` is explicitly a *superset*: OpRegistry's own doc says data-carrying
  ops (piecewise, timeline, rbf, sequence, …) are "known … but are not
  transmutation targets", and `fnrewrite.js` documents its `opWords` as "a subset
  of OpRegistry.knownOps". Requiring every `knownOp` would fight that documented
  design and force a large allow-list. So the principled reverse boundary is the
  transmutation targets, not all known ops.
- Consequence: the editor set = `transmutableOps` ∪ {a few extra reachable ops
  kept for highlighting: delay, mirror, repeat, warp, timeline}. The forward
  check already licenses those extras; the reverse check only guarantees
  *completeness of the transmutation vocabulary*.

**Allow-list:** none needed. A programmatic check (all 37 `transmutableOps`
lowercased vs `opWords` lowercased) shows every transmutation target is already
surfaced. So the reverse assertion passes today with no allow-list. Documented
in-comment rather than carrying an empty list.

**Drift found?** No. The JS is already complete w.r.t. transmutation targets.
The deliverable is therefore the *hardened bidirectional test* itself — it locks
the invariant so a future OpRegistry addition that skips `fnrewrite.js` fails
loudly. No change to `fnrewrite.js` required.

**Steps:**
1. Add a reverse assertion to the existing mirror test (keep the forward check
   untouched, keep it in the same test so the mirror invariant lives in one
   place). Assert every `OpRegistry.transmutableOps` entry (lowercased) is in
   `opWords` (lowercased), with a commented justification and no allow-list.
2. Run `./gradlew jvmTest --tests "afield.stdlib.FunctionsGraphRewriteTest"` from
   the worktree root (the test reads `fnrewrite.js` by relative path).
3. Update the mirror-invariant comment in `fnrewrite.js` to say the mirror is now
   bidirectional. Check dev_docs/public docs for a drift-invariant description.
4. Finish this worklog.

## Execution & results

**Files changed:**
- `src/jvmTest/kotlin/afield/stdlib/FunctionsGraphRewriteTest.kt` — renamed the
  mirror test `editorOpVocabularyNeverNamesAnOpTheRegistryDoesNotKnow` →
  `editorOpVocabularyMirrorsTheRegistryInBothDirections`; kept the forward check
  verbatim; added the reverse assertion (every `OpRegistry.transmutableOps` entry,
  lowercased, must be in `opWords`) with a full commented justification and no
  allow-list. Why: locks the previously one-directional invariant so a future
  registry-only op addition fails loudly instead of silently degrading the editor.
- `src/commonMain/resources/fnrewrite.js` — updated only the comment above
  `opWords` to describe the now-bidirectional mirror (forward + reverse) and to
  note the known-but-not-transmutable extras (delay, mirror, repeat, warp,
  timeline). Why: keep the invariant documented at its home. **No op-word diff:
  the JS was already complete w.r.t. transmutation targets — no words added.**
- `NIGHTLY_WORKLOG.md` — this file.

**Drift found?** No. All 37 `transmutableOps` are already surfaced in `opWords`
(verified programmatically and by the test). The hardened bidirectional test is
the deliverable, as anticipated.

**Docs:** the only doc mention (`public/docs/functions_examples.md` ~L3365) says
the editor "consults" `OpRegistry` as the single source of truth at runtime —
accurate and unchanged. It documents runtime behavior, not the test drift-guard,
so I deliberately did not inject test internals into user-facing reference. No
`dev_docs/` mention exists.

## Verification (honest)

Ran from the worktree root:
`./gradlew jvmTest --tests "afield.stdlib.FunctionsGraphRewriteTest"`

Blocker encountered: the JVM *test* source set does not compile on the base
commit — pre-existing, unrelated breakage in `src/jvmTest/kotlin/organic/`
(`FLineLinearizerTest.kt`, `PointMatcherTest.kt` reference `linearize`,
`FLINE_LINEAR_VERTEX_STRIDE`, `PointMatcher`, none of which exist in main src).
These files are unchanged by me (`git diff HEAD` empty). Because KMP compiles the
whole test source set together, they block every JVM test including mine.

To verify honestly, I temporarily moved those two pre-existing-broken files to the
scratchpad, re-ran the scoped test, then restored them. This left NO committed
change to them (final `git status` shows only my three intended paths).

Result (real, observed):

    > Task :compileTestKotlinJvm
    > Task :jvmTest
    BUILD SUCCESSFUL in 11s

    TEST-afield.stdlib.FunctionsGraphRewriteTest.xml:
    tests="17" skipped="0" failures="0" errors="0"
    testcase editorOpVocabularyMirrorsTheRegistryInBothDirections[jvm] time="0.008"  (no failure/error)

All 17 tests in the class pass, including the new bidirectional mirror test.

## Risks & unknowns

- Low risk. Change is test-only + a JS comment; no runtime/product code touched,
  no editor behavior changed.
- The reverse invariant is scoped to `transmutableOps` by design (not all
  `knownOps`). If the team later wants data-carrying ops surfaced in the editor,
  they'd extend `opWords` and possibly widen this check — documented in-comment.
- I could NOT verify via a full clean `jvmTest` because the base test source set
  is independently broken (`organic/*`). That breakage is out of scope for this
  task and I did not fix or commit anything about it. My test's compile+pass was
  verified with those pre-existing-broken files parked, then restored.

## Recommendation

**Merge.** The deliverable (hardened bidirectional mirror test) compiles and
passes as observed; it closes the described drift channel with no product-code
risk. Note for whoever merges: the pre-existing `organic/*` test compile failures
on the base commit are a separate, prior issue and will still need addressing to
run the full JVM test suite in CI.
