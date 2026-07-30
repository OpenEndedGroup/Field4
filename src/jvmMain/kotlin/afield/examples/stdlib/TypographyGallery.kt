package afield.examples.stdlib

import afield.Rect
import afield.graphics.FLine
import afield.stdlib.Img
import afield.stdlib.MeshView
import afield.stdlib.TextAlign
import afield.stdlib.TextFillRule
import afield.stdlib.TextLayout
import afield.stdlib.Typeface
import afield.stdlib.Typefaces
import afield.stdlib.Vec2
import afield.stdlib.marchingCubes
import afield.stdlib.sdfSmoothUnion
import afield.stdlib.sdfSphere
import afield.stdlib.textWidth
import afield.stdlib.vec
import afield.stdlib.advanceBoxFLines
import afield.stdlib.distanceField
import afield.stdlib.eulerCharacteristic
import afield.stdlib.genus
import afield.stdlib.inkFraction
import afield.stdlib.isWatertight
import afield.stdlib.layoutText
import afield.stdlib.metricsFLines
import afield.stdlib.montage
import afield.stdlib.onCurve
import afield.stdlib.PlacedGlyph
import afield.stdlib.arcLength
import afield.stdlib.flatShaded
import afield.stdlib.fline
import afield.stdlib.imgFromBytes
import afield.stdlib.rasterizeFLines
import afield.stdlib.rasterizeFLinesFramed
import afield.stdlib.rasterizeText
import afield.stdlib.renderPng
import afield.stdlib.samplePoints
import afield.stdlib.toPngBytes
import afield.stdlib.signedVolume
import afield.stdlib.textCircle
import afield.stdlib.toContours
import afield.stdlib.toFLines
import afield.stdlib.toImg
import afield.stdlib.toMesh
import afield.stdlib.toRGB
import afield.stdlib.toSdf
import afield.stdlib.volume
import afield.stdlib.wrapText
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.exitProcess

/*
FIELD_TEST
id: stdlib.typography.gallery
runner: jvm-main
entrypoint: afield.examples.stdlib.TypographyGallery
tags: [smoke, stdlib, typography, capture, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "typography gallery rendered"
*/
/**
 * The typography family, one PNG per claim, all of it CPU.
 *
 * Every panel is a real render of `afield.stdlib/typography.kt` + `typography_field.kt`: the letters
 * are filled from the contours those files extracted, not drawn by `Graphics2D.drawString`, so a
 * legible PNG here is evidence about *this* code. Nothing opens a window, a GPU context or a browser —
 * the raster path is the family's own scanline filler through `Img.savePng` (`PngEncoder`, AWT-free)
 * and the mesh path is `TriMesh.renderPng` (`mesh_field.kt:368`, also AWT-free). AWT is touched only
 * for the font manager itself, under `java.awt.headless=true`.
 *
 * Several panels are deliberately **pairs**, because a single picture of type is easy to be wrong
 * about and a difference is not:
 *
 *  - `counters` — the same word, the same filler, the same fill rule, and the winding convention on
 *    versus off. On, the counters are holes; off, they fill solid. That is the whole argument for the
 *    convention, in two pictures.
 *  - `fill_rules` — the same rings under NON_ZERO and EVEN_ODD. Two independent rules that must agree
 *    when the winding is right, and the panel prints how much of the raster differs.
 *  - `tracking` — tracking off versus on, with the advance boxes drawn, so the extra space is visibly
 *    between the letters rather than around the block.
 *  - `mesh` — an extruded `o` next to an extruded `l`, rendered with back-face culling on, so a
 *    wrongly-wound face would be *missing* rather than merely shaded oddly. Their genus (1 and 0) is
 *    printed with them.
 *
 * Output: `args[0]`, else `$TYPOGRAPHY_GALLERY_DIR`, else a temp directory — the same three-way
 * precedence as `MeshGallery` and `WfcGallery`, so the FIELD_TEST writes nothing into the repo.
 */
object TypographyGallery {

    /** A panel: a name, and the PNG bytes plus a one-line summary it produces. */
    private class Panel(val name: String, val build: () -> Pair<ByteArray, String>)

