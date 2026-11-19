# Shapes from constraints: Ceres in Field

Describe a shape by what has to be true of it: these four edges are the same length, this face is
flat, these lines all meet at one point. Field hands that description to
[Ceres Solver](https://ceres-solver.org/), which moves the points until every statement holds, or
gets as close as it can. A solve is fast enough to repeat each time the pointer moves, so you can
grab a corner in a [`three` viewer](three_output.md) and watch the rest of the shape follow.

!!! note "Ceres is an optional native library"
    Build it once with `./gradlew buildCeresRedistributable`. Field finds the result by itself.
    A solve without it stops with an error that names the same command.

## A first model

A model has two parts. You declare named points with their starting positions, then you state
constraints between them inside `constraints { }`. Here four points start as a rough, bent
quadrilateral. The constraints ask for four sides of length 1, two diagonals of the same length,
and a flat panel, which together mean a square:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ceres/square.png" alt="A three.js view of two outlines: a bent grey quadrilateral, and an orange square of side 1 that the solver found near it. A legend in the top-left corner lists the four sides at 1.0000 and both diagonals at 1.4142" wait="4" capture="box" open-outputs="true" padding="20" name="ceres-square" requires="ceres" embed=true}
import afield.math.optimization.ceres.PointRef
import afield.math.optimization.ceres.geometryModel

// Four points that start as a rough, bent quadrilateral.
val start = [vec(0.0, 0.0, 0.0), vec(1.3, 0.1, 0.3), vec(1.1, 0.9, -0.4), vec(-0.2, 1.2, 0.2)]

val p = mutableListOf<PointRef>()
val model = geometryModel {
    start.forEachIndexed { i, s -> p += point("p$i", s.x.toDouble(), s.y.toDouble(), s.z.toDouble()) }
    val quad = panel(p[0], p[1], p[2], p[3])
    constraints {
        for (i in 0 until 4) line(p[i], p[(i + 1) % 4]).length(1.0) // four equal sides
        line(p[0], p[2]).sameLengthAs(line(p[1], p[3])) // equal diagonals
        quad.planar() // and flat
    }
}

val solved =
    model.openSession().use { session ->
        session.solve(maxIterations = 50)
        p.map { session.get(it).let { v -> vec(v.x, v.y, v.z) } }
    }

fun outline(at: List<Vec3>, c: Vec4) = fline {
    moveTo(at[0].x, at[0].y, at[0].z)
    for (v in at.drop(1)) lineTo(v.x, v.y, v.z)
    lineTo(at[0].x, at[0].y, at[0].z)
    color = c
}

three(name = "square", height = 420) {
    lines([outline(start, vec(0.6, 0.6, 0.6, 1.0))], id = "start", label = "start")
    lines([outline(solved, vec(1.0, 0.55, 0.2, 1.0))], id = "solved", label = "solved")
    val sides = (0 until 4).map { solved[it].distance(solved[(it + 1) % 4]) }
    markdown(
        """
        **A rough quad, solved into a square**

        - sides ${sides.joinToString { it.f4 }}
        - diagonals ${solved[0].distance(solved[2]).f4} and ${solved[1].distance(solved[3]).f4}""".trimIndent(),
        corner = "top-left",
    )
    camera(yaw = 20.0, pitch = 25.0)
    fitTo(vec(-0.2, -0.1, -0.4), vec(1.3, 1.55, 0.4)) // headroom for the legend
}
```

`openSession` compiles the model into a native problem once. `solve` runs Ceres from wherever the
points are now, and `get` reads a point back. The session holds native memory, so this box opens it
with `use`, which closes it when the block ends.

Nothing pinned the square, so it was free to settle anywhere. It stays near the grey start because
every point carries a weak pull toward where it began, and that pull decides between the many squares
that would satisfy the constraints equally well. The pull is a *prior*, and dragging works by
changing it.

## Drag a corner, solve again

A session that stays open can solve again whenever something changes. This box keeps one, and puts
a [drag handle](three_output.md#drag-handles) on each corner that is free to move. The model is a
cube with one kind of constraint: each of its six faces must stay flat. There are no lengths and no
right angles. The top four corners start knocked out of place, so no face begins flat, and the first
`solve` flattens them all:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ceres/flat_cube.png" alt="A three.js view of an irregular six-sided solid with translucent grey faces and edges coloured red, green and blue by their starting axis. Its four top corners carry yellow handles labelled c2, c3, c6 and c7; the top is tilted and the side faces are uneven quadrilaterals. A legend reads: six flat faces, nothing else; worst face bend 5.8e-07; edges from 0.67 to 1.40" wait="4" capture="box" open-outputs="true" padding="20" name="ceres-flat-cube" requires="ceres" embed=true}
import afield.graphics.three.ThreeHandleDrag
import afield.math.optimization.ceres.DragHandle
import afield.math.optimization.ceres.InteractionPolicy
import afield.math.optimization.ceres.PointRef
import afield.math.optimization.ceres.RobustLoss
import afield.math.optimization.ceres.geometryModel
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import kotlin.math.abs
import kotlin.math.sqrt

/*
Eight corners and six faces that must stay flat, and nothing else: no lengths, no right
angles. The bottom face is pinned; drag a top corner and Ceres finds the nearest shape whose
faces are all still flat.
*/

val flatWeight = 1e4

// Corner i sits at the bits of i: x = bit 0, y = bit 1, z = bit 2. y is up in the viewer.
// The top four start knocked about, so no face begins flat.
val knock = [
    [0.0, 0.0, 0.0],
    [0.0, 0.0, 0.0],
    [-0.25, 0.3, 0.1],
    [0.2, -0.15, 0.3],
    [0.0, 0.0, 0.0],
    [0.0, 0.0, 0.0],
    [0.3, 0.2, -0.2],
    [-0.1, 0.35, -0.15],
]
val corners =
    (0 until 8).map { i -> [(i and 1) + knock[i][0], ((i shr 1) and 1) + knock[i][1], ((i shr 2) and 1) + knock[i][2]] }
val edges = (0 until 8).flatMap { a -> [1, 2, 4].filter { (a and it) == 0 }.map { bit -> a to (a or bit) } }
// Each face's corners in perimeter order: one axis held at 0 or 1, walking round the other two.
val faces =
    [1, 2, 4].flatMap { k ->
        val (u, v) = [1, 2, 4].filter { it != k }
        [0, k].map { s -> [s, s or u, s or u or v, s or v] }
    }
val pinned = [0, 1, 4, 5] // the y = 0 face

// Edges coloured by the axis they started along.
val axisName = mapOf(1 to "x", 2 to "y", 4 to "z")
val axisColor = mapOf(1 to vec(0.95, 0.35, 0.3, 1.0), 2 to vec(0.4, 0.85, 0.4, 1.0), 4 to vec(0.35, 0.6, 1.0, 1.0))

val p = mutableListOf<PointRef>()
val model = geometryModel {
    robustLoss(RobustLoss.NONE) // plain least squares, so every face can reach exactly flat
    corners.forEachIndexed { i, c -> p += point("c$i", c[0], c[1], c[2]) }
    val panels = faces.map { f -> panel(p[f[0]], p[f[1]], p[f[2]], p[f[3]]) }
    constraints {
        for (face in panels) face.planar(weight = flatWeight)
    }
}

// The session owns native memory; the next run of this box closes it.
val session =
    model.openSession(policy = InteractionPolicy(preserveWeight = 1e-3, dragIterations = 12, settleIterations = 60))
RunScope.current()?.own(session, ReleasePolicy.ACCUMULATING)
for (i in pinned) session.pin(p[i])
session.solve(maxIterations = 60) // flatten the knocked-about start

var held: DragHandle? = null

fun corner(i: Int) = session.get(p[i]).let { vec(it.x, it.y, it.z) }

/** How far a face's fourth corner sits off the plane through its first three. */
fun bend(at: List<Vec3>, f: List<Int>): Double {
    val (a, b, c, d) = f.map { at[it] }
    val ux = (b.x - a.x).toDouble()
    val uy = (b.y - a.y).toDouble()
    val uz = (b.z - a.z).toDouble()
    val vx = (c.x - a.x).toDouble()
    val vy = (c.y - a.y).toDouble()
    val vz = (c.z - a.z).toDouble()
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    return abs(nx * (d.x - a.x) + ny * (d.y - a.y) + nz * (d.z - a.z)) / sqrt(nx * nx + ny * ny + nz * nz)
}

fun draw() {
    val at = (0 until 8).map(::corner)
    val worstBend = faces.maxOf { bend(at, it) }
    val longest = edges.maxOf { (a, b) -> at[a].distance(at[b]) }
    val shortest = edges.minOf { (a, b) -> at[a].distance(at[b]) }

    three(name = "cube", height = 560) {
        // The faces, as polygons in their own layer.
        for ((n, f) in faces.withIndex()) {
            val quad = triMesh {
                f.forEach { vertex(at[it]) }
                quad(0, 1, 2, 3)
            }
            mesh(quad, id = "face-$n", layer = ["Polygons"], wireframe = false, cullBackFaces = false, opacity = 0.3)
        }

        // One part per edge colour, so each family of edges can be hidden on its own.
        for ((bit, name) in axisName) {
            val family =
                edges
                    .filter { (a, b) -> (a xor b) == bit }
                    .map { (a, b) ->
                        fline {
                            moveTo(at[a].x, at[a].y, at[a].z)
                            lineTo(at[b].x, at[b].y, at[b].z)
                            color = axisColor[bit]!!
                        }
                    }
            lines(family, id = "edges-$name", layer = ["Edges"])
        }

        for (i in at.indices) {
            if (i in pinned) continue
            handle("c$i", at = at[i], label = "c$i") { drag ->
                val x = drag.position.x.toDouble()
                val y = drag.position.y.toDouble()
                val z = drag.position.z.toDouble()
                when (drag.phase) {
                    ThreeHandleDrag.Phase.START -> held = session.drag(p[i])
                    ThreeHandleDrag.Phase.MOVE -> (held ?: session.drag(p[i]).also { held = it }).to(x, y, z)
                    ThreeHandleDrag.Phase.END -> {
                        held?.to(x, y, z)
                        held?.release()
                        held = null
                    }
                }
                draw()
            }
        }

        markdown(
            """
            **Six flat faces, nothing else**

            - worst face bend ${"%.1e".format(worstBend)}
            - edges from ${shortest.f2} to ${longest.f2}""".trimIndent(),
            corner = "top-left",
        )
        controls(parts = true)
        camera(yaw = 35.0, pitch = 24.0)
        fitTo(vec(-0.6, 0.0, -0.6), vec(1.6, 1.6, 1.6))
    }
}

draw()
```

Pick up a top corner and pull it. The corner goes where you put it, and the rest of the top moves
just far enough to keep every face flat. Let go and the shape settles and stays. The
handle callback is built from these session calls:

| Call | What it does |
| --- | --- |
| `session.pin(p)` | Holds `p` where it is now, with a weight far heavier than any constraint in the model. The bottom face is pinned, so the solid cannot float away. |
| `session.drag(p)` | Starts a gesture on `p` and returns a `DragHandle`. |
| `handle.to(x, y, z)` | Moves the target and solves a few iterations, cheap enough to call on every pointer move. |
| `handle.release()` | Ends the gesture with a longer settle. `p` falls back to an ordinary prior, so it stays where you left it until a constraint needs it to move. |
| `session.unpin(p)` | Lets a pinned point go again, at the ordinary prior. |

The faces and the edges are separate parts in their own layers, so the **Parts** list in the viewer
can hide the polygons or one family of edges. The [three page](three_output.md#parts-by-id-layers-metadata-and-styles)
covers parts and layers.

Each constraint you add takes freedom away. Give every edge a length too, with
`line(p[a], p[b]).length(1.0)` beside `planar`, and each side face can only be a rhombus: the top
can slide, but it can no longer tilt or change size, so the cube can only lean.

## Toward a vanishing point

`vanishesAt` asks a line to point at a given point: the point lies on the line through its two
ends, extended as far as it needs to go. Give the cube's four uprights the same vanishing point `V`
and each side face becomes a slice of a triangle whose tip is at `V`. `V` is a point in the model
like any other, so you can drag it too.

!!! warning "The lines pull on `V` as well"
    The solve moves `V` as readily as it moves the line's ends, so lines that cannot all reach `V`
    drag it toward where they point. When a vanishing point is given rather than found, pin it
    with `session.pin(v)` or hold it with `v.lock()` in the model. The box below pins `V` while
    you drag a corner and lets it go when you release.

The top is a flat cut across the four rays to `V`. The box starts it on a tilted plane, and draws
the triangles each side face is cut from in a layer of their own:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ceres/one_vanishing_point.png" alt="A three.js view of a tapered solid whose four uprights, extended as thin yellow rays, meet at an orange point labelled V up and to the left. Faint grey triangles run from each bottom edge to V. The top of the solid is tilted, with yellow handles on its corners c2, c3, c6 and c7. A legend reads: V at (-0.15, 2.60, 1.50); worst upright miss of V 1.2e-07; top from y = 0.47 to 0.80" wait="4" capture="box" open-outputs="true" padding="20" name="ceres-one-vanishing-point" requires="ceres" embed=true}
import afield.graphics.three.ThreeHandleDrag
import afield.math.optimization.ceres.DragHandle
import afield.math.optimization.ceres.InteractionPolicy
import afield.math.optimization.ceres.PointRef
import afield.math.optimization.ceres.RobustLoss
import afield.math.optimization.ceres.geometryModel
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import kotlin.math.sqrt

/*
The cube again, but its four uprights now run toward a vanishing point V. Each side face is a
quad cut from a triangle whose apex is V, and the top is any flat cut across the four rays, so
it can tilt. Drag V to change the taper, or slide a top corner along its ray while V holds still.
*/

val weight = 1e4

// Corner i sits at the bits of i: x = bit 0, y = bit 1, z = bit 2. y is up in the viewer.
val edges = (0 until 8).flatMap { a -> [1, 2, 4].filter { (a and it) == 0 }.map { bit -> a to (a or bit) } }
val faces =
    [1, 2, 4].flatMap { k ->
        val (u, v) = [1, 2, 4].filter { it != k }
        [0, k].map { s -> [s, s or u, s or u or v, s or v] }
    }
val pinned = [0, 1, 4, 5] // the y = 0 face
val uprights = edges.filter { (a, b) -> (a xor b) == 2 }
val sideFaces = faces.filter { f -> f.any { it in pinned } && f.any { it !in pinned } }
val vStart = [-0.15, 2.6, 1.5]

// Start consistent: each top corner where its ray to V crosses the tilted plane y = 0.7 + 0.3x - 0.2z.
val corners =
    (0 until 8).map { i ->
        val base = [(i and 1).toDouble(), 0.0, ((i shr 2) and 1).toDouble()]
        val t =
            (0.7 + 0.3 * base[0] - 0.2 * base[2]) /
                (vStart[1] - 0.3 * (vStart[0] - base[0]) + 0.2 * (vStart[2] - base[2]))
        if (i in pinned) base else base.indices.map { k -> base[k] + (vStart[k] - base[k]) * t }
    }

val axisName = mapOf(1 to "x", 2 to "y", 4 to "z")
val axisColor = mapOf(1 to vec(0.95, 0.35, 0.3, 1.0), 2 to vec(0.4, 0.85, 0.4, 1.0), 4 to vec(0.35, 0.6, 1.0, 1.0))

val p = mutableListOf<PointRef>()
val vp = mutableListOf<PointRef>()
val model = geometryModel {
    robustLoss(RobustLoss.NONE)
    corners.forEachIndexed { i, c -> p += point("c$i", c[0], c[1], c[2]) }
    vp += point("V", vStart[0], vStart[1], vStart[2])
    val panels = faces.map { f -> panel(p[f[0]], p[f[1]], p[f[2]], p[f[3]]) }
    constraints {
        for ((a, b) in uprights) line(p[a], p[b]).vanishesAt(vp[0], weight = weight)
        for (face in panels) face.planar(weight = weight)
    }
}

val session =
    model.openSession(policy = InteractionPolicy(preserveWeight = 1e-3, dragIterations = 12, settleIterations = 60))
RunScope.current()?.own(session, ReleasePolicy.ACCUMULATING)
for (i in pinned) session.pin(p[i])

val draggable = (0 until 8).filter { it !in pinned }.associate { "c$it" to p[it] } + mapOf("V" to vp[0])
var held: DragHandle? = null

fun pos(r: PointRef) = session.get(r).let { vec(it.x, it.y, it.z) }

/** How far V sits from the line through [a] and [b]. */
fun miss(v: Vec3, a: Vec3, b: Vec3): Double {
    val dx = (b.x - a.x).toDouble()
    val dy = (b.y - a.y).toDouble()
    val dz = (b.z - a.z).toDouble()
    val wx = (v.x - a.x).toDouble()
    val wy = (v.y - a.y).toDouble()
    val wz = (v.z - a.z).toDouble()
    val cx = dy * wz - dz * wy
    val cy = dz * wx - dx * wz
    val cz = dx * wy - dy * wx
    return sqrt(cx * cx + cy * cy + cz * cz) / sqrt(dx * dx + dy * dy + dz * dz)
}

fun segment(a: Vec3, b: Vec3, c: Vec4) = fline {
    moveTo(a.x, a.y, a.z)
    lineTo(b.x, b.y, b.z)
    color = c
}

fun draw() {
    val at = p.map(::pos)
    val v = pos(vp[0])
    val worstMiss = uprights.maxOf { (a, b) -> miss(v, at[a], at[b]) }
    val tops = [2, 3, 6, 7].map { at[it].y }

    three(name = "vanishing", height = 560) {
        for ((n, f) in faces.withIndex()) {
            val quad = triMesh {
                f.forEach { vertex(at[it]) }
                quad(0, 1, 2, 3)
            }
            mesh(quad, id = "face-$n", layer = ["Polygons"], wireframe = false, cullBackFaces = false, opacity = 0.3)
        }

        // The triangle each side face is cut from: its bottom edge and V.
        for ((n, f) in sideFaces.withIndex()) {
            val (a, b) = f.filter { it in pinned }
            val tri = triMesh {
                vertex(at[a])
                vertex(at[b])
                vertex(v)
                triangle(0, 1, 2)
            }
            mesh(tri, id = "toward-$n", layer = ["Toward V"], wireframe = false, cullBackFaces = false, opacity = 0.08)
        }
        lines(
            uprights.map { (_, top) -> segment(at[top], v, vec(1.0, 0.85, 0.3, 0.6)) },
            id = "rays",
            layer = ["Toward V"],
        )

        for ((bit, name) in axisName) {
            val family =
                edges.filter { (a, b) -> (a xor b) == bit }.map { (a, b) -> segment(at[a], at[b], axisColor[bit]!!) }
            lines(family, id = "edges-$name", layer = ["Edges"])
        }

        for ((id, ref) in draggable) {
            val isV = id == "V"
            val point = pos(ref)
            // A top corner slides along its ray to V, and V is held while it does.
            val ray = if (isV) null else vec(v.x - point.x, v.y - point.y, v.z - point.z)
            fun grab() = session.drag(ref).also {
                if (!isV) session.pin(vp[0])
                held = it
            }
            handle(id, at = point, label = id, color = if (isV) "#ff7a3d" else null, axis = ray) { drag ->
                val x = drag.position.x.toDouble()
                val y = drag.position.y.toDouble()
                val z = drag.position.z.toDouble()
                when (drag.phase) {
                    ThreeHandleDrag.Phase.START -> grab()
                    ThreeHandleDrag.Phase.MOVE -> (held ?: grab()).to(x, y, z)
                    ThreeHandleDrag.Phase.END -> {
                        held?.to(x, y, z)
                        held?.release()
                        held = null
                        if (!isV) session.unpin(vp[0])
                    }
                }
                draw()
            }
        }

        markdown(
            """
            **Cube toward a vanishing point**

            - V at (${v.x.f2}, ${v.y.f2}, ${v.z.f2})
            - worst upright miss of V ${"%.1e".format(worstMiss)}
            - top from y = ${tops.min().f2} to ${tops.max().f2}""".trimIndent(),
            corner = "top-left",
        )
        controls(parts = true)
        camera(yaw = 35.0, pitch = 18.0)
        fitTo(vec(-0.6, 0.0, -0.6), vec(1.6, 2.9, 1.9)) // room for V
    }
}

draw()
```

Two things keep this pleasant to drag. A top corner's handle is given an `axis` along its ray to
`V`, so the marker can only slide toward or away from `V`, which is the only way that corner can
move without breaking its upright. While a corner is held, `V` is pinned, and it is unpinned again
on release. Without that, the solver could satisfy the drag just as well by moving `V` as by moving
the corner, and `V` would wander off.

To keep the top square, add `line(p[a], p[b]).parallelTo(line(p[a and 5], p[b and 5]))` for each top
edge. The top is then the bottom square shrunk toward `V`, and dragging a corner changes the height
of the whole top at once.

## Two vanishing points

The same idea works with more than one family of lines. In this box the uprights run toward `V`, the
depth edges toward a second point `Vz`, and the width edges stay parallel to the front bottom edge.
Only that front edge is pinned. Both vanishing points are pinned too, except while you drag one, and
they stay where you let them go:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ceres/two_vanishing_points.png" alt="A three.js view of a box in two-point perspective. Yellow rays from its four uprights meet at an orange point V above it; rays from its four depth edges meet at a second orange point Vz far to the lower left. Faint triangles fill both fans of rays. Yellow handles sit on corners c2 to c7. A legend reads: V at (0.45, 3.90, 0.60), worst upright miss 1.7e-07; Vz at (0.60, -0.10, 5.00), worst depth-edge miss 2.0e-07" wait="4" capture="box" open-outputs="true" padding="20" name="ceres-two-vanishing-points" requires="ceres" embed=true}
import afield.graphics.three.ThreeHandleDrag
import afield.math.optimization.ceres.DragHandle
import afield.math.optimization.ceres.InteractionPolicy
import afield.math.optimization.ceres.PointRef
import afield.math.optimization.ceres.RobustLoss
import afield.math.optimization.ceres.geometryModel
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import kotlin.math.sqrt

/*
The cube with two vanishing points: its uprights run toward V, its depth edges toward Vz, and its
width edges stay parallel. Only the front bottom edge is pinned. Every face with two converging
edges is cut from a triangle whose apex is that edge family's vanishing point. Drag V or Vz to
change a taper; slide a corner along its ray to change the height or the depth. The vanishing
points hold still unless you are dragging one.
*/

val weight = 1e4

// Corner i sits at the bits of i: x = bit 0, y = bit 1, z = bit 2. y is up in the viewer.
val edges = (0 until 8).flatMap { a -> [1, 2, 4].filter { (a and it) == 0 }.map { bit -> a to (a or bit) } }
val faces =
    [1, 2, 4].flatMap { k ->
        val (u, v) = [1, 2, 4].filter { it != k }
        [0, k].map { s -> [s, s or u, s or u or v, s or v] }
    }
val pinned = [0, 1] // the front bottom edge
val family = { bit: Int -> edges.filter { (a, b) -> (a xor b) == bit } }

fun plus3(a: List<Double>, b: List<Double>) = a.indices.map { a[it] + b[it] }
fun minus3(a: List<Double>, b: List<Double>) = a.indices.map { a[it] - b[it] }
fun times3(a: List<Double>, s: Double) = a.map { it * s }
fun dot3(a: List<Double>, b: List<Double>) = a.indices.sumOf { a[it] * b[it] }
fun toward3(a: List<Double>, b: List<Double>, t: Double) = plus3(a, times3(minus3(b, a), t))

/** Where the line a→b meets the line c→d (the point on the first nearest the second). */
fun meet(a: List<Double>, b: List<Double>, c: List<Double>, d: List<Double>): List<Double> {
    val u = minus3(b, a)
    val v = minus3(d, c)
    val w = minus3(a, c)
    val uu = dot3(u, u)
    val uv = dot3(u, v)
    val vv = dot3(v, v)
    return toward3(a, b, (uv * dot3(v, w) - vv * dot3(u, w)) / (uu * vv - uv * uv))
}

// A consistent start: depth a fifth of the way to Vz, height a quarter of the way to V.
val vStart = [0.45, 3.9, 0.6]
val vzStart = [0.6, -0.1, 5.0]
val start =
    MutableList(8) { [0.0, 0.0, 0.0] }
        .also { c ->
            c[0] = [0.0, 0.0, 0.0]
            c[1] = [1.0, 0.0, 0.0]
            c[4] = toward3(c[0], vzStart, 0.2)
            c[5] = toward3(c[1], vzStart, 0.2)
            c[2] = toward3(c[0], vStart, 0.25)
            c[3] = toward3(c[1], vStart, 0.25)
            c[6] = meet(c[4], vStart, c[2], vzStart)
            c[7] = meet(c[5], vStart, c[3], vzStart)
        }

val axisName = mapOf(1 to "x", 2 to "y", 4 to "z")
val axisColor = mapOf(1 to vec(0.95, 0.35, 0.3, 1.0), 2 to vec(0.4, 0.85, 0.4, 1.0), 4 to vec(0.35, 0.6, 1.0, 1.0))

val p = mutableListOf<PointRef>()
val vps = mutableMapOf<Int, PointRef>() // edge family bit → its vanishing point
val model = geometryModel {
    robustLoss(RobustLoss.NONE)
    start.forEachIndexed { i, c -> p += point("c$i", c[0], c[1], c[2]) }
    vps[2] = point("V", vStart[0], vStart[1], vStart[2])
    vps[4] = point("Vz", vzStart[0], vzStart[1], vzStart[2])
    val panels = faces.map { f -> panel(p[f[0]], p[f[1]], p[f[2]], p[f[3]]) }
    constraints {
        for ((bit, vp) in vps) for ((a, b) in family(bit)) line(p[a], p[b]).vanishesAt(vp, weight = weight)
        for ((a, b) in family(1)) if (a != 0) line(p[a], p[b]).parallelTo(line(p[0], p[1]), weight = weight)
        for (face in panels) face.planar(weight = weight)
    }
}

val session =
    model.openSession(policy = InteractionPolicy(preserveWeight = 1e-3, dragIterations = 12, settleIterations = 60))
RunScope.current()?.own(session, ReleasePolicy.ACCUMULATING)
for (i in pinned) session.pin(p[i])
for (vp in vps.values) session.pin(vp)

// Which vanishing point each free corner slides toward: the top corners up their uprights, the back
// bottom corners along their depth edges.
val slidesToward = mapOf(2 to 2, 3 to 2, 6 to 2, 7 to 2, 4 to 4, 5 to 4)
val vpName = mapOf(2 to "V", 4 to "Vz")
var held: DragHandle? = null

fun pos(r: PointRef) = session.get(r).let { vec(it.x, it.y, it.z) }

/** How far [v] sits from the line through [a] and [b]. */
fun miss(v: Vec3, a: Vec3, b: Vec3): Double {
    val dx = (b.x - a.x).toDouble()
    val dy = (b.y - a.y).toDouble()
    val dz = (b.z - a.z).toDouble()
    val wx = (v.x - a.x).toDouble()
    val wy = (v.y - a.y).toDouble()
    val wz = (v.z - a.z).toDouble()
    val cx = dy * wz - dz * wy
    val cy = dz * wx - dx * wz
    val cz = dx * wy - dy * wx
    return sqrt(cx * cx + cy * cy + cz * cz) / sqrt(dx * dx + dy * dy + dz * dz)
}

fun segment(a: Vec3, b: Vec3, c: Vec4) = fline {
    moveTo(a.x, a.y, a.z)
    lineTo(b.x, b.y, b.z)
    color = c
}

fun draw() {
    val at = p.map(::pos)
    val vAt = vps.mapValues { (_, r) -> pos(r) }
    val worstMiss = vps.keys.associateWith { bit -> family(bit).maxOf { (a, b) -> miss(vAt[bit]!!, at[a], at[b]) } }

    three(name = "two vanishing points", height = 600) {
        for ((n, f) in faces.withIndex()) {
            val quad = triMesh {
                f.forEach { vertex(at[it]) }
                quad(0, 1, 2, 3)
            }
            mesh(quad, id = "face-$n", layer = ["Polygons"], wireframe = false, cullBackFaces = false, opacity = 0.3)
        }

        for ((bit, v) in vAt) {
            val name = vpName[bit]!!
            // Each face holding two edges of this family is cut from the triangle on their near ends and v.
            for ((n, f) in faces.withIndex()) {
                val near = f.filter { (it and bit) == 0 && (it or bit) in f }
                if (near.size != 2) continue
                val tri = triMesh {
                    vertex(at[near[0]])
                    vertex(at[near[1]])
                    vertex(v)
                    triangle(0, 1, 2)
                }
                mesh(
                    tri,
                    id = "toward-$name-$n",
                    layer = ["Toward $name"],
                    wireframe = false,
                    cullBackFaces = false,
                    opacity = 0.06,
                )
            }
            lines(
                family(bit).map { (_, far) -> segment(at[far], v, vec(1.0, 0.85, 0.3, 0.6)) },
                id = "rays-$name",
                layer = ["Toward $name"],
            )
        }

        for ((bit, name) in axisName) {
            lines(
                family(bit).map { (a, b) -> segment(at[a], at[b], axisColor[bit]!!) },
                id = "edges-$name",
                layer = ["Edges"],
            )
        }

        for ((bit, ref) in vps) {
            val name = vpName[bit]!!
            handle(name, at = vAt[bit]!!, label = name, color = "#ff7a3d") { drag ->
                val x = drag.position.x.toDouble()
                val y = drag.position.y.toDouble()
                val z = drag.position.z.toDouble()
                when (drag.phase) {
                    ThreeHandleDrag.Phase.START -> {
                        session.unpin(ref)
                        held = session.drag(ref)
                    }
                    ThreeHandleDrag.Phase.MOVE ->
                        (held
                                ?: session.drag(ref).also {
                                    session.unpin(ref)
                                    held = it
                                })
                            .to(x, y, z)
                    ThreeHandleDrag.Phase.END -> {
                        held?.to(x, y, z)
                        held?.release()
                        held = null
                        session.pin(ref) // hold it where it was let go
                    }
                }
                draw()
            }
        }

        for ((i, bit) in slidesToward) {
            val v = vAt[bit]!!
            val ray = vec(v.x - at[i].x, v.y - at[i].y, v.z - at[i].z)
            // Height and depth are separate freedoms: hold the one this corner does not change, or the
            // solver spreads the move over both. c4 fixes the depth, c2 the height.
            val holds = p[if (bit == 2) 4 else 2]
            fun grab() = session.drag(p[i]).also {
                session.pin(holds)
                held = it
            }
            handle("c$i", at = at[i], label = "c$i", axis = ray) { drag ->
                val x = drag.position.x.toDouble()
                val y = drag.position.y.toDouble()
                val z = drag.position.z.toDouble()
                when (drag.phase) {
                    ThreeHandleDrag.Phase.START -> grab()
                    ThreeHandleDrag.Phase.MOVE -> (held ?: grab()).to(x, y, z)
                    ThreeHandleDrag.Phase.END -> {
                        held?.to(x, y, z)
                        held?.release()
                        held = null
                        session.unpin(holds)
                    }
                }
                draw()
            }
        }

        markdown(
            """
            **Cube with two vanishing points**

            - V at (${vAt[2]!!.x.f2}, ${vAt[2]!!.y.f2}, ${vAt[2]!!.z.f2}), worst upright miss ${"%.1e".format(worstMiss[2])}
            - Vz at (${vAt[4]!!.x.f2}, ${vAt[4]!!.y.f2}, ${vAt[4]!!.z.f2}), worst depth-edge miss ${"%.1e".format(worstMiss[4])}""".trimIndent(),
            corner = "top-left",
        )
        controls(parts = true)
        camera(yaw = 55.0, pitch = 20.0)
        fitTo(vec(-0.6, -0.2, -0.6), vec(1.6, 4.1, 5.2)) // room for both vanishing points
    }
}

draw()
```

Here is the box being dragged: `V` first, then `Vz`, then the back corner `c4`, which slides along
its ray toward `Vz` and makes the box deeper. Last, a drag across empty space turns the camera. The corners follow
every move, and both families of edges keep meeting their points:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="ceres_two_vp" video="assets/ceres/two_vanishing_points_drag.mp4" fps="15" transcript="../../dev_docs/agents/transcripts/docs_capture/ceres_two_vanishing_points.yaml" hide-toasts=true padding="8" wait="0" timeout="300" embed=true hidden=true name="ceres-two-vanishing-points-drag" requires="ceres" alt="The two-point-perspective box being dragged. First V, the vanishing point above the box, is pulled to the left, and the four uprights lean to follow it while the box stays standing on its front edge. Then Vz, far to the lower left, is pulled up and to the right, and the depth edges swing round to meet it. Next the back corner c4 is slid along its ray toward Vz, and the box grows deeper. Last, a drag across empty space turns the camera around the box. The legend's two misses stay below 1e-6 throughout"}
// The setup transcript creates and runs the box; drag with the real mouse.
fun view(js: String): Pair<Double, Double> {
    val at =
        ui.eval(
                """
        const f = document.querySelector('iframe[src*="/threejs"]');
        if (!f || !f.contentWindow.FieldHandles) return 'missing';
        const r = f.getBoundingClientRect();
        const handle = (id) => f.contentWindow.FieldHandles.list().find((h) => h.id === id);
        const at = (() => { $js })();
        return at ? (r.left + at[0]) + ',' + (r.top + at[1]) : 'missing';
    """,
            )
            .toString()
            .trim('"')
    check(at != "missing") { "nothing at $js in the three view" }
    val (x, y) = at.split(',').map { it.toDouble() }
    return x to y
}

fun handleAt(id: String) = view("const h = handle('$id'); return h && h.client;")

fun drag(from: Pair<Double, Double>, to: Pair<Double, Double>) {
    val (x, y) = from
    ui.moveMouse(x, y, 6)
    ui.pause(150)
    ui.mouseDown()
    for (k in 1..30) {
        ui.moveMouse(x + (to.first - x) * k / 30, y + (to.second - y) * k / 30)
        ui.pause(30)
    }
    ui.pause(150)
    ui.mouseUp()
    ui.pause(500)
}

fun dragBy(id: String, dx: Double, dy: Double) {
    val (x, y) = handleAt(id)
    drag(x to y, x + dx to y + dy)
}

ui.pause(600)
dragBy("V", -170.0, 30.0)
dragBy("Vz", 130.0, -90.0)

// c4 slides on its ray, so aim at Vz: a third of the way there makes the box deeper.
val c4 = handleAt("c4")
val vz = handleAt("Vz")
drag(c4, c4.first + (vz.first - c4.first) * 0.35 to c4.second + (vz.second - c4.second) * 0.35)

// A drag across empty space below the box turns the camera.
val empty = view("return [r.width * 0.75, r.height * 0.88];")
drag(empty, empty.first - 260.0 to empty.second - 20.0)
ui.pause(500)
```

The box has two independent freedoms now: how tall it is, measured along the rays to `V`, and how
deep it is, measured along the rays to `Vz`. A drag on one corner should change only one of them.
Left to itself, the solver spreads a drag over both, because both moves satisfy the target equally
well, and the box grows taller when you meant to make it deeper. So while a corner is held, the box
also pins a corner that stands for the other freedom: `c4` fixes the depth while you change the
height, and `c2` fixes the height while you change the depth. The corner's handle runs along its own
ray, as before.

Starting positions have to agree with the constraints, or the first solve has to repair them. The
box builds a consistent start by walking each corner partway along its ray, and uses `meet` to put
the two back top corners where their rays to `V` and `Vz` cross.

## Constraints you can state

These are the statements most models are built from. Each one takes an optional `weight`, and a
solve balances the weighted statements against each other and against the priors. The
[Solvers reference](reference/solvers.md#architecturalgeometry-kt) lists all of them, with their
exact definitions.

| Statement | Means |
| --- | --- |
| `line(a, b).length(d)` | The distance from `a` to `b` is `d`. `minLength` and `maxLength` are one-sided. |
| `line(a, b).sameLengthAs(line(c, d))` | Two segments have equal length, whatever it is. |
| `line(a, b).parallelTo(line(c, d))` | Two segments are parallel, in either direction. Their four ends must be different points. |
| `line(a, b).perpendicularTo(line(c, d))` | A right angle. `angleRadiansTo` asks for any other angle. |
| `line(a, b).vanishesAt(v)` | The line through `a` and `b` passes through `v`. |
| `line(a, b).direction(dir)` | The segment points along a fixed direction. |
| `quad.planar()` | The four corners of a `panel(a, b, c, d)`, declared in the model, lie in one plane. `area(target)` fixes its area as well. |
| `p.on(plane)` | A point lies on a plane, optionally at an `offset` from it. |
| `p.lock()` | Holds a point where it was declared, on all axes or on the ones you choose. |
| `mirror(p, q, across = plane)` | `q` is the reflection of `p` in a plane. |

The drag examples begin with `robustLoss(RobustLoss.NONE)`, which asks for plain least squares.
The default is a Huber loss, whose cost grows only linearly once a statement is far from satisfied.
That keeps one badly placed point from dragging the whole model, but it also means that two
statements pulling against each other settle on the heavier one instead of a compromise between
their weights. Choose `NONE` whenever the weights are meant to trade off.

## Where to go next

- [Field's 3D output](three_output.md) covers the viewer these boxes draw in: drag handles, parts
  and layers, cameras and `fitTo`.
- The [Solvers reference](reference/solvers.md) documents every constraint, the session and
  `InteractionPolicy`, which sets how hard a dragged point is held and how many iterations each
  phase of a drag gets.
- [Polytopes](polytope_examples.md) uses Ceres to relax a polytope's realization toward canonical
  form.
- The binding's source is
  [ArchitecturalGeometry.kt](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/math/optimization/ceres/ArchitecturalGeometry.kt),
  and how the native library is built is in
  [dev_docs/CERES.md](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/CERES.md).

Next builds:

- Three vanishing points: give the width edges their own point instead of keeping them parallel,
  and the box is in full three-point perspective.
- A whole street: several boxes sharing one horizon, where dragging a vanishing point moves every
  building at once.
- A drawn photograph: pin a box's corners to points picked on an image and solve for the vanishing
  points, which recovers a camera from a picture.
- A starting-point document with these four boxes, so the page can link one you can open.
