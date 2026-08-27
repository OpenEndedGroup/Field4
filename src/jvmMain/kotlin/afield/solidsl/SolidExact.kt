@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.manifold.JoinType
import afield.manifold.ManifoldLibrary
import afield.manifold.Section
import afield.manifold.Solid
import afield.stdlib.AxisAlignedBoundingBox3D
import afield.stdlib.Vec2
import kotlin.math.max

/**
 * Lowering **(b)**: a [SolidExpr] as an *exact* Manifold [Solid] — or a stated refusal.
 *
 * ```kotlin
 * when (val l = (cuboid(2, 2, 1) - cylinder(3, 0.3)).exactSolid()) {
 *     is SolidLowering.Lowered -> println("volume ${l.value.volume}, genus ${l.value.genus}")
 *     is SolidLowering.Refused -> println("no exact form: ${l.why}")
 * }
 * ```
 *
 * # Refusals are in the type
 *
 * The discipline is [afield.rectsl.RectSLFnLowering]'s, wholesale, and for the same reason it gives:
 * a wrong answer is worse than no answer, so the lowering never guesses. Every node either maps to
 * the corresponding Manifold call or refuses **with a reason**, and the refusal propagates up.
 *
 * A `null` would invite a caller to substitute something plausible; a thrown exception loses the
 * reason at the first `runCatching`. A stated reason survives into a test assertion, a diagnostic and
 * a docs page — which is why the refusals in this package are enumerable and enumerated:
 *
 * | node | why there is no exact form | flavour |
 * |---|---|---|
 * | [SolidExpr.SmoothUnion] | the fillet surface lies on **neither** operand, so no sequence of unions, intersections and differences of the two can produce it | permanent |
 * | [SolidExpr.SmoothDifference] | the same, for the filleted cut | permanent |
 * | [SolidExpr.Gyroid] | a trigonometric field on all of space, not a bounded volume | permanent |
 * | [SolidExpr.Round] | `minkowskiSum` with a sphere exists, but that sphere is an inscribed polyhedron, so the result is strictly *inside* the true offset by up to its sagitta — and refining the march does not close a gap that is not the march's | *would not converge* |
 * | [SolidExpr.Shell] | `grow − erode` exists as two Minkowski operations, with [SolidExpr.Round]'s problem twice over and a concave decomposition between them | *would not converge* |
 *
 * The last two are the interesting ones. They are not "we did not get to it": an exact-looking answer
 * is available and is **the wrong answer**, systematically and in a direction that does not shrink.
 * Filing them beside the smooth blends would flatten a real distinction, so the type carries
 * [SolidLowering.Refused.closable] and the reason says which kind it is.
 *
 * Note what is **not** on the list. Every [Profile] node lowers exactly — including [Profile.Offset],
 * because `Section.offset(d, JoinType.ROUND)` is definitionally the Minkowski sum with a disc, which
 * is *exactly* what `sdf − d` means. So a rounded slot has an exact form and a rounded solid does not,
 * and the asymmetry is real: Clipper offsets a 2D region as a region, where the 3D story has to go
 * through a polyhedral ball.
 *
 * @see buildSolid for the hybrid, which marches only the refused subtrees
 * @see SolidAgreement for what happens when you run both lowerings on one source and compare
 */

/**
 * Either a lowering into Manifold's world, or a refusal that says why.
 *
 * @see SolidLowering.Refused for what the two fields on a refusal mean
 */
sealed interface SolidLowering<out T> {

    /** The lowering exists: [value] is the [Solid] or [Section]. */
    data class Lowered<out T>(val value: T) : SolidLowering<T>

    /**
     * There is no faithful exact form, [why] is the reason in one sentence, and [closable] says
     * whether that is a gap in ambition or a gap in mathematics.
     *
     * `closable = true` would mean the lowering could be written and has not been, with [missing]
     * naming what it would take. **No refusal on the exact path is `closable`**, and that is worth
     * stating rather than leaving to be inferred: every refusal in this file is a fact about CSG,
     * not a to-do. (`SolidGradient.kt` reuses this type for a different kind of refusal — a
     * mis-bound parameter — and those *are* closable, because closing one is fixing the binding.
     * The flag is what tells the two apart.)
     *
     * @param node the node that refused, as its `toString`, so a refusal from deep in a tree says
     *   where it came from.
     */
    data class Refused(
        val why: String,
        val node: String = "",
        val closable: Boolean = false,
        val missing: String = "",
    ) : SolidLowering<Nothing>
}

