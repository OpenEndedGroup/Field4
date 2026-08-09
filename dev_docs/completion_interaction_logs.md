# Completion interaction logs

Field records autocomplete at the last point where the truth is still available: the custom
`fieldSuggest.js` popup. The server response is not the presented list. The popup merges quick,
language-extension, compiler, and AI candidates, then applies fuzzy/category/context/recency ranking,
groups the result, and caps it at 80 visible rows. Training or evaluating against server response order
would therefore optimize a list nobody saw.

## Storage and correlation

The JVM appends JSONL to:

```text
~/.afield/logs/completion_interactions/YYYY-MM-DD.jsonl
```

Set `AFIELD_COMPLETION_INTERACTION_LOG_PATH` or
`-Dafield.completion.interactionLog.path=…` to choose another directory. Logging is local; nothing in
this path uploads the records. These logs contain full source and should be handled like the documents
they describe.

One `interactionId` joins an append-only event sequence:

1. `requested` — exact unsaved editor source, edited property, language, line/column/absolute caret,
   query, member-completion flag, and whether invocation was manual.
2. `presented` — a numbered revision containing candidates in exact visual/keyboard order. Distinct
   revisions capture the fast list and any later compiler or AI merge. Every candidate records its
   source, stable key, visible text, insertion, detail, documentation, category, icon, client score,
   context score, declaration proximity, and prefix edit.
3. `outcome` — `accepted` with the zero-based visible index, full selected candidate, presentation
   revision, and resulting source/caret; or `dismissed` with a reason such as `escape`, `closed`, or
   `superseded`.

The JVM enriches each line with document/box identity, the box's completion-relevant code properties,
and `gatherPreambleUpwards(box)`. On `requested`, the edited property's value is always replaced with
the browser's unsaved buffer so PropertyBag synchronization cannot make the training input stale.

A process/browser crash can leave a `requested` interaction without a terminal event. Consumers must
classify that separately as incomplete, not silently convert it to a rejection.

## Self-improvement contract

The completion helper is the component being evaluated. Useful base measurements are acceptance rate,
accepted visible rank, time/presentation revision at acceptance, no-result rate, and which candidate
source wins. Offline ranking experiments should replay a `presented` candidate set against the
correlated request context and compare the proposed order to the accepted key. Predictive models can
use the same records without reconstructing missing source or guessing the caret.

Do not train on raw server order, recency alone, or uncorrelated `complete`/`complete-kotlin-ai` logs.
The `presented` revision referenced by `outcome` is the ground-truth choice set and order.

## Verification

`FieldSuggestPresentationTest` executes the real popup JavaScript and asserts request context,
post-ranking presentation order, accepted rank/candidate/source-after, and explicit dismissal.
`CompletionInteractionLoggerTest` asserts that the JVM persists unsaved source together with relevant
properties and inherited preamble. The live browser/UI regression layer remains the transport cover.
