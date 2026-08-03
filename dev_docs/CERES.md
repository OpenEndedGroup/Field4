# Ceres in Field: the build, the bindings, and a second polyhedron realizer

Field talks to [Ceres](http://ceres-solver.org) through a small C++ wrapper (`ken_ceres`) that
exports a flat C ABI, bound from Kotlin with `java.lang.foreign`. The Kotlin side describes a solve
as a *program* — a `DoubleArray` of opcodes and operands — and the native side compiles that once
into a `ceres::Problem` and then solves it repeatedly against changing point data.

This document is what a tire-kicking pass over that stack found, what changed, and what is still
open. Two things motivated it: the polyhedron realizer in `afield.stdlib.polytope` wanted a
second-order solver, and the wrapper looked like it should be general infrastructure rather than a
one-off for architectural constraint editing.

## The build, before and after

**Before.** `libs/libken_ceres_jvm.dylib` was a committed binary with no in-tree source. It was
built by hand from `~/temp/ceres`, a separate one-commit repository holding a lightly-hacked copy of
the whole Ceres source tree (hacked so it would compile under emscripten for the WASM build, which
is what that project was originally for). Editing a constraint meant editing C++ in an untracked
directory outside the repo and remembering how the build worked.
`dev_docs/PUBLIC_RELEASE_CHECKLIST.md` already flagged the committed dylib as unresolved.

Worse, that vendored Ceres was configured with

```
-DEIGENSPARSE=OFF -DSUITESPARSE=OFF -DCXSPARSE=OFF -DACCELERATESPARSE=OFF -DLAPACK=OFF
```

so it has **no sparse linear solver at all**, and the wrapper hardcoded `DENSE_QR` to match.

**After.** `native/ken-ceres/` holds the source, and `./gradlew buildCeresNative` builds it with
CMake against a Ceres found by `find_package` — on macOS, `brew install ceres-solver`. Two things
turned out to be true and made this much cheaper than it looked:

- the wrapper compiles against **stock upstream Ceres 2.2.0, unmodified**. None of the emscripten
  hacking in the vendored tree is needed for a native build, so the 31 MB vendored copy is not on
  the critical path.
- Homebrew's Ceres reports `[EigenSparse, SparseLinearAlgebraLibrary, LAPACK, SuiteSparse,
  AccelerateSparse, SchurSpecializations]` — the full sparse arsenal.

A clean configure-and-build is about 25 seconds; an incremental wrapper rebuild is about 5. That is
the whole "Ceres is native and a little hairy" objection, and it is mostly gone.

```bash
./gradlew buildCeresNative
./gradlew smokeCeresAbiV2 -PceresLibrary=build/ken-ceres/libken_ceres_jvm.dylib
```

`-PceresLibrary=<path>` redirects every run/test/smoke task at a different wrapper, so a change can
be A/B'd against the committed artifact without overwriting it.

### What is deliberately *not* done

`libs/libken_ceres_jvm.dylib` is still the old, self-contained, statically-linked v1 build, and it
is still what every task uses by default. A Homebrew-linked dylib needs
`/opt/homebrew/opt/{ceres-solver,glog,gflags}` present at runtime, so committing one would trade a
working default for a broken one on any machine without those. Everything new is therefore opt-in
behind `-PceresLibrary`, and `buildCeresNative` is not wired into `check`.

Closing that gap means deciding how the artifact is distributed — a static v2 build with sparse
support turned on, per-platform artifacts, or building from source at install time. That is the same
open question the release checklist already records, and it should be answered deliberately rather
than by whichever dylib happens to be committed.

### A build bug found on the way

Every `JavaExec` task set its launcher with

```kotlin
javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion)) }
```

`java.toolchain` pins `vendor = GRAAL_VM`, but that governs compilation only: a `launcherFor` spec
naming just the language version matches *any* vendor 25, so Gradle was free to hand a run task an
Adoptium 25 downloaded for some unrelated project. `vectorizationJvmArgs` passes
`-XX:+EnableJVMCI`, which is built in on GraalVM and experimental on HotSpot, so the JVM refused to
start and every Ceres smoke died before `main()`:

```
Error: VM option 'EnableJVMCI' is experimental and must be enabled via -XX:+UnlockExperimentalVMOptions
```

All sixteen sites now go through one `JavaToolchainService.fieldLauncher()` that pins the vendor.
This was latent for every run task, not just the Ceres ones.

## The bindings, before and after

### Extensibility

The program was parsed by **three** `switch` statements that each had to agree on how many doubles
every opcode consumes — one to pre-count `PLANAR`, one to build residuals, one for locks. Adding an
opcode meant editing three tables, and forgetting one desynced the stream. There is now a single
`operand_span()` table that the two skipping passes defer to, so a new opcode needs one entry there
plus its case in the build pass.

### Silent truncation

An unrecognised or malformed opcode hit `default: ip = prog_len` — the parser stopped, the rest of
the program was never built, and the solve returned **success**. A caller who added a constraint the
native side did not know about got a confident answer to a smaller problem.

The build pass now records where it stopped, `ken_ceres_solver_last_error()` reports it, and
`FfmCeresBackend` refuses the compiled solver rather than returning one:

```
Ceres program was not fully built: unknown opcode 9999 at offset 7; the rest of the program
was not built
```

### The loss function

`robust::make()` returned a hardcoded `HuberLoss(1e-3)`, applied to every residual. That is a good
default for interactive constraint editing, where one badly-placed point should not drag the whole
model — and the wrong one for a least-squares problem whose residuals are *meant* to reach zero
together, because a robust loss caps the influence of whichever residual is currently largest and so
quietly changes the objective you wrote down. It is now per-program via `SET_LOSS` /
`RobustLoss`, with Huber(1e-3) still the default.

### Solver options and a summary

`DENSE_QR`, one thread, and Ceres' default tolerances were hardcoded, and the only thing a caller
learned about a solve was a boolean. `ken_ceres_solver_solve2` takes a flat options array (max
iterations, linear solver, threads, three tolerances, non-monotonic steps, progress) and fills a
flat summary array, surfaced as `CeresSolveSummary`:

```
convergence in 14 iter (12 ok, 2 rejected), cost 1.004 -> 3.177e-07, 4.7 ms, SPARSE_NORMAL_CHOLESKY
```

That summary immediately earned its keep by catching a bug in its own consumer:
`ceres::LinearSolverType` numbers its constants differently from this ABI
(`DENSE_NORMAL_CHOLESKY = 0`, `DENSE_QR = 1`, …), so a request for `DENSE_QR` was being reported
back as `DENSE_NORMAL_CHOLESKY`. The wrapper now translates into the ABI's own index space, and
never leaks Ceres' internal enum values — they are not stable across Ceres versions.

### Two conventions worth knowing

- **Weights are square-rooted.** Every functor stores `s = sqrt(w)` and multiplies the residual by
  `s`. So a residual weight of `k` in the usual "residual = k · (quantity − target)" sense is a
  Ceres weight of `k²`. Nothing said so before.
- **`PLANAR` is not "distance to the best-fit plane".** It gives each face its own plane — an
  origin and a unit normal on a `SphereManifold` — as free variables, plus two very weak (`1e-6`)
  stabilisers holding that plane near its seed. Better conditioned than recomputing a best fit
  inside the residual, but it means Ceres' reported *cost* includes residuals the caller never
  wrote.

### New opcodes

`EDGE_TANGENCY`, `TANGENCY_CENTROID`, `CENTROID`, `RADIUS_ABOUT_CENTROID`. The last three are
genuine all-points constraints — a mean or a fit over every listed vertex — implemented as
`DynamicAutoDiffCostFunction` with one parameter block per distinct point. (Ceres rejects a residual
block naming the same parameter block twice, so `TANGENCY_CENTROID` deduplicates its endpoints.)

`RawProgramFragment` is an escape hatch for emitting an opcode the native side understands before
the Kotlin layer models it — the two halves ship separately.

## The second realizer

`PolyhedralObject.relaxWithCeres(plan)` takes the same `RelaxationPlan` as `relax(...)`, returns the
same `RealizationSolveResult`, and applies the same `RigidGauge`, so the two are directly
comparable. `CeresRealization.unsupported(plan)` names any constraint family that cannot cross over
(currently only the symmetry-orbit one).

`./gradlew ceresPolytopeComparison -PceresLibrary=…` runs both on a ladder of Conway solids.

### Do they agree?

Yes. On `RelaxationPlan.regularize()` the per-family unweighted geometric errors and the weighted
residual RMS agree to the printed precision on every case tried — which is the real evidence that
the mapping, including the weight-squaring, is right.

### Where is the crossover?

Best-of-7 after warmup, one thread, `SPARSE_NORMAL_CHOLESKY`:

| solid | V / E / F | plan | Kotlin | Ceres | |
|---|---|---|---|---|---|
| `C` cube | 8 / 12 / 6 | canonical | 3.3 ms | 1.7 ms | ×2.0 |
| `aC` cuboctahedron | 12 / 24 / 14 | canonical | 0.55 ms | 1.13 ms | ×0.5 |
| `tC` truncated cube | 24 / 36 / 14 | canonical | 2.9 ms | 2.0 ms | ×1.5 |
| `tI` truncated icosahedron | 60 / 90 / 32 | canonical | 13.1 ms | 7.5 ms | ×1.8 |
| `kkC` kis kis cube | 38 / 108 / 72 | canonical | 1654 ms | 60 ms | **×27** |

Below a few dozen vertices the Kotlin solver wins: per-call FFM marshalling and rebuilding the
`ceres::Problem` cost more than the whole dense solve. The crossover is driven by *residual count*
rather than vertex count — `kkC` has only 38 vertices but 108 edges and 72 faces, and the Kotlin
path pays `2 · 3V` residual evaluations per iteration for its finite-difference Jacobian plus an
`O(m · (3V)²)` dense normal-matrix build.

Isolating the sparse linear solver alone — same problem, same iteration count, same answer, only
`linear_solver_type` changed:

| solid | DENSE_QR | SPARSE_NORMAL_CHOLESKY |
|---|---|---|
| `kC` | 243 ms | 68 ms |
| `tI` | 110 ms | 4.7 ms |
| `kkC` | 3937 ms | 64 ms |

This is the part the committed dylib cannot do at all, and it is the strongest single argument for
fixing the build.

### Does it crack `kkC`?

No — and the honest version is more interesting than a yes. `kkC` is the standing counter-example in
`dev_docs/POLYTOPE_ALGEBRA.md`: a 3-connected planar graph has a canonical form and neither
implemented iteration finds this one. Ceres reaches a *worse* tangency error than the Kotlin solver
within 200 iterations (6.8e-3 against 6.4e-4) while taking 27× less time to do it, and rejects 53 of
its 201 trust-region steps. It is grinding, not converging.

`kC` shows the same shape more sharply: Ceres runs all 200 iterations where Kotlin converges in 5,
even though the geometry it reaches is good (tangency RMS 8.9e-7). Loosening or tightening all three
tolerances across two orders of magnitude changed **nothing** — identical iteration counts and
identical final cost — so this is not a stopping-criterion artifact. There is a genuinely slow
direction in the problem as posed.

The most likely cause is that the canonical conditions leave the rigid-motion and scale null spaces
completely free: the realizer removes them *after* the solve, with `RigidGauge`, so the minimiser
spends its steps wandering along a continuum of equally-good answers. The Ceres formulation has more
of this freedom than the Kotlin one, not less, because `PLANAR` adds six free plane parameters per
face held only by `1e-6` stabilisers.

That suggests the next experiment, and it is a modelling change rather than a solver one: **pose the
gauge as constraints inside the problem** instead of normalising afterwards. That is what
`dev_docs/POLYTOPE_ALGEBRA.md` means by "a proper second-order solve on the tangency conditions",
and Ceres has the right tool for it — a `SubsetManifold` or a small set of hard gauge residuals.
Nothing in the current binding blocks it.

## What is still open

1. **Distribution.** A committed v2 artifact, or a build-from-source step. See above.
2. **Gauge fixing inside the problem**, per the previous section. The most promising lead.
3. **Plane stabilisers are hardcoded** at `1e-6` with a `// marc: not 100% sure about this` next to
   them. They pollute the reported cost and add free parameters; they should be a program-level
   setting like the loss now is.
