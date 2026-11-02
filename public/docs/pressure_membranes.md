# Pressure membranes

Give Field a closed triangular mesh and you can relax it as a pressurized membrane, with elastic
reinforcement, tethers and supports. The `afield.simulation.physics.pressure` library handles the
mesh mechanics; you supply the shape and connections, and make any sewing patterns you need.

Supply positions in metres and, for each triangle, its **unstretched material
coordinates** in metres. Material coordinates determine strain independently of
the initial spatial embedding. A curved initial surface is not automatically a
stress-free or cuttable reference surface.

## A complete small example

This example inflates a coarse octahedron to 1000 Pa and prints why the solver stopped and the
remaining force residual. The octahedron is only there to exercise the solver; it is not a
fabrication pattern.

```diffstatic {data-lang=kotlin}
import afield.simulation.physics.pressure.*

val points = [
    P3(1.0, 0.0, 0.0),
    P3(-1.0, 0.0, 0.0),
    P3(0.0, 1.0, 0.0),
    P3(0.0, -1.0, 0.0),
    P3(0.0, 0.0, 1.0),
    P3(0.0, 0.0, -1.0),
]
// Explicit illustrative material; not a product specification.
val film = Film(young = 1e6, poisson = .3, thickness = .1, density = 100.0)
val triangles =
    [0, 1].flatMap { a ->
        [2, 3].flatMap { b ->
            [4, 5].map { c ->
                // Choose outward winding using the face normal and its position from the origin.
                val ids =
                    if ((points[b] - points[a]).cross(points[c] - points[a]).dot(points[a]) > 0) [a, b, c]
                    else [a, c, b]
                val e = points[ids[1]] - points[ids[0]]
                val q = points[ids[2]] - points[ids[0]]
                // Project the rest triangle into its local, unstretched 2D material frame.
                PanelTriangle(
                    ids[0],
                    ids[1],
                    ids[2],
                    "skin",
                    doubleArrayOf(
                        0.0,
                        0.0,
                        e.norm(),
                        0.0,
                        q.dot(e.unit()),
                        q.cross(e.unit()).norm(),
                    ),
                    film,
                )
            }
        }
    }
val envelope = Envelope(points.flatMap { it.array().toList() }.toDoubleArray(), triangles)
val solution = InflationSolver.solve(envelope, LoadCase(pressure = 1000.0))
println("${solution.reason}; residual ${solution.residualN} N")
```

## A finer mesh rounds out

Six vertices cannot show much shape: the octahedron above inflates into a slightly larger
octahedron. Cut each face into 36 flat triangles and give it a thinner film, and the same solver
pushes the flat facets out toward a sphere. The left drawing is the mesh as built, the right one
`solution.positions`, both seen from the same side:

