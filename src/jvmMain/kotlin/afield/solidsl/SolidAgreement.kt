@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.manifold.Solid
import afield.stdlib.TriMesh
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.marchingCubes
import afield.stdlib.maxDeviation
import afield.stdlib.signedVolume
import afield.stdlib.surfaceArea
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * The measurement this package exists for: **one written shape, evaluated two completely independent
 * ways, and the disagreement reported as a number.**
 *
 * ```kotlin
 * val bracket = cuboid(4, 3, 1) - cylinder(2, 0.5).at(1, 0, 0)
 * println(bracket.crossCheck(SolidQuality(edgeLength = 0.02, segments = 256)).report())
 * ```
 *
 * # Why two lowerings of the same thing is worth measuring
 *
 * Almost nothing in a graphics codebase gets to do this. The usual options are to assert against a
 * remembered constant (which pins the current behaviour, not the correct one) or against a closed
 * form (which only exists for a handful of shapes). Here both sides are *implementations*, and they
 * share no code whatsoever:
 *
 * | | side A | side B |
 * |---|---|---|
 * | the shape becomes | a distance field over `Double`s | a CSG tree over Manifold handles |
 * | the surface comes from | `afield.stdlib.marchingCubes`, in Kotlin | Manifold's exact boolean kernel, in C++ |
 * | the volume comes from | a sum of signed tetrahedra over `Float` triangles | Manifold's own integration, in `double` |
 * | the genus comes from | edge/vertex adjacency in `mesh_topology.kt` | Manifold's Euler characteristic |
 *
 * There is no path by which a bug in one produces the same wrong answer in the other. Five-figure
 * agreement is therefore strong evidence that both are right, in a way that any number of assertions
 * against constants would not be.
 *
 * The same argument has since been applied to a **third** reading — the differentiable one in
 * `SolidGradient.kt`, which is `toScalarField`'s arithmetic replaced by
 * [afield.stdlib.AD] and shares no line of code with it. It is measured by
 * [SolidExpr.gradientAgreement] rather than folded in here, and the reason is that the two compare
 * different objects: this file compares **surfaces** — volume, area, genus, Hausdorff deviation —
 * and the differentiable reading produces a *field*, exactly as `toScalarField` does. Folding it in
 * would have meant inventing a volume for something that has none. What the two share is the
 * argument, so each cross-references the other.
 *
 * # Agreement does not converge to zero, and saying so is the point
 *
 * There are two regimes, and conflating them would be the overclaim this measurement is designed to
 * avoid:
 *
 *  - **Polyhedral shapes** — cuboids, polygon-profile extrusions, and booleans of those. The exact
 *    lowering *is* the truth, so the marched one converges to it and the residual goes to zero as
 *    [SolidQuality.edgeLength] shrinks. Expect the relative volume error to fall roughly as `h²`.
 *  - **Curved shapes** — anything with a sphere, a cylinder, a circular profile or a round offset.
 *    Manifold's circle is an **inscribed n-gon**, whose area falls short of the true one by
 *    `1 − (n/2π)·sin(2π/n) ≈ 2π²/3n²`. The field lowering describes the true curve. So agreement
 *    converges to that deficit and not to zero, however fine the march.
 *
 * [facetingDeficit] computes the second one from the segment count alone, so a curved shape's
 * residual can be checked against a *predicted* floor rather than a remembered one. That is the
 * difference between "these two numbers are close" and "these two numbers are as close as the
 * geometry permits, and here is the formula".
 */

/** Which two things were compared — see the note on [SolidAgreement] about the empty bucket. */
enum class AgreementKind {

    /**
     * Manifold's exact CSG against Field's marching cubes. The strong comparison, and the only one
     * that belongs in an exact-agreement statistic.
     */
    EXACT_VS_MARCHED,

    /**
     * Manifold's level set against Field's marching cubes — what a shape that *refused* the exact
     * path gets.
     *
     * Still two independent marchers on one field, so it catches a broken lowering; but both sides
     * are approximations of the same field, so agreement here says nothing about whether that field
     * is the shape the notation meant. Counted separately for exactly that reason: a report that
     * folded these into an "N of N agree" would be the classic overclaim, where the refusal path
     * quietly counts as a pass.
     */
    MARCHED_VS_MARCHED,
}