4. **Per-constraint-group loss.** `SET_LOSS` is program-level. Interactive editing plausibly wants
   Huber on user-dragged priors and plain L2 on hard geometry in the same problem.
5. **The opcode treadmill.** Every new residual is still C++ plus Kotlin plus a native rebuild.
   Since the functors are templated on the scalar type and Ceres autodiffs through them, the natural
   escape is a small expression VM evaluated over `ceres::Jet` — a postfix residual program the
   native side interprets — which would let Field define residuals from Kotlin without recompiling
   anything. That is a real design, not a small change, but it is the thing that would make this
   general infrastructure rather than a growing table.
6. **`solveInPlace` stays on v1** by design (its purpose is an allocation-free per-frame solve) and
   so refuses the v2 options. If a steady-state solve ever needs a different linear solver, that
   choice should move to compile time.

## Map

| | |
|---|---|
| `native/ken-ceres/` | wrapper source and CMake build |
| `afield.stdlib.ceres.CeresGeometry` | constraint vocabulary, program encoding, options, summary |
| `afield.stdlib.ceres.FfmCeresBackend` | the FFM binding, ABI detection, feature gating |
| `afield.stdlib.polytope.CeresRealization` | `RelaxationPlan` → `CeresProgram`, `relaxWithCeres` |
| `afield.examples.ceres.CeresAbiV2Smoke` | v2 features against a closed-form answer |
| `afield.examples.ceres.CeresPolytopeRealizerComparison` | the table above |
