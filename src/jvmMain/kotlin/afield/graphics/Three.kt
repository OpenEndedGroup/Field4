@file:afield.CompletionCategory("Graphics")

package afield.graphics

import afield.Box
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.Vec4
import afield.stdlib.boundsArray
import afield.stdlib.signedVolume
import afield.stdlib.topology

/**
 * One 3D output for everything Field can draw.
 *
 * [Box.three] is [Box.threeLines] grown a vocabulary. It takes meshes as well as FLines, draws them in
 * one orbitable scene, and lays several views out side by side when you ask for a contact sheet:
 *
 * ```kotlin
 * three(channel = "car", height = 460) {
 *     mesh(car, wireframe = false, pickable = true)
 *     lines(measurements)                       // annotations in the same space as the solid
 *     inspect(units = "mm")                     // …and a ruler that reaches both
 *     camera(yaw = 34.0, pitch = 26.0, distance = 2.4)
 *     title("ToyCar.glb — 108,936 triangles")
 * }
 * ```
 *
 * `pickable` and `inspect` are new on 2026-08-16, and the example above is why. Without them this same
 * scene drew a solid and its annotations together and then let you measure **only the annotations** —
 * the viewer built its pick list out of the FLine half of the payload, so a ray went straight through
 * every mesh in it. A mesh hit now resolves to a vertex, an edge or a triangle
 * ([ThreeSurfaceHit]) and the measure tool can span the two.
 *
 * # Why this exists
 *
 * Meshes used to go to the CPU rasterizer in [VisionGeometryPreview] and come back as a static PNG.
 * That renderer is still here and still the right tool for a value-visualizer thumbnail and for the
 * pixel-exact proofs in `MeshDefectRenderTest` and friends — it is deterministic, which a GPU is not.
 * What it could never do is let you *orbit*, or put a solid and its FLine annotations in one scene.
 *
 * # What you give up, said plainly
 *
 * The shading models differ. The rasterizer is two-light Lambert over vertex colours; this is
 * three.js's `MeshStandardMaterial` under the viewer's ambient/key/rim rig. The same mesh will not
 * come out pixel-identical, and it is not supposed to. What *is* held identical is the input — see
 * [toThreeDrawable] — and the back-face-culling behaviour, so a face wound the wrong way is missing
 * from both.
 *
 * @see toThreeDrawable for the lowering
 * @see ThreeDrawable for the format the viewer actually consumes
 */
class ThreeSceneBuilder internal constructor(private val samplesPerCubic: Int) {

    internal val drawables = ArrayList<ThreeDrawable>()
    internal val flines = ArrayList<FLine>()
    internal val panels = ArrayList<ThreePanel>()
    internal var columns: Int = 1
    internal var camera: ThreeCameraSpec? = null
    internal var background: String? = null
    internal var autoFrame: Boolean = true
    internal var inspector: ThreeInspector? = null
    private var helpersExplicit: Boolean? = null

    /**
     * Whether the reference grid and axis tripod are drawn.
     *
     * Left alone, they follow the content: **on for an FLine-only scene** (where the viewer has always
     * drawn them) and **off as soon as there is a mesh**, because both helpers are a fixed world size
     * and a mesh is whatever size its file says. See [ThreeGeometryPayload.helpers].
     */
    fun helpers(on: Boolean) {
        helpersExplicit = on
    }

    internal fun helpersEnabled(): Boolean = helpersExplicit ?: drawables.isEmpty()

