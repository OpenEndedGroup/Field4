package afield.solidsl

import afield.manifold.ManifoldLibrary
import afield.stdlib.TriMesh
import afield.stdlib.samplePoints
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Is the cross-validation actually cross-validating anything?**
 *
 * `SolidSLCrossValidationTest` reports that eight of eleven shapes take the exact CSG path and three
 * refuse. That claim is load-bearing, and up to now it rested on reading the implementation:
 * `SolidBuild.isExact` is `marchedSubtrees.isEmpty()`, and `buildHybrid` only appends to that list
 * inside `marchSubtree`. True, and not evidence — it is the code restating its own bookkeeping.
 *
 * The failure mode being ruled out is specific and it reads exactly like success. If a shape refuses,
 * `buildSolid` marches it with `Solid.levelSet` over `SolidExpr.toLevelSetField()` — **the same field
 * object graph** the marched side samples. Two marchers, one field. That still catches a broken
 * marcher, but it cannot catch a wrong *field*: if `toField3()` described the wrong solid, both sides
 * would describe the same wrong solid and agree perfectly. Folding those three into a single
 * eleven-shape agreement figure would be exactly the overclaim this project keeps catching.
 *
 * So this file establishes the split by observation, twice over, and separately audits whether the
 * membership sampling can find a disagreement at all.
 *
 * # 1. Independence, by a property the two paths cannot share
 *
 * The exact lowering **has no `edgeLength` input**. Nothing in `buildExactSolid` reads
 * [SolidQuality.edgeLength] or [SolidQuality.edgeLengthFraction]; a level set reads nothing else. So:
 *
 *  - a shape whose "exact" side is genuinely exact must return a **bit-identical** volume, area and
 *    triangle count when the march is refined, and
 *  - a shape whose "exact" side is a level set in disguise **must change**.
 *
 * That is a mechanism-free discriminator. It does not consult `isExact`, `marchedSubtrees`, or any
 * other thing this package says about itself — it varies an input the two paths respond to
 * differently and watches.
 *
 * # 2. Independence again, by triangle count
 *
 * Corroboration that needs no argument at all: `polyPlate`'s exact solid is **92 triangles** and
 * `lBracketCut`'s is **20**. There is no march at any usable resolution that produces a
 * twenty-triangle surface for a shape whose field-marched counterpart has fifty thousand. A number
 * that small is a CSG kernel emitting the actual faces of the actual solid.
 *
 * # 3. Whether the membership test can fail
 *
 * `0 disagreements in 29,635 samples` is a weak number and this file says so. Those points are
 * uniform in a box and, worse, the band within `0.03·span` of the surface is **deliberately skipped**
 * — which is precisely where two evaluations of one shape can differ. A test that samples mostly
 * empty space will report perfect agreement for two quite different solids.
 *
 * So the sampling is redone as a **ladder**: points projected onto the field's own zero set by
 * Newton iteration, then offset along the gradient by a band `δ` that shrinks by decades. The
 * question stops being "do they agree" and becomes "**how close to the surface do you have to get
 * before they stop agreeing, and does that distance match the faceting sagitta?**"
 *
 * The polyhedral shapes are the control. They have no faceting, so if they disagree at some `δ` the
 * cause is the raycast's own precision and not geometry — which is the difference between a
 * measurement and a coincidence.
 */
class SolidSLIndependenceTest {

    private val segments = 256

    /** The same corpus, restated here rather than shared, so this file's claim stands on its own. */
    private data class Case(val name: String, val expr: SolidExpr, val polyhedral: Boolean, val expectedExact: Boolean)

