# Add a path-traced hero of the sliced layer stack to chain_ceres_manifold_contours

- **Handoff:** `9098c6be-3067-4141-ad8c-f8f90a00c317` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_ceres_manifold_contours/
- **Filed by:** agent:claude, 2026-09-30 20:49
- **Close with:** `python tools/docs_notes.py handoff-close 9098c6be "what happened"`

## The notes that led here

- `8dd248d6` (public/docs/chain_ceres_manifold_contours.md:3): need hero image to match other sections
  > Three parts of Field, one object, and none of the three knows the other two exist. afield.stdlib.polytope holds the Conway operator algebra, the canonicalisation iteration and a second-order realizer over Ceres. afield.manifold does exact geometry on closed solids over Manifold's C ABI. Solid.slice is the single call that crosses from 3D down to 2D, and a stack of those is a print.

## What was asked

Note 8dd248d6 on `public/docs/chain_ceres_manifold_contours.md` (the opening paragraph, lines 3–7):
"need hero image to match other sections". The other chain pages open with a path-traced hero,
and this one opens with prose and then a table.

## Why this is bigger than a docs edit

Every sibling hero is a `capture="native-window"` fence with `hidden=true`, driven by an
OperationTranscript macro under `dev_docs/agents/transcripts/` that builds a renderer parent and a
`PathTracingRenderer` scene and prints a `"... study converged"` line for `wait-output-contains`:

- `public/docs/chain_polytope_render.md:11` → `dev_docs/agents/transcripts/polytope_path_trace_lineage.yaml`
- `public/docs/chain_skinned_volume.md:13` → `skinned_volume_path_trace.yaml`
- `public/docs/chain_physics_render.md:9` → `physics_manifold_path_trace.yaml`
- also `chain_life3d_render.md`, `chain_rectsl_spacetime.md`, `chain_scene_to_light.md`

This page has no transcript. Writing one is a new file outside the page, and it needs a live GPU
capture that has to be checked by eye (AGENTS.md §3: "a capture exercises the real code, or it
does not exist"). A fence cannot point at a transcript that does not exist yet, or the docs build
fails.

## Proposed approach

A new transcript, e.g. `dev_docs/agents/transcripts/polytope_layer_stack_path_trace.yaml`, shaped
like `polytope_manifold_path_trace.yaml` (which already goes Solid → `toTriMesh(normals = true,
minSharpAngle = 20.0)` → `scene.importTriMesh`):

1. `conway("gC").polyhedron.canonicalize()`, triangulate with `NormalPolicy.Flat`, `weld(1e-6)`,
   `toSolid()`, then gauge it to volume 1 on the bed, the same way the page's `onTheBed` does.
2. Slice at the page's 48 layer midpoints. Turn each `Section` into a slab with
   `Section.extruded(h / n)` (`src/jvmMain/kotlin/afield/manifold/Section.kt`, `fun extruded`),
   translated to the bottom of its layer. The result is the object *as printed*: a stepped
   pentagonal icositetrahedron.
3. Optionally put the raw `gC` stack next to it. It is a plain unit cube, which is the page's
   punchline: the gyro is entirely combinatorial until canonicalisation.
4. Colour the slabs cyan at the bed through orange at the top, matching the `gyro.png` plan view,
   or use two materials alternating by layer so the steps read.
5. Venice EXR, pale floor, and camera/exposure settings copied from the sibling transcripts, so
   the page matches them visually. End with `println("layer stack study converged: ...")`.

Alternatives: (a) a path-traced `kkC`/`relaxWithCeres` self-intersecting body. That is more
dramatic, but it needs the Ceres native wrapper at capture time and shows a failure, not the
subject. (b) Use the existing `gyro.png` as the hero. It is a plan-view rasterisation, not a
render, so it does not match the other pages.

## Questions for a human

- Should the hero show canonical `gC` alone, or raw beside canonical?
- Should the slabs be height-coloured like the plan view, or use one material with alternating
  shades?
- Should the capture go at the very top (after the first paragraph, as on `chain_polytope_render`),
  or after the paragraph that states the page's question (line 13–16)?

## What the page needs once this is done

A hidden hero fence after the opening paragraph:

```
```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_ceres_manifold_contours/stack.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="layer stack study converged" embed=true hidden=true timeout="300" requires="manifold" name="chain-contours-hero" alt="..." macro-file="../../dev_docs/agents/transcripts/polytope_layer_stack_path_trace.yaml" capture-box="..."}
// Built and launched from dev_docs/agents/transcripts/polytope_layer_stack_path_trace.yaml.
```
```

It also needs one or two sentences under the hero that say what the reader is looking at (48
extruded layers of the canonical form, at volume 1), and the smoke `manifold.polytope.contours`
could assert the same layer count.