    // Logical families, not "Helvetica": they resolve on every platform the docs build might run on,
    // and the point of the gallery is the geometry rather than any particular face.
    private val sans: Typeface by lazy { Typefaces.named("SansSerif", 128.0) }
    private val serif: Typeface by lazy { Typefaces.named("Serif", 128.0) }

    private val PANELS: List<Panel> = listOf(
        Panel("word") { panelWord() },
        Panel("counters") { panelCounters() },
        Panel("fill_rules") { panelFillRules() },
        Panel("outlines") { panelOutlines() },
        Panel("tracking") { panelTracking() },
        Panel("metrics") { panelMetrics() },
        Panel("on_a_curve") { panelOnACurve() },
        Panel("kinetic") { panelKinetic() },
        Panel("paragraph") { panelParagraph() },
        Panel("points") { panelPoints() },
        Panel("mesh") { panelMesh() },
        Panel("sdf") { panelSdf() },
        Panel("motion_march") { panelMotionMarch() },
        Panel("motion_morph") { panelMotionMorph() },
    )

    // ------------------------------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------------------------------

    /** The headline claim: a word, filled from its own contours, and readable. */
    private fun panelWord(): Pair<ByteArray, String> {
        val layout = layoutText("Handgloves", sans)
        val img = rasterizeText(layout, 1000, 260)
        val ink = img.inkFraction()
        check(ink > 0.06 && ink < 0.45) { "'Handgloves' inked $ink of the frame — blank or flooded" }
        // "Handgloves" contains a, d, g, o and e, so *some* counters must be holes. How many
        // depends on the face the platform resolved (a double-storey g has two), so the count is
        // reported rather than asserted — TypographyTest pins the face-independent facts.
        check(layout.holeCount >= 4) { "only ${layout.holeCount} holes in 'Handgloves' — counters were lost" }
        return montage(listOf("Handgloves — filled from its own contours" to img), columns = 1, cellSide = 1000)
            .toPngBytes() to "ink=${pct(ink)}, ${layout.contourCount} contours, ${layout.holeCount} holes"
    }

    /**
     * The control arm for the winding convention.
     *
     * Both halves go through the identical filler at the identical fill rule. The only difference is
     * `toContours(honourHoles = …)`, i.e. whether hole rings run the opposite way to their bowls.
     */
    private fun panelCounters(): Pair<ByteArray, String> {
        val layout = layoutText("open", sans.atSize(200.0))
        val window = layout.bounds
        val good = rasterizeText(
            layout, 460, 300, window = window,
            fillRule = TextFillRule.NON_ZERO, rings = layout.toContours(honourHoles = true)
        )
        val bad = rasterizeText(
            layout, 460, 300, window = window,
            fillRule = TextFillRule.NON_ZERO, rings = layout.toContours(honourHoles = false)
        )
        val gi = good.inkFraction()
        val bi = bad.inkFraction()
        check(bi > gi * 1.15) {
            "the control arm inked $bi and the convention inked $gi — the counters did not close up, " +
                "so this panel is not showing what it claims"
        }
        return montage(
            listOf(
                "convention on: counters are holes" to good,
                "convention off: counters fill solid" to bad,
            ),
            columns = 2, cellSide = 460
        ).toPngBytes() to "ink ${pct(gi)} with holes vs ${pct(bi)} without — ${"%.1f".format(bi / gi)}x"
    }

    /** Two independent fill rules over correctly wound rings must produce the same picture. */
    private fun panelFillRules(): Pair<ByteArray, String> {
        val layout = layoutText("B%o", serif.atSize(200.0))
        val window = layout.bounds
        val nz = rasterizeText(layout, 420, 300, window = window, fillRule = TextFillRule.NON_ZERO)
        val eo = rasterizeText(layout, 420, 300, window = window, fillRule = TextFillRule.EVEN_ODD)
        var diff = 0.0
        for (y in 0 until nz.height) for (x in 0 until nz.width) {
            diff += Math.abs(nz.at(x, y, 0) - eo.at(x, y, 0)).toDouble()
        }
        val mean = diff / (nz.width * nz.height)
        check(mean < 0.01) { "NON_ZERO and EVEN_ODD disagree by $mean per pixel — the winding is wrong" }
        val delta = Img.generate(nz.width, nz.height) { x, y -> Math.abs(nz.at(x, y, 0) - eo.at(x, y, 0)) }
        return montage(
            listOf("NON_ZERO" to nz, "EVEN_ODD" to eo, "|difference|" to delta),
            columns = 3, cellSide = 320
        ).toPngBytes() to "mean |NON_ZERO - EVEN_ODD| = ${"%.5f".format(mean)} per pixel"
    }

