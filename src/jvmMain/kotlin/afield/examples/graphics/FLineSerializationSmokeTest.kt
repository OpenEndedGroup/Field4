package afield.examples.graphics

import afield.graphics.FLine
import afield.graphics.set2DPlane
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.onClick
import afield.stdlib.pointSize
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

/*
FIELD_TEST
id: graphics.fline-serialization.smoke
runner: jvm-main
entrypoint: afield.examples.graphics.FLineSerializationSmokeTest
tags: [smoke, graphics, fline, serialization, json]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "FLine serialization smoke tests passed"
*/
object FLineSerializationSmokeTest {

    @Serializable
    private data class Envelope(
        val name: String,
        val line: FLine,
        val lines: List<FLine>
    )

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "FLine serialization smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testRoundTripsEmbeddedFLine()
            println("FLine serialization smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testRoundTripsEmbeddedFLine() {
        val line = FLine().apply {
            moveTo(1f, 2f, 3f)
            here.properties["tag"] = "start"
            here.properties["weight"] = 7
            lineTo(4f, 5f, 6f)
            here.properties["uv"] = vec(0.25f, 0.5f)
            cubicTo(
                7f, 8f, 9f,
                10f, 11f, 12f,
                13f, 14f, 15f
            )
            here.properties["normal"] = vec(0.1f, 0.2f, 0.3f)
            close()

            properties["title"] = "demo"
            properties["count"] = 42
            properties["ratio"] = 0.75f
            properties["offset"] = vec(2f, 3f)
            properties["anchor"] = vec(4f, 5f, 6f)
            properties["rgba"] = vec(0.2f, 0.3f, 0.4f, 0.5f)

            color = vec(0.9f, 0.8f, 0.7f, 1f)
            thickness = 2.5
            pointSize = 6.0
            filled = true
            stroked = false
            onClick = {}
            set2DPlane(vec(10f, 20f, 30f), vec(1f, 0f, 0f), vec(0f, 1f, 0f))
        }

        val json = Json.encodeToString(
            Envelope(
                name = "wrapper",
                line = line,
                lines = listOf(line.copy())
            )
        )
        val decoded = Json.decodeFromString<Envelope>(json)
        val restored = decoded.line

        require(decoded.name == "wrapper") { "Envelope data did not round-trip" }
        require(decoded.lines.size == 1) { "Expected list field to round-trip" }
        require(restored.nodes.size == 4) { "Expected 4 nodes, got ${restored.nodes.size}" }
        require(restored.nodes[0] is FLine.MoveTo) { "Expected first node to be MoveTo" }
        require(restored.nodes[1] is FLine.LineTo) { "Expected second node to be LineTo" }
        require(restored.nodes[2] is FLine.CubicTo) { "Expected third node to be CubicTo" }
        require(restored.nodes[3] is FLine.Close) { "Expected fourth node to be Close" }

        require(restored.properties["title"] == "demo") { "String property did not round-trip" }
        require(restored.properties["count"] == 42) { "Int property did not round-trip" }
        require(restored.properties["ratio"] == 0.75f) { "Float property did not round-trip" }

        requireVec2(restored.properties["offset"], 2f, 3f, "offset")
        requireVec3(restored.properties["anchor"], 4f, 5f, 6f, "anchor")
        requireVec4(restored.properties["rgba"], 0.2f, 0.3f, 0.4f, 0.5f, "rgba")

        require(restored.filled) { "Boolean line property 'filled' did not round-trip" }
        require(!restored.stroked) { "Boolean line property 'stroked' did not round-trip" }
        require(abs((restored.thickness ?: 0.0) - 2.5) < 1e-9) { "Thickness did not round-trip" }
        require(abs(restored.pointSize - 6.0) < 1e-9) { "Point size did not round-trip" }
        requireColor(restored.color, 0.9f, 0.8f, 0.7f, 1f, "line color")

        require(restored.properties["onClick"] == null) { "Runtime callback property should not serialize" }
        require(restored.properties["_fline_plane2d"] == null) { "Internal plane property should not serialize" }

        require(restored.nodes[0].properties["tag"] == "start") { "Node string property did not round-trip" }
        require(restored.nodes[0].properties["weight"] == 7) { "Node numeric property did not round-trip" }

        requireVec2(restored.nodes[1].properties["uv"], 0.25f, 0.5f, "node uv")
        requireVec3(restored.nodes[2].properties["normal"], 0.1f, 0.2f, 0.3f, "node normal")
    }

    private fun requireColor(actual: Any?, r: Float, g: Float, b: Float, a: Float, label: String) {
        requireVec4(actual, r, g, b, a, label)
    }

    private fun requireVec2(actual: Any?, x: Float, y: Float, label: String) {
        val value = actual as? Vec2 ?: error("Expected Vec2 for $label, got ${actual?.let { it::class.qualifiedName }}")
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y})"
        }
    }

    private fun requireVec3(actual: Any?, x: Float, y: Float, z: Float, label: String) {
        val value = actual as? Vec3 ?: error("Expected Vec3 for $label, got ${actual?.let { it::class.qualifiedName }}")
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f && abs(value.z - z) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y}, ${value.z})"
        }
    }

    private fun requireVec4(actual: Any?, x: Float, y: Float, z: Float, w: Float, label: String) {
        val value = actual as? Vec4 ?: error("Expected Vec4 for $label, got ${actual?.let { it::class.qualifiedName }}")
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f && abs(value.z - z) < 1e-6f && abs(value.w - w) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y}, ${value.z}, ${value.w})"
        }
    }
}
