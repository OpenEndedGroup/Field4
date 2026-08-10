package afield.graphics

import afield.FLineClick
import afield.getFLineClickCallback
import afield.stdlib.FLineFaceCoordinates
import afield.stdlib.FLineHitKind
import afield.stdlib.FLineIntersection
import afield.stdlib.Vec3
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.onClick
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import afield.toIntersection
import afield.util.NodeTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **What a click on a figure returns**, tested by running the code that actually decides.
 *
 * The click path for an interactive FLine has been wired end to end for a long time — `FLine.onClick`
 * → `buildInteractiveSVG`'s `onclick` attribute → `window.__flineIntersectionFromSvg` in
 * `src/commonMain/resources/sliders.js` → an `FLineClick` over the socket → `getFLineClickCallback` —
 * and `PetriVisual` has used it to make places and transitions clickable. What it did not have was a
 * single line of coverage: before this file, the only test that mentioned `onClick`
 * (`FLineSerializationSmokeTest`) set it in order to assert it does *not* serialise, so editing
 * `sliders.js` could not turn anything red.
 *
 * So this test runs **the shipped browser code against the SVG the renderer actually emitted**. The
 * figures are built here with the ordinary drawing vocabulary, [buildInteractiveSVG] renders them,
 * the verbatim `sliders.js` is staged off the **classpath** — which makes it a declared input of
 * `jvmTest`, so editing it re-runs this instead of leaving a stale UP-TO-DATE pass — and `node` calls
 * the same two functions the generated markup names. A parity test with both sides written in Kotlin
 * would agree with itself forever; this one fails when the JavaScript is wrong.
 *
 * The cases are chosen for what the *old* two-field hit record could not express:
 *
 *  - `rail edge at 30%` — the record used to say "nearest node 0" for a click 24 units along an
 *    80-unit segment. It now says edge, segment 0, `u = 0.3`.
 *  - `panel face at (0.25, 0.75)` — a click inside a skewed quad, reported as bilinear coordinates on
 *    the panel. The nearest *node* to that click is corner 3, which is the answer the old record gave
 *    and is not where the click was.
 *  - `panel close edge` — the segment a `close` draws is a real element with its own index, and `z` is
 *    interpolated along it, so a 3D line drawn into a 2D SVG still reports a depth.
 *  - `place face` / `place rim edge` in a **fitted** figure — the reported point comes back in the
 *    line's own coordinates, not in SVG pixels, and a short segment is not swallowed by its own
 *    endpoints' picking radius.
 *  - `curve at t = 0.35` — a cubic, found by search rather than by its endpoints.
 *
 * **Deliberately not covered**: browser hit-testing. Which `<g>` receives a click is the browser's
 * decision (z-order, fill rules, stroke widths), so each case names the group it is clicking, and the
 * assertions are about what the shipped JavaScript makes of a click *within* that group. The live
 * cover for the rest is a human clicking the demo document named in `dev_docs/fline_hit_records.md`.
 *
 * Needs a `node` >= [NodeTool.DEFAULT_MIN_MAJOR]; `AFIELD_SKIP_JS_TESTS=1` opts out visibly.
 */
class FLineHitRecordProbeTest {

    // ------------------------------------------------------------------ the figures under test

    /** A skewed quad — a "panel" — with `z` a linear function of `x`, so an interpolated z is checkable. */
    private fun panel(): FLine = fline {
        moveTo(20.0, 20.0, 2.0)
        lineTo(140.0, 40.0, 14.0)
        lineTo(160.0, 130.0, 16.0)
        lineTo(30.0, 110.0, 3.0)
        close()
        label = "panel"
        filled = true
        stroked = true
        thickness = 2.0
        color = vec(0.42, 0.78, 0.94, 0.85)
        hoverColor = vec(1.0, 0.85, 0.3, 0.95)
    }

    /** Two straight segments, unclosed. */
    private fun rail(): FLine = fline {
        moveTo(200.0, 20.0)
        lineTo(280.0, 20.0)
        lineTo(280.0, 160.0)
        label = "rail"
        thickness = 3.0
        color = vec(0.95, 0.55, 0.35, 1.0)
    }

