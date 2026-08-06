# Regression cover for the completion popup

**Audience:** anyone changing `src/commonMain/resources/fieldSuggest.js`, the shape of a completion
payload, or the set of completion categories.
**Status:** live in the default gate (`check` → `jvmTest`) as of 2026-08-06.

For where completions *come from*, read [`completion_aa_sidecar.md`](completion_aa_sidecar.md) and
[`COMPLETION_ENGINE.md`](COMPLETION_ENGINE.md). This note is only about the other half: what happens
to those candidates once they reach the browser.

---

## Why this exists

The completion server returns candidates **unranked, on purpose**.
`CompletionCorpusProbe.kt` states the split out loud:

> Assertions are ENGINE RECALL + TYPES, order-independent … Ranking is client-side
> (`fieldSuggest.js`) and deliberately NOT asserted here.

So one 1800-line JavaScript file decides everything a user actually sees: the fuzzy ranking, the
category weighting, the recency boost, the grouping, the group *titles*, the row markup, the
documentation pane, the source browser, and the call-signature badge. Until 2026-08-06 nothing in the
repository executed a line of it.

What that cost: commit `9ba22de77` is a hand-fix for the `Audio AI` group header rendering as
**"Audio Ai"**, found by a person looking at a popup. `titleForCategory` lowercases its key and
title-cases each word, so any category whose own spelling is not `Capitalised Words` comes back
respelled. When the guard described below first ran it immediately found a second live instance:
`ALife` was rendering as **"Alife"** (fixed in the same series of commits).

## What runs

`FieldSuggestPresentationTest` (`src/jvmTest/kotlin/afield/FieldSuggestPresentationTest.kt`) stages
four files into a temp directory and runs `node` over them:

| file | role |
| --- | --- |
| `fieldSuggest.js` | **verbatim**, read off the test classpath — the code under test |
| `fieldSuggest/host.mjs` | the minimum `window`/`document`/`localStorage`/Monaco a popup needs |
| `fieldSuggest/afield.mjs` | a stub for the three imports fieldSuggest.js takes from the compiled Kotlin/JS bundle, replacing the transport with a table of canned server replies |
| `fieldSuggest/probe.mjs` | the cases |
| `package.json` | `{"type":"module"}`, so node treats a `.js` file as the ES module it is |

The probe goes in through the **public entry point** — `installFieldSuggest(editor, monaco)` →
`controller.open()` → a canned server reply — and asserts against **the HTML the popup wrote**. That
is the load-bearing rule of the harness: assertions read the emitted markup string, not the harness's
model of it, so a bug in the tiny tag-soup DOM in `host.mjs` can make a case fail but cannot make one
pass.

The one thing it will not do is describe the rules again in Kotlin. This tree already has one
"parity" test that compares Kotlin to Kotlin and therefore never runs the JavaScript; a second would
have been worse than none.

Two tests, for two kinds of failure:

1. **`the popup ranks, groups and renders the way the editor expects`** — 24 behaviour cases.
2. **`every completion category has a correct popup title`** — the class-level guard (below).

Everything is staged **off the classpath**, which makes `fieldSuggest.js` and every harness file a
declared input of the `jvmTest` task: editing one re-runs the test rather than leaving a stale
UP-TO-DATE pass. (Observed: editing `titleForCategory` re-ran it.)

## The class-level category guard

`Audio Ai` was a *class* of defect, not an instance. The guard therefore enumerates every category
the Kotlin side can put on a completion, renders each one through the real popup, and requires the
header to come back spelled the way it was declared:

- every `@file:afield.CompletionCategory("…")` in `src/` (13 today: `Graphics`, `Math`, `Polytopes`,
  `Data`, `Function`, `Audio AI`, `Internal`, `ALife`, `Visualization`, `Imaging`, `Solvers`,
  `Workspace`, `Audio`), and
