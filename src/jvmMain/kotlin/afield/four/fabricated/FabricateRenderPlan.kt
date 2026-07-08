package afield.four.fabricated

import org.joml.Matrix4f
import org.joml.Vector3f

data class FabricateRenderItem(
    val node: FabricateMeshNode,
    val materialId: Int,
    val queue: FabricateRenderQueue,
    val world: Matrix4f,
    val previousWorld: Matrix4f,
    val cameraDistanceSquared: Float
)

data class FabricateRenderPlan(
    val opaque: List<FabricateRenderItem>,
    val alphaMask: List<FabricateRenderItem>,
    val transparent: List<FabricateRenderItem>,
    val shadowCasters: List<FabricateRenderItem>,
    val materials: List<FabricateMaterialRecord>
) {
    val allDrawItems: List<FabricateRenderItem>
        get() = opaque + alphaMask + transparent
}

/** Builds deterministic opaque/masked/transparent queues from a FabricateScene. */
class FabricateScenePlanner(
    private val materialLibrary: FabricateMaterialLibrary = FabricateMaterialLibrary()
) {
    fun plan(scene: FabricateScene, cameraPosition: Vector3f? = null): FabricateRenderPlan {
        scene.updateWorld()
        return plan(scene.root, cameraPosition)
    }

    fun plan(root: FabricateNode, cameraPosition: Vector3f? = null): FabricateRenderPlan {
        materialLibrary.clear()
        root.updateWorld(parentWorld = null, force = false)
        val opaque = mutableListOf<FabricateRenderItem>()
        val alphaMask = mutableListOf<FabricateRenderItem>()
        val transparent = mutableListOf<FabricateRenderItem>()
        val shadowCasters = mutableListOf<FabricateRenderItem>()

        root.traverse { node ->
            if (node !is FabricateMeshNode || !node.isEffectivelyVisible()) return@traverse
            val item = itemFor(node, cameraPosition)
            when (item.queue) {
                FabricateRenderQueue.Opaque -> opaque += item
                FabricateRenderQueue.AlphaMask -> alphaMask += item
                FabricateRenderQueue.Transparent -> transparent += item
            }
            if (node.castShadow && item.queue != FabricateRenderQueue.Transparent) {
                shadowCasters += item
            }
        }

        val stableFrontToBack = compareBy<FabricateRenderItem> { it.node.renderOrder }.thenBy { it.node.name }
        opaque.sortWith(stableFrontToBack)
        alphaMask.sortWith(stableFrontToBack)
        shadowCasters.sortWith(stableFrontToBack)
        transparent.sortWith(
            compareBy<FabricateRenderItem> { it.node.renderOrder }
                .thenByDescending { it.cameraDistanceSquared }
                .thenBy { it.node.name }
        )

        return FabricateRenderPlan(
            opaque = opaque,
            alphaMask = alphaMask,
            transparent = transparent,
            shadowCasters = shadowCasters,
            materials = materialLibrary.records()
        )
    }

    private fun itemFor(node: FabricateMeshNode, cameraPosition: Vector3f?): FabricateRenderItem {
        val materialId = materialLibrary.refresh(node.material)
        val position = node.worldPosition(Vector3f())
        val distanceSquared = cameraPosition?.distanceSquared(position) ?: 0f
        return FabricateRenderItem(
            node = node,
            materialId = materialId,
            queue = node.renderQueue,
            world = node.worldMatrix(),
            previousWorld = node.previousWorldMatrix(),
            cameraDistanceSquared = distanceSquared
        )
    }
}

fun FabricateNode.isEffectivelyVisible(): Boolean {
    var n: FabricateNode? = this
    while (n != null) {
        if (!n.visible) return false
        n = n.parent
    }
    return true
}
