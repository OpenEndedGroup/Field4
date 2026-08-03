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

What *is* now settled is how to build a shippable one. `./gradlew buildCeresRedistributable` (or
`native/ken-ceres/build_redistributable.sh` directly) produces a v3 wrapper whose `otool -L` shows
only `libc++` and `libSystem` — the property the committed v1 artifact has and the Homebrew-linked
build does not — by statically linking a Ceres built from pinned, checksummed source:

| | |
|---|---|
| Ceres | 2.2.0, the version every measurement here was taken against |
| Eigen | 3.4.0, pinned because Ceres 2.2.0's `find_package(Eigen3 3.3)` *rejects* the Eigen 5.x Homebrew now installs |
| sparse | `EIGENSPARSE=ON`, `EIGENMETIS=OFF` — Eigen's sparse Cholesky is header-only, so `SPARSE_NORMAL_CHOLESKY` costs no shared library; METIS *ordering* would have pulled in `libmetis` |
| logging | `MINIGLOG=ON` with `MINIGLOG_MAX_LOG_LEVEL=-3` |
| result | 8.0 MB, `ken_ceres_jvm_abi_v3`, two system libraries |

Two things that build taught us, both of which the script now encodes. Miniglog is *not* quiet:
Ceres defaults `MINIGLOG_MAX_LOG_LEVEL` to 2, which is two `VLOG` levels straight to `std::cerr`, and
the smoke suite emitted **11,108** lines of `Allocating values array with N bytes` before it was
gated. A library loaded into someone else's process should not do that — the same complaint the glog
path already fixed — so the level is `-3`, FATAL, matching what `quiet_glog_once()` asks of real glog.
And the script *asserts* the load commands rather than trusting the flags: anything outside `/usr/lib`
and `/System/Library` fails the build, because "I passed the static flag" and "the artifact is
self-contained" are different claims.

Verified against that artifact: all 42 checks across the five Ceres smokes, 29 solves genuinely
reporting `SPARSE_NORMAL_CHOLESKY` rather than falling back to dense, `kkC` and `kkD` still exact, and
zero lines of stderr noise.

What remains a decision rather than a gap: whether to *commit* it. Doing so would grow `libs/` from
2.2 MB to 8.0 MB and make every v2/v3 feature work on a fresh clone; not doing so keeps the repo
small and leaves those features behind one build command. It is also macOS-arm64 only, and
`CeresNativeLibrary.defaultPath` hardcodes `libs/libken_ceres_jvm.dylib` with no os/arch dispatch, so
a second platform is a loader change as well as a build one.

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
| `C` cube | 8 / 12 / 6 | canonical | 3.3 ms | 1.3 ms | ×2.6 |
| `aC` cuboctahedron | 12 / 24 / 14 | canonical | 0.55 ms | 1.13 ms | ×0.5 |
| `tC` truncated cube | 24 / 36 / 14 | canonical | 5.2 ms | 2.1 ms | ×2.4 |
| `tI` truncated icosahedron | 60 / 90 / 32 | canonical | 11.9 ms | 4.3 ms | ×2.8 |
| `kkC` kis kis cube | 38 / 108 / 72 | canonical | 1507 ms | 6.1 ms | **×248** |

The `kkC` row is not just a speed difference — the Kotlin solver never converges there, so it is
248x faster *and* four orders of magnitude more accurate. Below a couple of dozen vertices the
Kotlin solver still wins: per-call FFM marshalling and rebuilding the
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

### Should the compiled program be cached across calls?

`relaxWithCeres` builds a `CachedCeresSolvers`, uses it once and closes it, so the native program is
compiled on every call and `CachedCeresSolvers`' keying-by-program-hash never gets to do its job.
That looks like a plain oversight. `./gradlew ceresRealizerCacheCost -PceresLibrary=…` measures it,
and the answer is no, twice over.

Where a call actually goes (best of 20, canonical plan, `SPARSE_NORMAL_CHOLESKY`; one run — the
small-solid rows move by a few tenths of a millisecond between runs, so read the share, not the digits):

