# The world layer — a document you can open

Colliders, tearing and wind for `SimdVerlet`. Three boxes, one per thing a cloth could not do
before: drape over something, come apart, and fly.

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the boxes. No GPU, no window, no sidecar: the physics is CPU `SimdVerlet`,
`SimdVerlet.debugLines()` returns `FLine`s, and `showPlane` puts them out through Skia SVG, so
these run headless and in a live Field the same way. Each box holds an explicit `loop { }` — press
run and it keeps going until you stop it.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — a cloth draping over a sphere** | a 22 x 22 sheet let go above a sphere, with a floor under it | the sheet wraps the amber circle and spreads into a skirt, and **nothing gets inside it** — a projection collider's deepest penetration is 0.00000 at any spring setting the cloth itself survives |
| **2 — a sphere tearing through a pinned sheet** | the sheet is pinned all round, the sphere travels toward you through it | the grid stretches radially, the crack starts at the contact ring, and the hole *stays* open — because the tear hook also drops the **curvature** stencil at both lips, without which the sheet heals in the picture while the solver says it is torn |
| **3 — a flag in gusty wind, seen from above** | per-quad aerodynamics plus a seeded gust field | it *flaps*. Comment out `cloth.addWind(wind)` and the same wind, same gusts, same seed leaves it leaning instead — per-particle drag cannot tell which way the fabric is facing |

Box 3 is the one to touch: the difference between the two lines of wind setup is the difference
between a flag and a sheet in treacle, and it is one comment character away.

Three knobs worth turning:

- box 1, `friction = 0f` on the sphere — the sheet slides off its shoulder instead of gripping.
  Measured over 600 frames, a 10 x 10 sheet's centroid ends at 0.789 with `friction = 0.9` and at
  -32.2 with `0f`.
- box 2, `threshold` between 0.15 and 0.4 — the difference between a sheet that shreds and one the
  sphere merely dents. `maxBreaksPerStep` is a *different* rip rather than a slower one: a sheet
  failing a few edges at a time goes on carrying load while it fails, so more of it reaches the
  threshold (14 edges unthrottled against 22 at one per step, on the test's sheet).
- box 3, `turbulence` and `seed` — same seed, same weather, forever. A whole 400-frame run is
  bit-identical to itself and differs from the next seed in 262 of 288 coordinates.

## Replaying it

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into
a fresh Field, run all three boxes for twenty seconds and stop them, with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write simdverlet_world --file dev_docs/agents/demos/simdverlet_world/build.yaml' \
  --command 'macro run simdverlet_world' \
  --command 'launch world_drape --property code' \
  --command 'launch world_tear --property code' \
  --command 'launch world_wind --property code' \
  --command 'sleep 20' \
  --command 'stop world_drape' --command 'stop world_tear' --command 'stop world_wind'
```

Or open the saved document instead of rebuilding it:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --workspace dev_docs/agents/demos \
  --command 'open simdverlet_world' \
  --command 'launch world_tear --property code' --command 'sleep 15' --command 'stop world_tear'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

The written-up version, with the measurements and the design argument behind the projection, is
[`dev_docs/physics/SIMDVERLET_5_WORLD.md`](../../../physics/SIMDVERLET_5_WORLD.md). The cloth these
three add a world to is `cloth_simdverlet_start` in the transcript corpus.
