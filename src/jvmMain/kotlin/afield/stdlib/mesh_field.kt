@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Box
import afield.graphics.FLine
import afield.graphics.VISION_METAL_VERTEX_FLOATS
import afield.graphics.VisionGeometryPreview
import afield.graphics.polylineFrom
import afield.graphics.samplePoints
import java.util.Base64
import kotlin.math.max

/**
 * What makes the mesh family *Field's* rather than a generic geometry library: the bridges out of
 * [TriMesh] and into the things Field already draws, renders and displays.
 *
 * Four bridges:
 *
 *  1. **Mesh → [FLine].** [TriMesh.toFLines] gives you the wireframe, the silhouette-free edge set,
 *     the open boundary loops or the normals as drawable lines, so a marched iso-surface goes
 *     straight into the same drawing / animation / export machinery as anything hand-drawn — and is
 *     visible in a Field document *today*, with no renderer set up.
 *  2. **Mesh → pixels.** [TriMesh.renderPng] rasterises the mesh — perspective, z-buffer,
 *     perspective-correct attributes, two-light lambert, depth-tested wireframe — by packing it for
 *     [VisionGeometryPreview]'s renderer, which already had all of that. [Box.showMesh] pushes the
 *     result to a box output and [TriMesh.toHTML] uses it for the inline value thumbnail, so
 *     evaluating a mesh in a box shows the shape.
 *  3. **3D Voronoi → mesh.** [VoronoiCell3.toTriMesh] and [VoronoiLattice.toTriMesh] triangulate the
 *     polygonal cells `voronoi.kt` already computes by half-space clipping. Field could compute a 3D
 *     Voronoi diagram and not turn it into geometry; now it can.
 *  4. **Field lines → mesh.** [tubeMesh] on an [FLine], so a drawn stroke becomes a solid.
 *
 * ```kotlin
 * val cells = voronoi3d(List(24) { vec(rand(-1, 1), rand(-1, 1), rand(-1, 1)) })
 * val solid = cells.toTriMesh(shrink = 0.9)   // every cell, shrunk toward its own site
 * showMesh(solid, caption = "${solid.triangleCount} triangles from 24 sites")
 * ```
 */

// ==============================================================================================
// Mesh -> FLine
// ==============================================================================================

/** Which lines [TriMesh.toFLines] should produce. */
enum class MeshLines {
    /** Every unique edge, as its own two-point line. The honest wireframe. */
    EDGES,

    /**
     * Only edges where the two incident faces disagree by more than the crease angle, plus every
     * boundary edge — the "feature lines" a technical drawing would show. Far fewer lines than
     * [EDGES] on a smooth mesh, and much more readable.
     */
    CREASES,

    /** Only the open boundary, as closed rings — one line per hole. */
    BOUNDARY,

    /** One short line per vertex, along its normal. A normal-field debugger. */
    NORMALS,

    /** One closed triangle per face. Draws each edge twice, but keeps faces distinguishable. */
    FACES,
}

/**
 * This mesh as drawable [FLine]s — the fastest way to see a mesh in a Field document without
 * involving a renderer.
 *
 * @param mode which lines to produce; see [MeshLines].
 * @param creaseAngleDegrees the threshold for [MeshLines.CREASES].
 * @param normalLength the line length for [MeshLines.NORMALS], as a fraction of the mesh's bounding
 *   diagonal.
 */
fun TriMesh.toFLines(
    mode: MeshLines = MeshLines.EDGES,
    creaseAngleDegrees: Double = 25.0,
    normalLength: Double = 0.03,
): List<FLine> {
    if (triangleCount == 0) return emptyList()
    return when (mode) {
        MeshLines.FACES -> (0 until triangleCount).map { t ->
            polylineFrom(listOf(position(ia(t)), position(ib(t)), position(ic(t))), closed = true)
        }
        MeshLines.EDGES -> topology().edges.map { (a, b) -> polylineFrom(listOf(position(a), position(b))) }
        MeshLines.CREASES -> {
            val topo = topology()
            val cosLimit = kotlin.math.cos(creaseAngleDegrees.coerceIn(0.0, 180.0) * Math.PI / 180.0).toFloat()
            topo.edges.indices.mapNotNull { e ->
                val faces = topo.edgeTriangles[e]
                val keep = when (faces.size) {
                    1 -> true // a boundary edge is always a feature
                    2 -> faceNormal(faces[0]).dot(faceNormal(faces[1])) < cosLimit
                    else -> true // non-manifold: definitely worth seeing
                }
                if (!keep) null else {
                    val (a, b) = topo.edges[e]
                    polylineFrom(listOf(position(a), position(b)))
                }
            }
        }
        MeshLines.BOUNDARY -> topology().boundaryLoops.map { ring ->
            polylineFrom(ring.map { position(it) }, closed = true)
        }
        MeshLines.NORMALS -> {
            val src = if (hasNormals) this else withVertexNormals()
            val b = boundsArray()
            val diagonal = kotlin.math.sqrt(
                (b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])
            )
            val len = (diagonal * normalLength).toFloat()
            (0 until vertexCount).mapNotNull { v ->
                val n = src.normal(v) ?: return@mapNotNull null
                val p = position(v)
                polylineFrom(listOf(p, Vec3(p).add(n.x * len, n.y * len, n.z * len)))
            }
        }
    }
}

