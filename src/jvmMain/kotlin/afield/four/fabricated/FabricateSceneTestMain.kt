package afield.four.fabricated

import afield.four.modules.GltfSupport
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * JVM smoke test for Fabricate's pure scene graph: transform hierarchy, animation playback,
 * and opaque/transparent draw ordering. This intentionally avoids a Vulkan device.
 */
object FabricateSceneTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val scene = FabricateScene()
        val parent = scene.node("parent") {
            transform.position.set(1f, 0f, 0f)
        }
        val child = scene.mesh(
            name = "child",
            parent = parent,
            material = FabricateMaterial(baseColor = Vector4f(1f, 0f, 0f, 1f))
        ) {
            transform.position.set(0f, 2f, 0f)
        }

        scene.updateWorldTransforms()
        val childWorld = child.worldPoint()
        check(close(childWorld.x, 1f) && close(childWorld.y, 2f)) {
            "unexpected child world position $childWorld"
        }

        val animated = scene.mesh("animated") {
            animationSamples = listOf(
                GltfSupport.TRSSample(
                    0f,
                    GltfSupport.TRS(Vector3f(0f, 0f, 0f), Quaternionf(), Vector3f(1f, 1f, 1f))
                ),
                GltfSupport.TRSSample(
                    1f,
                    GltfSupport.TRS(Vector3f(0f, 0f, 10f), Quaternionf(), Vector3f(1f, 1f, 1f))
                )
            )
        }
        scene.updateWorldTransforms(0.5f)
        val animatedWorld = animated.worldPoint()
        check(close(animatedWorld.z, 5f)) {
            "unexpected animated z ${animatedWorld.z}"
        }

        scene.mesh(
            name = "transparent-near",
            material = FabricateMaterial(baseColor = Vector4f(1f, 1f, 1f, 0.5f)).transparent(0.5f)
        ) {
            transform.position.set(0f, 0f, 1f)
        }
        scene.mesh(
            name = "transparent-far",
            material = FabricateMaterial(baseColor = Vector4f(1f, 1f, 1f, 0.5f)).transparent(0.5f)
        ) {
            transform.position.set(0f, 0f, 5f)
        }

        val lists = scene.drawLists(Vector3f(0f, 0f, 0f))
        check(lists.opaque.map { it.node.name }.containsAll(listOf("child", "animated"))) {
            "opaque list missing expected nodes ${lists.opaque.map { it.node.name }}"
        }
        check(lists.transparent.map { it.node.name } == listOf("transparent-far", "transparent-near")) {
            "transparent list was ${lists.transparent.map { it.node.name }}"
        }

        println("FabricateSceneTestMain ok: opaque=${lists.opaque.size}, transparent=${lists.transparent.size}")
    }

    private fun close(a: Float, b: Float): Boolean =
        kotlin.math.abs(a - b) < 0.0001f
}
