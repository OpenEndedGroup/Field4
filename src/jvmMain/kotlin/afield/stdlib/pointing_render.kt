@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Box
import afield.graphics.VisionGeometryPreview
import java.util.Base64

/**
 * Seeing what the pointer found: the picked part lit, a crosshair where the pointer was aimed, and a
 * caption that names what it hit.
 *
 * # Why this is in the layer and not left to the caller
 *
 * Because "it compiles" and "a hit record came back non-null" are both worthless as evidence for
 * something whose whole job is to tell a person what they are touching. The failure mode is not a
 * crash; it is *the wrong part lit*, or a pointer that is confidently off by the height of a caption
 * band. Neither shows up in a unit test's return value, and both are obvious in a picture.
 *
 * So the layer ships its own way of drawing itself, `SpatialPointerPixelTest` decodes the PNG back
 * out and reads samples from it, and the demo transcript uses the same call a person would.
 *
 * # What the picture is
 *
 * [TriMesh.renderPng]'s rasterizer, over a mesh whose per-vertex colours have been repainted by part
 * ([TriMesh.coloredByPart]), with a crosshair composited on afterwards at the pointer's pixel. All of
 * it CPU: no GPU, no window, no AWT — so it runs inside a live Field box, inside a test, and inside a
 * headless docs capture, identically.
 *
 * ```kotlin
 * val scene = pickScene { add("model", mesh, parts = builder.groups()) }
 * val camera = MeshView().rayCamera(scene.mergedMesh())
 * val at = Vec2(360f, 270f)
 * display(scene.pointerFigureHtml(camera, at))
 * ```
 */

// ==============================================================================================
// Painting a mesh by part
// ==============================================================================================

/** The neutral blue-grey `withDefectColors` uses for "nothing to see here". Kept the same on purpose. */
private val PART_BASE = Vec3(0.42f, 0.48f, 0.58f)

/** The highlight. Warm, and far enough from [PART_BASE] in every channel to survive a JPEG. */
private val PART_HIGHLIGHT = Vec3(1.0f, 0.62f, 0.16f)

/**
 * This mesh with each triangle painted by the part it belongs to.
 *
 * **Unwelds.** A part boundary runs along triangle edges, and per-vertex colours cannot express that
 * — a vertex shared by two parts would have to be two colours, and interpolating between them smears
 * the boundary across both triangles. So every triangle gets its own three vertices. That costs
 * memory and throws away smooth shading across the seam (each new vertex keeps its source vertex's
 * normal, so shading *within* a part is unchanged), and it is the only way to get a crisp edge out of
 * a per-vertex-colour rasterizer.
 *
 * @param palette the colour for a part id. Called once per triangle; make it cheap.
 *   [PickParts.UNASSIGNED] is passed through as-is, so handle it or let it fall to a default.
 */
fun TriMesh.coloredByPart(parts: PickParts, palette: (part: Int) -> Vec3): TriMesh {
    require(parts.triangleCount == triangleCount) {
        "the part assignment covers ${parts.triangleCount} triangle(s) but this mesh has $triangleCount"
    }
    val n = triangleCount * 3
    val positions = FloatArray(n * 3)
    val indices = IntArray(n) { it }
    val colors = FloatArray(n * 3)
    val outNormals = if (hasNormals) FloatArray(n * 3) else null
    val outUvs = if (hasUvs) FloatArray(n * 2) else null
    for (t in 0 until triangleCount) {
        val c = palette(parts.partOf(t))
        for (k in 0..2) {
            val source = indices()[t * 3 + k]
            val dest = t * 3 + k
            positions[dest * 3] = x(source); positions[dest * 3 + 1] = y(source); positions[dest * 3 + 2] = z(source)
            colors[dest * 3] = c.x; colors[dest * 3 + 1] = c.y; colors[dest * 3 + 2] = c.z
            outNormals?.let { normals!!.copyInto(it, dest * 3, source * 3, source * 3 + 3) }
            outUvs?.let { uvs!!.copyInto(it, dest * 2, source * 2, source * 2 + 2) }
        }
    }
    return TriMesh(positions, indices, outNormals, outUvs, colors)
}