/** A tube swept along an [FLine], sampled at [samples] points — see [tubeMesh]. */
fun tubeMesh(
    line: FLine,
    radius: Number = 0.05,
    sides: Int = 12,
    samples: Int = 64,
    closed: Boolean = false,
    caps: Boolean = true,
): TriMesh {
    val points = line.samplePoints(samples)
    require(points.size >= 2) { "tubeMesh needs an FLine that samples to >= 2 points, got ${points.size}" }
    return tubeMesh(points, radius, sides, closed, caps)
}

// ==============================================================================================
// 3D Voronoi -> mesh
// ==============================================================================================

/**
 * One 3D Voronoi cell's polygonal faces, triangulated into a [TriMesh].
 *
 * `voronoi.kt` clips half-spaces into [VoronoiFace] polygons already wound counter-clockwise about
 * their plane normal, and the plane normals point out of the cell, so the fan triangulation below
 * comes out consistently outward with no repair pass — which is worth checking, and the tests do.
 *
 * @param shrink scale each cell toward its own site. `1.0` leaves the cells touching (so a whole
 *   lattice is one solid); `0.9` opens the seams, which is what makes an exploded Voronoi readable.
 * @param fanFromCentroid triangulate each face from its centroid rather than from its first corner.
 *   Better-shaped triangles at the cost of one extra vertex per face; on by default because Voronoi
 *   faces are often long thin polygons whose corner fan is all slivers.
 */
fun VoronoiCell3.toTriMesh(shrink: Double = 1.0, fanFromCentroid: Boolean = true): TriMesh {
    val s = shrink.toFloat()
    fun place(p: Vec3): Vec3 =
        if (s == 1f) Vec3(p) else Vec3(site.x + (p.x - site.x) * s, site.y + (p.y - site.y) * s, site.z + (p.z - site.z) * s)
    return triMesh {
        for (face in faces) {
            val ring = face.polygon
            if (ring.size < 3) continue
            val corners = ring.map { place(it).let { q -> vertex(q.x, q.y, q.z) } }
            if (fanFromCentroid) {
                var cx = 0f; var cy = 0f; var cz = 0f
                for (p in ring) { val q = place(p); cx += q.x; cy += q.y; cz += q.z }
                val inv = 1f / ring.size
                val hub = vertex(cx * inv, cy * inv, cz * inv)
                for (i in corners.indices) triangle(hub, corners[i], corners[(i + 1) % corners.size])
            } else {
                polygon(corners)
            }
        }
    }.removeDegenerateTriangles()
}

/**
 * Every bounded cell of a 3D Voronoi lattice as one [TriMesh].
 *
 * Unbounded cells (the ones on the convex hull of the sites, which extend to infinity and are only
 * closed by the clip box) are included by default because the clip box does close them; pass
 * `boundedOnly = true` for just the interior cells, which is what you want if you are about to
 * measure volumes.
 *
 * @param shrink see [VoronoiCell3.toTriMesh]. `0.92` is a good exploded look.
 */
fun VoronoiLattice.toTriMesh(shrink: Double = 0.92, boundedOnly: Boolean = false): TriMesh {
    var out = TriMesh.empty
    val builder = TriMeshBuilder()
    for (cell in cells) {
        if (boundedOnly && !cell.bounded) continue
        builder.add(cell.toTriMesh(shrink))
    }
    out = builder.build()
    return out
}

// ==============================================================================================
// Mesh -> pixels
// ==============================================================================================

/** The neutral surface colour a mesh with no per-vertex colours is drawn in. */
private val DEFAULT_SURFACE = floatArrayOf(0.70f, 0.745f, 0.80f)

