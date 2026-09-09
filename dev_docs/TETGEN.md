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

## The structural formulation

`structural.bodyFitted` runs a small-displacement element on the supplied mesh, with a clamp region,
gravity, and a total force spread over a load region. `createBodyMesh` is the protocol verb;
`KratosSession.openMeshAsync` is the Kotlin one.

The **mesh chooses the element**, not a parameter: a 4-node mesh gets
`SmallDisplacementElement3D4N`, a 10-node one gets `SmallDisplacementElement3D10N`. There is nothing
to configure because there is nothing to disagree about — asking for a quadratic element on a linear
mesh is not a thing anyone wants, and a parameter would make it expressible.

Validated against closed-form beam theory. A 1 m × 0.1 m × 0.1 m cantilever, self-weight, tip
deflection as a fraction of `wL⁴/8EI`:

| mesh | tetrahedra | 4-node | 10-node |
| --- | --- | --- | --- |
| 5×1×1 | 30 | 13.2% | **95.4%** |
| 10×2×2 | 240 | 35.6% | 98.8% |
| 20×3×3 | 1080 | 62.0% | 99.7% |

The linear element is the constant-strain tetrahedron **locking** — a known property of the element,
not a fault in the model. It converges to the theory from below and so slowly that refining is not a
route to a usable answer: it is still 38% short at 1080 elements. The quadratic element is within 5%
at *thirty*. On the identical 864-tetrahedron TetGen mesh the smoke test measures 69.5% against
99.8%.

Just under 100% is where the quadratic element should land, and is not a residual error to chase.
Euler–Bernoulli omits shear, which for this aspect ratio adds 1.0% (Timoshenko), while the model
clamps all three displacements over the whole end face, which is stiffer than beam theory assumes.
The two nearly cancel.

Axial extension, `FL/EA`, is the control that pins the material, the clamp, the point loads and the
solver — a constant-strain tetrahedron represents constant strain with no error at all:

| | 10×2×2 | 20×3×3 |
| --- | --- | --- |
| 4-node | 99.44% | 100.04% |
| 10-node | 100.80% | 100.66% |

The quadratic element is *less* exact here, and that is the load rather than the element. A total
force is split equally over every node in the load region; for a 6-node triangular face the
consistent split is nothing at the corners and a third at each midside, so an equal split is further
from consistent on a quadratic mesh. The resultant is exact either way, so this is a local end
effect — which is why it does not shrink with refinement the way the linear case's does, and why it
does not touch bending, where the load is gravity.

### TetGen's `-o2` is unusable from the library, so we do it ourselves

`Tetgen.tetrahedralize(surface, order = 2)` tetrahedralizes normally and then calls
`TetMesh.quadratic()`, which adds a node at the midpoint of every distinct edge. TetGen has a `-o2`
that does exactly this, and **it cannot be called through `tetgenio`**
(reproducer: `tools/tetgen_o2_use_after_free.py#def main`):

> TetGen 1.6.0's `highorder()` stores each element's six extra nodes in a `highordertable` and then
> `delete[]`s that table before returning, leaving every tetrahedron holding a dangling pointer into
> it. `outelements()` then dereferences those pointers. From the command line it usually survives;
> in library mode it does not, because `outelements()` allocates the output arrays first and the
> allocator hands back the block just freed. The crash is a SIGSEGV whose faulting address is a
> `double` bit pattern from the input body — `0xbfe3d70a40000040`, which is −0.62, a coordinate of
> the test slab.

The reproducer is what turns that from a reading of the source into a diagnosis. It builds the same
cube twice from the same TetGen source, differing in one line: stock TetGen dies on SIGSEGV, and
with `delete [] highordertable;` commented out and nothing else changed it returns 229 nodes and 104
ten-node tetrahedra. Nothing in Field links it and no gate runs it; it is there to be re-run by
whoever next wonders whether `-o2` would be simpler.

Doing the promotion on our side is not really a workaround. `highorder()` places each node at the
exact arithmetic midpoint of a straight edge, which is what `TetMesh.quadratic()` does; and building
the edge table here means the six midside nodes come out in Kratos's `Tetrahedra3D10` order by
construction, instead of having to be recovered from TetGen's internal `edge2ver`/`orgpivot` tables
— which are not public, have changed between releases, and whose misreading produces an element that
solves and is wrong.

That last risk is why the ordering is checked twice and geometrically, rather than trusted:
`TetgenTest.quadraticMidsideNodesAreWhereKratosExpectsThem` on the way out, and `SuppliedMesh` on the
way in, at the worker. The two promotion implementations are deliberately separate, so a divergence
is a test failure rather than a wrong answer.

The cost is real but small: on the smoke's 864-element beam, 1717 nodes against 298, and assembly
plus solve of 1.05 s against 0.20 s under x86-64 emulation. Against 62% versus 99.8% that is not a
trade worth thinking about.

## Reproducing

```bash
./gradlew buildTetgenNative
./gradlew jvmTest --tests 'afield.tetgen.TetgenTest'
```

```bash
python3 tools/run_field_tests.py --id kratos.structural.quadratic
```

```bash
podman run --rm --platform linux/amd64 -v "$PWD/optional/kratos:/work:ro" -w /work \
  field-kratos:10.4.0 /opt/field-kratos-env/.venv/bin/python -m unittest discover -s /work/tests
```

The two mesher-comparison harnesses that produced the tables at the top are not committed: they
drive the `mmg3d_O3` and `tetgen` binaries over Medit files and depend on a local MMG build, which is
not something the repository carries. The numbers they produced are here, and
`optional/kratos/VERIFICATION.md` records the runs. The beam tables above *are* reproducible from
the commands here — they are what `StructuralTest` asserts.
