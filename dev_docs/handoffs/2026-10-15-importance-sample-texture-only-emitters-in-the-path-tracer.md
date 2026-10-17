# Importance-sample texture-only emitters in the path tracer

- **Handoff:** `65d874c3-dd24-45f8-b0d4-fdb872dd4fe2` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 65d874c3 "what happened"`

Sharp edge (rule 25) on public/docs/path_tracing.md, emitter-sampling exclusions list:

> - **Emission that comes only from a texture.** The table's weight reads the material's emission factor, so a triangle whose factor is black and whose emissive *texture* is bright carries weight zero. ...

Fix in code: weight the emitter table by the emission factor times the emissive texture's mean (or max) so texture-only emitters are sampled. Delete this bullet in the same change.
