package afield.graphics.scene.fabricated

import afield.graphics.shader.Introspection
import afield.graphics.shader.ShaderIncludeResolution
import afield.graphics.shader.ShaderIncludeResolver
import afield.graphics.shader.ShaderKind
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What can be checked about [SplatShader], [SplatSet] and [ProceduralSplats] without a GPU.
 * The sort itself is measured on a GPU by `four.splat-shader.smoke` (`SplatShaderSortSmoke`).
 *
 * [SplatShader] binds the insert's push constants *by GLSL name*, so a rename on either side
 * compiles in Kotlin and fails only on a machine with a Vulkan device. So the `splat/insert` and
 * `splat/end` pair is compiled with shaderc here, around every insert in the `splat_shader`
 * starting point, and the push-constant names compared with the ones the Kotlin binds.
 */
class SplatShaderTest {

    private val splatInclude = ShaderIncludeResolver { request ->
        val path = request.requestedPath.trim()
        if (path.startsWith("splat/") && SplatShader.hasInclude(path.removePrefix("splat/")))
            ShaderIncludeResolution("splat/$path", SplatShader.include(path.removePrefix("splat/")))
        else null
    }

    private fun introspect(name: String, source: String): Introspection {
        val introspection = Introspection(source, ShaderKind.COMPUTE_SHADER, sourceName = name, includeResolver = splatInclude)
        assertTrue(introspection.valid, "$name did not compile and introspect")
        return introspection
    }

    private fun pushNames(introspection: Introspection): Set<String> =
        introspection.pushConstants.flatMap { block -> block.members.map { it.name } }.toSet()

    private val bound = setOf("splatsIn", "splatsOut", "splatCount", "t")

    @Test
    fun everyInsertInTheStartingPointCompilesAndDeclaresWhatTheKotlinBinds() {
        val transcript = File("dev_docs/agents/transcripts/splat_shader.yaml")
        assertTrue(transcript.isFile, "missing ${transcript.path}")
        @Suppress("UNCHECKED_CAST")
        val operations = Yaml().load<Map<String, Any?>>(transcript.readText())["operations"] as List<Map<String, Any?>>
        val computes = operations.mapNotNull { op ->
            val create = op["create"] as? Map<*, *> ?: return@mapNotNull null
            val compute = (create["properties"] as? Map<*, *>)?.get("compute") as? String ?: return@mapNotNull null
            create["box"] as String to compute
        }
        assertEquals(7, computes.size, "a passthrough and six presets: ${computes.map { it.first }}")
        computes.forEach { (box, source) ->
            assertTrue(source.contains("#include \"splat/insert\"") && source.contains("#include \"splat/end\""),
                "$box should bracket its insert with the two includes")
            assertEquals(bound, pushNames(introspect(box, source)), box)
        }
    }

    @Test
    fun theBareInsertSourceIsTheSameShader() {
        val introspection = introspect("computeSource", SplatShader.computeSource("pos.z = -pos.z;"))
        assertEquals(bound, pushNames(introspection))
    }

    @Test
    fun theIncludesAreFoundWithOrWithoutTheirExtension() {
        assertTrue(SplatShader.hasInclude("insert"))
        assertTrue(SplatShader.hasInclude("end.glsl"))
        assertFalse(SplatShader.hasInclude("system"))
        assertEquals(SplatShader.include("insert"), SplatShader.include("insert.glsl"))
    }

    @Test
    fun theSixCloudsAreTheDemosSixClouds() {
        val counts = ProceduralSplats.all.mapValues { it.value().count }
        // icosphere: an octahedron subdivided four times has 8 * 4^4 / 2 + 2 vertices
        assertEquals(1026, counts["icosphere shell"])
        assertEquals(7200 - 28, counts["fern plume"])
        assertEquals(240 * 14, counts["nautilus shell"])
        assertEquals(2800, counts["phyllotaxis bloom"])
        assertEquals(340 * 10, counts["torus braid"])
        // trunk (25 discs) → 2 → 4 → 12 → 36 branches with 21, 17, 13, 9 discs
        assertEquals(25 + 2 * 21 + 4 * 17 + 12 * 13 + 36 * 9, counts["coral branches"])
    }