    /** One cubic, with its nodes dotted. */
    private fun curve(): FLine = fline {
        moveTo(30.0, 170.0)
        cubicTo(70.0, 120.0, 130.0, 220.0, 180.0, 170.0)
        label = "curve"
        thickness = 2.0
        pointed = true
        pointSize = 1.5
        color = vec(0.6, 0.95, 0.7, 1.0)
    }

    /** The shape `PetriVisual` makes clickable: a filled polygonal disc, drawn into a fitted figure. */
    private fun place(segments: Int = 28, cx: Double = 100.0, cy: Double = 100.0, r: Double = 26.0): FLine =
        fline {
            for (i in 0 until segments) {
                val a = 2.0 * Math.PI * i / segments
                val x = cx + r * cos(a)
                val y = cy + r * sin(a)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            label = "place"
            filled = true
            stroked = false
            color = vec(0.55, 0.85, 0.75, 0.9)
            hoverColor = vec(0.2, 1.0, 0.6, 0.4)
        }

    /**
     * A deliberately lopsided cubic: both controls crowded against the start, so its Bézier parameter
     * and its arc length disagree by a lot. `u = 0.5` is most of the way along it by length.
     */
    private fun lopsided(): FLine = fline {
        moveTo(LOPSIDED[0])
        cubicTo(LOPSIDED[1], LOPSIDED[2], LOPSIDED[3])
        label = "lopsided"
        thickness = 2.0
        color = vec(0.9, 0.6, 1.0, 1.0)
    }

    /** A non-convex quad — one corner pulled back past the diagonal, folding the bilinear patch. */
    private fun dart(): FLine = fline {
        moveTo(20.0, 20.0)
        lineTo(120.0, 20.0)
        lineTo(60.0, 60.0)
        lineTo(20.0, 120.0)
        close()
        label = "dart"
        filled = true
        stroked = true
        thickness = 1.5
        color = vec(0.9, 0.4, 0.4, 0.8)
    }

    /** Five corners: there is no bilinear patch to have. */
    private fun pentagon(): FLine = fline {
        val cx = 250.0
        val cy = 60.0
        for (i in 0 until 5) {
            val a = -Math.PI / 2 + 2.0 * Math.PI * i / 5
            if (i == 0) moveTo(cx + 40 * cos(a), cy + 40 * sin(a)) else lineTo(cx + 40 * cos(a), cy + 40 * sin(a))
        }
        close()
        label = "pentagon"
        filled = true
        stroked = true
        thickness = 1.5
        color = vec(0.5, 0.7, 0.5, 0.8)
    }

    private fun cubicAt(p0: Vec3, c1: Vec3, c2: Vec3, p1: Vec3, t: Double): Vec3 {
        val mt = 1 - t
        val a = mt * mt * mt
        val b = 3 * mt * mt * t
        val c = 3 * mt * t * t
        val d = t * t * t
        return vec(
            a * p0.x + b * c1.x + c * c2.x + d * p1.x,
            a * p0.y + b * c1.y + c * c2.y + d * p1.y,
            a * p0.z + b * c1.z + c * c2.z + d * p1.z
        )
    }

    /**
     * The figures, the SVG the renderer emits for them, and the clicks — everything the probe needs.
     *
     * [FIT] is applied to the second figure the way `Box.lines(fitToSize = true)` would, so the fit
     * inverse in the browser is exercised rather than assumed away; the first figure is unfitted.
     */
    private inner class Fixture(val onClick: (FLineIntersection) -> Unit) {
        val panel = panel()
        val rail = rail()
        val curve = curve()
        val place = place()
        val lopsided = lopsided()
        val dart = dart()
        val pentagon = pentagon()
        val figureA: String
        val figureB: String
        val figureC: String
        val cases = StringBuilder()
        private var caseCount = 0

        init {
            listOf(panel, rail, curve, place, lopsided, dart, pentagon).forEach { it.onClick = onClick }
            figureA = buildInteractiveSVG(
                listOf(panel, rail, curve).map { it to it },
                300.0,
                200.0,
                null
            )
            figureB = buildInteractiveSVG(
                listOf(place).map { it to it.fitted(FIT) },
                220.0,
                220.0,
                FIT
            )
            figureC = buildInteractiveSVG(
                listOf(lopsided, dart, pentagon).map { it to it },
                320.0,
                200.0,
                null
            )
        }

        /** A click, given in the *line's* coordinates; the client coordinates are derived here. */
        fun click(
            name: String,
            svg: String,
            lineIndex: Int,
            at: Vec3,
            fit: FitTransform?,
            expect: String,
            tolerance: Double = 1e-4
        ) {
            val svgX = if (fit == null) at.x else (at.x + fit.offsetX) * fit.scale
            val svgY = if (fit == null) at.y else (at.y + fit.offsetY) * fit.scale
            val clientX = svgX * CTM_SCALE + CTM_TX
            val clientY = svgY * CTM_SCALE + CTM_TY
            if (caseCount > 0) cases.append(",\n")
            cases.append(
                """  {"name": "$name", "svg": "$svg", "lineIndex": $lineIndex, """ +
                    """"clientX": $clientX, "clientY": $clientY, """ +
                    """"expect": {$expect, "tol": $tolerance}}"""
            )
            caseCount++
        }

        fun casesJson(hover: String, differential: String): String =
            """{"ctm": {"scale": $CTM_SCALE, "tx": $CTM_TX, "ty": $CTM_TY},""" + "\n" +
                """ "cases": [""" + "\n$cases\n ],\n \"hover\": [$hover],\n" +
                " \"differential\": [$differential]}\n"
    }

    /** The `to` of an anchor node, which is the only kind of node these figures start with. */
    private fun FLine.anchor(index: Int): Vec3 = when (val node = nodes[index]) {
        is FLine.MoveTo -> node.to
        is FLine.LineTo -> node.to
        is FLine.CubicTo -> node.to
        is FLine.Close -> fail("node $index of this figure is a close, not an anchor")
    }

    private fun FLine.fitted(fit: FitTransform): FLine =
        transform { p -> vec((p.x + fit.offsetX) * fit.scale, (p.y + fit.offsetY) * fit.scale, p.z) }

    /**
     * The clicks, and what each one has to come back as.
     *
     * Every expected number is either a coordinate the figure was built from or a parameter the click
     * was *placed* at — the click for `u = 0.3` is put 30% along the segment by construction, and the
     * cubic click is placed by evaluating the Bézier at `t = 0.35`. The browser has to find those
     * numbers again by its own means.
     */
    private fun Fixture.buildCases() {
        // A face click at bilinear (0.25, 0.75) of the panel: p(u,v) = the four corners weighted.
        val p0 = vec(20.0, 20.0, 2.0)
        val p1 = vec(140.0, 40.0, 14.0)
        val p2 = vec(160.0, 130.0, 16.0)
        val p3 = vec(30.0, 110.0, 3.0)
        val u = 0.25
        val v = 0.75
        val faceX = (1 - u) * (1 - v) * p0.x + u * (1 - v) * p1.x + u * v * p2.x + (1 - u) * v * p3.x
        val faceY = (1 - u) * (1 - v) * p0.y + u * (1 - v) * p1.y + u * v * p2.y + (1 - u) * v * p3.y
        // The panel is planar with z = 0.1x, which is what makes the expected z below exact.
        click(
            "panel face at (0.25, 0.75)", "figureA.svg", 0, vec(faceX, faceY, 0.0), null,
            """"kind": "face", "lineIndex": 0, "label": "panel", "nodeIndex": 4, "subPathIndex": 0,
                "elementIndex": 0, "u": $u, "v": $v, "ex": $faceX, "ey": $faceY, "ez": ${0.1 * faceX},
                "faceCoordinates": "bilinear", "closestVertexIndex": 3""".trimIndent().replace("\n", " "),
            tolerance = 1e-3
        )
        // Within picking distance of corner 1 — a node beats the face it belongs to.
        click(
            "panel corner 1", "figureA.svg", 0, vec(p1.x + 1.0, p1.y + 1.0, 0.0), null,
            """"kind": "point", "lineIndex": 0, "nodeIndex": 1, "elementIndex": 1,
                "ex": ${p1.x}, "ey": ${p1.y}, "ez": ${p1.z}, "u": null, "v": null,
                "closestVertexIndex": 1""".trimIndent().replace("\n", " ")
        )
        // The midpoint of the segment `close` draws, from the last anchor back to the first.
        click(
            "panel close edge", "figureA.svg", 0, vec((p3.x + p0.x) / 2, (p3.y + p0.y) / 2, 0.0), null,
            """"kind": "edge", "nodeIndex": 4, "subPathIndex": 0, "elementIndex": 3, "u": 0.5,
                "ex": ${(p3.x + p0.x) / 2}, "ey": ${(p3.y + p0.y) / 2}, "ez": ${(p3.z + p0.z) / 2},
                "v": null""".trimIndent().replace("\n", " ")
        )
        // 30% along the rail's first segment — the case the old two-field record could not express.
        click(
            "rail edge at 30%", "figureA.svg", 1, vec(200.0 + 0.3 * 80.0, 20.0, 0.0), null,
            """"kind": "edge", "lineIndex": 1, "label": "rail", "nodeIndex": 1, "subPathIndex": 0,
                "elementIndex": 0, "u": 0.3, "ex": 224.0, "ey": 20.0, "ez": 0.0,
                "closestVertexIndex": 0""".trimIndent().replace("\n", " ")
        )
        // A cubic, at a parameter neither endpoint nor midpoint.
        val t = 0.35
        val onCurve = cubicAt(vec(30.0, 170.0, 0.0), vec(70.0, 120.0, 0.0), vec(130.0, 220.0, 0.0), vec(180.0, 170.0, 0.0), t)
        click(
            "curve at t = 0.35", "figureA.svg", 2, onCurve, null,
            """"kind": "edge", "lineIndex": 2, "label": "curve", "nodeIndex": 1, "subPathIndex": 0,
                "elementIndex": 0, "u": $t, "ex": ${onCurve.x}, "ey": ${onCurve.y}""".trimIndent().replace("\n", " "),
            tolerance = 2e-3
        )
        // The fitted figure: the centre of the disc, reported back in the disc's own coordinates.
        click(
            "place face at its centre", "figureB.svg", 0, vec(100.0, 100.0, 0.0), FIT,
            """"kind": "face", "lineIndex": 0, "label": "place", "subPathIndex": 0, "elementIndex": 0,
                "u": 0.5, "v": 0.5, "ex": 100.0, "ey": 100.0, "faceCoordinates": "bbox"""".trimIndent().replace("\n", " "),
            tolerance = 1e-3
        )
        // The midpoint of one 5.8-unit rim segment. Both its ends are inside the 8-line-unit point
        // radius this fit implies, so this is the case that pins "a node does not swallow its segment".
        val rimA = place.anchor(0)
        val rimB = place.anchor(1)
        click(
            "place rim edge", "figureB.svg", 0, vec((rimA.x + rimB.x) / 2.0, (rimA.y + rimB.y) / 2.0, 0.0), FIT,
            """"kind": "edge", "lineIndex": 0, "nodeIndex": 1, "subPathIndex": 0, "elementIndex": 0,
                "u": 0.5""".trimIndent().replace("\n", " "),
            tolerance = 1e-2
        )
        // `u` on a cubic is the BÉZIER PARAMETER, not arc length, and this is the case that says so out
        // loud. The lopsided cubic in figure C piles both controls up against its start, so parameter and
        // length diverge hard: the point at t = 0.5 is far past halfway along the curve. The assertion is
        // that `u` is the parameter — to 2e-3 — and [theCubicParameterIsNotArcLength] below asserts the
        // size of the disagreement, so a future change that quietly switched to arc length would fail
        // one of the two.
        click(
            "lopsided cubic at t = 0.5", "figureC.svg", 0, cubicAt(LOPSIDED[0], LOPSIDED[1], LOPSIDED[2], LOPSIDED[3], 0.5), null,
            """"kind": "edge", "lineIndex": 0, "label": "lopsided", "nodeIndex": 1, "elementIndex": 0,
                "u": 0.5""".trimIndent().replace("\n", " "),
            tolerance = 2e-3
        )
        // A non-convex face, in the 2D path this time. Measured rather than assumed (see the
        // differential list below): the checked bilinear solve *does* invert over almost all of a dart's
        // interior, so this deep-interior click is a verified `bilinear`, and the handful of interior
        // points where it does not invert all lie within the edge tolerance and are reported as edges
        // anyway. Which is the honest shape of the answer: on this figure the fallback is unreachable by
        // clicking, and the function-level differential is where it gets exercised.
        click(
            "dart face, deep interior", "figureC.svg", 1, vec(35.0, 35.0, 0.0), null,
            """"kind": "face", "label": "dart", "faceCoordinates": "bilinear"""".trimIndent().replace("\n", " ")
        )
        // Five corners: no bilinear patch exists at all, and the record says bbox rather than inventing one.
        click(
            "pentagon face", "figureC.svg", 2, vec(250.0, 60.0, 0.0), null,
            """"kind": "face", "label": "pentagon", "faceCoordinates": "bbox"""".trimIndent().replace("\n", " ")
        )
    }

    // ------------------------------------------------------------------ the tests

    @Test
    fun `the shipped sliders js names the element a click landed on`() {
        val seen = mutableListOf<FLineIntersection>()
        val fixture = Fixture { seen += it }
        fixture.buildCases()
        assertPanelSymmetry(fixture)
        run(fixture)
    }

    /**
     * The other half of the round trip: what the browser posted, decoded and dispatched.
     *
     * The payloads here are the ones `sendFLineClick` produced in the run above — not hand-written —
     * so this covers the wire type ([FLineClick], decoded the way `Server.kt` decodes it), the mapping
     * ([toIntersection]) and the registry ([getFLineClickCallback]) with the real thing.
     *
     * It also demonstrates the ergonomic point of [FLineIntersection.lineIndex]: **one** closure is
     * attached to all four lines, and it can still tell which part of the figure was clicked.
     */
    @Test
    fun `a click posted by the browser arrives at the FLine's own callback`() {
        val seen = mutableListOf<FLineIntersection>()
        val fixture = Fixture { seen += it }
        fixture.buildCases()
        val posted = run(fixture) ?: return

        val payloads = Json.parseToJsonElement(posted).jsonArray
        assertEquals(10, payloads.size, "expected one posted payload per click")

        val byName = LinkedHashMap<String, FLineIntersection>()
        payloads.forEach { entry ->
            val name = entry.jsonObject["name"]!!.jsonPrimitive.content
            val click = Json.decodeFromJsonElement(FLineClick.serializer(), entry.jsonObject["payload"]!!)
            val callback = getFLineClickCallback(click.callbackId)
                ?: fail("no callback registered for ${click.callbackId} (posted by case '$name')")
            val before = seen.size
            callback(click.toIntersection())
            assertEquals(before + 1, seen.size, "the callback for '$name' did not fire")
            byName[name] = seen.last()
        }

        val face = byName.getValue("panel face at (0.25, 0.75)")
        assertEquals(FLineHitKind.FACE, face.kind)
        assertEquals("panel", face.label)
        assertEquals(0, face.lineIndex)
        assertTrue(abs(face.u - 0.25) < 1e-3 && abs(face.v - 0.75) < 1e-3, "face uv was (${face.u}, ${face.v})")
        assertTrue(abs((face.onElement?.z ?: 0f).toDouble() - 5.9375) < 1e-2, "face z was ${face.onElement?.z}")
        // The old two-field record's answer, still there and still nearest — and still not where the
        // click was, which is the whole reason for the wider record.
        assertEquals(3, face.closestVertexIndex)

        val edge = byName.getValue("rail edge at 30%")
        assertEquals(FLineHitKind.EDGE, edge.kind)
        assertEquals(1, edge.lineIndex)
        assertEquals("rail", edge.label)
        assertEquals(1, edge.nodeIndex)
        assertTrue(abs(edge.u - 0.3) < 1e-3, "edge u was ${edge.u}")

        val point = byName.getValue("panel corner 1")
        assertEquals(FLineHitKind.POINT, point.kind)
        assertEquals(1, point.nodeIndex)
        assertTrue(point.u.isNaN() && point.v.isNaN(), "a point hit should have no local coordinate")

        val disc = byName.getValue("place face at its centre")
        assertEquals(FLineHitKind.FACE, disc.kind)
        assertEquals("place", disc.label)
        assertTrue(
            abs((disc.onElement?.x ?: 0f).toDouble() - 100.0) < 1e-2,
            "a fitted figure must report the line's own coordinates, got ${disc.onElement}"
        )

        assertEquals(
            setOf(0, 1, 2),
            byName.values.filter { it.label in setOf("panel", "rail", "curve") }.map { it.lineIndex }.toSet(),
            "one shared closure should have been able to tell the three lines of figure A apart"
        )

        val pentagon = byName.getValue("pentagon face")
        assertEquals(FLineHitKind.FACE, pentagon.kind)
        assertEquals(
            FLineFaceCoordinates.BOUNDING_BOX, pentagon.faceCoordinates,
            "a five-cornered face has no bilinear patch, and the record has to say which convention it used"
        )
        assertEquals(FLineFaceCoordinates.BILINEAR, face.faceCoordinates)
        assertEquals(FLineFaceCoordinates.NONE, edge.faceCoordinates, "an edge hit has no face convention")
    }

    /**
     * A browser running a **stale cached** `sliders.js` posts only the original five fields. That has
     * to keep working, which is why every added field on [FLineClick] is defaulted.
     */
    @Test
    fun `the payload an older browser posts still arrives, as an unknown element`() {
        val click = Json.decodeFromString(
            FLineClick.serializer(),
            """{"callbackId":"fline-legacy","closestVertexIndex":2,"x":11.5,"y":-3.25,"z":7.0}"""
        )
        val hit = click.toIntersection()
        assertEquals(FLineHitKind.UNKNOWN, hit.kind)
        assertEquals(2, hit.closestVertexIndex)
        assertEquals(11.5f, hit.position.x)
        assertEquals(7.0f, hit.position.z)
        assertEquals(-1, hit.lineIndex)
        assertEquals(null, hit.onElement)
        assertTrue(hit.u.isNaN() && hit.v.isNaN())
    }

    /**
     * The two sides have to agree on the words. `sliders.js` writes the kind as a lower-case string
     * and [toIntersection] parses it, so a rename on either side is a silent downgrade to
     * [FLineHitKind.UNKNOWN] rather than a compile error.
     */
    @Test
    fun `every hit kind the browser can send is a kind the JVM knows`() {
        val js = resource("/sliders.js")
        val emitted = Regex("""place\('(\w+)'""").findAll(js).map { it.groupValues[1] }.toSet()
        assertTrue(emitted.isNotEmpty(), "found no `place('kind'` calls in sliders.js — the scan needs updating")
        emitted.forEach { kind ->
            val parsed = FLineClick(callbackId = "x", kind = kind).toIntersection().kind
            assertTrue(
                parsed != FLineHitKind.UNKNOWN,
                "sliders.js can send kind='$kind' but the JVM parses it as UNKNOWN"
            )
        }
        assertEquals(
            FLineHitKind.entries.filter { it != FLineHitKind.UNKNOWN }.map { it.name.lowercase() }.toSet(),
            emitted,
            "the kinds sliders.js reports and the FLineHitKind vocabulary have drifted apart"
        )
    }

    /** The `(u, v) = (0.5, 0.5)` expectation for the disc only means anything if its bbox is centred. */
    private fun assertPanelSymmetry(fixture: Fixture) {
        val xs = fixture.place.nodes.indices
            .filter { fixture.place.nodes[it] !is FLine.Close }
            .map { fixture.place.anchor(it) }
        val minX = xs.minOf { it.x }
        val maxX = xs.maxOf { it.x }
        val minY = xs.minOf { it.y }
        val maxY = xs.maxOf { it.y }
        assertTrue(
            abs((minX + maxX) / 2 - 100f) < 1e-3 && abs((minY + maxY) / 2 - 100f) < 1e-3,
            "the disc's bounding box is not centred on its centre ($minX..$maxX, $minY..$maxY), so the " +
                "expected face coordinates of (0.5, 0.5) would be wrong"
        )
    }

    // ------------------------------------------------------------------ running the probe

    /** Runs the probe and returns what the browser posted, or `null` when JS testing is opted out of. */
    private fun run(fixture: Fixture): String? {
        if (System.getenv("AFIELD_SKIP_JS_TESTS") == "1") {
            println("FLineHitRecordProbeTest: skipped by AFIELD_SKIP_JS_TESTS=1")
            return null
        }
        val node = NodeTool.resolveNode()
            ?: fail(
                "the FLine hit-record cover runs the real sliders.js under node: " +
                    "${NodeTool.unavailableMessage()}. Set AFIELD_SKIP_JS_TESTS=1 to opt out."
            )

        val dir = Files.createTempDirectory("field-fline-hit-")
        dir.resolve("probe.mjs").writeText(resource("/flineHit/probe.mjs"))
        dir.resolve("sliders.js").writeText(resource("/sliders.js"))
        dir.resolve("package.json").writeText("{\"type\": \"module\"}\n")
        dir.resolve("figureA.svg").writeText(fixture.figureA)
        dir.resolve("figureB.svg").writeText(fixture.figureB)
        dir.resolve("figureC.svg").writeText(fixture.figureC)
        dir.resolve("cases.json").writeText(
            fixture.casesJson(
                """{"name": "panel hover", "svg": "figureA.svg", "lineIndex": 0},""" +
                    """{"name": "place hover", "svg": "figureB.svg", "lineIndex": 0}""",
                differentialFaces()
            )
        )
        val posted = dir.resolve("posted.json")
        // The viewer's own face-coordinate rule, lifted verbatim, so the probe can hold the two
        // implementations to each other rather than to a description of each other.
        val region = dir.resolve("viewerRegion.js")
        region.writeText(ViewerJavaScript.regionSource("fline-hit-picking"))

        val process = ProcessBuilder(
            node,
            dir.resolve("probe.mjs").toString(),
            dir.resolve("cases.json").toString(),
            posted.toString(),
            region.toString()
        )
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("the FLine hit-record probe never finished:\n$output")
        }
        println(output.trimEnd())
        if (process.exitValue() != 0 || PASSED !in output) {
            fail("the FLine hit-record probe failed (exit ${process.exitValue()}) in $dir:\n$output")
        }
        return posted.readText()
    }