/** This mesh's own index array, without the accessor gymnastics [coloredByPart] would otherwise need. */
private fun TriMesh.indices(): IntArray = indices

/**
 * This mesh with one part lit and everything else neutral — the picture of a pick.
 *
 * @param part the part id to light. [PickParts.UNASSIGNED] (or any id not in the assignment) lights
 *   nothing, which is the honest rendering of "the pointer is over nothing".
 */
fun TriMesh.withPartHighlight(
    parts: PickParts,
    part: Int,
    highlight: Vec3 = PART_HIGHLIGHT,
    base: Vec3 = PART_BASE,
): TriMesh = coloredByPart(parts) { if (it == part && it != PickParts.UNASSIGNED) highlight else base }

/**
 * This mesh with every part its own colour, walked around a hue circle.
 *
 * For seeing whether a part *assignment* is what you meant, before anybody points at anything —
 * a `byComponent` that found one part where you expected twelve is obvious here and invisible in a
 * hit record.
 */
fun TriMesh.coloredByPart(parts: PickParts): TriMesh = coloredByPart(parts) { part ->
    if (part == PickParts.UNASSIGNED) Vec3(0.30f, 0.32f, 0.36f) else {
        // Golden-ratio hue stepping, so adjacent part ids are never adjacent colours.
        val hue = (part * 0.61803399f) % 1f
        hsvToRgb(hue, 0.55f, 0.92f)
    }
}

private fun hsvToRgb(h: Float, s: Float, v: Float): Vec3 {
    val i = (h * 6f).toInt()
    val f = h * 6f - i
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    return when (i % 6) {
        0 -> Vec3(v, t, p)
        1 -> Vec3(q, v, p)
        2 -> Vec3(p, v, t)
        3 -> Vec3(p, q, v)
        4 -> Vec3(t, p, v)
        else -> Vec3(v, p, q)
    }
}

// ==============================================================================================
// The picture
// ==============================================================================================

/**
 * A finished picture of a pick: ARGB pixels, the size, and what the pointer found.
 *
 * Carries the hit rather than only the pixels so that a caption can be written from the same call
 * that produced the image, and the two cannot disagree.
 */
class PointerRaster(
    /** ARGB, row-major, [width] x [height]. */
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    /** What the pointer found, or `null`. */
    val hit: PickHit?,
    /** Where the pointer was aimed, in pixels. */
    val pointerX: Double,
    val pointerY: Double,
) {
    /** [pixels] as PNG bytes, via [afield.util.PngEncoder]. No AWT. */
    fun toPng(): ByteArray = afield.util.PngEncoder.encode(pixels, width, height)

    /** One line naming what was hit, for a caption or a log. */
    fun caption(): String =
        "pointer at (%.0f, %.0f) → %s".format(pointerX, pointerY, hit?.describe() ?: "nothing")
}

/**
 * Render this scene from [camera] with whatever is under the pointer lit up and a crosshair where the
 * pointer is aimed.
 *
 * The camera is a [MeshViewRayCamera] and not a bare [MeshView] on purpose: the *same* camera object
 * has to produce the ray and frame the picture, or the highlight is evidence of nothing. Build it
 * with [MeshView.rayCamera] over [PickScene.mergedMesh].
 *
 * @param at where the pointer is, in pixels from the frame's top-left.
 * @param crosshairRadius in pixels; `0` draws none.
 */
fun PickScene.renderPointer(
    camera: MeshViewRayCamera,
    at: Vec2,
    view: MeshView = MeshView(width = camera.pixelWidth, height = camera.pixelHeight),
    crosshairRadius: Int = 11,
    maxDistance: Double = Double.MAX_VALUE,
): PointerRaster {
    val ray = camera.rayThrough(at.x.toDouble(), at.y.toDouble())
    val hit = pick(ray, maxDistance)
    val merged = mergedMesh()
    val mergedParts = mergedParts()
    // The merged mesh's triangle indices are its own, so translate the hit into a merged part id by
    // name — the one place in this layer where a name is load-bearing rather than decorative.
    val litName = hit?.let { "${it.objectName} / ${it.partName}" }
    val litPart = litName?.let { mergedParts.names.indexOf(it) } ?: PickParts.UNASSIGNED
    val painted = merged.withPartHighlight(mergedParts, litPart)
    val raster = painted.renderPixels(view.copy(width = camera.pixelWidth, height = camera.pixelHeight))
    val pixels = raster.pixels.copyOf()
    if (crosshairRadius > 0) {
        drawCrosshair(pixels, raster.width, raster.height, at.x.toDouble(), at.y.toDouble(), crosshairRadius)
    }
    return PointerRaster(pixels, raster.width, raster.height, hit, at.x.toDouble(), at.y.toDouble())
}

