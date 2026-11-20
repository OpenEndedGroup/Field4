@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.rhino

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.graphics.text.layoutText
import afield.graphics.text.typeface
import afield.graphics.three.ThreeSceneBuilder
import afield.graphics.three.ThreeSurfaceMaterial
import afield.graphics.three.ThreeUpAxis
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import java.awt.Font

/**
 * Draw a [RhinoModel] in a [three] view, as Rhino would show it.
 *
 * ```kotlin
 * val model = Rhino3dm.load("~/Downloads/model.3dm")
 * three { rhino(model) }
 * ```
 *
 * Every object becomes one part of the view's Parts list, folded under its layer path, so the
 * layer tree in the file is the tree in the viewer. Meshes are shaded in their object's colour and
 * outlined with their Brep edges; curves, hatch boundaries and text keep their colours. Text is set
 * as outlines in its own plane at its own height, so it sits on the model the way it does in Rhino
 * rather than floating over it.
 *
 * @param hiddenLayers include objects that Rhino has hidden (on a switched-off layer, or hidden
 *   themselves), starting hidden in the Parts list, so you can switch them on. `false` leaves them out.
 * @param edges outline meshes that came from Breps and extrusions with their edges, and draw
 *   mesh-less Breps as wireframes.
 * @param text set text objects as outlines.
 * @param font the typeface for [text]; Rhino's own font is not read.
 * @param zUp frame the view with Z up, as Rhino models are. `false` keeps the viewer's own camera.
 * @param include which objects to draw at all — `{ it.layer?.path != "Construction" }`, say. An
 *   object left out is not in the Parts list either, and does not count toward the framing.
 */
fun ThreeSceneBuilder.rhino(
    model: RhinoModel,
    hiddenLayers: Boolean = true,
    edges: Boolean = true,
    text: Boolean = true,
    font: String = Font.SANS_SERIF,
    zUp: Boolean = true,
    include: (RhinoObject) -> Boolean = { true },
) {
    if (zUp) camera(yaw = 35.0, pitch = 24.0, distance = 2.4, upAxis = ThreeUpAxis.Z)
    // Frame what Rhino shows. Hidden layers are often construction geometry far from the model
    // (vanishing points, site helpers), and framing on them leaves the model a speck.
    visibleBounds(model.objects.filter { it.isVisible && include(it) })?.let { (lo, hi) -> fitTo(lo, hi) }
    model.objects.forEachIndexed { index, o ->
        if (!hiddenLayers && !o.isVisible) return@forEachIndexed
        if (!include(o)) return@forEachIndexed
        val id = "rhino-$index"
        val layer = o.layer?.pathParts ?: emptyList()
        val hidden = !o.isVisible
        val metadata = mapOf("rhinoType" to o.type, "rhinoId" to o.id, "rhinoName" to o.name, "block" to o.block)
        when (o) {
            is RhinoMesh -> {
                mesh(
                    o.mesh,
                    label = o.label,
                    id = id,
                    wireframe = false,
                    // A shell of separate Brep faces is not closed, so its back faces show through gaps.
                    cullBackFaces = false,
                    flatShading = o.mesh.normals == null,
                    opacity = o.color.w.toDouble(),
                    material = ThreeSurfaceMaterial(color = floatArrayOf(o.color.x, o.color.y, o.color.z)),
                    layer = layer,
                    metadata = metadata,
                    hidden = hidden,
                )
                if (edges) o.edges?.let { lines(listOf(outline(it, o.color)), id = id, layer = layer, hidden = hidden) }
            }
            is RhinoCurve -> lines(listOf(o.line), id = id, label = o.label, layer = layer, metadata = metadata, hidden = hidden)
            is RhinoText -> if (text) {
                val glyphs = o.toFLines(font).map(::flattened)
                if (glyphs.isNotEmpty()) lines(glyphs, id = id, label = "\"${o.text.lineSequence().first()}\"", layer = layer, metadata = metadata, hidden = hidden)
            }
            is RhinoPoints -> {
                val dots = o.points.map { p ->
                    FLine().moveTo(p).also {
                        it.pointed = true
                        it.pointSize = 6.0
                        it.color = o.color
                    }
                }
                lines(dots, id = id, label = o.label, layer = layer, metadata = metadata, hidden = hidden)
            }
            is RhinoSkipped -> if (edges) o.edges?.let {
                lines(listOf(outline(it, o.color)), id = id, label = "${o.label} (wireframe: no render mesh)", layer = layer, metadata = metadata, hidden = hidden)
            }
        }
    }
}

