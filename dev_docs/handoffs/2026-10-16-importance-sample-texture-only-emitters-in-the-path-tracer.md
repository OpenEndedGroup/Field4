# Importance-sample texture-only emitters in the path tracer

- **Handoff:** `2cbd888d-d927-49a3-b0c2-1a7b26c17dcb` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-10-16 08:54
- **Close with:** `python tools/docs_notes.py handoff-close 2cbd888d "what happened"`

Refiled from the code batch on 2026-10-16, not started. Weighting the emitter table by texture brightness needs each emissive texture's mean or max on the CPU when the scene is packed (decode or mip-reduce), and a path-traced check that sampling and the convergence figures still agree.


Sharp edge (rule 25) on public/docs/path_tracing.md, emitter-sampling exclusions list:

> - **Emission that comes only from a texture.** The table's weight reads the material's emission factor, so a triangle whose factor is black and whose emissive *texture* is bright carries weight zero. ...

Fix in code: weight the emitter table by the emission factor times the emissive texture's mean (or max) so texture-only emitters are sampled. Delete this bullet in the same change.
