package afield.examples

import afield.FieldJson
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.abs

/*
FIELD_TEST
id: examples.joml-serialization.smoke
runner: jvm-main
entrypoint: afield.examples.JomlSerializationSmokeTest
tags: [smoke, examples, serialization, joml, json]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "JOML serialization smoke tests passed"
*/
object JomlSerializationSmokeTest {

    @Serializable
    private data class Pose(
        @Contextual val v2: Vector2f,
        @Contextual val v3: Vector3f,
        @Contextual val v4: Vector4f,
        @Contextual val q: Quaternionf
    )

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "JOML serialization smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testRoundTrip()
            println("JOML serialization smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testRoundTrip() {
        val pose = Pose(
            v2 = Vector2f(1f, 2f),
            v3 = Vector3f(3f, 4f, 5f),
            v4 = Vector4f(6f, 7f, 8f, 9f),
            q = Quaternionf(0.1f, 0.2f, 0.3f, 0.4f)
        )

        val json = FieldJson.encodeToString(pose)
        val decoded = FieldJson.decodeFromString<Pose>(json)

        requireVec2(decoded.v2, 1f, 2f, "v2")
        requireVec3(decoded.v3, 3f, 4f, 5f, "v3")
        requireVec4(decoded.v4, 6f, 7f, 8f, 9f, "v4")
        requireQuat(decoded.q, 0.1f, 0.2f, 0.3f, 0.4f, "q")
    }

    private fun requireVec2(value: Vector2f, x: Float, y: Float, label: String) {
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y})"
        }
    }

    private fun requireVec3(value: Vector3f, x: Float, y: Float, z: Float, label: String) {
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f && abs(value.z - z) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y}, ${value.z})"
        }
    }

    private fun requireVec4(value: Vector4f, x: Float, y: Float, z: Float, w: Float, label: String) {
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f && abs(value.z - z) < 1e-6f && abs(value.w - w) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y}, ${value.z}, ${value.w})"
        }
    }

    private fun requireQuat(value: Quaternionf, x: Float, y: Float, z: Float, w: Float, label: String) {
        require(abs(value.x - x) < 1e-6f && abs(value.y - y) < 1e-6f && abs(value.z - z) < 1e-6f && abs(value.w - w) < 1e-6f) {
            "Unexpected $label: (${value.x}, ${value.y}, ${value.z}, ${value.w})"
        }
    }
}
