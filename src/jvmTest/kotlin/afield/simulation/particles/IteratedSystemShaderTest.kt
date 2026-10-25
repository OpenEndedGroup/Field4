package afield.simulation.particles

import afield.app.protocol.Rect
import afield.graphics.shader.Introspection
import afield.graphics.shader.ShaderIncludeResolution
import afield.graphics.shader.ShaderIncludeResolver
import afield.graphics.shader.ShaderKind
import afield.graphics.vulkan.targets.PointAccumulation
import afield.graphics.vulkan.targets.PointAccumulationSettings
import afield.graphics.vulkan.targets.PointShaders
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seams of [IteratedSystem] and [PointAccumulation] that can be checked without a GPU.
 *
 * Both bind push constants *by GLSL name* (`mesh.push.bind("steps") { ... }`), so a rename on
 * either side compiles in Kotlin and fails only on a machine with a Vulkan device, as a push
 * constant error in the log and a black window. So the packaged shaders are compiled with shaderc
 * here, their push-constant blocks introspected, and the names compared with the ones the Kotlin
 * binds. Every system in the `fern_iterated_systems` starting point is compiled against the
 * `points/system` harness too, which is what a student's first edit starts from.
 */
class IteratedSystemShaderTest {

    private val pointsInclude = ShaderIncludeResolver { request ->
        val path = request.requestedPath.trim()
        if (path.startsWith("points/") && PointShaders.has(path.removePrefix("points/")))
            ShaderIncludeResolution("points/$path", PointShaders.source(path.removePrefix("points/")))
        else null
    }

    private fun introspect(name: String, source: String, kind: ShaderKind): Introspection {
        val introspection = Introspection(source, kind, sourceName = name, includeResolver = pointsInclude)
        assertTrue(introspection.valid, "$name did not compile and introspect")
        return introspection
    }

    private fun pushNames(introspection: Introspection): Set<String> =
        introspection.pushConstants.flatMap { block -> block.members.map { it.name } }.toSet()

    @Test
    fun theAccumulationPassesDeclareWhatTheKotlinBinds() {
        val fade = introspect("fade", PointShaders.source("fade_fragment.glsl"), ShaderKind.FRAGMENT_SHADER)
        assertEquals(setOf("keep", "clearRequested"), pushNames(fade))

        val points = introspect("points", PointShaders.source("points_vertex.glsl"), ShaderKind.VERTEX_SHADER)
        assertEquals(setOf("points", "viewport", "targetSize", "intensity"), pushNames(points))
        assertTrue(points.inputs.isEmpty(), "the point quads are built from gl_VertexIndex, with no attributes")
        introspect("points-fragment", PointShaders.source("points_fragment.glsl"), ShaderKind.FRAGMENT_SHADER)

        val display = introspect("display", PointShaders.source("display_fragment.glsl"), ShaderKind.FRAGMENT_SHADER)
        assertEquals(setOf("background", "exposure", "whitePoint", "toneMap", "over"), pushNames(display))

        val vertex = introspect("fullscreen", PointShaders.source("fullscreen_vertex.glsl"), ShaderKind.VERTEX_SHADER)
        assertTrue(vertex.inputs.isEmpty(), "fullscreenTriangle needs a vertex shader without attributes")
    }

    @Test
    fun everySystemInTheStartingPointCompilesAgainstTheHarness() {
        val transcript = File("dev_docs/agents/transcripts/fern_iterated_systems.yaml")
        assertTrue(transcript.isFile, "missing ${transcript.path}")
        @Suppress("UNCHECKED_CAST")
        val operations = (Yaml().load<Map<String, Any?>>(transcript.readText())["operations"] as List<Map<String, Any?>>)
        val computes = operations.mapNotNull { op ->
            val create = op["create"] as? Map<*, *> ?: return@mapNotNull null
            val compute = (create["properties"] as? Map<*, *>)?.get("compute") as? String ?: return@mapNotNull null
            create["box"] as String to compute
        }
        assertEquals(4, computes.size, "the starting point has four systems: ${computes.map { it.first }}")

        val expected = setOf("particles", "points", "count", "steps", "maxSteps", "frame", "seed", "reset", "dt")
        computes.forEach { (box, source) ->
            assertTrue(source.contains("#include \"points/system\""), "$box should build on the harness")
            assertEquals(expected, pushNames(introspect(box, source, ShaderKind.COMPUTE_SHADER)), box)
        }
    }

    @Test
    fun thePointsIncludeIsFoundWithOrWithoutItsExtension() {
        assertTrue(PointShaders.has("system"))
        assertTrue(PointShaders.has("system.glsl"))
        assertFalse(PointShaders.has("nothing"))
        assertEquals(PointShaders.source("system"), PointShaders.source("system.glsl"))
    }

    @Test
    fun aViewportGrowsToTheTargetShapeAboutItsCentre() {
        val settings = PointAccumulationSettings().apply { viewport(-3.1, 3.1, 0.0, 10.4) }
        assertEquals(Rect(-3.1, 0.0, 6.2, 10.4), settings.viewport)

        // a tall fern in a square target: x widens, y is kept
        val square = PointAccumulation.fitViewport(settings.viewport, 1.0)
        assertEquals(-5.2, square.x.toDouble(), 1e-5)
        assertEquals(0.0, square.y.toDouble(), 1e-5)
        assertEquals(5.2, square.z.toDouble(), 1e-5)
        assertEquals(10.4, square.w.toDouble(), 1e-5)

        // a wide viewport in a tall target: x is kept, y grows about the middle
        val tall = PointAccumulation.fitViewport(Rect(0.0, 0.0, 2.0, 1.0), 1.0)
        assertEquals(0.0, tall.x.toDouble(), 1e-6)
        assertEquals(-0.5, tall.y.toDouble(), 1e-6)
        assertEquals(2.0, tall.z.toDouble(), 1e-6)
        assertEquals(1.5, tall.w.toDouble(), 1e-6)

        // no aspect: as given
        val asGiven = PointAccumulation.fitViewport(Rect(1.0, 2.0, 3.0, 4.0), 0.0)
        assertEquals(listOf(1.0, 2.0, 4.0, 6.0), listOf(asGiven.x, asGiven.y, asGiven.z, asGiven.w).map { it.toDouble() })
    }
}