    private val corpus: List<Case> by lazy {
        listOf(
            Case(
                "polyPlate",
                cuboid(4.0, 3.0, 1.0) - unionOf(
                    listOf(-1.2 to -0.9, 1.2 to -0.9, -1.2 to 0.9, 1.2 to 0.9).map { (x, y) ->
                        Profile.rect(0.5, 0.5).extruded(3.0).at(x, y, -1.5)
                    },
                ),
                polyhedral = true, expectedExact = true,
            ),
            Case(
                "lBracketCut",
                lProfile().extruded(1.5) - cuboid(0.7, 0.7, 4.0).turned(0.0, 0.0, 30.0).at(1.4, 1.4, 0.0),
                polyhedral = true, expectedExact = true,
            ),
            Case("starTaper", Profile.star(5, 1.0, 0.45).extruded(1.2, scaleTop = 0.45), true, true),
            Case("roundedSlot", Profile.rect(2.6, 0.9).offset(0.45, segments = segments).extruded(1.0), false, true),
            Case("ring", torus(1.2, 0.35, segments = segments), false, true),
            Case("wedge", Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(270.0, segments = segments), false, true),
            Case(
                "boredBoss",
                (cuboid(3.0, 3.0, 0.8) + cylinder(1.6, 0.8, segments = segments)) -
                    cylinder(4.0, 0.35, segments = segments),
                polyhedral = false, expectedExact = true,
            ),
            Case(
                "lens",
                sphere(1.0, segments = segments) and sphere(1.0, segments = segments).at(1.0, 0.0, 0.0),
                polyhedral = false, expectedExact = true,
            ),
            Case(
                "blend",
                smoothUnion(sphere(0.8, segments = segments), cuboid(1.6, 0.4, 0.4), k = 0.3),
                polyhedral = false, expectedExact = false,
            ),
            Case("gyroidCore", gyroid(3.0, 0.25) and cuboid(1.8, 1.8, 1.8), false, false),
            Case(
                "hybridPlate",
                cuboid(3.0, 3.0, 0.5) + smoothUnion(
                    sphere(0.7, segments = segments).at(0.0, 0.0, 0.6),
                    cylinder(1.6, 0.3, segments = segments),
                    k = 0.25,
                ),
                polyhedral = false, expectedExact = false,
            ),
        )
    }

    private fun lProfile(): Profile = Profile.polygon(
        listOf(P2(-1, -1), P2(1, -1), P2(1, 0), P2(0, 0), P2(0, 1), P2(-1, 1)),
    )

    // ==========================================================================================
    // 1. Which shapes are genuinely compared against an independent kernel
    // ==========================================================================================

    @Test
    fun `refining the march must not move a genuinely exact solid, and must move a level set`() {
        if (!requireManifold()) return
        val coarse = SolidQuality(segments = segments, edgeLengthFraction = 1.0 / 40.0)
        val fine = SolidQuality(segments = segments, edgeLengthFraction = 1.0 / 100.0)

        println("%-13s %-9s %14s %14s %9s %9s %s".format("case", "declared", "volume@1/40", "volume@1/100", "tris@40", "tris@100", "verdict"))
        var independent = 0
        var selfConsistentOnly = 0

        corpus.forEach { case ->
            val a = measure(case.expr, coarse)
            val b = measure(case.expr, fine)
            // The discriminator: the exact lowering has no edgeLength input at all, so a genuinely
            // exact solid is bit-identical under refinement and a level set cannot be.
            val unmoved = a.volume == b.volume && a.area == b.area && a.triangles == b.triangles
            val verdict = if (unmoved) "INDEPENDENT of the march" else "moves with the march"
            println(
                "%-13s %-9s %14.9f %14.9f %9d %9d %s".format(
                    case.name, if (case.expectedExact) "exact" else "levelset",
                    a.volume, b.volume, a.triangles, b.triangles, verdict,
                ),
            )
            if (unmoved) independent++ else selfConsistentOnly++

            assertEquals(
                case.expectedExact, unmoved,
                "${case.name}: declared ${if (case.expectedExact) "exact" else "a level set"}, but its " +
                    "solid ${if (unmoved) "did not move" else "moved"} when the march was refined " +
                    "(${a.volume} -> ${b.volume}). Either the declaration or the lowering is wrong.",
            )
            // And the bookkeeping this package keeps about itself must agree with the observation.
            assertEquals(
                case.expectedExact, a.isExact,
                "${case.name}: SolidBuild.isExact disagrees with the refinement experiment",
            )
        }

        println()
        println("$independent of ${corpus.size} shapes have an exact side that is INDEPENDENT of the field march")
        println("$selfConsistentOnly of ${corpus.size} are two marchers over one field — self-consistent only")
        assertEquals(8, independent, "the headline claims eight genuinely independent comparisons")
        assertEquals(3, selfConsistentOnly)
    }