| solid | V / F | call | program | hash | compile | solve | report + gauge | compile share |
|---|---|---|---|---|---|---|---|---|
| `C` | 8 / 6 | 1.45 ms | 0.03 | 0.03 | 0.10 | 0.15 | 0.32 | 7% |
| `kC` | 14 / 24 | 1.78 ms | 0.05 | 0.02 | 0.12 | 0.56 | 0.49 | 7% |
| `tI` | 60 / 32 | 4.99 ms | 0.04 | 0.03 | 0.22 | 2.49 | 0.89 | 4% |
| `kkC` | 38 / 72 | 6.49 ms | 0.03 | 0.03 | 0.29 | 2.95 | 0.99 | 5% |
| `kkkC` | 110 / 216 | 282 ms | 0.07 | 0.09 | 0.79 | 256 | 2.6 | 0.3% |
| `kkkkC` | 326 / 648 | 1805 ms | 0.08 | 0.24 | 2.27 | 1714 | 7.9 | 0.1% |

The compile share *falls* with size, because the solve grows far faster than the compile: on `kkkkC`
a 2.3 ms compile stands in front of a 1.7 s solve. Solving one bit-identical program 20 times through
a shared cache instead of a fresh one per solve is 1.1–2x on the small solids and inside the noise on
the large ones; a prototype that let the caller pass a cache into `relaxWithCeres` gave 1.1–1.8x over
20 whole calls, best on a cube.

The second reason is the one that settles it. `CeresRealization.program` resolves a plan's unset
targets — mean edge length, centroid, tangency radius, the minimum-edge-length floor — from the
*current* embedding and bakes them into the program, so the same topology with moved vertices is a
different program and a guaranteed miss. Twenty frames of a wobbling solid produce twenty distinct
programs at every size measured. An animation loop, which is the case a cache is wanted for, would
get nothing but the cost of retaining twenty native solvers.

So the prototype was dropped: public API that only pays for re-solving a bit-identical program, whose
natural "just share one globally" default would be a data race — `CachedCeresSolvers` is an
access-ordered `LinkedHashMap` of native handles with no locking. A caller who really is in that case
(a sweep over `CeresRelaxationOptions` on one seed, say — the solver options are not part of the key)
should build the program once with `CeresRealization.program` and drive their own cache, which also
skips the per-call mapping and hash. To make a moving solid cacheable, state the plan's targets
explicitly so the program stops depending on the embedding.

### Does it crack `kkC`?

Yes — but only after finding out why it did not.

`kkC` is the standing counter-example in `dev_docs/POLYTOPE_ALGEBRA.md`: a 3-connected planar graph
has a canonical form and neither implemented iteration finds this one. The first Ceres realizer did
not either; it just failed faster. Chasing that turned up two real defects, one in the wrapper and
one in the objective.

**The plane parameterisation had a null space.** `PLANAR`'s residual is `(P - O) . n`, which depends
on the plane origin `O` only through `O . n` — so the two components of `O` lying *in* the plane are
an exact null direction, two per face, restrained by nothing but a `1e-6` prior. Counting parameters
made it obvious: of `kkC`'s 474 effective parameters only 114 are points, and 144 of the remaining
360 are exactly null. The kis-family solids have more faces than vertices, so for them the null
space outnumbers the real problem and Levenberg-Marquardt spends its entire budget crawling along
it.

`PLANAR_HESSE` writes the plane as `{ x : x . n == d }` — normal on a sphere manifold, offset a
scalar. Three effective parameters per face, no null direction, no stabilisers needed:

| `kkC` canonical | params | iterations | termination | tangency RMS | |
|---|---|---|---|---|---|
| Kotlin `relax` | — | 200 | max iterations | 6.4e-04 | 1507 ms |
| Ceres `origin_normal` | 474 | 201 | no convergence | 6.8e-03 | 58 ms |
| Ceres **`hesse`** | 330 | **12** | **convergence** | **3.2e-08** | **6 ms** |

