# Diagnose stack.auto slot ordering and non-vec4 push reads

- **Handoff:** `d1eb456e-f879-4d51-bcc9-023cee3f6966` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close d1eb456e "what happened"`

Rule 25 sharp edge on `public/docs/gpu_state.md`, "The force law as an editable string":

> The ordering matters. The generated function lands exactly where the slot line is, and a program is free to read a push member with `?pc.knobs`, so a slot above the block produces `'pc' : undeclared identifier` at a line number inside code you did not write.

Fix in the `stack.auto` expansion (`afield/graphics/shader/shaders.kt`): detect a slot placed before the push-constant block whose program reads `?pc.*` and report it in the `stack.auto` channel (or emit the function after the push block). Also (same page, "One more"): `?pc.<float member>` yields a glslang swizzle error because external reads are typed `vec4` (`StackSL.kt` `parseExternalRead`) — type external reads from the reflected member, or give a StackSL diagnostic. Delete both warning paragraphs in the same change.
