# A RectSL animation, as a solid object

What if time became a third dimension? A RectSL animation can be read as a volume whose
cross-sections are its frames. Once you have that volume, you can cut it with Field's Boolean tools
and render it with the path tracer. The scene's signed distance field takes `(x, y, time)`; here we
use time as the third spatial axis.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_rectsl_spacetime/solid.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="spacetime study converged" embed=true hidden=true timeout="420" name="chain-rectsl-solid" alt="A smooth sculpture on a pale floor with the Venice lagoon behind: a twisted ribbon rising from a flat base, joined to a long wedge, shading from warm cream at the near end to cool lavender at the far one" macro-file="../../dev_docs/agents/transcripts/rectsl_spacetime_path_trace.yaml" capture-box="rectsl_spacetime_trace"}
// Built and launched from dev_docs/agents/transcripts/rectsl_spacetime_path_trace.yaml.
```

That is this animation, in full:

```diffstatic {data-lang=rectsl}
#rectsl
->wall 8 8 abstract
->duration 4
->fps 30

"sweep" rect
  0.7 2.4 ->wh
  '( 0=-2.6  4=2.6 ) ->x
  '( 0=0  4=3.14159 ) ->rot

"pulse" rect
  1.2 1.2 ->wh
  1.6 -1.4 ->xy
  '( 0=0.3  4=2.2 ) ->w
```

A bar that crosses the wall while turning through half a circle, and a square that grows. Swept
through time the bar becomes the twisted ribbon and the square becomes the wedge. The colour runs
along the time axis, warm at the first frame and cool at the last, so the render says which end the
animation starts at. Every vertex carries it: `importTriMesh(..., vertexColors = true)` multiplies
`TriMesh.colors` into the material's base colour per corner.

Environment: Venice Sunset by Greg Zaal / Poly Haven (CC0), shared with the
[Fabricate demo assets](assets/fabricate_demo/ATTRIBUTION.md).

## Why the field is sampled onto a grid first

`SceneAdField.frameAt(t)` transforms every rect at one instant onto a fresh autodiff tape, and
`SceneAdFrame.distanceAt(x, y)` is then the scene's signed distance there. That is a
`(x, y, t) -> distance` already, but it is not handed straight to a marcher, for two reasons.

A tape only grows, so it should be reused across a batch of points and not held for a whole march.
A marcher samples in an order of its own choosing, which gives no batch. Sampling slice by slice
uses one tape per time step.

And `Solid.levelSet` calls its field from several threads at once. A grid read back by
interpolation is safe to share that way; an autodiff tape is not.

`sampledField3D` is the bridge: a `FloatArray` of samples, the lattice dimensions, the box they span,
and what comes back is an ordinary `ScalarField3D` that both marchers accept.

```diffstatic {data-lang=kotlin}
val grid = FloatArray(nx * ny * nt)
for (k in 0 until nt) {
    val slice = field.frameAt(program.duration * k / (nt - 1.0))
    for (j in 0 until ny) for (i in 0 until nx) {
        grid[(k * ny + j) * nx + i] = slice.distanceAt(x(i), y(j)).value.toFloat()
    }
}
val spacetime = sampledField3D(grid, nx, ny, nt, bounds)
```

## What happens where the volume meets the window

This box marches the volume with both marchers, with and without padding, and tabulates what each
one returns.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_rectsl_spacetime/readout.png" wait="12" capture="box" open-outputs="true" w="1120" h="340" padding="18" timeout="240" name="chain-rectsl-readout" embed=true alt="A table: one RectSL scene with both rects lowered and zero channel disagreement; then four rows showing marching cubes leaving the unpadded volume open with two boundary loops while the level set closes it, and both closing the padded volume; and one component"}
:::static
import afield.geometry.solid.manifold.Solid
import afield.lang.rectsl.compileRectSL
import afield.lang.rectsl.toAdField

val source =
    """
#rectsl
->wall 8 8 abstract
->duration 4
->fps 30

"sweep" rect
  0.7 2.4 ->wh
  '( 0=-2.6  4=2.6 ) ->x
  '( 0=0  4=3.14159 ) ->rot

"pulse" rect
  1.2 1.2 ->wh
  1.6 -1.4 ->xy
  '( 0=0.3  4=2.2 ) ->w
"""
        .trimIndent()

val program = compileRectSL(source).program
val field = program.toAdField()

:::fold sample the space-time volume, then march it twice, cut and closed
val nx = 104
val ny = 104
val nt = 80
val halfWall = 4.4
val halfDepth = 3.0

val inner = FloatArray(nx * ny * nt)
for (k in 0 until nt) {
    val slice = field.frameAt(program.duration * k / (nt - 1.0))
    for (j in 0 until ny) {
        val y = -halfWall + 2 * halfWall * j / (ny - 1.0)
        for (i in 0 until nx) {
            val x = -halfWall + 2 * halfWall * i / (nx - 1.0)
            inner[(k * ny + j) * nx + i] = slice.distanceAt(x, y).value.toFloat()
        }
    }
}

// The volume as sampled: solid at the first and last frame, so its surface runs into the window.
val cutBounds = boundsOf(vec(-halfWall, -halfWall, -halfDepth), vec(halfWall, halfWall, halfDepth))
val cut = sampledField3D(inner, nx, ny, nt, cutBounds)
val cutMarch = marchingCubes(cut, cutBounds, resolution = 140)
val cutSolid = Solid.levelSetOf(cut, cutBounds, edgeLength = 0.12)

// And with one empty slice each side, a hair outside, so it closes a fraction of a cell beyond.
val dz = 2 * halfDepth / (nt - 1.0)
val padded = FloatArray(nx * ny * (nt + 2))
val slab = nx * ny
java.util.Arrays.fill(padded, 0, slab, 0.25f)
System.arraycopy(inner, 0, padded, slab, inner.size)
java.util.Arrays.fill(padded, slab + inner.size, padded.size, 0.25f)
val bounds = boundsOf(
    vec(-halfWall, -halfWall, -(halfDepth + dz)),
    vec(halfWall, halfWall, halfDepth + dz),
)
val closed = sampledField3D(padded, nx, ny, nt + 2, bounds)
val closedMarch = marchingCubes(closed, bounds, resolution = 140)
val closedSolid = Solid.levelSetOf(closed, bounds, edgeLength = 0.12)
val parts = closedSolid.decompose()

markdown(
    "spacetime",
    """
#### One RectSL scene, ${field.rectCount} of ${program.nodes.size} rects lowered, ${program.duration}s over an ${program.wall.width} x ${program.wall.height} wall

`${inner.size}` samples on ${nt} tapes; `${inner.count { it < 0f }}` of them inside; the worst
disagreement between the autodiff channels and `Fn.eval` was `${field.worstChannelDisagreement}`.

| the volume ends | marcher | triangles | closed | detail |
|---|---|---|---|---|
| at the first and last frame | `marchingCubes` | ${cutMarch.triangleCount} | **${cutMarch.isWatertight()}** | ${cutMarch.diagnose().boundaryLoops.size} boundary loops |
| at the first and last frame | `levelSetOf` | ${cutSolid.triangleCount} | ${cutSolid.status == afield.geometry.solid.manifold.ManifoldStatus.NO_ERROR} | genus ${cutSolid.genus}, volume ${"%.3f".format(cutSolid.volume)} |
| one empty slice beyond each | `marchingCubes` | ${closedMarch.triangleCount} | **${closedMarch.isWatertight()}** | genus ${closedMarch.genus()} |
| one empty slice beyond each | `levelSetOf` | ${closedSolid.triangleCount} | ${closedSolid.status == afield.geometry.solid.manifold.ManifoldStatus.NO_ERROR} | genus ${closedSolid.genus}, volume ${"%.3f".format(closedSolid.volume)} |

`decompose()` finds **${parts.size}** component, because the bar passes over the square at some
frame and two shapes that touch on screen are one solid in space-time.
""",
    width = 1040,
)

parts.forEach { it.close() }
cutSolid.close()
closedSolid.close()
:::endfold
```