    /** The contours themselves, as line art, so you can see the cubics and the ring structure. */
    private fun panelOutlines(): Pair<ByteArray, String> {
        val layout = layoutText("aeg", serif.atSize(240.0))
        val rings = layout.toContours()
        val outerLines = rings.filter { it.isOuter }.map { it.toPolyline() }
        val holeLines = rings.filter { it.isHole }.map { it.toPolyline() }
        // Both cells framed by the union, so the holes sit where they do in the letters rather than
        // being blown up to fill their own cell.
        val reference = outerLines + holeLines
        val outers = rasterizeFLinesFramed(outerLines, reference, 460, 320, margin = 10)
        val holes = rasterizeFLinesFramed(holeLines, reference, 460, 320, margin = 10)
        check(holeLines.isNotEmpty()) { "'aeg' has no hole rings — the nesting classifier failed" }
        return montage(
            listOf(
                "outer rings (${outerLines.size}, positive area)" to outers,
                "hole rings (${holeLines.size}, negative area)" to holes,
            ),
            columns = 2, cellSide = 460
        ).toPngBytes() to
            "${rings.size} rings: ${outerLines.size} outer, ${holeLines.size} holes; " +
            "areas ${rings.joinToString(" ") { if (it.signedArea > 0) "+" else "-" }}"
    }

    /** Tracking off versus on, with the advance boxes drawn: the space goes *between* the letters. */
    private fun panelTracking(): Pair<ByteArray, String> {
        val tight = layoutText("TYPE", sans.atSize(150.0), tracking = 0.0)
        val loose = layoutText("TYPE", sans.atSize(150.0), tracking = 150.0 * 0.18)
        val ratio = loose.advanceWidth / tight.advanceWidth
        check(ratio > 1.2) { "tracking barely widened the line ($ratio) — it is not being applied" }
        // Both cells framed by the wider layout, so the tight one really looks narrower instead of
        // being scaled up to fill its cell — the whole claim would otherwise be invisible.
        val reference = polylinesOf(loose) + loose.advanceBoxFLines()
        val a = rasterizeFLinesFramed(polylinesOf(tight) + tight.advanceBoxFLines(), reference, 620, 220, margin = 6)
        val b = rasterizeFLinesFramed(polylinesOf(loose) + loose.advanceBoxFLines(), reference, 620, 220, margin = 6)
        return montage(
            listOf(
                "tracking 0: ${"%.1f".format(tight.advanceWidth)} units wide" to a,
                "tracking 0.18em: ${"%.1f".format(loose.advanceWidth)} units wide" to b,
            ),
            columns = 1, cellSide = 620
        ).toPngBytes() to "loose / tight advance width = ${"%.4f".format(ratio)}"
    }

    /** What layout decided: baselines, ascent, descent, and one advance box per glyph. */
    private fun panelMetrics(): Pair<ByteArray, String> {
        val layout = layoutText("Ax gj\nwq", serif.atSize(120.0), lineSpacing = 1.15)
        val lines = polylinesOf(layout) + layout.metricsFLines() + layout.advanceBoxFLines()
        val img = rasterizeFLines(lines, 700, 420, margin = 8)
        check(layout.lines.size == 2) { "expected two baselines, got ${layout.lines.size}" }
        val gap = layout.lines[1].baselineY - layout.lines[0].baselineY
        check(gap > layout.ascent) { "baseline gap $gap is smaller than the ascent ${layout.ascent}" }
        return montage(listOf("baselines, ascent/descent, advance boxes" to img), columns = 1, cellSide = 700)
            .toPngBytes() to
            "ascent=${"%.1f".format(layout.ascent)} descent=${"%.1f".format(layout.descent)} " +
            "baseline gap=${"%.1f".format(gap)}"
    }

