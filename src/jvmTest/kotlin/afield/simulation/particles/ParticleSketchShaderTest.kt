package afield.simulation.particles

import afield.graphics.shader.Introspection
import afield.graphics.shader.ShaderIncludeResolution
import afield.graphics.shader.ShaderIncludeResolver
import afield.graphics.shader.ShaderKind
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seams of [ParticleSketch] that can be checked without a GPU.
 *
 * The Kotlin binds push constants by GLSL name and writes the world record and reads the particle
 * struct by byte offset, so a rename or a reordered field on either side compiles in Kotlin and
 * fails only on a machine with a Vulkan device, as garbage motion or a black window. So the
 * packaged shaders are compiled with shaderc here, their push constants compared with what the
 * Kotlin binds, the two copies of the particle struct compared with each other, and the structs'
 * std430 sizes with the sizes the Kotlin assumes. Every preset in the `particles_sketchbook`
 * starting point is compiled against the harness too, which is what a student's first edit
 * starts from.
 */
class ParticleSketchShaderTest {

    private val include = ShaderIncludeResolver { request ->
        val path = request.requestedPath.trim()
        if (path.startsWith("particles/") && ParticleShaders.has(path.removePrefix("particles/")))
            ShaderIncludeResolution("particles/$path", ParticleShaders.source(path.removePrefix("particles/")))
        else null
    }

    private fun introspect(name: String, source: String, kind: ShaderKind): Introspection {
        val introspection = Introspection(source, kind, sourceName = name, includeResolver = include)
        assertTrue(introspection.valid, "$name did not compile and introspect")
        return introspection
    }

    private fun pushNames(introspection: Introspection): Set<String> =
        introspection.pushConstants.flatMap { block -> block.members.map { it.name } }.toSet()

    private val harnessPush = setOf("src", "dst", "strokes", "spawns", "world", "gridValues", "cellStart", "cellEnd", "phase")

    @Test
    fun theDrawingPassesDeclareWhatTheKotlinBinds() {
        val strokes = introspect("strokes", ParticleShaders.source("strokes_vertex"), ShaderKind.VERTEX_SHADER)
        assertEquals(setOf("strokes", "size", "targetSize", "capacity", "layer"), pushNames(strokes))
        assertTrue(strokes.inputs.isEmpty(), "the stroke quads are built from gl_VertexIndex, with no attributes")

        val dots = introspect("dots", ParticleShaders.source("dots_vertex"), ShaderKind.VERTEX_SHADER)
        assertEquals(setOf("particles", "size", "targetSize", "count"), pushNames(dots))
        assertTrue(dots.inputs.isEmpty(), "the dot quads are built from gl_VertexIndex, with no attributes")

        introspect("shape", ParticleShaders.source("shape_fragment"), ShaderKind.FRAGMENT_SHADER)
        val display = introspect("display", ParticleShaders.source("display_fragment"), ShaderKind.FRAGMENT_SHADER)
        assertEquals(setOf("background"), pushNames(display))
    }

    @Test
    fun theHarnessCompilesWithEmptyHooks() {
        val source = """
            #version 450
            #include "particles/sketch"
            void spawn(vec2 at) {}
            void eachPair(Particle other, Pair pair) {}
            void update() {}
            void draw() {}
        """.trimIndent()
        assertEquals(harnessPush, pushNames(introspect("empty", source, ShaderKind.COMPUTE_SHADER)))
    }