    @Test
    fun `a twenty-triangle solid cannot be a march, and that needs no argument`() {
        if (!requireManifold()) return
        val q = SolidQuality(segments = segments, edgeLengthFraction = 1.0 / 90.0)
        // Corroboration that consults nothing this package says about itself. A CSG kernel emits the
        // actual faces of the actual solid; a marcher emits one or two triangles per surface cell.
        println("%-13s %10s %10s %8s".format("case", "exact tris", "march tris", "ratio"))
        corpus.filter { it.polyhedral }.forEach { case ->
            val exact = case.expr.exactSolid(q).orThrow().use { it.triangleCount }
            val marched = case.expr.marchedMesh(q).triangleCount
            println("%-13s %10d %10d %7.0fx".format(case.name, exact, marched, marched.toDouble() / exact))
            assertTrue(
                exact < 200,
                "${case.name}: a polyhedral solid's exact form should be a couple of dozen faces; got $exact",
            )
            assertTrue(
                marched > 100 * exact,
                "${case.name}: the marched surface should be orders of magnitude denser; got $marched vs $exact",
            )
        }
    }

    @Test
    fun `the refused three name a refusal, and the exact eight name none`() {
        // Symbolic, so no Manifold needed. The third leg: the declared split must match what the
        // algebra itself says, not only what the runtime did.
        val refusing = corpus.filter { it.expr.exactRefusal() != null }.map { it.name }.toSet()
        assertEquals(setOf("blend", "gyroidCore", "hybridPlate"), refusing)
        corpus.filter { it.expectedExact }.forEach {
            assertEquals(null, it.expr.exactRefusal(), "${it.name} must have an exact form")
        }
    }

    // ==========================================================================================
    // 2. Can the membership test fail at all?
    // ==========================================================================================

    private data class Band(val delta: Double, val tested: Int, val wrong: Int) {
        val rate: Double get() = if (tested == 0) 0.0 else wrong.toDouble() / tested
    }