Two things are worth noticing in the full sweep (`./gradlew ceresPlaneFormulationStudy`). The
rotation gauge — three residuals holding `sum(ref_i x p_i)` at zero — rescues `kC` under the *old*
parameterisation (201 iterations to 12) but is a small improvement once the planes are fixed; it is
on by default because it never hurts and it makes the answer reproducible rather than
path-dependent. And `PLANAR_FIT`, which computes the Newell best-fit plane inside the residual and
so carries no plane variables at all, *fails on `kkC`* — which is the same objective `relax` has,
and `relax` fails too. The well-conditioned auxiliary variables are not a cost to be minimised away;
they are what makes the problem tractable.

**The objective has a spurious zero-cost minimum.** With the planes fixed, `kkT` started converging
— to cost `5.7e-17` and a completely wrong answer. Measuring the geometry directly showed why: 12 of
its 54 edges had collapsed to length `1e-7`.

`EdgeTangencyConstraint` measures the distance from the origin to the *line* through an edge's two
endpoints. As those endpoints converge, that line's direction becomes arbitrary — so a collapsed
edge can put its tangency point anywhere on the sphere and satisfies its constraint for free.
Collapsing edges *removes* constraints, and the canonical objective therefore has a family of exact
zero-cost solutions in which part of the solid has folded up. **`relax` uses the identical formula
and shares this defect**; it is saved only by converging too slowly to reach the trap.

The fix needed no new native code — `MIN_DISTANCE` was already there. A one-sided
`max(0, floor - length)` barrier at 1% of the initial mean edge length contributes exactly nothing
while the solid is healthy (`kkC`, `kkD`, `kC`, `tI` are bit-identical with it on) and rescues the
rest:

| tangency-radius spread | `canonicalize()` | Ceres, no barrier | Ceres, barrier |
|---|---|---|---|
| `kkO` | 7.2e-01 | 4.96e-01 | **2.5e-04** |
| `kkI` | 1.1e+00 | 2.91e-01 | **1.0e-04** |
| `kkT` | 3.1e-01 | 6.90e-03 | **5.3e-04** |

`./gradlew smokeCeresCanonical` now checks the whole `kk` family against
`PolyhedralObject.canonicalDiagnostics()` — the library's own independent measurement of the three
defining conditions — plus no degenerate faces, no collapsed edges, finite bounded coordinates, and
"strictly better than `canonicalize()`". All eight solids pass. `kC`, `kD`, `tI`, `kkC` and `kkD`
reach spread `1e-8`; `kkT`, `kkO` and `kkI` reach `1e-4`, which is not exact but is three to four
orders of magnitude better than the existing iteration, and sane where it produced a lump. On every
`kk` case `canonicalize()` ends up *worse than the seed it started from*.

## What is still open

1. **Distribution.** Half done. `buildCeresRedistributable` builds a self-contained v3 artifact with
   sparse support from pinned source, and asserts its own self-containment — so *producing* the right
   binary is now one reproducible command. What is undecided is whether it gets committed (2.2 MB →
   8.0 MB in `libs/`, against every v2/v3 feature working on a fresh clone) and whether a second
   platform is worth the loader's first os/arch dispatch. See above.
2. **`PLANAR` should migrate to the Hesse form.** The null space above is not specific to
   realization — every caller of the original opcode, including the architectural DSL, is paying
   for two free directions per face. `PLANAR_HESSE` is left as a separate opcode only so that this
   change does not silently move existing results; the architectural smokes should be diffed
   against it and then switched.
3. **`kkT`, `kkO`, `kkI` reach 1e-4, not machine precision.** They now find a sane solid, so what is
   left is the tail rather than the failure. Worth trying: continuation on the barrier (solve with
   the floor, then release it), or a Mobius normalisation between solves.
4. **The collapse defect applies to `relax` too.** `EdgeTangencyConstraint` should either carry its
   own floor or be reformulated so that a short edge does not free its own constraint.
5. **Per-constraint-group loss.** `SET_LOSS` is program-level. Interactive editing plausibly wants
   Huber on user-dragged priors and plain L2 on hard geometry in the same problem.
