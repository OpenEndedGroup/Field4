package afield.graphics.three

import afield.geometry.path.FLine
import afield.geometry.path.cross
import afield.geometry.path.div
import afield.geometry.path.dot
import afield.geometry.path.end
import afield.geometry.path.minus
import afield.geometry.path.perpendicular
import afield.geometry.path.plus
import afield.geometry.path.start
import afield.geometry.path.then
import afield.geometry.path.times
import afield.graphics.visionos.VisionGeometryPreview
import afield.lang.remotejs.JsFun
import afield.ui.visualization.flineTopologyJson

import afield.geometry.path.scale
import afield.ui.visualization.l
import afield.ui.visualization.lines

import afield.geometry.mesh.DEFAULT_SURFACE
import afield.geometry.mesh.TriMesh
import afield.math.linalg.Vec3
import afield.ui.output.prettySummary
import afield.geometry.mesh.shadedNormals
import kotlinx.serialization.Serializable

/**
 * The intermediate representation the Three.js viewer draws: a [ThreeDrawable] is some geometry plus
 * how to draw it.
 *
 * [FLine]s lower to drawables, [TriMesh]es lower to drawables, and anything with a `toTriMesh()`
 * reaches the viewer that way, so a mesh can be orbited and share a scene with its FLine
 * annotations. The viewer knows neither source type; it draws drawables.
 *
 * The geometry is a [ThreeGeometryData], a three.js `BufferGeometry` of named attributes and an
 * index, and [TriMesh]'s positions, indices, normals and colours transcribe into it directly. What a
 * drawable adds is per-drawable material state, [ThreeDrawStyle].
 *
 * [ThreeGeometryPayload]'s `lines`, `fills` and `points` are unchanged by drawables: a payload with
 * an empty [ThreeGeometryPayload.drawables] serialises as a lines-only payload always has, which the
 * editable path and the inspector rely on.
 */

/** How one [ThreeDrawable] is drawn: which three.js object class, and its material settings. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ThreeDrawStyle(
    /** `"surface"`, `"lines"` or `"points"` — which three.js object class draws this. */
    val kind: String,
    /**
     * Draw back faces as well as front ones.
     *
     * Defaults to `true` because that is what an FLine fill has always wanted: a filled path is a
     * sheet with no inside, and culling it would make half the scene vanish depending on where you
     * orbited to. A solid mesh wants the opposite — see [surfaceStyle].
     */
    val doubleSided: Boolean = true,
    /**
     * Write to the depth buffer.
     *
     * `false` for FLine fills, which stack as translucent sheets and must not occlude each other.
     * **`true` for anything solid**, or the mesh renders with no self-occlusion — the far side of a
     * sphere drawn over the near side, which reads as a strange flat blob rather than as a bug.
     */
    val depthWrite: Boolean = false,
    /** Depth-tested wireframe over the shading. Surfaces only; ignored for lines and points. */
    val wireframe: Boolean = false,
    /** Wireframe colour as `[r, g, b, a]` in `[0, 1]`, or `null` for the viewer's default. */
    val wireframeColor: FloatArray? = null,
    /** Shade per triangle rather than per vertex: faceted, so a coarse mesh reads as coarse. */
    val flatShading: Boolean = false,
    /** Multiplied into the per-vertex alpha. */
    val opacity: Float = 1f,
    /** Display data colours without lighting modulation. */
    val unlit: Boolean = false,
    /** Optional display opacity multiplier in X-ray mode; null follows the viewer slider. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val xrayOpacity: Float? = null,
    /**
     * The opacity a [ThreeSceneBuilder.shot] draws this surface at when the shot's x-ray amount is `0`;
     * at `1` it is [opacity]. `null` keeps [opacity] throughout. Lets a see-through view start solid.
     */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val solidOpacity: Float? = null,
    /**
     * A physically based material for a surface: base colour, roughness, metalness and emission.
     * `null`, the default, is the viewer's fixed `roughness 0.6 / metalness 0.1` white, which every
     * figure drawn before this existed still gets. [afield.geometry.mesh.GeometryBuilder.mat] sets it.
     */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val material: ThreeSurfaceMaterial? = null,
) {
    override fun toString(): String = prettySummary(
        "ThreeDrawStyle",
        "kind" to kind,
        "doubleSided" to doubleSided,
        "depthWrite" to depthWrite,
        "wireframe" to wireframe,
        "flatShading" to flatShading,
        "opacity" to opacity,
    )

    companion object {
        /**
         * How a solid is drawn: depth-written and, by default, back-face culled.
         *
         * Both defaults are the rasterizer's, on purpose. [MeshView.cullBackFaces] is on because the
         * headset culls too, and a face wound the wrong way should be *missing* here exactly as it
         * would be missing there — the single most common "why is my mesh full of holes" cause, and
         * worth reproducing rather than papering over. Switching renderers must not quietly switch
         * that diagnostic off; `typography_examples.md` teaches it.
         */
        fun surface(
            wireframe: Boolean = true,
            cullBackFaces: Boolean = true,
            flatShading: Boolean = false,
            opacity: Float = 1f,
            unlit: Boolean = false,
        ) = ThreeDrawStyle(
            kind = "surface",
            doubleSided = !cullBackFaces,
            depthWrite = true,
            wireframe = wireframe,
            flatShading = flatShading,
            opacity = opacity,
            unlit = unlit,
        )

        /** How an FLine's fills have always been drawn — translucent sheets, no depth write. */
        val flineFills = ThreeDrawStyle(kind = "surface", doubleSided = true, depthWrite = false)

        /** Stroked geometry. */
        val lines = ThreeDrawStyle(kind = "lines", depthWrite = true)

        /** Point clouds and FLine node markers. */
        val points = ThreeDrawStyle(kind = "points", depthWrite = true)
    }
}

