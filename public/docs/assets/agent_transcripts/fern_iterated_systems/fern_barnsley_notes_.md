# Fern: iterated systems on the GPU

This document is Field's version of **fern**, a classroom demo of iterated systems
(`fern.html` in the madp demos). A *system* is a rule for moving a point. Thousands of points
follow the rule at once, and every point is plotted after every step into a picture that fades a
little each frame. A single point tells you nothing, but together their visits build up, like
dust, into the shape the rule keeps returning to.

The demo compares three kinds of rule:

- **an IFS played as the chaos game** (this box, the Barnsley fern): a few affine maps, one
  picked at random each step. The fern is the one shape the four maps together send onto itself.
- **a continuous chaotic flow** (*Lorenz attractor*): an ODE stepped by `dt`, where nearby
  trajectories never settle and never repeat, yet all of them trace the same two lobes.
- **a nonlinear IFS** (*flame-style IFS*): an affine map followed by a variation (swirl,
  handkerchief, spherical). This is the step from an IFS to a fractal flame.

The *Sierpinski triangle* box is a fourth, the smallest chaos game there is.

## How to play

- Launch a system box to show it in the **Fern** window. Launching another replaces it, as the
  demo's preset menu does.
- The **controls** panel is the demo's toolbar. Low **fps** shows the shape assembling at
  random; low **fade** keeps the whole history; **steps per frame** plots several steps of each
  particle per frame; **exposure** brightens or darkens the dust; **reset** starts every particle
  again.
- The rule is in the box's **compute** tab. Change a coefficient or a weight, and the shader
  reloads. Try moving the stem map's `0.16`, or giving the third map more weight.
- The rest of the system is in the **code** tab: `viewport`, `background`, `particles`, `fade`,
  `fps`.

## From the demo to Field

The demo's `system` object becomes two tabs. The six functions keep their names and their order,
but they are GLSL now, running once per particle in a Vulkan compute shader:
`initParticle`, `step`, `project`, `shouldDraw`, `color`, `pointSize`. The first line,
`#include "points/system"`, provides the rest: the `State` struct (`pos`, `age`, and `data` for a
system's own fields), a seeded `random()`, CSS-style `rgba(...)` and `hsla(...)`, `affine(...)`
written as an IFS table, the flame variations, and the loop that steps, plots and respawns.
The settings (`viewport`, `background`, `particles`, `steps`, `fade`, `fps`, `dt`) are
`IteratedSystem` settings in the code tab. The points pile up in a `PointAccumulation`: a float
image that keeps `1 - fade` of itself every frame and adds each point's light.

## What is different

- **Many more particles.** The demo runs 7 000 points on the CPU; here the fern has 200 000 and
  the flame 250 000. Lorenz has 12 000, because above that its trajectories merge into a smooth
  sheet and the spirals disappear.
- **Light, not paint.** Points add light in a floating-point image and a tone map shows the
  result, instead of 8-bit canvas pixels painted over each other. The flame uses the `LOG` tone
  map, as fractal flames do, so sparse threads and dense knots show together.
- **The viewport keeps its shape.** The canvas version stretches to fill its pane.
- `age` goes up by one after each step without `step` having to do it, and `State` is fixed at two
  `vec4`s where the JS object could hold any fields.
- Not carried over: the in-page editor, AI completion, local saves, fullscreen and the metrics
  card.
