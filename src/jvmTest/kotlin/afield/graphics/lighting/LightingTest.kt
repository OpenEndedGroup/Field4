package afield.graphics.lighting

import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.bvh
import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The physics the lighting helpers promise, checked against closed forms: the inverse-square law,
 * the cosine factor, the on-axis irradiance of a Lambertian panel, and energy kept in a closed
 * room. The shadow test is the one the "Lights and Shadows" deck is built on: the same power from
 * a bigger source lights open ground as brightly and draws a wider penumbra.
 */
class LightingTest {

    private val up = Vec3(0f, 1f, 0f)

    @Test
    fun aPointLightFallsOffAsTheInverseSquareOfDistance() {
        val power = 100.0
        for (d in listOf(0.5, 1.0, 2.0, 4.0)) {
            val e = irradianceAt(Vec3(0f, 0f, 0f), up, PointLight(Vec3(0f, d.toFloat(), 0f), power)).x
            assertEquals(power / (4 * PI * d * d), e.toDouble(), 1e-4 * e, "E = Φ / (4π d²) at d = $d")
        }
        val near = irradianceAt(Vec3(0f, 0f, 0f), up, PointLight(Vec3(0f, 1f, 0f), power)).x
        val far = irradianceAt(Vec3(0f, 0f, 0f), up, PointLight(Vec3(0f, 2f, 0f), power)).x
        assertEquals(0.25, (far / near).toDouble(), 1e-5, "twice as far, a quarter of the light")
    }

    @Test
    fun tiltingTheSurfaceScalesIrradianceByTheCosine() {
        val light = PointLight(Vec3(0f, 3f, 0f), 50.0)
        val straight = irradianceAt(Vec3(0f, 0f, 0f), up, light).x.toDouble()
        for (degrees in listOf(0.0, 30.0, 60.0, 80.0)) {
            val a = degrees * PI / 180
            val normal = Vec3(sin(a).toFloat(), cos(a).toFloat(), 0f)
            val tilted = irradianceAt(Vec3(0f, 0f, 0f), normal, light).x.toDouble()
            assertEquals(cos(a), tilted / straight, 1e-5, "E(θ) / E(0) = cos θ at $degrees°")
        }
        val away = irradianceAt(Vec3(0f, 0f, 0f), Vec3(0f, -1f, 0f), light).x
        assertEquals(0f, away, "a surface facing away receives nothing")
    }

    @Test
    fun aSphereLightSeenFromOutsideLightsLikeAPointOfTheSamePower() {
        for (radius in listOf(0.1, 0.5, 0.9)) {
            val sphere = SphereLight(Vec3(0f, 2f, 0f), radius, power = 80.0, samples = 4096)
            val point = PointLight(Vec3(0f, 2f, 0f), power = 80.0)
            val es = irradianceAt(Vec3(0f, 0f, 0f), up, sphere).x.toDouble()
            val ep = irradianceAt(Vec3(0f, 0f, 0f), up, point).x.toDouble()
            assertEquals(ep, es, ep * 0.02, "a sphere of radius $radius fully above the horizon matches the point")
        }
    }

    @Test
    fun aSmallPanelOnAxisGivesItsLambertianIntensity() {
        // A one-sided Lambertian panel of power Φ has on-axis intensity L·A = Φ / π.
        val d = 5.0
        val panel = RectLight(Vec3(0f, d.toFloat(), 0f), width = 0.1, height = 0.1, power = 30.0)
        val e = irradianceAt(Vec3(0f, 0f, 0f), up, panel).x.toDouble()
        assertEquals(30.0 / (PI * d * d), e, 1e-3 * e)
        // Seen from beside the panel, edge on, it gives nothing; from behind, nothing either.
        assertEquals(0f, irradianceAt(Vec3(0f, 6f, 0f), Vec3(0f, -1f, 0f), panel).x, "behind the panel")
    }

    @Test
    fun theSamePowerFromABiggerSourceCastsASofterShadow() {
        // A wide slab at height 0.5 covers x < 0; the light is 2 above the floor, over the slab's edge.
        val slab = panelBoxMesh(6.0, 0.05, 6.0, divisions = 2).translated(-3.0, 0.5, 0.0)
        val tree = slab.bvh()
        fun profile(radius: Double): List<Pair<Double, Double>> {
            val light = SphereLight(Vec3(0f, 2f, 0f), radius, power = 100.0, samples = 2048)
            return (-60..60).map { i ->
                val x = i / 100.0
                x to visibilityAt(Vec3(x.toFloat(), 0f, 0f), up, light, tree)
            }
        }
        fun penumbra(p: List<Pair<Double, Double>>): Double {
            val dark = p.last { it.second <= 0.1 }.first
            val bright = p.first { it.second >= 0.9 }.first
            return bright - dark
        }
        val small = profile(0.05)
        val large = profile(0.4)
        // Similar triangles: the penumbra is 2r · h / (H - h) = 2r / 3 across, and 10–90 % is most of it.
        assertTrue(penumbra(small) < 0.05, "a 5 cm source draws a nearly sharp edge, got ${penumbra(small)}")
        assertTrue(penumbra(large) > 0.15, "a 40 cm source draws a wide penumbra, got ${penumbra(large)}")
        assertTrue(penumbra(large) > 4 * penumbra(small), "and the bigger source's edge is much softer")
        // Visibility rises monotonically across the edge.
        for (pass in listOf(small, large)) pass.zipWithNext().forEach { (a, b) -> assertTrue(b.second >= a.second - 0.02, "monotone at ${a.first}") }
        // Out in the open, away from the slab, the two give the same light: the power is the same.
        val open = Vec3(1.5f, 0f, 0f)
        val es = irradianceAt(open, up, SphereLight(Vec3(0f, 2f, 0f), 0.05, 100.0, samples = 2048), tree).x
        val el = irradianceAt(open, up, SphereLight(Vec3(0f, 2f, 0f), 0.4, 100.0, samples = 2048), tree).x
        assertEquals(es.toDouble(), el.toDouble(), es * 0.03, "same power, same light in the open")
    }

