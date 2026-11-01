# Add the missing captures to openxr_eye_intersection.md (1 section)

- **Handoff:** `8d82d2d9-e9b3-4580-8c90-bd0e973a80c5` (project)
- **Filed from:** https://openendedgroup.com/field4/openxr_eye_intersection/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 8d82d2d9 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### On the desktop (`public/docs/openxr_eye_intersection.md:59`, medium, needs-gpu-or-hardware)

- **Point made:** The desktop preview shows the grey reference cubes and the EXT dashed ray (with the binocular marker correctly hidden), and these are what a user can check without a headset. The page has no image at all.
- **Capture:** A native renderer window capture of the starter document's desktop preview, showing the three reference cubes at -1, -2 and -4 m and the dashed ray.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