    /** Arc-length reparameterisation: the same string bent onto a circle and onto an S. */
    private fun panelOnACurve(): Pair<ByteArray, String> {
        val text = "AROUND AND AROUND"
        val face = sans.atSize(74.0)
        val straight = layoutText(text, face, tracking = 6.0)
        val circle = textCircle(radius = straight.advanceWidth / (2 * Math.PI), clockwise = true)
        val onCircle = straight.onCurve(circle, align = TextAlign.CENTER)

        val wave = fline {
            moveTo(0.0, 0.0)
            cubicTo(300.0, -220.0, 700.0, 220.0, 1000.0, 0.0)
        }
        val onWave = layoutText("a rolling line of type", face, tracking = 2.0).onCurve(wave, align = TextAlign.CENTER)

        val ci = rasterizeText(onCircle, 460, 460)
        val wi = rasterizeText(onWave, 700, 300)
        check(ci.inkFraction() > 0.02) { "the roundel came out blank" }
        check(wi.inkFraction() > 0.02) { "the wave came out blank" }
        // The letters really turned: a straight layout has every rotation at zero.
        val turned = onCircle.glyphs.count { Math.abs(it.rotation) > 0.05 }
        check(turned >= onCircle.glyphs.size - 2) {
            "only $turned of ${onCircle.glyphs.size} glyphs rotated — onCurve is not setting the handles"
        }
        return montage(
            listOf(
                "on a circle (${onCircle.glyphs.size} glyphs rotated)" to ci,
                "on a cubic" to wi,
            ),
            columns = 2, cellSide = 460
        ).toPngBytes() to
            "circle length=${"%.1f".format(circle.arcLength())}, " +
            "layout width=${"%.1f".format(straight.advanceWidth)}, $turned glyphs rotated"
    }

    /** The handles as animation: per-glyph rotation and scale from a travelling wave. */
    private fun panelKinetic(): Pair<ByteArray, String> {
        val frames = (0 until 4).map { fi ->
            val phase = fi * Math.PI / 2
            val l = layoutText("KINETIC", sans.atSize(130.0), tracking = 14.0)
            l.eachGlyph { i ->
                val w = phase + i * 0.7
                rotation = 0.45 * sin(w)
                scale = vec(1.0 + 0.25 * cos(w), 1.0 + 0.25 * cos(w))
                offset = vec(0.0, 28.0 * sin(w * 1.3))
            }
            l
        }
        // One shared window, so the four frames are comparable rather than each self-fitted.
        val window = frames.map { it.bounds }.reduce { a, b -> a.union(b) }
        val imgs = frames.mapIndexed { i, l -> "frame $i" to rasterizeText(l, 520, 260, window = window) }
        val movement = imgs.zipWithNext().sumOf { (a, b) ->
            var d = 0.0
            for (y in 0 until a.second.height) for (x in 0 until a.second.width) {
                d += Math.abs(a.second.at(x, y, 0) - b.second.at(x, y, 0)).toDouble()
            }
            d / (a.second.width * a.second.height)
        }
        check(movement > 0.05) { "the four frames are nearly identical ($movement) — the handles did nothing" }
        return montage(imgs, columns = 2, cellSide = 520).toPngBytes() to
            "mean frame-to-frame change = ${"%.4f".format(movement)} per pixel over 3 steps"
    }

    /** Multi-line: measured word wrap, then the three alignments over the same wrapped text. */
    private fun panelParagraph(): Pair<ByteArray, String> {
        val face = serif.atSize(46.0)
        val prose =
            "Type is geometry that happens to be legible, and legibility is the only test that " +
                "cannot be faked by a plausible looking picture."
        val wrapped = wrapText(prose, face, maxWidth = 560.0)
        val lineCount = wrapped.lines().size
        check(lineCount in 3..8) { "wrap produced $lineCount lines, which is not a paragraph" }
        val widths = wrapped.lines().map { textWidth(it, face) }
        check(widths.all { it <= 560.0 + 1e-6 }) { "a wrapped line is wider than the limit: ${widths.max()}" }
        val panels = TextAlign.entries.map { align ->
            val l = layoutText(wrapped, face, align = align, lineSpacing = 1.12)
            align.name.lowercase() to rasterizeText(l, 620, 320)
        }
        return montage(panels, columns = 3, cellSide = 420).toPngBytes() to
            "$lineCount lines, widest ${"%.1f".format(widths.max())} of 560 allowed"
    }