- the fixed vocabulary parsed out of `KotlinCompletionSupport`'s own category `when` branches
  (`normalizedCompletionCategory`, `inferCompletionCategory`, `categoryFromPath`,
  `categoryFromQualifiedName`), which adds `Kotlin`, `External` and `AI`.

16 categories today. The vocabulary is **scanned, not listed**, so a new
`@file:CompletionCategory("Two Words")` joins the guard without anyone remembering to; and two
sentinels (`Audio AI`, `ALife`) assert the scan still finds something, so the guard cannot rot into a
no-op. Deliberate aliases live in one readable map (`intentionalAliases`) — today only
`Function` → `Functions`, which is a real decision and not an accident.

**If this test fails on a category you just added:** add the correct spelling to the `known` map in
`titleForCategory` in `fieldSuggest.js`. That is the whole fix; the failure message says so.

## Two contract facts worth knowing before you edit ranking

Both were discovered by writing these cases, and both surprise people:

1. **Grouping dominates ranking in the display.** `filterAndLimit` ranks and then calls
   `orderItemsForGroupedDisplay`, and `renderList` groups *again* by title. A ranking change between
   two categories is therefore never visible as rows interleaving — it is visible as a change of
   **group order** (a group sits where its best-ranked member put it). The reason grouping must
   happen to the item list and not only to the markup is the keyboard: `data-index` is the index into
   `controller.items`, so if that list were left in pure score order the arrow keys would walk in a
   different order than the eye. There is a case for exactly that.
2. **The category weights predate the server's category vocabulary.** `CATEGORY_WEIGHT` keys the old
   client-side buckets (`local`, `graphics`, `functions`, `values`, `types`, `shader`, `metadata`,
   `imports`, `snippets`, `ai`, `other`). Of the categories the Kotlin side actually sends, only
   `Graphics` matches one; `Math`, `Data`, `Polytopes`, `Audio AI`, `ALife`, `Solvers`, `Imaging`,
   `Workspace`, `Function`, `Kotlin` and `External` all fall through to 0 — the same weight as
   `other`, and *below* `imports` (10) and `snippets` (8). So at equal fuzzy score an import outranks
   a `Math` function. There is a case named `CHARACTERIZATION:` that pins this down; it is a record
   of today's behaviour, **not** an endorsement, and it is the expectation you came to change if you
   are rebalancing the weights.

## What is deliberately NOT covered

Say this out loud rather than let a green tick imply more than it means:

- **Monaco.** The editor and model in `host.mjs` are stubs. `getBoundingClientRect` is a constant, so
  `reposition`, `scrollActiveRowIntoView` and the width/flip logic are exercised only for "does not
  throw".
- **The element tree is flat.** `innerHTML` indexes every tag it sees as a direct child, and
  descendant selectors match on their last simple selector. Every selector `fieldSuggest.js` uses is
  a class or `tag.class` lookup, which survives that; a future one might not.
- **`afield.mjs`** — the compiled Kotlin/JS bundle — is stubbed, so the markdown renderer behind the
  documentation pane is out of scope. The cases assert the pane's *structure* (chunk order, source
  footer, receiver section), not what markdown becomes.
- **CSS.** Nothing here can tell you the popup is legible.
- **The transport**, the AA sidecar, and everything upstream of the `complete` reply.

The live-browser cover for the parts a stub cannot reach is `tools/run_ui_regressions.py`, which
drives the real widget in a real page. It needs a running Field and is therefore not in `check`.

## The live half

`completion-category-headers` in `tools/run_ui_regressions.py` is the same category question asked of
the real thing: a real box, a real `complete` round trip, real `fieldSuggest.js` in a real browser.
It completes `lenia` (the ALife package) and `fieldAudio` (the Audio AI package) and requires the
rendered `.afield-suggest-section` text to be `ALife` and `Audio AI` — and *not* `Alife` or
`Audio Ai`, both of which have actually shipped.

```
PORT=9602 AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh          # in one shell
python3 tools/run_ui_regressions.py --port 9602 --id completion-category-headers
```

