# Field → Manifold → Kratos: embedded thermal prototype

An optional persistent Python worker solves stationary conduction inside a cuboid
assembly on a fixed tetrahedral background. Moving/resizing a cuboid changes the
nodal distance and cut integration, **not the background nodes or elements**.
Field returns the temperature as `DoubleArray` data and draws derived `TriMesh`
views using its existing graphics system. This is a small thermal experiment,
not a general simulation DSL or a structural analysis backend.

The measured outcome and validation artifacts are recorded in
[VERIFICATION.md](VERIFICATION.md).

## Repository integration points

The Kotlin client lives in `src/jvmMain/kotlin/afield/kratos`, following the existing
JVM package layout. It adds no imports or startup hooks to Field core and no
Gradle dependency. The existing `org.msgpack:msgpack-core` dependency is reused.
`afield.manifold.Solid` supplies native CSG, explicit closing and the existing
flat-array `TriMesh` conversion; `mesh_measure.kt` supplies the BVH evaluator.
`showMeshGrid` supplies the existing Three.js rendering path.

The lazy Jolt bootstrap, optional Ceres backend and separate Field Audio client
establish the capability/environment boundary; the Vision geometry preview
establishes the mesh-rendering boundary. The audio client also demonstrates
off-runloop futures and binary MessagePack numerical fields. Kratos uses standard
JVM process/socket/byte-buffer facilities directly because it needs one owned
process and a different session protocol. Mapped storage and direct Vulkan
upload remain possible extensions; neither is needed for these small arrays.

## Install and run

For an interactive frontend demo, open **kratos_thermal_playground** from Field's starting points,
select **Kratos thermal playground** and launch it (Alt-R). Drag the four sliders to resize the
cuboid arm or change conductivity, heating and skin temperature. **Solve now** bypasses the preview
debounce; **Cancel pending** invalidates pending results. Field's Stop action closes the worker.
One launch retains one background domain; relaunching creates a new session. The first solution
stays beside the current solution for comparison, with numerical differences and timings below.

The editable source is [the OperationTranscript](../../dev_docs/agents/transcripts/kratos_thermal_playground.yaml).
It can also be installed in the current document with `macro write kratos_thermal_playground --file
dev_docs/agents/transcripts/kratos_thermal_playground.yaml` followed by `macro run kratos_thermal_playground`.
The standalone [cuboids.kt](examples/cuboids.kt) remains the automated two-edit example.

Reference: Linux x86_64, glibc ≥2.28, CPython 3.12.13, Kratos core and
ConvectionDiffusionApplication 10.4.0, NumPy 2.2.6, MessagePack 1.1.1.
`uv.lock` pins the optional environment independently of Field's Python project.
The verified formulation needs **no source build, body mesher, or additional
LinearSolversApplication**: the first low-resolution profile uses core Skyline LU.

```bash
uv sync --project optional/kratos --frozen
export AFIELD_KRATOS_PYTHON="$PWD/optional/kratos/.venv/bin/python"
OMP_NUM_THREADS=2 uv run --project optional/kratos python -m unittest discover -s optional/kratos/tests -v
./gradlew jvmTest --tests 'afield.kratos.KratosTest'
uv run --project . --group backdoor python tools/run_field_tests.py --id kratos.embedded.thermal --port 9677
```

Field's normal build never installs this environment or imports Kratos. The
client uses Field's existing MessagePack dependency. `Kratos.probe()` returns a
future with availability, installation guidance, Python executable/version,
platform, actual import results and package versions. A missing executable,
script, native dependency, timeout or incompatible handshake produces an
unavailable report. Runtime tests print an explicit skip when the optional
Python environment is absent; protocol/array/missing-worker tests still run.

Launch [examples/cuboids.kt](examples/cuboids.kt) in one Field box. It displays the
existing Manifold surface, solves, resizes an arm, rejects an intervening obsolete
preview, and shows interior temperature slices in Field's interactive Three.js
mesh viewer. It prints both timings, mesh identity, build count, changed node
count, residual and solution delta. The worker closes in `finally`.

