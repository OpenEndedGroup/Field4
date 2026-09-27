# StackSL gallery workspace

A canonical_graphics-style Field workspace (see
`../canonical_graphics/README.md`) holding the StackSL examples used by
`stacksl_examples.md`.

Each box is one example:

| File | Purpose |
|------|---------|
| `<name>_stackSL_.stacksl` | the StackSL program (the source of truth) |
| `<name>_vertex_.glsl` | shared fullscreen-triangle vertex |
| `<name>_fragment_.glsl` | fragment with the `{{ stack.auto.program }}` slot |
| `<name>_code_.kt` | binds `module.program = stackSL` and draws fullscreen |
| `<name>_metadata_.yaml` | `docOrder` |

The docs gallery is rendered by `StackSLGalleryHarness`, which reads every
`*_stackSL_.stacksl` program here and renders them in a single graphics context
(one renderer, one `{{ stack.auto.program }}` slot re-bound per program) into an
sRGB target, as the window's swapchain is, so each PNG is what the program shows in a
live window rather than its darker linear values. Edit a
`.stacksl` file and regenerate with
`run_field_tests.py --id docs.stacksl.gallery`.
