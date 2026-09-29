@file:afield.CompletionCategory("Graphics")

package afield.manifold

import afield.graphics.FLine
import afield.stdlib.Vec2
import afield.stdlib.Vec3
// `field.graphics`, not `afield.graphics` -- the Cursor-based sampler predates the rename and stayed put.
import field.graphics.FLinesAndJavaShapes
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Cleaner

/**
 * [Section] — a 2D region with holes, that you can **offset**. Manifold's `CrossSection`, which is
 * [Clipper2](https://github.com/AngusJohnson/Clipper2) underneath.
 *
 * ```kotlin
 * val plate = Section.square(4.0, 3.0, center = true) - Section.circle(0.6, segments = 64)
 * val fillet = plate.offset(0.25, JoinType.ROUND) - plate.offset(-0.25, JoinType.ROUND)
 * display(fillet.toFLines())            // straight into Field's drawing
 * val solid = plate.extruded(1.5)       // and up into 3D
 * ```
 *
 * # Why this is here, and not just [FLine]
 *
 * Field draws 2D outlines beautifully and has done for years: [FLine] with its cubics, thickening,
 * the Java2D `Area` bridge in [FLinesAndJavaShapes]. What it lacks is the two operations that turn an
 * outline into a *region you can compute with*:
 *
 *  - **Robust boolean ops on many contours at once**, with holes tracked by winding rather than by
 *    hope. `Area` can union and subtract, but it is float, it is slow on thousands of segments, and
 *    it does not tell you which contour is a hole.
 *  - **Offsetting** — a true parallel curve at distance `d`, with a choice of corner treatment,
 *    handling the self-intersections a naive normal-displacement produces. This is the operation
 *    behind a fillet, a wall thickness, a tool radius, a clearance, a stroke that is a *shape*. There
 *    is no honest way to do it by displacing points, which is why `outsetShape` in
 *    [FLinesAndJavaShapes] reaches for a `BasicStroke` and gets a round-join approximation.
 *
 * Measured against exact geometry, offsetting a 2x2 square by `+0.5` (area 4 before, sides add 4):
 *
 * | [JoinType] | area | exact | what the corner does |
 * |---|---|---|---|
 * | [JoinType.MITER] | 9.0000 | `8 + 4(0.5²) = 9` | stays a sharp corner |
 * | [JoinType.ROUND] | 8.7841 | `8 + π(0.5²) = 8.7854` | becomes a quarter circle |
 * | [JoinType.SQUARE] | 8.8284 | — | cut off square at the offset distance |
 * | [JoinType.BEVEL] | 8.5000 | `8 + 4(0.5²/2) = 8.5` | cut straight across |
 *
 * # Winding is meaning
 *
 * A [Section] is a set of contours, and which ones are holes is decided by their winding, under a
 * [FillRule]. Read one back with [toPolygons] and the outer contours are counter-clockwise (positive
 * signed area) and the holes clockwise (negative) — measured on an annulus: `+3.1413` outer,
 * `-0.7853` inner. That is also the convention [toFLines] preserves, so a ring drawn from a Section
 * fills correctly rather than filling its own hole.
 *
 * Field's default here is [FillRule.POSITIVE] rather than Clipper's `EVEN_ODD`, because it is the
 * rule under which winding *means* something: two overlapping same-wound outlines are one shape,
 * where `EVEN_ODD` would punch their overlap out as a hole.
 *
 * @see Solid for the 3D counterpart, and [extruded] / [revolved] for the way up
 */
class Section internal constructor(handle: MemorySegment) : AutoCloseable {

    private val release = NativeRelease(handle, NativeRelease.Kind.SECTION)
    private val cleanable: Cleaner.Cleanable = Solid.CLEANER.register(this, release)

    internal val ptr: MemorySegment
        get() {
            check(!release.released) { "this Section is closed — it was released explicitly or by its enclosing scope" }
            return release.handle
        }

    val isClosed: Boolean get() = release.released

    // ==========================================================================================
    // Booleans
    // ==========================================================================================

