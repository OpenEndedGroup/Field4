package afield.examples

import afield.FieldJson
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.arena.FrameArena
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/*
FIELD_TEST
id: examples.generated-linalg.smoke
runner: jvm-main
entrypoint: afield.examples.GeneratedLinalgSmokeTest
tags: [smoke, examples, linalg, arena, json]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "Generated linalg smoke tests passed"
*/
object GeneratedLinalgSmokeTest {
    @Serializable
    private data class SavedPoint(@Contextual val position: Vector3f)

    @JvmStatic
    fun main(args: Array<String>) {
        val heapResult = Vector3f(1f, 2f, 3f).cross(Vector3f(4f, 5f, 6f))
        check(heapResult == Vector3f(-3f, 6f, -3f))

        FrameArena(32).use { arena ->
            arena.scoped {
                val a = vec3(1f, 2f, 3f)
                val b = vec3(4f, 5f, 6f)
                val result = vec3()
                a.cross(b, result)
                check(result.copyTo(Vector3f()) == heapResult)
            }
            check(arena.growthCount > 0)
            check(arena.usedBytes == 0)
        }

        val encoded = FieldJson.encodeToString(SavedPoint(heapResult))
        val decoded = FieldJson.decodeFromString<SavedPoint>(encoded)
        check(decoded.position == heapResult)
        println("Generated linalg smoke tests passed")
    }
}