    /**
     * The faces both implementations of the face-coordinate rule are made to answer for.
     *
     * All in the `z = 0` plane, so the viewer region's plane projection is the identity and the two
     * functions receive *identical* numbers — which is what makes disagreement mean disagreement rather
     * than a different frame. A convex quad, the same quad's fold, a pentagon and a degenerate face: the
     * four outcomes the record can report.
     */
    private fun differentialFaces(): String {
        fun face(name: String, pts: String, at: Pair<Double, Double>, mode: String, extra: String = "") =
            """{"name": ${json(name)}, "pts": [$pts], "at": [${at.first}, ${at.second}], """ +
                """"expectMode": ${json(mode)}$extra}"""
        val square = "[0,0,0],[100,0,0],[100,100,0],[0,100,0]"
        val skewed = "[0,0,0],[100,20,0],[80,120,0],[10,90,0]"
        val dartPoints = "[20,20,0],[120,20,0],[60,60,0],[20,120,0]"
        val pentagonPoints = "[0,0,0],[100,0,0],[130,80,0],[50,130,0],[-30,80,0]"
        val degenerate = "[7,7,0],[7,7,0],[7,7,0]"
        return listOf(
            face("unit square, dead centre", square, 25.0 to 75.0, "bilinear", ""","expectU": 0.25, "expectV": 0.75"""),
            face("skewed quad", skewed, 40.0 to 40.0, "bilinear"),
            // Measured, not guessed: of the 941 interior points of a 2-unit grid over this dart, exactly
            // two fail the checked solve, and both sit in the extreme corners where the patch folds.
            face("dart, the corner where the patch folds", dartPoints, 116.0 to 22.0, "bbox"),
            face("dart, deep interior", dartPoints, 30.0 to 30.0, "bilinear"),
            face("pentagon", pentagonPoints, 40.0 to 40.0, "bbox"),
            face("a face with no extent", degenerate, 7.0 to 7.0, "none")
        ).joinToString(",\n  ")
    }

