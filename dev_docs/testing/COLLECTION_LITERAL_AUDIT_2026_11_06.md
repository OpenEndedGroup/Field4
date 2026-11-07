# Collection-literal example audit — 2026-11-06

The mixed-map example in `dev_docs/viz_demo.kt` did not compile: the string collection literal was
inferred as a numeric vector. Even specifying `mapOf<String, Any>` did not fix that inference.
The string list now uses `listOf(...)`; the fixer preserves factories after `to`, including across
a line break, so another cleanup cannot reintroduce the error.

The audit also repaired a palette with Double arguments to `Vec4` (now Float literals), seven
Functions examples missing `with(Functions)`, and two kit identity examples missing imports and
passing a Path to the File constructor. All eleven repaired bodies pass the real compiler's strict
gate. The visualizer example additionally ran with an appended assertion that its map contains
the intended three strings; the receipt retains the emitted success marker and execution verdict.

## Coverage

Base: `da9668585`. The receipt fingerprints the tested working-tree sources, including the repairs.
Docs bodies use the docs runner's own effective-code extraction: folds retain their code, presentation
markers disappear, and diff fences select the current result rather than the deleted example.

| Check | Result |
| --- | --- |
| Transcript owners through `checkBoxCodeCompiles` | 139 passed, including all 134 owners selected by the refined inventory |
| Additional distinct JVM bodies, compiled without evaluating their bodies | 491 of 522 compiled independently |
| Bodies still requiring context or review | 31, explicitly retained with compiler diagnostics |
| Repaired examples, strict compiler gate | 11 of 11 passed |
| Inventory/report and fixer tests | 48 passed |

The 31 are **not certified** and are not automatically 31 bugs. Most refer to earlier variables,
renderer parents, generated mesh properties or imports supplied elsewhere. The list also contains
an illustrative ellipsis, a Kotlin/JS fragment and a module source that cannot access its internal
APIs when compiled as a box. Compilation does not certify pixels, audio or runtime behavior;
the separate docs certification remains with its owning agent.

The scan excludes strings/comments and does not certify runtime-generated code. The underlying
census also flags one YAML presentation-wrapper parse in `how_field_is_built.md`; that displayed
hello-world transcript contains no collection literals and is outside this compiler selection.

Receipts: [source hashes and results](collection_literal_audit_2026_11_06/receipt.json),
[remaining contexts and diagnostics](collection_literal_audit_2026_11_06/needs_context_or_review.json).
These are dated evidence, not a substitute for checking changed sources.

## Reproduce

```bash
uv run --project . --group backdoor python tools/audit_collection_literals.py
AFIELD_GUARD_CHANGED_PATHS_FILE="$PWD/build/example-literals/transcripts.txt" ./gradlew checkBoxCodeCompiles
uv run --project . --group backdoor python tools/field_backdoor.py --json --timeout 3600 \
  --command 'run-jvm-main afield.examples.CollectionLiteralCompileAudit --timeout 3600'
uv run --project . --group backdoor python tools/audit_collection_literals.py \
  --results build/example-literals/compile-results.json
```

The inventory selects candidates, not compilation verdicts. The summary refuses empty, duplicate,
incomplete or stale source-hash reports. The JVM auditor records every rejected body; `--gate`
fails on any rejection when checking a selected set of complete examples. It runs on a worker JVM,
because synchronously invoking the compiler from a live box blocks the Runloop needed for setup.

The working CI backlog was reconciled separately on `nightly/ci-journal` (`e39adda4e`): 69 historical
IDs retired, 20 follow-ups retained, and the scratchpad reduced from 7,213 to 67 lines. Its audit
and verbatim archive are under `reports/2026-11-06-backlog-audit.md` and
`reports/2026-11-06-scratchpad-archive.md` on that journal branch. No capture assets changed here.
