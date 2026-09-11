package afield.four.fabricated

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

/**
 * Render queue data structures and the `FabricateScenePlanner` that builds them from a `FabricateScene`.
 *
 * The planner walks the scene graph, assigns each visible renderable to one of three queues based
 * on its material's `alphaMode` (`Opaque`, `AlphaMask`, or `Transparent`), and sorts each queue
 * for correct draw ordering: opaque and masked items front-to-back by sort order, transparent
 * items back-to-front by camera distance. Shadow casters are collected from the opaque and masked
 * sets. `FabricateScene.planScene` calls the planner; `StandardRenderer.Bundle.applyScenePlan`
 * consumes the resulting `FabricateRenderPlan`.
 */

/** One visible renderable node, resolved with its material ID, world transforms, and camera distance. */
data class FabricateRenderItem(
    val node: FabricateNode,
    val renderable: FabricateRenderable,
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

enum class FabricateRenderQueue {
    Opaque,
    AlphaMask,
    Transparent
}

val FabricateMaterial.renderQueue: FabricateRenderQueue
    get() = when (alphaMode) {
        FabricateAlphaMode.OPAQUE -> FabricateRenderQueue.Opaque
        FabricateAlphaMode.MASK -> FabricateRenderQueue.AlphaMask
        FabricateAlphaMode.BLEND -> FabricateRenderQueue.Transparent
    }

/** Builds deterministic opaque/masked/transparent queues from a FabricateScene. */
class FabricateScenePlanner(
    private val materialLibrary: FabricateMaterialLibrary = FabricateMaterialLibrary()
) {
    fun plan(scene: FabricateScene, cameraPosition: Vector3f? = null): FabricateRenderPlan {
        scene.updateWorldTransforms()
        return buildPlan(scene.root, cameraPosition)
    }

    fun plan(root: FabricateNode, cameraPosition: Vector3f? = null): FabricateRenderPlan {
        root.updateFromRoot()
        return buildPlan(root, cameraPosition)
    }

    private fun buildPlan(root: FabricateNode, cameraPosition: Vector3f?): FabricateRenderPlan {
        materialLibrary.clear()
        val opaque = mutableListOf<FabricateRenderItem>()
        val alphaMask = mutableListOf<FabricateRenderItem>()
        val transparent = mutableListOf<FabricateRenderItem>()
        val shadowCasters = mutableListOf<FabricateRenderItem>()

        root.walk().forEach { node ->
            val renderable = node.renderable
            if (renderable == null || !node.isEffectivelyVisible()) return@forEach
            val item = itemFor(node, renderable, cameraPosition)
            when (item.queue) {
                FabricateRenderQueue.Opaque -> opaque += item
                FabricateRenderQueue.AlphaMask -> alphaMask += item
                FabricateRenderQueue.Transparent -> transparent += item
            }
            if (renderable.castShadow && item.queue != FabricateRenderQueue.Transparent) {
                shadowCasters += item
            }
        }

        val stableFrontToBack = compareBy<FabricateRenderItem> { it.renderable.material.sortOrder }.thenBy { it.node.name }
        opaque.sortWith(stableFrontToBack)
        alphaMask.sortWith(stableFrontToBack)
        shadowCasters.sortWith(stableFrontToBack)
        transparent.sortWith(
            compareBy<FabricateRenderItem> { it.renderable.material.sortOrder }
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

    private fun itemFor(node: FabricateNode, renderable: FabricateRenderable, cameraPosition: Vector3f?): FabricateRenderItem {
        val materialId = materialLibrary.refresh(renderable.material)
        val position = node.worldPoint(renderable.boundsCenter)
        val distanceSquared = cameraPosition?.distanceSquared(position) ?: 0f
        return FabricateRenderItem(
            node = node,
            renderable = renderable,
            materialId = materialId,
            queue = renderable.material.renderQueue,
            world = Matrix4f(node.worldMatrix),
            previousWorld = Matrix4f(node.previousWorldMatrix),
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
