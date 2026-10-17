# Unify or rename the two shader include mechanisms

- **Handoff:** `a7510623-f531-40e1-8ea5-332c95b13c3d` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close a7510623 "what happened"`

`public/docs/afield_four_graphics.md`, section "Shader includes":

> Note that `#include` here is the C-style directive handled by the shader compiler's include resolver, while `{% include %}` above is Field's own templating over box properties: two different mechanisms that happen to share a name.

A name that misleads (rule 25). Decide whether to unify them (e.g. let `#include "name"` resolve box properties first, then registered roots like Lygia, so one directive covers both), or rename one. Delete the paragraph in the same change. This may need Marc's call.
