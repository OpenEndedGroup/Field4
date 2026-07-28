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
 * One 3D Voronoi cell's polygonal faces, triangulated into a closed [TriMesh].
 *
 * `voronoi.kt` clips half-spaces into [VoronoiFace] polygons already wound counter-clockwise about
 * their plane normal, and the plane normals point out of the cell, so fanning each polygon gives a
 * consistently outward mesh with no repair pass.
 *
 * # Why there is still a healing step
 *
 * `voronoi.kt` used to leave a **T-junction** wherever four or more bisector planes nearly met: a face
 * claiming a corner that was not on it, so one edge on one side of a seam and two on the other, showing
 * up here as a hairline crack in an otherwise perfect cell. **That is now fixed at the source** — see
 * `Voronoi3d.collectCorners` and `VoronoiFaceTopologyTest` — and where 22 of the 36 interior cells of a
 * 72-site lattice used to close from their faces alone, all of them now do.
 *
 * The repair stays, because the fix is not total: 4 of 465 bounded cells over a 24-lattice sweep, and 12
 * of 447 of the wildly-conditioned clipped ones, still have an ill-conditioned corner the clipper
 * resolves into two. `VoronoiFaceTopologyTest` ratchets those counts, and `voronoi.kt` explains what
 * closing the gap properly would take. A caller who would rather see the raw faceted result, cracks and
 * all, passes `healToConvexHull = false`.
 *
 * A Voronoi cell is by construction the intersection of half-spaces, so it is **convex**, so its
 * convex hull is exactly itself. That makes [convexHullMesh] over the same corner set an exact repair
 * rather than an approximation. The faceted result is preferred whenever it is already correct, because
 * it keeps the cell's real face structure.
 *
 * @param shrink scale the cell toward its own site. `1.0` leaves the cells touching (so a whole
 *   lattice is one solid); `0.9` opens the seams, which is what makes an exploded Voronoi readable.
 * @param fanFromCentroid triangulate each face from its centroid rather than from its first corner.
 *   Better-shaped triangles at the cost of one extra vertex per face; on by default because Voronoi
 *   faces are often long thin polygons whose corner fan is all slivers.
 * @param healToConvexHull rebuild the cell as the convex hull of its corners when the fanned faces do
 *   not close. Set it false to see the raw faceted result, cracks and all.
 */
