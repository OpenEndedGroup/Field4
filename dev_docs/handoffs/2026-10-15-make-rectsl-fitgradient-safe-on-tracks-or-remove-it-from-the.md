# Make RectSL fitGradient safe on tracks or remove it from the track surface

- **Handoff:** `39aa7148-78a3-4a11-80d4-07ed87363dac` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_inverse_animation/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 39aa7148 "what happened"`

Rule 25 sharp edge on rectsl_inverse_animation.md:

> Use `fitParameters` on a track, not `fitGradient`. `fitGradient` moves every number in the graph, including key times, clamp bounds and easing constants, and moving those breaks the track.

Fix: make `fitGradient` refuse (or route to `fitParameters`) on a RectSL Track, so the wrong call cannot break a track. Delete the paragraph in the same change.
