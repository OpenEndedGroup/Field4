@file:afield.runtime.completion.CompletionCategory("Graphics")
/**
 * An orbitable picture of a handful of embeddings and the distances between them, in a box's output.
 *
 * [SimplexView] wraps one of the `three` viewers and draws what the madp `tetra` page draws: a
 * coloured sphere per point, an edge per pair, the point numbers and edge lengths written onto the
 * picture, and a table of the pairs in the corner. [simplexView] opens one and hands it back, so
 * [SimplexView.show] can replace the simplex underneath without resetting the camera you orbited
 * to; [showSimplex] is the one-call form, for a picture that is drawn once. Before the first
 * simplex arrives the view holds a regular tetrahedron with no lengths on it, and [SimplexView.dim]
 * greys the current picture while a newer one is being worked out.
 *
 * What is drawn is an [EmbeddingSimplex], which places the vectors in 3D so that the drawn edges
 * are their pairwise distances: exactly so for four points or fewer, a best fit above that. The
 * note in the corner says which of the two you are looking at, and quotes the stress when it is
 * the second.
 *
 * ```kotlin
 * showSimplex(embeddingSimplex(listOf(clipEncode("cat"), clipEncode("dog"), clipEncode("car"))))
 * ```
 */

package afield.graphics.three

import afield.document.graph.Box
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.uvSphereMesh
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import afield.math.statistics.EmbeddingSimplex
import afield.math.statistics.embeddingSimplex
import kotlin.math.pow

/**
 * An [EmbeddingSimplex] drawn as the madp `tetra` page draws it: a coloured sphere per point, an edge
 * per pair, the points' numbers and the edges' lengths written on the picture, a table of the pairs
 * with bars in the corner, and a slow turn until you take hold of it.
 *
 * Make the view once, when the box runs, and [show] each new simplex into it; the view keeps its
 * camera, so you can orbit while the picture changes underneath. Until the first [show] it holds a
 * regular tetrahedron with no lengths, the madp page's empty state.
 *
 * ```kotlin
 * val view = simplexView()
 * view.show(embeddingSimplex(vectors, labels))
 * ```
 *
 * Get one with [Box.simplexView].
 */
