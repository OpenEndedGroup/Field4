package afield.four.physics

import afield.graphics.FLine
import afield.graphics.outputTo
import afield.stdlib.color
import afield.stdlib.thickness
import afield.stdlib.vec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The world layer driven to actual pixels: a filmstrip PNG per capability, rendered headless
 * through Skia's raster surface and `outputTo` — the same path `ChartGallery` uses, CPU only, no
 * Window, no Renderer and no GPU. The geometry is exactly what a live box would get from
 * `sim.debugLines()` and `ColliderSet.outline()` through `Box.showPlane`; only the rasteriser
 * differs, because a test cannot look at an HTML card.
 *
 * "It compiles" and "the test is green" are not verification for anything rendered, so these files
 * exist to be *looked at*. They go to a temp directory by default — a test never writes a committed
 * asset — and to a chosen directory with
 *
 *     ./gradlew jvmTest --tests '*SimdVerletWorldCaptureTest' -Dafield.physics.captureDir=/tmp/world
 *
 * (only the `afield.` property namespace is forwarded to test workers; see `build.gradle.kts`).
 *
 * What is asserted is what an image can be wrong in a way a number would catch: that there is ink
 * at all, that there is not so much ink the frame is a solid block, and — the one that matters —
 * that the frames *differ from each other* by a floored fraction of pixels. A capture harness
 * whose scene silently stopped simulating would still produce four beautiful identical panels.
 */
class SimdVerletWorldCaptureTest {

    private val background = 0xFF0B0F14.toInt()

    private val outputDir: File by lazy {
        val chosen = System.getProperty("afield.physics.captureDir")
        val dir = if (chosen != null) File(chosen) else File(System.getProperty("java.io.tmpdir"), "afield-physics-captures")
        dir.mkdirs()
        dir
    }

    /** A world rectangle, in whichever two axes the strip is projecting. */
    private class View(val x: Double, val y: Double, val w: Double, val h: Double)

    /**
     * One panel of a filmstrip: the geometry of one frame, and optionally its own window. A strip
     * of *frames* shares one view; a strip comparing separate scenes cannot.
     */
    private class Panel(val lines: List<FLine>, val view: View? = null)

    private class Strip(val width: Int, val height: Int, val pixels: IntArray, val png: ByteArray)

    /**
     * Renders [panels] side by side into one raster.
     *
     * @param axes which world axes go to screen x and y: `"xy"` for the front view, `"xz"` for the
     *   view from above, which is the only one a flag flapping in z is visible in.
     */
    private fun renderStrip(
        panels: List<Panel>,
        view: View,
        cellWidth: Int = 320,
        cellHeight: Int = 320,
        axes: String = "xy",
        padding: Double = 10.0
    ): Strip {
        val w = cellWidth * panels.size
        val h = cellHeight
        val surface = Surface.makeRasterN32Premul(w, h)
        val bitmap = Bitmap()
        try {
            val canvas = surface.canvas
            canvas.clear(background)

            val innerW = cellWidth - padding * 2
            val innerH = cellHeight - padding * 2
            val scale = minOf(innerW / view.w, innerH / view.h)

            for ((n, panel) in panels.withIndex()) {
                val v = panel.view ?: view
                val sc = if (panel.view == null) scale else minOf(innerW / v.w, innerH / v.h)
                val ox = n * cellWidth + padding + (innerW - v.w * sc) * 0.5
                val oy = padding + (innerH - v.h * sc) * 0.5
                // Screen y grows down; every scene here is written with y up, so the panel flips.
                val mapped = panel.lines.map { line ->
                    line.transform { p ->
                        val a = if (axes == "xz") p.x else p.x
                        val b = if (axes == "xz") p.z else p.y
                        vec(
                            (a - v.x) * sc + ox,
                            (v.h - (b - v.y)) * sc + oy,
                            0.0
                        )
                    }
                }
                mapped.forEach { outputTo(it, canvas, antiAlias = true) }
                if (n > 0) {
                    // A hairline between panels, so a strip reads as frames rather than as one
                    // very wide picture.
                    val divider = FLine()
                    divider.moveTo(n * cellWidth.toDouble(), 0.0)
                    divider.lineTo(n * cellWidth.toDouble(), h.toDouble())
                    divider.color = vec(0.22, 0.28, 0.36, 1.0)
                    outputTo(divider, canvas, antiAlias = false)
                }
            }

            val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("Skia declined to encode the strip as PNG")
            bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
            check(surface.readPixels(bitmap, 0, 0)) { "Skia declined to read the strip back" }
            val pixels = IntArray(w * h) { bitmap.getColor(it % w, it / w) }
            return Strip(w, h, pixels, png)
        } finally {
            runCatching { bitmap.close() }
            runCatching { surface.close() }
        }
    }

