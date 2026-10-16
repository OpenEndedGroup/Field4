# Order Syphon followOnGpu after its target automatically

- **Handoff:** `d4c5dbed-0c49-4eae-89ff-f74d4f410013` (project)
- **Filed from:** https://openendedgroup.com/field4/syphon/
- **Filed by:** human, 2026-10-16 08:53
- **Close with:** `python tools/docs_notes.py handoff-close d4c5dbed "what happened"`

Refiled from the code batch on 2026-10-16, with analysis. RenderTarget renders `preTargets` before `others` (RenderTarget.kt), so a target in preTargets is always ahead of the publisher. The trap is only a target in `others` added after `followOnGpu`. A membership check (refuse when the target is in neither list) would wrongly refuse a target rendered inside another pass.

Workable designs: (a) a marker interface (say `RunsAfterOthers`) that RenderTarget's `others` insertion respects by keeping such targets at the end; direct `others[key] =` writes would need to go through it too. (b) The publisher detects, during its own render, that its target's key comes after it, and reorders on the next Runloop tick, outside the iteration.


`public/docs/syphon.md` ("From the GPU") says: "Call it after the target is in the renderer's list."
This is an ordering trap (rule 25). `SyphonServer.followOnGpu` (`src/jvmMain/kotlin/afield/io/syphon/Syphon.kt`) inserts its copy step at the end of the insertion-ordered `renderer.renderList.others`, so it only runs after the target if the target was added first. Fix in code: order the publisher explicitly after its target (dependency on `target`, or re-insert when the target is added later), or fail loudly when the target is not yet in the list. Delete that sentence from the page in the same change.