/** What the two lowerings of one [SolidExpr] said, and how far apart they were. */
data class SolidAgreement(
    val expr: SolidExpr,
    val kind: AgreementKind,
    val edgeLength: Double,
    val segments: Int,
    val refusals: List<String>,

    /** Manifold's own integration of the exact (or level-set) solid. */
    val solidVolume: Double,

    /** `afield.stdlib.signedVolume` over the marched triangles — a sum of signed tetrahedra. */
    val marchedVolume: Double,

    val solidArea: Double,
    val marchedArea: Double,

    val solidGenus: Int,

    /** `null` when the marched mesh came out open, which is itself a failure worth seeing. */
    val marchedGenus: Int?,

    val marchedIsWatertight: Boolean,

    val solidTriangles: Long,
    val marchedTriangles: Int,

    /** Symmetric vertex-sampled Hausdorff distance between the two surfaces, in model units. */
    val deviation: Double,

    /** The longest axis of the shape's bound — what [deviation] is relative to. */
    val scale: Double,
) {
    /** `|Va − Vb| / max(|Va|, |Vb|)`. */
    val relativeVolumeError: Double
        get() = relative(solidVolume, marchedVolume)

    /** `|Aa − Ab| / max(|Aa|, |Ab|)`. */
    val relativeAreaError: Double
        get() = relative(solidArea, marchedArea)

    /** [deviation] as a fraction of the shape's longest axis, so it compares across sizes. */
    val relativeDeviation: Double
        get() = if (scale <= 0.0) 0.0 else deviation / scale

    /** `true` when both sides closed and agreed on how many holes there are. */
    val genusAgrees: Boolean
        get() = marchedGenus != null && marchedGenus == solidGenus

    /** One line, for a sweep printed as a table. */
    fun line(): String = "%-11s h=%-8.4g segs=%-5d volume %.6f vs %.6f (%.3e)  area %.3e  genus %s  dev %.3e"
        .format(
            if (kind == AgreementKind.EXACT_VS_MARCHED) "exact" else "levelset",
            edgeLength, segments,
            solidVolume, marchedVolume, relativeVolumeError,
            relativeAreaError,
            if (genusAgrees) "$solidGenus=" else "$solidGenus/$marchedGenus!",
            relativeDeviation,
        )

    /** The whole story, for a `println` in a box. */
    fun report(): String = buildString {
        appendLine(expr.toString())
        appendLine(
            when (kind) {
                AgreementKind.EXACT_VS_MARCHED -> "  exact CSG vs marching cubes"
                AgreementKind.MARCHED_VS_MARCHED -> "  Manifold level set vs marching cubes (the exact path refused)"
            },
        )
        refusals.forEach { appendLine("  refused: $it") }
        appendLine("  volume   ${"%.8g".format(solidVolume)} vs ${"%.8g".format(marchedVolume)}   rel ${"%.4e".format(relativeVolumeError)}")
        appendLine("  area     ${"%.8g".format(solidArea)} vs ${"%.8g".format(marchedArea)}   rel ${"%.4e".format(relativeAreaError)}")
        appendLine("  genus    $solidGenus vs ${marchedGenus ?: "open"}   ${if (genusAgrees) "agree" else "DISAGREE"}")
        appendLine("  surfaces ${solidTriangles} vs $marchedTriangles triangles, deviation ${"%.4e".format(deviation)} (${"%.3e".format(relativeDeviation)} of ${"%.4g".format(scale)})")
    }

    private fun relative(a: Double, b: Double): Double {
        val s = max(abs(a), abs(b))
        return if (s == 0.0) 0.0 else abs(a - b) / s
    }
}