/** The lowered value, or `null` for a refusal. */
fun <T> SolidLowering<T>.valueOrNull(): T? = (this as? SolidLowering.Lowered<T>)?.value

/** The refusal reason, or `null` when this did lower. */
fun SolidLowering<*>.refusalOrNull(): String? = (this as? SolidLowering.Refused)?.why

/** The lowered value, or a thrown [IllegalStateException] carrying the refusal. */
fun <T> SolidLowering<T>.orThrow(): T = when (this) {
    is SolidLowering.Lowered -> value
    is SolidLowering.Refused -> throw IllegalStateException(
        "this shape has no exact CSG form" + (if (node.isNotBlank()) " at $node" else "") + ": $why",
    )
}

/**
 * How finely the two evaluations are asked to work.
 *
 * One object rather than a parameter list because the same numbers are consulted by the exact
 * lowering (Manifold's circular segment count), by the level-set fallback (its target edge length)
 * and by [SolidAgreement] (both, plus the march) — and a convergence sweep varies exactly one of them
 * at a time, which reads much better as `q.copy(edgeLength = e)`.
 *
 * @param segments circular segments for [SolidExpr.Sphere], [SolidExpr.Cylinder],
 *   [Profile.Circle] and [Profile.Offset] where the node did not name its own. `0` consults
 *   Manifold's process-global quality. **This is the knob that sets the agreement floor** for any
 *   shape with a curved primitive: Manifold's circle is an inscribed n-gon, whose area falls short of
 *   the true one by `1 − (n/2π)·sin(2π/n) ≈ 2π²/3n²`, and no amount of marching finds that back.
 * @param edgeLength the level set's target triangle edge, in model units. `null` derives one from the
 *   shape's own [SolidExpr.bounds] and [edgeLengthFraction].
 * @param edgeLengthFraction the derived edge length, as a fraction of the shape's longest axis.
 * @param levelSetTolerance passed to `Solid.levelSet`; `-1.0` keeps the full marching resolution.
 * @param parallelLevelSet let Manifold call the field from its own thread pool. Safe here, and
 *   unlike `Solid.levelSetOf`'s default, because [SolidExpr.toLevelSetField] is re-entrant by
 *   construction — it never touches a shared [afield.stdlib.Vec3].
 */
data class SolidQuality(
    val segments: Int = 0,
    val edgeLength: Double? = null,
    val edgeLengthFraction: Double = 0.01,
    val levelSetTolerance: Double = -1.0,
    val parallelLevelSet: Boolean = true,
) {
    /** The edge length to march [bounds] at: [edgeLength] if it was given, else a fraction of the longest axis. */
    fun edgeLengthFor(bounds: AxisAlignedBoundingBox3D): Double {
        edgeLength?.let { return it }
        val s = bounds.size
        val longest = maxOf(s.x, s.y, s.z).toDouble()
        return max(1e-9, longest * edgeLengthFraction)
    }
}

/**
 * The exact Manifold [Solid] for this shape, or the refusal that explains why there is not one.
 *
 * Everything intermediate is built inside a `Solid.scope`, so a call that builds forty solids leaves
 * one alive and the caller owns it.
 */
fun SolidExpr.exactSolid(quality: SolidQuality = SolidQuality()): SolidLowering<Solid> {
    exactRefusal()?.let { return it }
    return SolidLowering.Lowered(Solid.scope { buildExactSolid(this, quality) })
}

/** The exact Manifold [Section] for this region. Every [Profile] node has one, so this never refuses. */
fun Profile.exactSection(quality: SolidQuality = SolidQuality()): Section =
    Solid.scope { buildExactSection(this, quality) }

/**
 * The refusal this shape's exact lowering would produce, or `null` if it has an exact form —
 * answered **from the expression**, with no native call and nothing built.
 *
 * The question "can this be made exactly?" is a property of the notation, not of the run, and being
 * able to ask it cheaply is what lets [buildSolid] apply the level set at the smallest subtree that
 * needs it rather than to the whole tree.
 */
fun SolidExpr.exactRefusal(): SolidLowering.Refused? = ownRefusal() ?: childRefusal()

/**
 * This node's own refusal, ignoring its children — the whole refusal catalogue, in one `when`.
 *
 * Split out from [exactRefusal] so [allRefusals] can walk past a refusing node into its children
 * rather than stopping at the first one, and so the table in this file's KDoc has exactly one place
 * it could drift from.
 */