For an automated real browser capture, from the repo root:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9678 \
  --command 'new-box --name Kratos' \
  --command 'launch @last --file optional/kratos/examples/cuboids.kt' \
  --command 'wait-output @last --contains KRATOS_DEMO_COMPLETE --timeout 60' \
  --command 'output @last' \
  --command 'screenshot-box @last /tmp/field4-kratos.png --padding 16'
```

Check `KRATOS_DEMO_COMPLETE`, errors and the actual screenshot. `wait-output`
asserts completion and fails early on a compiler/runtime error.

## Small Field-facing API

```kotlin
import afield.kratos.*

val capability = Kratos.probe() // asynchronous; inspect when complete
val opening = KratosSession.openAsync(
    SimulationBounds(-1.0,-1.0,-1.0,1.0,1.0,1.0), resolution=12)
// In a Field Frames scope: while (!opening.isDone) frame()
val experiment = opening.get() // only after completion on Field's runloop
val pending = experiment.solveAsync(KratosDemo.body(),
    ThermalParameters(conductivity=2.0, source=100.0, boundaryTemperature=293.15))
// Yield while pending. Publish on Field's runloop using:
experiment.withLatest { result ->
    println(result.sample(0.0,0.0,0.0))
    // showMesh(result.sliceMesh()) in the enclosing box
}
// finally: experiment.close()
```

`SimulationBox` and `SimulationCSG` retain primitive IDs and evaluate analytic
constructive distances in the worker. `surface()` uses the **existing**
`Solid.cube`, transforms, Boolean operations and `Solid.toTriMesh`, closing
intermediates. `ManifoldGeometry(id, solid)` snapshots that existing conversion
and reuses `TriMesh.bvh().signedDistance` on the Field executor. The caller still
owns the original Solid. The bridge negates Field's negative-inside distance to
Kratos's positive-inside convention. Both paths feed identical nodal distances
to the same elements. CSG min/max preserves signs and the zero surface; it is
not generally the exact Euclidean distance to a Boolean result.

Only one volume material and one uniform embedded Dirichlet boundary are
supported: their semantic identities are `ThermalParameters.materialId` (default `body-material`), `<solid-id>:skin` and
`ThermalParameters.boundaryConditionId` (default `skin-temperature`). The volume
ID is `<solid-id>:volume`, and the returned field ID is `temperature`. These IDs
travel with the request and result diagnostics. `SimulationProbe(id,x,y,z)`
returns a named, revision-tagged sample; histories can retain those samples. A primitive's authored ID survives resizing; removing it
removes that selection. Boolean split pieces retain the authored operand
identity conceptually, but **this slice does not export per-face provenance or
support independent boundary selections**. The current Manifold conversion
exports positions/normals/indices, not its native run/face property channels.
Do not claim exact triangle ancestry from this conversion. Predicate/painted
regions, per-material interfaces and disappearing-region policy beyond the
single whole-body region need a later schema.

`SimulationEdit` describes geometry, material, boundary or load edits. The caller
must mark a changed shape as `GEOMETRY`. Geometry increments both revisions;
other edits increment the simulation revision. The worker reuses the analytic
distance values when the expression is identical; active/cut state is currently
recomputed globally and algebra is rebuilt for every solve. `dirtyBounds` should include old/new bounds expanded by the evaluator's distance
support radius. It is a
reserved spatial invalidation hint: schema 1 accepts it but reports a full
classification (or reused distance), never pretends it did tiled updates.
Changing domain bounds/resolution requires a new session explicitly.

`KratosState` exposes immediate geometry preview, debounced coarse preview,
committed solve, ready, failed and closed. Preview debounce is 120 ms; the
session's fixed resolution determines its cost. Use separate coarse and
committed sessions to select different resolutions. `latest` is invalidated
immediately by an edit. `withLatest` guards scene publication under the same
revision lock. A future returning a result is historical data, not perpetual
permission to display it after another edit. Cancellation discards queued and
in-flight results; the serialized native solve can finish. Timeout/crash closes
the socket and owned process, and the error directs the caller to open a new
session. There is no automatic replay of possibly invalid solver state.

`KratosConfig` configures executable, worker path, deadlines and OpenMP threads.
`setupScript` is an explicit trusted Python escape hatch. It is loaded once with
`runpy` and may define `before_solve(domain, request)` to inspect the ModelPart,
alter advanced request parameters, or experiment with alternative setup.
This executes arbitrary local Python with the user's permissions. Hooks must
preserve topology to retain the no-remesh contract; user-provided hooks are
outside the verified reference profile.

## Formulation decision and source evidence

Investigated current master `1e6822f8d5bf1352b0b4bf2820257c33c33f6501`, installed
10.4.0 Python code and native wheels. The embedded element source is byte-for-byte
identical between that master snapshot and v10.4.0 (SHA256
`f8d4bd83b96159449eba4147cdc762f815cf1eaafe07371ff1dae13d2a905729`).
PyPI currently lists 10.4.3; 10.4.0 is the environment actually verified here,
not a claim about the newest release. Sources below identify the exact seam.

| Question | Evidence and decision |
| --- | --- |
| Smallest credible formulation? | [ConvectionDiffusion embedded tests](https://github.com/KratosMultiphysics/Kratos/blob/v10.4.0/applications/ConvectionDiffusionApplication/tests/test_convection_diffusion_embedded_solver.py) solve an analytically defined circle with a source and embedded Dirichlet temperature, including small-cut variants. Thermal is credible; no need to switch to fluid for the first proof. |
| What geometry does it accept? | [Embedded stationary solver](https://github.com/KratosMultiphysics/Kratos/blob/v10.4.0/applications/ConvectionDiffusionApplication/python_scripts/convection_diffusion_stationary_embedded_solver.py) registers historical nodal `DISTANCE`, supports simplex 2D3N/3D4N elements and deactivates negative elements. It does not itself need a triangulated skin. |
| What identifies cut/active elements and BCs? | [EmbeddedLaplacianElement](https://github.com/KratosMultiphysics/Kratos/blob/v10.4.0/applications/ConvectionDiffusionApplication/custom_elements/embedded_laplacian_element.cpp) derives split geometry from signed nodal distances at assembly, integrates the positive side, and uses elemental `EMBEDDED_SCALAR` with `PENALTY_COEFFICIENT` for Nitsche Dirichlet terms. The bridge returns cell states 0 inactive, 1 positive, 2 cut. |
| Same ModelPart after an edit? | Element geometry is recomputed from distances when assembling. Our edit/restore test retains the identical ModelPart, coordinates, connectivity and background UUID, and reactivates elements and nodes as well as deactivating them. Upstream solver initialization alone only deactivates and is insufficient for repeat moving geometry. |
| Reusable solver structures? | [Convection-diffusion base solver](https://github.com/KratosMultiphysics/Kratos/blob/v10.4.0/applications/ConvectionDiffusionApplication/python_scripts/convection_diffusion_solver.py) constructs scheme, builder and linear strategy. This bridge retains mesh/model and cached analytic distances, recreating DOF numbering, matrix and factorization. Same-geometry load/BC changes could reuse the operator; this is not yet implemented or measured. |
| Partial classification? | The distance is nodal and element cuts are local, so dirty-node updates are possible in principle. Current bridge loops over all nodes/elements. Neighbor/MLS dependencies would require an expanded support region; tiled updates are not claimed. |
| Surface sampling? | Use barycentric interpolation in the exact six-tetrahedra cube subdivision. `sample` and `surfaceMesh` preserve raw values; unsupported skin vertices are grey. This avoids a new locator for this structured mesh. A different discretisation will need its own point locator. |
| Available Linux wheels? | [Core](https://pypi.org/project/KratosMultiphysics/10.4.0/#files), [ConvectionDiffusion](https://pypi.org/project/KratosConvectionDiffusionApplication/10.4.0/#files), [FluidDynamics](https://pypi.org/project/KratosFluidDynamicsApplication/10.4.0/#files), and [LinearSolvers](https://pypi.org/project/KratosLinearSolversApplication/10.4.0/#files) all publish CPython 3.12 manylinux_2_28_x86_64 wheels. Only the first two were installed/required here. Other physics applications/platforms are not certified. |
| Existing communication layer? | [CoSimulation IO](https://github.com/KratosMultiphysics/CoSimIO) supports coupling, mesh/data exchange and connection management. It is a plausible later external-solver coupling path. For one owned Python worker, one session and bounded binary replies, a dedicated authenticated loopback socket is smaller and avoids an additional coupling lifecycle. CoSimIO was inspected conceptually, not benchmarked. |
| Extension limits? | This scalar stationary cut Laplacian does not supply constitutive mechanics, transient storage, contact or CFD stabilization. [FluidDynamics application](https://github.com/KratosMultiphysics/Kratos/blob/v10.4.0/applications/FluidDynamicsApplication/README.md) has its own embedded flow formulation and is a better next investigation than extrapolating this element to elasticity. |

## Protocol and ownership

See [protocol.md](protocol.md). TCP listens on an ephemeral **loopback** port in
Field; the child connects back with a per-process random token. Native logs go
to an owned temporary directory, outside the control stream. Requests and replies
carry session, request, geometry/simulation revision, schema, units and cancellation
state. Replies also carry `obsolete`. Arrays use framed MessagePack binary blocks
with explicit float64/int32 little-endian descriptors; no vertex/field arrays
pass through JSON or boxed numerical collections. Maximum frame size: 64 MiB.

Positions and connectivity transfer once at background creation. Each result
transfers temperature, distance and cell state. Buffers are retained as primitive
arrays for Kotlin computation and eventual Vulkan buffer upload. This first
transport uses owned in-process bytes rather than mapped files, so there are no
shared-file deletion races. Closing cancels queued futures, shuts down the owned
worker, closes the socket and removes temporary logs. A JVM shutdown hook also
closes owned processes. No machine-wide process cleanup is used.

## Evaluation and limits

Run `uv run --project optional/kratos python optional/kratos/examples/standalone.py --output /tmp/thermal.npz`
to exercise the actual socket worker without Field and retain the numerical data.

The standalone 12³-domain proof has 2,197 nodes and 10,368 tetrahedra. Resizing
`arm` from x=0.71 to x=0.43 changes 30 nodal classifications; active elements
change from 2,952 to 2,676. With k=q=1 and zero boundary temperature, the maximum
nodal solution change is about 0.04293. Edit/restore recovers the first solution
within 1e-12 without rebuilding the model. Timings vary; see the actual test and
smoke output for current numbers rather than using these as latency promises.

Analytic check: sphere radius 0.75, uniform unit source/conductivity, zero boundary,
`T=(R²-r²)/6`. At resolution 12, interior nodal RMS error is about 0.000361.
The tests also verify inverse conductivity scaling and a boundary-temperature
shift. The direct linear residual is checked against 1e-7. This residual checks
the algebra, not geometric/continuum accuracy or physical units chosen by a user.

Geometry transfer, Field-side skin evaluation, classification, model update,
assembly, solve, result packing, result transfer, round-trip and visualization submission are
reported separately. Round-trip includes worker computation; do not add it to
the component times. Browser paint completion is not part of the submission
measurement. Temperature has one value per node, including extension values at
negative nodes of cut elements; only positive interior values define extrema.

At this grid size, the initial coordinate/connectivity payload is 218,616 bytes;
a result is 76,624 bytes. Binary payloads temporarily coexist with decoded arrays.
Kratos's algebra/native allocations are not included in these byte counts. The
Skyline profile is capped at resolution 32 to avoid implying that dense-band
factorization scales to the brief's illustrative resolution 96.

Small cuts can be ill-conditioned; the reference uses a Nitsche penalty of 10
and avoids exact zero distances. It does not implement MLS stabilization or
adaptive refinement. Thin regions with no positive grid node are rejected or
unresolved, and all geometry must lie strictly inside the domain. Distance-linear
cut boundaries round/shift cuboid corners within grid cells. Interior slice
rendering omits triangles crossing its sampled boundary; that display crop is
not the authoritative cut quadrature. The prescribed-temperature skin is nearly
constant, so interior slices are the useful thermal visualization.

Next work should first evaluate small-cut stabilization and a sparse solver at
higher resolutions, then measure operator reuse for load/BC edits. Embedded flow
is the best-supported next physics candidate from this investigation, but needs
its own maintained example, benchmark and boundary semantics. Body-fitted
committed solves can be a separate future domain implementation; no mesher is
introduced by this prototype.