6. ~~**Auxiliary variables from Kotlin.**~~ Done — see "Free variables, and the manifolds they live
   on" below. What is left of it: `PLANAR_HESSE` itself could now be *deleted* and replaced by the
   Kotlin expression, which reaches the same answer to `1e-15` at ×1.4 the cost. That is a real
   choice rather than an obvious one, and it is the same question item 2 asks.
7. **Nothing can be held constant.** Bounds are exposed now; `SetParameterBlockConstant` is not.
   There is still no way to say "this point is data, not an unknown" except a very stiff
   `LockPosition`, which is why the fits above put their data in the expression as constants instead.
   That works, but it bakes the data into the program hash, so changing a data point recompiles the
   problem rather than re-solving it.
8. **Only three manifolds.** `NONE`, `SPHERE`, `QUATERNION`. `SubsetManifold` (hold some components
   of a block fixed) and `LineManifold` are the obvious next two, and `SubsetManifold` would answer
   half of item 7 without a new ABI concept.
9. **Bounds and manifolds do not compose**, and cannot: see above. If a bounded *direction* is ever
   needed, the answer is a different parameterisation, not a way around the refusal.
10. **`solveInPlace` stays on v1** by design (its purpose is an allocation-free per-frame solve) and
   so refuses the v2 options. If a steady-state solve ever needs a different linear solver, that
   choice should move to compile time.

## Residuals written in Kotlin

Every constraint above is a C++ functor with a matching opcode, so a new kind of residual costs a
native rebuild, a Kotlin change, and a redeployed dylib. The `EXPR` opcode ends that. A caller
builds an expression, it compiles to a small register program, and the native side evaluates it —
templated on the scalar type, so **Ceres runs it on `ceres::Jet` and gets analytic derivatives for
a residual it has never seen**. No finite differences, no rebuild.

```kotlin
buildCeresProgram {
    // a spring between two points, at rest length 2.5
    expression(a, b) { (pa, pb) -> listOf(pa.distanceTo(pb) - 2.5.c) }

    // a one-sided floor on the same distance, contributing nothing above it
    expression(a, b) { (pa, pb) -> listOf(barrierBelow(pa.distanceTo(pb), 0.1.c)) }
}
```

It is a **register machine, not a stack machine**, and that is the one design decision worth
defending. The caller compiles an expression DAG; with named destination slots, a subexpression
mentioned twice is computed once, for free. A stack machine would have to recompute it or juggle
`DUP`/`SWAP`. `CeresExpr` instances are hash-consed as you build them, so the sharing is structural
rather than something the compiler has to rediscover — three residuals over one shared distance
compile to one `sqrt`.

Instructions are four values, `[op, dst, a, b]`, and everything is validated at build time — slot
ranges, constant indices, parameter components, unknown ops — because the evaluator runs once per
residual per Jet per iteration and an out-of-range slot index there is an out-of-bounds write in
someone else's process. `CompiledCeresExpression.disassemble()` prints the program; being able to
look at it is half the point of having one.

### Is it right?

`smokeCeresExpression` checks it three ways.

**Against the wrapper's own functors.** A residual written as an expression must agree with the
hand-written opcode that does the same thing — checked against the wrapper, not against a number
someone typed in:

| | max coordinate difference after a full solve |
|---|---|
| `DISTANCE` opcode vs expression | 2.2e-11 |
| `EDGE_TANGENCY` opcode vs expression | 9.9e-14 |

**Against something no opcode can express.** The Thomson problem — minimise the Coulomb energy of
N charges confined to a sphere — needs `1/r` repulsion, which nothing in this vocabulary can say.
Written as residuals, `r_ij = 1/sqrt(d_ij)` gives exactly the Coulomb energy, and the known minima
are exact numbers:

| N | energy found | known minimum | error |
|---|---|---|---|
| 4 | 3.674233 | 3.674235 | 1.7e-06 |
| 6 | 9.985273 | 9.985281 | 8.3e-06 |
| 8 | 19.675264 | 19.675288 | 2.4e-05 |
| 12 | 49.231655 | 49.165253 | local minimum |

