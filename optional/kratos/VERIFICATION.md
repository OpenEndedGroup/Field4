# Kratos in Field — verification

## Body-fitted structural, via TetGen (2026-09-08)

`structural.bodyFitted` is the third formulation and the first that is not embedded, because
Kratos has no immersed structural element. It solves a tetrahedral mesh Field produces from the
Manifold surface with `afield.tetgen.Tetgen`, sent over the new `createBodyMesh` verb.
`dev_docs/TETGEN.md` carries the mesher comparison that chose this route; this section records the
solve.

**Meshing**, natively on macOS, no container: a 2x3x4 cube meshes to a volume of
`23.999999999999936` against an exact `24.0` — relative error `2.7e-15`, which is the sharp-edge
claim measured rather than asserted. The L-bracket used in the end-to-end run meshes to 1,434 nodes
and 5,912 tetrahedra in 67 ms, volume `0.003968000329971304` against Manifold's
`0.003967999999999999`.

**Validation** against closed-form beam theory, in the worker:

| case | 10x2x2 | 20x3x3 | 30x4x4 |
| --- | --- | --- | --- |
| axial extension, `FL/EA` | 99.44% | 100.04% | — |
| cantilever under self-weight, `wL⁴/8EI` | 35.6% | 62.0% | 75.9% |

Axial is correct, which pins the material, the clamp, the point loads and the solver. The bending
shortfall is the constant-strain tetrahedron locking — a property of the element, converging from
below. It is characterised in the test suite rather than asserted away, and quadratic elements are
the fix.

**End to end from a real Field browser session** on macOS driving the container worker: an
L-bracket clamped at the base of its post, under gravity plus 4 kN on the arm, 1,434 nodes and
5,912 tetrahedra, 33 clamped nodes and 68 loaded. Peak displacement 0.250 mm, peak von Mises
23.8 MPa, relative linear residual 3.3e-11. Assembly 204 ms, solve 771 ms, stress recovery 272 ms —
all emulated. Evidence: `build/kratos-verification/structural.png` (gitignored), visually inspected;
the stress band along the re-entrant corner is the feature the body-fitted route exists to
reproduce.

**Coverage**: 6 Kotlin TetGen tests, including a five-faced cube proving that TetGen's fatal-error
exception is caught rather than aborting the VM; 23 Python worker tests, of which 4 are structural.

Two test expectations were wrong on first writing and are worth recording, because both would have
been easy to "fix" by loosening the wrong thing. Asserting that two identical thermal solves return
**bit-identical** bytes fails: Kratos assembles with OpenMP atomics, so the summation order varies
and the last ulp with it. And asserting axial extension exact to 2 decimal places fails at the
coarse mesh at 99.44%: the total force is split equally between end-face nodes regardless of
tributary area, which is a Saint-Venant end effect that shrinks on refinement to 100.04%.

## Embedded flow, the second formulation (2026-09-08)

`flow.embedded` runs `EmbeddedWeaklyCompressibleNavierStokes3D4N` on the same background grid, the
same protocol, the same revision gate and the same transport as the thermal slice. Nothing outside
`python/formulations/flow.py` and `FlowParameters` knew it was being added.

The answer is physical, which is the claim worth making before any picture. Free stream 1.0 m/s
past a sphere at resolution 8:

```
x=-1.00  |u|= 1.000   p= +0.998      free stream
x=-0.62  |u|= 0.883   p= +1.300      slowing into the body
x=-0.25  |u|= 0.130   p= +1.746      stagnation
x=+0.50  |u|= 0.977   p= -0.709      wake
x=+2.00  |u|= 0.995   p= +0.000      recovered, outlet
```

`speedMax` is 1.454 against the exact potential-flow value of **1.5** at a sphere's equator. All
Newton steps converged, in 4 then 3 then 3 iterations.

Two findings, both recorded because neither is guessable:

- **The legacy element cannot be used.** `EmbeddedNavierStokes` zeroes the velocity rows of
  negative nodes and leans on the penalty term for a diagonal; on this configuration the first
  Newton step died with `THE MATRIX IS STRUCTURALLY SINGULAR ... ZERO COLUMN AT 6`. Upstream's own
  solver already names the weakly-compressible element its successor, and that one works.
- **Flow is not a preview.** One request marches `steps` steps of Newton–Raphson, so it costs
  seconds where conduction costs milliseconds — 24 s for 3 steps at resolution 8 under emulation,
  and roughly 1.5 s natively. The playground gives it a button; a debounced slider would be a lie
  about the latency.

19 Python tests pass, of which 4 are new: the reversed solved region (conduction inside the solid,
flow around it, from one expression), the wake and the acceleration, a geometry edit that keeps the
same `ModelPart`, and parameter validation. The wake assertion is deliberately loose — at
resolution 6 the deficit is 11% and at resolution 8 it is 82%, so the test pins that the obstacle
slows the flow, not by how much.

