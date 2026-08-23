package afield.four.physics

import afield.graphics.FLine
import afield.graphics.outputTo
import afield.stdlib.color
import afield.stdlib.vec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
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

    /** One panel of a filmstrip: the geometry of one frame. */
    private class Panel(val lines: List<FLine>)

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
                val ox = n * cellWidth + padding + (innerW - view.w * scale) * 0.5
                val oy = padding + (innerH - view.h * scale) * 0.5
                // Screen y grows down; every scene here is written with y up, so the panel flips.
                val mapped = panel.lines.map { line ->
                    line.transform { p ->
                        val a = if (axes == "xz") p.x else p.x
                        val b = if (axes == "xz") p.z else p.y
                        vec(
                            (a - view.x) * scale + ox,
                            (view.h - (b - view.y)) * scale + oy,
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
}