N=12 converges cleanly and stays on the sphere but finds a configuration slightly worse than the
icosahedron. That is the Thomson problem's own local-minimum structure, not a VM error, so the check
for it is "converged, on the sphere, and no better than the known global minimum" — landing *below*
a known minimum would mean the energy was being computed wrong.

**Against malformed input**, in both layers: Kotlin rejects an expression naming the same point
twice (Ceres forbids a repeated parameter block), and the native validator rejects an out-of-range
point, slot or opcode through the same error channel as any other malformed program.

### What it costs

Same problem, same answer, same iteration count; the only difference is interpreted register program
against compiled C++ template:

| points | `DISTANCE` opcode | expression | |
|---|---|---|---|
| 50 | 3.6 ms | 5.3 ms | ×1.5 |
| 200 | 13.7 ms | 20.2 ms | ×1.5 |
| 800 | 54.3 ms | 77.6 ms | ×1.4 |

A flat 1.5×, which is low enough that the VM is a reasonable place for a residual to *live*, not
just a place to prototype one before promoting it. Promote to a real opcode when a residual is hot
and settled; use an expression while you are still working out what the residual should be, or when
it is caller-specific and will never belong in the wrapper.

### What it cannot do

Worth knowing: `abs`, `min` and `max` are not differentiable at their kinks, and Ceres will hand back
a subgradient. That is what you want for a one-sided barrier and not what you want in the middle of a
smooth objective.

Everything else it could not do is the next section. This paragraph used to say the VM could read
nothing but the point parameter blocks, and that declaring a free variable was "the extension worth
doing next"; it is done, so the paragraph is gone rather than merely qualified.

## Free variables, and the manifolds they live on

An expression can declare unknowns of its own — quantities Ceres solves for that are not points.
That is the mechanism `PLANAR_HESSE` hardcodes in C++, made available to a residual written at
runtime, and the evidence for why it is worth having is entirely above: the plane as a free variable
converges on `kkC` in 12 iterations to tangency `3e-08`, while `PLANAR_FIT` — the same objective with
the plane eliminated inside the residual — does not converge at all. **Well-conditioned auxiliary
unknowns are not overhead to be minimised away; they are frequently what makes a problem tractable.**

```kotlin
buildCeresProgram(RobustLoss.NONE) {
    // fit a sphere: the centre and the radius are unknowns, and neither is a point
    val centre = variable("centre", Vec3(0.0, 0.0, 0.0))
    val radius = variable("radius", 1.0)
    expression(emptyList(), listOf(centre, radius)) { _, (c, r) ->
        data.map { p -> (CeresExprVec.of(p) - c.vec()).length() - r.scalar }
    }
}
```

Three decisions are worth defending.

**Variables are declared at *program* scope, not owned by a residual.** `variable(...)` returns a
`CeresVariableRef` that any number of expressions may name, because "each face has its own plane" and
"these fifty observations all see the same camera" are both things a caller wants, and the second is
not expressible if a variable belongs to one residual block. This is what makes a bundle-style
problem writable at all.

**A residual may name no points.** A fit has no point unknowns: the data are constants in the
expression and every unknown is a free variable. Requiring a point would have forced such a residual
to name one it never reads, which is a Jacobian column of exact zeros for a parameter that provably
cannot affect the answer — the defect this layer refuses everywhere else.

**Manifolds are the ABI's own codes.** `NONE`, `SPHERE` (`ceres::SphereManifold<DYNAMIC>` — the one
`PLANAR_HESSE` needs) and `QUATERNION` (`w` first; `CeresExprVariable.rotate()` writes out the
quaternion sandwich, so a free frame needs no trigonometry in the residual). Ceres' own enum values
are not stable across versions and were never an interchange format.

### Bounds

A variable can carry per-component box constraints, which become Ceres'
`SetParameterLowerBound` / `SetParameterUpperBound`:

```kotlin
val radius = variable(CeresVariableDecl.scalar("radius", 1.0, atLeast = 0.0))
```

This is the right tool for "this radius is positive", and a one-sided barrier residual is not: a
barrier can only *penalise* an excursion the minimiser has already taken, and it changes the
objective you wrote down while doing so.

