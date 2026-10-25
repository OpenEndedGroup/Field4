# Frame history and per-object reprojection

Four boxes show a temporally accumulated grid of 25 lit spheres. The camera moves in Z with bounded yaw; each sphere bobs and drifts independently. This tests depth-dependent and per-object correspondence, as well as camera motion.

Launch **frame history renderer**, **lit scene**, **reprojected accumulation**, then **the four debug modes**. The renderer must remain running while you relaunch a child. `build.yaml` constructs the same code and connections as this saved document; the transcript is `dev_docs/agents/transcripts/frame_history_reprojection.yaml`.

## Change a value

Change a `var` declaration at the top of the accumulation box and Alt-Enter it. Field resets older mutable bindings of the same name and type, so the running loop picks up the new value.

- `historyBlend` is the history weight: `0f` uses the current frame, `0.92f` gives roughly twelve frames of history, and `0.98f` makes correspondence errors easier to see. Keep it below `1f`.
- `motionGain` controls the debug view's display gain. Large gains clip its red/green channels.
- Yaw, dolly, bob and drift each have an amplitude and speed. Angles are radians and distances are world units. Motion time advances by one sixtieth per Field frame; speeds use these simulation seconds.

The blend and display gain are bound to their variables, so declaration edits reach the shader. The motion loop reads its controls each frame. The native bundle is kept by `once`, while relaunching the accumulation box replaces the named passes and resets motion/history deliberately.

## Read the modes

The final box advances every 150 Field frames:

| Mode | What to inspect |
| --- | --- |
| 0 · beauty | Moving spheres should remain sharp. Ghosts behind them indicate bad correspondence or excessive retained history. |
| 1 · backward motion | Motion is measured from current pixels to their previous locations. Dolly and independently moving balls create local structure; zero motion is neutral grey. |
| 2 · previous UV | Absolute previous coordinates form a colour ramp, interrupted at moving silhouettes. This exposes offsets and swapped axes. |
| 3 · availability | Green means valid, in-bounds history; red means invalid history or an off-screen previous UV. A declared cut invalidates its first frame. This view does not detect depth disocclusion. |

To hold one mode, stop the final box and run this in the accumulation box:

```kotlin
chain.setPush("display", "mode", 1)
```

Both temporal passes use per-pixel motion. The standard renderer supplies previous object transforms; the camera pose is derived from one base eye/target every frame. Translating the eye translates the target too, and yaw rotates the original direction, so neither translation nor repeated launches accumulate an unintended rotation.

## Relaunch and cut

A repeated chain declaration keeps its upstream scene input and reuses compatible persistent targets. It must not sample its own previous display output. The accumulation box invalidates frame history when it resets its camera and objects.

For another deliberate camera or scene cut:

```kotlin
bundle.invalidateFrameHistory()
```

The two temporal passes use 1024×1024 targets, matching the bundle. Change both declarations together if you change that resolution.