    @Test
    fun `membership disagreement appears only inside the faceting band`() {
        if (!requireManifold()) return
        val q = SolidQuality(segments = segments, edgeLengthFraction = 1.0 / 90.0)
        val fractions = listOf(1e-2, 1e-3, 1e-4, 1e-5, 1e-6)

        println("Disagreement rate against distance from the surface, as a fraction of the shape's span.")
        println("%-13s %-6s %9s %9s %9s %9s %9s %12s".format(
            "case", "poly", "1e-2", "1e-3", "1e-4", "1e-5", "1e-6", "sagitta/span",
        ))

        val polyhedralRates = mutableMapOf<Double, MutableList<Double>>()
        val curvedRates = mutableMapOf<Double, MutableList<Double>>()

        corpus.filter { it.expectedExact }.forEach { case ->
            val span = case.expr.bounds()!!.let { maxOf(it.size.x, it.size.y, it.size.z).toDouble() }
            val field = case.expr.toField3()
            val surface = case.expr.marchedMesh(q)
            val bands = case.expr.exactSolid(q).orThrow().use { solid ->
                fractions.map { f -> sampleBand(field, surface, solid, span * f, span) }
            }
            val sagitta = predictedSagitta(case.expr) / span
            println(
                "%-13s %-6s %8.2f%% %8.2f%% %8.2f%% %8.2f%% %8.2f%% %12.2e".format(
                    case.name, if (case.polyhedral) "yes" else "no",
                    *bands.map { 100 * it.rate }.toTypedArray(), sagitta,
                ),
            )
            bands.forEachIndexed { i, b ->
                val bucket = if (case.polyhedral) polyhedralRates else curvedRates
                bucket.getOrPut(fractions[i]) { mutableListOf() } += b.rate
                assertTrue(b.tested > 300, "${case.name} at ${fractions[i]}: only ${b.tested} usable samples")
            }

            // Per shape, and this is the quantitative claim rather than a trend: the crossover sits
            // at the sagitta. Every band comfortably outside Manifold's inscribed-polygon error must
            // agree completely, and a band well inside it must not — because inside it the two
            // surfaces are genuinely in different places and a test that still agreed would be
            // reporting on something other than the geometry.
            fractions.forEachIndexed { i, f ->
                if (f > 4.0 * sagitta) {
                    assertTrue(
                        bands[i].rate < 0.01,
                        "${case.name}: disagrees ${100 * bands[i].rate}% at delta = $f of the span, which is " +
                            "well outside the faceting sagitta ($sagitta) — faceting cannot explain this",
                    )
                }
            }
            if (!case.polyhedral) {
                val deepest = bands.last()
                assertTrue(
                    fractions.last() < 0.2 * sagitta,
                    "${case.name}: the deepest band (${fractions.last()}) is not inside the sagitta " +
                        "($sagitta), so this shape's crossover was never actually probed",
                )
                assertTrue(
                    deepest.rate > 0.02,
                    "${case.name}: at ${fractions.last()} of the span the two surfaces are separated by " +
                        "${sagitta / fractions.last()}x this distance, so membership MUST start " +
                        "disagreeing. It reported ${100 * deepest.rate}%, which means the sampling is " +
                        "not reaching the surface and an agreement figure from it measures nothing",
                )
            }
        }

        println()
        fractions.forEach { f ->
            println(
                "  delta = %-7s polyhedral %6.2f%%   curved %6.2f%%".format(
                    f, 100 * polyhedralRates[f]!!.average(), 100 * curvedRates[f]!!.average(),
                ),
            )
        }

        // The control. A polyhedron has no faceting: Manifold's faces and the field's faces are the
        // same planes, so the two must classify identically however close to the surface you get.
        // If THIS row rises, the cause is the raycast's precision and the curved numbers below mean
        // nothing.
        fractions.forEach { f ->
            val rate = polyhedralRates[f]!!.max()
            assertTrue(
                rate < 0.01,
                "the polyhedral control disagrees at ${100 * rate}% at delta = $f of the span — that is " +
                    "raycast noise, not faceting, and it invalidates the curved measurement below",
            )
        }

        // And the finding. Far from the surface the two lowerings of a curved shape agree completely;
        // inside the sagitta of Manifold's inscribed polygon they cannot, because there the two
        // surfaces are genuinely in different places. A membership test that never gets this close
        // is not testing the thing it claims to.
        assertTrue(
            curvedRates[1e-2]!!.max() < 0.005,
            "a curved shape should agree essentially perfectly at 1e-2 of its span from the surface",
        )
        assertTrue(
            curvedRates[1e-6]!!.average() > 0.05,
            "at 1e-6 of the span the two surfaces are separated by far more than that, so a real " +
                "membership test MUST start disagreeing. It did not (${curvedRates[1e-6]!!.average()}), " +
                "which means the sampling is not reaching the surface and the headline agreement " +
                "figure is measuring nothing.",
        )
    }

