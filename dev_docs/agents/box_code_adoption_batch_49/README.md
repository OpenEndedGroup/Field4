# Current teaching headers and explicit shader contracts

Six frozen findings addressed: `e8da8e8fdcb8-01`, `ab4732b75877-01`, `a96d6bf12ad1-01`, `6caa5d8a54fe-03`, `956a69992e2a-01`, `ae09b7bae9fc-02`.

Loaded Petri, tessellation and FieldML examples lead with their current invariant or dependency instead of dates and implementation history. The complete original headers remain in changes.json as provenance. Measured examples and their arithmetic are unchanged.

Both exact skin vertex copies name their projected local clipPosition. The existing vViewPos output is explicitly documented as unused clip-space xyz; its interface and value remain intact. Computed cluster comments explain the two packed uvec4 contracts and the 81 vertices / 128 triangles / 384 indices per cluster. NR shader comments document host frame packing, image UV orientation and linear LDR colour conversion.

## Shared verification

Actual import/ktfmt handles ten changed Kotlin bodies, including three inline saved sources. Five collection calls become house-style literals. Actual GLSL formatting handles five shader bodies. No skips.

verify.py compares all baseline executable tokens (normalizing only the local clipPosition rename and collection literals), execution markers, YAML operation/metadata structure and EDN IDs/graph/non-code properties. All match. Five neighboring inline bodies, including FieldML, stay byte-for-byte intact. The actual ClusterGeometry writer, OpenDlssNrStudy frame/LINEAR_LDR inputs and paired fragment non-use of vViewPos confirm the comments.

Field SavingContext refreshes the two skin vertex sidecar fallbacks. Its live assertions confirm all other sidecars were already synchronized, box IDs/order and non-source properties remain unchanged, and every resulting inline fallback matches its sidecar. The saved-document AGENTS guidance is followed through the codec rather than editing that metadata by hand.

No native, Petri-search or tessellation replay is repeated: this batch preserves executable tokens, shader expressions and interfaces. It does not claim a new GPU execution or capture. Required full guards are delegated to the lander. Evidence retains formatter/token verdicts and actual codec results.

Checkpoint: 323/405 addressed, 82 open. Corpus-wide mechanical coverage is still incomplete.
