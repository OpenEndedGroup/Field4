package afield.examples.four

import afield.four.util.CameraPose
import afield.stdlib.KeyframeScope
import afield.stdlib.remAssign
import afield.stdlib.times
import afield.stdlib.linalg.DualQuaternionf
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs

/*
FIELD_TEST
id: examples.four.dual-quaternionf-smoke
runner: jvm-main
entrypoint: afield.examples.four.DualQuaternionfSmokeTest
timeoutSec: 20
expect:
  stdout:
    - "DualQuaternionfSmokeTest ok"
*/
object DualQuaternionfSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        constructionAndTransform()
        compositionOrder()
        screwInterpolation()
        cameraPoseRoundTrip()
        keyframeInterpolationAndPersistence()
        println("DualQuaternionfSmokeTest ok")
    }

    private fun constructionAndTransform() {
        val rotation = Quaternionf().rotateY((PI / 2.0).toFloat())
        val translation = Vector3f(1f, 2f, 3f)
        val dq = DualQuaternionf(rotation, translation)

        assertVecClose("translation", dq.getTranslation(), translation)

        val point = Vector3f(0f, 0f, -2f)
        val expected = rotation.transform(Vector3f(point)).add(translation)
        val actual = dq.transform(point)
        assertVecClose("transform", actual, expected)
        assertVecClose("dq * point", dq * point, expected)
        assertVecClose("point * dq", point * dq, expected)
        assertVecClose("-dq same transform", (-dq).transform(point), expected)

        val copied = DualQuaternionf()
        copied %= dq
        assertVecClose("copy assignment", copied * point, expected)
    }

    private fun compositionOrder() {
        val a = DualQuaternionf(
            Quaternionf().rotateY((PI / 2.0).toFloat()),
            Vector3f(1f, 0f, 0f)
        )
        val b = DualQuaternionf(
            Quaternionf().rotateX((PI / 2.0).toFloat()),
            Vector3f(0f, 2f, 0f)
        )
        val point = Vector3f(0.25f, 0.5f, -1f)

        val composed = DualQuaternionf(a).mul(b)
        val expected = a.transform(b.transform(point))
        assertVecClose("composition", composed.transform(point), expected)
        assertVecClose("operator composition", (a * b) * point, expected)
    }

    private fun screwInterpolation() {
        val start = DualQuaternionf()
        val end = DualQuaternionf(
            Quaternionf().rotateZ(PI.toFloat()),
            Vector3f(0f, 0f, 10f)
        )
        val mid = start.sclerp(end, 0.5f)

        assertVecClose("sclerp midpoint translation", mid.getTranslation(), Vector3f(0f, 0f, 5f))
        assertVecClose("sclerp midpoint transform", mid.transform(Vector3f(1f, 0f, 0f)), Vector3f(0f, 1f, 5f))
        assertVecClose("sclerp start", start.sclerp(end, 0f).getTranslation(), Vector3f())
        assertVecClose("sclerp end", start.sclerp(end, 1f).getTranslation(), Vector3f(0f, 0f, 10f))
    }

    private fun cameraPoseRoundTrip() {
        val eye = Vector3f(1f, 2f, 3f)
        val center = Vector3f(1f, 2f, 2f)
        val pose = CameraPose.lookAt(eye, center)

        assertVecClose("camera pose position", pose.position, eye)
        assertVecClose("camera pose target", pose.target, center)
        assertVecClose("camera pose forward", pose.forward, Vector3f(0f, 0f, -1f))
        assertVecClose("camera pose up", pose.up, Vector3f(0f, 1f, 0f))
    }

    private fun keyframeInterpolationAndPersistence() {
        val scope = KeyframeScope()
        val start = CameraPose.lookAlong(Vector3f(0f, 0f, 0f), Vector3f(0f, 0f, -1f), focusDistance = 2f)
        val end = CameraPose.lookAlong(Vector3f(0f, 0f, 10f), Vector3f(0f, 1f, 0f), focusDistance = 4f)
        val keyA = scope.Key(mutableMapOf("camera" to start))
        val keyB = scope.Key(mutableMapOf("camera" to end))

        val mid = scope.interpolate(keyA, keyB, 0.5)["camera"] as CameraPose
        require(abs(mid.focusDistance - 3f) < 1e-5f) { "Expected interpolated focus distance 3, got ${mid.focusDistance}" }

        val path = Files.createTempFile("field-dualquat-key", ".json")
        try {
            scope.saveKey(path, keyB)
            val loaded = scope.loadKey(path)["camera"] as CameraPose
            assertVecClose("loaded camera position", loaded.position, end.position)
            require(abs(loaded.focusDistance - end.focusDistance) < 1e-5f) {
                "Loaded focus distance ${loaded.focusDistance}, expected ${end.focusDistance}"
            }
        } finally {
            val versionsDir = path.resolveSibling("${path.fileName}.versions")
            Files.deleteIfExists(path)
            Files.deleteIfExists(versionsDir.resolve("manifest.json"))
            Files.deleteIfExists(versionsDir.resolve("000000.json"))
            Files.deleteIfExists(versionsDir)
        }
    }

    private fun assertVecClose(name: String, actual: Vector3f, expected: Vector3f, eps: Float = 1e-4f) {
        require(actual.distance(expected) <= eps) {
            "$name expected $expected, got $actual"
        }
    }
}
