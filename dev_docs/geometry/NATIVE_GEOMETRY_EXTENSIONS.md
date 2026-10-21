# Native geometry extensions: Phase 0 note

Status: reconnaissance done, nothing implemented yet. This covers three optional native modules:
Morse–Smale complexes, polyvector line vectorization (Bessmeltsev–Solomon), and principal-curve
forests.

## What we reuse

- **Binding: Panama FFM, copied from TetGen.** Field uses no JNI or JNA for optional natives.
  `afield/geometry/mesh/tetgen/Tetgen.kt` is the model to follow:
  - a `TetgenLibrary` object with a search path (`-Dafield.<x>.library`, then `AFIELD_<X>_LIBRARY`,
    then `build/field-<x>/build[/Release]`), `isAvailable`, `HOW_TO_BUILD` and `describe()`;
  - an internal `TetgenNative` that resolves its symbols lazily and throws a remedy-bearing
    `UnsatisfiedLinkError` when the library or a symbol is missing.

  Nothing native loads until first use, and the main Kotlin build never links a native library.
- **Native layout.** Each module gets `native/field-<x>/`, containing `CMakeLists.txt` and a C ABI
  in `src/`. Each also gets:
  - `tools/build_<x>_native.py`, which uses `native_download_cache.py` for any pinned source;
  - a `build<X>Native` Gradle task, wired into `buildDocsNativePrerequisites`;
  - a `NATIVE_PREREQUISITES` entry in `tools/build_zensical_docs.py`, so doc fences can say
    `requires="<x>"`.
- **Inputs.**
  - Scalar grids use `Img`, which holds a float `data` array and has a `channels` count
    (`afield/media/image/imaging.kt`).
  - Line evidence comes from the existing `canny`, `structureTensor` and
    `nonMaximumSuppression` (`imaging_features.kt`). The structure tensor is the natural seed
    for a frame field.
  - Point-cloud nearest-neighbour queries can use `vec2NN`/`vec3NN` for the Kotlin side.
- **Output.**
  - Curves become `FLine`, with per-node attributes in `Node.properties`.
  - Smoothing uses `fitHyperbezierCubics` (`Hyperbezier.kt`), applied only to the geometry, never
    to the topology.
- **Async.** Use `runAsync { CompletableFuture }` (`afield/runtime/lifecycle/AsyncOperation.kt`):
  native work runs on an executor, and callbacks are dropped once the box run that started it
  ends.
- **Tests and docs.** Tests skip with a printed `describe()` when the library is missing, as
  `TetgenTest` does. Each module gets a docs page modelled on `public/docs/tetgen.md`, a nav entry
  in `public/zensical.yml`, and demos under `dev_docs/agents/demos/`, registered as
  `AgentDemoRegistrationTest` requires.

## Proposed locations

| Piece | Location |
| --- | --- |
| Shared interchange (`GeometryNetwork`, FLine conversion, `NativeGeometry.available`) | `afield.geometry.network` (pure Kotlin, no native code) |
| Morse–Smale | `native/field-morse-smale/`, `afield.geometry.topology.morsesmale` |
| Polyvector | `native/field-polyvector/`, `afield.geometry.vectorize.polyvector` |
| Principal curves | `native/field-principal-curves/`, `afield.geometry.curves.principal` |

`GeometryNetwork` stores packed arrays: one coordinate `DoubleArray` per curve, and named
`DoubleArray` channels per node and per curve. It does not use per-vertex objects. The three
modules depend only on it, never on each other.

## Licensing (this decides what we write ourselves)

| Reference | License | Consequence |
| --- | --- | --- |
| TTK | BSD-3 | Can be used as a validation oracle. We write our own regular-grid discrete-gradient complex rather than pull in VTK. |
| DMSC | to audit | Read the algorithm only; do not embed its CUDA or PyTorch code. |
| bmpix/PolyVectorization | to audit before copying | Its dependencies (Qt, OpenCV, Boost, Eigen) are why the proposal asks for a separate reference build. The production path is a headless rewrite using Eigen (MPL-2). |
| SingularityFreeFrameFields | to audit | Evaluate only after the reference reproduces. |
| ElPiGraph | GPL-3 | Porting is approved (Marc, 2026-10-18). Field is AGPL-3.0+, and GPLv3 §13 allows combining GPL-3 with AGPL-3 code, so the port lives in `native/field-principal-curves/` as ordinary Field code. Keep ElPiGraph's copyright notices in the ported files. |

## Build risks

- Windows and Linux packaging are untested here. Like TetGen, the first reproducible target is
  macOS arm64.
- The polyvector reference build may not compile with current OpenCV and Qt. Isolate it under
  `build/`, and never make it a dependency.
- Benchmarks at 2048² (Morse–Smale) and 10⁶ points (principal curves) are a design constraint:
  a persistence hierarchy computed once and queried at any threshold, and a segment BVH for point
  assignment.