class SimplexView internal constructor(
    private val box: Box,
    private val uid: String,
    private val width: Int?,
    private val height: Int,
) {
    /** The live `three` view underneath, for anything this class does not do. */
    val view: ThreeGeometryViewState get() = threeViewFor(uid)

    @Volatile private var last: Shown? = null

    private class Shown(val simplex: EmbeddingSimplex, val colors: List<String>, val flags: Boolean, val table: Boolean, val known: Boolean)

    /**
     * Draw [simplex], replacing whatever the view showed.
     *
     * @param colors one CSS colour per point, in order; the madp four, then two more, by default.
     * @param flags mark both ends of every edge with that edge's rank among the edges at that end —
     * green for the closest, orange, red for the furthest — as `tetra2` does.
     * @param table the pair table in the top-right corner: each pair, a bar, its distance.
     * @param note the line in the bottom-left corner; by default it says whether the edges are exact.
     */
    fun show(
        simplex: EmbeddingSimplex,
        colors: List<String> = SIMPLEX_COLORS,
        flags: Boolean = false,
        table: Boolean = true,
        note: String? = null,
    ) {
        require(colors.size >= simplex.size) { "showSimplex: ${colors.size} colours for ${simplex.size} points" }
        val shown = Shown(simplex, colors, flags, table, known = true)
        last = shown
        publish(shown, dimmed = false, note = note ?: defaultNote(simplex))
    }

    /**
     * Grey out the picture on screen, for while a newer one is being worked out, with [note] in the
     * corner. The next [show] brings it back.
     */
    fun dim(note: String = "diagram out of date") {
        publish(last ?: placeholder(), dimmed = true, note = note)
    }

    internal fun showPlaceholder(note: String) {
        publish(placeholder(), dimmed = false, note = note)
    }

    private fun placeholder() = Shown(
        embeddingSimplex(REGULAR_TETRAHEDRON, labels = listOf("1", "2", "3", "4"), normalize = false),
        SIMPLEX_COLORS, flags = false, table = true, known = false,
    )

    private fun publish(shown: Shown, dimmed: Boolean, note: String) {
        val builder = ThreeSceneBuilder(48).apply { simplexScene(shown, dimmed, note) }
        view.update(builder.payload(uid, frameWidth = width, frameHeight = height))
    }

    private fun ThreeSceneBuilder.simplexScene(shown: Shown, dimmed: Boolean, note: String) {
        val s = shown.simplex
        // Drawn about the middle of their bounding box rather than their centroid, so the view frames
        // the shape tightly while it turns; the shape and its distances are the same either way.
        val lo = (0 until 3).map { k -> s.points.minOf { it[k] } }
        val hi = (0 until 3).map { k -> s.points.maxOf { it[k] } }
        val points = s.points.map { vec(it.x - (lo[0] + hi[0]) / 2, it.y - (lo[1] + hi[1]) / 2, it.z - (lo[2] + hi[2]) / 2) }
        background("#050607")
        helpers(false)
        hud(false)
        camera(yaw = 39.4, pitch = 23.8, fieldOfView = 42.0, padding = 1.0)
        turntable(degreesPerSecond = 2.75)
        dimmed(dimmed)
        note(note)

        for (e in s.edges) {
            val alpha = if (shown.known) 0.74 else 0.28
            lines(FLine().apply {
                moveTo(points[e.a]); lineTo(points[e.b])
                color = vec(0.85, 0.85, 0.85, alpha)
            })
            if (shown.known) {
                label(mid(points[e.a], points[e.b], 0.5), "%.3f".format(e.distance), style = "tag")
                if (shown.flags) {
                    label(mid(points[e.a], points[e.b], 0.14), style = "diamond",
                        color = rankColor(e.rankFromA, s.size - 1), title = "${rankName(e.rankFromA, s.size - 1)} from point ${e.a + 1}")
                    label(mid(points[e.b], points[e.a], 0.14), style = "diamond",
                        color = rankColor(e.rankFromB, s.size - 1), title = "${rankName(e.rankFromB, s.size - 1)} from point ${e.b + 1}")
                }
            }
        }
        points.forEachIndexed { i, p ->
            mesh(sphereAt(p), wireframe = false, material = ThreeSurfaceMaterial(
                color = linearRgb(shown.colors[i]), roughness = 0.46f, metalness = 0.05f,
            ))
            label(p, "${i + 1}", style = "chip", title = s.labels[i])
        }
        if (shown.table) overlay(pairTable(s, shown.known), corner = "top-right")
    }

    private fun defaultNote(s: EmbeddingSimplex): String =
        if (s.exact) "diagram current · edges are the distances"
        else "best 3D fit · stress %.3f".format(s.stress1)

    companion object {
        /** The madp `tetra` page's four point colours, then two more from the same palette. */
        val SIMPLEX_COLORS = listOf("#8be9fd", "#50fa7b", "#ffb86c", "#ff79c6", "#bd93f9", "#f1fa8c")

        /** `tetra2`'s rank colours: closest, middle, furthest. */
        val RANK_COLORS = listOf("#50fa7b", "#ffb86c", "#ff5555")

        private val REGULAR_TETRAHEDRON = listOf(
            doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(-1.0, -1.0, 1.0),
            doubleArrayOf(-1.0, 1.0, -1.0), doubleArrayOf(1.0, -1.0, -1.0),
        )

        private val SPHERE = uvSphereMesh(32, 16, 0.2)

        private fun sphereAt(p: Vec3): TriMesh {
            val moved = SPHERE.positions.copyOf()
            for (i in moved.indices step 3) {
                moved[i] += p.x; moved[i + 1] += p.y; moved[i + 2] += p.z
            }
            // White vertex colours, so the material's colour is the colour: with none, the viewer
            // multiplies it by its default surface grey.
            return TriMesh(moved, SPHERE.indices, SPHERE.normals, SPHERE.uvs, FloatArray(moved.size) { 1f })
        }

        private fun mid(a: Vec3, b: Vec3, t: Double): Vec3 =
            vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

        /** Rank [rank] of [ranks] on the green–orange–red scale; with three ranks, exactly [RANK_COLORS]. */
        internal fun rankColor(rank: Int, ranks: Int): String {
            if (ranks <= 1) return RANK_COLORS[0]
            val t = rank.toDouble() / (ranks - 1) * (RANK_COLORS.size - 1)
            val i = t.toInt().coerceAtMost(RANK_COLORS.size - 2)
            val f = t - i
            val a = RANK_COLORS[i].removePrefix("#").chunked(2).map { it.toInt(16) }
            val b = RANK_COLORS[i + 1].removePrefix("#").chunked(2).map { it.toInt(16) }
            return "#" + a.indices.joinToString("") { "%02x".format(Math.round(a[it] + (b[it] - a[it]) * f).toInt()) }
        }

        private fun rankName(rank: Int, ranks: Int) = when (rank) {
            0 -> "closest"
            ranks - 1 -> "furthest"
            else -> "rank ${rank + 1} of $ranks"
        }

        private fun linearRgb(css: String): FloatArray {
            val hex = css.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
            require(hex.length == 6) { "showSimplex: colours are CSS hex, \"#rrggbb\" or \"#rgb\"; got \"$css\"" }
            return FloatArray(3) {
                val c = hex.substring(it * 2, it * 2 + 2).toInt(16) / 255f
                if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
            }
        }

        private fun pairTable(s: EmbeddingSimplex, known: Boolean): String = buildString {
            append("<div style='width:min(250px,calc(100vw - 24px));border:1px solid rgba(255,255,255,.08);")
            append("background:rgba(8,8,8,.58);backdrop-filter:blur(2px);padding:8px;color:#777;font-size:11px'>")
            if (!known) {
                append(row("--", "<span>no current distances</span>", "--"))
            } else {
                val longest = s.edges.maxOf { it.distance }.takeIf { it > 0 } ?: 1.0
                for (e in s.edges) {
                    val pct = (e.distance / longest * 100).coerceIn(2.0, 100.0)
                    val bar = "<span style='background:#151515;height:6px;align-self:center;display:block'>" +
                        "<span style='display:block;height:100%;width:${"%.1f".format(pct)}%;background:#8be9fd'></span></span>"
                    append(row("${e.a + 1}-${e.b + 1}", bar, "%.3f".format(e.distance)))
                }
            }
            append("</div>")
        }

        private fun row(pair: String, middle: String, value: String) =
            "<div style='display:grid;grid-template-columns:42px 1fr 64px;gap:8px;padding:2px 0;" +
                "border-bottom:1px solid rgba(255,255,255,.04)'><span style='color:#bcbcbc'>$pair</span>$middle" +
                "<span style='text-align:right;color:#969696'>$value</span></div>"
    }
}

