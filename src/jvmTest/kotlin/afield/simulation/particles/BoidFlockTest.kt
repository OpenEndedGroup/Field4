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
 * The seams of [BoidFlock] that can be checked without a GPU.
 *
 * The Kotlin writes the kind and rule tables by word offset and binds push constants by GLSL name,
 * and three shaders declare the same structs, so a field added on one side compiles everywhere
 * and shows up only on a GPU, as boids that steer by the wrong numbers. So the shaders are
 * compiled with shaderc, their push constants compared with what the Kotlin binds, the structs
 * compared across files, and their std430 layout with the offsets [BoidFlock] writes at. The
 * tables are checked against the page's `flock()`: `among` masks, the reach each kind has to
 * every other, and the grid's cell size. Every preset in the `madp_boids` starting point is
 * compiled against the harness.
 */
class BoidFlockTest {

    private val include = ShaderIncludeResolver { request ->
        val path = request.requestedPath.trim()
        if (path.startsWith("boids/") && BoidShaders.has(path.removePrefix("boids/")))
            ShaderIncludeResolution("boids/$path", BoidShaders.source(path.removePrefix("boids/")))
        else null
    }

    private fun introspect(name: String, source: String, kind: ShaderKind): Introspection {
        val introspection = Introspection(source, kind, sourceName = name, includeResolver = include)
        assertTrue(introspection.valid, "$name did not compile and introspect")
        return introspection
    }

    private fun pushNames(introspection: Introspection): Set<String> =
        introspection.pushConstants.flatMap { block -> block.members.map { it.name } }.toSet()

    private val harnessPush = setOf(
        "src", "dst", "history", "eaten", "pick", "stats", "strokes", "world",
        "gridValues", "cellStart", "cellEnd", "phase", "step", "lastStep", "t",
    )

    private val minimal = """
        #version 450
        #include "boids/flock"
        vec4 color(Boid b) { return hsl(deg(b.heading) + 190.0, 65.0, 62.0); }
    """.trimIndent()

    @Test
    fun theHarnessAndItsHooksCompileAndDeclareWhatTheKotlinBinds() {
        assertEquals(harnessPush, pushNames(introspect("minimal", minimal, ShaderKind.COMPUTE_SHADER)))
        val hooks = """
            #version 450
            #define FLOW_FIELD
            #define CUSTOM_RULE
            #include "boids/flock"
            vec4 color(Boid b) { return b.cool > 0.0 ? hex(0x7a4038u) : hsl(210.0, 20.0 + float(b.n), 50.0, 0.9); }
            float flowAngle(vec2 uv, float t) { return noise(uv.x * 2.2, uv.y * 2.2, t * 0.0015) * TAU * 2.0; }
            float bestD; vec2 bestAt; bool found;
            void customStart(int rule, Boid me) { bestD = 1e9; found = false; }
            void customNeighbour(int rule, Boid me, Boid other, vec2 d, float dist) {
                vec2 h = vec2(cos(me.heading), sin(me.heading));
                if (dot(d, h) > 0.0 && dist < bestD) { bestD = dist; bestAt = d - vec2(cos(other.heading), sin(other.heading)) * 9.0; found = true; }
            }
            vec2 customForce(int rule, Boid me) { return found ? steer(me, bestAt) : vec2(0.0); }
        """.trimIndent()
        assertEquals(harnessPush, pushNames(introspect("hooks", hooks, ShaderKind.COMPUTE_SHADER)))
    }

    @Test
    fun theDrawingPassesDeclareWhatTheKotlinBinds() {
        val trails = introspect("trails", BoidShaders.source("trails_vertex"), ShaderKind.VERTEX_SHADER)
        assertEquals(setOf("boids", "history", "world", "targetSize", "count"), pushNames(trails))
        assertTrue(trails.inputs.isEmpty())
        introspect("trail", BoidShaders.source("trail_fragment"), ShaderKind.FRAGMENT_SHADER)
        val bodies = introspect("bodies", BoidShaders.source("bodies_vertex"), ShaderKind.VERTEX_SHADER)
        assertEquals(setOf("boids", "world", "targetSize", "count"), pushNames(bodies))
        assertTrue(bodies.inputs.isEmpty())
        introspect("body", BoidShaders.source("body_fragment"), ShaderKind.FRAGMENT_SHADER)
    }

    @Test
    fun theStructsAgreeAcrossShadersAndWithTheOffsetsTheKotlinWrites() {
        val harness = BoidShaders.source("flock")
        for (other in listOf("trails_vertex", "bodies_vertex")) {
            for (name in listOf("Boid", "KindRecord", "RuleRecord", "WorldRecord")) {
                assertEquals(struct(harness, name), struct(BoidShaders.source(other), name), "$name in $other")
            }
        }
        val structs = listOf("Boid", "KindRecord", "RuleRecord", "WorldRecord").associateWith { struct(harness, it) }
        val layout = Std430(structs)
        assertEquals(16 * 4, layout.size("Boid"), "BoidFlock allocates 16 floats per boid")
        assertEquals(24 * 4, layout.size("KindRecord"), "BoidFlock writes a kind every 24 words")
        assertEquals(12 * 4, layout.size("RuleRecord"), "BoidFlock writes a rule every 12 words")
        assertEquals(1012 * 4, layout.size("WorldRecord"), "BoidFlock writes 1012 words of world")
        assertEquals(20 * 4, layout.offset("WorldRecord", "rocks"))
        assertEquals(52 * 4, layout.offset("WorldRecord", "kinds"))
        assertEquals(244 * 4, layout.offset("WorldRecord", "rules"))
        assertEquals(16 * 4, layout.offset("KindRecord", "reachA"))
        assertEquals(8 * 4, layout.offset("RuleRecord", "p"))
    }

