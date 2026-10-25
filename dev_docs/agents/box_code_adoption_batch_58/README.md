# Keep single-character crowd palettes finite and reject empty crowds

Frozen findings: `184894c9ac87-01`.

Validate the advertised crowd count before loading a model or allocating graphics resources. A single character uses mix = 0f; larger crowds keep exactly the original per-instance blend and phase offsets. Apply the same code to the source transcript, editable demo sidecar, saved inline fallback and matching document mirror.

Actual import/ktfmt and body-token/boundary checks cover eight transcript programs, the crowd sidecar and eight mirrored programs. Field's real persistence codec refreshes the saved fallback, verifies all source fallbacks match their sidecars, and preserves graph IDs and all non-source properties.

A fresh private native Renderer executes the authored fixture and library shaders at counts 1, 2 and 64. Native screenshots of all three cases were inspected: the single fixture, pair and crowd are visible. Runtime assertions verify one module, one mesh, the requested instance count, and finite matrices in every blended bone palette. The authored zero-count prefix rejects zero before model allocation and leaves the existing 64-instance drawing intact.

No public capture is replaced. The private Renderer on port 9719 remains alive for further GPU checks; subsequent tests must keep this session document and stop only their own scenes until all GPU work is finished. Full guards are delegated to the lander.

Checkpoint: 344/405 addressed, 61 open. Mechanical coverage: all edited programs formatted; frozen-corpus retained cases remain documented in batches 56–57. Full guards are delegated to the lander.
