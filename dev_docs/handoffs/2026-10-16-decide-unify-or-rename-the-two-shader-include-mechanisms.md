# Decide: unify or rename the two shader include mechanisms

- **Handoff:** `e814d9c5-b582-455e-837f-7359a8ab6e8c` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-16 07:31
- **Close with:** `python tools/docs_notes.py handoff-close e814d9c5 "what happened"`

Moved from the code batch to decisions on 2026-10-16. The brief itself says it may need Marc's call: whether to unify `#include` (shaderc resolver) and `{% include %}` (Field templating over box properties), or rename one.


`public/docs/afield_four_graphics.md`, section "Shader includes":

> Note that `#include` here is the C-style directive handled by the shader compiler's include resolver, while `{% include %}` above is Field's own templating over box properties: two different mechanisms that happen to share a name.

A name that misleads (rule 25). Decide whether to unify them (e.g. let `#include "name"` resolve box properties first, then registered roots like Lygia, so one directive covers both), or rename one. Delete the paragraph in the same change. This may need Marc's call.

## Decision, 2026-10-16 (Marc)

**Rename the templating one to `{% property "name" %}`.** `#include` (the shaderc resolver: Lygia and the registered roots) is unchanged. `{% include %}` stays as a deprecated alias so saved documents keep compiling. Delete the "two different mechanisms that happen to share a name" paragraph in the same change. Filed as a code handoff.
