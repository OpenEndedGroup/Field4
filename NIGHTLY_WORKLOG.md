# Nightly 2026-08-06 — agent 2: the completion *presentation* path gets its first automated cover

Branch `nightly/2026-08-06/2-completion-popup-regression-cover`, based on `50d838851`.
Kind B (feature / genuine sharp edge). Status: **done**.

## Plan (written before implementation, kept verbatim)

### What the evidence says

`9ba22de77` hand-fixed a popup defect a human found by *looking*: the completion group header for
the `Audio AI` category rendered as **"Audio Ai"**, because `titleForCategory` in
`src/commonMain/resources/fieldSuggest.js` falls back to title-casing each word and `Audio AI` is
the first two-word category. Nothing in `check` noticed, because *nothing* executes
`fieldSuggest.js` — and that file owns the entire client-side ranking, grouping, category titling,
recency weighting, row rendering, doc panel and signature badge (the server deliberately returns
everything unranked; `CompletionCorpusProbe.kt:23` says so out loud: "Ranking is client-side
(fieldSuggest.js) and deliberately NOT asserted here").

### Refinements after reading the real code

1. **The category vocabulary is enumerable, so the class-level guard is exact.** Categories reaching
   the popup come from two Kotlin sources: the fixed alias table in
   `KotlinCompletionSupport.normalizedCompletionCategory` / `inferCompletionCategory`, and every
   `@file:afield.CompletionCategory("…")` in the tree. Today that is: Graphics(22) Math(20)
   Polytopes(17) Data(17) Function(12) **Audio AI**(10) Internal(7) **ALife**(7) Visualization(6)
   Imaging(6) Solvers(4) Workspace(3) Audio(2), plus Kotlin/External/AI. So the guard is: *for every
   category the Kotlin side can emit, the title the real popup renders must equal the declared
   spelling* (bar a small, stated alias table — `Function`→`Functions` is deliberate).
2. **That guard already finds a second live instance of the same class:** `ALife` → key `alife` →
   not in the `known` map → fallback → **"Alife"** in the popup, wrong today, 7 files' worth. Fixing
   it is the guard proving itself on a defect nobody had spotted.
3. **Execute the JS, mock only the host.** Copy the *verbatim* file into a temp dir next to a
   3-function stub `afield.mjs` + `package.json {"type":"module"}`, install a minimal
   `window`/`document`/`localStorage`/Monaco host, then drive the **public** entry point
   `installFieldSuggest(editor, monaco)` → `controller.open()` → canned RPC reply → and assert on the
   **HTML the browser would receive**. Nothing is re-implemented in Kotlin.
4. **Wire into `check`** as a plain `kotlin.test` in `src/jvmTest`. No new public API in `jvmMain`, so
   `:checkCodebaseReference` stays green.

(Plan followed as written. Nothing was abandoned; two stretch items were added — see below.)

---

## What changed

| file | what |
| --- | --- |
| `src/jvmTest/resources/fieldSuggest/probe.mjs` | **new** — 24 behaviour cases + the category guard, driving the real `fieldSuggest.js` |
| `src/jvmTest/resources/fieldSuggest/host.mjs` | **new** — the minimal browser/Monaco host |
| `src/jvmTest/resources/fieldSuggest/afield.mjs` | **new** — stub for the 3 imports fieldSuggest.js takes from the Kotlin/JS bundle; canned replies, deferrable, browser-strict `fetch` |
| `src/jvmTest/kotlin/afield/FieldSuggestPresentationTest.kt` | **new** — enumerates the category vocabulary, spawns node via `NodeTool`, asserts; wired into `check` via `jvmTest` |
| `src/commonMain/resources/fieldSuggest.js` | **fix** — `alife: "ALife"` in `titleForCategory`'s `known` map, and a comment stating the rule rather than the instance |
| `tools/run_ui_regressions.py` | **new regression** `completion-category-headers` — the same question asked of a real editor in a real browser |
| `dev_docs/completion_popup_cover.md` | **new** — the whole thing: harness, contract facts, mutation table, how to add a case, what is deliberately not covered |
| `dev_docs/agents/transcripts/docs_capture/completion_category_headers.yaml` | **new** — replayable eyeball demo |
| `dev_docs/README.md`, `dev_docs/completion_aa_sidecar.md`, `CompletionCorpusProbe.kt` | index + cross-links; the probe's "ranking is deliberately NOT asserted here" now names the test that asserts it |

Five commits, one behaviour change each: plan → harness (deliberately red) → the one-line fix →
widened suite + docs → server-side link assertion → live regression + demo.

## Verification

**Headline: red → green on the known defect, observed.** With
`"audio ai": "Audio AI"` deleted from `titleForCategory` (i.e. the tree exactly as it was before
`9ba22de77`), `./gradlew --no-daemon jvmTest --tests 'afield.FieldSuggestPresentationTest'` fails
both tests:

```
FAIL multi-word category headers render their own spelling: group headers
  expected: ["Audio AI","ALife"]
  actual:   ["Audio Ai","ALife"]
FAIL Audio AI (@file:CompletionCategory) renders as "Audio Ai", expected "Audio AI"
      — add it to titleForCategory's `known` map in fieldSuggest.js
```

Restored: `tests="2" failures="0"`, 24 cases and 16 categories green. (The restore also demonstrated
the classpath staging working — editing `fieldSuggest.js` is what re-ran the test.)

**The guard found a live defect on its first run.** `ALife` rendered as "Alife" — the same bug in a
category nobody had squinted at. Committed red (`907727173`), then fixed (`e235f2fe0`).

**Mutation-tested, because a suite never seen red is not known to test anything.** 15 one-line
mutations to `fieldSuggest.js`, one at a time: every one of the 24 cases is killed by at least one
specific mutation, and **no mutation survived unnoticed**. The first round had two survivors, which is
how the suite got better: (a) dropping the `orderItemsForGroupedDisplay` pass changed nothing
observable, because `renderList` groups a second time — the property it really protects is that
arrow-key order equals visual order, and that is now asserted; (b) one of my mutations was a dud (it
left the escaping chain intact), so I fixed the mutation. Table in the dev doc.

**Live, in a real browser** (Field launched from this worktree on port 9602, PID 22669, cwd verified,
sidecar off so K1 answered): the new `completion-category-headers` UI regression PASSes in 3.8s with
the group headers read back as `["ALife"]` and `["Audio AI"]` over a real `complete` round trip.
Inverting expected/mangled made it FAIL ("group header rendered ALife — titleForCategory lost the
Alife spelling"); swapping back, PASS. So the live assertion is reading the live popup.

**Server-side link.** For all 16 categories, `KotlinCompletionSupport.classifyMetadata` hands the
declared spelling through untouched, so the JS guard grades the string the popup really gets. Teeth
checked: a `Maths` entry fails with "the server respells `Maths` as `Math`".

**Demo replay.** `macro write` + `macro run` of the demo transcript on the live server:
`applied=3 skipped=0 errors=0`.

**Full gate.** `./gradlew --no-daemon check` → **BUILD SUCCESSFUL in 3m42s**, including `jvmTest`,
`checkCodebaseReference`, `checkKDocAttachment`, `checkAgentTranscripts` and `checkPythonTools`. Run
twice (mid-way and final), green both times. `:checkCodebaseReference` is **green** — this change adds
no public API, so agent 1's generated file is untouched and I never ran `generateCodebaseReference`.

**Not verified / not claimed:**
- I did not run the full smoke catalog (`tools/run_field_tests.py`); nothing I touched has a
  `FIELD_TEST` block, and `check` covers the tests I added.
- The live regression was run against **K1** (I turned the completion sidecar off for speed and
  determinism). The AA sidecar path was not exercised tonight. Categories are attached server-side
  regardless of engine, so I expect no difference, but I did not measure it.
- The pass screenshot the live regression saves is not good evidence: the capture step re-opens the
  popup and catches it mid-spinner. The evidence is the `page-eval` data, not the image.
- One pre-existing Kotlin warning exists in `jvmTest` (`RectSLSdfBakeTest.kt:351`, "`when` is
  exhaustive so `else` is redundant"). It is present at the base commit in a file I never touched.
  My own files compile warning-free.

## Risks and unknowns — including where my own evidence is weak

1. **`check` now needs `node` >= 14.** This is a new external dependency for the default gate. It
   fails (with `NodeTool.unavailableMessage()`) rather than skipping, because a silent skip is how a
   guard rots; `AFIELD_SKIP_JS_TESTS=1` is the visible opt-out. `NodeTool` picks a candidate that
   *meets* the floor and falls back to the Gradle-managed node, which is why this survives this
   machine's v10-first `PATH` — but a machine with no suitable node turns `check` red.
2. **The harness mocks a lot of Monaco, and I want to be blunt about it.** `getBoundingClientRect` is
   a constant, so positioning, flipping and scroll-into-view are exercised only for "does not throw";
   the element tree is flat tag soup; `afield.mjs` is stubbed, so the markdown renderer behind the doc
   pane is out of scope. A green run says the popup *decided* correctly, not that it *looked* right.
   The live regression closes that gap for exactly one question (category headers) and no others.
3. **The `readList` parser is regex over the popup's markup.** If the row markup changes shape it will
   mis-parse and produce a confusing failure. It fails closed rather than open, but the failure
   message will point at the wrong thing.
4. **One duplicated rule.** The probe recomputes `completionKeyFor` to seed a recency entry. The
   `accept()` case guards the duplication (a real accept must write a key of the same shape), but it
   is still a rule written twice.
5. **The `CHARACTERIZATION` case pins behaviour that is probably wrong** (import completions outrank
   `Math` ones because `CATEGORY_WEIGHT` predates the server's category vocabulary). That is
   deliberate and labelled, but anyone rebalancing the weights has to edit it. I did **not** change
   the weights: that is a taste call about ranking that a nightly agent should not make unilaterally.
   It is written up in the dev doc as the open question it is.
6. **A few cases assert today's exact ordering.** A legitimate ranking improvement will need
   expectation updates. Unavoidable for a ranking test, but it is friction, and the failures will read
   as regressions until someone reads them.
7. **The mutation harness is not committed** (a throwaway script with absolute paths in the
   scratchpad). The table in the dev doc is therefore a *record* of a run, not a re-runnable artifact;
   the doc gives the recipe instead.
8. **Nothing runs the live regression automatically.** It needs a running Field, like its two
   neighbours.
9. `public/docs/` untouched: no user-facing page documents completion category headers, and inventing
   one to describe a corrected spelling seemed like noise. The user-visible change is that an ALife
   completion is now filed under "ALife".

## Recommendation: **merge**

The tree is greener than I found it in a way that is checked rather than asserted: a whole
display-and-ranking layer that nothing executed now has 24 mutation-validated cases and a class-level
guard in the default gate, plus a live counterpart; the guard paid for itself immediately by finding
`ALife`; and the one product change is a one-line spelling fix confirmed in a real browser. The
headline red→green was observed on the actual historical defect, not on a proxy.

The one thing a reviewer should decide rather than accept from me is risk 1 — whether the default gate
may depend on `node`. If the answer is no, the fix is one line (turn the `fail` into a skip) and the
suite still runs on demand and in the nightly.

## Demo / replay

```
python3 tools/field_backdoor.py --json --scratch --port <your port> \
  --command 'macro write completion_category_headers --file dev_docs/agents/transcripts/docs_capture/completion_category_headers.yaml' \
  --command 'macro run completion_category_headers'
```

Then click into either box's editor, put the caret at the end of the seeded `lenia` / `fieldAudio`
line and press Ctrl+Space. The grey group header above the rows is the thing this whole branch is
about: it must read **ALife** and **Audio AI**.

## Housekeeping

Field on port 9602 (field.sh PID 22558, JVM PID 22669, cwd verified as this worktree) was stopped
before returning. SIGINT did **not** take — neither on the launcher nor on the JVM, after 35s — so it
was escalated to SIGTERM on the JVM PID I had started myself. Port 9602 has no listener and both PIDs
are gone, verified with `lsof`/`ps` after the fact. Nothing was killed that I did not start, and no
process on port 9477 was touched (I never ran `run_field_tests.py`).