    /**
     * Add a solid.
     *
     * @param wireframe depth-tested wireframe over the shading. On by default for the same reason it
     * is on in [MeshView]: it turns "a smooth blob" into "a mesh you can count the triangles in".
     * @param cullBackFaces on by default, so an inside-out face is a *missing* face — the diagnostic
     * the rasterizer has always given and the switch of renderer must not silently drop.
     * @param panel which viewport draws it; `-1` for all of them.
     * @param id a stable name for the solid, echoed back on every hit as [ThreeSurfaceHit.id]. Free,
     * and the thing a shared `onClick` closure switches on.
     * @param onClick called with a [ThreeSurfaceHit] naming the triangle, edge or vertex that was
     * clicked — the mesh counterpart of [afield.stdlib.onClick] on an FLine.
     * @param hoverColor highlight while the pointer is over the solid, as an FLine's does.
     * @param pickable whether a raycast may hit this mesh at all. Implied by [onClick] and by
     * [hoverColor]; set it on its own to make a mesh **measurable without being clickable**, which is
     * what [inspect]'s pick-to-measure needs and is the whole point of the flag. Off by default because
     * a raycast against a 100k-triangle mesh is a brute-force triangle sweep and a figure that is only
     * a picture should not pay for one.
     * @param group which assembly group [inspect]'s explode moves this with, or `-1` to leave it put.
     * A drawable's group is one number for the whole drawable — unlike an FLine batch, which carries a
     * per-vertex `group` attribute — so the viewer moves it by its object transform rather than by
     * rewriting a buffer. Same offsets, a great deal less work per slider frame.
     */
    fun mesh(
        mesh: TriMesh,
        label: String? = null,
        wireframe: Boolean = true,
        cullBackFaces: Boolean = true,
        flatShading: Boolean = false,
        opacity: Double = 1.0,
        panel: Int = 0,
        id: String = "",
        onClick: ((ThreeSurfaceHit) -> Unit)? = null,
        hoverColor: Vec4? = null,
        pickable: Boolean = false,
        group: Int = -1,
    ) {
        drawables += mesh.toThreeDrawable(
            style = ThreeDrawStyle.surface(
                wireframe = wireframe,
                cullBackFaces = cullBackFaces,
                flatShading = flatShading,
                opacity = opacity.toFloat(),
            ),
            label = label,
            panel = panel,
            group = group,
            id = id,
            callbackId = onClick?.let { registerThreeSurfaceClickCallback(it) } ?: "",
            hoverColor = hoverColor?.let { floatArrayOf(it.x, it.y, it.z, it.w) },
            pickable = pickable,
        )
    }

    /** Add FLines. They keep every behaviour they have in [Box.threeLines] — picking, hover, fills. */
    fun lines(lines: Collection<FLine>) {
        flines += lines
    }

    /** Vararg convenience for [lines]. */
    fun lines(vararg lines: FLine) {
        flines += lines
    }

    /** Where the camera starts. Setting it turns auto-framing off — you picked an angle, keep it. */
    fun camera(
        yaw: Double = 28.0,
        pitch: Double = 18.0,
        distance: Double = 2.7,
        fieldOfView: Double = 40.0,
    ) {
        camera = ThreeCameraSpec(yaw, pitch, distance, fieldOfView)
        autoFrame = false
    }

    /** The camera as a [MeshView] already describes it — the migration path for existing figures. */
    fun camera(view: MeshView) {
        camera = view.toThreeCameraSpec()
        autoFrame = false
    }

    /** Scene background as a CSS colour. */
    fun background(css: String) {
        background = css
    }

    /** A titled viewport. Call it once per panel, in order; [columns] lays them out. */
    fun panel(
        title: String? = null,
        caption: String? = null,
        facts: String? = null,
        camera: ThreeCameraSpec? = null,
        bounds: ThreeBounds? = null,
    ) {
        panels += ThreePanel(camera = camera, title = title, caption = caption, facts = facts, bounds = bounds)
    }

    /** Panels per row. */
    fun columns(n: Int) {
        require(n >= 1) { "columns must be at least 1, got $n" }
        columns = n
    }

    /** A single title over a one-panel scene. */
    fun title(text: String) {
        if (panels.isEmpty()) panels += ThreePanel(title = text)
        else panels[0] = panels[0].copy(title = text)
    }

    /** A single caption under a one-panel scene. */
    fun caption(text: String) {
        if (panels.isEmpty()) panels += ThreePanel(caption = text)
        else panels[0] = panels[0].copy(caption = text)
    }

