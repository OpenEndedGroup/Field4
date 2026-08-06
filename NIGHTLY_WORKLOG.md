# Nightly 2026-08-06 — agent 2: the completion *presentation* path gets its first automated cover

Branch `nightly/2026-08-06/2-completion-popup-regression-cover`, based on `50d838851`.

## Plan

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
3. **Execute the JS, mock only the host.** `fieldSuggest.js` is a real ES module whose only import is
   `./afield.mjs` (the compiled Kotlin/JS bundle). Copy the *verbatim* file into a temp dir next to a
   3-function stub `afield.mjs` + `package.json {"type":"module"}`, install a minimal
   `window`/`document`/`localStorage`/Monaco host, then drive the **public** entry point
   `installFieldSuggest(editor, monaco)` → `controller.open()` → canned RPC reply → and assert on the
   **HTML string the browser would receive**. Nothing is re-implemented in Kotlin: the Kotlin side
   only enumerates categories, spawns `node` (via `afield.util.NodeTool`, same as
   `RectSLEditorExtensionWorkout`) and reads the verdict.
4. **Wire into `check`** as `src/jvmTest/kotlin/afield/FieldSuggestPresentationTest.kt` — plain
   `kotlin.test`, which `check` already runs (`jvmTest`). No new public API in `jvmMain`, so
   `:checkCodebaseReference` stays green.

### Cases to cover (all through the real popup pipeline)

- **category titles**: every declared/derivable category renders its exact spelling; `Audio AI` and
  `ALife` explicitly; no `Audio Ai` / `Alife` anywhere in the emitted HTML.
- **grouping**: one header per category, rows of a category contiguous even when scores interleave
  (`orderItemsForGroupedDisplay` is the only thing making that true).
- **ranking**: exact > prefix > word-boundary substring > fuzzy; category weight as tie-break;
  member-context penalties (`equals`/`hashCode`, keywords); server `contextScore` boost; recency
  boost + the `recent` badge from a seeded `localStorage`.
- **`variant.tail`**: server `tail` reaches the row's `.afield-suggest-detail`.
- **request payload**: uid/property/line/column/position the client sends for `complete`.
- **doc panel**: empty-doc text, source footer link, receiver ("About …") section.
- **accept**: `insertText` applied, recency written, signature badge HTML from `itemSignatureFor` +
  `renderKotlinSignatureHtml`.
- **hardening**: a non-numeric/NaN `contextScore` must not poison the ordering; HTML injection in a
  label/category must be escaped.

### Proof of teeth

Delete the `"audio ai": "Audio AI"` line locally, observe the test go red; restore, observe green.
Same for the new `alife` mapping. Report both.

(Plan written before implementation; verification and outcome sections are appended at the end.)
