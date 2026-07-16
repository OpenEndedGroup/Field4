package afield.examples

import afield.FieldJson
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.closestPointOnTriangle
import afield.stdlib.linalg.arena.FrameArena
import afield.stdlib.linalg.arena.frameScratch
import afield.stdlib.linalg.storage.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer

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

        frameScratch {
            check(vec3(3f, 4f, 0f).length() == 5f)
        }

        val packed = SegmentVec3Buffer.of(ByteBuffer.allocateDirect(24), 2, Vec3StorageLayout.PACKED_12)
        with(packed) {
            packed[0].set(1f, 2f, 3f)
            packed[1].set(4f, 5f, 6f)
            check(packed[0].dot(packed[1]) == 32f)
        }
        val packed2 = SegmentVec2Buffer.of(ByteBuffer.allocateDirect(16), 2, Vec2StorageLayout.PACKED_8)
        with(packed2) {
            packed2[0].set(1f, 2f); packed2[1].set(3f, 4f)
            check(packed2[0].dot(packed2[1]) == 11f)
        }
        val aligned4 = SegmentVec4Buffer.of(ByteBuffer.allocateDirect(32), 2, Vec4StorageLayout.ALIGNED_16)
        with(aligned4) {
            aligned4[0].set(1f, 2f, 3f, 4f); aligned4[1].set(4f, 3f, 2f, 1f)
            check(aligned4[0].dot(aligned4[1]) == 20f)
        }

        val closest = closestPointOnTriangle(
            Vector3f(2f, 2f, 3f),
            Vector3f(0f, 0f, 0f),
            Vector3f(2f, 0f, 0f),
            Vector3f(0f, 2f, 0f),
            Vector3f(),
        )
        check(closest == Vector3f(1f, 1f, 0f))

        val encoded = FieldJson.encodeToString(SavedPoint(heapResult))
        val decoded = FieldJson.decodeFromString<SavedPoint>(encoded)
        check(decoded.position == heapResult)
        println("Generated linalg smoke tests passed")
    }
}
