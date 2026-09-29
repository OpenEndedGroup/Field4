package afield.graphics

import afield.Box
import afield.stdlib.label
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * The three.js output area as an **instrument** rather than a picture of one.
 *
 * ### Why this file exists
 *
 * Every one of the human's 2026-08-09 `panel-*` workspace documents renders through the three.js
 * viewer, and one of them ends like this:
 *
 * ```kotlin
 * showConduitRelaxationThree(relaxed, channelPrefix = "cross-host-assembled", explosionMm = 0.0, height = 720)
 * ```
 *
 * `explosionMm = 0.0` is the whole argument for this file. An exploded view *existed*, as a number the
 * human retypes and re-runs a box for. Last night the viewer learned to name which element a click hit;
 * that answered "what is this". It still could not answer the three questions anyone asks of a 3D
 * preview of a thing that gets built:
 *
 *  * **how far apart are these two things** — [ThreeMeasurement], drawn in the scene as a real
 *    dimension with witness lines and a legible number;
 *  * **what does this look like in section** — [ThreeSection], a cut plane with a highlighted cut
 *    outline, not merely a hidden front half;
 *  * **how does it come apart** — [ThreeExplode], a *parameter* with a scrubber, in two modes.
 *
 * ### Where the work actually happens
 *
 * In the viewer, on the client, and deliberately. A slider that costs a websocket round-trip and a box
 * re-run is the thing being replaced, so the displacement, the cut and the closest approach are all
 * computed in `threejs_viewer.html`. Which means they are computed **twice** — once there and once
 * here — and that is the situation `ViewerJavaScript` exists for. Each of the three is a marked
 * `// [viewer-region …]` region, lifted out of the shipped HTML and executed on GraalJS by
 * `ThreeInspectorMeasureDifferentialTest`, `ThreeInspectorExplodeDifferentialTest` and
 * `ThreeInspectorSectionDifferentialTest`, against expectations derived from the geometry the figures
 * were built from.
 *
 * ### What is not covered, said once
 *
 * The regions are the *geometry*. The three.js plumbing around them — `localClippingEnabled`, the
 * canvas-texture sprite the dimension text is drawn into, the HUD sliders — is not liftable and is not
 * under differential test; it is covered by the docs capture in `public/docs/three_inspector.md`, which
 * is a real screenshot of a real run rather than an assertion. If the dimension text renders as an
 * empty sprite, these tests will still be green. That is the honest boundary.
 */
@Serializable
data class ThreeExplode(
    /**
     * How far apart to push the groups, in the figure's own units.
     *
     * `0.0` is the assembled state and is required to be *exactly* the assembled state — the region
     * returns literal zero offsets rather than a scaled direction, so the geometry drawn at zero is the
     * geometry the server sent, bit for bit.
     */
    val amount: Double = 0.0,
    /**
     * `"radial"` — every group slides directly away from the bounds centre. Reads well on an irregular
     * assembly, which is what a routed conduit run is.
     *
     * `"axis"` — every group slides along [axis], spaced by its **rank** in that direction rather than
     * by its distance. This is the layered-wall explode: four skins 12 mm apart in reality fan out
     * evenly instead of staying in a clump. The ranks are centred so the assembly does not walk out of
     * frame as the slider moves.
     */
    val mode: String = "radial",
    val axis: FloatArray = floatArrayOf(0f, 1f, 0f),
    /**
     * The top of the viewer's own scrubber. `0.0` means "work it out from the bounds", which is what a
     * caller who has not thought about it wants; a caller who has can pin it so that two figures
     * side-by-side scrub at the same rate.
     */
    val sliderMax: Double = 0.0,
) {
    override fun toString(): String =
        "ThreeExplode(amount=$amount, mode=$mode, axis=${axis.contentToString()}, sliderMax=$sliderMax)"
}

/**
 * A cut plane through the figure.
 *
 * [fraction] runs `0`..`1` across the figure's own bounds *measured along [normal]*, rather than being
 * a world coordinate, so a section is portable between figures and a scrubber over it is meaningful
 * without knowing the model's scale.
 *
 * [outline] is the part that makes this a section rather than a hidden half: the viewer computes where
 * the plane crosses each triangle of the fill and each line segment, and draws those crossings as a
 * bright cut line. Clipping alone shows you the inside of a shell; the outline shows you the *cut*.
 */