Two restrictions, both refused rather than allowed to misbehave. **Bounds only on
`CeresManifold.NONE`** — a bound is a statement about an ambient coordinate and a manifold's `Plus`
steps in the tangent space, so it can walk straight through one. And **the seed has to be feasible**,
because an infeasible start is a `CHECK` failure inside the minimiser, which would take the process
down rather than return an error.

The check that bounds are actually *applied*, rather than approximated by something tuned: fit a
sphere to points that lie on a plane. A plane is a sphere of infinite radius, so the unconstrained
geometric fit has no finite optimum and the answer is decided entirely by the bound. With
`radius <= 5` the solve converges with the radius at `5.000000000`.

The solved values come back in `SolveResult.variables`, keyed by the names the declarations gave
them. Names never cross the ABI: the native side is handed dimensions and initial values, this side
knows the declaration order, and the two independently computed layouts are cross-checked when the
solver is compiled — because a disagreement would attribute every solved value to the wrong name.
The values are warm-started across solves, exactly as `PLANAR`'s planes always have been.

### ABI v3, and why the version moves

`EXPR`'s operand stream now carries a variable-reference run whether or not it is empty. A v2 wrapper
reading a v3 stream would take that count as the residual count and desync from there — the
silent-misparse failure mode the v2 work was built to end. So *every* `CeresExpressionResidual`
requires `CeresFeature.AbiV3` and refuses at compile time with a clear message, rather than the
feature being sniffed. Everything `3f5950a20` established is unchanged: detection is per symbol
rather than per version string, the committed v1 dylib is still the default, and `buildCeresNative`
is still not in `check`.

### Is it right?

`smokeCeresExpressionVariables` proves two separate things.

**`PLANAR_HESSE`, re-derived as a Kotlin expression.** `PlaneFormulation.HESSE_EXPRESSION` runs the
realizer with each face's plane written as `corner . n - d` over a free unit normal and a free scalar
offset (`planarHesseExpression`), against the C++ opcode on the same plan and the same seed — the
seed reproduced in Kotlin by `hessePlaneOf`, centroid plus the smallest eigenvector of the scatter
matrix, by Jacobi, agreeing with `Eigen::SelfAdjointEigenSolver`:

| solid | iterations, opcode / expression | max coordinate difference |
|---|---|---|
| `kC` | 7 / 7 | 5.3e-15 |
| `kD` | 15 / 15 | 1.3e-14 |
| `tI` | 6 / 6 | 1.1e-15 |
| `kkC` | **12 / 12** | **1.6e-15** |
| `kkD` | 10 / 10 | 1.2e-15 |

Identical iteration counts and machine-precision agreement, including the recorded `kkC` result. An
interpreted register program with hash-consed free variables reaches the same solid as the compiled
template.

`kkT`, `kkO` and `kkI` — the three that reach `1e-4` rather than machine precision — reach the same
cost and the same canonical diagnostics and land *somewhere else*, and not even at a congruent solid
(a pose-independent fingerprint of sorted radii and edge lengths differs by 5e-2 / 8e-3 / 7e-3).
**That is a fact about the objective, not about the VM.** At final cost `~2e-07` its minimum is not
isolated, so where in the valley a run stops is decided by rounding in the seventh digit, and a
template and an interpreter round differently. The evidence that it is not a VM defect is the table
above: whenever the problem is well-conditioned enough to converge in a dozen iterations the two
agree to `1e-15`. The expression's answer is very slightly *better* on all three.

**Three fits with closed-form answers and no point unknowns at all.**

| | check | error |
|---|---|---|
| circumsphere of a tetrahedron, free centre + free radius | closed-form circumcentre | centre 1.6e-16, radius exact |
| total-least-squares plane, free unit normal on a `SphereManifold` + free offset | smallest eigenvector of the scatter matrix | normal 3.6e-09, offset 1.7e-09 |
| a free rigid frame, `QuaternionManifold` + free translation, 7 observations sharing one frame | the generating transform | rotation 3.7e-15, translation 1.9e-14 |