    operator fun plus(other: Section): Section = wrap(ManifoldNative.sectionUnion, ptr, other.ptr)

    operator fun minus(other: Section): Section = wrap(ManifoldNative.sectionDifference, ptr, other.ptr)

    infix fun and(other: Section): Section = wrap(ManifoldNative.sectionIntersection, ptr, other.ptr)

    infix fun or(other: Section): Section = this + other

    // ==========================================================================================
    // The offset
    // ==========================================================================================

    /**
     * Grow (or shrink, for a negative [delta]) the region by [delta], as a true parallel curve.
     *
     * The workhorse of this type. A few of the things it is:
     *
     * ```kotlin
     * val wall = outline - outline.offset(-2.0)                 // a 2mm wall, following every corner
     * val fillet = outline.offset(1.0).offset(-1.0)             // round every convex corner by 1
     * val relief = outline.offset(-0.2)                         // clearance for a press fit
     * val toolpath = pocket.offset(-cutterRadius)               // where the cutter centre may go
     * ```
     *
     * `offset(r).offset(-r)` is the standard *closing* — it rounds convex corners and leaves concave
     * ones — and is not the identity, which is the point of it.
     *
     * @param joinType what happens at a convex corner. See the table on [Section].
     * @param miterLimit for [JoinType.MITER] only: how far a spike may extend, as a multiple of
     *   [delta], before it is bevelled instead. Clipper's default of 2 is a sane one.
     * @param circularSegments for [JoinType.ROUND] only: segments in a full circle of radius [delta].
     *   `0` consults Manifold's global quality setting.
     */
    fun offset(
        delta: Double,
        joinType: JoinType = JoinType.ROUND,
        miterLimit: Double = 2.0,
        circularSegments: Int = 0,
    ): Section = wrap(ManifoldNative.sectionOffset, ptr, delta, joinType.code, miterLimit, circularSegments)

    // ==========================================================================================
    // Transforms
    // ==========================================================================================

    fun translated(x: Double, y: Double): Section = wrap(ManifoldNative.sectionTranslate, ptr, x, y)

    /** Rotate about the origin, **in degrees**. */
    fun rotated(degrees: Double): Section = wrap(ManifoldNative.sectionRotate, ptr, degrees)

    fun scaled(x: Double, y: Double): Section = wrap(ManifoldNative.sectionScale, ptr, x, y)

    fun scaled(factor: Double): Section = scaled(factor, factor)

    /** Mirror through the line through the origin with this normal. */
    fun mirrored(normalX: Double, normalY: Double): Section = wrap(ManifoldNative.sectionMirror, ptr, normalX, normalY)

    /**
     * Drop vertices that are within [epsilon] of the line between their neighbours — the
     * Douglas-Peucker-ish cleanup for a contour that came out of a [Solid.slice] or a dense offset.
     */
    fun simplified(epsilon: Double): Section = wrap(ManifoldNative.sectionSimplify, ptr, epsilon)

    /** The convex hull of the region. */
    fun hull(): Section = wrap(ManifoldNative.sectionHull, ptr)

    /** Split into separate regions, largest first by area. Holes stay with the outline that owns them. */
    fun decompose(): List<Section> {
        val vec = ManifoldNative.ptr(
            ManifoldNative.sectionDecompose,
            ManifoldNative.ptr(ManifoldNative.allocSectionVec),
            ptr,
        )
        try {
            val n = ManifoldNative.long(ManifoldNative.sectionVecLength, vec)
            return (0 until n)
                .map { Section(ManifoldNative.ptr(ManifoldNative.sectionVecGet, ManifoldNative.newSection(), vec, it)) }
                .sortedByDescending { it.area }
        } finally {
            ManifoldNative.unit(ManifoldNative.deleteSectionVec, vec)
        }
    }

    // ==========================================================================================
    // Up into 3D
    // ==========================================================================================

