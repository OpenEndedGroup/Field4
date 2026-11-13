# Teaching-code implementations, November 2026

This pass applies the cellular-automaton pilot throughout authored teaching code.
`checkpoint.json` freezes the starting commit and records progress. Candidate counts
include hidden capture scaffolding and are not a count of defects.

The editing unit is the lesson's transformation: show inputs, intermediate values,
iteration and mapping in the box. Keep domain algorithms, native calls, resource
ownership and output primitives as ordinary library operations. Later reuse may stay
concise when its actual implementation is already taught in the same example.

For every changed example, compare default results with the previous implementation,
exercise useful parameter edits and reruns, format through the real editor formatter,
and recapture affected figures using the normal docs runner. Inspect the images.
Generated preambles, historical recordings and generated mirrors are excluded from
hand edits. Canonical transcripts and authored copies are reviewed together.

A retained abstraction needs a concrete reason in the review ledger. An unverified
edit remains pending; a queued branch is not a landed batch.

Native scene checks read the exact canonical transcript rather than a copied scene:
`uv run --group docs python dev_docs/agents/teaching_code_open_implementations/verify_rendered.py material`
(or `glass`). They compare packed triangles, materials, BVH and camera with the old installer,
render on the real device, then launch a parameter edit in the same box. `verify_vr.py` checks the
actual stereo panel, centre-ray UV, control edits, and texture reuse/recreation across reruns.
Their images and raw logs stay under `build/`; inspected receipts are committed beside this file.