@Serializable
data class ThreeSection(
    val enabled: Boolean = false,
    val normal: FloatArray = floatArrayOf(1f, 0f, 0f),
    val fraction: Double = 0.5,
    val outline: Boolean = true,
    /** Which half survives. Flipping is not the same as negating [normal]: [fraction] keeps its sense. */
    val flip: Boolean = false,
) {
    override fun toString(): String =
        "ThreeSection(enabled=$enabled, normal=${normal.contentToString()}, fraction=$fraction, " +
            "outline=$outline, flip=$flip)"
}

/**
 * One end of a measurement: a point, a segment or a polygon, in the figure's own coordinates.
 *
 * These are the three shapes a hit record resolves to, which is not a coincidence — a measurement made
 * by clicking two things in the viewer produces exactly these, and a measurement written in Kotlin
 * should be the same object so that the two are interchangeable.
 */
@Serializable
data class ThreeMeasureElement(
    val kind: String,
    val points: List<FloatArray>,
) {
    override fun toString(): String = "ThreeMeasureElement($kind, ${points.size} point(s))"

    companion object {
        fun point(x: Double, y: Double, z: Double) =
            ThreeMeasureElement("point", listOf(floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())))

        fun segment(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) =
            ThreeMeasureElement(
                "segment",
                listOf(
                    floatArrayOf(ax.toFloat(), ay.toFloat(), az.toFloat()),
                    floatArrayOf(bx.toFloat(), by.toFloat(), bz.toFloat()),
                ),
            )

        /**
         * The closed ring of an FLine's first subpath, as a polygon — the usual way to name "this panel".
         *
         * Anchors only: control points are not on the boundary, and a measurement to a control point is
         * a measurement to something the fabricator cannot find.
         */
        fun face(line: FLine): ThreeMeasureElement = ThreeMeasureElement(
            "polygon",
            line.nodes.filterNot { it is FLine.Close }
                .map { floatArrayOf(it.to.x, it.to.y, it.to.z) },
        )
    }
}

/** A dimension the figure carries from the moment it is drawn, rather than one the user has to pick. */
@Serializable
data class ThreeMeasurement(
    val a: ThreeMeasureElement,
    val b: ThreeMeasureElement,
    /** Prepended to the number. `"clear "` gives `clear 2.5 mm`. */
    val label: String = "",
)

/** A supplied, independently checked insertion motion, from displaced pose to assembled pose. */
@Serializable
data class ThreeAssemblyMotion(val part: String, val displacement: DoubleArray)

/** Canonical up axis; Z-up parts are rotated into the viewer's Y-up display frame. */
@Serializable
enum class ThreeUpAxis { Y, Z }

/** Canonical double coordinates and real boundary edges for artifact-local feature references.
 * Triangle order agrees with the display mesh even when shading duplicates its vertices.
 * [faces] groups connected coplanar triangles; Boolean provenance edges are not selectable seams.
 */
@Serializable
data class ThreePartTopology(
    val positions: DoubleArray,
    val indices: IntArray,
    val edges: List<List<Int>>,
    val faces: IntArray,
)

/** A selection on one exact artifact. Interpolated points locate a pick, not a machining tolerance. */
@Serializable
data class ThreeFeatureSelection(
    val artifactDigest: String,
    val partId: String,
    val kind: String,
    val point: DoubleArray,
    val triangle: Int? = null,
    val face: Int? = null,
    val barycentric: DoubleArray? = null,
    val vertex: Int? = null,
    val edge: List<Int>? = null,
    val edgeT: Double? = null,
    val region: ThreeRegionSelection? = null,
)

/** A view-space rectangle and the frontmost surface triangles it actually covered. */
@Serializable
data class ThreeRegionSelection(
    val cx: Double, val cy: Double, val width: Double, val height: Double, val angle: Double,
    val viewport: List<Int>, val pixelCount: Int, val surfaces: List<ThreeRegionSurface>,
    val view: ThreeReviewState? = null,
)

/** Artifact-local visible triangles belonging to one part in a rectangular review region. */
@Serializable
data class ThreeRegionSurface(val partId: String, val triangles: List<Int>)

