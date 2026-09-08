# Embedded thermal vertical slice — verification

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
