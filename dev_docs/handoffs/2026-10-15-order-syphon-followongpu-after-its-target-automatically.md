# Order Syphon followOnGpu after its target automatically

- **Handoff:** `5d3ebfd9-2cc1-4c6f-9ac1-4b959a021a2d` (project)
- **Filed from:** https://openendedgroup.com/field4/syphon/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 5d3ebfd9 "what happened"`

`public/docs/syphon.md` ("From the GPU") says: "Call it after the target is in the renderer's list."
This is an ordering trap (rule 25). `SyphonServer.followOnGpu` (`src/jvmMain/kotlin/afield/io/syphon/Syphon.kt`) inserts its copy step at the end of the insertion-ordered `renderer.renderList.others`, so it only runs after the target if the target was added first. Fix in code: order the publisher explicitly after its target (dependency on `target`, or re-insert when the target is added later), or fail loudly when the target is not yet in the list. Delete that sentence from the page in the same change.