/**
 * One thing to draw: geometry, how to draw it, and where it sits.
 *
 * [callbackId], [hoverColor] and [topology] carry the same meaning they carry on
 * [ThreeInteractiveObject], so a drawable is pickable on exactly the terms an interactive FLine is.
 *
 * # Pickable, from 2026-08-16
 *
 * Those three fields were declared when the drawable layer landed and **nothing read them**: the viewer
 * built `interactiveObjects` out of `payload.interactive`, which only the FLine half ever fills, so a
 * mesh could be drawn and never hit. The headline example in [Box.three]'s own KDoc — a solid plus its
 * FLine annotations — produced a scene in which you could measure the annotations and not the solid.
 * They are read now; see [pickable], which is the one field that had to be added, and
 * `// [viewer-region surface-hit-picking]` for what a hit on a surface resolves to.
 */
@Serializable
data class ThreeDrawable(
    val geometry: ThreeGeometryData,
    val style: ThreeDrawStyle,
    /** Reported back with a hit, and shown in the panel chrome when there is one. */
    val label: String? = null,
    /** Assembly group for the inspector's explode, or `-1` when this payload has no inspector. */
    val group: Int = -1,
    /** 16 floats, column-major, as three.js `Matrix4.fromArray` wants them. `null` is the identity. */
    val transform: FloatArray? = null,
    /**
     * Which viewport in a multi-panel scene draws this. `0` when there is one panel.
     *
     * **`-1` means every panel**, which is what makes a turntable cheap: one upload of a 100k-triangle
     * mesh drawn from four angles, rather than the same buffer sent four times.
     */
    val panel: Int = 0,
    /** Server-side click callback ID, or empty for a drawable that is not clickable. */
    val callbackId: String = "",
    /** Hover override as `[r, g, b, a]` in `[0, 1]`. */
    val hoverColor: FloatArray? = null,
    /** [flineTopologyJson], for a drawable that came from an FLine and wants element-level hits. */
    val topology: String = "",
    /**
     * A stable name for this drawable, echoed back on every hit as [ThreeSurfaceHit.id].
     *
     * The sibling of [ThreeInteractiveObject.lineIndex], and a string rather than an index on purpose.
     * An FLine's index is meaningful because the caller passed a *list* and the index points back into
     * it; a drawable is added by a `mesh(…)` call and its ordinal changes the moment somebody inserts
     * another one above. `label` is what a person reads and is free to be duplicated or absent; this is
     * what a `when (hit.id)` switches on.
     */
    val id: String = "",
    /**
     * Whether a raycast may hit this drawable at all.
     *
     * Separate from [callbackId] because **pick-to-measure needs no callback**. Measuring from a solid
     * to an annotation is a purely client-side act — the viewer computes the closest approach and draws
     * the dimension without the server hearing about it — so a mesh has to be able to join
     * `interactiveObjects` while having nothing to call. Conversely a mesh with a callback is pickable
     * whether or not this is set; [ThreeSceneBuilder.mesh] works that out for the caller.
     *
     * `false` by default, and that is deliberate rather than lazy: a raycast against an unindexed
     * 100k-triangle mesh is a brute-force triangle sweep, and every existing mesh figure in the docs is
     * a *picture* that would gain nothing and pay for it. Opting in is one named argument.
     */
    val pickable: Boolean = false,
) {
    override fun toString(): String = prettySummary(
        "ThreeDrawable",
        "id" to id,
        "label" to label,
        "style" to style,
        "vertices" to ((geometry.attributes["position"]?.array?.size ?: 0) / 3),
        "indices" to (geometry.index?.array?.size ?: 0),
        "group" to group,
        "panel" to panel,
        "pickable" to pickable,
    )
}

