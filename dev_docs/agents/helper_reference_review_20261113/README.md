# Delegated example implementations: 13 November 2026

The preceding teaching-code catch-up landed on local `main` as `a8c389e4b`.
This review starts there and follows the helpers that do the substantive work in
short examples: scene builders, simulations, measurements, model operations and
search algorithms. The examples keep their executable bodies and capture declarations.

## Result

The selection covers 176 methods on 61 helpers, across 46 documentation pages
including 40 individual starting-place gallery entries. Each selected context has
both a class reference and a direct method reference. Existing explanations receive
links where they name the operation; grouped API entries cover the remaining calls.
The ImGui panel and lambda-reduction examples now identify the operations beside the
code. `CaSoundDocs.gasketBar` previously linked to its class rather than its method;
that link now reaches the method's signature and KDoc.

Highlights include the path-tracing scene installers, ray-tracing capture scenes,
Loris analysis panels, drawn-score interpretation, optimisation trajectories,
Morse–Smale and contour-tree construction, neural cellular automata, procedural
training drawings, circuit search and temporal-memory construction.

## Scope and limits

The read-only inventory found 4,517 authored or inline Kotlin body occurrences
(3,854 distinct bodies) in docs, transcripts, demos and templates. It excluded
preambles, historical/tombstoned material, generated reference pages, generated
transcript documents and generated web publications. The short/configuration-body
heuristic yielded 1,319 candidate occurrences (1,103 distinct bodies). These are
candidates, not defects: ordinary drawing, output, lifecycle, accessors and setup
calls do not justify another reference list on their own.

This is a contextual review of substantial delegated operations, not a claim that
every callable in the corpus has been manually reviewed or linked. The census uses
conservative name/type inference and unique reference targets; ambiguous overloads,
unresolved receiver types and top-level calls are outside its automatic selection.
Hidden capture scaffolding is not a reason to add a public helper reference;
`RayTracingDocsHarness` is included because the page explicitly discusses its scene
construction and readback operations. Its incidental `htmlForImage` call is excluded.

The inventory also reported 44 inline-only sources in committed EDN documents;
their inline code was included. The one partial YAML fence is the illustrative
fragment in `how_field_is_built.md`, not a failed transcript parse.

## Evidence

- `scope.json` freezes the inventory baseline and counts.
- `selection.json` identifies every selected operation, exact reference targets,
  associated reading contexts and example IDs.
- `verification.json` records the preservation hashes and source-link checks.
- `verify.py` repeats those checks against the frozen baseline:

  ```bash
  uv run python dev_docs/agents/helper_reference_review_20261113/verify.py
  uv run --project . --group docs python tools/check_doc_source_anchors.py
  ```

All 723 fences across the selected pages, including their runner attributes, match
the baseline byte for byte. Generated preambles, Kotlin/GLSL source, transcript YAML,
templates and media are unchanged. This prose-only change needs no recapture.
