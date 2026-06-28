package afield.examples.rectsl

import afield.rectsl.*

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
FIELD_TEST
id: rectsl.language.workout
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLWorkout
tags: [smoke, examples, rectsl, animation, geometry, stacksl]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "rectsl workout passed"
*/
object RectSLWorkout {
    private const val EPS = 1e-6

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "rectsl workout failed" }
    }

    fun runTests(): Boolean {
        val tests = listOf(
            "tokenizer" to ::tokenizerParsesSurface,
            "minimal mirror mesh" to ::minimalMirrorMesh,
            "definitions and groups" to ::definitionsAndGroups,
            "selections and deep timing transforms" to ::selectionsAndDeepTimingTransforms,
            "operators and key lenses" to ::operatorsAndKeyLenses,
            "track modes and time warps" to ::trackModesAndTimeWarps,
            "layers tracks easing and repeat" to ::layersTracksEasingAndRepeat,
            "gradient and material lowering" to ::gradientAndMaterialLowering,
            "semantic analysis" to ::semanticAnalysis,
            "StackSL material shader" to ::stackSLMaterialShader
        )

        tests.forEach { (name, test) ->
            try {
                test()
                println("ok - $name")
            } catch (t: Throwable) {
                println("failed - $name: ${t.message}")
                t.printStackTrace()
                return false
            }
        }

        println("rectsl workout passed")
        return true
    }

    private fun tokenizerParsesSurface() {
        val tokens = RectLangTokenizer(
            """
            # comments are ignored
            : white 0xffffff solid fill ;
            "voice-a" rect
              '( 0 -12  8 0  18 12 ) x
            ;
            """.trimIndent()
        ).tokenize()

        require(tokens.any { it is Token.StringLit && it.value == "voice-a" })
        require(tokens.any { it is Token.ColorLit })
        val quote = tokens.filterIsInstance<Token.Quote>().single()
        require(quote.tokens.filterIsInstance<Token.Number>().size == 6) { quote }
        require(tokens.filterIsInstance<Token.Word>().any { it.name == ":" })

        val trailingQuote = RectAnimationOracle.compile(
            """
            "r" rect
              1 square
            ;
            "
            """.trimIndent()
        )
        require(!trailingQuote.ok) { "Trailing single quote should not compile" }
        require(trailingQuote.diagnostics.single().contains("exactly three quotes")) {
            trailingQuote.diagnostics
        }
    }

    private fun minimalMirrorMesh() {
        val program = compile(
            """
            wall 30 4 meters
            duration 10
            fps 60

            "r" rect
              -5 0 xy
              1 square
              0xff3300 solid fill
              '( 0 -5   5 0   10 5 ) x
            ;

            dup "r2" rename
              mirror-x
              0x3333ff solid fill
            ;
            """.trimIndent()
        )

        val rects = program.nodes.filterIsInstance<RectNode>()
        require(rects.map { it.id }.toSet() == setOf("r", "r2")) { rects.map { it.id } }

        val at0 = program.sample(0.0).rects.associateBy { it.id }
        val at5 = program.sample(5.0).rects.associateBy { it.id }
        val at10 = program.sample(10.0).rects.associateBy { it.id }
        near(-5.0, at0.getValue("r").center.x)
        near(5.0, at10.getValue("r").center.x)
        near(5.0, at0.getValue("r2").center.x)
        near(-5.0, at10.getValue("r2").center.x)
        near(0.0, at5.getValue("r").center.x)
        near(0.0, at5.getValue("r2").center.x)

        val mesh = lowerSampledScene(program.sample(5.0))
        require(mesh.vertices.size == 8) { "Expected 8 vertices, got ${mesh.vertices.size}" }
        require(mesh.indices.size == 12) { "Expected 12 indices, got ${mesh.indices.size}" }
        require(mesh.vertices.all { it.position.z == 0f }) { "RectSL mesh positions should stay inside clip-space z" }
        require(mesh.vertices.map { it.material.z }.toSet() == setOf(0f, 1f)) { "Expected object ids in material.z" }
        require(mesh.vertices.all { it.material.x == 0f }) { "Expected plain material id" }
        require(mesh.vertices.all { it.shaderData == Vec4f.ZERO }) { "Expected zero shader data" }
    }

    private fun definitionsAndGroups() {
        val program = compile(
            """
            wall 30 4 meters
            duration 12
            fps 24

            : white
              0xffffff solid fill
            ;

            : soft
              0.25 alpha
            ;

            : voice-bar
              rect
                white
                soft
                2.0 0.2 wh
            ;

            "group-low" group
              "low-a" voice-bar
                -2 -1 xy
              ;
              "low-b" voice-bar
                2 -1 xy
              ;
            end

            "group-low" select
              10 0 translate
              0.1 rot
              2 scale
            ;
            """.trimIndent()
        )

        val sampled = program.sample(0.0).rects.associateBy { it.id }
        val lowA = sampled.getValue("low-a")
        val expectedA = Vec2d(10.0, 0.0) + rotate(Vec2d(-4.0, -2.0), 0.1)
        near(expectedA.x, lowA.center.x)
        near(expectedA.y, lowA.center.y)
        near(0.1, lowA.rotation)
        near(2.0, lowA.scale.x)
        near(2.0, lowA.scale.y)
        near(0.25, lowA.alpha)
        near(2.0, lowA.size.x)
        near(0.2, lowA.size.y)
        require(program.nodes.filterIsInstance<GroupNode>().single().children == listOf("low-a", "low-b"))
    }

    private fun selectionsAndDeepTimingTransforms() {
        val program = compile(
            """
            wall 12 4 meters
            duration 4
            fps 24

            "source" group
              "voice-a" rect
                "voice" tag
                0 0 xy
                1 square
                '( 0 0  4 4 ) linear x
              ;

              "drone-a" rect
                "drone" tag
                2 0 xy
                1 square
                '( 0 2  4 6 ) linear x
              ;
            end

            "source" select
              clone-deep "copy" rename
            ;

            "copy" select descendants
              "voice" tagged
              '( 1 delay-all-time  0.5 opacity* ) each
            ;
            """.trimIndent()
        )

        val groups = program.nodes.filterIsInstance<GroupNode>().associateBy { it.id }
        require(groups.getValue("source").children == listOf("voice-a", "drone-a"))
        require(groups.getValue("copy").children.toSet() == setOf("voice-a-copy", "drone-a-copy")) {
            groups.getValue("copy").children
        }

        val at2 = program.sample(2.0).rects.associateBy { it.id }
        near(2.0, at2.getValue("voice-a").center.x)
        near(1.0, at2.getValue("voice-a-copy").center.x)
        near(0.5, at2.getValue("voice-a-copy").alpha)
        near(4.0, at2.getValue("drone-a").center.x)
        near(4.0, at2.getValue("drone-a-copy").center.x)
        near(1.0, at2.getValue("drone-a-copy").alpha)
    }

    private fun operatorsAndKeyLenses() {
        val program = compile(
            """
            wall 12 4 meters
            duration 6
            fps 24

            "voice-a" rect
              "voice" tag
              0 0 xy
              1 2 + square
              '( 0 0  2 2  4 4 ) linear x
              '( 0 1  2 0.3  4 1.4 ) linear alpha
            ;

            "other" rect
              4 0 xy
              1 square
              '( 0 0  2 2  4 4 ) linear x
            ;

            "voice-a" select
              x-track 1 time+ 2 value* x
              alpha-track 0 1 clamp-values alpha
            ;

            "other" select
              x-track 0 2 keys-between 10 value+ x
            ;

            all rects
              '( "voice" tagged? id "other" = or ) filter
              '( 0.5 opacity* ) each
            ;
            """.trimIndent()
        )

        val at3 = program.sample(3.0).rects.associateBy { it.id }
        val voice = at3.getValue("voice-a")
        val other = at3.getValue("other")
        near(4.0, voice.center.x)
        near(3.0, voice.size.x)
        near(3.0, voice.size.y)
        require(voice.alpha in 0.32..0.33) { voice.alpha }
        near(12.0, other.center.x)
        near(0.5, other.alpha)
    }

    private fun trackModesAndTimeWarps() {
        val program = compile(
            """
            wall 10 4 meters
            duration 8
            fps 30

            "tracks" rect
              '( 0 0  4 8 ) linear x
              '( 0 1  4 3 ) hold y
              '( 0 1  4 3 ) tcb  -0.4 0 0 tcb! scale
              '( 0 0  2 1  4 0 ) alpha
            ;

            "delayed" rect
              '( 0 0  2 2 ) tcb 1 delay-time x
              0 y
              1 square
            ;
            """.trimIndent()
        )

        val at2 = program.sample(2.0).rects.associateBy { it.id }
        val tracks = at2.getValue("tracks")
        near(4.0, tracks.center.x)
        near(1.0, tracks.center.y)
        require(tracks.scale.x > 1.0 && tracks.scale.x < 3.0) { tracks.scale }
        require(tracks.alpha > 0.5) { tracks.alpha }

        val delayedAt1 = program.sample(1.0).rects.associateBy { it.id }.getValue("delayed")
        val delayedAt3 = program.sample(3.0).rects.associateBy { it.id }.getValue("delayed")
        near(0.0, delayedAt1.center.x)
        near(2.0, delayedAt3.center.x)
    }

    private fun layersTracksEasingAndRepeat() {
        val program = compile(
            """
            wall 8 4 meters
            duration 2
            fps 24

            linear-track enter '( 0 -2  2 2 )
            track lift '( 0 0  2 2 )

            "background" rect
              0 0 xy
              3 square
              "background" layer
            ;

            "foreground" rect
              0 0 xy
              2 square
              "foreground" layer
            ;

            4 '(
              rect
                "bar" tag
                i 1.5 * -2.25 + -1 xy
                0.25 square
                lift smooth z
              ;
            ) repeat

            all '( "bar" tagged? ) where "bars" group-selected
              1 0 translate
            ;

            "mover" rect
              enter ease-in-out x
              1 y
              0.5 square
              front
            ;

            0 3 '(
              rect
                "range" tag
                i 0.5 * -0.5 + 1.5 xy
                0.2 square
                back
              ;
            ) range
            """.trimIndent()
        )

        val groups = program.nodes.filterIsInstance<GroupNode>().associateBy { it.id }
        require(groups.getValue("bars").children.size == 4) { groups.getValue("bars").children }

        val at0 = program.sample(0.0).rects
        val at05 = program.sample(0.5).rects.associateBy { it.id }
        val at1 = program.sample(1.0).rects

        require(at0.first().tags.contains("range")) { at0.map { it.id to it.z } }
        require(at0.last().id == "mover") { at0.map { it.id to it.z } }
        near(-1.5, at05.getValue("mover").center.x)

        val barsAt1 = at1.filter { "bar" in it.tags }.sortedBy { it.center.x }
        require(barsAt1.size == 4) { barsAt1.map { it.id } }
        near(-1.25, barsAt1.first().center.x)
        barsAt1.forEach { near(1.0, it.z) }

        val foreground = at0.first { it.id == "foreground" }
        val background = at0.first { it.id == "background" }
        require(foreground.z > background.z) { at0.map { it.id to it.z } }
    }

    private fun gradientAndMaterialLowering() {
        val program = compile(
            """
            wall 6 4 meters
            duration 1
            fps 1

            material pulse '( uv time + )

            "gradient" rect
              0 0 xy
              2 2 wh
              0xff0000 0x0000ff -1 0 1 0 linear fill
              soft-edge material
              0.12 2 3 4 shader
            ;

            "stacked" rect
              2 0 xy
              1 square
              0xffffff solid fill
              pulse material
            ;
            """.trimIndent()
        )

        val scene = program.sample(0.0)
        val mesh = lowerSampledScene(scene)
        require(mesh.vertices.size == 8)
        val gradientVertices = mesh.vertices.take(4)
        require(gradientVertices[0].color.x > gradientVertices[1].color.x) { gradientVertices.map { it.color } }
        require(gradientVertices[1].color.z > gradientVertices[0].color.z) { gradientVertices.map { it.color } }
        require(gradientVertices.all { it.material.x == 1f }) { "soft-edge material id expected" }
        near(0.12, gradientVertices.first().shaderData.x.toDouble())

        val stackedVertices = mesh.vertices.drop(4)
        require(stackedVertices.all { it.material.x == 3f }) { stackedVertices.map { it.material } }
        require(program.materialTable.modules.single().name == "pulse")
    }

    private fun semanticAnalysis() {
        val program = compile(
            """
            wall 4 4 meters
            duration 4
            fps 20

            "left" rect
              -1 0 xy
              1 square
              '( 0 -1  2 0  4 3 ) x
              '( 0 0   2 1  4 0 ) alpha
            ;

            dup "right" rename
              mirror-x
            ;
            """.trimIndent()
        )

        val report = analyzeAnimation(program, AnalysisSettings(fps = 20.0))
        require(report.events.any { it is VisualEvent.TouchStart }) { report.events }
        require(report.events.any { it is VisualEvent.TouchEnd }) { report.events }
        require(report.events.any { it is VisualEvent.LeaveScreen }) { report.events }
        require(report.events.any { it is VisualEvent.OpacityCrossing && it.threshold == 0.5 && it.rising }) {
            report.events
        }
        require(report.objectMetrics.keys.containsAll(listOf("left", "right"))) { report.objectMetrics.keys }
        require(report.frameMetrics.any { it.visibleObjectCount == 2 }) { report.frameMetrics.take(5) }
    }

    private fun stackSLMaterialShader() {
        val program = compile(
            """
            material slow-stripes '(
              uv 8.0 * time + sin
              0.5 * 0.5 +
            )

            "stripe" rect
              0 0 xy
              4 0.35 wh
              0xffcc66 solid fill
              slow-stripes material
              9 0 0.15 0 shader
            ;
            """.trimIndent()
        )

        val compiled = compileStackSLMaterials(program)
        require(compiled.size == 1)
        require(compiled.single().ok) { compiled.single().stackSL.diagnostics.joinToString("\n") }
        val insertion = rectMaterialShaderInsertion(compiled)
        require("#version" !in insertion) { insertion }
        require("void main()" !in insertion) { insertion }
        require("struct RectFrag" in insertion) { insertion }
        require("vec4 material_slow_stripes(RectFrag r)" in insertion) { insertion }
        require("if (id == 3) return material_slow_stripes(r);" in insertion) { insertion }
        require("shadePlain" in insertion && "shadeSoftEdge" in insertion && "shadeDiagnostic" in insertion)

        val shaderCode = rectMaterialShaderCode(program)
        require(shaderCode.ok) { shaderCode.diagnostics.joinToString("\n") }
        require(shaderCode.insertion == insertion) { shaderCode.insertion }
        require("#version 450" in shaderCode.exampleFragmentShader) { shaderCode.exampleFragmentShader }
        require("void main()" in shaderCode.exampleFragmentShader) { shaderCode.exampleFragmentShader }

        val shader = rectAnimationFragmentShader(compiled)
        require(shader == shaderCode.exampleFragmentShader) { shader }
    }

    private fun compile(source: String): SceneProgram {
        val result = RectAnimationOracle.compile(source)
        require(result.ok) { result.diagnostics.joinToString("\n") }
        return result.program!!
    }

    private fun rotate(p: Vec2d, angle: Double): Vec2d =
        Vec2d(
            cos(angle) * p.x - sin(angle) * p.y,
            sin(angle) * p.x + cos(angle) * p.y
        )

    private fun near(expected: Double, actual: Double, epsilon: Double = EPS) {
        require(abs(expected - actual) <= epsilon) {
            "Expected $expected, got $actual"
        }
    }
}