    /**
     * Turn the measure / section / explode toolbar on for this scene.
     *
     * The inspector landed on 2026-08-11 as [Box.threeInspect], which takes a `Collection<FLine>` and
     * builds the payload itself. There was no way to ask for it here, so the one scene type that has
     * both a solid and its annotations — the reason [Box.three] exists — was the one scene type that
     * could not be measured. This is that hole, closed.
     *
     * Pair it with `mesh(…, pickable = true)`: the toolbar is what draws the dimension, and the flag is
     * what lets the ray find the solid. Neither is much use without the other.
     *
     * @param units appended to every dimension: `"mm"`.
     * @param measurements dimensions the figure carries from the moment it is drawn, as distinct from
     * the ones a reader picks.
     */
    fun inspect(
        units: String = "",
        decimals: Int = 2,
        explode: ThreeExplode = ThreeExplode(),
        section: ThreeSection = ThreeSection(),
        measurements: List<ThreeMeasurement> = emptyList(),
        pickToMeasure: Boolean = true,
        groups: List<String> = emptyList(),
    ) {
        inspector = ThreeInspector(
            enabled = true,
            groups = groups,
            explode = explode,
            section = section,
            measurements = measurements,
            units = units,
            decimals = decimals,
            pickToMeasure = pickToMeasure,
        )
    }

    /** Build the payload. Internal: [Box.three] is the only caller. */
    internal fun payload(uid: String, frameWidth: Int? = null, frameHeight: Int? = null): ThreeGeometryPayload {
        // The FLine half is delegated wholesale to the existing builder rather than reimplemented, so
        // `three { lines(…) }` and `threeLines(…)` cannot disagree about what an FLine looks like —
        // including its interactive objects, its topology strings and its click callbacks.
        val base = if (flines.isNotEmpty()) {
            buildThreeGeometryPayload(uid, flines, samplesPerCubic, autoFrame, editable = false)
        } else null

        val tracker = BoundsTracker()
        base?.bounds?.let { tracker.include(it) }
        drawables.forEach { tracker.include(it) }

        // The HUD's counts are the whole scene's, so the drawables' triangles are added to whatever
        // the FLine half contributed rather than replacing it.
        val drawableTriangles = drawables.sumOf { (it.geometry.index?.array?.size ?: 0) / 3 }

        return ThreeGeometryPayload(
            uid = uid,
            lines = base?.lines,
            fills = base?.fills,
            points = base?.points,
            interactive = base?.interactive ?: emptyList(),
            bounds = tracker.toBounds(),
            meta = ThreeMeta(
                lineSegments = base?.meta?.lineSegments ?: 0,
                triangles = (base?.meta?.triangles ?: 0) + drawableTriangles,
                points = base?.meta?.points ?: 0,
            ),
            autoFrame = autoFrame,
            drawables = drawables.toList(),
            panels = panels.toList(),
            columns = columns,
            background = background,
            camera = camera,
            helpers = helpersEnabled(),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            inspector = inspector,
        )
    }
}

/** Grow a [BoundsTracker] by an existing bounds block. */
internal fun BoundsTracker.include(b: ThreeBounds) {
    include(b.min[0], b.min[1], b.min[2])
    include(b.max[0], b.max[1], b.max[2])
}

/** Grow a [BoundsTracker] by every position in a drawable, honouring its transform if it has one. */
internal fun BoundsTracker.include(d: ThreeDrawable) {
    val p = d.geometry.attributes["position"]?.array ?: return
    val m = d.transform
    var i = 0
    while (i + 2 < p.size) {
        val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
        if (m == null) {
            include(x, y, z)
        } else {
            // Column-major, as three.js Matrix4.fromArray reads it.
            include(
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
            )
        }
        i += 3
    }
}

/** [MeshView]'s four camera numbers, which [ThreeCameraSpec] deliberately mirrors one for one. */
fun MeshView.toThreeCameraSpec(): ThreeCameraSpec =
    ThreeCameraSpec(yawDegrees, pitchDegrees, distance, fieldOfViewDegrees)

/**
 * One cell of a [threeGrid]: a mesh, its heading, and whatever you want written under it.
 *
 * The `vararg Pair<String, TriMesh>` overload covers the common case; this one exists because a
 * gallery usually has something specific to say about each panel — `polytope_examples.md` writes the
 * vertex/edge/face counts and the face-type histogram — and [TriMesh.threeFacts]'s generic sentence
 * is not it.
 */