/**
 * Which element of a surface a click landed on.
 *
 * Deliberately the same three-way distinction [afield.graphics.render.FLineHitKind] draws — a dot, a line, an
 * area — under the names a mesh uses for them, so that somebody who has read one hit record can read
 * the other. [TRIANGLE] rather than `FACE` because a mesh face *is* a triangle and saying so avoids the
 * suggestion that the viewer reassembled a coplanar fan into a polygon. It did not; it hands you the
 * triangle three.js hit.
 */
enum class ThreeSurfaceHitKind {
    /** A corner of the hit triangle, within the snap radius. */
    VERTEX,

    /** One of the hit triangle's three edges. */
    EDGE,

    /** The triangle's interior. */
    TRIANGLE,

    /** The classifier refused: a degenerate triangle, or a viewer too old to send one. */
    UNKNOWN,
}

/**
 * Where a click landed on a solid — the sibling of [afield.graphics.render.FLineIntersection].
 *
 * # Read as a pair with FLineIntersection
 *
 * Every field that means the same thing is *named* the same thing, so the two records can be handled by
 * one head: [position] is the raw hit, [onElement] is the point on the element the classifier decided
 * you were pointing at, [label] is the author's name for the object, and [kind] says which of three
 * shapes that element is. Only the identifying triple differs, and it has to: an FLine names an element
 * by `(subPathIndex, nodeIndex, elementIndex)` because that is how an FLine is addressed, and a mesh
 * names one by [faceIndex] plus [vertexIndex], because that is how a mesh is.
 *
 * # Why the barycentric coordinates are here
 *
 * [u], [v], [w] are the hit's barycentric coordinates in the triangle, summing to 1. They are what
 * interpolates anything the mesh carries per vertex (a colour, a UV, a scalar field, a displacement)
 * at the point that was clicked, and they cost nothing to compute once the triangle is
 * known. `FLineIntersection`'s `u`/`v` are a *surface parameterisation* of a quad and are a different
 * animal; the names collide because both are "where in the element", and [w] is the tell that this one
 * is barycentric.
 *
 * @property frontFacing whether the ray met the triangle's front face — its winding normal pointing
 * back towards the camera. `false` means you clicked the inside of the model, which is either a
 * deliberate look through an open shell or the thing that makes a mesh appear to have holes. Nothing in
 * the FLine record corresponds, because an FLine fill is a sheet and has no inside.
 */
data class ThreeSurfaceHit(
    /** [ThreeDrawable.id], or empty when the author did not name the drawable. */
    val id: String = "",
    /** [ThreeDrawable.label]. */
    val label: String? = null,
    /** The drawable's ordinal in the payload — the sibling of `FLineIntersection.lineIndex`. */
    val drawableIndex: Int = -1,
    val kind: ThreeSurfaceHitKind = ThreeSurfaceHitKind.UNKNOWN,
    /** Where the ray met the surface, in the scene's own coordinates. */
    val position: Vec3,
    /**
     * The point *on* the named element: the corner for a [ThreeSurfaceHitKind.VERTEX], the foot of the
     * perpendicular for an [ThreeSurfaceHitKind.EDGE], the hit itself for the interior.
     */
    val onElement: Vec3? = null,
    /** Which triangle of the mesh, counting from zero — the sibling of `elementIndex`. */
    val faceIndex: Int = -1,
    /**
     * The mesh's own vertex index for a [ThreeSurfaceHitKind.VERTEX] hit, else `-1`.
     *
     * The index into the drawable's `position` attribute, which is the number you use to look the
     * clicked vertex up in whatever the mesh was built from. The sibling of `nodeIndex`.
     */
    val vertexIndex: Int = -1,
    /** Which of the triangle's three edges, `0` = (a,b), `1` = (b,c), `2` = (c,a). `-1` otherwise. */
    val edgeIndex: Int = -1,
    /** Barycentric weight of the triangle's first corner. */
    val u: Double = Double.NaN,
    /** Barycentric weight of the second corner. */
    val v: Double = Double.NaN,
    /** Barycentric weight of the third corner. `u + v + w == 1`. */
    val w: Double = Double.NaN,
    val frontFacing: Boolean = true,
    /** The three corners of the hit triangle, in the mesh's winding order. */
    val triangle: List<Vec3> = emptyList(),
) {
    override fun toString(): String = prettySummary(
        "ThreeSurfaceHit",
        "id" to id,
        "label" to label,
        "kind" to kind,
        "faceIndex" to faceIndex,
        "vertexIndex" to vertexIndex,
        "edgeIndex" to edgeIndex,
        "position" to position,
        "frontFacing" to frontFacing,
    )
}

