# Shafts of light through haze — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>`).

**Launch box 1, "path tracing renderer", then box 2, "shafts of light through haze".**
A window opens on a bright strip light with four slats under it, a floor, a back wall
and a sphere. Everything is extruded along z and the camera looks down z, so the
lamp's fan is seen in cross-section.

## The one parameter to move

```kotlin
tracer.mediumDensity = 0.16f   // extinction per world unit
```

Box 2 cycles it through `0.0`, `0.08`, `0.16`, `0.40` every 192 samples and prints the
value, so you can watch it without editing anything. Comment out the `frames { }` loop
at the bottom to hold one setting, or just assign a number and re-launch.

**What should happen as you raise it.** At `0.0` you get the room: hard-edged pools of
light on the floor between the slats' shadows, and *nothing in the air*. As it rises,
shafts appear between the slats — bright wedges of lit haze, with dark wedges between
them where a slat is in the way. The lamp gains a halo. The far wall goes soft, and the
sphere's own shadow becomes a visible cone rather than a patch on the floor. Past about
`0.4` the haze starts to swallow the geometry, and the image also gets *darker*: every
collision spends one of `maxBounces`, so a dense medium runs out of path length. Raise
`tracer.maxBounces` (the cap is twelve) and it comes back.

Then try the second knob:

```kotlin
tracer.mediumAnisotropy = 0.4f   // Henyey-Greenstein g, in (-1, 1)
```

`0.8` pulls the glow into a tight forward halo hugging the lamp; `-0.8` throws it back
toward the camera, brightening the near haze and flattening the shafts. The total
energy does not change — only where it goes.

## Why the slats are the point

The shafts are the one image `fogDensity` cannot produce. `fogDensity` is a function of
the *primary hit distance*, so it is constant along an iso-distance surface and has no
way to know a slat is between this point and the lamp. A participating medium samples a
collision in mid-air and runs that collision's own shadow ray, so the collisions behind
a slat come back dark.

The harness measures that, and the statistic it uses took two tries. Lateral variation
along a row of hazed pixels does *not* work — it comes out highest for the render with no
medium at all, because hard-edged pools of light against black air have more contrast than
haze does, and both of the other renders still carry the slats' shadows on the wall and
floor. Shadows on *surfaces* are not what is at issue. Light where there is **no surface**
is: above the wall's top edge the primary ray hits nothing, and `depthFog` maps every one
of those pixels to exactly `fogColor` because a miss has no depth to curve. Over the
100921 such pixels of a 640×400 frame, the medium's spatial standard deviation is 2.02
against `fogDensity`'s 0.0000015, which is float arithmetic on a constant.

| in the scene | why it is there |
| --- | --- |
| the **strip light**, thin in x and long in z | the fan is then confined to the plane the camera is looking at, so the shafts are seen edge-on rather than end-on |
| the **four slats** | the shadows they throw *into the air* are what makes this a medium rather than a depth curve |
| the **bounds** on the medium, set to the room | an unbounded medium has zero transmittance to infinity, so an environment map would be unreachable and the scene would render black. This one stops at the walls |
| the **sphere** | its volumetric shadow is a cone, not a footprint, and it is the easiest thing in the frame to compare against the no-medium render |
| the **black environment** | there is nowhere else for light to come from, so nothing here is being carried by the environment sampler |

## Transcripts

`build.yaml` beside this file is the `OperationTranscript` that built the document.
Replay it into a fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write path_tracing_medium_build --file dev_docs/agents/demos/path_tracing_medium/build.yaml' \
  --command 'macro run path_tracing_medium_build'
```

`verify.yaml` is the other transcript: it drives `PathTraceMediumGpuSmoke` over the same
mechanism and prints the closed-form, energy-conservation and variance measurements
instead of showing a picture. Its invocation, and what every number means, are in
[`dev_docs/PARTICIPATING_MEDIA.md`](../../../PARTICIPATING_MEDIA.md).

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable
inline copies of the same text, and `./gradlew checkAgentDemos` fails if the two ever
disagree.

The published behaviour is under "A participating medium" on
[`public/docs/path_tracing.md`](../../../../public/docs/path_tracing.md).
