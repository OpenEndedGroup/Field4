# Add the missing captures to contour_trees.md (1 section)

- **Handoff:** `771a1bd8-a3d9-45cd-8b28-ef6ab43a4675` (project)
- **Filed from:** https://openendedgroup.com/field4/contour_trees/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 771a1bd8 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Join trees and split trees (`public/docs/contour_trees.md:58`, medium, docs-runner)

- **Point made:** The join tree floods from below and the split tree from above; each sees only half the story, and the contour tree merges both.
- **Capture:** Runner box on a small synthetic landscape that plots joinTree, splitTree and contourTree side by side with plot(width,height,margin) (or segmentationImage), so the reader sees the halves and the merge.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
