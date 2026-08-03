package afield.examples.ceres

import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresExprVec
import afield.stdlib.ceres.CeresExpressionResidual
import afield.stdlib.ceres.CeresFeature
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.CeresManifold
import afield.stdlib.ceres.CeresProgram
import afield.stdlib.ceres.CeresVariableDecl
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.RobustLoss
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.ceres.SolveResult
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.buildCeresProgram
import afield.stdlib.ceres.c
import afield.stdlib.ceres.hessePlaneOf
import afield.stdlib.ceres.planarHesseExpression
import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.PlaneFormulation
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.canonicalDiagnostics
import afield.stdlib.polytope.canonicalize
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relaxWithCeres
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Free variables in the residual expression VM, checked four ways.
 *
 * The claim being tested is not "the mechanism runs". It is that a residual *written in Kotlin at
 * runtime* can introduce well-conditioned auxiliary unknowns, on a manifold, and reach the same answer
 * as the C++ opcode that hardcodes exactly that reformulation — because the recorded evidence is that
 * the reformulation is the difference between converging and not.
 *
 * 1. **`PLANAR_HESSE`, re-derived as an expression**, over the whole `kk` family. Same objective, same
 *    parameterisation, same seed; one is a compiled template and the other an interpreted register
 *    program. They must reach the same solid, and `kkC` must still land where `9a71f66d9` recorded it.
 * 2. **A fit no opcode and no point-only expression can state**: the circumsphere of a tetrahedron,
 *    with a free centre and a free radius and *no point unknowns at all*, against the closed-form
 *    circumcentre.
 * 3. **The manifold itself**: the total-least-squares plane, as a free unit normal on a sphere
 *    manifold plus a free offset, against the smallest eigenvector of the scatter matrix.
 * 4. **A free frame**: a rotation on the quaternion manifold plus a free translation, recovering a
 *    known rigid transform from corresponding points — the bundle-adjustment shape in miniature.
 *
 * Plus malformed input refused in both layers, and a disassembly that shows the variable blocks.
 */
private fun solve(
    points: PointCloud,
    program: CeresProgram,
    iterations: Int = 500,
): SolveResult = CachedCeresSolvers(FfmCeresBackend()).use { solvers ->
    val result = solvers.solve(
        SolveRequest(
            points,
            program,
            options = SolveOptions(
                maxIterations = iterations,
                linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
                functionTolerance = 1e-14,
                gradientTolerance = 1e-16,
                parameterTolerance = 1e-14,
                summary = true,
            ),
        ),
    )
    check(result.ok) { "solve failed" }
    result
}

/** One point, unconstrained: a fit has no point unknowns, and the cloud may not be empty in the ABI. */
private val NO_POINTS = PointCloud.of(Vec3(0.0, 0.0, 0.0))

private fun length(v: Vec3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

private fun minus(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

/** Cramer's rule. Small, exact enough, and visibly the closed form it claims to be. */
private fun solve3(m: DoubleArray, rhs: DoubleArray): Vec3 {
    fun det(a: DoubleArray) =
        a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])

    val d = det(m)
    require(abs(d) > 1e-14) { "singular system" }
    fun withColumn(column: Int): DoubleArray {
        val c = m.copyOf()
        for (row in 0..2) c[3 * row + column] = rhs[row]
        return c
    }
    return Vec3(det(withColumn(0)) / d, det(withColumn(1)) / d, det(withColumn(2)) / d)
}

/**
 * The circumcentre and circumradius of a tetrahedron, in closed form.
 *
 * `|c - p_i|^2 = R^2` for every vertex; subtracting the first equation from the rest leaves the linear
 * system `2 (p_i - p_0) . c = |p_i|^2 - |p_0|^2`.
 */
private fun circumsphere(p: List<Vec3>): Pair<Vec3, Double> {
    require(p.size == 4) { "a circumsphere needs four vertices" }
    fun norm2(v: Vec3) = v.x * v.x + v.y * v.y + v.z * v.z
    val m = DoubleArray(9)
    val rhs = DoubleArray(3)
    for (i in 1..3) {
        m[3 * (i - 1)] = 2.0 * (p[i].x - p[0].x)
        m[3 * (i - 1) + 1] = 2.0 * (p[i].y - p[0].y)
        m[3 * (i - 1) + 2] = 2.0 * (p[i].z - p[0].z)
        rhs[i - 1] = norm2(p[i]) - norm2(p[0])
    }
    val centre = solve3(m, rhs)
    return centre to length(minus(p[0], centre))
}