private fun SolidExpr.ownRefusal(): SolidLowering.Refused? = when (this) {

    is SolidExpr.Gyroid -> SolidLowering.Refused(
        "a gyroid is a trigonometric field on all of space, not a bounded volume — there is no " +
            "primitive and no boolean that produces one",
        node = toString(),
    )

    is SolidExpr.SmoothUnion -> SolidLowering.Refused(
        "a smooth blend's fillet surface lies on neither operand, so no union, intersection or " +
            "difference of the two can produce it",
        node = toString(),
    )

    is SolidExpr.SmoothDifference -> SolidLowering.Refused(
        "a filleted cut's rim lies on neither operand — the same reason SmoothUnion has no CSG form",
        node = toString(),
    )

    is SolidExpr.Round -> SolidLowering.Refused(
        "Manifold can grow a solid with minkowskiSum, but only by a *faceted* sphere, so the result " +
            "is strictly inside the true offset by up to that sphere's sagitta — an error that does " +
            "not shrink as the march refines, which is why this takes the level set rather than a " +
            "plausible-looking exact path",
        node = toString(),
    )

    is SolidExpr.Shell -> SolidLowering.Refused(
        "a shell is a grow minus an erode, which is minkowskiSum and minkowskiDifference with a " +
            "faceted ball — Round's non-converging error twice over, and a concave decomposition " +
            "between them",
        node = toString(),
    )

    else -> null
}

/** The first refusal among this node's children, or `null`. */
private fun SolidExpr.childRefusal(): SolidLowering.Refused? =
    children().firstNotNullOfOrNull { it.exactRefusal() }

/** This node's immediate [SolidExpr] children, in written order. The one place the shape of the tree is listed. */
fun SolidExpr.children(): List<SolidExpr> = when (this) {
    is SolidExpr.Sphere, is SolidExpr.Cuboid, is SolidExpr.Cylinder,
    is SolidExpr.Extrude, is SolidExpr.Revolve, is SolidExpr.Gyroid, SolidExpr.Empty,
    -> emptyList()
    is SolidExpr.Translate -> listOf(child)
    is SolidExpr.Rotate -> listOf(child)
    is SolidExpr.Scale -> listOf(child)
    is SolidExpr.Mirror -> listOf(child)
    is SolidExpr.Round -> listOf(child)
    is SolidExpr.Shell -> listOf(child)
    is SolidExpr.Union -> children
    is SolidExpr.Intersect -> children
    is SolidExpr.Difference -> children
    is SolidExpr.SmoothUnion -> listOf(a, b)
    is SolidExpr.SmoothDifference -> listOf(a, b)
}

/** Every refusal in this expression, not just the first — the catalogue a docs page or a report wants. */
fun SolidExpr.allRefusals(): List<SolidLowering.Refused> =
    listOfNotNull(ownRefusal()) + children().flatMap { it.allRefusals() }

// ==============================================================================================
// The hybrid
// ==============================================================================================

/**
 * A [Solid] built from this expression, taking the exact path everywhere it exists and the level set
 * only where it does not — **at the smallest subtree that needs it**.
 *
 * That last clause is the whole design. A naive fallback asks "does the whole tree lower exactly?"
 * and marches everything if the answer is no, so one [SolidExpr.SmoothUnion] anywhere costs a
 * hundred-thousand-triangle approximation of a part that was otherwise exact. This walks down
 * instead: `Union(plate, smoothBlend)` is a genuine batch union of an *exact* plate with a marched
 * blend, and the plate's flat faces come out with four triangles rather than four thousand.
 *
 * ```kotlin
 * val build = (cuboid(4, 4, 1) + smoothUnion(sphere(1).at(0, 0, 1), cylinder(3, 0.4), k = 0.4))
 *     .buildSolid(SolidQuality(edgeLength = 0.02))
 * println(build.report())
 * // exact: Cuboid, Union
 * // marched: SmoothUnion(...) at edgeLength 0.02 — a smooth blend's fillet surface lies on neither…
 * ```
 */
fun SolidExpr.buildSolid(quality: SolidQuality = SolidQuality()): SolidBuild {
    require(ManifoldLibrary.isAvailable) {
        "afield.solidsl's exact lowering needs Manifold. ${ManifoldLibrary.describe()}"
    }
    val marched = mutableListOf<MarchedSubtree>()
    val solid = Solid.scope { buildHybrid(this, quality, marched, bounds()) }
    return SolidBuild(solid, marched.toList())
}

