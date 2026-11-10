package afield.graphics.scene.fabricated

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `PathTraceEnvironment.fromAtmosphere` on the CPU: the bake the path tracer is handed. A high sun gives a
 * sky whose brightest texel is the sun and whose upper half is bluer than red; a sun below the horizon
 * leaves no texel brighter than the twilight glow around it.
 */
class PathTraceEnvironmentAtmosphereTest {
    private fun brightest(env: PathTraceEnvironment): Pair<Int, Float> {
        var best = -1; var value = -1f
        for (i in 0 until env.width * env.height) {
            val v = env.pixels[i * 4] + env.pixels[i * 4 + 1] + env.pixels[i * 4 + 2]
            if (v > value) { value = v; best = i }
        }
        return best to value
    }

    @Test
    fun highSunIsTheBrightestTexelInABlueSky() {
        val env = PathTraceEnvironment.fromAtmosphere(sunElevation = 45.0, width = 64, height = 32)
        val (index, value) = brightest(env)
        val row = index / env.width
        assertTrue(row < env.height / 2, "the sun texel (row $row) should be in the upper half")
        var r = 0.0; var b = 0.0
        for (j in 0 until env.height / 2) for (k in 0 until env.width) {
            r += env.pixels[(j * env.width + k) * 4]; b += env.pixels[(j * env.width + k) * 4 + 2]
        }
        assertTrue(b > r, "the sky above the horizon should be bluer than red (r=$r b=$b)")
        val median = (0 until env.width * env.height).map { env.pixels[it * 4 + 2] }.sorted()[env.width * env.height / 2]
        assertTrue(value > 100 * median, "the sun texel ($value) should dwarf the median sky ($median)")
    }

    @Test
    fun sunBelowTheHorizonAddsNoDisc() {
        val env = PathTraceEnvironment.fromAtmosphere(sunElevation = -3.0, width = 64, height = 32)
        val (_, value) = brightest(env)
        val high = brightest(PathTraceEnvironment.fromAtmosphere(sunElevation = 45.0, width = 64, height = 32)).second
        assertTrue(value < high / 100, "no sun disc below the horizon: brightest $value vs noon sun $high")
    }
}
