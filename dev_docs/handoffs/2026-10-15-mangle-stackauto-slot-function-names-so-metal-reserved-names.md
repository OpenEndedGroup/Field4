# Mangle StackAuto slot function names so Metal-reserved names build

- **Handoff:** `8df5dc18-d0b8-4cef-ac23-c03809551ab8` (project)
- **Filed from:** https://openendedgroup.com/field4/stackauto_conversation/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 8df5dc18 "what happened"`

The Limits section of `public/docs/stackauto_conversation.md` carries this paragraph:

> On macOS the shader is translated to Metal, and a function named like one of Metal's types (`decay`, `array`) will not build there. A slot with such a name is refused before it is compiled.

This is a sharp edge (rule 25): the user's slot name leaks into the generated GLSL function name, which SPIRV-Cross/MSL then collides with Metal reserved identifiers. Fix in code: mangle the generated slot function name (e.g. a `stackauto_` prefix) in the StackAuto slot binding under `src/jvmMain/kotlin/afield/lang/stacksl/` so any slot name builds on Metal, then remove the refusal. Delete the Limits bullet in the same change.