/**
 * Build this shape both ways and measure the disagreement.
 *
 * The marched side uses `afield.stdlib.marchingCubes` at a resolution derived from
 * [SolidQuality.edgeLength], over the shape's own symbolic [SolidExpr.bounds] with two cells of
 * headroom — a marched surface that touches its window comes out open, which would show up as a
 * `null` genus rather than as a silently wrong volume.
 *
 * @param quality the two knobs; a convergence sweep is `q.copy(edgeLength = h)` for a few `h`.
 */
fun SolidExpr.crossCheck(quality: SolidQuality = SolidQuality()): SolidAgreement {
    val raw = bounds() ?: error(
        "cannot cross-check $this: it is unbounded, so neither side has a window. " +
            "Intersect it with something bounded first.",
    )
    val edge = quality.edgeLengthFor(raw)
    val window = raw.expandedBy(2.0 * edge)
    val size = window.size
    val longest = maxOf(size.x, size.y, size.z).toDouble()
    val resolution = max(2, ceil(longest / edge).toInt())

    val marched = marchingCubes(toScalarField(), window, resolution)
    val refusals = allRefusals()

    return buildSolid(quality).use { build ->
        val solid = build.solid
        val solidMesh = solid.toTriMesh()
        SolidAgreement(
            expr = this,
            kind = if (build.isExact) AgreementKind.EXACT_VS_MARCHED else AgreementKind.MARCHED_VS_MARCHED,
            edgeLength = edge,
            segments = quality.segments,
            refusals = refusals.map { it.why },
            solidVolume = solid.volume,
            marchedVolume = marched.signedVolume(),
            solidArea = solid.surfaceArea,
            marchedArea = marched.surfaceArea(),
            solidGenus = solid.genus,
            marchedGenus = marched.genus(),
            marchedIsWatertight = marched.isWatertight(),
            solidTriangles = solid.triangleCount,
            marchedTriangles = marched.triangleCount,
            deviation = maxDeviation(solidMesh, marched),
            scale = longest,
        )
    }
}

/**
 * The relative volume a regular inscribed **n**-gon loses against its circle:
 * `1 − (n / 2π)·sin(2π / n)`, which is `2π²/3n²` to leading order.
 *
 * This is the floor a curved shape's [SolidAgreement.relativeVolumeError] converges to, and it is
 * *computed* rather than remembered — so a test can assert both that the residual is near it and
 * that it falls by 4x when the segment count doubles, which is the thing that proves the residual is
 * faceting rather than a bug in either lowering.
 *
 * Exact for a prism (a cylinder is a right prism over an n-gon, so its volume deficit is precisely
 * this). For a sphere, Manifold builds a geodesic rather than a surface of revolution, so this is the
 * right order and the right scaling but not the exact coefficient — which is why the sphere assertions
 * in `SolidSLCrossValidationTest` check the *scaling* and bound the coefficient rather than equating it.
 *
 * @param n segments in a full circle. Returns `0.0` for `n <= 2`, since there is no polygon to speak of.
 */
fun facetingDeficit(n: Int): Double = if (n <= 2) 0.0 else 1.0 - (n / (2.0 * PI)) * sin(2.0 * PI / n)

/**
 * How many segments Manifold will actually give a circle of this radius under the current global
 * quality, when a node asked for `0`.
 *
 * [facetingDeficit] needs the number that was *used*, not the one that was requested, and `0` — the
 * default everywhere in this package — means "consult the global setting". Going through Manifold
 * rather than reimplementing its rule is the only way this stays true.
 */
fun effectiveSegments(radius: Double, requested: Int): Int =
    if (requested > 0) requested else Solid.circularSegments(radius)

/** [TriMesh] from the marched side of a cross-check, for a render or a second opinion. */
fun SolidExpr.marchedMesh(quality: SolidQuality = SolidQuality()): TriMesh {
    val raw = bounds() ?: error("cannot march $this: it is unbounded")
    val edge = quality.edgeLengthFor(raw)
    val window = raw.expandedBy(2.0 * edge)
    val size = window.size
    val longest = maxOf(size.x, size.y, size.z).toDouble()
    return marchingCubes(toScalarField(), window, max(2, ceil(longest / edge).toInt()))
}