    private fun isInk(argb: Int): Boolean {
        val dr = abs(((argb shr 16) and 0xFF) - ((background shr 16) and 0xFF))
        val dg = abs(((argb shr 8) and 0xFF) - ((background shr 8) and 0xFF))
        val db = abs((argb and 0xFF) - (background and 0xFF))
        return dr + dg + db > 24
    }

    /** Fraction of a panel's pixels that carry ink. */
    private fun inkFraction(strip: Strip, panel: Int, panels: Int): Double {
        val cell = strip.width / panels
        var n = 0
        for (y in 0 until strip.height) {
            for (x in panel * cell until (panel + 1) * cell) {
                if (isInk(strip.pixels[y * strip.width + x])) n++
            }
        }
        return n.toDouble() / (cell * strip.height)
    }

    /** Fraction of pixels on which two panels of the same strip disagree about ink. */
    private fun differingFraction(strip: Strip, a: Int, b: Int, panels: Int): Double {
        val cell = strip.width / panels
        var n = 0
        for (y in 0 until strip.height) {
            for (x in 0 until cell) {
                val pa = strip.pixels[y * strip.width + a * cell + x]
                val pb = strip.pixels[y * strip.width + b * cell + x]
                if (isInk(pa) != isInk(pb)) n++
            }
        }
        return n.toDouble() / (cell * strip.height)
    }

    /**
     * Writes [strip] out and asserts the three things a picture can be wrong about that a number
     * catches: that it exists, that it has ink, and that its frames are not all the same picture.
     */
    private fun publish(name: String, strip: Strip, panels: Int, minInk: Double, minChange: Double) {
        val file = File(outputDir, name)
        file.writeBytes(strip.png)
        val inks = (0 until panels).map { inkFraction(strip, it, panels) }
        val firstToLast = differingFraction(strip, 0, panels - 1, panels)
        println(
            "%-22s %s  %d x %d, %d bytes; ink per panel %s; first vs last frame differ on %.1f %% of pixels"
                .format(name, file.absolutePath, strip.width, strip.height, strip.png.size,
                    inks.joinToString(" ") { "%.3f".format(it) }, firstToLast * 100)
        )
        assertTrue(strip.png.size > 2000, "$name: a PNG this small is an empty frame, ${strip.png.size} bytes")
        for ((n, ink) in inks.withIndex()) {
            assertTrue(ink > minInk, "$name panel $n: too little ink, $ink")
            assertTrue(ink < 0.6, "$name panel $n: the frame is a solid block, $ink")
        }
        assertTrue(
            firstToLast > minChange,
            "$name: the first and last frames must be different pictures -- a scene that quietly " +
                "stopped simulating would give identical panels. Differ on ${firstToLast * 100} %"
        )
    }

    // ------------------------------------------------------------------------------------------

    @Test
    fun clothDrapingOverASphere() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val cloth = sim.addCloth(
            width = 22, height = 22, spacing = 0.22f, stiffness = 60f, curvatureStrength = 0f,
            position = { c, r -> vec((c - 10.5f) * 0.22f, 1.2f, (r - 10.5f) * 0.22f) },
            pinned = { _, _ -> false }
        )
        val world = sim.addColliders(
            SphereCollider(0f, -0.9f, 0f, radius = 1.05f).also { it.friction = 0.75f; it.margin = 0.02f },
            PlaneCollider(0f, 1f, 0f, offset = -2.4f).also { it.friction = 0.9f }
        )