/** Saved observations are valid only for the exact artifact digest. Face ordinals are artifact-local. */
@Serializable
data class ThreeReviewState(
    val artifactDigest: String,
    val partIds: List<String>,
    val renderOrigin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    val renderUpAxis: ThreeUpAxis = ThreeUpAxis.Y,
    val hidden: List<String> = emptyList(),
    val camera: DoubleArray = doubleArrayOf(),
    val cameraFov: Double? = null,
    val cameraZoom: Double? = null,
    val target: DoubleArray = doubleArrayOf(),
    val explode: Double = 0.0,
    val assemblyStep: Double? = null,
    val light: DoubleArray = doubleArrayOf(5.0,8.0,6.0),
    val sectionEnabled: Boolean = false,
    val sectionFlip: Boolean = false,
    val sectionFraction: Double = 0.5,
    val sectionNormal: DoubleArray = doubleArrayOf(1.0,0.0,0.0),
    val selectedPart: String? = null,
    val selectedTriangle: Int? = null,
    val selection: ThreeFeatureSelection? = null,
)

/**
 * The inspector block of a geometry payload: what the viewer should be doing beyond drawing.
 *
 * [groups] is the assembly's parts list — one name per group, in the order the per-vertex `group`
 * attribute indexes into. It is here rather than only in the viewer so that the explode scrubber can
 * *name* what it is pulling apart.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ThreeInspector(
    val enabled: Boolean = true,
    val groups: List<String> = emptyList(),
    val explode: ThreeExplode = ThreeExplode(),
    val section: ThreeSection = ThreeSection(),
    val measurements: List<ThreeMeasurement> = emptyList(),
    /** Appended to every dimension. The human's panels are in millimetres. */
    val units: String = "",
    val decimals: Int = 2,
    /** Let the user pick two elements in the viewer and measure between them. */
    val pickToMeasure: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val artifactDigest: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val renderOrigin: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val renderUpAxis: ThreeUpAxis = ThreeUpAxis.Y,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val assembly: List<ThreeAssemblyMotion> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val review: ThreeReviewState? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parts: Map<String, ThreePartTopology> = emptyMap(),
)

/**
 * A live inspector view: the geometry, plus the three controls, plus the ability to change them.
 *
 * The point of the class is the last part. [explode], [section] and [measure] re-publish the payload
 * over the existing websocket, so a box can animate an explosion, drive a section from a slider box, or
 * add a dimension in response to a click — none of which is possible when the exploded state is a
 * literal in the source.
 *
 * Thread-safety is the same as [ThreeGeometryViewState]'s: `update` is safe to call from anywhere and
 * marshals onto the main loop. The spec fields are only written from whichever thread is driving.
 */
class ThreeInspectorView internal constructor(
    val state: ThreeGeometryViewState,
    private val lines: List<FLine>,
    private val groupKeys: List<String>,
    private val samplesPerCubic: Int,
    private val autoFrame: Boolean,
    private var inspector: ThreeInspector,
) {
    val uid: String get() = state.uid

    /** The current inspector block, as last published. */
    fun inspector(): ThreeInspector = inspector

    /** Republish with a new inspector block. Every mutator below funnels through this. */
    fun set(next: ThreeInspector): ThreeInspectorView {
        inspector = next
        state.update(
            buildThreeGeometryPayload(
                uid = state.uid,
                lines = lines,
                samplesPerCubic = samplesPerCubic,
                autoFrame = autoFrame,
                editable = false,
                groupKeys = groupKeys,
                inspector = next,
            )
        )
        return this
    }

    /** Pull the assembly apart by [amount], in the figure's own units. `0.0` reassembles it exactly. */
    fun explode(amount: Double): ThreeInspectorView =
        set(inspector.copy(explode = inspector.explode.copy(amount = amount)))

    /** Pull it apart along one direction, layer by layer — the wall build-up view. */
    fun explodeAlong(x: Double, y: Double, z: Double, amount: Double): ThreeInspectorView =
        set(
            inspector.copy(
                explode = inspector.explode.copy(
                    amount = amount,
                    mode = "axis",
                    axis = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat()),
                )
            )
        )

    /** Cut the figure at [fraction] of the way across its bounds, measured along the given normal. */
    fun section(
        x: Double,
        y: Double,
        z: Double,
        fraction: Double,
        outline: Boolean = true,
        flip: Boolean = false,
    ): ThreeInspectorView = set(
        inspector.copy(
            section = ThreeSection(
                enabled = true,
                normal = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat()),
                fraction = fraction,
                outline = outline,
                flip = flip,
            )
        )
    )

    /** Put the figure back together. */
    fun clearSection(): ThreeInspectorView =
        set(inspector.copy(section = inspector.section.copy(enabled = false)))

    /** Add a dimension between two elements. */
    fun measure(a: ThreeMeasureElement, b: ThreeMeasureElement, label: String = ""): ThreeInspectorView =
        set(inspector.copy(measurements = inspector.measurements + ThreeMeasurement(a, b, label)))

    /** Add a dimension between two whole FLines, face to face. */
    fun measureFaces(a: FLine, b: FLine, label: String = ""): ThreeInspectorView =
        measure(ThreeMeasureElement.face(a), ThreeMeasureElement.face(b), label)

    fun clearMeasurements(): ThreeInspectorView = set(inspector.copy(measurements = emptyList()))

    override fun toString(): String =
        "ThreeInspectorView(uid=$uid, groups=${inspector.groups.size}, explode=${inspector.explode.amount}, " +
            "section=${inspector.section.enabled}, measurements=${inspector.measurements.size})"
}