/**
 * Where the camera stands, in the terms [MeshView] and [VisionGeometryPreview.Camera] already use.
 *
 * Deliberately the *same* four numbers with the *same* meanings, because the two renderers have to
 * frame a mesh identically for a docs figure to survive the switch. [distance] is in multiples of the
 * bounding-sphere radius, so one spec frames a 2cm mesh and a 2m one.
 *
 * The viewer reimplements the yaw/pitch/distance → eye-position conversion in JavaScript, and that is
 * a second implementation of something with a sign convention in it. It is marked
 * `// [viewer-region camera-preset]` and held against this side by `ThreeCameraPresetParityTest`.
 */
@Serializable
data class ThreeCameraSpec(
    /** Orbit around `+y`, in degrees. `0` looks straight down `-z`. */
    val yawDegrees: Double = 28.0,
    /** Lift above the horizon, in degrees. Positive looks down on the geometry. */
    val pitchDegrees: Double = 18.0,
    /**
     * Eye distance in multiples of the bounding-sphere radius, as the rasterizer ([MeshView]) uses it. The
     * Three.js viewer fits the shape to the view instead and ignores it; its framing is [padding].
     */
    val distance: Double = 2.7,
    /** Vertical field of view in degrees. */
    val fieldOfViewDegrees: Double = 40.0,
    /**
     * The Three.js viewer's fit margin around the shape's on-screen outline: `1.0` touches the edges, the
     * default `1.06` leaves a little air. The rasterizer ignores it.
     */
    val padding: Double = 1.06,
    /**
     * Which world axis is up. [ThreeUpAxis.Z] keeps the viewer's grid, which lies in the x–y plane, as the
     * floor: yaw turns about `+z`, pitch lifts toward `+z`, and yaw 0 looks along `+y`. The yaw/pitch
     * mapping is the y-up one, rotated rigidly, so [ThreeCameraPresetParityTest] still covers it.
     */
    val upAxis: ThreeUpAxis = ThreeUpAxis.Y,
    /**
     * Track the geometry: on every update the camera moves by however far the centre of the bounds
     * moved, keeping whatever orbit and zoom the viewer has, and the grid shifts by whole cells so the
     * floor reads as fixed while the subject crosses it.
     */
    val follow: Boolean = false,
)

/**
 * A view that opens with **Link camera** already on. Written by [ThreeSceneBuilder.linkCamera].
 *
 * The link itself is the viewer's: views in one document share orbit, pan and zoom over a browser
 * channel, and this only says which channel to join on load.
 */
@Serializable
data class ThreeCameraLinkSpec(
    /** Views link only with views of the same group in the same document. */
    val group: String = "default",
)

/**
 * A slow automatic orbit about the view's up axis. Written by [ThreeSceneBuilder.turntable].
 *
 * Not [threeTurntable], which lays fixed angles out side by side; this one moves.
 */
@Serializable
data class ThreeTurntableSpec(
    /** Positive turns the model counter-clockwise seen from above. */
    val degreesPerSecond: Double = 6.0,
    /** How long the turn stays paused after the last interaction. */
    val resumeAfterSeconds: Double = 2.0,
)

/**
 * A surface's `MeshStandardMaterial` settings, sent with a [ThreeDrawStyle].
 *
 * Colours are **linear-light** RGB in `0..1`, the space three.js shades in; the vertex colours of the
 * mesh multiply [color], as `vertexColors: true` always does.
 */