    /** The bridge to every point-consuming family: outline samples, even by arc length. */
    private fun panelPoints(): Pair<ByteArray, String> {
        val layout = layoutText("dots", sans.atSize(200.0))
        val coarse = layout.samplePoints(spacing = 16.0)
        val fine = layout.samplePoints(spacing = 4.0)
        // Halving the spacing twice should roughly quadruple the count: the honest check that the
        // resampler is walking arc length rather than emitting one point per stored vertex.
        val ratio = fine.size.toDouble() / coarse.size
        check(ratio > 3.0 && ratio < 5.0) { "4x finer spacing gave ${"%.2f".format(ratio)}x the points" }
        val window = layout.bounds
        val a = dotRaster(coarse, window, 460, 260, 2)
        val b = dotRaster(fine, window, 460, 260, 1)
        return montage(
            listOf("spacing 16 (${coarse.size} points)" to a, "spacing 4 (${fine.size} points)" to b),
            columns = 1, cellSide = 460
        ).toPngBytes() to "${coarse.size} -> ${fine.size} points for 4x finer spacing (${"%.2f".format(ratio)}x)"
    }

    /**
     * Extrusion, with the topology printed.
     *
     * `cullBackFaces` is on in `MeshView`, so an inside-out wall is a *missing* wall here — the same
     * failure a GPU would show. `o` must be genus 1 and `l` genus 0; that is a fact about the
     * counters surviving as holes all the way into the triangulation.
     */
    private fun panelMesh(): Pair<ByteArray, String> {
        val face = sans.atSize(160.0)
        val o = layoutText("o", face).toMesh(depth = 44.0, centre = true)
        val l = layoutText("l", face).toMesh(depth = 44.0, centre = true)
        val word = layoutText("mesh", sans.atSize(120.0)).toMesh(depth = 34.0, centre = true)

        check(o.isWatertight()) { "extruded 'o' is not watertight" }
        check(l.isWatertight()) { "extruded 'l' is not watertight" }
        check(o.genus() == 1) { "extruded 'o' has genus ${o.genus()}, expected 1" }
        check(l.genus() == 0) { "extruded 'l' has genus ${l.genus()}, expected 0" }
        check(o.signedVolume() > 0) { "extruded 'o' is inside out (signed volume ${o.signedVolume()})" }

        // distance is in bounding-sphere radii, so a tall thin `l` and a squat `o` frame alike; 3.2
        // rather than the 2.7 default because the wireframe needs the room to be countable.
        val view = MeshView(yawDegrees = 26.0, pitchDegrees = 16.0, width = 460, height = 380, distance = 3.2)
        // flatShaded() only at the render boundary: it un-welds every triangle, so the topology
        // asserted above is a fact about `o`/`l`/`word` and not about what went to the rasteriser.
        val panels = listOf(
            "o — genus ${o.genus()}, chi ${o.eulerCharacteristic()}" to pngToImg(o.flatShaded().renderPng(view)),
            "l — genus ${l.genus()}, chi ${l.eulerCharacteristic()}" to pngToImg(l.flatShaded().renderPng(view)),
            "mesh — ${word.triangleCount} triangles" to pngToImg(
                word.flatShaded().renderPng(view.copy(width = 940, height = 380, distance = 2.6))
            ),
        )
        return montage(panels.take(2), columns = 2, cellSide = 460).let { top ->
            val bottom = panels[2].second
            stack(top, bottom).toPngBytes()
        } to "o: genus ${o.genus()}, volume ${"%.0f".format(o.volume())}; l: genus ${l.genus()}, " +
            "volume ${"%.0f".format(l.volume())}; word: ${word.triangleCount} tris, watertight=${word.isWatertight()}"
    }