    /**
     * Extrude along +Z, optionally twisting and tapering on the way — a lathe's opposite number.
     *
     * ```kotlin
     * val column = profile.extruded(10.0, slices = 60, twistDegrees = 90.0, scaleTop = 0.6)
     * ```
     *
     * @param slices intermediate rings. Only needed when twisting or tapering; a straight extrusion
     *   wants `0`, which produces the minimal mesh (an L extruded by 2 is 6.0 in volume and a couple
     *   of dozen triangles).
     * @param twistDegrees total rotation from bottom to top.
     * @param scaleTop x and y scale at the top, for a taper. `1.0` is a straight prism.
     */
    fun extruded(
        height: Double,
        slices: Int = 0,
        twistDegrees: Double = 0.0,
        scaleTop: Double = 1.0,
        scaleTopY: Double = scaleTop,
    ): Solid = withPolygons { polygons ->
        Solid.wrap(ManifoldNative.extrude, polygons, height, slices, twistDegrees, scaleTop, scaleTopY)
    }

    /**
     * A lathe: the cross-section's `x` becomes a radius and its `y` becomes the solid's **z**, so the
     * resulting solid's axis of symmetry is **Z**.
     *
     * The profile must lie at `x >= 0`; the part at negative x is discarded rather than folded. A
     * square section offset from the axis revolves into a torus — genus 1, and the volume Pappus's
     * theorem says it should be: measured 4.7105 against an exact 4.7124.
     *
     * # This KDoc used to say "revolve about the Y axis", and that cost somebody an afternoon
     *
     * The old wording was not quite a lie: Manifold revolves the 2D cross-section around *its own*
     * y-axis, and if you are thinking in the plane, "about Y" is what you are doing. But this function
     * returns a [Solid], and a reader holding a `Solid` reads "Y axis" as a statement about 3D — which
     * is wrong, because that 2D y-axis comes out as the solid's **Z**. Measured, on a profile spanning
     * `x` in `[1,2]` and `y` in `[0,3]`, revolved 360°:
     *
     * ```
     * bounds  min = (-2, -2, 0)   max = (2, 2, 3)      // x,y are radial; z is the profile's y
     * contains(0, 0, 1.5) = false                      // the Z axis is the hole through the tube
     * contains(0, 1.5, 0) = true                       // the Y axis is solid material
     * volume 28.229, against pi * (2^2 - 1^2) * 3 = 28.274
     * ```
     *
     * **This differs from Field's own [afield.stdlib.revolveMesh], whose axis is Y.** The two lathes
     * in this codebase do not agree, and the old wording made them look as though they did. If you are
     * porting a profile from one to the other, swap y and z — or [Solid.rotated] the result.
     *
     * @param revolveDegrees less than 360 leaves the solid open at the ends, and Manifold caps it. The
     *   sweep starts at +x and turns toward +y.
     */
    fun revolved(segments: Int = 0, revolveDegrees: Double = 360.0): Solid = withPolygons { polygons ->
        Solid.wrap(ManifoldNative.revolve, polygons, segments, revolveDegrees)
    }

    // ==========================================================================================
    // Measurement and read-out
    // ==========================================================================================

    /** Enclosed area, holes subtracted. */
    val area: Double get() = ManifoldNative.double(ManifoldNative.sectionArea, ptr)

    val vertexCount: Long get() = ManifoldNative.long(ManifoldNative.sectionNumVert, ptr)

    /** Contours, outlines and holes together. An annulus is 2. */
    val contourCount: Long get() = ManifoldNative.long(ManifoldNative.sectionNumContour, ptr)

    val isEmpty: Boolean get() = ManifoldNative.int(ManifoldNative.sectionIsEmpty, ptr) != 0

    /** The bounding rectangle, as `(minX, minY)` to `(maxX, maxY)`. */
    val bounds: Pair<Vec2, Vec2>
        get() {
            val rect = ManifoldNative.ptr(ManifoldNative.sectionBounds, ManifoldNative.ptr(ManifoldNative.allocRect), ptr)
            try {
                Arena.ofConfined().use { arena ->
                    val lo = ManifoldNative.vec2(ManifoldNative.invokeStruct(ManifoldNative.rectMin, arena, rect))
                    val hi = ManifoldNative.vec2(ManifoldNative.invokeStruct(ManifoldNative.rectMax, arena, rect))
                    return Vec2(lo.first.toFloat(), lo.second.toFloat()) to Vec2(hi.first.toFloat(), hi.second.toFloat())
                }
            } finally {
                ManifoldNative.unit(ManifoldNative.deleteRect, rect)
            }
        }

