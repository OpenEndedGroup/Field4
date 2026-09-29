# Inflatable study: core boundary

Field owns reusable mechanisms, with no dependency on `src/personal`:

| Field core | Private architectural study |
| --- | --- |
| Closed membrane mechanics, pressure continuation, flexible reinforcement and tethers | Miralon/PET study assumptions, half-atmosphere preset |
| Mesh force evaluation, residuals, sampled minimum and intersection diagnostics | FLine carrier networks, sphere-topology assembly, throat and lobe construction |
| Optimizer observers, stopping, trajectory control and dense vector kernels | Gore/patch extraction, material-map repair, topology-preserving draft snapping |
| Three.js panel cameras, dolly, picking, editing commit callbacks and unlit colors | Study-specific selection, stress legends, draft history and saved solutions |
| Output replay, responsive control groups, configurable JVM heap | Architectural documents, experiments and evidence |

Core pressure API: `afield.four.physics.pressure`. See `public/docs/pressure_membranes.md`.
The private `inflatables.Physics.kt` exposes compatibility type aliases and explicit
`studyFilm`, `studyLoad`, and `studyTape` factories. It contains no solver. Private
shape diagnostics delegate generic checks and retain only the network seam check.

Promote another private utility only when it has an independent input/output contract,
no study globals or presets, a non-architectural example, and focused verification.
The carrier mesher and inverse pattern construction do not yet meet that boundary.
A generic pressure engine does not imply that architectural fabrication or stability
has been validated.

## Runtime heap

`./gradlew runJvm -PfieldMaxHeap=24g` or `AFIELD_MAX_HEAP=24g ./field.sh ...` selects
an explicit positive JVM maximum heap. The default remains 8g. This setting is useful
beyond the study and does not hard-code the current machine's RAM size.

## Verification of extraction

- Focused JVM coverage: membrane forces and mesh refinement, optimizer and SIMD
  equivalence, retained outputs, FLine commits and Three.js camera/picking logic.
- Complete viewer module parsing guards integration across independently tested regions.
- The public octahedron example ran in a clean Field without personal sources and
  rendered before and after a browser reload.
- The private layer compiles using the aliases. Its saved architectural mesh passes
  reference/packed force comparisons, direct Newton refinement and cancellation checks.
- Public reference generation and KDoc attachment checks were run.

The original private checkpoint and saved document/draft bytes are retained. No
architectural material, fabrication or global-minimum certification is implied.