/** Edges drawn a little darker than the surface they outline, as Rhino's shaded view does. */
private fun outline(edges: FLine, surface: Vec4): FLine = flattened(edges).also {
    it.color = vec(surface.x * 0.35, surface.y * 0.35, surface.z * 0.35, 1.0)
    it.thickness = 1.0
}

/**
 * [line] with each cubic replaced by just enough straight segments to stay within a thousandth of
 * the line's own size (Wang's bound), and its colour, thickness and fill kept.
 *
 * The viewer otherwise samples every cubic 48 times. That is right for a curve someone drew, and
 * far too many for the thousands of short edge spans of a Brep or the cubics of a glyph; on an
 * architectural model it multiplied the segment count about eightfold.
 */
private fun flattened(line: FLine): FLine {
    val nodes = line.nodes
    if (nodes.none { it is FLine.CubicTo }) return line.copy()
    var lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    var hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    for (n in nodes) {
        if (n is FLine.Close) continue
        val v = n.to
        lo = doubleArrayOf(minOf(lo[0], v.x.toDouble()), minOf(lo[1], v.y.toDouble()), minOf(lo[2], v.z.toDouble()))
        hi = doubleArrayOf(maxOf(hi[0], v.x.toDouble()), maxOf(hi[1], v.y.toDouble()), maxOf(hi[2], v.z.toDouble()))
    }
    val size = Math.sqrt((0..2).sumOf { (hi[it] - lo[it]) * (hi[it] - lo[it]) })
    val tolerance = maxOf(1e-9, size * 1e-3)
    val out = FLine()
    // A Close stores no point, so the cubic's start is tracked rather than read off the node before.
    var start = nodes.first().to
    var last = start
    for (n in nodes) {
        when (n) {
            is FLine.CubicTo -> {
                val p0 = last
                val c1 = n.c1
                val c2 = n.c2
                val p3 = n.to
                fun second(a: Float, b: Float, c: Float) = (a - 2 * b + c).toDouble()
                val m = maxOf(
                    Math.sqrt(second(p0.x, c1.x, c2.x).let { it * it } + second(p0.y, c1.y, c2.y).let { it * it } + second(p0.z, c1.z, c2.z).let { it * it }),
                    Math.sqrt(second(c1.x, c2.x, p3.x).let { it * it } + second(c1.y, c2.y, p3.y).let { it * it } + second(c1.z, c2.z, p3.z).let { it * it }),
                )
                val segments = Math.ceil(Math.sqrt(0.75 * m / tolerance)).toInt().coerceIn(1, 24)
                for (k in 1..segments) {
                    val t = k.toDouble() / segments
                    val u = 1 - t
                    fun axis(a: Float, b: Float, c: Float, d: Float) = u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
                    out.lineTo(axis(p0.x, c1.x, c2.x, p3.x), axis(p0.y, c1.y, c2.y, p3.y), axis(p0.z, c1.z, c2.z, p3.z))
                }
            }
            is FLine.MoveTo -> {
                out.moveTo(n.to)
                start = n.to
            }
            is FLine.Close -> {
                out.close()
                last = start
                continue
            }
            else -> out.lineTo(n.to)
        }
        last = n.to
    }
    out.color = line.color
    line.thickness?.let { out.thickness = it }
    out.filled = line.filled
    out.stroked = line.stroked
    return out
}