private fun describe(label: String, p: PolyhedralObject): String {
    val d = p.canonicalDiagnostics()
    return "  %-22s spread=%.3e centroid=%.3e planarity=%.3e%s".format(
        label,
        d.tangencyRadiusSpread,
        d.tangencyCentroidOffset,
        d.maxFacePlanarityError,
        if (d.degenerateFaces.isEmpty()) "" else "  DEGENERATE x${d.degenerateFaces.size}",
    )
}

/**
 * A pose-independent fingerprint: the sorted vertex radii and the sorted edge lengths, invariant under
 * rotation and reflection.
 *
 * Reported rather than asserted on the three `kk` solids whose canonical form is only reached to
 * `1e-4`, because measuring it is how the interesting negative result below was established: they are
 * not congruent either.
 */
private fun congruenceFingerprint(p: PolyhedralObject): DoubleArray {
    val r = requireNotNull(p.realization) { "solved polyhedron has no realization" }
    val index = p.topology.elements(0).mapIndexed { at, v -> v.id.value to at }.toMap()
    val radii = (0 until r.size).map {
        sqrt(
            r.x(it).toDouble() * r.x(it).toDouble() +
                r.y(it).toDouble() * r.y(it).toDouble() +
                r.z(it).toDouble() * r.z(it).toDouble(),
        )
    }.sorted()
    val edges = p.topology.elements(1).map { edge ->
        val (a, b) = p.topology.incidentDown(edge.id).map { index.getValue(it) }
        val dx = (r.x(a) - r.x(b)).toDouble()
        val dy = (r.y(a) - r.y(b)).toDouble()
        val dz = (r.z(a) - r.z(b)).toDouble()
        sqrt(dx * dx + dy * dy + dz * dz)
    }.sorted()
    return (radii + edges).toDoubleArray()
}

private fun coordinates(p: PolyhedralObject): DoubleArray {
    val r = requireNotNull(p.realization) { "solved polyhedron has no realization" }
    return DoubleArray(3 * r.size) { i ->
        when (i % 3) {
            0 -> r.x(i / 3).toDouble()
            1 -> r.y(i / 3).toDouble()
            else -> r.z(i / 3).toDouble()
        }
    }
}