@Serializable
data class ThreeSurfaceMaterial(
    val color: FloatArray = floatArrayOf(1f, 1f, 1f),
    val roughness: Float = 0.68f,
    val metalness: Float = 0.04f,
    val emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    val emissiveIntensity: Float = 0f,
) {
    override fun equals(other: Any?): Boolean = other is ThreeSurfaceMaterial &&
        color.contentEquals(other.color) && roughness == other.roughness && metalness == other.metalness &&
        emissive.contentEquals(other.emissive) && emissiveIntensity == other.emissiveIntensity

    override fun hashCode(): Int =
        listOf(color.contentHashCode(), roughness, metalness, emissive.contentHashCode(), emissiveIntensity).hashCode()
}

/**
 * The studio look: tone mapping, soft shadows, a dark ground and a faint grid, for a scene that is a
 * sculpture on a plinth rather than a technical figure. See [ThreeSceneBuilder.studio].
 *
 * The lighting is a fixed rig — a sky/ground hemisphere, a shadow-casting key from above and in front,
 * a cool rim from behind — placed relative to the scene's bounds, so a model a hundred units across is
 * lit like one a metre across.
 */
@Serializable
data class ThreeStudioSpec(
    /** Draw a ground plane that receives the shadows. */
    val ground: Boolean = true,
    /** Draw a faint grid on the ground. */
    val grid: Boolean = true,
    /** Height of the ground, or `null` for a little below the scene's lowest point. */
    val groundY: Double? = null,
    /** The ground's colour as a CSS colour. */
    val groundColor: String = "#08090a",
    /** Cast soft shadows from the key light onto the ground and the model itself. */
    val shadows: Boolean = true,
    /** ACES filmic tone mapping exposure. */
    val exposure: Double = 1.0,
    /** Screen-space ambient occlusion: contact shadows in creases and under overhangs. Single views only. */
    val ambientOcclusion: Boolean = true,
)

/**
 * A piece of text pinned to a point in the scene: it follows the point as the view orbits, and is drawn
 * over the canvas as ordinary page text, so it stays sharp at any size. See [ThreeSceneBuilder.label].
 */
@Serializable
data class ThreeLabel(
    /** The anchor, in scene coordinates: three numbers. */
    val position: List<Double>,
    /** What the label says; plain text. */
    val text: String = "",
    /**
     * `"chip"` a boxed label (a point's name), `"tag"` small dim text (a length on an edge), or
     * `"diamond"` a small filled marker with no text, coloured by [color].
     */
    val style: String = "tag",
    /** Text colour, or for a `"diamond"` its fill, as a CSS colour; `null` keeps the style's own. */
    val color: String? = null,
    /** Background as a CSS colour; `null` keeps the style's own. */
    val background: String? = null,
    /** Border colour as a CSS colour; `null` keeps the style's own. */
    val border: String? = null,
    /** A tooltip; plain text. */
    val title: String? = null,
)

/** An HTML block fixed to one corner of the view. See [ThreeSceneBuilder.overlay]. */
@Serializable
data class ThreeOverlay(
    /** The block's HTML. It is inserted as is, so it is trusted as `display(...)` output is. */
    val html: String,
    /** `"top-right"`, `"top-left"`, `"bottom-right"` or `"bottom-left"`. */
    val corner: String = "top-right",
)

/**
 * One moment of a [ThreeShotSpec]: where in the loop it is, and how the view looks there. Between two keys
 * every value eases (smoothstep) from one to the other.
 */
@Serializable
data class ThreeShotKey(
    /** Where in the loop, from `0` (its start) to `1` (its end, which should look like the start). */
    val at: Double,
    /** Camera distance as a multiple of the distance the view would otherwise stand at. */
    val distance: Double = 1.0,
    /** How far the model is exploded, as a multiple of each mesh's own explode offsets. */
    val explode: Double = 0.0,
    /** How far surfaces with a solid opacity are x-rayed: `0` draws them solid, `1` at their own opacity. */
    val xray: Double = 1.0,
)

/**
 * A looping camera and model move for presentation. Written by [ThreeSceneBuilder.shot].
 *
 * One clock drives all three of [ThreeShotKey]'s values in every panel of the view. It is the wall clock, so
 * separate views of a document stay in step without being linked.
 */
