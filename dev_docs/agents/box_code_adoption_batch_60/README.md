# Use inherited renderer and bundle in the live pass-chain example

Frozen findings: `7f180cbab721-01`.

The scene uses its parent's inherited renderer directly. The pass-chain box uses inherited renderer and bundle, removing context queries, aliases and the now-unused StandardRenderer import. The native planner also exposed the existing six-pass description as inaccurate: this declaration has five passes sharing four targets; the prose now says five.

The actual authored 1024-resolution scene and pass chain ran beneath the existing private native renderer. Before/after native captures were pixel-identical. Rerunning both revised boxes retained the exact bundle, chain and all target instances. Changing all three live push controls changed the rendered pixels; restoring the controls restored exact pixels. The source and capture were inspected. The initial assertion of six passes failed, leading to the measured documentation correction; the diagnostic is retained.

Actual editor import/ktfmt formatting and parsed container/token/GLSL preservation checks passed. Generated transcript documents are left to the lander.

Checkpoint: 348/405 addressed, 57 open. Mechanical coverage: Full corpus receipts in batch 56 and recorded-history repair in batch 57; three batch 60 Kotlin bodies formatted and checked. Full guards are delegated to the lander.