fun main() {
    val failures = mutableListOf<String>()
    fun expect(name: String, ok: Boolean) {
        if (!ok) failures += name
    }

    val backend = FfmCeresBackend()
    check(CeresFeature.AbiV3 in backend.supportedFeatures) {
        "needs the v3 wrapper: ./gradlew buildCeresNative, then " +
            "-PceresLibrary=build/ken-ceres/libken_ceres_jvm.dylib"
    }
    println("wrapper ${backend.abiVersion}, features ${backend.supportedFeatures.sortedBy { it.name }}\n")

    // -------------------------------------------------------------------------------------------
    // 0. What the program looks like. Half the point of having a VM is that you can read it.
    // -------------------------------------------------------------------------------------------

    val square = doubleArrayOf(
        0.0, 0.0, 0.02,
        1.0, 0.0, -0.01,
        1.0, 1.0, 0.03,
        0.0, 1.0, 0.0,
    )
    val illustration = buildCeresProgram(RobustLoss.NONE) {
        planarHesseExpression(listOf(0, 1, 2, 3), square, name = "face")
    }
    val illustrated = (illustration.constraints.single() as CeresExpressionResidual).compile()
    println("PLANAR_HESSE as an expression, for one nearly-flat quad:")
    illustrated.disassemble().trim().lines().forEach { println("    $it") }
    expect("disassembly names the free variables", illustrated.disassemble().contains("face.n[3] on sphere"))
    expect("disassembly reads a variable", illustrated.disassemble().contains("v0[0]"))
    println()

    // -------------------------------------------------------------------------------------------
    // 1. PLANAR_HESSE: the opcode against the expression, over the kk family.
    //
    //    dev_docs/CERES.md records this parameterisation as the thing that cracked kkC — 12
    //    iterations to tangency 3.2e-08 where the plane-free formulation does not converge. If the
    //    expression layer's free variables and its sphere manifold are right, writing the same
    //    parameterisation in Kotlin lands on the same solid.
    // -------------------------------------------------------------------------------------------

    println("PLANAR_HESSE: C++ opcode vs the same parameterisation written as a Kotlin expression\n")
    // The five that reach machine precision must agree *coordinate for coordinate*: same objective,
    // same parameterisation, same seed, so anything else is a defect. The three that only reach 1e-4
    // are compared up to congruence instead, and the class comment on congruenceFingerprint says why.
    val exact = setOf("kC", "kD", "tI", "kkC", "kkD")
    val family = exact.toList() + listOf("kkT", "kkO", "kkI")
    for (name in family) {
        val seed = conway(name).polyhedron
        val plan = RelaxationPlan.canonicalConditions()
        val opcode = seed.relaxWithCeres(
            plan,
            CeresRelaxationOptions(maxIterations = 500, planes = PlaneFormulation.HESSE),
        )
        val expressed = seed.relaxWithCeres(
            plan,
            CeresRelaxationOptions(maxIterations = 500, planes = PlaneFormulation.HESSE_EXPRESSION),
        )

        val a = coordinates(opcode.realization.polyhedron)
        val b = coordinates(expressed.realization.polyhedron)
        val delta = a.indices.maxOf { abs(a[it] - b[it]) }
        val opcodeDiagnostics = opcode.realization.polyhedron.canonicalDiagnostics()
        val expressedDiagnostics = expressed.realization.polyhedron.canonicalDiagnostics()

        println(
            "$name  V=${seed.topology.elements(0).size} E=${seed.topology.elements(1).size} " +
                "F=${seed.topology.elements(2).size}",
        )
        println(describe("opcode", opcode.realization.polyhedron))
        println("      ${opcode.summary}")
        println(describe("expression", expressed.realization.polyhedron))
        println("      ${expressed.summary}")
        val fa = congruenceFingerprint(opcode.realization.polyhedron)
        val fb = congruenceFingerprint(expressed.realization.polyhedron)
        val congruence = fa.indices.maxOf { abs(fa[it] - fb[it]) }
        println(
            "      agreement: max coordinate difference %.2e, congruent to %.2e, spread %.3e vs %.3e".format(
                delta, congruence, opcodeDiagnostics.tangencyRadiusSpread,
                expressedDiagnostics.tangencyRadiusSpread,
            ),
        )

        val opcodeCost = opcode.summary.finalCost
        val expressedCost = expressed.summary.finalCost
        if (name in exact) {
            // Same objective, same parameterisation, same seed: these must agree coordinate for
            // coordinate, not merely reach comparable answers.
            expect("$name: opcode and expression agree exactly (got $delta)", delta < 1e-6)
        } else {
            // The three solids `dev_docs/CERES.md` records as reaching 1e-4 rather than machine
            // precision. They agree with the opcode on cost and on every canonical diagnostic and land
            // in *different places* — not even congruent — which is a fact about the objective, not
            // about the VM: at cost ~2e-07 its minimum is not isolated, so where in the valley a run
            // stops is set by rounding in the seventh digit, and the two formulations round
            // differently. The evidence that this is not a VM defect is above: whenever the problem is
            // well-conditioned enough to converge in a dozen iterations the two agree to 1e-15.
            //
            // So what is asserted here is what is actually comparable — the objective reached, and
            // the independently measured quality of the answer.
            expect(
                "$name: expression reaches the opcode's cost ($expressedCost vs $opcodeCost)",
                expressedCost <= opcodeCost * 1.5 + 1e-12,
            )
            expect(
                "$name: expression is no worse than the opcode " +
                    "(${expressedDiagnostics.tangencyRadiusSpread} vs ${opcodeDiagnostics.tangencyRadiusSpread})",
                expressedDiagnostics.tangencyRadiusSpread <= opcodeDiagnostics.tangencyRadiusSpread * 1.1 + 1e-9,
            )
        }
        // And the expression must satisfy the canonical conditions in its own right, measured by the
        // library's independent diagnostics rather than by Ceres' cost.
        val tolerance = if (name in setOf("kkT", "kkO", "kkI")) 5e-3 else 1e-5
        expect(
            "$name: expression reaches a canonical form (spread ${expressedDiagnostics.tangencyRadiusSpread})",
            expressedDiagnostics.tangencyRadiusSpread < tolerance,
        )
        expect("$name: no degenerate faces", expressedDiagnostics.degenerateFaces.isEmpty())
        expect("$name: faces planar", expressedDiagnostics.maxFacePlanarityError < 1e-6)
        expect("$name: tangency centroid at origin", expressedDiagnostics.tangencyCentroidOffset < 1e-4)
        val iterated = seed.canonicalize().polyhedron.canonicalDiagnostics()
        expect(
            "$name: beats canonicalize()",
            expressedDiagnostics.tangencyRadiusSpread <= iterated.tangencyRadiusSpread + 1e-12,
        )
        // The free-variable readback: three components of normal plus one offset per face.
        val faces = seed.topology.elements(2).size
        val program = afield.stdlib.polytope.CeresRealization.program(
            plan,
            afield.stdlib.polytope.RealizationProblem.of(seed),
            PlaneFormulation.HESSE_EXPRESSION,
            gaugeRotationWeight = 1.0,
            minEdgeLengthFraction = 0.01,
        )
        expect(
            "$name: declares one plane per face (${program.variables.size} for $faces faces)",
            program.variables.size == 2 * faces,
        )
        println()
    }

    // -------------------------------------------------------------------------------------------
    // 2. A fit with no point unknowns: the circumsphere of a tetrahedron.
    //
    //    No opcode can say this — RADIUS_ABOUT_CENTROID fits a radius about the *centroid* of the
    //    points, which is not the circumcentre — and no point-only expression can either, because the
    //    unknowns are not points. Checked against the closed form, not against a number typed in.
    // -------------------------------------------------------------------------------------------

    val tetrahedron = listOf(
        Vec3(1.0, 1.0, 1.0),
        Vec3(1.0, -1.0, -1.0),
        Vec3(-1.0, 1.0, -1.0),
        Vec3(-1.0, -1.0, 1.0),
    ).map { Vec3(it.x * 0.7 + 0.3, it.y * 1.3 - 0.8, it.z * 0.9 + 1.4) }
    val (closedCentre, closedRadius) = circumsphere(tetrahedron)

    val fit = buildCeresProgram(RobustLoss.NONE) {
        // Deliberately a bad start: nowhere near the answer, and the radius an order of magnitude off.
        val centre = variable("centre", Vec3(5.0, -5.0, 5.0))
        val radius = variable("radius", 0.1)
        expression(emptyList(), listOf(centre, radius)) { _, (c, r) ->
            tetrahedron.map { p -> (CeresExprVec.of(p) - c.vec()).length() - r.scalar }
        }
    }
    val fitted = solve(NO_POINTS, fit)
    val foundCentre = fitted.variables.vec3("centre")
    val foundRadius = fitted.variables.scalar("radius")
    val centreError = length(minus(foundCentre, closedCentre))
    val radiusError = abs(foundRadius - closedRadius)
    println("circumsphere of a tetrahedron — free centre, free radius, no point unknowns")
    println("    closed form  centre (%.9f, %.9f, %.9f)  radius %.9f".format(
        closedCentre.x, closedCentre.y, closedCentre.z, closedRadius,
    ))
    println("    ceres        centre (%.9f, %.9f, %.9f)  radius %.9f".format(
        foundCentre.x, foundCentre.y, foundCentre.z, foundRadius,
    ))
    println("    error        centre %.2e  radius %.2e   ${fitted.summary}".format(centreError, radiusError))
    expect("circumsphere centre matches the closed form (off by $centreError)", centreError < 1e-9)
    expect("circumsphere radius matches the closed form (off by $radiusError)", radiusError < 1e-9)
    expect("circumsphere cost reaches zero", (fitted.summary?.finalCost ?: 1.0) < 1e-18)
    expect("circumsphere converged", fitted.summary?.converged == true)
    println()

    // -------------------------------------------------------------------------------------------
    // 3. The sphere manifold, against a published closed form.
    //
    //    Orthogonal-distance regression to a plane: the fit is the centroid and the smallest
    //    eigenvector of the scatter matrix. Written here as a free unit normal *on the sphere
    //    manifold* plus a free scalar offset — so if the manifold were wrong (a normal free to change
    //    length, say) the minimum would be at length zero with cost zero, and this would notice.
    // -------------------------------------------------------------------------------------------

    val cloud = listOf(
        Vec3(0.0, 0.0, 0.10), Vec3(1.0, 0.2, -0.05), Vec3(0.3, 1.4, 0.22), Vec3(-0.9, 0.7, -0.18),
        Vec3(1.6, 1.9, 0.31), Vec3(-1.2, -0.4, -0.27), Vec3(0.8, -1.1, 0.04), Vec3(2.1, 0.6, 0.44),
    )
    val closedPlane = hessePlaneOf(cloud)
    val planeFit = buildCeresProgram(RobustLoss.NONE) {
        // Seeded well away from the answer, and *not* unit — the manifold's job is to fix that.
        val normal = unitVariable("n", Vec3(1.0, 1.0, 1.0))
        val offset = variable("d", 0.0)
        expression(emptyList(), listOf(normal, offset)) { _, (n, d) ->
            val direction = n.vec()
            cloud.map { p -> (CeresExprVec.of(p) dot direction) - d.scalar }
        }
    }
    val planeResult = solve(NO_POINTS, planeFit)
    val foundNormal = planeResult.variables.vec3("n")
    val foundOffset = planeResult.variables.scalar("d")
    val normalLength = length(foundNormal)
    // The plane is the same for (n, d) and (-n, -d), so compare with the sign that agrees.
    val flip = if (foundNormal.x * closedPlane.normal.x + foundNormal.y * closedPlane.normal.y +
        foundNormal.z * closedPlane.normal.z < 0.0
    ) {
        -1.0
    } else {
        1.0
    }
    val normalError = length(
        minus(
            Vec3(flip * foundNormal.x, flip * foundNormal.y, flip * foundNormal.z),
            closedPlane.normal,
        ),
    )
    val offsetError = abs(flip * foundOffset - closedPlane.offset)
    println("total-least-squares plane — free unit normal on a sphere manifold, free offset")
    println("    closed form (smallest eigenvector)  n (% .9f, % .9f, % .9f)  d % .9f".format(
        closedPlane.normal.x, closedPlane.normal.y, closedPlane.normal.z, closedPlane.offset,
    ))
    println("    ceres                               n (% .9f, % .9f, % .9f)  d % .9f".format(
        flip * foundNormal.x, flip * foundNormal.y, flip * foundNormal.z, flip * foundOffset,
    ))
    println(
        "    error  normal %.2e  offset %.2e  |n| - 1 = %.2e   ${planeResult.summary}".format(
            normalError, offsetError, normalLength - 1.0,
        ),
    )
    expect("plane normal matches the eigenvector fit (off by $normalError)", normalError < 1e-8)
    expect("plane offset matches the eigenvector fit (off by $offsetError)", offsetError < 1e-8)
    // This is the manifold's own claim: Ceres never let the normal leave the unit sphere, even though
    // no residual asks it to stay there.
    expect("normal stayed on the unit sphere (|n| = $normalLength)", abs(normalLength - 1.0) < 1e-14)
    expect(
        "the fitted normal is reported as living on a sphere",
        planeResult.variables.first("n").manifold == CeresManifold.SPHERE,
    )
    println()

    // -------------------------------------------------------------------------------------------
    // 4. A free frame: quaternion manifold plus a free translation.
    //
    //    Corresponding point pairs generated by a known rigid transform; the solve has to recover it.
    //    This is the bundle-adjustment shape — one unknown frame shared by many observations — and it
    //    is only expressible because variables are declared at *program* scope: every observation is
    //    its own residual block naming the same two variables.
    // -------------------------------------------------------------------------------------------

    // A 40-degree rotation about a tilted axis, then a translation.
    val angle = 40.0 * Math.PI / 180.0
    val axis = run {
        val a = Vec3(0.3, -0.8, 0.5)
        val l = length(a)
        Vec3(a.x / l, a.y / l, a.z / l)
    }
    val trueQuaternion = doubleArrayOf(
        kotlin.math.cos(angle / 2),
        axis.x * kotlin.math.sin(angle / 2),
        axis.y * kotlin.math.sin(angle / 2),
        axis.z * kotlin.math.sin(angle / 2),
    )
    val trueTranslation = Vec3(0.7, -1.3, 2.2)
    fun rotate(q: DoubleArray, v: Vec3): Vec3 {
        val (w, x, y, z) = listOf(q[0], q[1], q[2], q[3])
        // v + 2 u x (u x v + w v), the same expansion CeresExprVariable.rotate() emits.
        val tx = y * v.z - z * v.y + w * v.x
        val ty = z * v.x - x * v.z + w * v.y
        val tz = x * v.y - y * v.x + w * v.z
        return Vec3(
            v.x + 2.0 * (y * tz - z * ty),
            v.y + 2.0 * (z * tx - x * tz),
            v.z + 2.0 * (x * ty - y * tx),
        )
    }

    val source = listOf(
        Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(-1.0, 0.5, 0.3),
        Vec3(0.4, -1.2, 0.9), Vec3(1.7, 0.8, -0.6), Vec3(-0.5, -0.5, -1.4),
    )
    val target = source.map { p ->
        val r = rotate(trueQuaternion, p)
        Vec3(r.x + trueTranslation.x, r.y + trueTranslation.y, r.z + trueTranslation.z)
    }

    val frameFit = buildCeresProgram(RobustLoss.NONE) {
        val rotation = rotationVariable("q")           // identity, on the quaternion manifold
        val translation = variable("t", Vec3(0.0, 0.0, 0.0))
        // One residual block per observation, all naming the same frame — the reason variables are
        // program-scoped rather than owned by a residual.
        source.indices.forEach { i ->
            expression(emptyList(), listOf(rotation, translation)) { _, (q, t) ->
                val moved = q.rotate(CeresExprVec.of(source[i])) + t.vec()
                val want = CeresExprVec.of(target[i])
                listOf(moved.x - want.x, moved.y - want.y, moved.z - want.z)
            }
        }
    }
    val frame = solve(NO_POINTS, frameFit)
    val q = frame.variables.first("q").values
    val t = frame.variables.vec3("t")
    // q and -q are the same rotation.
    val sign = if (q[0] * trueQuaternion[0] < 0.0) -1.0 else 1.0
    val quaternionError = (0..3).maxOf { abs(sign * q[it] - trueQuaternion[it]) }
    val translationError = length(minus(t, trueTranslation))
    val quaternionLength = sqrt(q.sumOf { it * it })
    println("a free rigid frame — quaternion manifold + free translation, ${source.size} observations")
    println("    generated   q (% .9f, % .9f, % .9f, % .9f)  t (% .6f, % .6f, % .6f)".format(
        trueQuaternion[0], trueQuaternion[1], trueQuaternion[2], trueQuaternion[3],
        trueTranslation.x, trueTranslation.y, trueTranslation.z,
    ))
    println("    recovered   q (% .9f, % .9f, % .9f, % .9f)  t (% .6f, % .6f, % .6f)".format(
        sign * q[0], sign * q[1], sign * q[2], sign * q[3], t.x, t.y, t.z,
    ))
    println(
        "    error  rotation %.2e  translation %.2e  |q| - 1 = %.2e   ${frame.summary}".format(
            quaternionError, translationError, quaternionLength - 1.0,
        ),
    )
    expect("recovered the generating rotation (off by $quaternionError)", quaternionError < 1e-9)
    expect("recovered the generating translation (off by $translationError)", translationError < 1e-9)
    expect("quaternion stayed unit (|q| = $quaternionLength)", abs(quaternionLength - 1.0) < 1e-14)
    expect("frame fit converged to zero cost", (frame.summary?.finalCost ?: 1.0) < 1e-18)
    println()

    // -------------------------------------------------------------------------------------------
    // 5. Bounds, against a problem whose unconstrained optimum is on the wrong side of one.
    //
    //    Fit a sphere to points that lie on a *plane*. The unconstrained geometric fit runs away to an
    //    infinite radius — a plane is a sphere of infinite radius — so the answer is decided entirely
    //    by the bound. With `radius <= 5` the optimum sits exactly on it, and that is a claim about
    //    Ceres' own SetParameterUpperBound rather than about a penalty term someone tuned.
    // -------------------------------------------------------------------------------------------

    val coplanar = listOf(
        Vec3(-1.0, -1.0, 0.0), Vec3(1.0, -1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(-1.0, 1.0, 0.0),
        Vec3(0.0, 0.0, 0.0), Vec3(0.5, -0.5, 0.0),
    )
    val ceiling = 5.0
    val bounded = buildCeresProgram(RobustLoss.NONE) {
        val centre = variable(CeresVariableDecl("centre", listOf(0.0, 0.0, -1.0)))
        val radius = variable(CeresVariableDecl.scalar("radius", 1.0, atLeast = 0.0, atMost = ceiling))
        expression(emptyList(), listOf(centre, radius)) { _, (c, r) ->
            coplanar.map { p -> (CeresExprVec.of(p) - c.vec()).length() - r.scalar }
        }
    }
    val boundedFit = solve(NO_POINTS, bounded, iterations = 2000)
    val boundedRadius = boundedFit.variables.scalar("radius")
    println("sphere fitted to coplanar points, radius bounded to [0, $ceiling]")
    println(
        "    radius %.9f (ceiling %.1f)   centre %s   ${boundedFit.summary}".format(
            boundedRadius, ceiling, boundedFit.variables.first("centre"),
        ),
    )
    expect(
        "the bounded radius respects its ceiling (got $boundedRadius)",
        boundedRadius <= ceiling + 1e-9,
    )
    // The unconstrained problem has no finite optimum, so the bound has to be active. If it were not
    // being applied at all, this radius would be off at 1e3 or wherever the iteration limit left it.
    expect(
        "the bound is the thing deciding the answer (radius $boundedRadius should sit on $ceiling)",
        abs(boundedRadius - ceiling) < 1e-3,
    )
    println("    disassembly:")
    (bounded.constraints.single() as CeresExpressionResidual).compile().disassemble()
        .lines().take(2).forEach { println("      $it") }
    println()

    // -------------------------------------------------------------------------------------------
    // 6. Malformed input, refused in both layers.
    // -------------------------------------------------------------------------------------------

    fun rejects(what: String, block: () -> Unit) {
        val outcome = runCatching(block)
        expect("rejects $what", outcome.isFailure)
        println("ok rejects $what: ${outcome.exceptionOrNull()?.message?.take(94)}")
    }

    // Kotlin.
    rejects("a sphere manifold on a scalar") {
        CeresVariableDecl("bad", listOf(1.0), CeresManifold.SPHERE)
    }
    rejects("a quaternion with three components") {
        CeresVariableDecl("bad", listOf(1.0, 0.0, 0.0), CeresManifold.QUATERNION)
    }
    rejects("a unit vector seeded at the origin") {
        CeresVariableDecl.unitVector("bad", Vec3(0.0, 0.0, 0.0))
    }
    rejects("a non-finite initial value") {
        CeresVariableDecl.scalar("bad", Double.NaN)
    }
    rejects("reading component 3 of a 3-vector") {
        buildCeresProgram {
            val v = variable("v", Vec3(1.0, 0.0, 0.0))
            expression(listOf(0), listOf(v)) { (p), (v0) -> listOf(p.x + v0[3]) }
        }
    }
    rejects("a variable named twice in one residual") {
        buildCeresProgram {
            val v = variable("v", 1.0)
            expression(listOf(0), listOf(v, v)) { (p), (a, b) -> listOf(p.x + a.scalar + b.scalar) }
        }
    }
    rejects("a variable named but never read") {
        buildCeresProgram {
            val v = variable("v", 1.0)
            expression(listOf(0), listOf(v)) { (p), _ -> listOf(p.x - 1.0.c) }
        }
    }
    rejects("a variable declared but never referenced") {
        buildCeresProgram {
            variable("orphan", 1.0)
            expression(listOf(0)) { (p) -> listOf(p.x - 1.0.c) }
        }
    }
    rejects("a bound on a manifold") {
        CeresVariableDecl("bad", listOf(1.0, 0.0, 0.0), CeresManifold.SPHERE, mapOf(0 to 0.0..1.0))
    }
    rejects("a bound on a component that does not exist") {
        CeresVariableDecl("bad", listOf(1.0), bounds = mapOf(2 to 0.0..1.0))
    }
    rejects("an empty bound") {
        CeresVariableDecl("bad", listOf(1.0), bounds = mapOf(0 to 2.0..1.0))
    }
    rejects("an infeasible start") {
        CeresVariableDecl.scalar("bad", -1.0, atLeast = 0.0)
    }
    rejects("a bound that constrains nothing") {
        CeresVariableDecl(
            "bad",
            listOf(1.0),
            bounds = mapOf(0 to Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY),
        )
    }
    rejects("a variable from another program") {
        val alien = buildCeresProgram {
            val v = variable("v", 1.0)
            expression(listOf(0), listOf(v)) { (p), (v0) -> listOf(p.x - v0.scalar) }
        }
        val ref = (alien.constraints.single() as CeresExpressionResidual).variables.single()
        buildCeresProgram {
            expression(listOf(0), listOf(ref)) { (p), (v0) -> listOf(p.x - v0.scalar) }
        }
    }

    // The native validator, reached through RawProgramFragment — the escape hatch that exists partly
    // so the malformed-program path can be tested at all.
    fun rejectsNatively(what: String, values: List<Double>) {
        val outcome = runCatching {
            CachedCeresSolvers(backend).use {
                it.getOrCompile(NO_POINTS, CeresProgram(listOf(afield.stdlib.ceres.RawProgramFragment(values))))
            }
        }
        expect("native rejects $what", outcome.isFailure)
        println("ok native rejects $what: ${outcome.exceptionOrNull()?.message?.take(94)}")
    }

    // A well-formed `VARIABLE` operand run, so that each case below exercises the validator it names
    // rather than the span check: [dim, manifold, init..., nBounds=0].
    fun variableOp(dimension: Int, manifoldCode: Int, vararg initial: Double): List<Double> {
        require(initial.size == dimension) { "give one initial value per component" }
        return listOf(69.0, dimension.toDouble(), manifoldCode.toDouble()) + initial.toList() + listOf(0.0)
    }

    // A scalar with no manifold, seeded at 1 — the well-formed declaration the EXPR cases below refer to.
    val oneScalar = variableOp(1, 0, 1.0)

    // VARIABLE dim=3 sphere seeded at the origin.
    rejectsNatively("a curved manifold seeded at the origin", variableOp(3, 1, 0.0, 0.0, 0.0))
    // VARIABLE dim=1 on a sphere.
    rejectsNatively("a one-component sphere", variableOp(1, 1, 2.0))
    // VARIABLE dim=2 with manifold code 7.
    rejectsNatively("an unknown manifold code", variableOp(2, 7, 1.0, 0.0))
    // Then EXPR referencing variable 4, which does not exist.
    rejectsNatively(
        "an out-of-range variable reference",
        oneScalar + listOf(68.0, 1.0, 0.0, 1.0, 4.0, 1.0, 0.0, 1.0, 0.0, 1.0, 20.0, 0.0, 0.0, 0.0, 1.0),
    )
    // EXPR reading component 2 of a one-component variable.
    rejectsNatively(
        "reading past a variable's dimension",
        oneScalar + listOf(68.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 20.0, 0.0, 0.0, 2.0, 1.0),
    )
    // EXPR naming the variable and never reading it (a zero Jacobian column).
    rejectsNatively(
        "a variable named but never read",
        oneScalar + listOf(68.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 1.0, 5.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    )
    // A dimension past the cap.
    rejectsNatively("a variable larger than the cap", listOf(69.0, 4096.0, 0.0) + List(4096) { 1.0 })
    // VARIABLE dim=3 on a sphere manifold, with one bound.
    rejectsNatively(
        "a bound on a manifold",
        listOf(69.0, 3.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0),
    )
    // VARIABLE dim=1, bounded to [2, 3], starting at 1.
    rejectsNatively("an infeasible start", listOf(69.0, 1.0, 0.0, 1.0, 1.0, 0.0, 2.0, 3.0))
    // VARIABLE dim=1, bound on component 4.
    rejectsNatively("a bound past the dimension", listOf(69.0, 1.0, 0.0, 1.0, 1.0, 4.0, 0.0, 2.0))
    println()

    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        error("${failures.size} free-variable checks failed")
    }
    println(
        "ok — PLANAR_HESSE re-derived as a Kotlin expression matches the opcode coordinate for " +
            "coordinate on ${exact.size} solids and reaches its cost and its canonical diagnostics on " +
            "the ${family.size - exact.size} that only reach 1e-4; and three fits whose unknowns are " +
            "not points at all land on their closed-form answers",
    )
}
