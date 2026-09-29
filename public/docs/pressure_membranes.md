# Pressure membranes

`afield.four.physics.pressure` relaxes a supplied closed triangular membrane with
elastic reinforcement, tethers, and supports. It is a mesh mechanics library:
it does not choose an architectural shape, infer tape connectivity, or generate
sewing patterns. Those steps belong to the caller.

Supply positions in metres and, for each triangle, its **unstretched material
coordinates** in metres. Material coordinates determine strain independently of
the initial spatial embedding. A curved initial surface is not automatically a
stress-free or cuttable reference surface.

## A complete small example

This deliberately coarse octahedron is a mechanics example, not a fabrication pattern.

```diffstatic {data-lang=kotlin}
import afield.four.physics.pressure.*

val points = listOf(
    P3(1.0, 0.0, 0.0), P3(-1.0, 0.0, 0.0),
    P3(0.0, 1.0, 0.0), P3(0.0, -1.0, 0.0),
    P3(0.0, 0.0, 1.0), P3(0.0, 0.0, -1.0)
)
// Explicit illustrative material; not a product specification.
val film = Film(young = 1e6, poisson = .3, thickness = .1, density = 100.0)
val triangles = listOf(0, 1).flatMap { a ->
    listOf(2, 3).flatMap { b ->
        listOf(4, 5).map { c ->
            val ids = if ((points[b]-points[a]).cross(points[c]-points[a]).dot(points[a]) > 0)
                listOf(a,b,c) else listOf(a,c,b)
            val e = points[ids[1]]-points[ids[0]]
            val q = points[ids[2]]-points[ids[0]]
            PanelTriangle(ids[0], ids[1], ids[2], "skin",
                doubleArrayOf(0.0, 0.0, e.norm(), 0.0,
                    q.dot(e.unit()), q.cross(e.unit()).norm()), film)
        }
    }
}
val envelope = Envelope(points.flatMap { it.array().toList() }.toDoubleArray(), triangles)
val solution = InflationSolver.solve(envelope, LoadCase(pressure = 1000.0))
println("${solution.reason}; residual ${solution.residualN} N")
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

Independent tests cover analytic force derivatives, packed/reference agreement across
material branches, sealed gas, translation invariance, mesh validation, cancellation,
and convergence toward the spherical Laplace pressure law under mesh refinement.