No AA gate, unlike the two completion regressions above it: a candidate's category is attached
server-side by `classifyMetadata` whichever engine produced the candidate, so this grades K1 and AA
alike. What keeps it from passing vacuously is that it requires completions to have arrived at all —
`fieldAudio` needs the `audioai` import, because nothing in that package is in the REPL intro and
without the import the popup correctly offers nothing.

Observed 2026-08-06 against a Field on 9602 (sidecar off, so K1 answered): PASS in 3.8s, with the
group headers read back as `["ALife"]` and `["Audio AI"]`. Inverting the expected and mangled
spellings made it FAIL with "group header rendered ALife — titleForCategory lost the Alife spelling",
which is how the live assertion was confirmed to be reading the live popup rather than agreeing with
itself.

## Seeing it by hand

`dev_docs/agents/transcripts/docs_capture/completion_category_headers.yaml` is the eyeball version —
two boxes, one seeded `lenia` and one seeded `fieldAudio`:

```
python3 tools/field_backdoor.py --json --scratch --port <your port> \
  --command 'macro write completion_category_headers --file dev_docs/agents/transcripts/docs_capture/completion_category_headers.yaml' \
  --command 'macro run completion_category_headers'
```

Then click into either editor, put the caret at the end of the seeded line and press Ctrl+Space; the
grey group header above the rows is the thing under test. (Replay verified: `applied=3 errors=0`.) It
lives under `docs_capture/` because `AgentTranscriptMaterializer` scans `transcripts/` itself and
would demand a committed starting-point bundle for anything at the top level.

## Adding a case

Add an entry to `CASES` in `src/jvmTest/resources/fieldSuggest/probe.mjs`. A case is an async
function that throws to fail; `openPopup({ code, metadata, … })` does the work — `|` marks the caret,
`metadata` is a list of server candidates in the wire shape `Completion.metadata` really has, and the
returned helpers (`list()`, `doc()`, `press()`, `activeRow()`, `controller`) are how you read the
result. `deferred: ["complete-kotlin-ai"]` parks a reply so you can observe an intermediate state,
and `flush(name)` releases it.

Then **prove the case can fail.** A case that has never been observed red is not known to test
anything. Break the behaviour in `fieldSuggest.js`, watch your case go red, put it back. The suite
was validated that way on 2026-08-06 by 15 one-line mutations; each of the 24 cases is killed by at
least one, and no mutation survived unnoticed:

| mutation to `fieldSuggest.js` | cases killed |
| --- | --- |
| `titleForCategory` always falls back (`known[key] \|\|` → `undefined \|\|`) | 2 |
| `mergeLateItems` waits for both base and AI | 22 |
| drop the `orderItemsForGroupedDisplay` pass | 1 |
| `memberContextScore` inverted | 2 |
| `contextScore` ignored | 2 |
| recency ignored | 1 |
| `escapeHtml` stops escaping `<` | 1 |
| every language asks for AI completions | 1 |
| `imports` category weight → 0 | 1 |
| `MAX_VISIBLE_ITEMS` 80 → 400 | 1 |
| `setActive` no longer resets the doc browser | 1 |
| `tail` dropped from the row | 2 |
| fuzzy score zeroed | 3 |
| `accept` forgets `touchRecency` | 1 |
| `accept` raises no signature badge | 1 |
| request payload sends offset 0 | 1 |

## Running it

```
./gradlew --no-daemon jvmTest --tests 'afield.FieldSuggestPresentationTest'
```

Both tests print every case's PASS/FAIL line, so a failure in CI is readable without re-running
anything. Needs `node` >= 14 (`afield.util.NodeTool` finds one that actually *meets* that floor,
which matters on machines whose `PATH` starts with an ancient nvm entry). `AFIELD_SKIP_JS_TESTS=1`
opts out — deliberately and visibly, rather than having the gate quietly stop testing anything.