    @Test
    fun theTablesResolveAsThePagesFlockDoes() {
        val settings = BoidFlockSettings().apply {
            kind("starling") {
                count = 700
                rules(separate(11, 1.8), align(30, 1.0), cohere(42, 0.9), flee("hawk", 90, 4.5), wander(0.1))
            }
            kind("hawk") {
                count = 2
                rules(chase("starling", 260, 1.3), eat("starling", 7, 90), separate(90, 1.5, among = "same"), wander(0.5))
            }
        }
        val t = BoidTables(settings)
        val starling = t.kinds[0].rules
        val hawk = t.kinds[1].rules
        assertEquals(0b11, t.among(starling[0], 0), "separate sees all")
        assertEquals(0b01, t.among(starling[1], 0), "align sees its own kind")
        assertEquals(0b10, t.among(starling[3], 0), "flee('hawk') sees hawks")
        assertEquals(0b01, t.among(hawk[0], 1), "chase('starling') sees starlings")
        assertEquals(0b10, t.among(hawk[2], 1), "among: 'same' for a hawk")
        assertEquals(0b01, t.among(settings.kinds.values.first().cohere(1, among = "other"), 1))
        assertEquals(0b11, t.among(settings.kinds.values.first().cohere(1, among = listOf("hawk", "starling")), 0))
        // reach: starlings look 42 at starlings (cohere) and 90 at hawks (flee, separate's 11 too)
        assertEquals(listOf(42.0 * 42.0, 90.0 * 90.0), t.reachSquared[0])
        // hawks look 260 at starlings (chase) and 90 at hawks (separate among same)
        assertEquals(listOf(260.0 * 260.0, 90.0 * 90.0), t.reachSquared[1])
        assertEquals(listOf(90.0, 260.0), t.queryRadius)
        assertEquals(90.0, t.cellSize, "cells fit the most populous kind's query radius")
        assertEquals(listOf(0, 700), t.first)
        assertEquals(702, t.count)
        // default fields of view, as the page's makeRule defaults them
        assertEquals(listOf(false, true, true, false, false), starling.map { it.fov })
    }

    @Test
    fun everyPresetInTheStartingPointCompilesAgainstTheHarness() {
        val transcript = File("dev_docs/agents/transcripts/madp_boids.yaml")
        assertTrue(transcript.isFile, "missing ${transcript.path}")
        @Suppress("UNCHECKED_CAST")
        val operations = (Yaml().load<Map<String, Any?>>(transcript.readText())["operations"] as List<Map<String, Any?>>)
        val computes = operations.mapNotNull { op ->
            val create = op["create"] as? Map<*, *> ?: return@mapNotNull null
            val compute = (create["properties"] as? Map<*, *>)?.get("compute") as? String ?: return@mapNotNull null
            create["box"] as String to compute
        }
        assertTrue(computes.size >= 5, "a lead box and presets: ${computes.map { it.first }}")
        computes.forEach { (box, source) ->
            assertTrue(source.contains("#include \"boids/flock\""), "$box should build on the harness")
            assertEquals(harnessPush, pushNames(introspect(box, source, ShaderKind.COMPUTE_SHADER)), box)
        }
    }

    /** The (type, name) members of `struct name { ... }` in [source], array lengths kept in the name. */
    private fun struct(source: String, name: String): List<Pair<String, String>> {
        val body = Regex("struct\\s+$name\\s*\\{([^}]*)\\}").find(source)?.groupValues?.get(1) ?: error("no struct $name")
        return body.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            val type = line.substringBefore(' ')
            line.substringAfter(' ').removeSuffix(";").split(',').map { type to it.trim() }
        }
    }

    /** std430 sizes and offsets for structs of scalars, vec2s, vec4s, nested structs and constant-length arrays. */
    private class Std430(val structs: Map<String, List<Pair<String, String>>>) {
        private val constants = mapOf("MAX_KINDS" to 8, "MAX_RULES" to 64, "MAX_ROCKS" to 8)

        private fun base(type: String): Pair<Int, Int> = when (type) {
            "float", "int", "uint" -> 4 to 4
            "vec2" -> 8 to 8
            "vec4" -> 16 to 16
            else -> size(type) to align(type)
        }

        fun align(struct: String): Int = structs.getValue(struct).maxOf { base(it.first).second }

        private fun walk(struct: String, until: String? = null): Int {
            var offset = 0
            for ((type, rawName) in structs.getValue(struct)) {
                val name = rawName.substringBefore('[')
                val count = rawName.substringAfter('[', "").removeSuffix("]").let { if (it.isEmpty()) 1 else constants[it] ?: it.toInt() }
                val (size, align) = base(type)
                val stride = if (count > 1) (size + align - 1) / align * align else size
                offset = (offset + align - 1) / align * align
                if (name == until) return offset
                offset += stride * count
            }
            require(until == null) { "no member $until in $struct" }
            val a = align(struct)
            return (offset + a - 1) / a * a
        }

        fun size(struct: String) = walk(struct)
        fun offset(struct: String, member: String) = walk(struct, member)
    }
}