### The document

`dev_docs/agents/transcripts/kratos_flow_playground.yaml` is one box: four sliders, a Solve button,
the obstacle as a Manifold surface, streamlines integrated through the returned velocity field and
drawn as tubes coloured by speed, and a mid-plane slice coloured by speed. Captured from a real
Field browser session on macOS, driving the container worker:

```
READY · flow.embedded · revision SimulationRevision(geometry=1, simulation=1)
Background 14a0fa93-460a-4121-bcc6-4ff2d4fd0782 · builds 1 · 729 nodes · 3072 tetrahedra
radius 0.30 m · 3 steps of 0.08 s · Re 12
Speed 0.175–1.454 m/s · pressure -0.81 to 1.34 Pa
all Newton steps converged · Newton iterations [4, 3, 3]
Active cells 3060 · cut 186 · classification 32.4 ms
Newton loop 23930 ms · round trip 24971 ms
```

Evidence: `build/kratos-verification/flow-playground.png` (gitignored), visually inspected — real
controls, a real Manifold obstacle, streamlines that part around the body and a blue wake behind
it. Not attempted: driving the sliders headlessly, and any claim that these timings resemble a
native worker's.

## Formulation registry (2026-09-08)

The worker's physics moved into `python/formulations/`, behind a registry, with the tetrahedral
grid and the geometry classification in a Kratos-free `python/background.py`. `hello` now reports
every registered formulation, whether its applications imported, and the import error when they
did not; `createBackgroundDomain` takes a `formulation`, and `solve` takes `parameters` (with
`thermal` still accepted as the schema-1 spelling).

The check that matters for a refactor of verified code is that it changed no numbers. It did not:
the catalog smoke returns `changedNodes=30` and `maxTemperatureDelta=0.042929105837778495`, digit
for digit what it returned before, on the same 2,197-node background.

Geometry gained `sphere` and a capped `cylinder` at any axis, and an n-ary spelling of the
booleans. 15 Python tests pass (up from 8: three geometry, four registry) and 14 Kotlin tests
(up from 11: three geometry, including an off-axis cylinder whose bounding box and volume are
checked against closed form, since a wrong affine basis still yields a valid solid in the wrong
place).

**Not attempted, and not available upstream:** a transient embedded thermal solve. Kratos v10.4.0's
`python_solvers_wrapper_convection_diffusion` offers `transient` on standard elements and two
*stationary* embedded variants — `stationary_embedded` (the CutFEM/Nitsche one used here) and
`stationary_shifted_boundary`. There is no transient cut element, so "transient thermal" would mean
writing one, not binding one. The shifted-boundary variant is the available second thermal
formulation and is directly comparable to this one.

## Container transport, on a host with no Kratos wheels (2026-09-08)

The backend below was verified on Linux x86_64. This section is the same slice driven from an
Apple Silicon macOS checkout, where `uv sync --project optional/kratos --frozen` cannot succeed at
all — `uv` reports that Kratos publishes `manylinux_2_28_x86_64` and `win_amd64` wheels only.

`podman build --platform linux/amd64 -t field-kratos:10.4.0 optional/kratos` builds the reference
environment from the same `pyproject.toml` and `uv.lock`, and
`tools/run_field_tests.py --id kratos.embedded.thermal` passed against it in 23.5 s:

```
launch=Container(image=field-kratos:10.4.0, runtime=podman, platform=linux/amd64, …)
executable=/opt/field-kratos-env/.venv/bin/python
platform=Linux-6.8.8-300.fc40.aarch64-x86_64-with-glibc2.36
versions={KratosMultiphysics=10.4.0, KratosConvectionDiffusionApplication=10.4.0, numpy=2.2.6, msgpack=1.1.1}
NO_REMESH background=a9264268-… buildCount=1 changedNodes=30 maxTemperatureDelta=0.042929105837778495 staleRejected=true
```

The emulated run reproduces the native reference exactly where it should: 30 changed nodal
classifications, active elements 2,952 → 2,676, cut 1,708 → 1,528, `buildCount` 1, and a relative
linear residual of 3.39e-15. The maximum nodal change of 0.042929 matches the figure the standalone
proof records below to five digits.

It reproduces nothing about latency. Every instruction is emulated:

| Component | Native Linux | Emulated container | |
| --- | ---: | ---: | ---: |
| Classification | 1.03 ms | 20.06 ms | 19× |
| Model update | 92.35 ms | 1510.83 ms | 16× |
| Assembly | 10.35 ms | 184.23 ms | 18× |
| Linear solve | 10.52 ms | 176.80 ms | 17× |