    /** The SDF bridge: the 2D distance grid, and the surface `marchingCubes` finds in it. */
    private fun panelSdf(): Pair<ByteArray, String> {
        val layout = layoutText("SDF", sans.atSize(150.0))
        val sdf = layout.toSdf(depth = 0.22, gridResolution = 300)
        val marched = sdf.march(resolution = 128)
        check(marched.triangleCount > 500) { "marched only ${marched.triangleCount} triangles" }
        val field = sdf.distanceField
        check(field.deepestInside() > 5.0) { "the distance field never gets far inside the ink" }

        // A composed field, which is the point of having letters in the SDF family at all.
        val blended = sdfSmoothUnion(
            sdf.field,
            sdfSphere(0.55, vec(0.0, 0.0, 0.0)),
            0.16
        )
        val melted = marchingCubes(blended, sdf.bounds, 128)

        val view = MeshView(yawDegrees = 18.0, pitchDegrees = 12.0, width = 440, height = 340, distance = 2.5)
        return montage(
            listOf(
                "2D signed distance grid (${field.nx}x${field.ny})" to field.toImg(),
                "marchingCubes (${marched.triangleCount} tris)" to pngToImg(marched.renderPng(view)),
                "sdfSmoothUnion with a sphere" to pngToImg(melted.renderPng(view)),
            ),
            columns = 3, cellSide = 440
        ).toPngBytes() to
            "grid ${field.nx}x${field.ny}, deepest inside ${"%.1f".format(field.deepestInside())} units, " +
            "marched ${marched.triangleCount} tris, blended ${melted.triangleCount} tris"
    }

    /**
     * Motion, measured rather than asserted by eye: text marching along a fixed curve.
     *
     * A still of a moving thing proves nothing about the moving, so this panel sweeps `startOffset`
     * by a fixed `dL` for 90 frames and *measures* what each glyph did. Two failure modes that no
     * still can show are what it is looking for:
     *
     *  - **crawl** — glyphs travelling at the wrong speed, i.e. mean displacement per frame that is
     *    not `dL`;
     *  - **jitter** — glyphs stalling and then jumping because `FLine.cursor()`'s arc-length table is
     *    a polyline through the cubic and its sample spacing beats against `dL`.
     *
     * The figure is the glyph pivots' traces over those 90 frames, which is a still *of* the motion:
     * even bands mean even spacing frame after frame, and a bunched or gappy band would be the
     * jitter. The numbers below the picture are the real check.
     */
    private fun panelMotionMarch(): Pair<ByteArray, String> {
        val face = Typefaces.named("Monospaced", 46.0)
        val layout = layoutText("MARCHING ALONG", face, tracking = 3.0)
        val path = fline {
            moveTo(0.0, 0.0)
            // Deliberately uneven speed: a cubic whose control points are bunched at one end covers
            // very different arc length per unit parameter along its length. Arc-length placement has
            // to undo exactly that, so it is the honest curve to march along.
            cubicTo(80.0, -260.0, 900.0, 260.0, 1000.0, 0.0)
        }
        val length = path.arcLength()
        val frames = 90
        val dL = (length - layout.advanceWidth) / (frames - 1)
        check(dL > 0.5) { "the path is too short to march along: $length vs ${layout.advanceWidth}" }

        // Per frame, per glyph: where its pivot ended up.
        val tracks = ArrayList<List<Vec2>>(frames)
        repeat(frames) { f ->
            val bent = layout.onCurve(path, startOffset = f * dL, align = TextAlign.LEFT)
            tracks.add(bent.glyphs.map { g -> pivotOf(g) })
        }

        // Displacement per frame per glyph. Chord <= arc, and for dL this small the two agree to well
        // under a percent, so the mean is a speed check and the spread is a jitter check.
        val disp = ArrayList<Double>()
        for (f in 1 until frames) for (i in tracks[f].indices) {
            val a = tracks[f - 1][i]
            val b = tracks[f][i]
            disp.add(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()))
        }
        val mean = disp.average()
        val worst = disp.maxOf { kotlin.math.abs(it - dL) } / dL
        val stalls = disp.count { it < dL * 0.5 }
        val jumps = disp.count { it > dL * 1.5 }
        check(kotlin.math.abs(mean - dL) / dL < 0.02) { "glyphs crawled: mean step $mean against dL $dL" }
        check(stalls == 0 && jumps == 0) { "$stalls stalls and $jumps jumps out of ${disp.size} steps — jitter" }
        check(worst < 0.10) { "worst per-step error ${pct(worst)} of dL — the arc-length table is beating against dL" }

