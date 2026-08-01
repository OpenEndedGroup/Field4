package afield.stdlib

import afield.Rect
import afield.graphics.FLineSamplers
import afield.graphics.sampleSeries
import afield.graphics.signedArea2D
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What `typography.kt` and `typography_field.kt` promise, checked against facts that are true of
 * letters rather than of this implementation.
 *
 * The trap this file is written to avoid is the assertion whose two sides are the same expression. So
 * the load-bearing checks here are all *cross-representation* or *control-armed*:
 *
 *  - [holesAreNegativeAndOutersArePositive] compares a ring's nesting depth (from point-in-polygon
 *    containment) against the sign of its signed area (from the shoelace formula). Two independent
 *    computations that the winding convention forces to agree.
 *  - [extrudedGenusEqualsHoleCount] compares a 2D count (how many rings nested at odd depth) against
 *    a 3D one (Euler characteristic over the extruded mesh's edges and faces). `o` is genus 1 because
 *    its counter survived all the way into the triangulation.
 *  - [extrudedVolumeMatchesInkAreaTimesDepth] compares a 2D shoelace sum against the divergence
 *    theorem over triangles. A dropped cap triangle, a misoriented wall or an unpunched hole all
 *    break it.
 *  - [distanceGridAgreesWithBruteForceDistance] compares a bilinearly sampled grid against the exact
 *    nearest-point-on-segment distance.
 *  - [countersNeedTheWindingConvention], [trackingWidensByExactlyOneGapPerGap] and
 *    [boldInksMoreThanRegular] are control arms: the same code with one thing switched.
 *
 * Face-independence: the logical families (`SansSerif`, `Serif`) resolve to whatever the platform
 * installed, so nothing here asserts a specific advance width or a specific hole count for a word.
 * `o` has one counter and `l` has none in every Latin face, and that is the level these assertions
 * stay at.
 */
class TypographyTest {

    private val sans: Typeface get() = Typefaces.named("SansSerif", 100.0)
    private val serif: Typeface get() = Typefaces.named("Serif", 100.0)

    // ==========================================================================================
    // Outlines, nesting and winding
    // ==========================================================================================

    /**
     * The convention itself, over a wide sample: depth parity and area sign must agree for every ring.
     *
     * Depth comes from containment tests between rings; the sign comes from the shoelace formula over
     * the ring's own points. Neither is derived from the other, so this is a real constraint —
     * dropping the reversal step in `extractGlyph` fails it on the first glyph with a counter.
     */
    @Test
    fun holesAreNegativeAndOutersArePositive() {
        var rings = 0
        var holes = 0
        for (face in listOf(sans, serif, sans.withStyle(bold = true))) {
            for (c in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789%&@#") {
                val g = face.glyph(c)
                g.contours.forEach { ring ->
                    rings++
                    if (ring.isHole) {
                        holes++
                        assertTrue(
                            ring.signedArea < 0.0,
                            "'$c' hole at depth ${ring.depth} has area ${ring.signedArea}, expected negative"
                        )
                    } else {
                        assertTrue(
                            ring.signedArea > 0.0,
                            "'$c' outer at depth ${ring.depth} has area ${ring.signedArea}, expected positive"
                        )
                    }
                }
            }
        }
        assertTrue(rings > 150, "only $rings rings extracted across three faces — extraction is failing")
        assertTrue(holes > 30, "only $holes holes across three faces — nesting is not being detected")
    }

    /** `o`, `e`, `a`, `b`, `d`, `p`, `q`, `O`, `D` have counters; `l`, `v`, `w`, `x`, `z`, `/` do not. */
    @Test
    fun counterBearingGlyphsHaveHolesAndTheRestDoNot() {
        for (face in listOf(sans, serif)) {
            for (c in "oeabdpqOD") {
                assertTrue(face.glyph(c).holeCount >= 1, "'$c' in $face has no counter")
            }
            for (c in "lvwxz/") {
                assertEquals(0, face.glyph(c).holeCount, "'$c' in $face should have no counter")
            }
            // Two counters, in every Latin face that has the glyph at all.
            assertEquals(2, face.glyph('8').holeCount, "'8' in $face should have two counters")
            assertEquals(2, face.glyph('B').holeCount, "'B' in $face should have two counters")
        }
    }

    /** Every hole sits geometrically inside an outer ring of the same glyph — depth is not a guess. */
    @Test
    fun everyHoleIsInsideAnOuterRingOfItsGlyph() {
        for (c in "oeabB8%gÆ") {
            val g = serif.glyph(c)
            g.contours.filter { it.isHole }.forEach { hole ->
                val probe = hole.points[0]
                val container = g.contours.filter { it.isOuter && it.contains(probe) }.getOrNull(0)
                assertNotNull(container, "'$c' has a hole with no containing outer ring")
            }
        }
    }

    /**
     * A counter really removes area: an `o`'s ink is strictly less than the area its rings enclose
     * unsigned, and an `l`'s is exactly equal because it has nothing to remove.
     *
     * The control arm for [Glyph.inkArea] — a threshold-free comparison of two glyphs.
     */
    @Test
    fun inkAreaSubtractsCountersAndLeavesSolidGlyphsAlone() {
        val o = sans.glyph('o')
        val l = sans.glyph('l')
        val oUnsigned = o.contours.sumOf { abs(it.signedArea) }
        val lUnsigned = l.contours.sumOf { abs(it.signedArea) }
        assertTrue(o.inkArea > 0.0, "'o' has non-positive ink area ${o.inkArea}")
        assertTrue(
            o.inkArea < oUnsigned * 0.95,
            "'o' ink ${o.inkArea} is not meaningfully less than its unsigned ring area $oUnsigned"
        )
        assertEquals(lUnsigned, l.inkArea, lUnsigned * 1e-9, "'l' has no counter, so ink == unsigned area")
    }

    /**
     * The two representations agree: [Glyph.outline]'s subpaths are the same rings, in the same order,
     * wound the same way.
     *
     * Read back through `FLine.pieces()` and re-measured, so a reversal applied to the polygon but not
     * to the cubics — the easy bug in `extractGlyph` — fails here.
     */
    @Test
    fun outlineSubpathsMatchTheClassifiedContours() {
        for (c in "oaeB8glW") {
            val g = serif.glyph(c)
            val pieces = g.outline.pieces()
            assertEquals(g.contours.size, pieces.size, "'$c': ${pieces.size} subpaths for ${g.contours.size} rings")
            g.contours.forEachIndexed { i, ring ->
                val area = pieces[i].signedArea2D().toDouble()
                assertTrue(
                    (area > 0) == (ring.signedArea > 0),
                    "'$c' ring $i: polygon area ${ring.signedArea} but outline subpath area $area"
                )
            }
        }
    }

    /** Whitespace advances the pen and inks nothing. */
    @Test
    fun blankGlyphsHaveAnAdvanceAndNoContours() {
        val space = sans.glyph(' ')
        assertTrue(space.isBlank, "a space should be blank")
        assertEquals(0, space.contourCount)
        assertTrue(space.advance > 0.0, "a space should still advance, got ${space.advance}")
    }

    /** The cache is keyed by glyph code, so a repeated letter costs nothing new. */
    @Test
    fun repeatedLettersReuseTheirExtractedOutline() {
        val face = Typefaces.named("Monospaced", 71.0)
        layoutText("mississippi", face)
        val after = Typefaces.cachedGlyphCount
        layoutText("mississippi", face)
        layoutText("simp", face)
        assertEquals(after, Typefaces.cachedGlyphCount, "laying out the same letters again grew the cache")
    }

    // ==========================================================================================
    // Layout
    // ==========================================================================================

    /**
     * With kerning off and no tracking, the line's width is the sum of its glyphs' advances.
     *
     * Two AWT paths that must agree: `getGlyphPosition(n)` accumulates internally, `getGlyphMetrics`
     * reports per glyph. It is also the check that layout is not silently rounding.
     */
    @Test
    fun advanceWidthIsTheSumOfGlyphAdvancesWithoutKerning() {
        val face = sans.withKerning(false)
        val layout = layoutText("minimum", face)
        val sum = layout.glyphs.sumOf { it.glyph.advance }
        assertEquals(sum, layout.advanceWidth, 1e-3, "advance width disagrees with the sum of advances")
    }

    /**
     * Tracking adds one gap per *inter-glyph* space, not one per glyph.
     *
     * The layout accumulates `i * tracking` glyph by glyph; the assertion multiplies once. Off-by-one
     * on the trailing gap — the mistake this API invites — shows up as exactly one extra `t`.
     */
    @Test
    fun trackingWidensByExactlyOneGapPerGap() {
        val t = 9.5
        val tight = layoutText("HAMBURGEFONS", sans, tracking = 0.0)
        val loose = layoutText("HAMBURGEFONS", sans, tracking = t)
        val gaps = tight.glyphs.size - 1
        assertEquals(
            gaps * t, loose.advanceWidth - tight.advanceWidth, 1e-6,
            "tracking $t over $gaps gaps did not widen the line by exactly ${gaps * t}"
        )
        // And it went between the letters rather than scaling them: the glyphs are untouched.
        assertEquals(tight.glyphs[0].glyph.advance, loose.glyphs[0].glyph.advance, 1e-9)
    }

    /** Kerning can only pull letters together, and this string is chosen so that it does. */
    @Test
    fun kerningNeverWidensAndHereItNarrows() {
        val on = layoutText("AVATAR Wa To LT", sans.withKerning(true))
        val off = layoutText("AVATAR Wa To LT", sans.withKerning(false))
        assertTrue(
            on.advanceWidth <= off.advanceWidth + 1e-6,
            "kerning widened the line: ${on.advanceWidth} > ${off.advanceWidth}"
        )
        // Not an assertion about the platform's font: a face with no `kern` table gives equal widths
        // and the inequality above is then the whole claim. What IS asserted is that the attribute
        // reached the font, so a silently dropped deriveFont cannot masquerade as "no kern table".
        assertTrue(on.typeface.kerning, "kerning = true did not reach the AWT font")
        assertTrue(!off.typeface.kerning, "kerning = false did not reach the AWT font")

        // And then the real check, on whichever installed face does kern: the widths must differ, and
        // the difference must be a whole number of AVA/Wa/To pairs' worth rather than noise. If no
        // installed family kerns at all this reports that instead of pretending.
        val kerners = Typefaces.available().asSequence()
            .map { Typefaces.named(it, 100.0) }
            .map { face ->
                Triple(
                    face.familyName,
                    layoutText("AVATAR Wa To LT", face.withKerning(false)).advanceWidth,
                    layoutText("AVATAR Wa To LT", face.withKerning(true)).advanceWidth,
                )
            }
            .filter { (_, offW, onW) -> offW - onW > 0.5 }
            .take(3)
            .toList()
        if (kerners.isEmpty()) {
            println("TypographyTest: no installed family applied kerning to 'AVATAR Wa To LT' — checked ${Typefaces.available().size} families")
        } else {
            kerners.forEach { (name, offW, onW) ->
                assertTrue(onW < offW, "$name: kerning widened the line")
                // A whole line's worth of kerning is small: pulling 'AV', 'Wa', 'To' and 'LT'
                // together is single-digit percent at 100pt, never a third of the line.
                assertTrue(
                    (offW - onW) / offW < 0.15,
                    "$name: kerning changed the width by ${(offW - onW) / offW} — that is not kerning"
                )
                println("TypographyTest: $name kerns 'AVATAR Wa To LT' from $offW to $onW")
            }
        }
    }

    /** `\n` makes baselines, spaced by the typeface's own line height times the multiplier. */
    @Test
    fun baselinesAreOneScaledLineHeightApart() {
        val spacing = 1.35
        val layout = layoutText("one\ntwo\nthree", serif, lineSpacing = spacing)
        assertEquals(3, layout.lines.size)
        val expected = serif.lineHeight * spacing
        for (i in 1 until layout.lines.size) {
            assertEquals(
                expected, layout.lines[i].baselineY - layout.lines[i - 1].baselineY, 1e-6,
                "baseline gap $i is not ${serif.lineHeight} * $spacing"
            )
        }
        assertEquals(0.0, layout.lines[0].baselineY, 1e-9, "the first baseline is the origin")
    }

    /**
     * Alignment moves lines and changes nothing else.
     *
     * The per-line advance widths are identical across all three alignments — only the first glyph's x
     * differs, and it differs in the order LEFT < CENTER < RIGHT for a short line beside a long one.
     */
    @Test
    fun alignmentShiftsShortLinesAndLeavesTheirWidthsAlone() {
        val text = "a very much longer line\nshort"
        val byAlign = TextAlign.entries.associateWith { layoutText(text, sans, align = it) }
        val widths = byAlign.mapValues { (_, l) -> l.lines.map { it.advanceWidth } }
        assertEquals(widths[TextAlign.LEFT], widths[TextAlign.CENTER], "alignment changed a line's width")
        assertEquals(widths[TextAlign.LEFT], widths[TextAlign.RIGHT], "alignment changed a line's width")

        val firstXOfShortLine = byAlign.mapValues { (_, l) -> l.lines[1].glyphs.first().origin.x.toDouble() }
        assertEquals(0.0, firstXOfShortLine[TextAlign.LEFT]!!, 1e-6)
        assertTrue(
            firstXOfShortLine[TextAlign.LEFT]!! < firstXOfShortLine[TextAlign.CENTER]!! &&
                firstXOfShortLine[TextAlign.CENTER]!! < firstXOfShortLine[TextAlign.RIGHT]!!,
            "the short line did not move left -> centre -> right: $firstXOfShortLine"
        )
        // Centring is centring: the slack is split evenly.
        val slack = byAlign[TextAlign.CENTER]!!.advanceWidth - byAlign[TextAlign.CENTER]!!.lines[1].advanceWidth
        assertEquals(slack / 2, firstXOfShortLine[TextAlign.CENTER]!!, 1e-4)
    }

    /** Wrap respects the limit, and a wider limit needs no more lines. */
    @Test
    fun wrapStaysUnderTheLimitAndUsesFewerLinesWhenGivenRoom() {
        val face = serif.atSize(40.0)
        val prose = "the quick brown fox jumps over the lazy dog and then keeps going for a while yet"
        val narrow = wrapText(prose, face, maxWidth = 300.0)
        val wide = wrapText(prose, face, maxWidth = 700.0)
        narrow.lines().forEach { assertTrue(textWidth(it, face) <= 300.0 + 1e-6, "'$it' is wider than 300") }
        wide.lines().forEach { assertTrue(textWidth(it, face) <= 700.0 + 1e-6, "'$it' is wider than 700") }
        assertTrue(
            wide.lines().size < narrow.lines().size,
            "a wider limit did not use fewer lines (${wide.lines().size} vs ${narrow.lines().size})"
        )
        // No word was lost or invented.
        assertEquals(prose.split(" "), narrow.replace("\n", " ").split(" "))
    }

    // ==========================================================================================
    // Handles and text on a curve
    // ==========================================================================================

    /** A fresh layout has identity handles; that is what makes [PlacedGlyph.isUntransformed] usable. */
    @Test
    fun aFreshLayoutHasIdentityHandles() {
        val layout = layoutText("plain", sans)
        assertTrue(layout.glyphs.all { it.isUntransformed }, "a fresh layout already has handles set")
    }

    /**
     * `onCurve` writes handles into a *copy*.
     *
     * The property that lets the same layout be bent along several paths, or animated by sweeping
     * `startOffset`, without being consumed.
     */
    @Test
    fun onCurveLeavesTheOriginalLayoutAlone() {
        val straight = layoutText("bend me", sans)
        val bent = straight.onCurve(textCircle(120.0))
        assertTrue(straight.glyphs.all { it.isUntransformed }, "onCurve mutated its receiver")
        assertTrue(bent.glyphs.none { it.isUntransformed }, "onCurve did not set any handles")
    }

    /**
     * Bending is by arc length, not by curve parameter.
     *
     * A monospaced string of identical glyphs on a circle must land on evenly spaced points, so the
     * chord between consecutive glyph centres is constant. A parameter-space walk over a circle drawn
     * from line segments would still pass; over a *cubic* it would not, so the control arm is the
     * second half: the same string on an S-curve keeps the same *arc-length* spacing, which is what
     * the constant-chord test on the circle cannot distinguish on its own.
     */
    @Test
    fun onCurveSpacesGlyphsEvenlyByArcLength() {
        val face = Typefaces.named("Monospaced", 40.0)
        val text = "IIIIIIIIIIIIIIII"
        val straight = layoutText(text, face)
        val advance = straight.glyphs[0].glyph.advance

        val wave = fline {
            moveTo(0.0, 0.0)
            cubicTo(200.0, -260.0, 500.0, 260.0, 800.0, 0.0)
        }
        val bent = straight.onCurve(wave, align = TextAlign.LEFT)
        val centres = bent.glyphs.map { g ->
            val t = g.transform()
            val p = t.transform(java.awt.geom.Point2D.Double(g.glyph.advance / 2.0, 0.0), null)
            Vec2(p.x.toFloat(), p.y.toFloat())
        }
        val chords = centres.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }

        // The control arm. FLineSamplers.uniform() walks the same cubic in *parameter*, which on a
        // curve whose speed varies bunches its samples up; the arc-length placement should not. So
        // the claim is not "the gaps are near some number I chose" but "the gaps vary far less than
        // the parameter walk's do" — and the spread of the parameter walk is measured here, not
        // assumed.
        val byParameter = wave.sampleSeries(centres.size, FLineSamplers.uniform()).map { it.point }
        val paramGaps = byParameter.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
        fun spread(v: List<Double>) = (v.max() - v.min()) / v.average()
        val arcSpread = spread(chords)
        val paramSpread = spread(paramGaps)
        assertTrue(
            arcSpread < paramSpread / 4,
            "arc-length spacing varies by $arcSpread and the parameter walk by $paramSpread — not much better"
        )
        assertTrue(arcSpread < 0.02, "arc-length spacing still varies by $arcSpread across the curve")

        // And the spacing is the glyph's own advance, to within the arc-length table's flattening
        // tolerance — the table is a polyline through the cubic, so it under-measures the true arc by
        // a fraction of a percent and a chord can very slightly exceed the advance.
        assertEquals(advance, chords.average(), advance * 0.01, "mean chord is not the advance")
        // Every glyph turned with the curve, and the turn changes sign across the inflection.
        val angles = bent.glyphs.map { it.rotation }
        assertTrue(angles[0] < -0.2, "the first glyph should be climbing, got ${angles[0]}")
        assertTrue(angles.any { it > 0.2 }, "no glyph turned the other way across the inflection: $angles")
    }

    /** A closed path is closed: `onCurve` over a circle wraps without piling glyphs on one point. */
    @Test
    fun onCurveAroundACircleUsesTheWholeCircumference() {
        val face = sans.atSize(40.0)
        val straight = layoutText("ROUND AND ROUND AND", face)
        val radius = straight.advanceWidth / (2 * Math.PI)
        val bent = straight.onCurve(textCircle(radius), align = TextAlign.LEFT)
        val b = bent.bounds
        // The ink should fill an annulus, so its bounds are about a diameter across in both axes.
        val diameter = 2 * radius
        assertTrue(b.w > diameter * 0.85, "the roundel is only ${b.w} wide for a $diameter circle")
        assertTrue(b.h > diameter * 0.85, "the roundel is only ${b.h} tall for a $diameter circle")
        // And it is an annulus, not a disc: nothing near the centre.
        val rings = bent.toContours()
        val centre = Vec2((b.x + b.w / 2).toFloat(), (b.y + b.h / 2).toFloat())
        val nearest = rings.minOf { it.distanceTo(centre) }
        assertTrue(nearest > radius * 0.5, "ink reaches within $nearest of the centre of a $radius circle")
    }

    /** A mirrored glyph keeps its counters: the bridges re-normalise winding after a flipping affine. */
    @Test
    fun mirroringAGlyphKeepsItsCountersHoles() {
        val layout = layoutText("o", sans.atSize(120.0))
        layout.eachGlyph { scale = vec(-1.0, 1.0) }
        val rings = layout.toContours()
        assertEquals(2, rings.size, "'o' should still be two rings after mirroring")
        assertEquals(1, rings.count { it.isHole })
        rings.forEach { ring ->
            assertTrue(
                (ring.signedArea > 0) == ring.isOuter,
                "mirroring left a ${if (ring.isHole) "hole" else "outer"} ring with area ${ring.signedArea}"
            )
        }
        val mesh = layout.toMesh(depth = 30.0)
        assertEquals(1, mesh.genus(), "a mirrored 'o' extruded to genus ${mesh.genus()}")
    }

    // ==========================================================================================
    // Mesh bridge
    // ==========================================================================================

    /**
     * A 2D count and a 3D count of the same thing.
     *
     * [Glyph.holeCount] comes from ring nesting in the plane; `genus()` comes from counting the
     * extruded mesh's vertices, edges and faces. They agree only if every counter survived
     * triangulation as a real hole and every wall closed.
     */
    @Test
    fun extrudedGenusEqualsHoleCount() {
        for (c in listOf('o', 'l', 'B', '8', 'i', 'A', 'x')) {
            val layout = layoutText(c.toString(), sans.atSize(120.0))
            val mesh = layout.toMesh(depth = 30.0)
            assertTrue(mesh.isWatertight(), "extruded '$c' is not watertight")
            assertTrue(mesh.isManifold(), "extruded '$c' is not manifold")
            assertEquals(
                layout.holeCount, mesh.genus(),
                "'$c' has ${layout.holeCount} counters in 2D but genus ${mesh.genus()} in 3D"
            )
        }
    }

    /**
     * A 2D area and a 3D volume of the same thing.
     *
     * The ink area is a shoelace sum over the ring polygons; the volume is the divergence theorem over
     * the mesh's triangles. A dropped cap triangle, a wall wound the wrong way, or a counter left
     * filled all break the identity.
     */
    @Test
    fun extrudedVolumeMatchesInkAreaTimesDepth() {
        for (c in listOf('o', 'B', 'l', 'g')) {
            val depth = 17.0
            val layout = layoutText(c.toString(), serif.atSize(120.0))
            val mesh = layout.toMesh(depth = depth, yUp = false)
            val expected = layout.glyphs.sumOf { it.glyph.inkArea } * depth
            assertEquals(
                expected, mesh.volume(), expected * 1e-4,
                "'$c': ink area x depth = $expected but the mesh measures ${mesh.volume()}"
            )
            assertTrue(mesh.signedVolume() > 0, "'$c' extruded inside out (signed volume ${mesh.signedVolume()})")
        }
    }

    /** A whole word extrudes as one mesh with one component per outer ring. */
    @Test
    fun extrudingAWordKeepsEveryLetterSeparateAndWatertight() {
        val layout = layoutText("ohio", sans.atSize(90.0))
        val mesh = layout.toMesh(depth = 20.0)
        assertTrue(mesh.isWatertight(), "extruded 'ohio' is not watertight")
        // o, h, i (stem + tittle = two outers), o -> five closed shells, three of them with a counter.
        assertEquals(2, layout.holeCount, "'ohio' should have exactly two counters")
        assertEquals(2, mesh.genus(), "extruded 'ohio' should be genus 2, got ${mesh.genus()}")
    }

    /** `depth = 0` is a flat single-sided cap, and says so by not being watertight. */
    @Test
    fun zeroDepthGivesAFlatOpenCap() {
        val flat = layoutText("o", sans.atSize(100.0)).toMesh(depth = 0.0)
        assertTrue(flat.triangleCount > 0, "a flat cap has no triangles")
        assertTrue(!flat.isWatertight(), "a single-sided flat cap should not be watertight")
        val z = (0 until flat.vertexCount).map { flat.z(it) }
        assertEquals(0f, z.max() - z.min(), 1e-6f, "a flat cap should be planar")
    }

    // ==========================================================================================
    // Distance field and SDF bridge
    // ==========================================================================================

    /**
     * The grid against the exact answer.
     *
     * Brute-force nearest point over every segment of every ring, versus the bilinearly sampled grid.
     * The tolerance is one and a half cell diagonals, which is the interpolation error a bilinear
     * sample of a distance function is *entitled* to — anything larger means the sign test, the
     * segment loop or the bounding-box reject is wrong.
     */
    @Test
    fun distanceGridAgreesWithBruteForceDistance() {
        val layout = layoutText("Bo", serif.atSize(120.0))
        val field = layout.distanceField(resolution = 220, margin = 0.2)
        val rings = layout.toContours()
        val cell = hypot(field.bounds.w / field.nx, field.bounds.h / field.ny)
        val rng = java.util.Random(4)
        var checked = 0
        repeat(400) {
            val x = field.bounds.x + rng.nextDouble() * field.bounds.w
            val y = field.bounds.y + rng.nextDouble() * field.bounds.h
            val p = Vec2(x.toFloat(), y.toFloat())
            val exact = rings.minOf { it.distanceTo(p) }
            val sampled = abs(field.at(x, y))
            assertTrue(
                abs(exact - sampled) < 1.5 * cell,
                "at ($x, $y): exact $exact vs grid $sampled, cell $cell"
            )
            checked++
        }
        assertEquals(400, checked)
    }

    /** Negative inside, positive outside, with the sides identified independently of the field. */
    @Test
    fun distanceFieldIsNegativeInsideTheInkAndPositiveOutside() {
        val layout = layoutText("l", sans.atSize(160.0))
        val field = layout.distanceField(resolution = 160)
        val ink = layout.bounds
        // Dead centre of an `l`'s stem is inside by construction — an `l` is a rectangle.
        val inside = field.at(ink.cx, ink.cy)
        assertTrue(inside < 0.0, "the centre of an 'l' stem sampled $inside, expected negative")
        val outside = field.at(field.bounds.x + 1.0, field.bounds.y + 1.0)
        assertTrue(outside > 0.0, "a corner of the padded bounds sampled $outside, expected positive")
        // The deepest point inside cannot be deeper than half the stem's width.
        assertTrue(
            field.deepestInside() <= ink.w / 2 + 1e-6,
            "deepest inside ${field.deepestInside()} exceeds half the ${ink.w}-wide stem"
        )
    }

    /** The SDF's zero level set is where the ink boundary is, and marching it recovers the letter. */
    @Test
    fun marchingTheTextSdfRecoversTheLetterAndItsCounter() {
        val layout = layoutText("o", sans.atSize(120.0))
        val sdf = layout.toSdf(depth = 0.3, gridResolution = 260)
        val mesh = sdf.march(resolution = 110)
        assertTrue(mesh.triangleCount > 1000, "marched only ${mesh.triangleCount} triangles")
        // The counter is a hole through the marched surface too — same claim as the extruder's, by a
        // completely different route (a grid and an iso-surface rather than a triangulated polygon).
        assertEquals(1, mesh.genus(), "marched 'o' has genus ${mesh.genus()}, expected 1")
        // The extrusion depth survived: the z extent is the requested thickness, within a cell.
        val zs = (0 until mesh.vertexCount).map { mesh.z(it) }
        assertEquals(0.3, (zs.max() - zs.min()).toDouble(), 0.02, "the marched surface is not 0.3 thick")
    }

    /** Letters compose with the rest of the SDF family — the reason for the bridge. */
    @Test
    fun textSdfComposesWithTheOtherSdfPrimitives() {
        val layout = layoutText("i", sans.atSize(120.0))
        val sdf = layout.toSdf(depth = 0.3, gridResolution = 200)
        val letterOnly = marchingCubes(sdf.field, sdf.bounds, 80)
        val withBall = marchingCubes(
            sdfSmoothUnion(sdf.field, sdfSphere(0.4, vec(0.0, 0.0, 0.0)), 0.15), sdf.bounds, 80
        )
        // An `i` is two pieces; smooth-unioning a ball that reaches both fuses them into one.
        assertTrue(
            withBall.volume() > letterOnly.volume(),
            "the union is not bigger than the letter alone (${withBall.volume()} vs ${letterOnly.volume()})"
        )
        assertTrue(withBall.triangleCount > 0)
    }

    // ==========================================================================================
    // Raster bridge
    // ==========================================================================================

    /**
     * The control arm for the winding convention, measured.
     *
     * Identical filler, identical fill rule, identical rings *except* for their direction. Under
     * NON_ZERO the counters close only when hole rings run the other way, so the broken arm inks
     * strictly more.
     */
    @Test
    fun countersNeedTheWindingConvention() {
        val layout = layoutText("boop", sans.atSize(180.0))
        val window = layout.bounds
        val good = rasterizeText(
            layout, 300, 200, window = window,
            fillRule = TextFillRule.NON_ZERO, rings = layout.toContours(honourHoles = true)
        )
        val bad = rasterizeText(
            layout, 300, 200, window = window,
            fillRule = TextFillRule.NON_ZERO, rings = layout.toContours(honourHoles = false)
        )
        assertTrue(
            bad.inkFraction() > good.inkFraction() * 1.15,
            "the broken arm inked ${bad.inkFraction()} against ${good.inkFraction()} — the counters did not close"
        )
        // EVEN_ODD is blind to direction, so it fills the counters correctly either way. That is the
        // asymmetry that makes the two rules a usable cross-check.
        val eo = rasterizeText(
            layout, 300, 200, window = window,
            fillRule = TextFillRule.EVEN_ODD, rings = layout.toContours(honourHoles = false)
        )
        assertEquals(good.inkFraction(), eo.inkFraction(), 1e-9)
    }

    /** Over correctly wound rings the two fill rules agree pixel for pixel. */
    @Test
    fun fillRulesAgreeOnCorrectlyWoundRings() {
        val layout = layoutText("B%8@ego", serif.atSize(120.0))
        val nz = rasterizeText(layout, 420, 160, fillRule = TextFillRule.NON_ZERO)
        val eo = rasterizeText(layout, 420, 160, fillRule = TextFillRule.EVEN_ODD)
        var worst = 0.0
        for (y in 0 until nz.height) for (x in 0 until nz.width) {
            worst = maxOf(worst, abs(nz.at(x, y, 0) - eo.at(x, y, 0)).toDouble())
        }
        assertEquals(0.0, worst, 1e-6, "NON_ZERO and EVEN_ODD disagree by up to $worst")
        assertTrue(nz.inkFraction() > 0.05, "the raster is nearly blank (${nz.inkFraction()})")
    }

    /** Bold inks more of the same frame than regular at the same size — a raster control arm. */
    @Test
    fun boldInksMoreThanRegular() {
        val text = "weight"
        val regular = layoutText(text, sans.atSize(120.0))
        val bold = layoutText(text, sans.withStyle(bold = true).atSize(120.0))
        val window = regular.bounds.union(bold.bounds)
        val r = rasterizeText(regular, 500, 160, window = window).inkFraction()
        val b = rasterizeText(bold, 500, 160, window = window).inkFraction()
        assertTrue(b > r * 1.05, "bold inked $b against regular's $r — the style is not reaching the outlines")
    }

    /** The raster fits the ink inside the frame, margin and all. */
    @Test
    fun theRasterKeepsTheInkInsideItsMargin() {
        val img = rasterizeText(layoutText("edge", sans.atSize(90.0)), 240, 120, margin = 0.1)
        fun rowInked(y: Int) = (0 until img.width).any { img.at(it, y, 0) > 0.01f }
        fun colInked(x: Int) = (0 until img.height).any { img.at(x, it, 0) > 0.01f }
        val guard = 240 / 20
        assertTrue((0 until guard).none { colInked(it) }, "ink reached the left margin")
        assertTrue((img.width - guard until img.width).none { colInked(it) }, "ink reached the right margin")
        assertTrue((0 until 120 / 20).none { rowInked(it) }, "ink reached the top margin")
    }

    // ==========================================================================================
    // Point bridge
    // ==========================================================================================

    /** Four times finer spacing gives about four times the points, and they lie on the outline. */
    @Test
    fun outlineSamplesScaleWithSpacingAndSitOnTheOutline() {
        val layout = layoutText("sample", sans.atSize(120.0))
        val coarse = layout.samplePoints(spacing = 20.0)
        val fine = layout.samplePoints(spacing = 5.0)
        val ratio = fine.size.toDouble() / coarse.size
        assertTrue(ratio > 3.4 && ratio < 4.6, "4x finer spacing gave ${ratio}x the points")

        val rings = layout.toContours()
        val worst = fine.maxOf { p -> rings.minOf { it.distanceTo(p) } }
        assertTrue(worst < 1e-3, "a sampled point sits $worst away from every outline")

        // Consecutive samples within a ring are one spacing apart — the resampler walks arc length.
        val ring = rings.filter { it.isOuter }[0]
        val walk = ring.resample(6.0)
        val gaps = walk.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
        val median = gaps.sorted()[gaps.size / 2]
        assertEquals(6.0, median, 0.6, "median sample gap $median for a spacing of 6")
    }

    // ==========================================================================================
    // Font loading
    // ==========================================================================================

    /** A logical family always resolves and always has sane vertical metrics. */
    @Test
    fun logicalFamiliesResolveWithUsableMetrics() {
        listOf("SansSerif", "Serif", "Monospaced").forEach { name ->
            val face = Typefaces.named(name, 64.0)
            assertEquals(64.0, face.size, 1e-9)
            assertTrue(face.ascent > 0 && face.descent > 0, "$name has degenerate metrics")
            assertTrue(
                face.ascent > face.descent,
                "$name reports ascent ${face.ascent} <= descent ${face.descent}"
            )
            assertTrue(face.lineHeight >= face.ascent + face.descent)
        }
    }

    /** A name nobody has installed is a silent AWT substitution unless you ask it not to be. */
    @Test
    fun strictNamingRejectsAFontThatIsNotThere() {
        val lenient = Typefaces.named("NoSuchFontHere-Regular", 40.0)
        assertTrue(lenient.ascent > 0, "AWT should have substituted something usable")
        assertTrue(
            runCatching { Typefaces.named("NoSuchFontHere-Regular", 40.0, strict = true) }.isFailure,
            "strict = true accepted a font family that is not installed"
        )
    }

    /**
     * A `.ttf` from disk goes through `Font.createFont` and produces the same kind of outlines.
     *
     * Skipped, loudly, on a machine with no system TrueType file — the loader is worth covering and
     * is not worth a fixture in the repo.
     */
    @Test
    fun aTrueTypeFileFromDiskYieldsRealOutlines() {
        val candidate = sequenceOf("/System/Library/Fonts", "/usr/share/fonts", "/Library/Fonts")
            .map(::File)
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().maxDepth(3) }
            .firstOrNull { it.isFile && it.extension.lowercase() == "ttf" && it.length() > 20_000 }
        if (candidate == null) {
            println("TypographyTest: no system .ttf found; skipping the file-loading check")
            return
        }
        val face = Typefaces.fromFile(candidate.absolutePath, 90.0)
        assertEquals(90.0, face.size, 1e-9)
        val layout = layoutText("og", face)
        assertTrue(layout.contourCount >= 2, "${candidate.name} produced ${layout.contourCount} contours for 'og'")
        assertTrue(layout.advanceWidth > 0.0)
        layout.glyphs.forEach { g ->
            g.glyph.contours.forEach { ring ->
                assertTrue((ring.signedArea > 0) == ring.isOuter, "${candidate.name} broke the winding convention")
            }
        }
    }

    // ==========================================================================================
    // Figure helpers
    // ==========================================================================================

    /** The figure path is `drawToSVG`, so it works with no AWT rasteriser and no GPU. */
    @Test
    fun theFigureHelpersProduceRealSvgHeadlessly() {
        val layout = layoutText("figure", sans.atSize(80.0))
        val html = planeFigureHtml(
            layout.toFLines(filled = true, stroked = false) + layout.metricsFLines(),
            title = "figure", caption = "svg", width = 400, height = 160
        )
        assertTrue("<svg" in html, "no <svg> in the figure markup")
        assertTrue(html.length > 2000, "the figure markup is suspiciously short (${html.length} chars)")
    }

    /** One `FLine` per inked glyph, and blanks contribute none. */
    @Test
    fun oneFLinePerInkedGlyph() {
        val layout = layoutText("a b", sans.atSize(80.0))
        assertEquals(3, layout.glyphs.size, "three glyphs including the space")
        assertEquals(2, layout.inkedGlyphs.size)
        assertEquals(2, layout.toFLines().size, "the space should not become a line")
        // The advance-box diagram covers the space too, which is the point of having it.
        assertEquals(3, layout.advanceBoxFLines().size)
    }

    /** Bounds are of the ink and follow the handles. */
    @Test
    fun boundsFollowTheHandles() {
        val layout = layoutText("shift", sans.atSize(80.0))
        val before = layout.bounds
        layout.eachGlyph { offset = vec(40.0, 0.0) }
        val after = layout.bounds
        assertEquals(before.x + 40.0, after.x, 1e-4, "bounds did not follow a 40-unit offset")
        assertEquals(before.w, after.w, 1e-4, "a pure translation changed the width")
        assertTrue(
            min(before.w, before.h) > 0.0 && before.w <= layout.advanceWidth + 1e-6,
            "ink bounds ${before.w} exceed the advance width ${layout.advanceWidth}"
        )
    }

    /** A rectangle is a rectangle: the layout box is the advance width by ascent plus descent. */
    @Test
    fun layoutBoundsAreTheEmBoxNotTheInk() {
        val layout = layoutText("xx", sans.atSize(100.0))
        val box: Rect = layout.layoutBounds
        assertEquals(layout.advanceWidth, box.w, 1e-9)
        assertEquals(-layout.ascent, box.y, 1e-9)
        assertEquals(layout.ascent + layout.descent, box.h, 1e-9)
        // The ink of "xx" is shorter than the em box, because x has neither ascender nor descender.
        assertTrue(layout.bounds.h < box.h, "the ink of 'xx' is as tall as the em box")
    }
}