The ratios between components are what survives, and they say the same thing in both places: the
full-grid Python-to-Kratos update dominates everything, including the solve. That is a per-entity
Python loop over nodes and elements, not Kratos work. `VariableUtils.SetSolutionStepValuesVector`
and `VariableUtils.SetFlag` are the bulk equivalents, and only the elements whose activity actually
changed need touching at all — 30 nodes moved here, against a loop over 2,197. Neither is
implemented; both are the first thing to try before any higher resolution.

Two traps, both now handled and both silent if they are not:

- `python:slim` has no `libgomp1`. Without it `import KratosMultiphysics` fails with
  `ImportError: libgomp.so.1` followed by Kratos's own "Unable to find KratosCore … check your
  LD_LIBRARY_PATH", which sends you looking in the wrong place.
- A container runtime publishes a port by running a host-side proxy that accepts connections **from
  the moment `run` starts** — before the image is unpacked and long before anything calls `bind()`.
  A first attempt connected successfully to a container whose Python had not started, sent `hello`,
  and read EOF. Field now waits for the worker's own `field-kratos listening on <port>` line.

Test coverage in this mode: 11 Kotlin tests pass, of which 5 are new — container and attach
guidance, and attaching to a closed port reported as a capability rather than a crash. The 5
real-worker Kotlin tests **skip** here and say so; three of them substitute a fault script for
`worker.py` and read the child's argv, which describes the local-process launch specifically, so
running them against a container would pass while testing something else. 8 Python tests pass in
the container, of which 2 are new and cover `--listen` binding and token rejection.

Unverified in this mode: the browser playground (not attempted), the five local-worker Kotlin
tests, and any claim that emulated timings bound a real interaction. Use a Linux checkout for
latency work.

## Frontend playground (2026-09-08)

The `kratos_thermal_playground` OperationTranscript is also a materialized frontend starting point.
Its box was launched in a real Field browser, then `examples/playground_check.js` drove the actual
slider and button pointer handlers. Two rapid arm edits (0.61 then 0.43 m), conductivity 2 → 4,
Cancel pending, and Solve now passed. The final arm remained 0.43 m; the same 2,197-node,
10,368-element background UUID remained throughout, with build count 1. Thirty node classifications
changed relative to the first solution. The final coefficient/committed solve reused classification;
model update was 75.7 ms, assembly 9.7 ms, solve 8.6 ms, and view submission 13.0 ms.
Stop reported the box no longer running and released its worker.

Final checks: 8 Kratos JVM tests, 4 control-surface tests and 6 Python tests passed. The transcript
compiled through Field; starting-point, index, layout, completion-corpus, KDoc and reference guards
passed. The broader citation gate finished with 87/88 tests passing: `PersonalBoundaryTest` rejects
existing local private packages that use the public `afield` namespace. Those private files were
not changed. This is a remaining local verification failure, not a clean full-suite result.

This check exposed two reorder points in Field's `/ws` control path: messages and callbacks each
launched independent coroutines. Slider/button messages now use an ordered consumer per connection,
and their callbacks start without another asynchronous dispatch. Socket reception remains independent
of that consumer. The live check verifies the latest geometry survives subsequent solves.

Evidence: `build/kratos-verification/playground-final.jsonl` and `playground.png` (visually inspected;
real controls, Manifold geometry and Kratos temperature slices). Earlier failed checks are retained
beside these files, including the wire trace proving the browser sent 0.61 before 0.43.

Reproduce from the checkout after installing the optional environment:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --port 9688 --timeout 150 \
  --command 'macro write kratos_thermal_playground --file dev_docs/agents/transcripts/kratos_thermal_playground.yaml' \
  --command 'macro run kratos_thermal_playground' \
  --command 'wait-output kratos_thermal --contains data-background --timeout 90' \
  --command 'page-eval --file optional/kratos/examples/playground_check.js --timeout 120' \
  --command 'stop kratos_thermal'
