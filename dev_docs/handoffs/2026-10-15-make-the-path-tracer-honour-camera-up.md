# Make the path tracer honour camera.up

- **Handoff:** `0766ab5e-0af3-40a5-a4c0-099f1ead1a3a` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 0766ab5e "what happened"`

Sharp edge (rule 25) on public/docs/path_tracing.md, section "Reading the tracer":

> **The tracer does not use `camera.up`.** It builds its own up as `right × forward` ... The shipped material study's camera is 11.94° out. The row appears only when they disagree.

Fix in code: the path tracer should orthonormalise `camera.up` the same way the raster camera does (or `Camera` should store a normalised, perpendicular up), and the shipped material study's camera should be re-saved with an agreeing up. Delete the paragraph (and the "non-agreeing up" status row mention) in the same change.

## Completed on Linux/NVIDIA, 2026-10-17

The premise was corrected upstream in a20fbcfa1: right already depends on camera.up, so right cross forward is the raster-equivalent orthonormal up. Added actual GPU roll/projection checks; both backends passed. No camera or render change was necessary.

Evidence and reproduction: [Linux/NVIDIA verification](../lighting/ELSEWHERE_20261017.md). The originating annotation store is absent on this host; this tracked record carries the outcome for closing there after landing.
