# Add the missing captures to solidcraft.md (2 sections)

- **Handoff:** `046dcbde-41ba-4ea4-ad79-d04ce06814a0` (project)
- **Filed from:** https://openendedgroup.com/field4/solidcraft/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 046dcbde "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### What a sweep does with its profile (`public/docs/solidcraft.md:139`, medium, docs-runner)

- **Point made:** A profile 27 degrees off square shrinks the section by about cos 27, and a closed knot sweep meets itself at a capped seam (genus 0) rather than a ring. Both are geometric claims best proved by sight.
- **Capture:** Use a runner box capture (requires=solidcraft) with two meshes side by side: a square-on sweep and a 27-degree-off sweep with their fact lines, or a two-halves JOIN ring (genus 1) beside the single-sweep knot (genus 0).

### Surfaces (`public/docs/solidcraft.md:156`, medium, docs-runner)

- **Point made:** surfaceRevolve, surfaceBoundary and thicken make open sheets and solids, and the note warns that opposite-direction curves fold the patch over itself. None of this is pictured.
- **Capture:** Use a runner box capture of a surfaceBoundary patch between two curves and the same patch thickened. Optionally add the folded result from curves running opposite ways, as proof of the note.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