    @Test
    fun `the uniform sampler used by the headline test is the weak one, and here is how weak`() {
        if (!requireManifold()) return
        // Published as `0 disagreements in 29,635 samples`. This measures what that number is worth:
        // how many of those points were anywhere near a surface at all.
        val q = SolidQuality(segments = segments)
        var total = 0
        var withinOnePercent = 0
        var withinSagitta = 0
        var survivingTheSkip = 0
        var survivingAndWithinSagitta = 0
        var worstSagittaOverSkip = 0.0
        corpus.filter { it.expectedExact }.forEach { case ->
            val tight = case.expr.bounds()!!
            val span = maxOf(tight.size.x, tight.size.y, tight.size.z).toDouble()
            val b = tight.expandedBy(0.25 * span)
            val field = case.expr.toField3()
            val sagitta = predictedSagitta(case.expr)
            // The headline test's own skip band, quoted rather than guessed.
            val skip = 0.03 * span
            worstSagittaOverSkip = maxOf(worstSagittaOverSkip, sagitta / skip)
            val random = kotlin.random.Random(11L)
            repeat(4000) {
                val x = b.min.x + random.nextDouble().toFloat() * b.size.x
                val y = b.min.y + random.nextDouble().toFloat() * b.size.y
                val z = b.min.z + random.nextDouble().toFloat() * b.size.z
                val d = abs(field.at(x.toDouble(), y.toDouble(), z.toDouble()))
                total++
                if (d < 0.01 * span) withinOnePercent++
                if (d < sagitta) withinSagitta++
                if (d >= skip) {
                    survivingTheSkip++
                    if (d < sagitta) survivingAndWithinSagitta++
                }
            }
        }
        println("uniform box sampling, ${corpus.count { it.expectedExact }} shapes, $total points:")
        println("  within 1% of the span of a surface: $withinOnePercent (${"%.2f".format(100.0 * withinOnePercent / total)}%)")
        println("  within the faceting sagitta:        $withinSagitta (${"%.4f".format(100.0 * withinSagitta / total)}%)")
        println("  surviving the headline test's 3%-of-span skip: $survivingTheSkip")
        println("  ...of which within the sagitta: $survivingAndWithinSagitta")
        println("  largest sagitta as a fraction of the skip band: ${"%.2e".format(worstSagittaOverSkip)}")

        // This is the retraction, made mechanical. The headline membership test discards every point
        // within 3% of the span of a surface, and the largest faceting sagitta in the corpus is about
        // four orders of magnitude smaller than that. So *not one* of its 29,635 samples was ever in
        // a position to disagree for a geometric reason. The number is a real check against a
        // mirrored or transposed lowering — a gross error is visible from anywhere — and it is not
        // evidence about surface fidelity, which is what the ladder above is for.
        assertEquals(
            0, survivingAndWithinSagitta,
            "if the headline sampler did reach the sagitta this retraction would be too harsh and " +
                "should be softened; it does not",
        )
        assertTrue(
            worstSagittaOverSkip < 1e-2,
            "the skip band is meant to be orders of magnitude wider than any faceting error; got " +
                "a ratio of $worstSagittaOverSkip",
        )
        assertTrue(
            withinOnePercent < total / 20,
            "under 5% of uniform samples land within 1% of the span of a surface; got $withinOnePercent/$total",
        )
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private data class Measured(val volume: Double, val area: Double, val triangles: Long, val isExact: Boolean)

    private fun measure(expr: SolidExpr, q: SolidQuality): Measured =
        expr.buildSolid(q).use { Measured(it.solid.volume, it.solid.surfaceArea, it.solid.triangleCount, it.isExact) }

    /**
     * The largest gap between a curved primitive in this expression and Manifold's inscribed polygon
     * approximation of it: `r · (1 − cos(π/n))`.
     *
     * Zero for a wholly polyhedral shape, which is what makes those the control. Transforms that
     * scale are folded in, because a sphere scaled by three has three times the sagitta.
     */
    private fun predictedSagitta(e: SolidExpr, scale: Double = 1.0): Double {
        fun seg(n: Int, r: Double) = effectiveSegments(r, if (n != 0) n else segments)
        fun sag(r: Double, n: Int) = scale * r * (1.0 - cos(Math.PI / seg(n, r)))
        return when (e) {
            is SolidExpr.Sphere -> sag(e.radius, e.segments)
            is SolidExpr.Cylinder -> sag(maxOf(e.radiusLow, e.radiusHigh), e.segments)
            is SolidExpr.Cuboid, SolidExpr.Empty -> 0.0
            is SolidExpr.Gyroid -> 0.0
            is SolidExpr.Extrude -> profileSagitta(e.profile, scale * maxOf(1.0, e.scaleTop))
            is SolidExpr.Revolve -> maxOf(
                profileSagitta(e.profile, scale),
                // the revolution itself is an n-gon sweep of the profile's farthest x
                sag(e.profile.bounds()?.maxX ?: 0.0, e.segments),
            )
            is SolidExpr.Scale -> predictedSagitta(e.child, scale * maxOf(abs(e.x), maxOf(abs(e.y), abs(e.z))))
            else -> e.children().maxOfOrNull { predictedSagitta(it, scale) } ?: 0.0
        }
    }

    private fun profileSagitta(p: Profile, scale: Double): Double = when (p) {
        is Profile.Circle -> scale * p.radius *
            (1.0 - cos(Math.PI / effectiveSegments(p.radius, if (p.segments != 0) p.segments else segments)))
        is Profile.Offset -> maxOf(
            scale * abs(p.delta) *
                (1.0 - cos(Math.PI / effectiveSegments(abs(p.delta), if (p.segments != 0) p.segments else segments))),
            profileSagitta(p.child, scale),
        )
        is Profile.Rect, is Profile.Polygon, Profile.Empty -> 0.0
        is Profile.Translate -> profileSagitta(p.child, scale)
        is Profile.Rotate -> profileSagitta(p.child, scale)
        is Profile.Scale -> profileSagitta(p.child, scale * maxOf(abs(p.x), abs(p.y)))
        is Profile.Union -> p.children.maxOf { profileSagitta(it, scale) }
        is Profile.Intersect -> p.children.maxOf { profileSagitta(it, scale) }
        is Profile.Difference -> p.children.maxOf { profileSagitta(it, scale) }
    }

    /**
     * Sample points at distance [delta] from the field's zero set, on both sides, and ask both
     * lowerings which side they are on.
     *
     * The seed distribution is the marched surface, which spreads points over the shape by area
     * rather than bunching them where a bounding box happens to be thin. But a marched vertex is not
     * *on* the field's zero set — it is off by up to the chamfer at an edge — so each one is Newton-
     * projected onto the true zero set first. Without that, points near an edge would be silently
     * dropped by the sign guard and the sample would drift toward flat faces, where agreement is
     * trivial.
     */
    private fun sampleBand(
        field: Field3,
        surface: TriMesh,
        solid: afield.manifold.Solid,
        delta: Double,
        span: Double,
    ): Band {
        var tested = 0
        var wrong = 0
        val h = 1e-5 * span
        for (p in surface.samplePoints(600, seed = 5L)) {
            var x = p.x.toDouble(); var y = p.y.toDouble(); var z = p.z.toDouble()
            // Five Newton steps along the gradient put the point on the field's own surface.
            repeat(5) {
                val d = field.at(x, y, z)
                val gx = (field.at(x + h, y, z) - field.at(x - h, y, z)) / (2 * h)
                val gy = (field.at(x, y + h, z) - field.at(x, y - h, z)) / (2 * h)
                val gz = (field.at(x, y, z + h) - field.at(x, y, z - h)) / (2 * h)
                val g2 = gx * gx + gy * gy + gz * gz
                if (g2 < 1e-18) return@repeat
                x -= d * gx / g2; y -= d * gy / g2; z -= d * gz / g2
            }
            if (abs(field.at(x, y, z)) > 1e-3 * delta.coerceAtLeast(1e-9) + 1e-9) {
                // Newton did not land; a kink in the field, not a disagreement. Drop it.
                if (abs(field.at(x, y, z)) > 0.05 * delta) continue
            }
            val gx = (field.at(x + h, y, z) - field.at(x - h, y, z)) / (2 * h)
            val gy = (field.at(x, y + h, z) - field.at(x, y - h, z)) / (2 * h)
            val gz = (field.at(x, y, z + h) - field.at(x, y, z - h)) / (2 * h)
            val len = sqrt(gx * gx + gy * gy + gz * gz)
            if (len < 1e-12) continue
            for (sign in intArrayOf(1, -1)) {
                val qx = x + sign * delta * gx / len
                val qy = y + sign * delta * gy / len
                val qz = z + sign * delta * gz / len
                val d = field.at(qx, qy, qz)
                // Only use the point when the field agrees we moved to the side we meant to; near a
                // concave crease the gradient step can cross back.
                if ((d < 0) != (sign < 0)) continue
                tested++
                if ((d < 0) != solid.contains(qx, qy, qz)) wrong++
            }
        }
        return Band(delta, tested, wrong)
    }

    companion object {
        private var announced = false

        fun requireManifold(): Boolean {
            if (ManifoldLibrary.isAvailable) return true
            if (!announced) {
                announced = true
                println("SolidSLIndependenceTest: skipping — ${ManifoldLibrary.describe()}")
            }
            return false
        }
    }
}
