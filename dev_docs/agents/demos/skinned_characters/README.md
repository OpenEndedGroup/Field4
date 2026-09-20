# skinned_characters — a rigged mesh you can bend, and a crowd of them

Open this document in a fresh Field and run the boxes top to bottom.

```
uv run --project . --group backdoor python tools/field_backdoor.py --port 9101 \
  --workspace dev_docs/agents/demos --document skinned_characters --command 'launch rig_facts'
```

**The parameter to poke: `time`, the first slider in box 2.** It runs `0 → 2` seconds. At `0` and
`2` the cylinder is a straight tube; at `1` the upper bone has rotated 60° about `+z` and the tip
has swung to `x = -1.982`. Box 2 prints the tip's `x` and the mesh's top `y` at every slider
position, so the pose is a number and not only a picture. The second slider, `bend to curl`, mixes
in a clip that rotates the *lower* bone as well — at `time 1, bend to curl 1` the tip reaches
`x = -2.902`.

Skeletal animation for `afield.four`: bones and per-vertex weights read out of a glTF, a
joint-matrix palette evaluated at a time, and linear blend skinning into a deformed surface. Before
this, the only mention of a bone in the whole of `src/jvmMain` + `src/commonMain` was
`PathTraceGltf.kt` refusing to render one — the codebase naming a missing capability in its own
error message.

## The boxes

**0 — A window and a renderer, which box 4 needs.** Added 2026-09-19 after opening this document
the ordinary way (`./field.sh` on it, which is how everything else in this folder is opened) and
finding box 4 failed with *"must pass in a value for context parameter afield.four.Renderer"*. It
had only ever been run under `field_backdoor.py --graphics-context`, where the agent's own context
box supplies a `Renderer` by type. Every other GPU demo in this folder —
`frame_history_reprojection`, `path_tracer_instrument`, `path_tracing_emitters`,
`path_tracing_medium` — ships exactly one box declaring `prop<Renderer>(renderer)`; this one did
not. Box 4 is connected beneath it, so `renderer` now resolves by name.

**1 — A rigged mesh, and what the loader now reads out of it.** The node tree, the bones, the
clips, the weight sums, and the bind-pose bit-identity count, then four poses side by side. The
skeleton indexes *nodes*, not bones, because Assimp gives a bone a name and an inverse bind matrix
and no parent — and because a bone's parent chain routinely runs through nodes that are not bones.

**2 — Drag `time` and watch it bend.** Two sliders driving a live re-skin. This is the demo.

**3 — The refusal `PathTraceGltf` used to make.** Path traces the *deformed* mesh at a chosen pose
and cross-checks its bounds against the CPU bridge's. Two independent readers of one deformation:
the tracer walks Assimp and stages world-space triangles, the bridge packs a 12-float vertex buffer
and builds a `TriMesh`.

**4 — The same deformation on the GPU, no shader change.** Field's own glTF module, Field's own
bundled shaders, a real Vulkan window. `CpuSkinner` rewrites the position and normal fields of the
packed vertex buffer `applyToMesh` already uploads, so nothing in the pipeline knows the mesh is
skinned. **Run box 0 first** — it is box 4's parent and supplies the renderer. Edit `poseTime` at
the top and re-run — `0` is a straight tube, `1` is the 60-degree bend.

**5 — The candy wrapper.** One tube, one pose, two ways of averaging the joints.
Flip `useDualQuaternions` at the top and re-run: `false` pinches the waist shut to a point (the
hourglass), `true` keeps it round and just twists. The printed number is the min radius at the
`y = 2` ring — **0.0% of rest** for linear blending against **100.0%** for dual quaternions.
Evidence: `dev_docs/agents/evidence/skinning/twist_linear_blend.png` and `twist_dual_quaternion.png`,
both captured from this box.

**A trap this box records rather than hides:** drawing both methods at once as two `Mesh` objects
does **not** work. Both read the instance's vertex buffer at draw time rather than at
`applyToMesh` time, so both tubes show whichever pose was written *last* — measured, with
lbs-then-dqs rendering two round tubes and lbs-then-lbs rendering two pinched ones, while the
printed numbers stayed correct throughout. That would have been a figure that lies, so the box draws
one tube and toggles.

**A second thing box 5 records:** from 2026-09-19 until 2026-09-20 this box **did not compile**.
The rewrite from two meshes to one left four `println`s naming the two-mesh version's `rL`, `rR`,
`outR` and `rRest`, so it failed with *"Unresolved reference 'rRest'"* — while `./gradlew check`
stayed green, because box code is compiled by the K2 REPL and no Gradle task ever sees it. It was
found by running every box of this document one at a time, which is the only thing that does find
it. Both branches now reproduce the numbers quoted above: **0.0% of rest** for linear blending,
**100.0%** for dual quaternions.