@Serializable
data class ThreeShotSpec(
    /** How long one loop takes. */
    val periodSeconds: Double = 24.0,
    /** The loop, first key at `0` and last at `1`. */
    val keys: List<ThreeShotKey> = MONEY_SHOT,
    /** How long the loop stays held after the last press, wheel, key or drag in the view. */
    val resumeAfterSeconds: Double = 2.0,
) {
    companion object {
        /**
         * Close in and solid; pull back as the surfaces go see-through; explode at the widest; hold; then
         * gather back in to where it started.
         */
        val MONEY_SHOT: List<ThreeShotKey> = listOf(
            ThreeShotKey(0.0, distance = 0.6, explode = 0.0, xray = 0.0),
            ThreeShotKey(0.15, distance = 0.7, explode = 0.0, xray = 0.0),
            ThreeShotKey(0.4, distance = 1.0, explode = 0.0, xray = 1.0),
            ThreeShotKey(0.62, distance = 1.4, explode = 1.0, xray = 1.0),
            ThreeShotKey(0.82, distance = 1.4, explode = 1.0, xray = 1.0),
            ThreeShotKey(1.0, distance = 0.6, explode = 0.0, xray = 0.0),
        )
    }
}

/**
 * One viewport in a multi-panel scene, with its own camera and its own caption.
 *
 * # Why panels rather than iframes
 *
 * A contact sheet — `showMeshTurntable`, or one of `polytope_examples.md`'s galleries — is N views of
 * geometry side by side. The obvious translation is N viewers, and it is wrong: each iframe is its own
 * WebGL context, Chrome caps live contexts at around sixteen and silently evicts the oldest, and a
 * gallery of nine panels next to another gallery of nine is already over budget. The evicted context
 * comes back as a blank panel with nothing in the console.
 *
 * So a panel is a *viewport*, not a document: one context, `setScissorTest` and a per-panel
 * `setViewport` in the render loop. It also happens to be much faster.
 */
@Serializable
data class ThreePanel(
    val camera: ThreeCameraSpec? = null,
    val title: String? = null,
    val caption: String? = null,
    /** The computed fact line — counts, extent, topology. Rendered in monospace under the panel. */
    val facts: String? = null,
    /**
     * What this panel frames, when it differs from the scene's overall bounds.
     *
     * Computed here rather than in the viewer because the two contact-sheet shapes want opposite
     * things and only this side knows which it is: a **grid** shows a different mesh per panel and
     * each must frame its own, while a **turntable** shows one mesh from several angles and every
     * panel must frame the *same* bounds or the shape appears to breathe as it rotates.
     */
    val bounds: ThreeBounds? = null,
    /**
     * Frame [bounds] itself rather than the panel's geometry. The viewer normally fits the camera to
     * the mesh's own points, which is what a contact sheet wants; a size comparison wants every
     * panel framed at one scale, so `threeGrid(sharedScale = true)` sets this.
     */
    val fitBounds: Boolean = false,
)

/**
 * The neutral blue-grey a mesh with no per-vertex colours is drawn in.
 *
 * Read from the rasterizer's own constant rather than copied, so the two renderers cannot drift into
 * drawing an uncoloured mesh two different greys.
 */
internal val THREE_DEFAULT_SURFACE: FloatArray get() = DEFAULT_SURFACE

/**
 * This mesh as something the viewer can draw.
 *
 * Mirrors [afield.geometry.mesh.toCapturedMesh]'s preparation exactly: the GPU and the CPU renderer
 * must be shading the *same* mesh, so that a difference between their outputs is a difference in
 * shading model and never a difference in input.
 *
 *  * **Normals.** A mesh with none is run through [shadedNormals] at the same 35° crease angle the
 *    rasterizer uses — so a box stays a box and a cylinder comes out smooth-sided. Doing it here
 *    rather than leaning on three.js's `computeVertexNormals()` is what keeps the two in agreement;
 *    `computeVertexNormals` is an unconditional smooth average and would round every hard edge off a
 *    cube.
 *  * **Colours.** [TriMesh.colors] when present, else [THREE_DEFAULT_SURFACE].
 *  * **Alpha.** A flat `1`, so the viewer's per-vertex alpha attribute is present and the surface
 *    material's shader patch has something to read.
 *
 * @param creaseAngleDegrees the angle above which an edge is kept hard. The rasterizer's default.
 * @param id [ThreeDrawable.id] — a stable name echoed back on every hit.
 * @param pickable [ThreeDrawable.pickable]; implied by a non-empty [callbackId] or a [hoverColor].
 * @param explode per-vertex offsets (3 floats a vertex) a [ThreeSceneBuilder.shot]'s explode moves each
 * vertex by at full explode; carried through the crease shading onto the vertices it emits.
 */