    /**
     * The contours, as point lists. Outlines are counter-clockwise, holes clockwise — see the
     * winding note on [Section].
     *
     * Note that [Vec2] is `Float`, and Clipper works in `Double`, so a
     * `toPolygons()`/[Section.ofPolygons] round trip is faithful to about `1e-7` relative rather than
     * exact — enough to move a computed area in the ninth significant figure. Stay in [Section] for a
     * chain of boolean or offset steps and come out to points once, at the end.
     */
    fun toPolygons(): List<List<Vec2>> = toDoublePolygons().map { ring -> ring.map { Vec2(it.first.toFloat(),it.second.toFloat()) } }

    /** Native coordinates for dimensional audits; never pass through drawing Vec2s. */
    internal fun toDoublePolygons(): List<List<Pair<Double,Double>>> {
        val polygons = ManifoldNative.ptr(
            ManifoldNative.sectionToPolygons,
            ManifoldNative.ptr(ManifoldNative.allocPolygons),
            ptr,
        )
        try {
            Arena.ofConfined().use { arena ->
                val contours = ManifoldNative.long(ManifoldNative.polygonsLength, polygons)
                return (0 until contours).map { c ->
                    val n = ManifoldNative.long(ManifoldNative.polygonsSimpleLength, polygons, c)
                    (0 until n).map { i ->
                        val p = ManifoldNative.vec2(
                            ManifoldNative.invokeStruct(ManifoldNative.polygonsGetPoint, arena, polygons, c, i),
                        )
                        p
                    }
                }
            }
        } finally {
            ManifoldNative.unit(ManifoldNative.deletePolygons, polygons)
        }
    }

    /**
     * The contours as closed [FLine]s, ready to `display()`, thicken, animate or export — the way
     * back into everything Field already does with a line.
     *
     * One [FLine] per contour, each closed back to its own first point, so a filled draw of the whole
     * list renders the region with its holes.
     *
     * @param z the plane to place them on, since an [FLine] lives in 3D.
     */
    fun toFLines(z: Double = 0.0): List<FLine> = toPolygons().mapNotNull { contour ->
        if (contour.size < 2) return@mapNotNull null
        val line = FLine()
        contour.forEachIndexed { i, p ->
            if (i == 0) line.moveTo(p.x, p.y, z) else line.lineTo(p.x, p.y, z)
        }
        line.lineTo(contour[0].x, contour[0].y, z)
        line
    }

    /** Every contour merged into one [FLine] with a sub-path each — the single-value form of [toFLines]. */
    fun toFLine(z: Double = 0.0): FLine {
        val line = FLine()
        toPolygons().forEach { contour ->
            if (contour.size < 2) return@forEach
            contour.forEachIndexed { i, p ->
                if (i == 0) line.moveTo(p.x, p.y, z) else line.lineTo(p.x, p.y, z)
            }
            line.lineTo(contour[0].x, contour[0].y, z)
        }
        return line
    }

    /**
     * Build the `ManifoldPolygons` this section is, hand it to [block], and free it after.
     *
     * [extruded] and [revolved] take polygons rather than a cross section — a reasonable upstream
     * choice, since an extrusion is defined by contours and not by a fill rule — and this is where
     * the round trip through them is paid for exactly once.
     */
    private inline fun <T> withPolygons(block: (MemorySegment) -> T): T {
        val polygons = ManifoldNative.ptr(
            ManifoldNative.sectionToPolygons,
            ManifoldNative.ptr(ManifoldNative.allocPolygons),
            ptr,
        )
        try {
            return block(polygons)
        } finally {
            ManifoldNative.unit(ManifoldNative.deletePolygons, polygons)
        }
    }

