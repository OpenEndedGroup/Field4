# GPU State — what the probe actually read

Measurements taken while writing `public/docs/gpu_state.md`, moved here so the page can state the
consequence and leave the arithmetic to a reader who wants it. The scene throughout is
`dev_docs/agents/transcripts/gpu_state.yaml`: 40,000 particles, a StackSL force law bound to
`sim.force`, and `fieldProbeHere(samples = 4096)` on the per-particle speed.

## Initialising one ping-pong buffer instead of two

The question is how much damage `listOf(stateA)` does in place of `listOf(stateA, stateB)`. On
frame one the shader reads `prevState`, which half the time is the buffer nobody wrote.

Both variants were replayed against a live renderer and the probe read at matched renderer frames.
Over **109 matched frames** the mean per-particle speed of the two runs agreed to a **median of
0.0003**. The **worst gap was 0.005**, at the earliest frame either run reported — inside the
startup transient, before the spring has pulled anything back to its anchor.

So the two runs are on the same trajectory, and that is a property of *this* scene rather than of
the bug. The restoring force forgets its initial condition within the transient. A pure advected
flow, a cloth with no rest length, or anything accumulating into a history buffer does not forget
it, and frame one's uninitialised bytes stay in the simulation for as long as it runs. The page
states that consequence; these are the numbers behind it.

## The speed card's moving rows

`fieldProbeHere` reports 40,000 calls, all finite, none NaN, over a reservoir of 4,096. Those four
are fixed by the scene. The mean and the exact maximum move with the noise field.

Across **one run's 222 snapshots**:

| row | range over the run | most of the time |
|---|---|---|
| mean per-particle speed | 0.029 to 0.058 | about 0.052 |
| exact maximum speed | 0.091 to 0.187 | about 0.16 |

One run's numbers, on one machine, with the shipped force law. Nothing derives them and nothing
asserts them; they are here to say what order of magnitude to expect before you start turning the
force gain up.

## Open

- Neither measurement has a guard. If the shipped force law in `gpu_state.yaml` changes, both
  sections here go stale silently, and so does the "about 0.05 and 0.16" the page carries.
