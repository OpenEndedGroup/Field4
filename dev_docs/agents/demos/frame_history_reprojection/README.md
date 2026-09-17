# Frame history and reprojection — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh frame_history_reprojection` if you
have copied this folder into your workspace).

**Launch the four boxes left to right:** "frame history renderer", "lit scene", "reprojected
accumulation", "the four debug modes". A window opens on a grid of twenty-five lit spheres with the
camera slowly turning, and the fourth box starts cycling the debug view.

## What the four modes are showing you

The fourth box flips `motionDisplay` through 0..3 every 150 frames and prints which one it is on.

| mode | what you see | what is wrong if you see something else |
| --- | --- | --- |
| **0 beauty** | the temporally accumulated image, turning smoothly | if it ghosts or smears behind the spheres, the reprojection is not arriving |
| **1 backward motion** | a nearly uniform tint — the whole frame is panning the same way. Stop the camera (comment out the `yaw` in box 3) and it goes **flat grey** | a grey frame *while the camera turns* is a reprojection stuck at the identity |
| **2 previous UV** | a smooth corner-to-corner ramp: black at top-left, red rightwards, green downwards, yellow at bottom-right | this is the only mode that shows a **constant offset**. A ramp shifted bodily, or with red and green swapped, is an offset or a transposed axis that mode 1 cannot see, because an offset inside a *difference* is invisible |
| **3 availability** | almost entirely green, with a **thin red band on one edge** — the edge the camera is turning away from. Those pixels were off screen last frame | entirely green while the camera turns means the availability flag is hard-wired on. Entirely red means the CPU validity rule is rejecting every frame |

That red band is the honest one to watch. It is the whole argument for the debug view existing:
boxes 0 and 3 disagree about nothing when reprojection is right, and when it is wrong box 0 still
looks fine.

## The one thing to change

In box 3:

```kotlin
FabricatePassLibrary.temporalAccumulate(
    name = "taa",
    source = FabricatePassChain.CHAIN_INPUT,
    blend = 0.92f,                    // <- this
    resolution = 1024 to 1024
)
```

`blend` is the weight given to the reprojected history when it is valid. At `0f` the pass is a
copy — mode 0 is just the scene. At `0.92f` it is about twelve frames of effective history. Push it
to `0.98f` and the accumulation gets long enough that you can *see* whether reprojection is working:
with it, the image stays sharp while the camera turns; the moment correspondence is wrong, twelve
frames of misalignment pile up into a visible smear.

Then try `rejection`. At `0f` (the default) history is clamped to the raw 3×3 neighbourhood of the
current frame; raise it and the clamp box is dilated, more history survives — and so do more of its
ghosts, most visibly at the silhouette edges of the front row of spheres.

`resolution` **must** equal the chain input's size. A persistent pass has to state its own size, and
if it disagrees with the image it is accumulating, the reprojected UV addresses the right *fraction*
of the wrong picture — which renders, and looks like a soft image rather than like a bug.

## Also worth doing: cut the history

Add this to box 3 or run it from a fifth box:

```kotlin
bundle.invalidateFrameHistory()
```

On mode 3 the frame goes **entirely red for exactly one frame** and then back to green. That is the
escape hatch for everything a frame id and a size cannot see — a camera cut, a scene edit, a light
that moved. A frame id catches a dropped frame; nothing but this catches last frame being a picture
of something else.

## Boxes, and which were run

All four were launched one at a time through the backdoor against this checkout, and each produced
real output — not merely `ok: true`:

| box | output observed |
| --- | --- |
| `rp_renderer` | `renderer ready` |
| `rp_bundle` | `bundle ready: [motion-blur, aa]` |
| `rp_chain` | the `Frames` loop viz — and `addPassChain` compiled both history shaders on the GPU without throwing, which is the box-path proof the GLSL is valid |
| `rp_modes` | `mode 0 - beauty`, then the `Frames` loop viz |

Zero `[error,kotlin]` lines and zero unresolved references across the whole session. Box code is
compiled by the K2 REPL against a different import set from `jvmMain`, so this is not something a
Gradle gate can check — which is why box 2's comment spells out the `prop<>` trap in full.

## Transcripts

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --graphics-context \
  --command 'macro write rp_build --file dev_docs/agents/demos/frame_history_reprojection/build.yaml' \
  --command 'macro run rp_build'
```

The loose `rp_*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies
of the same text.

## Where the measurements are

The numbers — 3.11x fidelity against the same-UV counterfactual, 27.92x noise variance reduction,
0.9922 retained through a yaw, and the metric that lied — are in
[`dev_docs/FOUR_FRAME_HISTORY.md`](../../../FOUR_FRAME_HISTORY.md). The GPU test that produces them
is `tools/run_field_tests.py --id four.reprojection.smoke`.