The animation is solid at `t = 0` and at `t = duration`, so the volume's surface has nowhere to go at
those two walls. `marchingCubes` leaves it open there, and says so: the mesh is not watertight and
has exactly **two** boundary loops, one per wall. `Solid.levelSetOf` closes it against the box
instead (see the `levelSet` row of the comparison table in [Solids & Booleans](manifold.md)).

Pad the field with one empty slice at each end and both close, which is what the rendered figure
uses. The padding also makes the caps flat: without it the surface snaps to the last sample plane
with a sawtooth wherever neighbouring samples differ, because the jump to `sampledField3D`'s
`outside` value happens in no distance at all. A volume that already ends in empty space needs none of this.

!!! note "`min` is not the union's distance on the inside"

    RectSL's scene distance is the `min` over its visible rects, and the emitted GLSL does the same.
    Outside the union that is exact; inside it under-estimates, because the nearest individual
    boundary can be much closer than the union's own. The zero set is unaffected, which is all a march
    reads, so the surface here is right. An erosion or an offset of this field would not be, and
    [RectSL soft raster](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/RECTSL_SOFT_RASTER.md)
    measures that bias where it matters.

## Where each piece is documented

* [RectSL Examples](rectsl_examples.md) and the [RectSL Cheatsheet](rectsl_cheatsheet.md) for the
  language, the track literals and the array primitives, and
  [RectSL Inverse Animation](rectsl_inverse_animation.md) for fitting a scene's free parameters.
* [Meshes & 3D Geometry](mesh_examples.md) for `marchingCubes`, `sampledField3D` and the `ScalarField3D`
  family.
* [Solids & Booleans (Manifold)](manifold.md) for `levelSetOf`, `decompose` and what closure means.
* [Progressive Path Tracing](path_tracing.md) for the tracer, and
  [A polytope, cut open, and traced](chain_polytope_render.md) for the mesh-to-scene bridge and its
  per-triangle materials.
* [Three-dimensional Life, counted and traced](chain_life3d_render.md) is the neighbouring chain: the
  same two marchers on a different field, and what their genus numbers disagree about.