/** What [buildSolid] produced, and how much of it was exact. */
class SolidBuild internal constructor(

    /** The solid. The caller owns it — `close()` it, or build inside a `Solid.scope`. */
    val solid: Solid,

    /**
     * Every subtree that had to be marched, with the reason it could not be built exactly.
     *
     * Empty is the good case and means the solid is exact end to end.
     */
    val marchedSubtrees: List<MarchedSubtree>,
) : AutoCloseable {

    /** `true` when nothing had to be marched. */
    val isExact: Boolean get() = marchedSubtrees.isEmpty()

    /** A one-paragraph account of what happened, for a `println` in a box. */
    fun report(): String = if (isExact) {
        "exact: every node had a CSG form (${solid.triangleCount} triangles, genus ${solid.genus})"
    } else {
        buildString {
            append("hybrid: ${marchedSubtrees.size} subtree(s) marched, the rest exact ")
            append("(${solid.triangleCount} triangles, genus ${solid.genus})")
            marchedSubtrees.forEach {
                append("\n  marched ${it.node::class.simpleName} at edgeLength ${"%.4g".format(it.edgeLength)} — ${it.why}")
            }
        }
    }

    override fun close() = solid.close()
}

/** One subtree that the exact lowering refused and the level set took instead. */
data class MarchedSubtree(
    val node: SolidExpr,
    val why: String,
    val edgeLength: Double,
    val bounds: AxisAlignedBoundingBox3D,
)

/**
 * @param ambient the bound of the nearest enclosing node that had one.
 *
 * That parameter is what makes `gyroid() and cuboid(2, 2, 2)` work. The [SolidExpr.Gyroid] child is
 * unbounded and has no window of its own, but the [SolidExpr.Intersect] above it does — and
 * intersecting with a bounded sibling is precisely how anybody uses a gyroid. Marching the child
 * inside its parent's window is exact, because everything outside that window is cut away by the
 * intersection anyway.
 */
private fun buildHybrid(
    e: SolidExpr,
    q: SolidQuality,
    marched: MutableList<MarchedSubtree>,
    ambient: AxisAlignedBoundingBox3D?,
): Solid {
    if (e.exactRefusal() == null) return buildExactSolid(e, q)
    val inner = e.bounds() ?: ambient
    return when (e) {
        // The node itself is fine; one of its children is not, so keep this level exact.
        is SolidExpr.Union -> Solid.union(e.children.map { buildHybrid(it, q, marched, inner) })
        is SolidExpr.Intersect -> Solid.intersection(e.children.map { buildHybrid(it, q, marched, inner) })
        is SolidExpr.Difference -> Solid.difference(e.children.map { buildHybrid(it, q, marched, inner) })
        is SolidExpr.Translate -> buildHybrid(e.child, q, marched, inner).translated(e.x, e.y, e.z)
        is SolidExpr.Rotate -> buildHybrid(e.child, q, marched, inner).rotated(e.x, e.y, e.z)
        is SolidExpr.Scale -> buildHybrid(e.child, q, marched, inner).scaled(e.x, e.y, e.z)
        is SolidExpr.Mirror -> buildHybrid(e.child, q, marched, inner).mirrored(e.x, e.y, e.z)
        // This node is the one with no exact form. March it and nothing above it.
        else -> marchSubtree(e, q, marched, ambient)
    }
}

private fun marchSubtree(
    e: SolidExpr,
    q: SolidQuality,
    marched: MutableList<MarchedSubtree>,
    ambient: AxisAlignedBoundingBox3D?,
): Solid {
    val raw = e.bounds() ?: ambient ?: error(
        "cannot march $e: it is unbounded (a Gyroid, or a Union with one), so there is no window " +
            "and no bounded enclosing node to borrow one from. Intersect it with something bounded.",
    )
    val edge = q.edgeLengthFor(raw)
    // Two cells of headroom: a level set that touches its own window comes out open there, and an
    // open "solid" is the one failure this package exists to make impossible.
    val window = raw.expandedBy(2.0 * edge)
    val field = e.toLevelSetField()
    val solid = Solid.levelSet(window, edge, 0.0, q.levelSetTolerance, q.parallelLevelSet) { x, y, z ->
        field.at(x, y, z)
    }
    marched += MarchedSubtree(e, e.exactRefusal()?.why ?: "", edge, window)
    return solid
}