    /** An independent copy — the one thing that survives its enclosing [Solid.scope]. */
    fun copy(): Section = wrap(ManifoldNative.sectionCopy, ptr)

    override fun close() {
        cleanable.clean()
    }

    override fun toString(): String = if (isClosed) {
        "Section(closed)"
    } else {
        "Section(${contourCount} contours, $vertexCount verts, area ${"%.5g".format(area)})"
    }

    companion object {

        /** The empty region. */
        fun empty(): Section = Section(ManifoldNative.ptr(ManifoldNative.sectionEmpty, ManifoldNative.newSection()))

        /** An axis-aligned rectangle. */
        fun square(x: Double, y: Double = x, center: Boolean = false): Section =
            wrap(ManifoldNative.sectionSquare, x, y, if (center) 1 else 0)

        /** A regular polygon inscribed in this radius. `segments = 0` consults the global quality setting. */
        fun circle(radius: Double, segments: Int = 0): Section =
            wrap(ManifoldNative.sectionCircle, radius, segments)

        /**
         * A region from explicit contours.
         *
         * @param contours one outline, plus a contour per hole. Under the default
         *   [FillRule.POSITIVE], an outline must be counter-clockwise and a hole clockwise — which is
         *   also how [toPolygons] hands them back, so a read/modify/write round trip preserves holes.
         */
        fun ofPolygons(contours: List<List<Vec2>>, fillRule: FillRule = FillRule.POSITIVE): Section =
            ofDoublePolygons(contours.map { ring -> ring.map { it.x.toDouble() to it.y.toDouble() } },fillRule)

        /** Double input for native geometry audits, without a Float drawing conversion. */
        internal fun ofDoublePolygons(contours: List<List<Pair<Double,Double>>>, fillRule: FillRule = FillRule.POSITIVE): Section {
            val usable = contours.filter { it.size >= 3 }
            if (usable.isEmpty()) return empty()
            Arena.ofConfined().use { arena ->
                val simplePolygons = arena.allocate(ValueLayout.ADDRESS, usable.size.toLong())
                val owned = ArrayList<MemorySegment>(usable.size)
                try {
                    usable.forEachIndexed { index, contour ->
                        val points = arena.allocate(ManifoldNative.VEC2, contour.size.toLong())
                        contour.forEachIndexed { i, p ->
                            require(p.first.isFinite() && p.second.isFinite())
                            points.set(ValueLayout.JAVA_DOUBLE, i * 16L, p.first)
                            points.set(ValueLayout.JAVA_DOUBLE, i * 16L + 8, p.second)
                        }
                        val simple = ManifoldNative.ptr(
                            ManifoldNative.simplePolygon,
                            ManifoldNative.ptr(ManifoldNative.allocSimplePolygon),
                            points,
                            contour.size.toLong(),
                        )
                        owned += simple
                        simplePolygons.setAtIndex(ValueLayout.ADDRESS, index.toLong(), simple)
                    }
                    val polygons = ManifoldNative.ptr(
                        ManifoldNative.polygonsOf,
                        ManifoldNative.ptr(ManifoldNative.allocPolygons),
                        simplePolygons,
                        usable.size.toLong(),
                    )
                    try {
                        return wrap(ManifoldNative.sectionOfPolygons, polygons, fillRule.code)
                    } finally {
                        ManifoldNative.unit(ManifoldNative.deletePolygons, polygons)
                    }
                } finally {
                    owned.forEach { ManifoldNative.unit(ManifoldNative.deleteSimplePolygon, it) }
                }
            }
        }

        /** [ofPolygons] for the common single-contour case. */
        fun ofPolygon(contour: List<Vec2>, fillRule: FillRule = FillRule.POSITIVE): Section =
            ofPolygons(listOf(contour), fillRule)

        /**
         * A region from [FLine]s — the bridge from anything Field drew.
         *
         * Each [FLine] sub-path (a `moveTo` and the nodes after it) becomes one contour, sampled to
         * straight segments because Clipper has no notion of a cubic. A typography outline, an
         * iso-contour from [afield.stdlib.generateIsoContours], a hand-drawn `fline { }` — all of
         * them arrive here and become something offsettable.
         *
         * ```kotlin
         * val letter = Section.ofFLines(typeface.outlines("Fi"))
         * display(letter.offset(2.0, JoinType.ROUND).toFLines())     // a swollen outline
         * ```
         *
         * @param sampleDistance the chord length to flatten curves to, in the FLine's own units. The
         *   default is fine for screen-scale drawing; drop it for a shape you are about to offset by
         *   a small amount, since offsetting exaggerates a coarse polyline's flat spots.
         */
        fun ofFLines(
            lines: List<FLine>,
            sampleDistance: Double = 1.0,
            fillRule: FillRule = FillRule.POSITIVE,
        ): Section {
            val contours = lines.flatMap { line ->
                // `pieces()` is FLine's own split-at-every-MoveTo, so a single FLine carrying an
                // outline and its counters becomes one contour each rather than one tangled ring.
                line.pieces().mapNotNull { subPath ->
                    val sampled = FLinesAndJavaShapes.samplePoints(subPath, sampleDistance.toFloat())
                    if (sampled.size < 3) {
                        null
                    } else {
                        // A closed FLine's last sample coincides with its first; leaving both in gives
                        // Clipper a zero-length edge, which it tolerates and Manifold's validity check
                        // does not need to see.
                        val points = sampled.map { Vec2(it.x, it.y) }
                        if (points.size > 3 && points.first().distance(points.last()) < 1e-6) points.dropLast(1) else points
                    }
                }
            }
            return ofPolygons(contours, fillRule)
        }

        /** [ofFLines] for one line. */
        fun ofFLine(line: FLine, sampleDistance: Double = 1.0, fillRule: FillRule = FillRule.POSITIVE): Section =
            ofFLines(listOf(line), sampleDistance, fillRule)

        /** Union of many regions at once, planned as one batch. */
        fun union(sections: List<Section>): Section = batchBoolean(sections, BooleanOp.UNION)

        fun union(vararg sections: Section): Section = union(sections.toList())

        fun intersection(sections: List<Section>): Section = batchBoolean(sections, BooleanOp.INTERSECTION)

        fun difference(sections: List<Section>): Section = batchBoolean(sections, BooleanOp.DIFFERENCE)

        fun batchBoolean(sections: List<Section>, op: BooleanOp): Section {
            if (sections.isEmpty()) return empty()
            if (sections.size == 1) return sections[0].copy()
            val vec = ManifoldNative.ptr(
                ManifoldNative.sectionVecOf,
                ManifoldNative.ptr(ManifoldNative.allocSectionVec),
                sections.size.toLong(),
            )
            try {
                sections.forEachIndexed { i, s ->
                    ManifoldNative.unit(ManifoldNative.sectionVecSet, vec, i.toLong(), s.ptr)
                }
                return wrap(ManifoldNative.sectionBatchBoolean, vec, op.code)
            } finally {
                ManifoldNative.unit(ManifoldNative.deleteSectionVec, vec)
            }
        }

        internal fun wrap(handle: java.lang.invoke.MethodHandle, vararg args: Any?): Section =
            Section(ManifoldNative.ptr(handle, ManifoldNative.newSection(), *args))
    }

    init {
        Solid.TRACKER.get()?.add(this)
    }
}

/** [Section.ofFLine], as an extension, for reading left to right off an existing line. */
fun FLine.toSection(sampleDistance: Double = 1.0, fillRule: FillRule = FillRule.POSITIVE): Section =
    Section.ofFLine(this, sampleDistance, fillRule)

/** [Section.ofFLines], as an extension. */
fun List<FLine>.toSection(sampleDistance: Double = 1.0, fillRule: FillRule = FillRule.POSITIVE): Section =
    Section.ofFLines(this, sampleDistance, fillRule)

/** The 3D points of a [Section]'s contours, on the `z = 0` plane — for [Solid.hullOf] and friends. */
fun Section.toVec3s(z: Double = 0.0): List<Vec3> =
    toPolygons().flatten().map { Vec3(it.x, it.y, z.toFloat()) }