fun TriMesh.toThreeDrawable(
    style: ThreeDrawStyle = ThreeDrawStyle.surface(),
    label: String? = null,
    panel: Int = 0,
    group: Int = -1,
    creaseAngleDegrees: Double = 35.0,
    id: String = "",
    callbackId: String = "",
    hoverColor: FloatArray? = null,
    pickable: Boolean = false,
    explode: FloatArray? = null,
): ThreeDrawable {
    val ready = if (hasNormals) this else shadedNormals(creaseAngleDegrees)
    val n = ready.vertexCount
    // Explode offsets belong to vertices, and the shading above may split a vertex at a crease. It keeps the
    // triangles in order, so every emitted corner takes the offset of the corner it came from.
    val explodeOffsets = explode?.let { source ->
        require(source.size == vertexCount * 3) { "explode needs 3 floats per vertex (${vertexCount * 3}), got ${source.size}" }
        require(source.all { it.isFinite() }) { "explode offsets must be finite" }
        if (ready === this) source.copyOf() else {
            check(ready.triangleCount == triangleCount) { "shading changed the triangles, so explode offsets cannot follow them" }
            FloatArray(n * 3).also { out ->
                for (corner in indices.indices) {
                    val from = indices[corner]; val to = ready.indices[corner]
                    for (c in 0 until 3) out[to * 3 + c] = source[from * 3 + c]
                }
            }
        }
    }

    val normals = FloatArray(n * 3)
    val readyNormals = checkNotNull(ready.normals) {
        "shadedNormals must produce normals; got a mesh with none for $n vertices"
    }
    for (v in 0 until n) {
        val nx = readyNormals[v * 3]
        val ny = readyNormals[v * 3 + 1]
        val nz = readyNormals[v * 3 + 2]
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        // Normalise here rather than trusting the source, exactly as toCapturedMesh does: a
        // nearly-unit normal is the signature of an un-normalised cross product and shades wrong.
        if (l > 1e-20f) {
            normals[v * 3] = nx / l; normals[v * 3 + 1] = ny / l; normals[v * 3 + 2] = nz / l
        } else {
            normals[v * 3] = 0f; normals[v * 3 + 1] = 1f; normals[v * 3 + 2] = 0f
        }
    }

    val colors = FloatArray(n * 3)
    val src = ready.colors
    for (v in 0 until n) {
        colors[v * 3] = src?.get(v * 3) ?: THREE_DEFAULT_SURFACE[0]
        colors[v * 3 + 1] = src?.get(v * 3 + 1) ?: THREE_DEFAULT_SURFACE[1]
        colors[v * 3 + 2] = src?.get(v * 3 + 2) ?: THREE_DEFAULT_SURFACE[2]
    }

    val attributes = mapOf(
        "position" to ThreeAttribute(itemSize = 3, array = ready.positions.copyOf()),
        "normal" to ThreeAttribute(itemSize = 3, array = normals),
        "color" to ThreeAttribute(itemSize = 3, array = colors),
        "alpha" to ThreeAttribute(itemSize = 1, array = FloatArray(n) { 1f }),
    ) + (explodeOffsets?.let { mapOf("explode" to ThreeAttribute(itemSize = 3, array = it)) } ?: emptyMap())
    val maxIndex = ready.indices.maxOrNull() ?: 0
    return ThreeDrawable(
        geometry = ThreeGeometryData(
            attributes = attributes,
            index = ThreeIndex(
                type = if (maxIndex > 65535) "Uint32Array" else "Uint16Array",
                array = ready.indices.copyOf(),
            ),
        ),
        style = style,
        label = label,
        group = group,
        panel = panel,
        callbackId = callbackId,
        hoverColor = hoverColor,
        id = id,
        pickable = pickable || callbackId.isNotEmpty() || hoverColor != null,
    )
}