/**
 * Open a [SimplexView] in this box's output: an orbitable picture of a few embeddings and the
 * distances between them. It starts as the madp page's empty tetrahedron, saying [note].
 *
 * ```kotlin
 * val view = simplexView()
 * view.show(embeddingSimplex(listOf(clipEncode("cat"), clipEncode("dog"), clipEncode("car"))))
 * ```
 *
 * @param channel the output channel the view opens in.
 * @param name the view's name within this box; two views in one box need two names.
 */
fun Box.simplexView(
    channel: String = "simplex",
    name: String = "simplex",
    height: Int = 520,
    width: Int? = 820,
    note: String = "waiting for current embeddings",
): SimplexView {
    val uid = threeViewerUid(this, name)
    val sizing = if (width != null && width > 0) "width:${width}px" else "width:100%"
    display(channel, "<iframe src='${threeViewerIframeSource(uid)}' style='border:none;padding:0;margin:0;$sizing;height:${height}px'>")
    threeViewFor(uid).bindSource(this, "${this.id}_$name")
    return SimplexView(this, uid, width, height).also { it.showPlaceholder(note) }
}

/**
 * Draw [simplex] in this box's output in one call: [simplexView] then [SimplexView.show]. For a
 * picture that changes, keep the [SimplexView] and call `show` on it instead, so the view is not
 * reopened (and its camera reset) each time.
 */
fun Box.showSimplex(
    simplex: EmbeddingSimplex,
    colors: List<String> = SimplexView.SIMPLEX_COLORS,
    flags: Boolean = false,
    channel: String = "simplex",
    name: String = "simplex",
): SimplexView = simplexView(channel, name).also { it.show(simplex, colors, flags) }