    /**
     * **What `u` means on a curve**, asserted rather than assumed.
     *
     * A reader seeing `u = 0.5` on a cubic will assume half way along it. It is the **Bézier parameter**,
     * and on [LOPSIDED] — both controls crowded against the start — the two are nowhere near each other:
     * parameter 0.5 is 16.8% of the way along by length.
     * This measures the gap with a Kotlin oracle (2000-step polyline arc length) so that the claim in
     * `FLineIntersection.u`'s KDoc and in `dev_docs/fline_hit_records.md` is a measured fact, and so that
     * a future change to arc-length parameterisation fails a test instead of silently redefining a field.
     */
    @Test
    fun `u on a cubic is the Bezier parameter, and that is measurably not arc length`() {
        val steps = 2000
        var total = 0.0
        val lengths = DoubleArray(steps + 1)
        var previous = cubicAt(LOPSIDED[0], LOPSIDED[1], LOPSIDED[2], LOPSIDED[3], 0.0)
        for (i in 1..steps) {
            val here = cubicAt(LOPSIDED[0], LOPSIDED[1], LOPSIDED[2], LOPSIDED[3], i.toDouble() / steps)
            total += kotlin.math.hypot((here.x - previous.x).toDouble(), (here.y - previous.y).toDouble())
            lengths[i] = total
            previous = here
        }
        val atHalf = lengths[steps / 2] / total
        // Measured 2026-08-10: the point at parameter 0.5 is only **16.8%** of the way along by length —
        // the controls crowd the start, so the curve creeps and then bolts. The floor is set just under
        // that gap of 0.332: a loose floor like 0.01 would pass for almost any curve and prove nothing,
        // and the direction matters too, which is why this is not an `abs`.
        assertTrue(
            0.5 - atHalf > 0.3,
            "the lopsided cubic is not lopsided enough to make the point: parameter 0.5 sits at " +
                "${"%.4f".format(atHalf)} of its arc length"
        )
        println("lopsided cubic: parameter 0.5 is at ${"%.4f".format(atHalf)} of arc length")
    }

    private fun json(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText()
            ?: fail("$path is not on the test classpath (expected it in commonMain or jvmTest resources)")

    private companion object {
        const val PASSED = "fline hit record probe passed"

        /** The fit a `lines(fitToSize = true)` call would apply — scale and offset, both non-trivial. */
        val FIT = FitTransform(0.5f, 10f, 6f)

        /** start, both controls, end — the controls crowded against the start on purpose. */
        val LOPSIDED = listOf(
            vec(20.0, 160.0, 0.0),
            vec(28.0, 150.0, 0.0),
            vec(36.0, 140.0, 0.0),
            vec(300.0, 120.0, 0.0)
        )

        // The page's own transform from SVG units to client pixels, so `getScreenCTM` is not identity.
        const val CTM_SCALE = 2.0
        const val CTM_TX = 30.0
        const val CTM_TY = 17.0
    }
}