        val at = listOf(0, 40, 110, 260, 700)
        val panels = ArrayList<Panel>()
        var frame = 0
        for (target in at) {
            while (frame < target) {
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16)
                frame++
            }
            panels += Panel(sim.debugLines(points = false, thickness = 0.9) + world.outline(thickness = 2.0))
        }
        assertTrue(world.contacts > 40, "premise: the sheet must be in contact at the end, ${world.contacts}")

        val strip = renderStrip(panels, View(-2.6, -2.9, 5.2, 5.2), cellWidth = 300, cellHeight = 300)
        publish("drape-over-sphere.png", strip, panels.size, minInk = 0.01, minChange = 0.02)
    }

    /**
     * A sphere pushed through a sheet pinned all round its border: the two capabilities in one
     * picture, because tearing is only legible when something is doing the tearing.
     *
     * The first attempt here was a hanging sheet with a heavy corner under a ramped load, and it
     * failed in a way worth recording: a uniformly pinned sheet fails uniformly, the whole
     * attachment row went in a few frames, and four of the five panels were an empty field with
     * the pinned row still drawn across the top. A concentrated, *moving* load is what makes a
     * crack start somewhere and go somewhere.
     */
    @Test
    fun aSphereTearingThroughAPinnedSheet() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val n = 26
        val cloth = sim.addCloth(
            width = n, height = n, spacing = 0.2f, stiffness = 60f, curvatureStrength = 0f,
            position = { c, r -> vec((c - (n - 1) * 0.5f) * 0.2f, ((n - 1) * 0.5f - r) * 0.2f, 0f) },
            pinned = { c, r -> c == 0 || r == 0 || c == n - 1 || r == n - 1 }
        )
        val punch = SphereCollider(0f, 0f, -1.6f, radius = 0.55f).also { it.friction = 0.4f }
        val world = sim.addColliders(punch)
        val tear = cloth.enableTearing(threshold = 0.24f, maxBreaksPerStep = 3)

        val at = listOf(0, 200, 330, 380, 470)
        val panels = ArrayList<Panel>()
        val torn = ArrayList<Int>()
        var frame = 0
        for (target in at) {
            while (frame < target) {
                punch.centerZ = -1.6f + frame * 0.007f
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 16)
                frame++
            }
            torn += tear.brokenTotal
            panels += Panel(sim.debugLines(points = false, thickness = 1.0) + world.outline(thickness = 2.0))
        }
        println("punch-through capture: sphere z ${"%.2f".format(punch.centerZ)}, edges broken at $at -> $torn")
        assertTrue(torn.first() == 0, "premise: the first panel must be the intact sheet")
        assertTrue(torn.last() in 8..400, "premise: the last must be holed but not obliterated, ${torn.last()} edges")
        assertTrue(sim.liveEdgeCount() > 800, "premise: most of the sheet must survive, ${sim.liveEdgeCount()} edges")

        val strip = renderStrip(panels, View(-2.9, -2.9, 5.8, 5.8), cellWidth = 300, cellHeight = 300)
        publish("tearing-under-load.png", strip, panels.size, minInk = 0.02, minChange = 0.01)
    }

    @Test
    fun aFlagFlappingInTheWind() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val cloth = sim.addCloth(
            width = 26, height = 16, spacing = 0.2f, stiffness = 60f, curvatureStrength = 0f,
            position = { c, r -> vec(c * 0.2f, -r * 0.2f, 0f) },
            pinned = { c, _ -> c == 0 }
        )
        val wind = sim.addWind(velocityX = 9f, drag = 0.05f, turbulence = 0.7f, turbulenceScale = 0.5f, seed = 12)
        val aero = cloth.addWind(wind)

        // From above: the flag lies in xy and flaps in z, so the front view shows a rectangle
        // standing perfectly still. This is the whole reason the strip can pick its axes.
        val at = listOf(120, 260, 400, 540, 680)
        val panels = ArrayList<Panel>()
        var frame = 0
        for (target in at) {
            while (frame < target) {
                cloth.step(dt = 1f / 60f, damping = 0.98f, gravity = -2f, iterations = 16)
                frame++
            }
            panels += Panel(
                sim.debugLines(points = false, thickness = 0.9) +
                    wind.windLines(-0.4f, -1.2f, -1.1f, 5.6f, -1.2f, 1.1f, columns = 10, rows = 7, scale = 0.05f)
            )
        }
        assertTrue(aero.lastImpulse > 0f, "premise: the aero pass must be live, ${aero.lastImpulse}")
        var spread = 0f
        for (i in cloth.base until cloth.end) spread = maxOf(spread, abs(sim.state.z[i]))
        println("flag capture: peak |z| at the end %.3f".format(spread))
        assertTrue(spread > 0.1f, "premise: the flag must be out of its plane to be worth a top view, $spread")

        val strip = renderStrip(
            panels, View(-0.5, -1.3, 6.0, 2.6),
            cellWidth = 380, cellHeight = 220, axes = "xz"
        )
        publish("flag-in-wind.png", strip, panels.size, minInk = 0.006, minChange = 0.01)
    }

    /**
     * A verdict mark for the corner of a panel: a red cross for "this is the failure" and a green
     * tick for "this is what it should look like". Drawn in *panel* coordinates and mapped like
     * everything else, so a six-panel comparison of separate scenes is readable without captions —
     * which matters, because a strip of wrong-then-right pictures with no labels is a Rorschach
     * test.
     */
    private fun verdict(ok: Boolean, view: View): List<FLine> {
        val s = view.w * 0.045
        val cx = view.x + view.w * 0.09
        val cy = view.y + view.h * 0.91
        val line = FLine()
        if (ok) {
            line.moveTo(cx - s, cy - s * 0.1)
            line.lineTo(cx - s * 0.25, cy - s * 0.8)
            line.lineTo(cx + s, cy + s * 0.8)
            line.color = vec(0.42, 0.92, 0.5, 0.95)
        } else {
            line.moveTo(cx - s, cy - s)
            line.lineTo(cx + s, cy + s)
            line.moveTo(cx - s, cy + s)
            line.lineTo(cx + s, cy - s)
            line.color = vec(1.0, 0.35, 0.32, 0.95)
        }
        line.thickness = 3.0
        return listOf(line)
    }

    /**
     * Six panels: three failure modes of a world layer, each beside the same scene done right.
     *
     * This is the capture the debug view's own argument demands. `SimdVerletDebugLines` exists
     * because "a constraint that was never wired, a neighbour index pointing at the wrong particle
     * and a pin that isn't pinned all look obviously wrong there and look like nothing at all in a
     * shaded mesh". If that is true of the solver it has to be true of the world on top of it, and
     * the way to find out is to build the three wrong versions on purpose and look.
     *
     * 1. A **penalty-force collider** against the projection. The cloth visibly sits *inside* the
     *    amber circle on the left and on it on the right.
     * 2. **Tunnelling**: the same fast drop onto the same thin ledge, with `margin = 0` and with
     *    `margin = 0.25`. A projection only ever sees where a particle *ended up*, so a particle
     *    moving further in one frame than the obstacle is thick is simply past it. One parameter
     *    apart, and it is the documented mitigation, drawn.
     * 3. **Tearing without dropping the curvature stencil.** `Cloth`'s flattening force is written
     *    from the grid, not from the constraint set, so a plain `sim.addTearing` leaves it pulling
     *    the two lips of every rip toward each other with no spring behind them: the sheet heals
     *    in the picture while the solver says it is torn. `Cloth.enableTearing` suppresses it at
     *    both lips. This pair is that claim, drawn.
     */
    @Test
    fun whatGettingItWrongLooksLike() {
        val panels = ArrayList<Panel>()

        // ---- 1. penalty force vs projection -------------------------------------------------
        val sphereY = -1.1f
        val radius = 1.0f
        val drapeView = View(-2.2, -2.6, 4.4, 4.4)
        fun drape(penalty: Float?): Panel {
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            val cloth = sim.addCloth(
                width = 18, height = 18, spacing = 0.24f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> vec((c - 8.5f) * 0.24f, 0.9f, (r - 8.5f) * 0.24f) },
                pinned = { _, _ -> false }
            )
            val marker = SphereCollider(0f, sphereY, 0f, radius)
            // The floor is a projection in both panels: it is not what is being compared, and
            // without it neither sheet stays in frame -- measured, both ended at y = -85 and -59.
            val floor = PlaneCollider(0f, 1f, 0f, offset = -2.3f).also { it.friction = 0.9f }
            if (penalty != null) {
                sim.addColliders(floor)
                sim.addBulkForce("penalty") { st, _ ->
                    for (i in 0 until st.size) {
                        val dx = st.x[i]
                        val dy = st.y[i] - sphereY
                        val dz = st.z[i]
                        val d = sqrt(dx * dx + dy * dy + dz * dz)
                        if (d < radius && d > 1e-6f) {
                            val push = penalty * (radius - d) / d
                            st.ax[i] += dx * push; st.ay[i] += dy * push; st.az[i] += dz * push
                        }
                    }
                }
            } else {
                sim.addColliders(
                    SphereCollider(0f, sphereY, 0f, radius).also { it.friction = 0.7f },
                    floor
                )
            }
            repeat(420) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16) }
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            var deepest = -10f
            for (i in cloth.base until cloth.end) {
                val y = sim.state.y[i]
                if (y < lo) lo = y
                if (y > hi) hi = y
                val dx = sim.state.x[i]
                val dy = y - sphereY
                val dz = sim.state.z[i]
                val pen = radius - sqrt(dx * dx + dy * dy + dz * dz)
                if (pen > deepest) deepest = pen
            }
            println("penalty capture: penalty=$penalty -> y in [%.2f, %.2f], deepest sphere penetration %.3f"
                .format(lo, hi, deepest))
            return Panel(
                sim.debugLines(points = false, thickness = 1.0) +
                    marker.outline(thickness = 2.0) + floor.outline(thickness = 2.0) +
                    verdict(penalty == null, drapeView),
                drapeView
            )
        }
        panels += drape(penalty = 400f)
        panels += drape(penalty = null)

        // ---- 2. tunnelling at a large timestep ----------------------------------------------
        val tunnelLowest = FloatArray(2)
        val ledgeView = View(-1.7, -2.8, 3.4, 4.8)
        fun tunnel(margin: Float): Panel {
            // Free particles, no constraints: this is about the collider and nothing else, and a
            // cloth's own springs would be the thing that failed first at any interesting speed.
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            for (r in 0 until 12) {
                for (c in 0 until 12) {
                    sim.addParticle(x0 = (c - 5.5f) * 0.09f, y0 = 1.6f + r * 0.02f, z0 = (r - 5.5f) * 0.09f)
                }
            }
            // A ledge 0.06 thick. At a tenth of a second of free fall from 1.6 units up, a
            // particle covers about 0.09 in one frame -- more than the ledge is thick.
            val ledge = BoxCollider(-1.2f, -0.03f, -1.2f, 1.2f, 0.03f, 1.2f, inside = false)
                .also { it.friction = 0.9f; it.margin = margin }
            sim.addColliders(ledge)
            repeat(58) { sim.step(dt = 1f / 60f, damping = 0.999f, gravity = -9.8f, iterations = 0) }
            var lowest = Float.POSITIVE_INFINITY
            for (i in 0 until sim.size) if (sim.state.y[i] < lowest) lowest = sim.state.y[i]
            println("tunnelling capture: margin %.2f -> lowest particle %.3f (the ledge is at 0)".format(margin, lowest))
            val through = lowest < -0.5f
            tunnelLowest[if (margin == 0f) 0 else 1] = lowest
            return Panel(
                sim.debugLines(points = true, pointSize = 1.1) +
                    ledge.outline(thickness = 2.0) + verdict(!through, ledgeView),
                ledgeView
            )
        }
        panels += tunnel(margin = 0f)
        panels += tunnel(margin = 0.25f)

        // ---- 3. tearing without dropping the curvature stencil --------------------------------
        val tearView = View(-2.9, -2.9, 5.8, 5.8)
        val healed = IntArray(2)
        val torn = IntArray(2)
        fun punch(dropCurvature: Boolean): Panel {
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            val n = 26
            val cloth = sim.addCloth(
                width = n, height = n, spacing = 0.2f, stiffness = 60f, curvatureStrength = 140f,
                position = { c, r -> vec((c - (n - 1) * 0.5f) * 0.2f, (r - (n - 1) * 0.5f) * 0.2f, 0f) },
                pinned = { c, r -> c == 0 || r == 0 || c == n - 1 || r == n - 1 }
            )
            val ball = SphereCollider(0f, 0f, -1.6f, radius = 0.55f).also { it.friction = 0.4f }
            sim.addColliders(ball)
            // Left: plain tearing, which knows nothing about the curvature stencil. Right: the
            // Cloth-aware version, which suppresses the stencil at both lips of every broken edge.
            val tear = if (dropCurvature) {
                cloth.enableTearing(threshold = 0.2f, maxBreaksPerStep = 4)
            } else {
                sim.addTearing(threshold = 0.2f, range = cloth.base until cloth.end, maxBreaksPerStep = 4)
            }
            repeat(560) { f ->
                ball.centerZ = (-1.6f + f * 0.007f).coerceAtMost(3.6f)
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 16)
            }
            // How big is the hole, really? Count the cloth nodes inside the radius the ball swept.
            var inHole = 0
            for (i in cloth.base until cloth.end) {
                val d = sqrt(sim.state.x[i] * sim.state.x[i] + sim.state.y[i] * sim.state.y[i])
                if (d < 0.55f) inHole++
            }
            println(
                "curvature-stencil capture: dropCurvature=$dropCurvature -> ${tear.brokenTotal} edges torn, " +
                    "$inHole nodes still inside the swept hole"
            )
            healed[if (dropCurvature) 1 else 0] = inHole
            torn[if (dropCurvature) 1 else 0] = tear.brokenTotal
            return Panel(
                sim.debugLines(points = false, thickness = 1.0) +
                    ball.outline(thickness = 2.0) + verdict(dropCurvature, tearView),
                tearView
            )
        }
        panels += punch(dropCurvature = false)
        panels += punch(dropCurvature = true)

        val strip = renderStrip(panels, drapeView, cellWidth = 280, cellHeight = 280)
        publish("what-goes-wrong.png", strip, panels.size, minInk = 0.01, minChange = 0.02)

        // Each failure mode is a number as well as a picture, and the numbers are the argument.
        assertTrue(
            tunnelLowest[0] < -0.5f && tunnelLowest[1] > 0f,
            "tunnelling must be real: margin 0 reached ${tunnelLowest[0]}, margin 0.25 reached ${tunnelLowest[1]}"
        )
        assertEquals(
            torn[0], torn[1],
            "premise: both tear panels must break the same number of edges, or they are not the same scene"
        )
        assertTrue(torn[0] >= 4, "premise: and there must be a rip to look at, ${torn[0]} edges")
        assertEquals(
            0, healed[1],
            "with the curvature stencil dropped the hole must be empty, ${healed[1]} nodes inside it"
        )
        assertTrue(
            healed[0] >= 12,
            "and without it the sheet must visibly heal across the rip -- that is the whole reason " +
                "Cloth.enableTearing exists. Only ${healed[0]} nodes were pulled back into the hole"
        )

        // Each pair must be a *different picture*, or the failure mode is not being shown.
        for (pair in 0 until 3) {
            val d = differingFraction(strip, pair * 2, pair * 2 + 1, panels.size)
            println("failure pair $pair: the wrong and right panels differ on %.1f %% of pixels".format(d * 100))
            assertTrue(d > 0.01, "pair $pair must show a visible difference, only ${d * 100} %")
        }
    }
}
