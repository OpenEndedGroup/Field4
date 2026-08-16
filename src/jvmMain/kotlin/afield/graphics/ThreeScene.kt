package afield.graphics

import afield.stdlib.DEFAULT_SURFACE
import afield.stdlib.TriMesh
import afield.stdlib.prettySummary
import afield.stdlib.shadedNormals
import kotlinx.serialization.Serializable

/**
 * The intermediate representation the Three.js viewer draws.
 *
 * # Why this exists
 *
 * Field had two 3D renderers that shared no vocabulary. [FLine]s went to the Three.js viewer —
 * orbitable, pickable, editable, with the measure/section/explode inspector. [TriMesh]es went to the
 * CPU z-buffer rasterizer in [VisionGeometryPreview] and came back as a static PNG at a fixed camera.
 * A mesh could not be orbited, a mesh and its FLine annotations could not share a scene, and every
 * feature the viewer grew stopped at the FLine boundary.
 *
 * A [ThreeDrawable] is the common currency: some geometry, plus how to draw it. FLines lower to one,
 * meshes lower to one, and anything with a `toTriMesh()` reaches the viewer for free. The viewer
 * gained no knowledge of either source type — it draws drawables.
 *
 * # What was already right
 *
 * Very little of this is new machinery. [ThreeGeometryData] was *already* a generic three.js
 * `BufferGeometry` — named attributes and an index, with nothing FLine-shaped about it — and
 * [TriMesh] is already `positions / indices / normals? / colors?`. The lowering below is mostly a
 * transcription. What was missing was the layer above: per-drawable **material state**, which the
 * payload had no way to express because every FLine fill wanted the same material.
 *
 * # Additive, deliberately
 *
 * [ThreeGeometryPayload]'s `lines` / `fills` / `points` are untouched, and a payload with an empty
 * [ThreeGeometryPayload.drawables] serialises byte for byte as it did before any of this existed.
 * That is what lets the editable path, the inspector and the twelve tests that pin the current
 * payload carry on without a second thought — and `ThreeScenePayloadCompatibilityTest` holds it there.
 */
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
    /** Shade per triangle rather than per vertex — a faceted look, and honest about a coarse mesh. */
    val flatShading: Boolean = false,
    /** Multiplied into the per-vertex alpha. */
    val opacity: Float = 1f,
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
        ) = ThreeDrawStyle(
            kind = "surface",
            doubleSided = !cullBackFaces,
            depthWrite = true,
            wireframe = wireframe,
            flatShading = flatShading,
            opacity = opacity,
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
) {
    override fun toString(): String = prettySummary(
        "ThreeDrawable",
        "label" to label,
        "style" to style,
        "vertices" to ((geometry.attributes["position"]?.array?.size ?: 0) / 3),
        "indices" to (geometry.index?.array?.size ?: 0),
        "group" to group,
        "panel" to panel,
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
    /** Eye distance in multiples of the bounding-sphere radius. */
    val distance: Double = 2.7,
    /** Vertical field of view in degrees. */
    val fieldOfViewDegrees: Double = 40.0,
)

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
 * Mirrors [afield.stdlib.toCapturedMesh]'s preparation exactly, which is the point: the GPU and the
 * CPU renderer must be shading the *same* mesh, so that a difference between their outputs is a
 * difference in shading model and never a difference in input.
 *
 *  * **Normals.** A mesh with none is run through [shadedNormals] at the same 35° crease angle the
 *    rasterizer uses — so a box stays a box and a cylinder comes out smooth-sided. Doing it here
 *    rather than leaning on three.js's `computeVertexNormals()` is what keeps the two honest;
 *    `computeVertexNormals` is an unconditional smooth average and would round every hard edge off a
 *    cube.
 *  * **Colours.** [TriMesh.colors] when present, else [THREE_DEFAULT_SURFACE].
 *  * **Alpha.** A flat `1`, so the viewer's per-vertex alpha attribute is present and the surface
 *    material's shader patch has something to read.
 *
 * @param creaseAngleDegrees the angle above which an edge is kept hard. The rasterizer's default.
 */
fun TriMesh.toThreeDrawable(
    style: ThreeDrawStyle = ThreeDrawStyle.surface(),
    label: String? = null,
    panel: Int = 0,
    group: Int = -1,
    creaseAngleDegrees: Double = 35.0,
): ThreeDrawable {
    val ready = if (hasNormals) this else shadedNormals(creaseAngleDegrees)
    val n = ready.vertexCount

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
    )
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
    )
}