```diffstatic_runner {data-lang=kotlin screenshot="assets/pressure_membranes/finer_mesh.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" w="760" timeout="180" embed=true name="pressure-membranes-finer-mesh" alt="a status line reading 146 nodes, 288 triangles: force residual satisfied at full pressure, with the residual in newtons; below it two wireframe diamonds side by side, the left one with straight edges and flat facets, the right one the same mesh bulged outward into a rounded, nearly spherical outline"}
import afield.simulation.physics.pressure.*

// An octahedron with each face cut into n x n flat triangles, so the pressure has facets to round out.
val n = 6
val corners = [P3(1.0, 0.0, 0.0), P3(-1.0, 0.0, 0.0), P3(0.0, 1.0, 0.0), P3(0.0, -1.0, 0.0), P3(0.0, 0.0, 1.0), P3(0.0, 0.0, -1.0)]
val points = mutableListOf<P3>()
val index = mutableMapOf<String, Int>()
fun node(p: P3): Int {
    val key = [p.x, p.y, p.z].map { Math.round(it * 1e6) }.toString()
    return index.getOrPut(key) { points.add(p); points.size - 1 }
}
val film = Film(young = 1e6, poisson = .3, thickness = 1e-3, density = 100.0)
val triangles = mutableListOf<PanelTriangle>()
fun face(a: P3, b: P3, c: P3) {
    // Outward winding, as in the small example.
    val (p, q, r) = if ((b - a).cross(c - a).dot(a) > 0) Triple(a, b, c) else Triple(a, c, b)
    fun at(i: Int, j: Int) = node(p + (q - p) * (i.toDouble() / n) + (r - p) * (j.toDouble() / n))
    fun tri(i0: Int, i1: Int, i2: Int) {
        val e = points[i1] - points[i0]
        val f = points[i2] - points[i0]
        triangles.add(PanelTriangle(i0, i1, i2, "skin",
            doubleArrayOf(0.0, 0.0, e.norm(), 0.0, f.dot(e.unit()), f.cross(e.unit()).norm()), film))
    }
    for (i in 0 until n) for (j in 0 until n - i) {
        tri(at(i, j), at(i + 1, j), at(i, j + 1))
        if (i + j < n - 1) tri(at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
    }
}
for (a in [0, 1]) for (b in [2, 3]) for (c in [4, 5]) face(corners[a], corners[b], corners[c])

val envelope = Envelope(points.flatMap { it.array().toList() }.toDoubleArray(), triangles)
val solution = InflationSolver.solve(envelope, LoadCase(pressure = 200.0))
markdown("${points.size} nodes, ${triangles.size} triangles: ${solution.reason}; residual ${solution.residualN} N")

:::fold draw the mesh before and after, side on
fun wire(xyz: DoubleArray, dx: Double) =
    triangles.map { t ->
        FLine().apply {
            [t.a, t.b, t.c, t.a].forEachIndexed { k, i ->
                val x = dx + (xyz[3 * i] - xyz[3 * i + 2] * 0.35) * 120.0
                val y = (xyz[3 * i + 2] * 0.2 - xyz[3 * i + 1]) * 120.0
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }

:::endfold
lines(wire(envelope.initial, 0.0) + wire(solution.positions, 340.0), w = 680.0, h = 300.0, fitToSize = true)
```

## Loads, results, and stopping

- `Film` takes Young's modulus in Pa, thickness in metres, density in kg/m³,
  and Poisson's ratio. There is no implicit Mylar material.
- `Tape` and `Tether` use axial rigidity EA in N and unstretched length in metres.
  They carry tension only. A `SurfacePoint` attaches them through fixed material
  barycentric weights. Tapes relax with the membrane; their starting paths are not constraints.
- `Anchor` fixes a node, or supplies a spring stiffness in N/m. `LoadCase` also
  supports gravity in m/s² and a ground penalty in N/m. Tether targets and anchor
  locations are in the same coordinates as the mesh.
- Pressure is gauge pressure in Pa. The default is zero. `REGULATED` holds the
  requested pressure; `SEALED_ISOTHERMAL` holds pV using the initial volume.
- `Solution.positions` contains the relaxed mesh. Film major/minor resultants are
  N per current metre; tape tension and support reactions are N. They are not
  material strength margins. Film `strain` is the largest principal stretch minus one.
- Pressure continuation records accepted and rejected endpoints in `history`.
  Check `converged` and the actual history fraction before interpreting full-load results.
  `initialPositions` starts from a supplied full-load state. Cancellation and progress
  callbacks allow a host application to own execution and UI.

The solver uses analytic forces, L-BFGS, and optional Newton refinement. `FastMechanics`
is a packed equivalent evaluator; `Mechanics` supplies the reference implementation
and detailed surface values. Keep model arrays unchanged during a solve.

## What the model establishes

The membrane uses a plane-stress St Venant–Kirchhoff elastic law with relaxed
compressive principal directions: taut, uniaxially wrinkled, or slack. Large rotations
are supported, but this is not a calibrated large-strain material model. There is no
bending stiffness, wrinkle wavelength, plasticity, rupture, or self-contact response.

Force convergence establishes a stationary state. `MinimumSearch` can probe curvature
and restart along a sampled unstable direction; it does not certify a global minimum.
`ShapeAudits.inspect` reports triangle intersections and sharp folds as diagnostics,
not as collision prevention. Refine meshes and compare physical quantities before
trusting a visually smooth result.

??? aside "What the tests cover"

    Independent tests cover analytic force derivatives, packed/reference agreement across
    material branches, sealed gas, translation invariance, mesh validation, cancellation,
    and convergence toward the spherical Laplace pressure law under mesh refinement.