data class ThreeMeshPanel(
    val label: String,
    val mesh: TriMesh,
    /** Written under the panel in monospace. Plain text: it is set as `textContent`, not as HTML. */
    val facts: String? = null,
    val caption: String? = null,
)

/** This mesh's bounds in the payload's terms. */
fun TriMesh.toThreeBounds(): ThreeBounds {
    val b = boundsArray()
    return ThreeBounds(
        min = floatArrayOf(b[0].toFloat(), b[1].toFloat(), b[2].toFloat()),
        max = floatArrayOf(b[3].toFloat(), b[4].toFloat(), b[5].toFloat()),
    )
}

/**
 * The fact line under a rendered mesh — the same sentence [afield.stdlib.toFigureHtml] computes.
 *
 * Shared rather than reimplemented so the two renderers cannot report a different genus for the same
 * mesh, which would be a uniquely annoying bug to chase.
 */
fun TriMesh.threeFacts(): String {
    val b = boundsArray()
    val topo = topology()
    return buildString {
        append(
            "%d verts · %d tris · extent %.3f × %.3f × %.3f".format(
                vertexCount, triangleCount, b[3] - b[0], b[4] - b[1], b[5] - b[2],
            )
        )
        append(" · χ=${topo.eulerCharacteristic}")
        topo.genus?.let { append(" genus $it") }
        if (topo.isWatertight) append(" · watertight, volume %.4f".format(signedVolume()))
        else append(" · open (${topo.boundaryLoops.size} boundary loop(s))")
    }
}

/**
 * Display a Three.js viewer built from [block], and stream geometry updates to it.
 *
 * Returns the live [ThreeGeometryViewState], so the scene stays drivable after it is on screen — the
 * same contract [Box.threeLines] has.
 *
 * @param channel output channel name (disclosure panel).
 * @param name viewer name, used to build a stable id within this [Box].
 * @param height iframe height in pixels.
 * @param samplesPerCubic sampling density for any FLine cubics in the scene.
 */
fun Box.three(
    channel: String = "main",
    name: String = "three",
    height: Int = 430,
    samplesPerCubic: Int = 48,
    width: Int? = null,
    block: ThreeSceneBuilder.() -> Unit,
): ThreeGeometryViewState {
    val builder = ThreeSceneBuilder(samplesPerCubic).apply(block)
    val uid = "${this.id}_$name"
    // The size must be in **pixels on the iframe itself**, and that is not decoration.
    //
    // `field.css:491` says `.disclosure-content iframe { width: 100% !important; height: 100% !important }`,
    // which reads as though the inline size is ignored. It is not: `outputareas2.js`'s
    // `iframeIntrinsicSize` *measures* the output panel from `iframe.style.width` / `.style.height`
    // and sizes the disclosure to the result — and only then does the CSS stretch the iframe to fill
    // the panel it just caused. The inline size sets the panel; the panel sets the iframe.
    //
    // `parsePixels` returns 0 for a percentage, so the `width: 100%` that `threeLines` has always
    // written measures as "no intrinsic width" and the panel falls back to its current size. That is
    // right for an FLine viewer, which wants to fill its column, and wrong for a migrated mesh figure:
    // it silently collapsed 1520- and 2040-pixel-wide galleries to a uniform 1136 and lost a third of
    // every picture. A [width] in pixels is how a figure keeps the size its author drew it at.
    val sizing = if (width != null && width > 0) "width:${width}px" else "width:100%"
    display(
        channel,
        "<iframe src='${threeViewerIframeSource(uid)}' " +
            "style='border:none;padding:0;margin:0;$sizing;height:${height}px'>"
    )
    val view = threeViewFor(uid)
    view.update(builder.payload(uid, frameWidth = width, frameHeight = height))
    return view
}

/**
 * One mesh, drawn on its own.
 *
 * @param height Frame height in pixels. Defaults to the [MeshView]'s own — that number is the
 * author's statement about how tall the figure should be, and honouring it is what keeps a migrated
 * docs figure the size it was. The frame will usually be *wider* than the [MeshView] asked for,
 * since a box output is as wide as its column, but the camera frames vertically so the mesh occupies
 * the same fraction of the height either way.
 */
