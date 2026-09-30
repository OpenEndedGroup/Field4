# Add a path-traced render of the mounted photo plaque

- **Handoff:** `4daf8d53-c867-4286-bfc8-5e4e64e27570` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_photo_to_solid/
- **Filed by:** agent:claude, 2026-09-30 20:50
- **Close with:** `python tools/docs_notes.py handoff-close 4daf8d53 "what happened"`

## What was asked

On `public/docs/chain_photo_to_solid.md`, the "limits of this route" paragraph explained why the
plaque and the framed assembly are drawn with the CPU rasteriser (`TriMesh.renderPng`) rather than
the GPU path tracer. The human's note: "ignore" / "we could just use the real pathtracer? nobody
cares". The docs pass deleted the explanatory paragraph (the reader does not care how the figure was
made). What remains is the actual suggestion: render the mounted plaque with the real path tracer.

## Why it is bigger than a docs edit

It is a new capture that needs a live Vulkan `Renderer` and a native window, which the page's
current headless box captures do not have. Per AGENTS.md §3 it must be a real capture, not a stand-in.

## Evidence

- `PathTraceScene.importTriMesh` in `src/jvmMain/kotlin/afield/four/fabricated/PathTraceTriMesh.kt`
  accepts a `TriMesh` plus material, so the meshes `PhotoToSolidChain.mount()`
  (`src/jvmMain/kotlin/afield/examples/chain/PhotoToSolidChain.kt`) produces can be imported as is.
- Current figures: `capture-box="photo_plaque"` and `photo_boolean` in
  `dev_docs/agents/transcripts/photo_to_solid_chain.yaml`, drawn by `TriMesh.renderPng`
  (`src/jvmMain/kotlin/afield/stdlib/mesh_field.kt`).
- Precedent for a path-traced chain ending with a native-window capture: `chain_polytope_render.md`,
  `chain_physics_render.md`, `chain_life3d_render.md` (they build a `PathTraceScene`).
- Trap: `importTriMesh`'s KDoc example uses `toTriMesh(normals = true)`; that is fine for drawing
  but the page's own section "Asking for normals breaks the round trip" says not to feed that mesh
  back into `toSolid`. Keep the two uses separate.

## Proposed approach

Add a renderer-parented box to the transcript (the `graphics_renderer_launch.yaml` pattern from
AGENTS.md §6), import the mounted assembly into a `PathTraceScene`, and capture it as a window
capture — probably `capture-profile="linux-nvidia"` if it needs RT hardware like the other chains,
otherwise a macOS window capture. Add it as a fourth figure after "Mounted in a frame", keeping the
CPU renders (they are what CI checks headlessly).

Alternative: replace the CPU renders outright with path-traced ones. This loses headless
reproducibility for the page's figures and CI's `chain.photo-to-solid` check still would not see them.

## Questions for the human

1. Add a path-traced figure, or replace the CPU renders?
2. Which capture profile — does the path tracer run acceptably on the Mac (KosmicKrisp) or only on
   the Linux/NVIDIA box?

## What the page needs afterwards

One new `diffstatic_runner` fence and a sentence of caption in or after "Mounted in a frame". No
limitation paragraph needs to come back.