fun VoronoiCell3.toTriMesh(
    shrink: Double = 1.0,
    fanFromCentroid: Boolean = true,
    healToConvexHull: Boolean = true,
): TriMesh {
    val s = shrink.toFloat()
    fun place(p: Vec3): Vec3 =
        if (s == 1f) Vec3(p) else Vec3(site.x + (p.x - site.x) * s, site.y + (p.y - site.y) * s, site.z + (p.z - site.z) * s)
    val faceted = triMesh {
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
    if (faceted.triangleCount == 0) return faceted
    // The face polygons already share exact corner coordinates where they agree, so the weld tolerance
    // only has to absorb float noise: scale it to the cell rather than picking an absolute number.
    val extent = faceted.bounds().let { Vec3(it.max).sub(it.min).length().toDouble() }
    val welded = faceted.weld(extent * 1e-5)
    if (!healToConvexHull || welded.topology().isWatertight) return welded
    val hull = convexHullMesh(faces.flatMap { face -> face.polygon.map { place(it) } })
    return if (hull.triangleCount > 0) hull else welded
}

/**
 * Every cell of a 3D Voronoi lattice as one [TriMesh], one connected component per cell.
 *
 * Unbounded cells (the ones on the convex hull of the sites, which extend to infinity and are only
 * closed by the clip box) are included by default because the clip box does close them; pass
 * `boundedOnly = true` for just the interior cells, which is what you want if you are about to
 * measure volumes or make an exploded picture.
 *
 * @param shrink see [VoronoiCell3.toTriMesh]. `0.92` is a good exploded look.
 */
fun VoronoiLattice.toTriMesh(shrink: Double = 0.92, boundedOnly: Boolean = false): TriMesh {
    val builder = TriMeshBuilder()
    for (cell in cells) {
        if (boundedOnly && !cell.bounded) continue
        builder.add(cell.toTriMesh(shrink))
    }
    return builder.build()
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
 * Per-vertex colours from a per-vertex scalar, through the same [afield.rectsl.Colormaps] tables
 * RectSL's charts and `Img.colormap` use — so a curvature field, a distance field and a bar chart in
 * the same document are coloured by one source of truth.
 *
 * This is what turns [TriMesh.gaussianCurvature] or [TriMesh.meanCurvature] from an array of numbers
 * into something you can look at.
 *
 * @param values one per vertex.
 * @param colormap a name from [afield.rectsl.Colormaps.names] — `viridis`, `magma`, `inferno`,
 *   `plasma`, `turbo`, `coolwarm`, `grayscale`.
 * @param low the value mapped to the bottom of the ramp; defaults to the data's own minimum.
 * @param high the value mapped to the top; defaults to the data's own maximum.
 * @param percentile when [low] and [high] are left null, clip this fraction off each tail before
 *   choosing them. Curvature fields have long tails at creases, and one outlier vertex otherwise
 *   flattens the entire ramp; `0.02` is a good default and `0.0` disables the clip.
 */
fun TriMesh.coloredByScalar(
    values: DoubleArray,
    colormap: String = "viridis",
    low: Double? = null,
    high: Double? = null,
    percentile: Double = 0.02,
): TriMesh {
    require(values.size == vertexCount) { "expected $vertexCount values, got ${values.size}" }
    if (vertexCount == 0) return this
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return withColor(0.5, 0.5, 0.5)
    val clip = percentile.coerceIn(0.0, 0.49)
    val lo = low ?: sorted[(sorted.size * clip).toInt().coerceIn(0, sorted.size - 1)]
    val hi = high ?: sorted[((sorted.size - 1) * (1.0 - clip)).toInt().coerceIn(0, sorted.size - 1)]
    val span = if (hi - lo < 1e-30) 1.0 else hi - lo
    val out = FloatArray(vertexCount * 3)
    for (v in 0 until vertexCount) {
        val t = if (values[v].isFinite()) (values[v] - lo) / span else 0.5
        val c = afield.rectsl.Colormaps.sample(colormap, t)
        out[v * 3] = c.r.toFloat(); out[v * 3 + 1] = c.g.toFloat(); out[v * 3 + 2] = c.b.toFloat()
    }
    return withColors(out)
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
    /** Orbit around `+y`, in degrees. `0` looks straight down `-z`. */
    val yawDegrees: Double = 28.0,
    /** Lift above the horizon, in degrees. Positive looks down on the mesh. */
    val pitchDegrees: Double = 18.0,
    /** Eye distance in multiples of the mesh's bounding-sphere radius, so it is scale-free. */
    val distance: Double = 2.7,
    /** Vertical field of view in degrees. Smaller is a longer lens and less perspective. */
    val fieldOfViewDegrees: Double = 40.0,
    /** Output width in pixels. */
    val width: Int = 720,
    /** Output height in pixels. */
    val height: Int = 540,
    /** Draw a depth-tested wireframe over the shading. On by default: it turns "a smooth blob" into "a mesh you can count the triangles in". */
    val wireframe: Boolean = true,
    /** Render at this multiple in each axis and box-filter down. `2` is a good anti-aliasing default. */
    val supersample: Int = 2,
    /** Cull back faces, as a GPU would — so a face wound the wrong way is missing here exactly as it would be there. */
    val cullBackFaces: Boolean = true,
    /** Background as packed ARGB. */
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

/** The background the inline thumbnail's raster is cleared to, as packed ARGB. */
internal const val MESH_THUMBNAIL_BACKGROUND: Int = 0xFF101318.toInt()

/** How wide and tall [meshThumbnailHtml]'s render is, in pixels, before supersampling. */
internal const val MESH_THUMBNAIL_WIDTH: Int = 300
internal const val MESH_THUMBNAIL_HEIGHT: Int = 225

/**
 * The inline card [TriMesh.toHTML] shows in the value visualizer: the mesh actually rendered, from a
 * viewpoint above and to the right, beside the measurements you would otherwise have to type out.
 *
 * # What you are looking at
 *
 * The image is the real mesh through the same CPU rasterizer the gallery uses — perspective, z-buffer,
 * two-light lambert over the mesh's own per-vertex colours (a neutral blue-grey if it has none), and a
 * depth-tested wireframe so the triangles are countable. Back faces are culled exactly as a GPU would
 * cull them, so an **open** mesh wound inside-out loses the surfaces facing you and reads as holes.
 *
 * A **closed** mesh wound inside-out does *not* show up in the picture, and it is worth knowing why: the
 * silhouette is unchanged (culling the front faces only reveals the far inner wall) and the rasterizer
 * deliberately flips an inward normal rather than shading it black, so the render looks normal. The tell
 * is in the numbers instead — the `volume` line is the **signed** volume, so an inverted solid reports a
 * negative one. Both halves of that are pinned by [afield.stdlib.MeshVisualizerPixelTest].
 *
 * Nothing in the picture is colour-coded by any measured quantity;
 * for that, colour the mesh yourself with [coloredByScalar] and the shading will carry it.
 *
 * The facts beside it are the ones that decide whether a mesh is usable: counts, whether it is closed
 * (and manifold, which is not the same thing), its Euler characteristic and genus, surface area, volume
 * if it is closed, and its bounding box. All of them are computed on the mesh you actually have, even
 * when the *picture* was decimated to stay cheap — which it says when it happens.
 *
 * # What it costs
 *
 * A 300x225 render at 2x supersampling — 270k rasterized samples — plus one topology pass (edge and
 * vertex adjacency, boundary loops, components) and one area/volume pass over the triangles. On an
 * icosphere that is a few milliseconds. Above 40k triangles the *render* runs on a decimation, because
 * QEM at 20k triangles is much cheaper than rasterizing 200k of them; the numbers are still the real
 * mesh's, and the caption says the picture is not.
 *
 * Kept out of `mesh.kt` so the type itself does not have to know about the rasterizer.
 */
internal fun meshThumbnailHtml(mesh: TriMesh): String {
    if (mesh.triangleCount == 0) {
        return "<div style=\"font-family:monospace;font-size:11px;color:#8a90a0\">" +
                "TriMesh(empty) — ${mesh.vertexCount} verts, no triangles</div>"
    }
    // A thumbnail must stay cheap: a 200k-triangle marched surface would otherwise rasterise on every
    // evaluation. Decimating for display is honest as long as the caption reports the real count.
    val shown = if (mesh.triangleCount > 40_000) mesh.decimate(20_000) else mesh
    val png = shown.renderPng(
        MeshView(
            width = MESH_THUMBNAIL_WIDTH,
            height = MESH_THUMBNAIL_HEIGHT,
            supersample = 2,
            background = MESH_THUMBNAIL_BACKGROUND,
        ),
        caption = "", title = null,
    )
    val b64 = Base64.getEncoder().encodeToString(png)
    val b = mesh.boundsArray()
    val topo = mesh.topology()

    val rows = ArrayList<Pair<String, String>>()
    rows += "size" to "${mesh.vertexCount} verts · ${mesh.triangleCount} tris" +
            (if (topo.componentCount > 1) " · ${topo.componentCount} parts" else "")
    rows += "closed" to when {
        topo.isWatertight -> "watertight"
        topo.isManifold -> "open · ${topo.boundaryLoops.size} boundary loop(s)"
        else -> "not manifold · ${topo.boundaryLoops.size} boundary loop(s)"
    }
    rows += "topology" to buildString {
        append("χ=${topo.eulerCharacteristic}")
        topo.genus?.let { append(" · genus $it") }
    }
    rows += "area" to "%.4f".format(mesh.surfaceArea())
    if (topo.isWatertight) rows += "volume" to "%.4f".format(mesh.signedVolume())
    rows += "bounds" to "%.3f × %.3f × %.3f".format(b[3] - b[0], b[4] - b[1], b[5] - b[2])
    rows += "at" to "(%.2f, %.2f, %.2f)…(%.2f, %.2f, %.2f)".format(b[0], b[1], b[2], b[3], b[4], b[5])
    if (shown !== mesh) rows += "note" to "picture decimated to ${shown.triangleCount} tris"

    val facts = rows.joinToString("") { (label, value) ->
        "<tr><td style=\"color:#6d7787;padding:0 8px 2px 0;vertical-align:top\">$label</td>" +
                "<td style=\"color:#c3ccd9;padding:0 0 2px 0\">$value</td></tr>"
    }
    return """
        <div style="font-family:monospace;font-size:11px;line-height:1.45;display:flex;gap:10px;align-items:flex-start">
          <img src="data:image/png;base64,$b64" width="$MESH_THUMBNAIL_WIDTH" height="$MESH_THUMBNAIL_HEIGHT"
               style="border:1px solid #3a3f4b;border-radius:3px;display:block;flex:0 0 auto"
               alt="shaded render of a ${mesh.triangleCount}-triangle mesh"/>
          <table style="border-collapse:collapse"><tbody>$facts</tbody></table>
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
