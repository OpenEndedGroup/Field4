# Complete split-package ownership map

Current generation: symbol-map-v4 in the private refactor session; declaration-index-v4 is its
source snapshot. Older v2/v3 reports are historical and must not be used as the remaining-work list.

The fresh inventory contains 16,672 top-level declarations, with zero parser errors. Within the
seven split packages, 5,685 families have one destination and 17 have explicit per-declaration
destinations. Zero families are unassigned; no family is implicitly retained. Whole-package
rules remain separate. The FLineExact KDoc parser issue was repaired.

`assignments.json` contains the full reviewed family map and the declaration-specific assignments.
`expanded.json` is synchronized with the active session recipe. Regenerate an inventory after source
edits; declaration IDs survive line shifts, but file hashes still enforce source freshness. Reuse
these assignments with `tools/refactor.py symbol-map`, then install its recipe and replan.

The 17 multi-destination families are represented by `splitSymbols`, with source/signature evidence
in the expanded map. Their explicit imports and qualified uses remain `split-symbol` findings until
a receiver/binding-aware review chooses the right destination. This is complete ownership, not
proof of binding equivalence or a completed declaration relocation.

Generated swizzles/operators belong to math.linalg; linalg-packages.properties owns regeneration.
VisionOS belongs to graphics.visionos, alignment to ui.layout, AI logging to ai.diagnostics and
language specifications to runtime.languages. The legacy field.graphics package moves to
geometry.path.java2d. `shared` in the shared-types fixtures is generated user code, not an unmapped
application package. Persistent EDN tag names remain unchanged.

Next phase: relocate source declarations and repair same-package consumers/private helpers;
regenerate outputs; resolve explicit split-family uses; compile JVM/JS and run representative box,
EDN/sidecar and runtime checks. Do not reopen the ownership audit just because a declaration-move
finding remains. Neither this map nor a recorded review decision clears those checks.

Run the current refactor tools from the session state's owner checkout. The migration worktree
was created before declaration-specific splitSymbols support and still contains older tooling.
Its older tool implementation is not authoritative for the current recipe schema.
