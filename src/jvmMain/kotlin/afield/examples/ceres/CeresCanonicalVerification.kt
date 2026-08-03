package afield.examples.ceres

import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.canonicalDiagnostics
import afield.stdlib.polytope.canonicalize
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relaxWithCeres
import kotlin.math.abs

/**
 * Does the Ceres realizer produce a *canonical form*, or merely a small residual?
 *
 * A low cost is not the claim — the canonical objective turns out to have a spurious zero-cost
 * minimum, so it cannot be. The claim is the three defining conditions measured independently by
 * [canonicalDiagnostics]: every edge tangent to one common sphere, the centroid of those tangency
 * points at the origin, every face planar — plus the result still being a solid rather than a
 * folded lump.
 *
 * The `kk` family is the interesting part. `dev_docs/POLYTOPE_ALGEBRA.md` records `kkC` as the
 * standing counter-example that neither existing iteration solves; it is now exact. The rest of the
 * family divides into cases that reach machine precision and cases that reach roughly `1e-3` — much
 * better than [canonicalize] manages, and honestly not exact.
 */
private data class CanonicalCase(
    val expression: String,
    /** Tangency-radius spread this input is expected to reach. */
    val spread: Double,
)

private val CANONICAL_CASES = listOf(
    // Exact.
    CanonicalCase("kC", 1e-5),
    CanonicalCase("kD", 1e-5),
    CanonicalCase("tI", 1e-5),
    CanonicalCase("kkC", 1e-5),
    CanonicalCase("kkD", 1e-5),
    // Good, not exact. See the class comment.
    CanonicalCase("kkT", 5e-3),
    CanonicalCase("kkO", 5e-3),
    CanonicalCase("kkI", 5e-3),
)

private fun describe(label: String, p: PolyhedralObject): String {
    val d = p.canonicalDiagnostics()
    return "  %-24s spread=%.3e centroid=%.3e planarity=%.3e dihedral=[%.3f..%.3f]%s".format(
        label,
        d.tangencyRadiusSpread,
        d.tangencyCentroidOffset,
        d.maxFacePlanarityError,
        d.dihedralAngleMin,
        d.dihedralAngleMax,
        if (d.degenerateFaces.isEmpty()) "" else "  DEGENERATE x${d.degenerateFaces.size}",
    )
}

fun main() {
    val failures = mutableListOf<String>()

    for (case in CANONICAL_CASES) {
        val seed = conway(case.expression).polyhedron
        println(
            "\n${case.expression}  V=${seed.topology.elements(0).size} " +
                "E=${seed.topology.elements(1).size} F=${seed.topology.elements(2).size}",
        )
        println(describe("seed", seed))

        val iterated = seed.canonicalize().polyhedron
        println(describe("canonicalize()", iterated))

        val result = seed.relaxWithCeres(
            RelaxationPlan.canonicalConditions(),
            CeresRelaxationOptions(maxIterations = 500),
        )
        val solved = result.realization.polyhedron
        println(describe("ceres", solved))
        println("      ${result.summary}")

        val d = solved.canonicalDiagnostics()
        fun require(name: String, ok: Boolean) {
            if (!ok) failures += "${case.expression}: $name"
        }

        require("tangency radii common (< ${case.spread})", d.tangencyRadiusSpread < case.spread)
        require("tangency centroid at origin", d.tangencyCentroidOffset < 1e-4)
        require("faces planar", d.maxFacePlanarityError < 1e-6)
        require("no degenerate faces", d.degenerateFaces.isEmpty())
        require("midsphere radius sane", d.tangencyRadiusMean.isFinite() && d.tangencyRadiusMean > 1e-3)
        require("coordinates finite and bounded", solved.coordinatesSane())
        // No collapsed edges: the spurious minimum this objective admits. The barrier in
        // CeresRelaxationOptions.minEdgeLengthFraction is what keeps this true.
        require("no collapsed edges", solved.shortestEdge() > 1e-4)
        // Strictly better than the iteration it is meant to replace.
        require(
            "beats canonicalize()",
            d.tangencyRadiusSpread <= iterated.canonicalDiagnostics().tangencyRadiusSpread + 1e-12,
        )
    }

    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        error("${failures.size} canonical checks failed")
    }
    println("\nok — ${CANONICAL_CASES.size} solids reach a genuine canonical form, kkC and kkD exactly")
}

private fun PolyhedralObject.coordinatesSane(): Boolean {
    val r = realization ?: return false
    return (0 until r.size).all {
        r.x(it).isFinite() && r.y(it).isFinite() && r.z(it).isFinite() &&
            abs(r.x(it)) < 1e6 && abs(r.y(it)) < 1e6 && abs(r.z(it)) < 1e6
    }
}

private fun PolyhedralObject.shortestEdge(): Double {
    val r = realization ?: return 0.0
    val index = topology.elements(0).mapIndexed { at, v -> v.id.value to at }.toMap()
    return topology.elements(1).minOfOrNull { edge ->
        val (a, b) = topology.incidentDown(edge.id).map { index.getValue(it) }
        val dx = (r.x(a) - r.x(b)).toDouble()
        val dy = (r.y(a) - r.y(b)).toDouble()
        val dz = (r.z(a) - r.z(b)).toDouble()
        kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    } ?: 0.0
}