// ==============================================================================================
// Node by node
// ==============================================================================================

private fun buildExactSolid(e: SolidExpr, q: SolidQuality): Solid = when (e) {

    is SolidExpr.Sphere -> Solid.sphere(e.radius, if (e.segments != 0) e.segments else q.segments)

    is SolidExpr.Cuboid -> Solid.cube(e.x, e.y, e.z, center = true)

    is SolidExpr.Cylinder -> Solid.cylinder(
        e.height, e.radiusLow, e.radiusHigh,
        if (e.segments != 0) e.segments else q.segments,
        center = true,
    )

    is SolidExpr.Extrude -> buildExactSection(e.profile, q)
        .extruded(e.height, e.slices, e.twistDegrees, e.scaleTop)

    is SolidExpr.Revolve -> buildExactSection(e.profile, q)
        .revolved(if (e.segments != 0) e.segments else q.segments, e.degrees)

    SolidExpr.Empty -> Solid.empty()

    is SolidExpr.Translate -> buildExactSolid(e.child, q).translated(e.x, e.y, e.z)
    is SolidExpr.Rotate -> buildExactSolid(e.child, q).rotated(e.x, e.y, e.z)
    is SolidExpr.Scale -> buildExactSolid(e.child, q).scaled(e.x, e.y, e.z)
    is SolidExpr.Mirror -> buildExactSolid(e.child, q).mirrored(e.x, e.y, e.z)

    // The batch forms, not a fold. Manifold plans the whole set as one operation, which is a
    // different computation from n−1 nested ones and the reason the algebra's booleans are n-ary.
    is SolidExpr.Union -> Solid.union(e.children.map { buildExactSolid(it, q) })
    is SolidExpr.Intersect -> Solid.intersection(e.children.map { buildExactSolid(it, q) })
    is SolidExpr.Difference -> Solid.difference(e.children.map { buildExactSolid(it, q) })

    // Unreachable: every caller consults `exactRefusal()` first. Stated rather than swallowed,
    // because a new node added to the algebra without a refusal *or* a lowering should say so here
    // loudly, not produce a plausible empty solid.
    is SolidExpr.Gyroid, is SolidExpr.Round, is SolidExpr.Shell,
    is SolidExpr.SmoothUnion, is SolidExpr.SmoothDifference,
    -> error("buildExactSolid reached a node with no exact form: ${e.exactRefusal()?.why}")
}

private fun buildExactSection(p: Profile, q: SolidQuality): Section = when (p) {

    is Profile.Circle -> Section.circle(p.radius, if (p.segments != 0) p.segments else q.segments)

    is Profile.Rect -> Section.square(p.x, p.y, center = true)

    // Normalised to counter-clockwise. The field lowering's sign comes from a crossing-parity test
    // and does not care about winding; Clipper decides holes *by* winding, so a clockwise polygon
    // written by hand would come back as an empty region under FillRule.POSITIVE. Normalising here
    // rather than requiring it of the caller is what makes the two lowerings agree on the same text.
    is Profile.Polygon -> Section.ofPolygon(counterClockwise(p.points).map { Vec2(it.x.toFloat(), it.y.toFloat()) })

    is Profile.Offset -> buildExactSection(p.child, q).offset(
        p.delta,
        JoinType.ROUND,
        circularSegments = if (p.segments != 0) p.segments else q.segments,
    )

    is Profile.Translate -> buildExactSection(p.child, q).translated(p.x, p.y)
    is Profile.Rotate -> buildExactSection(p.child, q).rotated(p.degrees)
    is Profile.Scale -> buildExactSection(p.child, q).scaled(p.x, p.y)

    is Profile.Union -> Section.union(p.children.map { buildExactSection(it, q) })
    is Profile.Intersect -> Section.intersection(p.children.map { buildExactSection(it, q) })
    is Profile.Difference -> Section.difference(p.children.map { buildExactSection(it, q) })

    Profile.Empty -> Section.empty()
}

/** Twice the signed area of a closed contour — positive for counter-clockwise. */
internal fun signedArea(points: List<P2>): Double {
    var total = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        total += a.x * b.y - b.x * a.y
    }
    return total / 2.0
}

/** These points, reversed if they were clockwise. See the note at [Profile.Polygon]'s lowering. */
internal fun counterClockwise(points: List<P2>): List<P2> =
    if (signedArea(points) < 0.0) points.reversed() else points