        // The trace figure: every pivot of every frame as a dot, over the path itself.
        val dots = tracks.flatten()
        val img = dotRaster(dots, boundsOfAll(dots + pathPoints(path)), 900, 420, 0)
        val pathImg = rasterizeFLinesFramed(
            listOf(path), listOf(path) + listOf(fline { moveTo(0.0, 0.0); lineTo(0.0, 0.0) }), 900, 420, margin = 6
        )
        return montage(
            listOf(
                "90 frames of pivot positions — even bands mean even spacing" to img,
                "the curve they were marching along" to pathImg,
            ),
            columns = 1, cellSide = 900
        ).toPngBytes() to
            "dL=${"%.3f".format(dL)}, mean step ${"%.3f".format(mean)} (${pct(kotlin.math.abs(mean - dL) / dL)} off), " +
            "worst step error ${pct(worst)}, 0 stalls, 0 jumps over ${disp.size} steps"
    }

    /**
     * Motion, measured: the *curve* moving under the text.
     *
     * The harder half of "does it animate coherently". Marching along a fixed path only exercises one
     * arc-length table; morphing the path rebuilds the table every frame, and a table whose knots move
     * is exactly where a glyph can jump. So this sweeps a control point through 90 frames and asks
     * whether each glyph's position is *continuous* — which is a property of the sequence and cannot
     * be read off any one frame.
     *
     * The threshold is relative to the motion actually observed (`3 x median step`), not a magic
     * number: a jump is a step that is wildly out of line with its neighbours, and how big a normal
     * step is depends on how fast the control point is being swept.
     */
    private fun panelMotionMorph(): Pair<ByteArray, String> {
        val face = Typefaces.named("SansSerif", 44.0)
        val layout = layoutText("a curve that will not hold still", face, tracking = 1.0)
        val frames = 90
        fun pathAt(phase: Double): FLine {
            // One control point swept up and down; the other end pinned, so the arc length changes and
            // the table is genuinely rebuilt rather than translated.
            val lift = -300.0 * sin(phase)
            return fline {
                moveTo(0.0, 0.0)
                cubicTo(260.0, lift, 640.0, -lift, 940.0, 0.0)
            }
        }

        val tracks = ArrayList<List<Vec2>>(frames)
        val lengths = ArrayList<Double>(frames)
        repeat(frames) { f ->
            val phase = 2 * Math.PI * f / frames
            val path = pathAt(phase)
            lengths.add(path.arcLength())
            tracks.add(layout.onCurve(path, align = TextAlign.CENTER).glyphs.map { g -> pivotOf(g) })
        }

        val steps = ArrayList<Double>()
        for (f in 1 until frames) for (i in tracks[f].indices) {
            val a = tracks[f - 1][i]
            val b = tracks[f][i]
            steps.add(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()))
        }
        val median = steps.sorted()[steps.size / 2]
        val worst = steps.max()
        val ratio = worst / median
        check(median > 0.2) { "nothing moved: median step $median" }
        check(ratio < 3.0) { "a glyph jumped ${"%.1f".format(ratio)}x the median step — the motion is not continuous" }

        // And the rotations must be continuous too: a tangent that flips sign between frames is the
        // other way this looks broken, and it would not show up in position alone.
        var worstTurn = 0.0
        for (f in 1 until frames) {
            val prev = layout.onCurve(pathAt(2 * Math.PI * (f - 1) / frames), align = TextAlign.CENTER)
            val now = layout.onCurve(pathAt(2 * Math.PI * f / frames), align = TextAlign.CENTER)
            prev.glyphs.indices.forEach { i ->
                worstTurn = max(worstTurn, kotlin.math.abs(now.glyphs[i].rotation - prev.glyphs[i].rotation))
            }
        }
        check(worstTurn < 0.35) { "a glyph turned ${"%.3f".format(worstTurn)} rad in one frame — the tangent flipped" }

        val window = boundsOfAll(tracks.flatten())
        val shown = listOf(0, frames / 6, frames / 3, frames / 2).map { f ->
            val path = pathAt(2 * Math.PI * f / frames)
            "frame $f — path length ${"%.0f".format(lengths[f])}" to
                rasterizeText(layout.onCurve(path, align = TextAlign.CENTER), 620, 320, window = window)
        }
        return montage(shown, columns = 2, cellSide = 620).toPngBytes() to
            "median step ${"%.2f".format(median)}, worst ${"%.2f".format(worst)} (${"%.2f".format(ratio)}x median), " +
            "worst one-frame turn ${"%.3f".format(worstTurn)} rad; arc length swept " +
            "${"%.0f".format(lengths.min())}..${"%.0f".format(lengths.max())}"
    }

    // ------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------

    /** Where a placed glyph's rotation/scale centre ended up, in layout space. */
    private fun pivotOf(g: PlacedGlyph): Vec2 {
        val p = g.resolvedPivot
        val d = g.transform().transform(java.awt.geom.Point2D.Double(p.x.toDouble(), p.y.toDouble()), null)
        return vec(d.x, d.y)
    }

    private fun boundsOfAll(points: List<Vec2>): Rect {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        points.forEach {
            minX = min(minX, it.x.toDouble()); maxX = max(maxX, it.x.toDouble())
            minY = min(minY, it.y.toDouble()); maxY = max(maxY, it.y.toDouble())
        }
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }

    private fun pathPoints(path: FLine): List<Vec2> =
        path.nodes.map { vec(it.to.x, it.to.y) }

    private fun pct(x: Double) = "%.1f%%".format(x * 100)

    /** The layout's rings as polylines — `rasterizeFLines` walks nodes, so cubics must be flattened. */
    private fun polylinesOf(layout: TextLayout): List<FLine> = layout.toContours().map { it.toPolyline() }


    private fun dotRaster(points: List<Vec2>, window: Rect, w: Int, h: Int, radius: Int): Img {
        val img = Img.zeros(w, h, 1)
        val margin = 0.06
        val scale = min(w * (1 - 2 * margin) / window.w, h * (1 - 2 * margin) / window.h)
        val offX = w / 2.0 - (window.x + window.w / 2) * scale
        val offY = h / 2.0 - (window.y + window.h / 2) * scale
        points.forEach { p ->
            val px = (p.x * scale + offX).toInt()
            val py = (p.y * scale + offY).toInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val x = px + dx
                val y = py + dy
                if (x in 0 until w && y in 0 until h) img.data[img.index(x, y, 0)] = 1f
            }
        }
        return img
    }

    /** Decode PNG bytes back into an [Img] so a mesh render can share a [montage] with a raster. */
    private fun pngToImg(png: ByteArray): Img = imgFromBytes(png, 3)

    /** Two images, one above the other, centred, on the montage background. */
    private fun stack(top: Img, bottom: Img): Img {
        val t = top.toRGB()
        val b = bottom.toRGB()
        val w = max(t.width, b.width)
        val out = Img.filled(w, t.height + b.height + 10, 3, 0.08f)
        fun blit(src: Img, y0: Int) {
            val x0 = (w - src.width) / 2
            for (y in 0 until src.height) for (x in 0 until src.width) for (c in 0 until 3) {
                out.data[out.index(x0 + x, y0 + y, c)] = src.data[src.index(x, y, c)]
            }
        }
        blit(t, 0)
        blit(b, t.height + 10)
        return out
    }

    // ------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        // The gallery touches the AWT font manager and nothing else in AWT; headless keeps it away
        // from AppKit entirely, which is the documented hazard (imaging_field.kt:417).
        System.setProperty("java.awt.headless", "true")
        val outDir = File(
            args.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?: System.getenv("TYPOGRAPHY_GALLERY_DIR")?.takeIf { it.isNotBlank() }
                ?: java.nio.file.Files.createTempDirectory("typography-gallery").toFile().absolutePath
        )
        outDir.mkdirs()

        Typefaces.warm()
        println("typography: ${Typefaces.available().size} font families visible; sans = $sans, serif = $serif")

        var failures = 0
        for (panel in PANELS) {
            try {
                val started = System.nanoTime()
                val (png, summary) = panel.build()
                check(png.size > 1000) { "${panel.name} produced only ${png.size} PNG bytes" }
                val file = File(outDir, "${panel.name}.png")
                file.writeBytes(png)
                val ms = (System.nanoTime() - started) / 1_000_000
                println("wrote ${file.absolutePath} (${png.size} bytes, ${ms}ms) — $summary")
            } catch (t: Throwable) {
                failures++
                System.err.println("[error] panel ${panel.name} failed: ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace(System.err)
            }
        }
        if (failures > 0) {
            System.err.println("TypographyGallery FAILED: $failures of ${PANELS.size} panels")
            exitProcess(1)
        }
        println("typography gallery rendered to ${outDir.absolutePath} (${PANELS.size} panels)")
    }
}