    @Test
    fun sightLinesSplitAtTheShadowEdgeAsVisibilityDoes() {
        val slab = panelBoxMesh(6.0, 0.05, 6.0, divisions = 2).translated(-3.0, 0.5, 0.0)
        val tree = slab.bvh()
        val lamp = SphereLight(Vec3(0f, 2f, 0f), 0.4, power = 100.0, samples = 512)
        fun clearShare(x: Float) = lamp.sightLines(Vec3(x, 0f, 0f), up, tree).let { l -> l.count { it.clear }.toDouble() / l.size }
        assertEquals(1.0, clearShare(1.5f), "out in the open every line is clear")
        assertEquals(0.0, clearShare(-1.5f), "deep under the slab none is")
        val edge = clearShare(0f)
        assertTrue(edge > 0.2 && edge < 0.8, "at the edge some are and some are not, got $edge")
        // Only the half of the ball facing the point is sampled.
        assertTrue(lamp.sightLines(Vec3(1.5f, 0f, 0f), up).size in 200..312)
    }

    @Test
    fun aClosedWhiteRoomReturnsAlbedoTimesTheLightEachBounce() {
        // Inside a sphere, the form factors from any point to the whole wall sum to 1, so with a light
        // at the centre every bounce is the albedo times the one before, everywhere.
        val radius = 2.0
        val room = icosphereMesh(3, radius).flipped().withColor(0.5, 0.5, 0.5)
        val solve = Radiosity(room, listOf(PointLight(Vec3(0f, 0f, 0f), power = 100.0)), bounces = 2)
        val e0 = 100.0 / (4 * PI * radius * radius)
        fun mean(a: FloatArray) = a.filterIndexed { i, _ -> i % 3 == 0 }.average()
        assertEquals(e0, mean(solve.irradiance[0]), e0 * 0.01, "direct light on the wall")
        assertEquals(0.5 * e0, mean(solve.irradiance[1]), e0 * 0.05, "first bounce = ρ × direct")
        assertEquals(0.25 * e0, mean(solve.irradiance[2]), e0 * 0.05, "second bounce = ρ² × direct")
    }

    @Test
    fun aRedWallBleedsRedOntoAWhiteFloor() {
        val floor = floorMesh(2.0, 2.0, divisions = 8).withColor(0.8, 0.8, 0.8)
        val wall = floorMesh(2.0, 1.0, divisions = 8)
            // Stand it up at z = -1, facing +z.
            .transformed { Vec3(it.x, -it.z + 0.5f, -1f) }
            .let { it.withNormals(FloatArray(it.vertexCount * 3) { i -> if (i % 3 == 2) 1f else 0f }) }
            .withColor(0.8, 0.05, 0.05)
        val solve = Radiosity(floor + wall, listOf(PointLight(Vec3(0f, 1.5f, 0f), 50.0)), bounces = 1)
        val bounce = solve.irradiance[1]
        // A floor vertex near the wall: more red than green arrives by the bounce.
        val v = (0 until floor.vertexCount).minBy { (floor.z(it) + 0.75f).let { d -> d * d } + floor.x(it) * floor.x(it) }
        assertTrue(bounce[v * 3] > 3 * bounce[v * 3 + 1], "red ${bounce[v * 3]} vs green ${bounce[v * 3 + 1]}")
        // Direct light is white: the red comes only from the bounce.
        val direct = solve.irradiance[0]
        assertEquals(direct[v * 3], direct[v * 3 + 1], 1e-4f)
    }

    @Test
    fun litMapsRadianceThroughExposureAndTone() {
        val floor = floorMesh(1.0, 1.0, divisions = 1)
        val light = PointLight(Vec3(0f, 1f, 0f), power = 4 * PI)
        val e = floor.irradiance(light)
        val shaded = floor.lit(light, exposure = 0.5)
        for (v in 0 until floor.vertexCount) {
            val expected = (0.5 * DEFAULT_ALBEDO * e[v * 3] / PI).coerceAtMost(1.0)
            assertEquals(expected, shaded.colors!![v * 3].toDouble(), 1e-6)
        }
        assertEquals(1.0, ToneMap.CLAMP.apply(7.0))
        assertTrue(ToneMap.EXPONENTIAL.apply(7.0) < 1.0 && ToneMap.EXPONENTIAL.apply(7.0) > 0.99)
    }
}