```

## Backend verification (2026-09-07)

Verified on 2026-09-07 against Field base `f97809a1` plus the local integration.
Reference worker: Linux x86_64, CPython 3.12.13, Kratos core and
ConvectionDiffusionApplication 10.4.0, two OpenMP threads. Installation used the
published wheels; no source build or body mesher was necessary.

## Results

The demonstrated background contains 2,197 nodes and 10,368 tetrahedra. Two
overlapping Manifold cuboids define the solid. Resizing the arm's maximum x from
0.71 to 0.43 changes 30 nodal classifications. Active elements change from 2,952
to 2,676, and cut elements from 1,708 to 1,528. The background UUID and build
count remain unchanged. The Python edit/restore check additionally asserts the
same ModelPart object, unchanged coordinates and unchanged connectivity, then
recovers the original temperature to within 1e-12.

With conductivity 2 W/(m K), source 100 W/m³ and skin temperature 293.15 K, the
Field example's maximum nodal change is 2.1465 K. Its edited interior range is
approximately 293.177–296.589 K. The screenshot was captured from the actual
Field/Three.js output using server-owned Playwright and inspected visually.

The final browser run recorded these worker times:

| Component | Original | Resized |
| --- | ---: | ---: |
| Classification | 1.03 ms | 1.01 ms |
| Model update | 92.35 ms | 78.19 ms |
| Assembly and algebra setup | 10.35 ms | 10.30 ms |
| Linear solve | 10.52 ms | 8.16 ms |
| Relative linear residual | 3.69e-15 | 3.53e-15 |

Field visualization submission took 63.2 ms for the two temperature panels;
this excludes asynchronous browser painting. The separate catalog smoke measured
95.46/88.26 ms request round-trips, with approximately 0.2–0.3 ms request
serialization/write and 0.02–0.06 ms result-payload read. Timings are individual
observations, not latency guarantees, and the round-trip already includes
worker computation. Preview adds a 120 ms debounce.

The standalone worker's measured peak RSS was 100,312 KiB (about 98 MiB), using
Linux `resource.getrusage(RUSAGE_CHILDREN)` after it exited. This covers the
worker, not the JVM/browser or aggregate simultaneous memory. Initial binary
geometry/connectivity costs 218,616 bytes, and each result costs 76,624 bytes.

## Verification coverage

- Eight Kotlin tests passed with no skips: missing worker; framing and binary
  shape/type/range validation; incompatible handshake; crash/timeout/restart and
  temporary-log cleanup; primitive/Manifold classification agreement; genuinely
  in-flight obsolete-result rejection; cancelled startup releasing its child;
  repeated solves, cancellation, named probes and a burst of 40 edits.
- Six Python tests passed: capabilities, domain creation, constructive
  classification, analytic thermal reference/coefficient scaling, edit/restore
  without mesh reconstruction, and structured errors with subsequent successful
  work in the same socket worker.
- `kratos.embedded.thermal` passed through the Field smoke catalog.
- A fresh Field started with `AFIELD_KRATOS_PYTHON=/no-such-kratos-python`.
  Capability probing reported unavailable and an ordinary Kotlin box printed 42.
- The final browser example reached `KRATOS_DEMO_COMPLETE`, asserted stale-result
  rejection and mesh identity, and produced a real screenshot.

The analytic test uses a sphere of radius 0.75 with unit source/conductivity and
zero skin temperature: `T=(R²-r²)/6`. Interior nodal RMS error at resolution 12 is
approximately 0.000361. A small linear residual verifies the discrete algebra;
this analytic check supplies a separate continuum reference.

These are focused integration checks, not a full repository certification.
The initial combined `run-jvm-main` then `new-box` verification invocation hit an
existing backdoor missing-scratch-session error. The standalone catalog smoke
and fresh browser session were run separately and both passed. That failed
combined invocation is retained in the local artifact directory.

## Artifacts and reproduction

Local generated artifacts live in `build/kratos-verification/` (gitignored):

- `cuboids.png`: final inspected browser capture.
- `field-demo-final.jsonl`: successful browser command results.
- `jvm-tests.xml`, `jvm-tests.log`, `python-tests.log`: focused test evidence.
- `smoke.log`, `smoke-results.json`: catalog result and numerical diagnostics.
- `absent.jsonl`: Field startup/capability/Kotlin execution without a worker.
- `standalone.json`, `standalone-memory.log`, `thermal.npz`: isolated worker
  diagnostics, peak memory and raw positions/cells/before/after/distance arrays.

Commands to recreate the tests and capture are in [README.md](README.md).
The final browser capture used port 9683; the catalog used 9682. The resulting
background UUID in the final capture was
`c575e76e-ea50-4773-be00-729a254589b4` with build count 1.

## What is reused and what remains limited

The actual ModelPart, nodes, elements and background arrays are retained across
geometry edits. Identical analytic geometry also reuses distance values on
coefficient/BC edits. DOF setup, the matrix and factorization are rebuilt.
Full-grid Python-to-Kratos updates dominate this small case; matrix reuse and
tiled classification are unimplemented, explicitly reported extensions.

The first formulation supports one scalar stationary thermal field, one volume
material and a uniform embedded Dirichlet temperature. Region IDs and named
probes are present, but per-face provenance/independent boundary selections,
transient histories, contact and structural constitutive physics are not.
Thin features and small cuts remain resolution/stability limitations. The
colored interior slice omits triangles crossing its sampled boundary; it is a
view of the raw field, not the cut quadrature mesh.

The next useful work is small-cut stabilization, a sparse solver profile and
operator reuse for load/BC changes. Embedded incompressible flow is the next
physics candidate supported by the source investigation, and needs a separate
formulation-specific proof before extending this API.
