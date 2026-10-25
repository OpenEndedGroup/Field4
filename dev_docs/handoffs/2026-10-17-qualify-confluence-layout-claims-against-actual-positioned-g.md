# Qualify confluence layout claims against actual positioned graphs

- **Handoff:** `ee3f6373-f715-4146-b1ac-d785c1f1b1ff` (project)
- **Filed from:** https://openendedgroup.com/field4/dev_docs/agents/transcripts/confluence_grid.yaml; also affects dev_docs/agents/demos/confluence_grid/README.md
- **Filed by:** human, 2026-10-17 00:27
- **Close with:** `python tools/docs_notes.py handoff-close ee3f6373 "what happened"`

The description in `dev_docs/agents/transcripts/confluence_grid.yaml` and the saved demo tour
`dev_docs/agents/demos/confluence_grid/README.md` promise identical final shapes. The current
three-round WL/hash/BFS layout is a heuristic: equal labels can retain order, and structural
readback agreement does not imply equal positioned edges.

The actual twenty-cell run after fixing landing produced the correct independent lambda answer
and exact snapped landing for every row. Positioned node/type/port-edge signatures match across
all schedulers only for `succ two` and `3^3`; the other three rows differ. Preserve that measured
 distinction instead of claiming coordinate multisets prove equal geometry. Counts also differ
from the old embedded historical measurements: 35,86,127,86,123 reductions respectively.

The old renderer-free Frames zero-tick diagnosis is also stale: a real private box advances five
Frames ticks with no native Window/Renderer. The current example retains its explicit blocking
CPU driver and streams real geometry into the existing Three.js viewer.

Evidence: `box_code_adoption_batch_9/evidence.tar.gz`, `runtime/confluence_landing.json`,
`runtime/frames_clock_live.json`, actual private-browser capture `runtime/confluence_landing.png`.
The code now reports actual landing and positioned topology separately. Please align descriptions
and tours; this code pass leaves surrounding prose to its normal editorial workflow.
