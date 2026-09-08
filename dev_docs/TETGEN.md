# TetGen in Field: why the structural route is body-fitted, and what it cost

Field's Kratos bridge solves conduction and flow on a fixed background grid, cutting an authored
solid into it as a level set. Structural mechanics cannot work that way, and this is the
measurement that established it plus the binding that replaced it.

Two facts set the problem. **Kratos has no immersed structural element** — 312 public names in
`StructuralMechanicsApplication`, none matching `embedded`, `shifted` or `sbm`. And **Field is
AGPL-3**, so TetGen's AGPL licence, which stops most projects using it, does not stop this one.
Kratos itself gates TetGen behind `USE_TETGEN_NONFREE_TPL` because *Kratos* is BSD; the published
wheels contain MMG and no TetGen.

## The measurement

Both meshers were built from source and run natively on the same machine against the same body — a
slab welded to a ball, chosen for having a curved surface, sharp edges and a concave junction. A
lone sphere would have flattered the level-set route on exactly the features that decide this.

Quality is the radius ratio `3 r_in / r_circ`, 1.0 for a regular tetrahedron. Below 0.05 is a
sliver, where conditioning starts to hurt.

| at h = 0.0625 | MMG 5.8.0, level set | TetGen 1.6.0, Manifold surface (`-q1.2`) |
| --- | --- | --- |
| tetrahedra | 22,191 | 21,368 |
| time | 4,669 ms | **223 ms** |
| quality min | 0.300 | 0.052 |
| quality median | 0.819 | 0.864 |
| elements below 0.05 | 0 | 0 |
| inverted | 0 | 0 |
| boundary error | 0.0341 = 0.55 h | 0.0015 = 0.024 h |

The sliver worry that prompted this turned out to be unfounded for both. MMG never produced one at
any setting; TetGen produced exactly one at its own default `-q1.414`, and `-q1.2` removes it for
no measurable cost, which is why 1.2 is the default in `afield.tetgen.Tetgen`.

### The finding that decided it

Splitting a smooth body from a sharp one, boundary error against the analytic surface:

| body | h = 0.167 | h = 0.083 | h = 0.0625 |
| --- | --- | --- | --- |
| sphere, MMG | 0.12 h | 0.06 h | 0.05 h |
| box, MMG | 0.39 h | 0.40 h | **0.35 h** |
| box, TetGen | — | — | **0.000000, exact** |

MMG converges faster than `h` on a curved surface and **never converges at all on a sharp edge**.
That is not an MMG defect: sampling an SDF onto grid nodes destroys the edge before the mesher sees
it, so refining only buys a smaller rounded radius. Structural stress concentrates at exactly those
edges.

TetGen is handed Manifold's triangulation, whose edges are exact, and constrains its output to it.
Measured through the Kotlin binding, a `2 x 3 x 4` cube meshes to a volume of `23.999999999999936`
against an exact `24.0` — a relative error of `2.7e-15`.

### And "incremental meshing" turned out to be the wrong question

A full re-tetrahedralization of a realistic body is ~0.2 s. Against a Kratos structural solve, that
is noise. Every incremental-remeshing option — MMG's Lagrangian mode, TetGen's `-r`, CGAL's
refinement — solves a problem this does not have. A geometry edit re-meshes.

MMG's isosurface mode was also, contrary to expectation, **not** local: it is a full batch pass over
the whole background grid, and a slower one.

## What is built, and the traps

`tools/build_tetgen_native.py` downloads a pinned, checksummed TetGen 1.6.0 and builds
`native/field-tetgen` into `build/field-tetgen`. `./gradlew buildTetgenNative` is the same thing.
Nothing is vendored.

Three things in that build are load-bearing:

- **`predicates.cxx` must be compiled at `-O0`.** Upstream's makefile says so (`PREDCXXFLAGS = -O0`)
  and the reason is that Shewchuk's exact predicates depend on floating-point operations not being
  reassociated. Built optimised they return wrong signs on nearly-degenerate input, which does not
  crash — it silently produces an invalid tetrahedralization.
- **`TETLIBRARY` must be defined.** Without it TetGen calls `exit()` on a bad surface. With it, it
  throws an `int`, which `field_tetgen.cpp` catches — letting that unwind through the FFM frames
  would abort the VM. `TetgenTest.anOpenSurfaceIsRefusedWithoutTakingTheProcessDown` is that
  guarantee, and it feeds TetGen five faces of a cube so the refusal comes from TetGen rather than
  from our own arity check.
- **The C ABI uses out-parameters, not a struct.** A struct would make the Kotlin side reproduce
  this compiler's field offsets and tail padding, and getting that wrong reads plausible garbage
  rather than failing.

One more, found the hard way while measuring: **TetGen truncates output filenames at the first
dot**, so `body_h0.0625.off` writes its result to `body_h0.1.mesh`.

## The structural formulation, and its one real limitation

`structural.bodyFitted` runs `SmallDisplacementElement3D4N` on the supplied mesh, with a clamp
region, gravity, and a total force spread over a load region. `createBodyMesh` is the protocol verb;
`KratosSession.openMeshAsync` is the Kotlin one.

Validated against closed-form beam theory, and the two results say different things:

| case | 10x2x2 | 20x3x3 | 30x4x4 |
| --- | --- | --- | --- |
| axial extension, `FL/EA` | 99.44% | 100.04% | — |
| cantilever under self-weight, `wL⁴/8EI` | 35.6% | 62.0% | 75.9% |

Axial is right, which pins the material, the clamp, the point loads and the solver. Bending is the
constant-strain tetrahedron **locking**, a known property of the element and not a fault in the
model: it converges to the theory from below, and slowly.

So: trust this for stiffness-dominated and axial problems, and read a bending deflection as a lower
bound. The fix is quadratic tetrahedra — `SmallDisplacementElement3D10N` with TetGen's `-o2` — and
it is the obvious next piece of work. The wrapper currently rejects anything but 4-node output, so
that is a deliberate, visible gap rather than a silent one.

## Reproducing

```bash
./gradlew buildTetgenNative
./gradlew jvmTest --tests 'afield.tetgen.TetgenTest'
podman run --rm --platform linux/amd64 -v "$PWD/optional/kratos:/work:ro" -w /work \
  field-kratos:10.4.0 /opt/field-kratos-env/.venv/bin/python -m unittest discover -s /work/tests
```

The two measurement harnesses that produced the tables above are not committed: they drive the
`mmg3d_O3` and `tetgen` binaries over Medit files and depend on a local MMG build, which is not
something the repository carries. The numbers they produced are here, and
`optional/kratos/VERIFICATION.md` records the runs.
