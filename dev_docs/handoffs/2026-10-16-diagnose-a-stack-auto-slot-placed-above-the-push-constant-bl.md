# Diagnose a stack.auto slot placed above the push-constant block

- **Handoff:** `d678fb57-c56d-49d7-9627-5ff03ad0d5b3` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-16 08:32
- **Close with:** `python tools/docs_notes.py handoff-close d678fb57 "what happened"`

Follow-up from the code batch on 2026-10-16 (handoff 139, part 1). Part 2 is done: StackSL external reads go through an overloaded `<prefix>_asVec4` helper, so `?pc.<float member>` works.

Still to do: the `stack.auto` expansion in `afield/graphics/shader/shaders.kt` places the generated function exactly where the slot line is. When that line sits above the push-constant block and the program reads `?pc.*`, glslang reports `'pc' : undeclared identifier` inside generated code. Either emit the function after the push block, or report the ordering on the box's `stack.auto` channel. Then delete the "The ordering matters…" paragraph on public/docs/gpu_state.md ("The force law as an editable string").
