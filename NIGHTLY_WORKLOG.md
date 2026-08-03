# Free variables and manifolds in the Ceres expression VM

Branch `nightly/2026-08-03/1-ceres-expression-free-variables`, based on `a2dc947b7`.

## Plan

`dev_docs/CERES.md` ends its account of the `EXPR` register-machine VM with a named gap: an
expression can read *nothing but the point parameter blocks*, and so cannot state the one
reformulation that has mattered most in this subsystem. `PLANAR_HESSE` beats `PLANAR` on `kkC`
(12 converging iterations at tangency `3.2e-08` against 201 non-converging at `6.8e-03`) precisely
because it introduces a plane as a **free variable** with its normal on a `SphereManifold`, and
`PLANAR_FIT` — same objective, no auxiliary variables — fails on `kkC` exactly as `relax()` does.
Well-conditioned auxiliary variables are not overhead; they are what makes the problem tractable.
The general mechanism cannot express them. That is the gap.

### Shape of the change

**Kotlin (`CeresExpression.kt`, `CeresGeometry.kt`).**

- `CeresManifold` — `NONE`, `SPHERE` (unit-norm, the one `PLANAR_HESSE` needs), `QUATERNION`.
- `CeresVariableDecl(name, initial, manifold)` with `scalar` / `vector` / `unitVector` /
  `quaternion` factories. Dimension comes from `initial.size`, so it cannot disagree with it.
- `ExprVar(block, component)`, a third leaf beside `ExprConst` and `ExprParam`, hash-consed the
  same way. A new VM op `VAR` with `a` = variable index, `b` = component.
- `CeresProgramBuilder.expression(points, variables) { pts, vars -> … }` — a second overload whose
  lambda receives point vectors *and* variable handles (`CeresExprVars`, with `scalar`, `vec()`,
  `get(i)`), each validated against its declared dimension at build time.
- Encoding: `EXPR` grows a variables section between the points and the residual slots. Validated in
  the compiler (component in range for the declared dim, index bound, finite initials).

**ABI (`ken_ceres.h`, `ken_ceres.cpp`, → `ken_ceres_jvm_abi_v3`).**

- `expr::VAR` in the VM, reading `p[nParams + a][b]`; the evaluator stays templated on `T`, so
  `ceres::Jet` still flows and the derivatives stay analytic.
- `operand_span(EXPR)` learns the variables section — one table, as the v2 work established.
- The build pass allocates one `std::vector<double>` per declared variable in a `std::deque` (so
  addresses stay stable without pre-counting), registers it as a parameter block with
  `SphereManifold<DYNAMIC>` / `QuaternionManifold` / no manifold, and appends those blocks after the
  point blocks on the `DynamicAutoDiffCostFunction`.
- Everything validated at build time and reported through `ken_ceres_solver_last_error`, because the
  evaluator runs once per residual per Jet per iteration and an out-of-range slot index there is an
  out-of-bounds write in someone else's process.
- New entry point `ken_ceres_solver_variables(handle, out, cap)` → total count, fills `min(cap,n)`.
  A solve answers with points; the *solved values of the free variables* have to be readable or the
  mechanism is write-only. Names never cross the ABI: Kotlin knows the declaration order, so it
  reconstructs names and dimensions itself and cross-checks its own layout against the count the
  native side reports.

**Encoding decision.** One opcode, extended encoding, and `CeresFeature.AbiV3` required by *any*
expression residual — not a second `EXPR2` opcode. A v2 wrapper handed a v3 `EXPR` stream would
misparse it, which is exactly the silent-truncation failure mode the v2 work eliminated; so it is
gated in Kotlin (clear refusal at compile time) *and* caught by v2's own error channel if it ever
gets there. Two encodings kept in sync forever is the worse trade. v1/v2 detection stays exactly as
`3f5950a20` established it, the committed v1 dylib stays the default, and `buildCeresNative` stays
out of `check`.

**FFM (`FfmCeresBackend.kt`).** `AbiV3` feature by symbol, not by trusting the version string;
`SolveResult.variables` carrying the solved blocks with their declared names.

### Proof

1. **`PLANAR_HESSE`, re-derived as a Kotlin expression** — a face's corners against a free unit
   normal and a free scalar offset — run over the whole `kk` family against the compiled opcode.
   Same seed (centroid + smallest eigenvector of the scatter matrix, computed in Kotlin by Jacobi so
   it matches Eigen's `SelfAdjointEigenSolver`), same canonical diagnostics, and the recorded `kkC`
   target of ~12 iterations / tangency ~3e-8 must survive.
2. **Something no opcode and no point-only expression can state**, against closed form:
   - the **circumsphere of a tetrahedron** — free centre (3) + free radius (1), cost must reach zero
     and centre/radius must match the closed-form circumcentre;
   - a **total-least-squares plane** — free unit normal on the sphere manifold + free offset,
     against the smallest eigenvector of the scatter matrix (published closed form). This is the
     check that the manifold itself is right.
   - if time allows, a **free rigid frame** (quaternion manifold + free translation) recovering a
     known generating transform — the bundle-style case.
3. **Cost**, in the shape `CeresExpressionCost` already established: same problem, same answer,
   opcode against expression.
4. **Malformed input refused in both layers** through the same error channel as any other malformed
   program.

### Demo

A loadable Field document (or replayable transcript) that solves something with a free variable and
draws the result as FLines. There is currently no document anywhere in the workspace that lets a
person feel a Ceres solve.