/**
 * A two-tone crosshair: a white cross with a dark outline, so it is visible against both the lit part
 * and the background. A gap at the centre leaves the pixel that was actually picked unpainted —
 * otherwise the crosshair covers the evidence.
 */
private fun drawCrosshair(pixels: IntArray, width: Int, height: Int, cx: Double, cy: Double, radius: Int) {
    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val inner = 3
    fun put(x: Int, y: Int, argb: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = argb
    }
    val dark = 0xFF101014.toInt()
    val light = 0xFFFFFFFF.toInt()
    for (d in inner..radius) {
        for (s in listOf(-d, d)) {
            // Outline first, then the bright core over it.
            put(x0 + s, y0 - 1, dark); put(x0 + s, y0 + 1, dark); put(x0 + s, y0, light)
            put(x0 - 1, y0 + s, dark); put(x0 + 1, y0 + s, dark); put(x0, y0 + s, light)
        }
    }
}

/**
 * The pick as a `<figure>` — the picture, and a caption naming the part — for `display(…)` in a box.
 *
 * AWT-free, so it is safe from inside a live Field box.
 */
fun PickScene.pointerFigureHtml(
    camera: MeshViewRayCamera,
    at: Vec2,
    view: MeshView = MeshView(width = camera.pixelWidth, height = camera.pixelHeight),
    title: String? = null,
): String {
    val raster = renderPointer(camera, at, view)
    val b64 = Base64.getEncoder().encodeToString(raster.toPng())
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return buildString {
        append("<figure style='margin:0'>")
        title?.let { append("<div style='color:#e6ecf4;font-weight:600;padding-bottom:4px'>${escape(it)}</div>") }
        append("<img src='data:image/png;base64,$b64' style='max-width:900px;height:auto;border-radius:6px;display:block'/>")
        append("<figcaption style='color:#93a4ba;font-size:12px;padding-top:6px;line-height:1.5;font-family:monospace'>")
        append(escape(raster.caption()))
        append("</figcaption></figure>")
    }
}

/**
 * Push [pointerFigureHtml] to a box output — the one-liner a live document uses.
 *
 * `display` replaces a channel rather than appending to it, so calling this every frame animates the
 * pick in place, which is what you want.
 */
fun Box.showPointer(
    scene: PickScene,
    camera: MeshViewRayCamera,
    at: Vec2,
    view: MeshView = MeshView(width = camera.pixelWidth, height = camera.pixelHeight),
    title: String? = null,
): PickHit? {
    val raster = scene.renderPointer(camera, at, view)
    val b64 = Base64.getEncoder().encodeToString(raster.toPng())
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    display(
        buildString {
            append("<figure style='margin:0'>")
            title?.let { append("<div style='color:#e6ecf4;font-weight:600;padding-bottom:4px'>${escape(it)}</div>") }
            append("<img src='data:image/png;base64,$b64' style='max-width:900px;height:auto;border-radius:6px;display:block'/>")
            append("<figcaption style='color:#93a4ba;font-size:12px;padding-top:6px;font-family:monospace'>")
            append(escape(raster.caption()))
            append("</figcaption></figure>")
        }
    )
    return raster.hit
}

/** The rasterizer's raw output for this scene, z-buffer included — what the pixel differential reads. */
internal fun PickScene.rasterize(camera: MeshViewRayCamera, view: MeshView): VisionGeometryPreview.Raster =
    mergedMesh().renderPixels(view.copy(width = camera.pixelWidth, height = camera.pixelHeight))
