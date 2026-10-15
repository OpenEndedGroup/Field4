# Stop uniform-block classes shadowing imported types

- **Handoff:** `3bb063ad-242a-4d28-ae4d-3f961faa0cdf` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 3bb063ad "what happened"`

`public/docs/afield_four_graphics.md`, section "Camera matrices in a UBO", carries this paragraph:

> `moduleHere()` generates a Kotlin class per uniform block, named by capitalising the block's name. Name a block after something the box already uses (a block called `camera` would generate a `Camera` class) and that class hides the import: `Camera()` would then build the generated class, and `camera.at` would fail to resolve.

This is a sharp edge (rule 25): a shader block name silently shadows a Field type. Fix it in the reflection code generator: emit generated block classes under a non-colliding name (e.g. a suffix or nested namespace), or detect a collision with an imported/visible type and report it as a clear diagnostic naming the block. Delete the paragraph in the same change.
