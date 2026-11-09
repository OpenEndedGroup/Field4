package afield.simulation.optics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraOpticsTest {
    private fun pinhole(diameter: Double, sensor: Double = 0.4) = SensorCamera(
        y = 0.0, z = -6.0, lookY = 0.0, sensorWidth = sensor, sensorHeight = sensor, sensorDistance = 1.0, apertureDiameter = diameter,
    )

    /** A smaller hole lets light through a smaller patch, so a point blurs less, and the image is dimmer in proportion to the hole's area. */
    @Test
    fun aSmallerPinholeGivesASharperButDimmerImage() {
        val point = OpticsScene.pointLight(radius = 0.03)
        val wide = pinhole(0.08).render(point, 64, 64, samples = 1024)
        val narrow = pinhole(0.02).render(point, 64, 64, samples = 1024)
        assertTrue(narrow.spotRadius() < 0.5 * wide.spotRadius(), "narrow ${narrow.spotRadius()} vs wide ${wide.spotRadius()}")

        // The same scene, the same exposure: a quarter of the diameter is a sixteenth of the light.
        val scene = OpticsScene.testScene()
        val bright = pinhole(0.08, sensor = 1.5).render(scene, 48, 32, samples = 64).mean()
        val dim = pinhole(0.02, sensor = 1.5).render(scene, 48, 32, samples = 64).mean()
        assertEquals(16.0, bright / dim, 1.0)
    }

    /** The pinhole blur of a point is the hole's shadow: a disc of diameter aperture·(1 + d/o). */
    @Test
    fun pinholeBlurMatchesTheHolesShadow() {
        val image = pinhole(0.12).render(OpticsScene.pointLight(radius = 0.01), 64, 64, samples = 2048)
        val pixel = 0.4 / 64
        val predicted = ThinLens.blurDiameter(Double.POSITIVE_INFINITY, 0.12, 6.0, 1.0) / pixel / (2 * sqrt(2.0))
        assertEquals(predicted, image.spotRadius(), 0.12 * predicted)
    }

    /** Every ray a thin lens passes from one sensor point meets the others at the conjugate point, where 1/o + 1/i = 1/f. */
    @Test
    fun theThinLensEquationHoldsForEveryRay() {
        val f = 1.0
        val i = 1.25
        val o = ThinLens.objectDistance(f, i)
        assertEquals(1.0 / f, 1.0 / o + 1.0 / i, 1e-12)
        assertEquals(i, ThinLens.imageDistance(f, o), 1e-12)
        val camera = SensorCamera(y = 0.0, z = 0.0, lookY = 0.0, lookZ = 1.0, sensorDistance = i, apertureDiameter = 0.4, focalLength = f)
        val sx = 0.07; val sy = -0.03
        val meets = (0 until 12).map { k ->
            val a = 0.2 * sqrt(k / 12.0)
            val ray = camera.ray(sx, sy, a * cos(k * 2.4), a * sin(k * 2.4))
            ray.at((o - ray.oz) / ray.dz)
        }
        // Camera +x is world -x when it looks along +z, so the inverted image point is at +sx·o/i in world x.
        for (p in meets) {
            assertEquals(sx * o / i, p[0], 1e-9)
            assertEquals(-sy * o / i, p[1], 1e-9)
            assertEquals(o, p[2], 1e-9)
        }
    }

    /** Rendered through a lens, a point is sharpest on the sensor the thin-lens equation names, and its blur elsewhere is A·|d − i|/i. */
    @Test
    fun aLensFocusesWhereTheEquationSays() {
        val f = 1.0
        val o = 6.0
        val i = ThinLens.imageDistance(f, o)
        val point = OpticsScene.pointLight(radius = 0.01)
        fun spot(d: Double) = SensorCamera(y = 0.0, z = -o, lookY = 0.0, sensorWidth = 0.3, sensorHeight = 0.3, sensorDistance = d, apertureDiameter = 0.3, focalLength = f)
            .render(point, 72, 72, samples = 2048).spotRadius()
        val focused = spot(i)
        val near = spot(0.9 * i)
        val far = spot(1.1 * i)
        assertTrue(focused < 0.3 * near && focused < 0.3 * far, "focused $focused, near $near, far $far")
        val pixel = 0.3 / 72
        val predicted = ThinLens.blurDiameter(f, 0.3, o, 0.9 * i) / pixel / (2 * sqrt(2.0))
        assertEquals(predicted, near, 0.12 * predicted)
    }

    /** Under an even sky a pinhole image falls off as cos⁴ of the angle off the axis. */
    @Test
    fun offAxisFalloffIsCosineToTheFourth() {
        val camera = SensorCamera(y = 0.0, z = 0.0, lookY = 0.0, lookZ = 1.0, sensorWidth = 2.0, sensorHeight = 0.02, sensorDistance = 1.0, apertureDiameter = 0.002)
        val image = camera.render(OpticsScene.uniformSky(), 41, 1, samples = 16)
        val centre = image[20, 0, 0]
        assertEquals(image.reference, centre.toDouble(), 1e-3 * image.reference)
        for (i in listOf(0, 5, 10, 30, 40)) {
            val sx = ((i + 0.5) / 41 - 0.5) * 2.0
            val c = 1.0 / sqrt(1 + sx * sx)
            assertEquals(c * c * c * c, image[i, 0, 0] / centre.toDouble(), 2e-3)
        }
    }

    @Test
    fun snellsLawAndTotalInternalReflection() {
        val theta = 0.6
        val t = Refraction.snellAngle(theta, 1.0, 1.5)
        assertEquals(1.0 * sin(theta), 1.5 * sin(t), 1e-12)
        val critical = Refraction.criticalAngle(1.5, 1.0)
        assertEquals(1.0 / 1.5, sin(critical), 1e-12)
        assertTrue(Refraction.snellAngle(critical + 0.01, 1.5, 1.0).isNaN())
        assertEquals(1.0, Refraction.reflectance(critical + 0.01, 1.5, 1.0))
        assertEquals(0.04, Refraction.reflectance(0.0, 1.0, 1.5), 1e-12)
        // The vector form bends a ray by the same angle.
        val d = Refraction.refract(sin(theta), -cos(theta), 0.0, 1.0, 1.0, 1.5)!!
        assertEquals(sin(t), d[0], 1e-12)
        assertEquals(-cos(t), d[1], 1e-12)
        assertTrue(Refraction.refract(sin(critical + 0.01), -cos(critical + 0.01), 0.0, 1.0, 1.5, 1.0) == null)
        assertTrue(Refraction.cauchyIndex(450.0) > Refraction.cauchyIndex(650.0), "blue bends more than red")
    }

    @Test
    fun depthOfFieldLimitsBlurByTheAcceptableCircle() {
        val f = 0.05; val a = 0.025; val focus = 3.0; val c = 0.00003
        val (near, far) = ThinLens.depthOfField(f, a, focus, c).let { it[0] to it[1] }
        assertTrue(near < focus && focus < far, "near $near, far $far")
        val sensor = ThinLens.imageDistance(f, focus)
        assertEquals(c, ThinLens.blurDiameter(f, a, near, sensor), 1e-9)
        assertEquals(c, ThinLens.blurDiameter(f, a, far, sensor), 1e-9)
        assertEquals(2.0, ThinLens.fNumber(f, a), 1e-12)
    }

    /** An image of one even colour goes through the Bayer filter and back unchanged. */
    @Test
    fun demosaicRestoresAnEvenColour() {
        val w = 8; val h = 6
        val rgb = FloatArray(w * h * 3) { floatArrayOf(0.7f, 0.3f, 0.1f)[it % 3] }
        val mosaic = SensorImage(w, h, rgb).mosaic()
        assertEquals(0f, mosaic[0, 0, 1]); assertEquals(0.7f, mosaic[0, 0, 0])
        assertEquals(0.1f, mosaic[1, 1, 2]); assertEquals(0.3f, mosaic[1, 0, 1])
        val back = mosaic.demosaic()
        for (j in 0 until h) for (i in 0 until w) for (ch in 0 until 3) assertEquals(rgb[ch], back[i, j, ch], 1e-6f)
    }

    @Test
    fun photonCountsAverageToTheIrradiance() {
        val image = SensorImage(64, 64, FloatArray(64 * 64 * 3) { 0.5f })
        val counts = image.photons(40.0, seed = 7)
        assertEquals(20.0, counts.mean(), 0.3)
        assertTrue(abs(counts[3, 3, 0] - counts[4, 3, 0]) + abs(counts[5, 9, 1] - counts[2, 8, 1]) > 0, "counts vary")
    }

    @Test
    fun polygonApertureHasTheRightArea() {
        val camera = SensorCamera(apertureDiameter = 2.0, blades = 6)
        assertEquals(6 / 2.0 * sin(2 * PI / 6), camera.apertureArea, 1e-12)
        // A corner points along +x; the middle of an edge, 30° round, is cos 30° ≈ 0.866 out.
        assertTrue(camera.insideAperture(0.99, 0.0))
        assertTrue(camera.insideAperture(0.85 * cos(PI / 6), 0.85 * sin(PI / 6)))
        assertTrue(!camera.insideAperture(0.9 * cos(PI / 6), 0.9 * sin(PI / 6)))
    }

    @Test
    fun raysEndAtTheFrameEdge() {
        val right = frameExit(100.0, 100.0, 1.0, 0.1, 640.0, 360.0)
        assertEquals(640.0, right[0], 1e-9); assertEquals(154.0, right[1], 1e-9)
        val bottom = frameExit(100.0, 100.0, 1.0, 2.0, 640.0, 360.0)
        assertEquals(230.0, bottom[0], 1e-9); assertEquals(360.0, bottom[1], 1e-9)
        val left = frameExit(100.0, 100.0, -1.0, 0.0, 640.0, 360.0)
        assertEquals(0.0, left[0], 1e-9); assertEquals(100.0, left[1], 1e-9)
    }

    /** With nothing in front of it every detector sees the same half of the world, so the picture is nearly flat. */
    @Test
    fun aBareSensorSeesAnEvenBlend() {
        val sky = SensorCamera().renderBare(OpticsScene.uniformSky(), 8, 6, samples = 64)
        assertEquals(PI, sky.mean(), 1e-5)
        fun contrast(image: SensorImage): Double {
            val m = image.mean()
            return sqrt(image.rgb.sumOf { (it - m) * (it - m) } / image.rgb.size) / m
        }
        val scene = OpticsScene.testScene()
        val bare = SensorCamera(z = -4.0).renderBare(scene, 24, 16, samples = 256)
        val pinhole = SensorCamera(z = -4.0, apertureDiameter = 0.03).render(scene, 24, 16, samples = 16)
        assertTrue(contrast(bare) < 0.25 * contrast(pinhole), "bare ${contrast(bare)}, pinhole ${contrast(pinhole)}")
    }

    /** A crest's air and glass parts meet on the surface; in glass they are λ/n apart and turned to Snell's angle. */
    @Test
    fun waveCrestsStayJoinedAcrossTheSurface() {
        val w = 640.0; val h = 360.0; val surface = 180.0; val lambda = 44.0; val n = 1.5
        for (degrees in listOf(0.0, 15.0, 40.0, 70.0)) {
            val angle = degrees * PI / 180
            val crests = Refraction.crossingCrests(angle, n, lambda, 123.4, w, h, surface)
            val turned = Refraction.snellAngle(angle, 1.0, n)
            if (degrees > 0) {
                val both = crests.filter { it.air != null && it.glass != null }
                assertTrue(both.size >= 3, "at $degrees° only ${both.size} crests cross the surface")
                for (c in both) {
                    // The end of each part that lies on the surface.
                    fun onSurface(seg: DoubleArray) = if (abs(seg[1] - surface) < abs(seg[3] - surface)) seg[0] to seg[1] else seg[2] to seg[3]
                    val (ax, ay) = onSurface(c.air!!); val (gx, gy) = onSurface(c.glass!!)
                    assertEquals(surface, ay, 1e-9); assertEquals(surface, gy, 1e-9)
                    assertEquals(ax, gx, 1e-9, "at $degrees° a crest breaks at the surface")
                }
            }
            // Glass crests: parallel lines at Snell's angle, λ/n apart.
            val glass = crests.mapNotNull { it.glass }.filter { hypot(it[2] - it[0], it[3] - it[1]) > 1 }
            for (seg in glass) {
                val len = hypot(seg[2] - seg[0], seg[3] - seg[1])
                assertEquals(abs(cos(turned)), abs(seg[2] - seg[0]) / len, 1e-9)
            }
            val offsets = glass.map { (it[0] - w / 2) * sin(turned) + (it[1] - surface) * cos(turned) }.sorted()
            for (k in 1 until offsets.size) assertEquals(lambda / n, offsets[k] - offsets[k - 1], 1e-9)
            val air = crests.mapNotNull { it.air }.filter { hypot(it[2] - it[0], it[3] - it[1]) > 1 }
            val airOffsets = air.map { (it[0] - w / 2) * sin(angle) + (it[1] - surface) * cos(angle) }.sorted()
            for (k in 1 until airOffsets.size) assertEquals(lambda, airOffsets[k] - airOffsets[k - 1], 1e-9)
        }
    }

    /** As the wave travels on past a whole wavelength nothing jumps: each crest is where one was a moment before, moved on a little. */
    @Test
    fun waveCrestsMoveSmoothlyThroughTheWrap() {
        val angle = 0.5
        val turned = Refraction.snellAngle(angle, 1.0, 1.5)
        // Each crest's distance along the wave, in air and in glass.
        fun offsets(t: Double) = Refraction.crossingCrests(angle, 1.5, 44.0, t, 640.0, 360.0, 180.0).let { crests ->
            crests.mapNotNull { it.air }.map { (it[0] - 320) * sin(angle) + (it[1] - 180) * cos(angle) } to
                crests.mapNotNull { it.glass }.map { (it[0] - 320) * sin(turned) + (it[1] - 180) * cos(turned) }
        }
        val (airBefore, glassBefore) = offsets(44.0 - 0.2)
        val (airAfter, glassAfter) = offsets(44.0 + 0.2)
        for (a in airAfter.filter { it in -150.0..-20.0 }) assertEquals(0.4, a - airBefore.filter { it < a }.max(), 1e-9)
        for (g in glassAfter.filter { it in 20.0..150.0 }) assertEquals(0.4 / 1.5, g - glassBefore.filter { it < g }.max(), 1e-9)
    }
}