    @Test
    fun everyVerbCompilesAsAPresetWouldCallIt() {
        val source = """
            #version 450
            #include "particles/sketch"
            void spawn(vec2 at) {
                p.pos = at + vec2(rand(-6.0, 6.0), rand(-6.0, 6.0));
                p.color = hsla(float(randInt(150, 210)), 92.0, 72.0, 0.95);
                p.group = randInt(0, 2);
            }
            void eachPair(Particle other, Pair pair) {
                spring(other, pair, 30.0, 4.8, 0.18);
                collide(other, pair, 0.9);
                collide(other, pair, 0.9, 1.0);
                link(other, rgba(120.0, 255.0, 210.0, 0.12), 1.0);
            }
            void update() {
                int n = flock(82.0, 2.2, 0.82, 0.52, 125.0) + clump(96.0, 12.0, 18.0, 50.0) + neighbors(40.0);
                int j = nearest(40.0);
                if (j >= 0) attract(particle(uint(j)).pos, 3.0);
                force(vec2(noise2D(p.pos.x * 0.004, t), triWave(t) + tri01(t)) * float(n));
                impulse(vec2(lerp(0.0, 1.0, 0.5)));
                springTo(vec2(width, height) * 0.5, 110.0, 1.8, 0.15);
                oscillate(vec2(1.0, 0.0), 24.0, 0.48, p.seed * 0.03);
                drag(0.002);
                limitSpeed(175.0);
                bounce(0.88);
                bounce(0.88, 2.0);
                wrap(16.0);
                if (mouseInside && mouseDown) p.color = rgba(255.0, 255.0, 255.0, 1.0);
            }
            void draw() {
                ring(mouse, 18.0, rgba(255.0, 120.0, 120.0, 0.25), 2.0);
                cross(mouse, 8.0, rgba(255.0, 240.0, 220.0, 0.9), 1.5);
                circle(mouse, 2.8, rgba(255.0, 245.0, 225.0, 0.95));
                line(vec2(0.0), mouse, rgba(150.0, 220.0, 255.0, 0.14), 1.0);
            }
        """.trimIndent()
        assertEquals(harnessPush, pushNames(introspect("verbs", source, ShaderKind.COMPUTE_SHADER)))
    }

    @Test
    fun theParticleStructIsTheSameInTheHarnessAndTheDots() {
        val harness = struct(ParticleShaders.source("sketch"), "Particle")
        val dots = struct(ParticleShaders.source("dots_vertex"), "Particle")
        assertEquals(harness, dots, "sketch.glsl and dots_vertex.glsl must declare the same Particle")
        assertEquals(24 * 4, std430Size(harness), "ParticleSketch allocates 24 floats per particle")
    }

    @Test
    fun theWorldRecordIsTheSizeTheKotlinWrites() {
        val world = struct(ParticleShaders.source("sketch"), "WorldState")
        // ParticleSketch writes 4 + 4 * 2 + 7 floats and then 3 + 8 integers, 30 words, into a
        // 128-byte buffer: std430 pads the struct to its vec4 alignment.
        assertEquals(128, std430Size(world))
        assertEquals(
            listOf("vec4", "vec2", "vec2", "vec2", "vec2") + List(7) { "float" } + List(3) { "int" } + List(8) { "uint" },
            world.map { it.first },
            "the order ParticleSketch writes the world record in"
        )
    }

    @Test
    fun everyPresetInTheStartingPointCompilesAgainstTheHarness() {
        val transcript = File("dev_docs/agents/transcripts/particles_sketchbook.yaml")
        assertTrue(transcript.isFile, "missing ${transcript.path}")
        @Suppress("UNCHECKED_CAST")
        val operations = (Yaml().load<Map<String, Any?>>(transcript.readText())["operations"] as List<Map<String, Any?>>)
        val computes = operations.mapNotNull { op ->
            val create = op["create"] as? Map<*, *> ?: return@mapNotNull null
            val compute = (create["properties"] as? Map<*, *>)?.get("compute") as? String ?: return@mapNotNull null
            create["box"] as String to compute
        }
        assertTrue(computes.size >= 5, "the starting point has a lead box and presets: ${computes.map { it.first }}")
        computes.forEach { (box, source) ->
            assertTrue(source.contains("#include \"particles/sketch\""), "$box should build on the harness")
            assertEquals(harnessPush, pushNames(introspect(box, source, ShaderKind.COMPUTE_SHADER)), box)
        }
    }

    /** The (type, name) members of `struct name { ... }` in [source]. */
    private fun struct(source: String, name: String): List<Pair<String, String>> {
        val body = Regex("struct\\s+$name\\s*\\{([^}]*)\\}").find(source)?.groupValues?.get(1)
            ?: error("no struct $name")
        return body.lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line ->
                val type = line.substringBefore(' ')
                line.substringAfter(' ').removeSuffix(";").split(',').map { type to it.trim() }
            }
    }

    /** The std430 size of a struct of scalars, vec2s and vec4s. */
    private fun std430Size(members: List<Pair<String, String>>): Int {
        var offset = 0
        var largest = 4
        members.forEach { (type, _) ->
            val (size, align) = when (type) {
                "float", "int", "uint" -> 4 to 4
                "vec2" -> 8 to 8
                "vec4" -> 16 to 16
                else -> error("std430Size does not know $type")
            }
            largest = maxOf(largest, align)
            offset = (offset + align - 1) / align * align + size
        }
        return (offset + largest - 1) / largest * largest
    }
}
