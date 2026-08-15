package afield.solidsl

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import kotlin.math.abs
import kotlin.test.Test

/**
 * A scratch probe, not an assertion: prints what Manifold actually does for the handful of
 * conventions `afield.solidsl` has to match exactly — rotation order, twist direction, the sense of a
 * partial revolve, where an extrusion sits in z.
 *
 * These are the places where a lowering can be plausibly, silently wrong: a mirrored solid still has
 * the right volume and the right genus and renders perfectly. So they are determined by running the
 * library rather than by reading its header, and the answers are then pinned by real assertions in
 * [SolidSLCrossValidationTest].
 */
class SolidSLConventionProbe {

    @Test
    fun `report what Manifold's conventions actually are`() {
        if (!ManifoldLibrary.isAvailable) {
            println("SolidSLConventionProbe: skipping — ${ManifoldLibrary.describe()}")
            return
        }
        println("Manifold: ${ManifoldLibrary.describe()}")
        println("default circular segments for r=1: ${Solid.circularSegments(1.0)}")

        // ---- 1. does the field lowering agree with Manifold about which points are inside? -------
        // This one check subsumes every convention question below: a mirrored, transposed or
        // counter-rotated lowering keeps the same volume and genus and fails this immediately.
        val cases = listOf<Pair<String, SolidExpr>>(
            "cuboid" to cuboid(2.0, 1.0, 0.5),
            "sphere" to sphere(1.0, segments = 128),
            "cylinder Z" to cylinder(2.0, 0.6, segments = 128),
            "cone Z" to cone(2.0, 0.8, segments = 128),
            "frustum Z" to cylinder(2.0, 0.8, 0.3, segments = 128),
            "rotate 30,0,0" to cuboid(2.0, 1.0, 0.5).turned(30.0, 0.0, 0.0),
            "rotate 0,40,0" to cuboid(2.0, 1.0, 0.5).turned(0.0, 40.0, 0.0),
            "rotate 0,0,50" to cuboid(2.0, 1.0, 0.5).turned(0.0, 0.0, 50.0),
            "rotate 30,40,50" to cuboid(2.0, 1.0, 0.5).turned(30.0, 40.0, 50.0),
            "mirror x" to cuboid(2.0, 1.0, 0.5).at(0.7, 0.0, 0.0).mirrored(1.0, 0.0, 0.0),
            "mirror 1,1,0" to cuboid(2.0, 1.0, 0.5).at(0.7, 0.3, 0.0).mirrored(1.0, 1.0, 0.0),
            "scale 2,1,0.5" to sphere(1.0, segments = 128).sized(2.0, 1.0, 0.5),
            "extrude rect" to Profile.rect(2.0, 1.0).extruded(1.5),
            "extrude L" to lProfile().extruded(1.5),
            "extrude star" to Profile.star(5, 1.0, 0.45).extruded(0.8),
            "extrude offset rect" to Profile.rect(2.0, 1.0).offset(0.35, segments = 256).extruded(1.0),
            "extrude twist +90" to Profile.rect(2.0, 0.6).extruded(2.0, twistDegrees = 90.0, slices = 64),
            "extrude taper 0.4" to Profile.rect(2.0, 1.0).extruded(2.0, scaleTop = 0.4, slices = 32),
            "revolve 360" to torus(1.0, 0.35, segments = 128),
            "revolve 90" to Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(90.0, segments = 128),
            "revolve 270" to Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(270.0, segments = 128),
        )

        println()
        println("%-22s %8s %8s  %s".format("case", "inside%", "agree%", "note"))
        for ((name, expr) in cases) {
            val r = runCatching { membershipCheck(expr) }
            if (r.isFailure) {
                println("%-22s %8s %8s  %s".format(name, "-", "-", "threw ${r.exceptionOrNull()}"))
                continue
            }
            val m = r.getOrThrow()
            println(
                "%-22s %7.1f%% %7.2f%%  %s".format(
                    name, 100.0 * m.insideFraction, 100.0 * m.agreementFraction,
                    if (m.agreementFraction > 0.999) "" else "MISMATCH (${m.disagreements} of ${m.tested})",
                ),
            )
        }
    }

    @Test
    fun `which axis does Section revolve actually turn about`() {
        if (!ManifoldLibrary.isAvailable) return
        val profile = Profile.rect(0.6, 0.6).translated(1.2, 0.0)
        profile.exactSection().use { s ->
            println("section bounds ${s.bounds}, area ${s.area}")
            for (deg in listOf(360.0, 270.0, 90.0)) {
                s.revolved(128, deg).use { solid ->
                    println(
                        "revolved $deg -> bounds ${solid.bounds}, volume ${"%.5f".format(solid.volume)}, " +
                            "genus ${solid.genus}",
                    )
                    // Where is the material, angularly? Probe a ring of points at the tube radius.
                    val hits = (0 until 12).joinToString(" ") { i ->
                        val a = Math.toRadians(i * 30.0)
                        val inX = solid.contains(1.2 * kotlin.math.cos(a), 0.0, 1.2 * kotlin.math.sin(a))
                        val inZ = solid.contains(1.2 * kotlin.math.cos(a), 1.2 * kotlin.math.sin(a), 0.0)
                        "${i * 30}:${if (inX) "X" else "-"}${if (inZ) "Z" else "-"}"
                    }
                    println("  ring(cos,0,sin)=X  ring(cos,sin,0)=Z   $hits")
                }
            }
        }
    }

    /** An L, in the notation, counter-clockwise. Area 3. */
    private fun lProfile(): Profile = Profile.polygon(
        listOf(P2(0, 0), P2(2, 0), P2(2, 1), P2(1, 1), P2(1, 2), P2(0, 2)),
    )

    private data class Membership(
        val tested: Int,
        val disagreements: Int,
        val insideFraction: Double,
    ) {
        val agreementFraction: Double get() = if (tested == 0) 0.0 else 1.0 - disagreements.toDouble() / tested
    }

    /**
     * Sample the shape's bound and ask both lowerings, point by point, "is this inside?".
     *
     * Points within a band of the surface are skipped: a faceted Manifold primitive genuinely differs
     * from the true curve there, and the question being asked is about *conventions*, not accuracy.
     */
    private fun membershipCheck(expr: SolidExpr, samples: Int = 3000, seed: Long = 7L): Membership {
        val q = SolidQuality(segments = 128)
        // A quarter of the longest axis of headroom, so the sample box contains a decent population
        // of *outside* points. A tight bound on a solid extrusion is 100% inside, and a check where
        // every sample is inside cannot see a shape that is too big.
        val tight = expr.bounds()!!
        val b = tight.expandedBy(0.25 * maxOf(tight.size.x, tight.size.y, tight.size.z).toDouble())
        val field = expr.toField3()
        val size = b.size
        val skin = 0.03 * maxOf(size.x, size.y, size.z)
        val random = kotlin.random.Random(seed)
        return expr.exactSolid(q).orThrow().use { solid ->
            var tested = 0
            var wrong = 0
            var inside = 0
            repeat(samples) {
                val x = b.min.x + random.nextDouble().toFloat() * size.x
                val y = b.min.y + random.nextDouble().toFloat() * size.y
                val z = b.min.z + random.nextDouble().toFloat() * size.z
                val d = field.at(x.toDouble(), y.toDouble(), z.toDouble())
                if (abs(d) < skin) return@repeat
                tested++
                if (d < 0) inside++
                if ((d < 0) != solid.contains(x.toDouble(), y.toDouble(), z.toDouble())) wrong++
            }
            Membership(tested, wrong, if (tested == 0) 0.0 else inside.toDouble() / tested)
        }
    }
}
