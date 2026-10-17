# Stop uniform-block classes shadowing imported types

- **Handoff:** `01f6086a-7b7e-48e7-a3c2-2828b28a719c` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-16 07:28
- **Close with:** `python tools/docs_notes.py handoff-close 01f6086a "what happened"`

Refiled from the code batch on 2026-10-16. Where the fix goes:

- The generated class names come from `sanitizeKotlinTypeName(candidate.name)` in the structure generator in `afield/graphics/shader/shaders.kt` (search `val baseClassName`). There is already a suffix loop (`$baseClassName$suffix`) for two blocks that collide with each other.
- The missing input is the set of simple type names a box already sees, its implicit imports. I did not find quickly where the box script's import preamble is assembled (KotlinInterface/KotlinInterface2 only add `defaultImports(DependsOn::class, ...)`). Once that set is reachable, add it to the collision check: a name in it gets a suffix (`CameraBlock`), and a line goes in the generated chunk's comment or a box warning naming the block and the type it would have hidden.


`public/docs/afield_four_graphics.md`, section "Camera matrices in a UBO", carries this paragraph:

> `moduleHere()` generates a Kotlin class per uniform block, named by capitalising the block's name. Name a block after something the box already uses (a block called `camera` would generate a `Camera` class) and that class hides the import: `Camera()` would then build the generated class, and `camera.at` would fail to resolve.

This is a sharp edge (rule 25): a shader block name silently shadows a Field type. Fix it in the reflection code generator: emit generated block classes under a non-colliding name (e.g. a suffix or nested namespace), or detect a collision with an imported/visible type and report it as a clear diagnostic naming the block. Delete the paragraph in the same change.