/**
 * Pack this mesh into the 16-float-per-vertex layout [VisionGeometryPreview]'s rasterizer consumes,
 * so the whole shaded / depth-buffered / wireframed renderer applies to a [TriMesh] for free.
 *
 * A mesh with **no normals** is run through [TriMesh.shadedNormals] first (a box stays a box, a
 * cylinder comes out smooth-sided), because the rasterizer needs a normal per vertex and a missing
 * one shades flat grey. A mesh with no colours gets a neutral surface tint.
 */
fun TriMesh.toCapturedMesh(label: String = "TriMesh", creaseAngleDegrees: Double = 35.0): VisionGeometryPreview.CapturedMesh {
    check(VISION_METAL_VERTEX_FLOATS == 16) {
        "toCapturedMesh assumes a 16-float vertex; VISION_METAL_VERTEX_FLOATS is $VISION_METAL_VERTEX_FLOATS"
    }
    val ready = if (hasNormals) this else shadedNormals(creaseAngleDegrees)
    val packed = FloatArray(ready.vertexCount * VISION_METAL_VERTEX_FLOATS)
    for (v in 0 until ready.vertexCount) {
        val o = v * VISION_METAL_VERTEX_FLOATS
        packed[o] = ready.x(v); packed[o + 1] = ready.y(v); packed[o + 2] = ready.z(v); packed[o + 3] = 1f
        val n = ready.normal(v) ?: Vec3(0f, 1f, 0f)
        val l = n.length()
        // The invariant checker rejects a nearly-unit normal (the signature of an un-normalised cross
        // product), so normalise here rather than trusting the source.
        if (l > 1e-20f) n.mul(1f / l) else n.set(0f, 1f, 0f)
        packed[o + 4] = n.x; packed[o + 5] = n.y; packed[o + 6] = n.z; packed[o + 7] = 0f
        val c = ready.color(v)
        packed[o + 8] = c?.x ?: DEFAULT_SURFACE[0]
        packed[o + 9] = c?.y ?: DEFAULT_SURFACE[1]
        packed[o + 10] = c?.z ?: DEFAULT_SURFACE[2]
        packed[o + 11] = 1f
        val uv = ready.uv(v)
        packed[o + 12] = uv?.x ?: 0f
        packed[o + 13] = uv?.y ?: 0f
    }
    return VisionGeometryPreview.CapturedMesh(packed, ready.indices.copyOf(), label)
}

/**
 * How to draw a [TriMesh] in a preview: where the camera stands and what the frame looks like.
 *
 * A thin, mesh-family-shaped wrapper over [VisionGeometryPreview.Camera] and
 * [VisionGeometryPreview.Style], so callers of this family do not have to import the visionOS
 * preview's types to render a cube. [distance] is in multiples of the mesh's own bounding-sphere
 * radius, so the same view frames a 2cm mesh and a 2m one.
 */
data class MeshView(
    val yawDegrees: Double = 28.0,
    val pitchDegrees: Double = 18.0,
    val distance: Double = 2.7,
    val fieldOfViewDegrees: Double = 40.0,
    val width: Int = 720,
    val height: Int = 540,
    val wireframe: Boolean = true,
    val supersample: Int = 2,
    val cullBackFaces: Boolean = true,
    val background: Int = 0xFF101318.toInt(),
) {
    internal fun camera() = VisionGeometryPreview.Camera(yawDegrees, pitchDegrees, distance, fieldOfViewDegrees)

    internal fun style(caption: String?, title: String?) = VisionGeometryPreview.Style(
        width = width,
        height = height,
        supersample = supersample,
        wireframe = wireframe,
        cullBackFaces = cullBackFaces,
        background = background,
        caption = caption ?: "",
        title = title,
    )

    companion object {
        /** Four evenly spaced orbits, for a turntable contact sheet. */
        fun turntable(count: Int = 4, pitchDegrees: Double = 18.0): List<MeshView> =
            (0 until count).map { MeshView(yawDegrees = 360.0 * it / count, pitchDegrees = pitchDegrees) }
    }
}

/**
 * This mesh rasterised to PNG bytes: perspective, z-buffer, two-light lambert shading over the
 * per-vertex colours, and a depth-tested wireframe.
 *
 * AWT-free (the encoder is [afield.util.PngEncoder]), so this is safe to call from inside a live Field
 * box — which matters on macOS, where touching AWT in the server's JVM initialises AppKit in a
 * process that already owns its main thread for GLFW and takes the whole thing down.
 */
