# Splat shader

This is a Field version of **splat.html**, the madp classroom demo. In the original you write a
few lines of GLSL in an editor beside a three.js view, and they run once for every Gaussian splat
in a cloud, every frame. Each splat is a small soft ellipsoid with a centre, three radii, a
rotation, a colour and an opacity, and your lines may change any of them. The cloud is redrawn
with your changes at once. The demo comes with six clouds built from code (an icosphere shell, a
fern plume, a nautilus shell, coral branches, a phyllotaxis bloom and a torus braid) and eleven
example inserts. You can also drop in a `.ply` capture, or a `.glb` model shown as one flat disc
per vertex.

What it teaches is shader thinking on data that is not pixels or triangles: one small program
applied to thousands of independent elements, where position, size, colour and transparency are
all just numbers you can drive with noise, distance and time.

## How to play

1. Launch **renderer**. A window opens.
2. Launch this box. The icosphere shell appears and turns slowly.
3. Open this box's **compute** tab. Your lines go between `#include "splat/insert"` and
   `#include "splat/end"`. Write some, for example `rgba.rgb = vec3(1.0, 0.3, 0.1);`, then
   reload the shader (**Reload Compute Shader**, alt-r). A GLSL error is reported against your
   own line number, and the last good shader keeps running.
4. Launch any of the other boxes. Each is one of the demo's examples on a different cloud, and it
   replaces whatever is showing.
5. Use the **controls** panel under a box: *turn* and *size* are the demo's sliders, *splat size*
   scales every splat on screen, *play* pauses `t` and the turn, *t = 0* restarts time, and
   *trail* with *fade* is the demo's trail mode.

What your lines can use:

| name | type | |
| --- | --- | --- |
| `pos` | `vec3` | the splat's centre; change it |
| `scales` | `vec3` | its three radii; change them |
| `rgba` | `vec4` | its colour and opacity; change them |
| `quat` | `vec4` | its rotation, a quaternion `(x, y, z, w)`; change it |
| `index` | `float` | which splat, 0, 1, 2, ... |
| `t` | `float` | seconds since the box ran |
| `pi` | `float` | |

The helpers are `sat(x)`, `rot2(a)`, `hash31(p)` and `noise31(p)`, the same as the demo's.
Afterwards `rgba` is clamped to 0..1 and every scale is kept above 0.0001.

To use another cloud, change `ProceduralSplats.icosphereShell()` in this box's code to
`fernPlume()`, `nautilusShell()`, `coralBranches()`, `phyllotaxisBloom()` or `torusBraid()`. For
your own data, use `SplatSet.fromPly("/path/to/capture.ply").fitted()` for a Gaussian-splat
capture, or `SplatSet.fromGltf("/path/to/model.glb").fitted()` for a mesh. `fitted()` centres the
cloud and scales it to about a unit across, which is the size the examples are written for.

## How it works in Field

The splats are drawn by Field's own Vulkan Gaussian-splat renderer, not by Spark and three.js.
Each box's compute tab is a compute shader: the two includes wrap your lines in a `main()` that
reads one splat, runs them, and writes the result into the buffer the renderer draws from.
`SplatShader` runs that pass every frame. The renderer then sorts the splats back to front and
draws each as a screen-aligned Gaussian, blended over the ones behind it. The picture goes into a
target that keeps a fading copy of the last frame, which is how trail mode works; with the trail
off that target is cleared every frame.

## What is different from the original

- **The sort sees where your lines moved the splats.** Splats blended over each other only look
  right drawn farthest first. Here your lines run before the sort, so a dissolve that throws
  splats outward is sorted where they went. Measured on the GPU (`four.splat-shader.smoke`),
  moving the splats in the vertex shader instead leaves half of all neighbouring pairs in the
  wrong order.
- **Splats are drawn a little smaller.** Spark blends in display colours and Field blends in
  linear light. At the same radius a Field splat's faint edge shows more, so the default
  *splat size* is 0.67 to match the demo's look. Set it to 1 for the splats' true size.
- **The camera is fixed.** There is no mouse orbit: the cloud turns about its vertical axis, and
  the view is the demo's starting one, from (0, 0.38, 2.65) with a 54° lens.
- **Models are handled more carefully.** Disc sizes for a `.glb` are relative to the model's size
  rather than in absolute units, and textures are not sampled, so discs start in the material
  colours.
- **Left out:** the AI completion, the chat panel, local saves and the "copy LLM intro" button.
  Field's own completion, AI chat, documents and history cover those. Spherical-harmonic colour
  is not exposed, as in the demo.
