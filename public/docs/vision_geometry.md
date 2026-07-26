# Building geometry in the headset

!!! warning "Written for the visionOS app"
    Everything here runs inside Field4Vision's **Live Metal** head on an Apple Vision Pro — that is
    where the shader, the uniforms and the finished image live. See [Field on Apple Vision
    Pro](vision.md) for the app itself.

    The **geometry** is the exception: [Preview Vision Geometry](#previewing-it-on-your-desk) builds
    your mesh on your Mac and shows it to you, headset or no headset. Every picture on this page was
    made that way.

A Live Metal program has three parts, and you write each one somewhere different:

| Part | Where |
|---|---|
| **shader** | the box's `msl` property — Metal Shading Language, both entry points |
| **geometry** | `geometry { … }` in the box's Kotlin, which runs *in the headset* |
| **uniforms** | the `uniforms=` list on the box's `// {{ visionmetal }}` marker |

This page is about the middle one.

## Why the geometry runs in the headset

The obvious design would be to build a mesh on your Mac and send it to the Vision Pro. That works for
a mesh you build once, and Field can do it — but it puts a network hop between your code and the
image, which is fatal for anything that changes shape.

So the Kotlin in a `// {{ visionmetal }}` box doesn't run on your Mac at all. It compiles to
JavaScript and runs in a small JavaScriptCore context **on the headset's render thread**.
`geometry { … }` assembles vertices there and hands the finished arrays to Metal in a single call. The
network carries your *program*, once; it never carries vertices at frame rate.

That's what makes rebuilding a mesh every frame reasonable rather than absurd.

## The smallest complete example

```diffstatic {data-lang=kotlin}
:::static
// {{ visionmetal uniforms=phase }}
onStart {
    geometry {
        color(1.0, 0.35, 0.15)
        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
    }
    onFrame { frame -> uniform("phase", frame.time * 3.0) }
}
```

Positions are in metres, in the space you launched the immersive scene in: `-z` is in front of you,
`+y` is up. So `cz = -1.2` is 1.2m ahead, and `0.18` is a cube about the size of a grapefruit.

The matching shader goes in the box's `msl` property. It reads `u_phase` — the head generates
`#define u_phase frame.user[0]` from the marker's `uniforms=` list, so shaders name their uniforms
instead of counting slots.

## Vertices and indices

Underneath the shape helpers is a plain indexed-triangle builder.

`vertex(x, y, z)` appends a vertex **and returns its index**. It takes the builder's current
`normal()`, `color()`, `uv()` and `extra()` state, so you set those once and append many vertices:

```diffstatic {data-lang=kotlin}
:::static
geometry {
    normal(0.0, 0.0, 1.0)
    color(0.9, 0.3, 0.2)

    val a = vertex(-0.1, -0.1, -1.0)
    val b = vertex(0.1, -0.1, -1.0)
    val c = vertex(0.1, 0.1, -1.0)
    val d = vertex(-0.1, 0.1, -1.0)

    quad(a, b, c, d)          // == triangle(a, b, c) + triangle(a, c, d)
}
```

Winding is **counter-clockwise as seen from the front**; the head culls back faces, so a face wound
the other way is invisible. If a shape appears to have holes in it, that's the first thing to check —
and [the preview](#winding-and-why-a-mesh-can-be-invisible) will check it for you.

Because faces reference vertices by index, you choose your own normals:

- **Share** vertices between faces and the normals interpolate — a smooth surface.
- **Duplicate** them per face, each with its own normal, and the edges stay crisp — facet shading.
  This is what `cube(tintFaces = …)` does, which is why its six sides read as flat.

`vertexCount` and `indexCount` are readable as you go, which is the easiest way to sanity-check a
generator:

```diffstatic {data-lang=kotlin}
:::static
log("built $vertexCount vertices, ${indexCount / 3} triangles")
```

## Per-vertex channels

Each vertex carries four `float4`s, and the shader sees them as `FieldVertex`:

| Builder call | MSL | Notes |
|---|---|---|
| `vertex(x, y, z)` | `v.position` | `.w` is 1, supplied for you |
| `normal(x, y, z)` | `v.normal` | `.w` is 0 |
| `color(r, g, b, a)` | `v.color` | linear RGBA |
| `uv(u, v)` | `v.uv.xy` | texture coordinate |
| `extra(first, second)` | `v.uv.zw` | two free channels — anything you like |

`extra()` is the escape hatch. There is no way to add a *new* attribute to the vertex layout from a
box, so when you need to get one more number per vertex into the shader — a band index, an age, a
random seed — put it there.

## The shape helpers

These append to whatever is already in the builder, so you can combine them freely in one mesh.

```diffstatic {data-lang=kotlin}
:::static
geometry {
    color(1.0, 0.42, 0.18)
    cube(0.11, cx = -0.42, cz = -1.2, tintFaces = true)

    color(0.25, 0.75, 1.0)
    sphere(0.13, segments = 32, rings = 20, cx = -0.14, cz = -1.2)

    color(0.95, 0.85, 0.25)
    plane(0.26, 0.26, cols = 8, rows = 8, cx = 0.14, cz = -1.2)
}
```

- `cube(half, cx, cy, cz, tintFaces)` — an axis-aligned cube of edge `2 * half`. Facet normals, one
  set of four vertices per face. `tintFaces` colours each face from its normal, which is a cheap way
  to tell orientation while you're getting a shader working.
- `plane(width, height, cols, rows, cx, cy, cz)` — a quad lattice on the XY plane facing `+z`, with
  uv spanning the whole sheet. Raise `cols`/`rows` when a vertex shader needs something to deform.
- `sphere(radius, segments, rings, cx, cy, cz)` — a UV sphere with smooth (shared) normals.
- `tube(points, radius, sides)` — see below.

## Tubes: a line with real thickness

`tube` sweeps a polygon along a polyline. It's the same treatment Field's native FLine viewer gives a
stroke, and the reason a curve drawn in Kotlin looks like an object in the headset rather than a
hairline:

```diffstatic {data-lang=kotlin}
:::static
geometry {
    color(0.62, 0.42, 1.0)
    tube(
        (0..48).map { i ->
            val t = i / 48.0
            val a = t * 6.2831853
            p(0.42 + 0.055 * sin(a), -0.13 + t * 0.26, -1.2 + 0.055 * cos(a))
        },
        radius = 0.017,
        sides = 10,
    )
}
```

`p(x, y, z)` builds a point. `sides` is the cross-section's polygon count — 6 to 10 is plenty at arm's
length. uv runs *around* the tube in `u` and *along the spine* in `v`, so a shader can put a
travelling band on it with `in.uv.y`. Fewer than two points draws nothing rather than erroring.

## Rebuilding

Call `geometry { … }` again whenever you like — including from `onFrame`:

```diffstatic {data-lang=kotlin}
:::static
onFrame { frame ->
    geometry {
        color(0.2, 0.9, 0.8)
        tube(spineAt(frame.time), radius = 0.012, sides = 8)
    }
}
```

The swap happens at a frame boundary, so a rebuild never tears — you will not catch a half-built
mesh on screen.

Two things worth knowing about when to do this:

1. **If the motion is a transform, do it in the shader instead.** Rotating, scaling, or displacing
   along a normal costs one uniform and no rebuild. Reach for a rebuild when the *spine or the
   topology* changes, which the shader can't do for you.
2. **Rebuild cost scales with the mesh.** A few hundred to a couple of thousand vertices per frame is
   comfortable. For tens of thousands, rebuild on demand and let the shader animate.

An empty builder is a legitimate way to draw nothing:

```diffstatic {data-lang=kotlin}
:::static
geometry { }   // clears the mesh
```

Note the flip side of geometry being explicit: there is **no** fallback to a built-in cube. A box that
never calls `geometry { … }` draws nothing. The head says so once rather than leaving you puzzled, and
the **Vision Geometry: Built-in Cube** command drops in a known-good mesh when you want to work out
whether the problem is your shader or your geometry. **Preview Vision Geometry** comes at that same
question from the other side — see [below](#previewing-it-on-your-desk).

## A procedural mesh, start to finish

Height fields are the shape most procedural geometry takes, so here it is in full — including normals
by finite difference, which saves differentiating your surface by hand:

```diffstatic {data-lang=kotlin}
:::static
// {{ visionmetal uniforms=spin,light }}
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

onStart {
    geometry {
        val n = 48
        val size = 0.52
        val centreZ = -1.2

        fun height(x: Double, z: Double): Double {
            val r = sqrt(x * x + z * z)
            return 0.055 * cos(r * 38.0) * exp(-r * 5.5)
        }

        val step = size / n
        val eps = step * 0.5
        val base = vertexCount

        for (row in 0..n) {
            for (col in 0..n) {
                val x = (col.toDouble() / n - 0.5) * size
                val z = (row.toDouble() / n - 0.5) * size
                val h = height(x, z)

                // Central differences give the two tangents; their cross product is the normal.
                val dx = (height(x + eps, z) - height(x - eps, z)) / (2.0 * eps)
                val dz = (height(x, z + eps) - height(x, z - eps)) / (2.0 * eps)
                val len = sqrt(dx * dx + dz * dz + 1.0)
                normal(-dx / len, 1.0 / len, -dz / len)

                val t = (h / 0.055 + 1.0) * 0.5
                color(0.15 + 0.75 * t, 0.35 + 0.35 * t, 0.95 - 0.35 * t)

                uv(col.toDouble() / n, row.toDouble() / n)
                vertex(x, h, centreZ + z)
            }
        }

        // This sheet lies in XZ with its normal on +y, so the corners have to go round this way.
        // The other diagonal winds it face-down and the head culls the whole landscape away.
        val stride = n + 1
        for (row in 0 until n) {
            for (col in 0 until n) {
                val a = base + row * stride + col
                quad(a, a + stride, a + stride + 1, a + 1)
            }
        }
    }

    uniform("light", 0.35, 0.9, 0.35)
    onFrame { frame -> uniform("spin", frame.time * 0.25) }
}
```

Note the `import kotlin.math.…` lines above the `onStart`. `kotlin.math` is not a default import, and
imports go at the top of the box, under the marker.

![The ripple height field: a central peak, a trough ring and a decaying wave, shaded by
height](assets/vision_geometry/vision-geometry-heightfield.png)

That picture was made on a Mac, from this exact code. Which brings us to:

## Previewing it on your desk

`geometry { … }` runs in the headset, and for a while that meant you could only *see* it in the
headset. The **Preview Vision Geometry** command closes that: it takes the box's Kotlin, compiles it
to JavaScript with the same compiler a live box uses, runs it against a mirror of the head's
JavaScriptCore bridge, and rasterizes the mesh that comes out — into the box's own output.

!!! warning "It previews your geometry, not your shader"
    The mesh is the real one: the packed vertex array your headset would have received, built by the
    same builder, byte for byte. The **shading is a stand-in** — a fixed two-light lambert over your
    vertex colours. Your MSL is not compiled, translated or simulated here, and every preview says so
    in its caption. This answers *"is my geometry the shape I meant?"*; **Vision Geometry: Built-in
    Cube** answers the other half on the device.

![Cube, sphere, plane and swept tube in one mesh, previewed side by side on a
desktop](assets/vision_geometry/vision-geometry-shapes.png)

From Kotlin, the same thing is one call:

```diffstatic {data-lang=kotlin}
:::static
val capture = previewVisionGeometry(source = myLiveMetalProgram)
val mesh = capture!!.mesh()
println("built " + mesh.vertexCount + " vertices, " + mesh.triangleCount + " triangles")
```

`capture.meshes` holds *every* upload in order, so a box that rebuilds in `onFrame` gives you one mesh
per tick and you can watch the shape move:

![Four frames of a helix whose winding count and radius drift, rebuilt from scratch each
frame](assets/vision_geometry/vision-geometry-animated-filmstrip.png)

### Winding, and why a mesh can be invisible

The preview culls back faces because the head does. That reproduces the most common way a mesh goes
wrong: a face wound the other way is simply not there.

Worse, it can be *convincingly* not there. A closed shape wound inside-out still draws a plausible
solid — you are looking at the inside of its far side. So the preview also measures the mesh against
itself: for every triangle it compares the winding's implied direction with the `normal()` you
declared, and reports the fraction that agree. A clean mesh reads 100%. Anything less will have holes
in it on the device, and the number is printed on the frame.

![A ring of quads wound backwards, drawn with culling disabled, its stats line reading "winding 0%
agrees with normals" in amber](assets/vision_geometry/vision-geometry-lantern-inverted.png)

That check is not hypothetical: it is what found `sphere()` and `tube()` wound the opposite way round
from `cube()` and `plane()`, and the height field above facing downwards. All three are fixed, and the
gallery fails the build if they come back.

### Uniforms that don't line up

The other thing the preview checks needs no picture at all. A uniform is named in three places — the
marker's `uniforms=` list, the shader's `u_NAME`, and the script's `uniform("name", …)` — and each
mismatch fails differently and late:

| Mismatch | What the head does |
|---|---|
| shader reads `u_NAME`, marker doesn't declare it | no `#define` is emitted; **Metal fails to compile your shader** |
| script writes a name the marker doesn't declare | there is no slot; the write is dropped and the shader reads zero |
| marker declares a name nothing uses | a wasted slot — reported, but harmless |
| shader has no `field_vertex` / `field_fragment` | the head refuses to build a pipeline |

`VisionMetalProgramCheck` decides all of those from the box's own text plus a real run of its script,
and **Preview Vision Geometry** reports them alongside the mesh. It does not compile MSL — that still
happens on the device — but the mistakes above never reach the compiler in the first place.

### A turntable, and the mesh as FLines

`VisionGeometryPreview.renderContactSheet` draws the same mesh from several angles at once, which is
the quickest way to be sure a shape is a solid and not a lucky projection:

![The same cube from yaw 0, 90, 180 and 270 degrees, each face a different
tint](assets/vision_geometry/vision-geometry-cube-turntable.png)

And if you would rather measure the mesh than look at it, `CapturedMesh.toFLines()` hands its unique
edges — in metres, with vertex colour — to Field's own 3-D viewer, where the rest of Field's drawing
and geometry tools apply.

## Runnable starting points

Each of these is a complete document — geometry, shader and uniforms — you can open and edit. They are
also the examples this page is drawn from, and CI compiles every one of them through the real
Kotlin→JavaScript pipeline, so none of the code above is aspirational.

- **visionos_start** — the smallest complete program: a cube, a shader, one animated uniform.
  [Open ↗](/offline.html?file=/field4/assets/agent_transcripts/visionos_start/metadata.edn)
- **vision_geometry_shapes** — cube, sphere, plane and tube side by side, with a shader that reads the
  uv the shapes write.
  [Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_shapes/metadata.edn)
- **vision_geometry_heightfield** — the procedural mesh above, in full.
  [Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_heightfield/metadata.edn)
- **vision_geometry_animated** — a swept tube rebuilt from scratch every frame.
  [Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_animated/metadata.edn)
- **vision_geometry_desk_preview** — the preview above, without a headset: one box builds a lantern
  and draws it in its own output, the next builds the same ring wound backwards so you can see what
  the winding check catches.
  [Open ↗](/offline.html?file=/field4/assets/agent_transcripts/vision_geometry_desk_preview/metadata.edn)

## Further reading

- [Field on Apple Vision Pro](vision.md) — the app, and the other native surfaces.
- [Kotlin in the Head](kotlin_in_the_head.md) — how a `// {{ … }}` box compiles and runs remotely.
- [Agent Transcript Gallery](agent_transcripts.md) — the whole starting-point corpus.