fun TriMesh.renderPng(view: MeshView = MeshView(), caption: String? = null, title: String? = null): ByteArray =
    VisionGeometryPreview.renderPng(toCapturedMesh(), view.camera(), view.style(caption, title))

/** This mesh's raster as ARGB ints plus its size — for compositing several meshes into one figure. */
fun TriMesh.renderPixels(view: MeshView = MeshView()): VisionGeometryPreview.Raster =
    VisionGeometryPreview.renderPixels(toCapturedMesh(), view.camera(), view.style("", null))

/**
 * A complete `<figure>` — this mesh rendered as an inline PNG plus its measurements as a caption —
 * for `display(…)` in a box output or for the value visualizer.
 */
fun TriMesh.toFigureHtml(view: MeshView = MeshView(), caption: String? = null, title: String? = null): String {
    val png = renderPng(view, caption = "", title = null)
    val b64 = Base64.getEncoder().encodeToString(png)
    val b = boundsArray()
    val topo = topology()
    val facts = buildString {
        append("%d verts · %d tris · extent %.3f × %.3f × %.3f".format(
            vertexCount, triangleCount, b[3] - b[0], b[4] - b[1], b[5] - b[2],
        ))
        append(" · χ=${topo.eulerCharacteristic}")
        topo.genus?.let { append(" genus $it") }
        if (topo.isWatertight) append(" · watertight, volume %.4f".format(signedVolume()))
        else append(" · open (${topo.boundaryLoops.size} boundary loop(s))")
    }
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return buildString {
        append("<figure style='margin:0'>")
        title?.let { append("<div style='color:#e6ecf4;font-weight:600;padding-bottom:4px'>${escape(it)}</div>") }
        append("<img src='data:image/png;base64,$b64' style='max-width:900px;height:auto;border-radius:6px;display:block'/>")
        append("<figcaption style='color:#93a4ba;font-size:12px;padding-top:6px;line-height:1.5'>")
        append("<div style='font-family:monospace'>${escape(facts)}</div>")
        caption?.let { append("<div>${escape(it)}</div>") }
        append("</figcaption></figure>")
    }
}

/**
 * The inline thumbnail [TriMesh.toHTML] shows in the value visualizer: the mesh actually rendered,
 * small, with its measurements underneath.
 *
 * Kept out of `mesh.kt` so the type itself does not have to know about the rasterizer.
 */
internal fun meshThumbnailHtml(mesh: TriMesh): String {
    if (mesh.triangleCount == 0) {
        return "<div style=\"font-family:monospace;font-size:11px;color:#8a90a0\">TriMesh(empty)</div>"
    }
    // A thumbnail must stay cheap: a 200k-triangle marched surface would otherwise rasterise on every
    // hover. Decimating for display is honest as long as the caption reports the real count.
    val shown = if (mesh.triangleCount > 40_000) mesh.decimate(20_000) else mesh
    val png = shown.renderPng(MeshView(width = 280, height = 210, supersample = 2), caption = "", title = null)
    val b64 = Base64.getEncoder().encodeToString(png)
    val b = mesh.boundsArray()
    val note = if (shown !== mesh) " · thumbnail decimated to ${shown.triangleCount}" else ""
    return """
        <div style="font-family:monospace;font-size:11px;line-height:1.4">
          <img src="data:image/png;base64,$b64" width="280" height="210"
               style="border:1px solid #3a3f4b;border-radius:3px;display:block"/>
          <div style="color:#8a90a0;margin-top:3px">
            ${mesh.vertexCount} verts · ${mesh.triangleCount} tris · ${"%.3f × %.3f × %.3f".format(b[3] - b[0], b[4] - b[1], b[5] - b[2])}$note
          </div>
        </div>
    """.trimIndent()
}

// ==============================================================================================
// Box output
// ==============================================================================================

/**
 * Push a rendered view of [mesh] to one of this box's output channels, with its measurements as a
 * caption.
 *
 * The AWT-free path ([TriMesh.renderPng] → `display`), so it is safe inside a live box.
 */
fun Box.showMesh(
    mesh: TriMesh,
    channel: String = "main",
    view: MeshView = MeshView(),
    caption: String? = null,
    title: String? = null,
) {
    display(channel, mesh.toFigureHtml(view, caption, title))
}

