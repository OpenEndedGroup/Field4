# Linear programs as curves

<!-- capture-sources: src/jvmMain/kotlin/afield/geometry/spatial/lp_trajectories.kt -->

*Every public declaration used below is listed, with its KDoc, in the codebase reference:
[Graphics](reference/graphics.md).*

A two-dimensional linear program asks for the point of a convex polygon that goes furthest in one
direction: maximise `c·x` over the polygon. The answer is always a vertex, and every classical
algorithm for it gets there. What differs is the route. The barrier method follows a smooth arc out
of the polygon's centre, the primal-dual method takes a few long strides, PDHG spirals in from
outside, the ellipsoid method shrinks a sequence of ellipses around the answer, and the simplex
method hops round the boundary. [lpviz.net](https://lpviz.net) draws these routes in a browser.

In Field these routes are the product: each method returns its whole trajectory, step by step,
as something you can draw, sample, animate and feed into other geometry. A polygon, an objective
direction and a few dials (weights on the edges, step sizes, a centring rule) give you a family of
curves that all agree about where they end.

## A polygon and three routes

Build an `LpPolygon` from its vertices (either winding; it is normalised to counter-clockwise), pick
a direction, and ask for a route. The example below draws the barrier method's central path, the
primal-dual method's iterates and the simplex method's walk, all for the same direction.

```diffstatic_runner {data-lang=kotlin screenshot="assets/lp_curves/three_routes.png" wait="3.0" capture="box" open-outputs="true" w="1000" h="400" padding="14"}
:::static
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(1.0, 0.4)

val central = polygon.centralPath(c) // 160 samples as the barrier weight μ falls
val pd = polygon.primalDual(c) // one sample per Newton step
val walk = polygon.simplexWalk(c, startVertex = 4) // vertex to vertex along the boundary
:::fold draw the three panels
val outline = polygon.toFLine()
val answer = pointCloudFLine([polygon.optimalVertex(c)], vec(1.0, 0.4, 0.4, 1.0), 2.0)
val dots = { t: LpTrajectory -> pointCloudFLine(t.positions, vec(1.0, 1.0, 1.0, 0.9), 1.0) }

showPlaneGrid(
    "centralPath\n${central.samples.size} samples, μ from %.0e to %.0e".format(central.samples.first().scale, central.last.scale) to
        [outline, central.toFLine(), answer],
    "primalDual\n${pd.samples.size - 1} Newton steps to μ = %.1e".format(pd.last.scale) to
        [outline, pd.toFLine(vec(0.55, 0.9, 0.6, 0.95)), dots(pd), answer],
    "simplexWalk\n${walk.samples.size - 1} pivots from vertex 4" to
        [outline, walk.toFLine(vec(0.6, 0.75, 1.0, 0.95), 2.4), dots(walk), answer],
    columns = 3,
    yUp = true,
    cellWidth = 310,
    cellHeight = 280,
)
:::endfold
```

![Three copies of a pentagon. In the first a smooth orange arc leaves the middle and curves into the right-hand vertex; in the second a few green strides cover the same ground; in the third a blue line runs along two edges to the same vertex](assets/lp_curves/three_routes.png)

Each route is an `LpTrajectory`: a list of `LpSample`s, each carrying its position, its iteration
number, its objective value `c·x`, its constraint violation, a `scale` and a `tag`. What `scale`
measures depends on the method: μ for the two interior-point methods, the dual norm for PDHG, the
ellipse's size for the ellipsoid method, the remaining area for cutting planes. `toFLine()` draws the
route; `positions` gives the points.

## A fan of central paths

Turn the objective direction through a full circle and you get one central path per direction. All
of them leave the same point (the polygon's *analytic centre*, where the barrier alone balances)
and each ends at the vertex that wins for its direction. `centralFan` returns the whole family; it
is a sampling of a surface `x(θ, μ)` whose two parameters are the direction's angle and the barrier
weight.

```diffstatic_runner {data-lang=kotlin screenshot="assets/lp_curves/fan.png" wait="3.0" capture="box" open-outputs="true" w="900" h="420" padding="14"}
:::static
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])

val fan = polygon.centralFan(angles = 72, samples = 90)
// Weight 6 on edge 0 (the bottom-left edge) pushes every path, and the centre, away from it.
val leaning = polygon.centralFan(angles = 72, samples = 90, weights = doubleArrayOf(6.0, 1.0, 1.0, 1.0, 1.0))

showPlaneGrid(
    "centralFan, equal weights\n${fan.size} directions, one curve each" to [polygon.toFLine()] + fan.toFLines(),
    "centralFan, edge 0 weighted 6\nsame vertices at the ends, different curves" to [polygon.toFLine()] + leaning.toFLines(),
    columns = 2,
    yUp = true,
    cellWidth = 420,
    cellHeight = 340,
)
```

![Two pentagons filled with rainbow-coloured curves radiating from a common centre to the five vertices. In the second the common centre has moved up, away from the heavily weighted bottom edge, and every curve has bent with it](assets/lp_curves/fan.png)

## Weights and redundant constraints

The barrier is a sum of `wᵢ · ln(slackᵢ)`, one term per edge. Giving an edge weight 3 is exactly the
same as listing that edge three times: the polygon is unchanged, but the path is not. That makes
the weights a drawing control. The left panel below follows one direction with three different
weightings; the right panel sweeps the weight on one edge from 1 to 40 and plots where the path
starts, which is the weighted analytic centre.

```diffstatic_runner {data-lang=kotlin screenshot="assets/lp_curves/weights.png" wait="3.0" capture="box" open-outputs="true" w="900" h="420" padding="14"}
:::static
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(-0.3, 1.0)

val plain = polygon.centralPath(c)
val heavyBottom = polygon.centralPath(c, weights = doubleArrayOf(8.0, 8.0, 1.0, 1.0, 1.0))
val heavyRight = polygon.centralPath(c, weights = doubleArrayOf(1.0, 1.0, 8.0, 1.0, 1.0))

// The weighted analytic centre as edge 2's weight grows: the first sample of each path.
val centres = (0..60).map { k ->
    val w = 1.0 + 39.0 * k / 60
    polygon.centralPath(c, weights = doubleArrayOf(1.0, 1.0, w, 1.0, 1.0), samples = 2).samples.first().position
}
:::fold draw the two panels
showPlaneGrid(
    "three weightings, one direction\nwhite: equal, orange: edges 0 and 1 ×8, blue: edge 2 ×8" to
        [
            polygon.toFLine(),
            plain.toFLine(vec(1.0, 1.0, 1.0, 0.9)),
            heavyBottom.toFLine(vec(1.0, 0.65, 0.3, 0.95)),
            heavyRight.toFLine(vec(0.5, 0.75, 1.0, 0.95)),
        ],
    "the start moves with the weight\nedge 2 weighted 1 → 40, 61 analytic centres" to
        [polygon.toFLine(), polylineFrom(centres).also { it.color = vec(1.0, 0.8, 0.4, 0.9); it.thickness = 1.6 }, pointCloudFLine(centres, pointSize = 0.8)],
    columns = 2,
    yUp = true,
    cellWidth = 420,
    cellHeight = 340,
)
:::endfold
```

![Left: three curves leave three different interior points and converge on the same vertex of the pentagon. Right: a short curve of dots runs from the middle of the pentagon down toward its lower-left corner, away from the upper-right edge whose weight grows](assets/lp_curves/weights.png)

## PDHG: spirals from outside

The primal-dual hybrid gradient method does no Newton solves. It keeps one dual value per edge,
pushes `x` along the objective, and lets each edge's dual push back once `x` crosses it. The
iterates leave the polygon, overshoot, and spiral into the vertex. `pdhg` keeps every state,
infeasible ones included, and the duals too (`trace.duals`). The step sizes `tau` and `sigma` set
the spiral's character: small steps wind round the vertex many times before settling, larger ones
settle in a turn or two, and past the stability limit the iterates oscillate without settling,
which the trace keeps.

```diffstatic_runner {data-lang=kotlin screenshot="assets/lp_curves/pdhg.png" wait="3.0" capture="box" open-outputs="true" w="1000" h="400" padding="14"}
:::static
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(1.0, 0.4)
val answer = polygon.optimalVertex(c)

val panels = [0.08, 0.3, 0.62].map { step ->
    val trace = polygon.pdhg(c, iterations = 700, tau = step, sigma = step)
    val end = trace.trajectory.last
    val outside = trace.trajectory.samples.count { it.violation > 0.0 }
    "tau = sigma = $step\n$outside of 701 iterates outside; ends %.3f from the vertex".format(end.position.distance(answer)) to
        [polygon.toFLine(), trace.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.85), 1.0)]
}

showPlaneGrid(*panels.toTypedArray(), columns = 3, cellWidth = 310, cellHeight = 280, yUp = true)
```

![Three pentagons with a pink trajectory that runs to the right-hand vertex and spirals into it: many wide slow turns with small steps, two tight turns with medium steps, and one short angular loop with large steps](assets/lp_curves/pdhg.png)

## The ellipsoid method, with its ellipses

The ellipsoid method keeps an ellipse known to contain the answer. At each step it cuts the ellipse
in half through its centre (on the most violated edge if the centre is outside the polygon, on the
objective if it is inside) and replaces it with the smallest ellipse containing the half it keeps.
In two dimensions every cut multiplies the area by `√(16/27) ≈ 0.77`. `ellipsoid` returns both the
centres and the ellipses (`LpEllipse`, a centre and a 2×2 shape matrix, with `toFLine()`).

```diffstatic_runner {data-lang=kotlin screenshot="assets/lp_curves/ellipsoid.png" wait="3.0" capture="box" open-outputs="true" w="900" h="420" padding="14"}
:::static
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(1.0, 0.4)

val trace = polygon.ellipsoid(c, iterations = 40)
val samples = trace.trajectory.samples
val feasibilityCuts = samples.count { it.tag == "feasibility-cut" }
val best = samples.filter { it.violation == 0.0 }.maxOf { it.objective }
val optimum = polygon.simplexWalk(c).last.objective // c·x at the optimal vertex
val shrink = kotlin.math.sqrt(trace.ellipses[1].determinant / trace.ellipses[0].determinant)
:::fold draw the two panels
val ellipses = trace.ellipses.take(24).map { it.toFLine(color = vec(0.55, 0.8, 1.0, 0.35)) }
val centres = { tag: String, color: Vec4 -> pointCloudFLine(samples.filter { it.tag == tag }.map { it.position }, color, 1.3) }

showPlaneGrid(
    "the first 24 ellipses\narea shrinks by %.3f per step".format(shrink) to
        [polygon.toFLine()] + ellipses + trace.trajectory.toFLine(vec(1.0, 1.0, 1.0, 0.6), 0.8),
    "centres: red cut an edge, green cut the objective\n$feasibilityCuts feasibility cuts; best feasible c·x within %.1e".format(optimum - best) to
        [polygon.toFLine(), trace.trajectory.toFLine(vec(1.0, 1.0, 1.0, 0.35), 0.8), centres("feasibility-cut", vec(1.0, 0.4, 0.4, 1.0)), centres("objective-cut", vec(0.4, 1.0, 0.55, 1.0))],
    columns = 2,
    yUp = true,
    cellWidth = 420,
    cellHeight = 340,
)
:::endfold
```

![Left: a nest of blue ellipses, the first much larger than the pentagon, shrinking toward its right-hand vertex. Right: the pentagon with the ellipse centres as red dots outside it and green dots inside, clustering at the vertex](assets/lp_curves/ellipsoid.png)

## Cutting planes

A cutting-plane method keeps a region instead of an ellipse. It finds a centre of what is left,
cuts away everything worse than that centre, and repeats. The four `LpCenter`s choose the centre:

- `CENTROID`, the centre of gravity of the region.
- `ANALYTIC`, the analytic centre of every constraint so far, so each earlier cut keeps pushing, as
  an extra constraint pushes a central path.
- `CHEBYSHEV`, the centre of the largest disc that fits in the region. Only the edges the disc
  touches decide it.
- `VOLUMETRIC`, Vaidya's volumetric centre: like the analytic centre, but each constraint counts
  in proportion to how much it constrains, so a stack of nearly parallel cuts counts for less.

`trace.regions` holds the region at every step. In the example the direction `(-1, -0.2)` ties
the two left-hand vertices, so the whole left edge is optimal and the methods may end anywhere on
it: three end near its middle, while the Chebyshev centres slide down to the lower corner.

```diffstatic_runner {data-lang=kotlin screenshot="assets/lp_curves/cutting_planes.png" wait="3.0" capture="box" open-outputs="true" w="900" h="820" padding="14"}
:::static
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(-1.0, -0.2)

val panels = LpCenter.entries.map { kind ->
    val trace = polygon.cuttingPlanes(c, iterations = 14, center = kind)
    val regions = trace.regions.map { r -> polylineFrom(r, closed = true).also { it.color = vec(0.6, 0.8, 1.0, 0.4); it.thickness = 0.9 } }
    "$kind centres\narea %.2f → %.1e in ${trace.regions.size - 1} cuts".format(trace.trajectory.samples.first().scale, trace.trajectory.last.scale) to
        [polygon.toFLine()] + regions + trace.trajectory.toFLine(vec(1.0, 0.72, 0.32, 0.95)) + pointCloudFLine(trace.trajectory.positions, pointSize = 1.0)
}

showPlaneGrid(*panels.toTypedArray(), columns = 2, cellWidth = 420, cellHeight = 340, yUp = true)
```

![Four pentagons, each cut by parallel lines into nested shrinking regions, with an orange path of centres running left to the pentagon's left-hand edge; in the Chebyshev panel the path bends down to the lower-left corner](assets/lp_curves/cutting_planes.png)

## Animated: a turning objective

The objective direction below turns once every eight seconds while the hexagon's vertices breathe,
and every frame redraws the central path, the PDHG spiral and the simplex walk for the new
direction. The routes swing from vertex to vertex as the arrow from the centre passes each one.

The central path comes from an `LpContinuation`. Each frame's polygon and direction are close to
the last frame's, so the continuation starts Newton for each μ at the previous frame's sample for
that μ (`centralPath(..., warm = previous)` does the same by hand). The path is the same one a
cold start would find. The caption shows both Newton step counts, and they do not always favour
the warm start: when frames are close together it saves steps, but at this clip's 2.25° a frame,
and while the path swings from one vertex to the next, a stale start can cost more than following
the path from the centre.

```diffstatic_runner {data-lang=kotlin video="assets/lp_curves/turning.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="8" fps="20" timeout="300" w="620" embed=true name="lp-curves-turning" alt="a breathing hexagon with an arrow turning from its centre; an orange central path, a pink PDHG spiral and a blue simplex walk swing together from vertex to vertex as the arrow turns"}
:::static
import kotlin.math.*

val period = 8.0 // seconds per turn of the objective
val weights = doubleArrayOf(1.0, 1.0, 3.0, 1.0, 1.0, 1.0)
val window = Rect(-3.6, -3.4, 7.2, 6.8)

// Each vertex's radius wanders between 1.9 and 2.9, which keeps the hexagon convex.
fun shape(t: Double) = LpPolygon((0 until 6).map { k ->
    val a = 2.0 * PI * k / 6 + 0.25
    val r = 2.4 + 0.5 * sin(t * 0.9 + k * 1.7)
    vec(r * cos(a), r * sin(a))
})

val continuation = LpContinuation()
val started = AnimationClock.nowSeconds()

loop {
    val t = AnimationClock.nowSeconds() - started
    val polygon = shape(t)
    val theta = 2.0 * PI * t / period
    val c = vec(cos(theta), sin(theta))
    val central = continuation.centralPath(polygon, c, weights, samples = 90)
    val coldSteps = polygon.centralPath(c, weights, samples = 90).solverSteps
    val spiral = polygon.pdhg(c, iterations = 300, tau = 0.3, sigma = 0.3)
    val walk = polygon.simplexWalk(c)
    val o = polygon.center
    val arrow = fline {
        moveTo(o.x, o.y)
        lineTo(o.x + 0.9 * c.x, o.y + 0.9 * c.y)
        color = vec(1.0, 1.0, 1.0, 0.6)
        thickness = 1.4
    }
    showPlane(
        [
            polygon.toFLine(),
            arrow,
            spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.7), 0.9),
            walk.toFLine(vec(0.6, 0.75, 1.0, 0.95), 2.4),
            central.toFLine(vec(1.0, 0.72, 0.32, 1.0), 2.0),
        ],
        title = "θ = %3.0f°".format(Math.toDegrees(theta) % 360.0),
        caption = "orange: central path, ${central.solverSteps} Newton steps warm, $coldSteps cold · pink: PDHG · blue: simplex walk",
        width = 560,
        height = 520,
        window = window,
        interactive = false,
        yUp = true,
    )
}
```

The starting point [lp_curves](agent_transcripts.md#lp_curves) opens the same animation in a box
of its own, next to a box that draws every route at once. Change the weights or the step sizes and
run it again.

## Polygons from drawings

`LpPolygon.of(line)` reads the node positions of an `FLine`, so a closed outline drawn with
`fline { }` or taken from elsewhere is a polygon here. The constructor refuses fewer than three
distinct vertices, zero area, a reflex corner and three collinear vertices, and says which. Only
the end point of each segment is read; flatten curved segments first if they should count.

## Reference

<div class="field-data-table" markdown>

| Entry point | What it returns |
|---|---|
| `LpPolygon(points)`, `LpPolygon.of(line)`, `LpPolygon.regular(sides, radius)` | a strictly convex polygon, counter-clockwise, as vertices and edge half-planes |
| `polygon.centralPath(direction, weights, samples, muHigh, muLow, start, warm)` | the log-barrier central path, `LpTrajectory`; `warm` reuses a previous path's samples as Newton starts |
| `LpContinuation().centralPath(polygon, direction, ...)` | a central path warm-started from the previous call's, for animation |
| `polygon.centralFan(angles, samples, weights)` | one central path per direction round the circle, `List<LpTrajectory>` |
| `polygon.primalDual(direction)` | Mehrotra predictor–corrector iterates, `LpTrajectory` |
| `polygon.pdhg(direction, iterations, tau, sigma, theta)` | `LpPdhgTrace`: the primal walk and every dual vector |
| `polygon.ellipsoid(direction, iterations)` | `LpEllipsoidTrace`: centres and `LpEllipse`s |
| `polygon.simplexWalk(direction, startVertex)` | the boundary walk, `LpTrajectory` |
| `polygon.cuttingPlanes(direction, iterations, center)` | `LpCuttingPlaneTrace`: centres and remaining regions; `center` is `ANALYTIC`, `CENTROID`, `CHEBYSHEV` or `VOLUMETRIC` |
| `trajectory.toFLine()`, `fan.toFLines()`, `ellipse.toFLine()`, `polygon.toFLine()` | drawable `FLine`s |

</div>

??? aside "Agents: what pins these figures"
    `src/jvmTest/kotlin/afield/geometry/spatial/LpTrajectoriesTest.kt` asserts what the captions
    show: strict feasibility of the central path and primal-dual iterates, convergence of every
    method to the optimal vertex, the `16/27` determinant ratio of successive ellipses (the 2-D
    central-cut factor is `n²/(n²−1) = 4/3`, not `16/9`), that each ellipse still contains the
    optimum, that weights move the path, the Chebyshev centre against a rectangle and a 3-4-5
    triangle, the volumetric centre's vanishing gradient, and that a warm-started path matches the
    cold one in at most 85% of the Newton steps. Figures pass `yUp = true` to `showPlaneGrid` and
    `showPlane`, so +y is up the page, as in the maths. The animated starting point is
    `dev_docs/agents/transcripts/lp_curves.yaml`.

Source: `src/jvmMain/kotlin/afield/geometry/spatial/lp_trajectories.kt`.
