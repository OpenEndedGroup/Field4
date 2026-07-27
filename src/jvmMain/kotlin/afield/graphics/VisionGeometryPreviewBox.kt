package afield.graphics

import afield.Box
import afield.notify
import afield.remotejs.KotlinJsHeadProfiles
import afield.stdlib.color
import afield.stdlib.linalg.Vector4f

/**
 * The in-Field half of [VisionGeometryPreview]: turning "my mesh only exists in the headset" into a
 * picture in the box you wrote it in.
 *
 * A Live Metal box's Kotlin never runs on the Mac — it compiles to JavaScript for the head — so its
 * output panel is normally empty of anything but log lines. [Box.previewVisionGeometry] fills that
 * gap: it takes the box's own source, runs it for real (see [VisionGeometryPreview.capture]) and
 * draws the resulting mesh straight into the box's output. One command, and your headset geometry is
 * on the desktop.
 */

/**
 * Build this box's `geometry { … }` for real and draw it into the box's output panel.
 *
 * Blocking, and typically a second or two — the box's Kotlin is genuinely compiled to JavaScript and
 * genuinely executed. Call it from a box body, or use the **Preview Vision Geometry** command, which
 * runs it off the main thread.
 *
 * @param source the Kotlin to build, defaulting to this box's `code` property.
 * @param frames `onFrame` ticks to pump; the *last* mesh built is the one drawn, so pass a tick or
 *   two to preview a box whose mesh is rebuilt per frame rather than in `onStart`.
 * @return the capture, so a caller can go on to assert on the mesh, or null if the box has no
 *   Kotlin/JS source to build.
 */
fun Box.previewVisionGeometry(
    source: String? = null,
    camera: VisionGeometryPreview.Camera = VisionGeometryPreview.Camera(),
    style: VisionGeometryPreview.Style = VisionGeometryPreview.Style(),
    frames: List<VisionGeometryPreview.FrameTick> = emptyList(),
    channel: String = "output",
): VisionGeometryPreview.Capture? {
    val kotlin = (source ?: getPropertyRaw("code", "")?.toString()).orEmpty()
    if (kotlin.isBlank()) {
        notify("No Kotlin in '${this.name}' to build geometry from", "vision-metal")
        return null
    }
    val selection = KotlinJsHeadProfiles.resolve(kotlin)
    if (selection.explicit && selection.profile !== KotlinJsHeadProfiles.visionMetal) {
        notify(
            "'${this.name}' asks for the '${selection.profile.id}' head, not Live Metal",
            "vision-metal",
        )
        return null
    }

    val capture = VisionGeometryPreview.capture(
        kotlin,
        moduleName = "vision_preview_${id.take(8)}",
        frames = if (frames.isEmpty() && "onFrame" in kotlin && "geometry" !in kotlin.substringBefore("onFrame")) {
            // The mesh is built inside onFrame, so onStart alone would upload nothing. One tick is
            // enough to have something to look at; the caller can ask for a specific time.
            listOf(VisionGeometryPreview.FrameTick(time = 0.0))
        } else {
            frames
        },
    )
    // The uniform wiring is decidable from the same run, and its failures are just as invisible from
    // here as a bad winding: an undeclared `u_NAME` fails Metal's compiler minutes later, on a device.
    VisionMetalProgramCheck.summarise(
        VisionMetalProgramCheck.problems(kotlin, msl, capture.uniforms.map { it.name }),
    )?.let { notify("'${this.name}': $it", "vision-metal") }

    if (capture.meshes.isEmpty()) {
        notify("'${this.name}' never called geometry { … } — the head would draw nothing", "vision-metal")
        return capture
    }
    val mesh = capture.mesh()
    // renderFigureHtml, not render: AWT must not be touched from the Field server's JVM on macOS —
    // initialising AppKit in a process that already owns its main thread for GLFW kills it outright.
    display(channel, VisionGeometryPreview.renderFigureHtml(mesh, camera, style, title = style.title ?: this.name))
    val winding = mesh.windingAgreement()
    if (winding < 0.999) {
        // Worth interrupting for: a mesh that contradicts its own normals draws as a hollow shell on
        // a head that culls back faces, and looks convincing from the outside while doing it.
        notify(
            "'${this.name}': only ${"%.0f".format(winding * 100)}% of triangles are wound to match " +
                "their normals — this mesh will render inside-out",
            "vision-metal",
        )
    }
    return capture
}

/**
 * The mesh's unique edges as [FLine]s, in metres, for Field's own 3-D viewer.
 *
 * The rasterized preview is a picture; this is the mesh as geometry you can measure, transform and
 * draw alongside everything else Field knows how to draw. Vertex colour is carried onto each line so
 * a coloured mesh stays legible as a wireframe.
 */
fun VisionGeometryPreview.CapturedMesh.toFLines(): List<FLine> {
    val seen = HashSet<Long>(indices.size)
    val lines = mutableListOf<FLine>()
    for (t in 0 until triangleCount) {
        val corners = intArrayOf(indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2])
        if (corners.any { it < 0 || it >= vertexCount }) continue
        for (e in 0..2) {
            val a = corners[e]
            val b = corners[(e + 1) % 3]
            if (a == b) continue
            val key = if (a < b) a.toLong() * 1_000_003L + b else b.toLong() * 1_000_003L + a
            if (!seen.add(key)) continue
            val line = FLine()
                .moveTo(positionX(a), positionY(a), positionZ(a))
                .lineTo(positionX(b), positionY(b), positionZ(b))
            line.color = Vector4f(
                (red(a) + red(b)) / 2f,
                (green(a) + green(b)) / 2f,
                (blue(a) + blue(b)) / 2f,
                1f,
            )
            lines += line
        }
    }
    return lines
}