    @Test
    fun everyCloudIsFiniteDeterministicAndInRange() {
        for ((name, build) in ProceduralSplats.all) {
            val a = build()
            val b = build()
            assertTrue(a.floats.copyOf(a.count * SplatSet.STRIDE).contentEquals(b.floats.copyOf(b.count * SplatSet.STRIDE)),
                "$name should build the same splats twice")
            for (i in 0 until a.count) {
                val o = i * SplatSet.STRIDE
                val f = a.floats
                for (k in 0 until SplatSet.STRIDE) assertTrue(f[o + k].isFinite(), "$name splat $i float $k")
                assertTrue(f[o + 3] in 0f..1f, "$name splat $i opacity ${f[o + 3]}")
                for (k in 4..6) assertTrue(f[o + k] in 0f..1f, "$name splat $i colour")
                for (k in 8..10) assertTrue(f[o + k] > 0f, "$name splat $i scale")
                val q = sqrt(f[o + 12] * f[o + 12] + f[o + 13] * f[o + 13] + f[o + 14] * f[o + 14] + f[o + 15] * f[o + 15])
                assertEquals(1f, q, 1e-4f, "$name splat $i quaternion length")
            }
            // all six sit in roughly the box -1..1
            val (min, max) = a.bounds()
            for (v in listOf(min.x, min.y, min.z, max.x, max.y, max.z)) assertTrue(abs(v) < 2f, "$name bounds $min .. $max")
        }
    }

    @Test
    fun aQuaternionFromUnitVectorsTurnsTheOneIntoTheOther() {
        val cases = listOf(
            doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0),
            doubleArrayOf(0.6, 0.0, 0.8), doubleArrayOf(0.0, -1.0, 0.0)
        )
        for (to in cases) {
            val q = ProceduralSplats.quaternionFromUnitVectors(0.0, 0.0, -1.0, to[0], to[1], to[2])
            val turned = rotate(q, doubleArrayOf(0.0, 0.0, -1.0))
            for (k in 0..2) assertEquals(to[k], turned[k], 1e-9, "to ${to.toList()}")
        }
    }

    @Test
    fun hslIsThreeJsHslInLinear() {
        val red = ProceduralSplats.hsl(0.0, 1.0, 0.5)
        assertEquals(listOf(1.0, 0.0, 0.0), red.map { (it * 1e9).toLong() / 1e9 })
        // three.js: setHSL(0.6, 0.5, 0.5) is sRGB (0.25, 0.45, 0.75) before linearising
        val blue = ProceduralSplats.hsl(0.6, 0.5, 0.5)
        val srgb = doubleArrayOf(0.25, 0.45, 0.75)
        for (k in 0..2) assertEquals(Math.pow(srgb[k] * 0.9478672986 + 0.0521327014, 2.4), blue[k], 1e-9)
        // hue wraps
        for (k in 0..2) assertEquals(blue[k], ProceduralSplats.hsl(1.6, 0.5, 0.5)[k], 1e-12)
    }

    @Test
    fun theFitIsTheMedianAndTheNinetiethPercentileRadius() {
        val set = SplatSet()
        // nine splats on a unit circle about (5, 0, 0) and one far outlier
        for (i in 0 until 9) {
            val a = i * 2 * Math.PI / 9
            set.push(5 + Math.cos(a), Math.sin(a), 0.0, 0.01, 0.01, 0.01, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0)
        }
        set.push(500.0, 0.0, 0.0, 0.01, 0.01, 0.01, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0)
        val fit = set.fit()
        assertTrue(abs(fit.centre.x - 5f) < 0.25f, "median x ${fit.centre.x}")
        assertTrue(abs(fit.radius90 - 1f) < 0.25f, "p90 radius ${fit.radius90}, not dragged out by the outlier")
        val model = fit.model(1.08f)
        val m = model.transform(afield.math.linalg.Vector4f(fit.centre.x, fit.centre.y, fit.centre.z, 1f))
        assertEquals(0f, m.x, 1e-4f)
    }

    @Test
    fun aGlbBecomesOneDiscPerVertexFacingAlongItsNormal() {
        val glb = File("dev_docs/agents/fixtures/path_tracing/quad.glb")
        assertTrue(glb.isFile, "missing ${glb.path}")
        val set = SplatSet.fromGltf(glb.path)
        assertTrue(set.count >= 4, "a quad has at least four vertices, got ${set.count}")
        for (i in 0 until set.count) {
            val o = i * SplatSet.STRIDE
            val f = set.floats
            assertTrue(f[o + 10] < f[o + 8], "splat $i is a disc: thinner in z than across")
            assertEquals(f[o + 8], f[o + 9], "splat $i is round")
        }
    }

    private fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        val (x, y, z, w) = q.toList()
        val tx = 2 * (y * v[2] - z * v[1])
        val ty = 2 * (z * v[0] - x * v[2])
        val tz = 2 * (x * v[1] - y * v[0])
        return doubleArrayOf(
            v[0] + w * tx + y * tz - z * ty,
            v[1] + w * ty + z * tx - x * tz,
            v[2] + w * tz + x * ty - y * tx
        )
    }
}
