# Release native study solids after copying scene geometry

Frozen findings: `4e6db45ce6bf-01`, `68263951f250-01`, `a0f1fb8e7acb-01`.

Keep every native solid within Solid.scope through census and mesh conversion. The physics example now returns copied shaded/plain meshes and genus instead of returning a live union. The polytope example returns its copied shaded mesh, releasing the shell before environment/renderer setup. The Life3D example scopes the full level-set/decomposition/census/import stage and returns only the independent scene and bounds. No native solid needs to survive into rendering.

Actual import/ktfmt and token/boundary checks cover all six Kotlin transcript bodies. Real full authored native geometry executes through CPU scene import before and after. Every copied triangle and material is hashed; all three scenes are identical before/after. The revised runs assert the observed root solids and all Life3D parts are closed before rendering. Controlled failures during census/conversion also close those solids. These are actual Manifold allocations, not fake implementations.

Verification stops before environment loading, camera and Vulkan renderer creation. It proves native lifetime and copied geometry/material preservation; it does not claim a fresh GPU render or public recapture. The renderer setup remains mechanically checked and otherwise unchanged.

Checkpoint: 341/405 addressed, 64 open. Corpus-wide mechanical coverage remains incomplete. Full guards are delegated to the lander.