/**
 * This text set as outlines, one FLine per glyph, lying in the text's plane at its height and
 * alignment, in model units.
 *
 * Rhino's [height][RhinoText.height] is the height of a capital, so the typeface is scaled until its
 * "H" is that tall. The font is [font], not the one named in the file, which openNURBS does not
 * resolve; the letters' size and placement match, their shapes need not.
 */
fun RhinoText.toFLines(font: String = Font.SANS_SERIF, filled: Boolean = false): List<FLine> {
    if (text.isBlank() || height <= 0) return emptyList()
    val face = try {
        typeface(font, 100.0)
    } catch (_: Exception) {
        return emptyList()
    }
    val capHeight = layoutText("H", face).inkedGlyphs.firstOrNull()?.glyph?.bounds?.h?.takeIf { it > 0 } ?: (face.ascent * 0.72)
    val scale = height / capHeight
    val align = when (horizontalAlignment) {
        1 -> afield.graphics.text.TextAlign.CENTER
        2 -> afield.graphics.text.TextAlign.RIGHT
        else -> afield.graphics.text.TextAlign.LEFT
    }
    val layout = layoutText(text, face, align = align)
    // Text space runs y down from the first baseline; Rhino's plane runs y up. `lastBaseline` is
    // how far below the first baseline the last one sits.
    val lastBaseline = layout.lines.lastOrNull()?.baselineY ?: 0.0
    val dy = when (verticalAlignment) {
        0 -> -capHeight                          // top of the first line's capitals
        1 -> -capHeight / 2                      // middle of the first line's capitals
        2 -> 0.0                                 // first baseline
        3 -> (lastBaseline - capHeight) / 2      // middle of the block
        4 -> lastBaseline - capHeight / 2        // middle of the last line
        5 -> lastBaseline                        // last baseline
        else -> lastBaseline + face.descent      // bottom of the ink
    }
    // TextAlign already centres or right-aligns each line about x = 0, which is the anchor.
    return layout.toFLinesInPlane(this, scale, dy, filled)
}

private fun afield.graphics.text.TextLayout.toFLinesInPlane(
    text: RhinoText, scale: Double, dy: Double, filled: Boolean,
): List<FLine> = inkedGlyphs.map { g ->
    val t = g.transform()
    g.glyph.outline.transform { p ->
        val q = t.transform(java.awt.geom.Point2D.Double(p.x.toDouble(), p.y.toDouble()), null)
        // Glyph space is y down; the plane is y up.
        text.at(q.x * scale, -(q.y - dy) * scale)
    }.also {
        it.color = text.color
        it.thickness = 1.0
        it.filled = filled
        it.stroked = true
    }
}

/** The box around [objects]' geometry, or `null` when there is none. */
private fun visibleBounds(objects: List<RhinoObject>): Pair<afield.math.linalg.Vec3, afield.math.linalg.Vec3>? {
    val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    var any = false
    fun take(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        any = true
        lo[0] = minOf(lo[0], x); lo[1] = minOf(lo[1], y); lo[2] = minOf(lo[2], z)
        hi[0] = maxOf(hi[0], x); hi[1] = maxOf(hi[1], y); hi[2] = maxOf(hi[2], z)
    }
    for (o in objects) {
        when (o) {
            is RhinoMesh -> o.mesh.positions.let { p -> for (i in p.indices step 3) take(p[i], p[i + 1], p[i + 2]) }
            is RhinoCurve -> o.line.nodes.forEach { if (it !is FLine.Close) take(it.to.x, it.to.y, it.to.z) }
            is RhinoText -> take(o.origin.x, o.origin.y, o.origin.z)
            is RhinoPoints -> o.points.forEach { take(it.x, it.y, it.z) }
            is RhinoSkipped -> o.edges?.nodes?.forEach { if (it !is FLine.Close) take(it.to.x, it.to.y, it.to.z) }
        }
    }
    return if (any) vec(lo[0], lo[1], lo[2]) to vec(hi[0], hi[1], hi[2]) else null
}