/**
 * The edges of this mesh worth drawing: open boundaries and creases, not the diagonals that triangulate
 * a flat face. An edge is kept when it bounds one triangle (or more than two), when a triangle beside it
 * is degenerate, or when its two triangles meet at more than [angleDegrees]. Returns vertex pairs.
 *
 * Vertices are welded by position first, so a triangle soup gets the same edges as the indexed mesh.
 * With [explode] they are welded by position *and* offset, so two parts that touch keep their own
 * outlines and come apart cleanly in a [ThreeSceneBuilder.shot]'s explode.
 *
 * The rule is the one [solidParts] draws its panel outlines by, restated for Float positions: its
 * coplanarity test runs at double precision, and here it would keep the diagonal of every flat Float
 * quad, so this one asks for an angle instead.
 */
fun TriMesh.outlineEdges(angleDegrees: Double = 1.0, explode: FloatArray? = null): List<Pair<Int, Int>> {
    require(explode == null || explode.size == vertexCount * 3) { "explode needs 3 floats per vertex (${vertexCount * 3}), got ${explode?.size}" }
    val scale = positions.maxOfOrNull { kotlin.math.abs(it) }?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
    val q = scale * 1e-6
    data class Weld(val x: Long, val y: Long, val z: Long, val ex: Long, val ey: Long, val ez: Long)
    val canonical = HashMap<Weld, Int>()
    val weld = IntArray(vertexCount) { v ->
        fun r(a: FloatArray?, i: Int) = if (a == null) 0L else Math.round(a[v * 3 + i] / q)
        canonical.getOrPut(Weld(r(positions, 0), r(positions, 1), r(positions, 2), r(explode, 0), r(explode, 1), r(explode, 2))) { v }
    }
    val normals = Array(triangleCount) { t ->
        fun p(c: Int, i: Int) = positions[indices[3 * t + c] * 3 + i].toDouble()
        val ux = p(1, 0) - p(0, 0); val uy = p(1, 1) - p(0, 1); val uz = p(1, 2) - p(0, 2)
        val wx = p(2, 0) - p(0, 0); val wy = p(2, 1) - p(0, 1); val wz = p(2, 2) - p(0, 2)
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 1e-30 && l.isFinite()) doubleArrayOf(nx / l, ny / l, nz / l) else null
    }
    val incident = LinkedHashMap<Long, MutableList<Int>>()
    for (t in 0 until triangleCount) for (e in 0..2) {
        val a = weld[indices[3 * t + e]]; val b = weld[indices[3 * t + (e + 1) % 3]]
        if (a == b) continue
        incident.getOrPut(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()) { ArrayList(2) } += t
    }
    val cosLimit = kotlin.math.cos(Math.toRadians(angleDegrees))
    return incident.filter { (_, ts) ->
        if (ts.size != 2) return@filter true
        val na = normals[ts[0]] ?: return@filter true; val nb = normals[ts[1]] ?: return@filter true
        na[0] * nb[0] + na[1] * nb[1] + na[2] * nb[2] < cosLimit
    }.keys.map { (it ushr 32).toInt() to (it and 0xffffffffL).toInt() }
}

/**
 * [outlineEdges] as a `lines` drawable in [color] (`[r, g, b]`), drawn over the mesh's own surface (which
 * the viewer pushes back a touch, so they do not z-fight). With [explode] each line end carries its
 * vertex's offset, so the outline moves with its part.
 */
fun TriMesh.toThreeEdgeDrawable(
    color: FloatArray = floatArrayOf(.08f, .1f, .12f),
    angleDegrees: Double = 1.0,
    panel: Int = 0,
    group: Int = -1,
    id: String = "",
    explode: FloatArray? = null,
): ThreeDrawable {
    require(color.size == 3 && color.all { it.isFinite() && it in 0f..1f }) { "edge colour needs three values in 0..1" }
    val edges = outlineEdges(angleDegrees, explode)
    val ends = edges.flatMap { listOf(it.first, it.second) }
    fun gather(source: FloatArray) = FloatArray(ends.size * 3) { source[ends[it / 3] * 3 + it % 3] }
    val attributes = mapOf(
        "position" to ThreeAttribute(itemSize = 3, array = gather(positions)),
        "color" to ThreeAttribute(itemSize = 3, array = FloatArray(ends.size * 3) { color[it % 3] }),
    ) + (explode?.let { mapOf("explode" to ThreeAttribute(itemSize = 3, array = gather(it))) } ?: emptyMap())
    return ThreeDrawable(ThreeGeometryData(attributes), ThreeDrawStyle.lines, panel = panel, group = group, id = id)
}