**6 — Skinning on the GPU, and a crowd of 64.** The stretch box 4 did not take. Box 4 is the CPU
skinner drawing through the GPU: `CpuSkinner` rewrites the packed vertex buffer and `applyToMesh`
re-uploads all of it, every pose. That is 2.8 ms for a 50,000-vertex character, and it is why box
4 shows *one* character. Box 6 puts the deformation in the **vertex shader** — a joint stream, a
joint-matrix palette sliced by `gl_InstanceIndex`, and `Mesh.instanceCount` — so 64 characters,
each at its own phase of its own blend of two clips, are one mesh, one draw call, and one upload
of 128 matrices per frame. Nothing re-uploads a vertex after setup. **Run box 0 first**; box 6 is
connected beneath it. **Edit `count` at the top and re-run.**

## The rig

`dev_docs/agents/fixtures/skinning/bending_cylinder.gltf` — two bones, 60 vertices, 96 triangles,
two clips. Generated by `afield.four.modules.RiggedFixture` and regenerated by
`./gradlew runSkinningEvidence`, not downloaded: there was no rigged model in the repository
(`ToyCar.glb`, `quad.glb` and `transforms.gltf` are all rigid), and a generated one has constants
chosen so its bind pose is *exact in binary*. That is what lets `GltfSkinningTest` assert
bit-identity rather than a tolerance.

## Run captured here

Every box was launched one at a time through `tools/field_backdoor.py --port 9101`, and both
sliders were then driven with a `slider-update` message. Re-run once more at the end, all four in
one fresh session from this committed document, with `--graphics-context` so box 4 had a renderer:

```
box 1  nodes [ROOT, cylinder, armature, boneLower, boneUpper], parents [-1, 0, 0, 2, 3]
       bones [boneLower -> node 3, boneUpper -> node 4]
       clips [bend (2.0s, 1 channels), curl (2.0s, 2 channels)]
       weights over all 60 vertices: worst |sum(w) - 1| = 0.0, 0 unweighted, 0 past the 4-wide cap
       bind pose: 60 of 60 vertices bit-identical to the unskinned mesh

box 2  time 0.60 s, bend->curl 0.00 -> tip x -1.5801, top y 3.9119
       time 1.00 s, bend->curl 0.00 -> tip x -1.9821, top y 3.4330     <- after dragging `time`
       time 1.00 s, bend->curl 1.00 -> tip x -2.9017, top y 2.6523     <- after dragging the mix

box 3  rest pose   96 triangles, x in [-0.5000, 0.5000]
       bend t=1    96 triangles, x in [-1.9821, 0.5000]
       warning: Skinned mesh 'cylinder' is traced deformed at 'bend' t=1.0s.
       max |difference| between the tracer and the CPU bridge = 0.0000000

box 4  gpu skinned at t = 1.00 -> tip x -1.9821, top y 3.4330
       (run with --graphics-context; the native window screenshot is
        dev_docs/agents/evidence/skinning/gpu_t100.png, and gpu_t000 / gpu_t050 are the same
        box at poseTime 0 and 0.5 — a straight tube, a 30-degree bend, a 60-degree bend)
```

Re-run in full on 2026-09-20 after box 6 was added — all seven boxes launched one at a time
through `tools/field_backdoor.py --port 9101`, **0 errors**:

```
box 5  twist t = 1.00, min radius at the y = 2.0 ring (rest 0.5000)
       linear blend     0.000000   (  0.0% of rest)
       dual quaternion  0.500000   (100.0% of rest)    <- after flipping useDualQuaternions
       non-rigid joints 0, antipodal flips 0

box 6  frame 0 .. 960: 64 instances, 128 matrices uploaded, one draw call
       (960 frames in 8 s, ~120 fps, while animating)
```

Box 6's native window capture is `dev_docs/agents/evidence/gpu_skinning/demo_box6_live.png`, and
`demo_all_boxes_live.png` is the whole document running at once — box 6's tinted crowd with boxes
4 and 5's normal-shaded tubes standing in the middle of it.

**One trap this retires.** Backlog Z7 says the native window capture times out against a per-frame
`applyToMesh`, which is why box 4 poses once instead of animating. Box 6 animates *and*
photographs: it was captured mid-animation with no timeout. The trap is the per-frame **vertex
re-upload** specifically, not any per-frame buffer write — box 6 rewrites its palette every frame
and captures fine.

The method, the numbers with their populations and the traps are in
`dev_docs/SKINNED_CHARACTERS.md` and `dev_docs/GPU_SKINNING.md`. Rendered frames of the deformation are in
`dev_docs/agents/evidence/skinning/`.
