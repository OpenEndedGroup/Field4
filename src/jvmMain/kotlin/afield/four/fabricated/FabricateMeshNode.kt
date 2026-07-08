package afield.four.fabricated

/** Scene-graph node that represents drawable fabricated geometry. */
class FabricateMeshNode(
    name: String = "mesh",
    var material: FabricateMaterial = FabricateStandardMaterial(),
    var source: Any? = null
) : FabricateNode(name) {
    var castShadow: Boolean = true
    var receiveShadow: Boolean = true
    var renderOrder: Int = 0

    val renderQueue: FabricateRenderQueue
        get() = material.renderQueue
}

enum class FabricateRenderQueue {
    Opaque,
    AlphaMask,
    Transparent
}
