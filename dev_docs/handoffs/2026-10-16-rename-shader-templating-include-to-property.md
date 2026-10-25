# Rename shader templating {% include %} to {% property %}

- **Handoff:** `5523eb52-1fec-48a0-b91c-db8ba86b01ba` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/; also affects field_probes.md
- **Filed by:** human, 2026-10-16 09:22
- **Close with:** `python tools/docs_notes.py handoff-close 5523eb52 "what happened"`

Decided 2026-10-16 (Marc, handoff e814d9c5; see `dev_docs/handoffs/2026-10-16-decide-unify-or-rename-the-two-shader-include-mechanisms.md`): Field's shader-templating `{% include "name" %}` becomes **`{% property "name" %}`**. The C-style `#include` (shaderc resolver: Lygia and registered roots) is unchanged.

Work:

1. `src/jvmMain/kotlin/afield/graphics/vulkan/util/templating.kt`: accept `{% property "name" %}`, and keep `{% include %}` as a deprecated alias with identical behaviour so saved documents still compile. Update the template-span handling in `afield/runtime/kotlin/formatter.kt` and any editor grammar or completion that knows the tag.
2. Rewrite the in-repo users to the new spelling: `alife_bml.kt`, the `afield/examples/four/*SmokeTest.kt` probes (fieldProbe, fieldStats, quantileWarp), templates, `dev_docs/agents/transcripts/`, and the ~31 docs/transcript files that use `{% include`. Regenerate transcript starting points rather than editing them by hand.
3. Add a test that both spellings expand identically.
4. On afield_four_graphics.md, "Shader includes", use `{% property %}` and delete the paragraph explaining that `#include` and `{% include %}` are different mechanisms. Check field_probes.md and the other pages for the old spelling.