/**
 * Several meshes side by side, each rendered and captioned — the fastest way to see a whole pipeline
 * (generated / subdivided / smoothed / decimated) at a glance.
 *
 * @param columns meshes per row.
 */
fun Box.showMeshGrid(
    vararg panels: Pair<String, TriMesh>,
    channel: String = "main",
    columns: Int = 2,
    cellWidth: Int = 380,
    view: MeshView = MeshView(),
) {
    require(panels.isNotEmpty()) { "showMeshGrid needs at least one panel" }
    val cell = view.copy(width = cellWidth, height = (cellWidth * 0.75).toInt())
    val html = buildString {
        append("<div style='display:grid;grid-template-columns:repeat($columns,minmax(0,1fr));gap:10px'>")
        for ((label, mesh) in panels) {
            append("<div>")
            append(mesh.toFigureHtml(cell, caption = null, title = label))
            append("</div>")
        }
        append("</div>")
    }
    display(channel, html)
}

/**
 * A turntable contact sheet of one mesh: the same shape from [views], laid out in a row.
 *
 * Four viewpoints is usually the difference between "that looks plausible" and "yes, that is the
 * shape" — a silhouette can hide an inside-out face or a missing cap from any single angle.
 */
fun Box.showMeshTurntable(
    mesh: TriMesh,
    channel: String = "main",
    views: List<MeshView> = MeshView.turntable(4),
    cellWidth: Int = 260,
    title: String? = null,
) {
    require(views.isNotEmpty()) { "showMeshTurntable needs at least one view" }
    val html = buildString {
        title?.let { append("<div style='color:#e6ecf4;font-weight:600;padding-bottom:6px'>$it</div>") }
        append("<div style='display:flex;gap:8px;flex-wrap:wrap'>")
        for (v in views) {
            val png = mesh.renderPng(v.copy(width = cellWidth, height = (cellWidth * 0.75).toInt()), caption = "", title = null)
            val b64 = Base64.getEncoder().encodeToString(png)
            append("<div><img src='data:image/png;base64,$b64' style='border-radius:4px;display:block'/>")
            append("<div style='color:#8a90a0;font-family:monospace;font-size:11px;padding-top:3px'>")
            append("yaw ${v.yawDegrees.toInt()}° pitch ${v.pitchDegrees.toInt()}°</div></div>")
        }
        append("</div>")
    }
    display(channel, html)
}

/**
 * A one-line factual summary of a mesh: counts, extent, topology and volume — the thing to `print`
 * when you want the numbers rather than the picture.
 */
fun TriMesh.summary(): String {
    if (triangleCount == 0) return "TriMesh(empty)"
    val topo = topology()
    val b = boundsArray()
    val q = quality()
    return buildString {
        append("$vertexCount verts, $triangleCount tris")
        append(", extent %.4f x %.4f x %.4f".format(b[3] - b[0], b[4] - b[1], b[5] - b[2]))
        append(", chi=${topo.eulerCharacteristic}")
        topo.genus?.let { append(", genus $it") }
        append(if (topo.isWatertight) ", watertight" else ", open(${topo.boundaryLoops.size})")
        append(", area %.4f".format(surfaceArea()))
        if (topo.isWatertight) append(", volume %.4f".format(signedVolume()))
        append(", minAngle %.1f deg".format(q.minAngleDegrees))
    }
}

/** Longest edge in the mesh — a useful scale for choosing a weld tolerance or a march resolution. */
fun TriMesh.longestEdge(): Double {
    var worst = 0.0
    for (t in 0 until triangleCount) {
        val a = position(ia(t)); val b = position(ib(t)); val c = position(ic(t))
        worst = max(worst, max(a.distance(b).toDouble(), max(b.distance(c).toDouble(), c.distance(a).toDouble())))
    }
    return worst
}

/** Mean edge length over the mesh's unique edges. */
fun TriMesh.meanEdgeLength(): Double {
    val topo = topology()
    if (topo.edgeCount == 0) return 0.0
    var sum = 0.0
    for ((a, b) in topo.edges) sum += position(a).distance(position(b)).toDouble()
    return sum / topo.edgeCount
}

/** This mesh recentred on its own bounding box and scaled so its longest axis is [size]. */
fun TriMesh.normalized(size: Number = 1.0): TriMesh {
    if (vertexCount == 0) return this
    val b = boundsArray()
    val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
    val longest = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2]))
    val s = if (longest < 1e-12) 1.0 else size.toDouble() / longest
    return translated(-cx, -cy, -cz).scaled(s)
}