fun Box.three(
    subject: TriMesh,
    channel: String = "main",
    name: String = "three",
    view: MeshView = MeshView(),
    height: Int = view.height,
    title: String? = null,
    caption: String? = null,
    facts: Boolean = true,
): ThreeGeometryViewState = three(
    channel = channel,
    name = name,
    height = height,
    width = view.width,
) {
    mesh(subject, wireframe = view.wireframe, cullBackFaces = view.cullBackFaces)
    camera(view)
    panel(title = title, caption = caption, facts = if (facts) subject.threeFacts() else null)
}

/** FLines, drawn on their own — [Box.threeLines] by another name, for a caller already using [three]. */
fun Box.three(
    lines: Collection<FLine>,
    channel: String = "main",
    name: String = "three",
    height: Int = 430,
    samplesPerCubic: Int = 48,
): ThreeGeometryViewState = three(channel, name, height, samplesPerCubic) { lines(lines) }

/**
 * One mesh from several angles, in one WebGL context.
 *
 * Every panel frames the *same* bounds, so the shape does not appear to breathe as it turns, and the
 * geometry is uploaded once (`panel = -1`) rather than per view.
 */
fun Box.threeTurntable(
    mesh: TriMesh,
    channel: String = "main",
    name: String = "turntable",
    height: Int = 260,
    views: List<MeshView> = MeshView.turntable(4),
    columns: Int = views.size,
    title: String? = null,
    wireframe: Boolean = true,
): ThreeGeometryViewState {
    require(views.isNotEmpty()) { "threeTurntable needs at least one view" }
    val shared = mesh.toThreeBounds()
    return three(
        channel = channel,
        name = name,
        height = height,
        width = (height * 4 / 3) * views.size,
    ) {
        mesh(mesh, wireframe = wireframe, panel = -1)
        columns(columns)
        views.forEach { v ->
            panel(
                title = "yaw ${v.yawDegrees.toInt()}° pitch ${v.pitchDegrees.toInt()}°",
                camera = v.toThreeCameraSpec(),
                bounds = shared,
            )
        }
        title?.let { caption(it) }
    }
}

/**
 * Several meshes side by side, each in its own panel and each framing its own bounds.
 *
 * The replacement for `showMeshGrid` and for the galleries in `polytope_examples.md`. One WebGL
 * context however many panels there are — see [ThreePanel].
 */
fun Box.threeGrid(
    vararg panels: Pair<String, TriMesh>,
    channel: String = "main",
    name: String = "grid",
    height: Int = 430,
    columns: Int = 2,
    view: MeshView = MeshView(),
    facts: Boolean = false,
    cellWidth: Int = view.width,
): ThreeGeometryViewState = threeGrid(
    panels = panels.map { (label, m) ->
        ThreeMeshPanel(label, m, facts = if (facts) m.threeFacts() else null)
    },
    channel = channel,
    name = name,
    height = height,
    columns = columns,
    view = view,
    cellWidth = cellWidth,
)

/** [threeGrid] with a caller-supplied fact line per cell. */
fun Box.threeGrid(
    panels: List<ThreeMeshPanel>,
    channel: String = "main",
    name: String = "grid",
    height: Int = 430,
    columns: Int = 2,
    view: MeshView = MeshView(),
    cellWidth: Int = view.width,
): ThreeGeometryViewState {
    require(panels.isNotEmpty()) { "threeGrid needs at least one panel" }
    return three(
        channel = channel,
        name = name,
        height = height,
        // As wide as the cells it actually has, so a two-up sheet is not given a five-up frame.
        width = cellWidth * minOf(columns, panels.size),
    ) {
        columns(columns)
        panels.forEachIndexed { index, cell ->
            mesh(
                cell.mesh,
                label = cell.label,
                wireframe = view.wireframe,
                cullBackFaces = view.cullBackFaces,
                panel = index,
            )
            panel(
                title = cell.label,
                camera = view.toThreeCameraSpec(),
                bounds = cell.mesh.toThreeBounds(),
                facts = cell.facts,
                caption = cell.caption,
            )
        }
    }
}