The middle row carries the manifold's own claim: `|n| - 1 = -1.1e-16` with no residual anywhere
asking the normal to stay unit. Without the manifold the minimum would sit at length zero with cost
zero. The last row is only expressible because variables are program-scoped.

Twenty-four malformed inputs are refused — fourteen in Kotlin, ten in the native validator reached
through `RawProgramFragment` — all through the same error channel as any other malformed program. The
smoke prints one line per case, so the count is read off its output rather than maintained by hand.

### What a free variable costs

Same problem, same seed, same answer to `1e-15`, identical iteration counts; the only difference is a
compiled C++ template against the interpreted register program, now with parameter blocks of two
different sizes and a manifold in the mix rather than a uniform run of 3-vectors:

| solid | faces | `PLANAR_HESSE` opcode | expression | |
|---|---|---|---|---|
| `kC` | 24 | 0.53 ms (6 iter) | 1.04 ms (6 iter) | ×2.0 |
| `tI` | 32 | 2.03 ms (4 iter) | 3.12 ms (4 iter) | ×1.5 |
| `kkC` | 72 | 2.42 ms (7 iter) | 3.35 ms (7 iter) | ×1.4 |
| `kkD` | 180 | 9.25 ms (8 iter) | 11.61 ms (8 iter) | ×1.3 |

The ratio *improves* with size, for the same reason the `DISTANCE` row does: the interpreter overhead
is per residual evaluation and the linear solve is not. So free variables cost the same flat ~1.5×
the rest of the VM does, against a choice of parameterisation that on `kkC` is the difference between
12 converging iterations and 201 non-converging ones.

### Try it

`public/docs/assets/agent_transcripts/ceres_free_variables/` is a loadable Field document — the
first anywhere in the workspace in which a person can run a Ceres solve and look at the result. Two
boxes: a canonical realization drawn together with the free variables themselves (one stem per face,
from the point of its plane nearest the origin, along the normal Ceres found — both endpoints derived
from `n` and `d` alone, so that half of the picture cannot be drawn without reading the variables
back), and the sphere fit above. Its source is
`dev_docs/agents/transcripts/ceres_free_variables.yaml`. Build the wrapper first, or the boxes will
tell you to:

```bash
./gradlew buildCeresNative
./gradlew smokeCeresExpressionVariables -PceresLibrary=build/ken-ceres/libken_ceres_jvm.dylib
```

## Map

| | |
|---|---|
| `native/ken-ceres/` | wrapper source and CMake build |
| `native/ken-ceres/build_redistributable.sh` | the shippable build: pinned Ceres/Eigen, static, quiet, self-containment asserted |
| `afield.stdlib.ceres.CeresGeometry` | constraint vocabulary, program encoding, options, summary |
| `afield.stdlib.ceres.FfmCeresBackend` | the FFM binding, ABI detection, feature gating |
| `afield.stdlib.polytope.CeresRealization` | `RelaxationPlan` → `CeresProgram`, `relaxWithCeres` |
| `afield.examples.ceres.CeresAbiV2Smoke` | v2 features against a closed-form answer |
| `afield.examples.ceres.CeresPolytopeRealizerComparison` | the timing table above |
| `afield.examples.ceres.CeresPlaneFormulationStudy` | the plane-parameterisation sweep |
| `afield.examples.ceres.CeresCanonicalVerification` | the canonical-conditions check on the kk family |
| `afield.examples.ceres.CeresRealizerDiagnostic` | parameter and residual counts per solid |
| `afield.examples.ceres.CeresRealizerCacheCost` | where a `relaxWithCeres` call goes, and why the program cache stays per-call |
| `afield.stdlib.ceres.CeresExpression` | the expression DAG, its compiler, the register program, free variables and manifolds |
| `afield.examples.ceres.CeresExpressionSmoke` | equivalence, sharing, validation, Thomson |
| `afield.examples.ceres.CeresExpressionCost` | the two cost tables above |
| `afield.examples.ceres.CeresExpressionVariablesSmoke` | `PLANAR_HESSE` re-derived in Kotlin, the manifold fits, malformed input |
| `dev_docs/agents/transcripts/ceres_free_variables.yaml` | the loadable demo document's source |