/**
 * A three.js viewer you can ask questions of: measure, section, explode.
 *
 * A **sibling** of [Box.threeLines] rather than more parameters on it, for two reasons: the docs page
 * pins `threeLines`' signature in a static block, and — the real one — the return type has to be
 * different. `threeLines` returns something you push new *geometry* to; this returns something you push
 * new *questions* to, and conflating them would make the interesting half undiscoverable.
 *
 * ```kotlin
 * val view = threeInspect(panels, units = "mm", groupBy = { it.label ?: "" })
 * view.explode(180.0)                                   // scrub it apart, no re-run
 * view.section(0.0, 0.0, 1.0, fraction = 0.42)          // cut it
 * view.measureFaces(panels[0], panels[3], "clear ")     // dimension it
 * ```
 *
 * @param groupBy what counts as one part of the assembly. The default is the FLine's
 * [afield.stdlib.label], which is what the `panel-*` documents already set, falling back to one group
 * per line for unlabelled figures — so an existing figure explodes sensibly with no extra work.
 * @param units appended to every dimension: `"mm"`.
 */
fun Box.threeInspect(
    lines: Collection<FLine>,
    channel: String = "debug",
    name: String = "inspector",
    height: Int = 520,
    samplesPerCubic: Int = 48,
    autoFrame: Boolean = true,
    groupBy: (FLine) -> String = { it.label ?: "" },
    explode: ThreeExplode = ThreeExplode(),
    section: ThreeSection = ThreeSection(),
    measurements: List<ThreeMeasurement> = emptyList(),
    units: String = "",
    decimals: Int = 2,
    pickToMeasure: Boolean = true,
): ThreeInspectorView {
    val sourceLines = lines.toList()
    val groupKeys = sourceLines.mapIndexed { index, line ->
        groupBy(line).ifBlank { "#$index" }
    }
    val inspector = ThreeInspector(
        enabled = true,
        groups = distinctGroupNames(groupKeys),
        explode = explode,
        section = section,
        measurements = measurements,
        units = units,
        decimals = decimals,
        pickToMeasure = pickToMeasure,
    )
    val view = threeLines(
        lines = sourceLines,
        channel = channel,
        name = name,
        height = height,
        samplesPerCubic = samplesPerCubic,
        autoFrame = autoFrame,
        editable = false,
    )
    return ThreeInspectorView(view, sourceLines, groupKeys, samplesPerCubic, autoFrame, inspector).set(inspector)
}

/**
 * The group names, de-duplicated in first-appearance order.
 *
 * First-appearance rather than sorted, because the order is the order the `group` vertex attribute
 * indexes into and *also* the order the viewer lists the parts in. Sorting would make the parts list
 * alphabetical and the geometry indices arbitrary, which is the wrong trade for a wall whose layers
 * come in a build-up order.
 *
 * `internal` so `ThreeInspectorExplodeDifferentialTest` can compute the same indices the payload does
 * rather than assuming them.
 */
internal fun distinctGroupNames(groupKeys: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    groupKeys.forEach { seen.add(it) }
    return seen.toList()
}
