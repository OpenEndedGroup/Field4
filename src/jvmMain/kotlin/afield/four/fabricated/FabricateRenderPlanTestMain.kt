package afield.four.fabricated

import org.joml.Vector3f
import org.joml.Vector4f

/** Pure JVM smoke test for material records and opaque/masked/transparent queue planning. */
object FabricateRenderPlanTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val scene = FabricateScene("render-plan")
        val opaque = FabricateMeshNode(
            "opaque",
            FabricateStandardMaterial(baseColor = Vector4f(1f, 0f, 0f, 1f))
        ).setTranslation(0f, 0f, 2f) as FabricateMeshNode
        val masked = FabricateMeshNode(
            "masked",
            FabricateStandardMaterial(alphaMode = FabricateAlphaMode.Mask)
        ).setTranslation(0f, 0f, 3f) as FabricateMeshNode
        val farGlass = FabricateMeshNode(
            "farGlass",
            FabricateStandardMaterial(alphaMode = FabricateAlphaMode.Blend)
        ).setTranslation(0f, 0f, 10f) as FabricateMeshNode
        val nearGlass = FabricateMeshNode(
            "nearGlass",
            FabricateStandardMaterial(alphaMode = FabricateAlphaMode.Blend)
        ).setTranslation(0f, 0f, 1f) as FabricateMeshNode

        scene.root += opaque
        scene.root += masked
        scene.root += nearGlass
        scene.root += farGlass

        val plan = FabricateScenePlanner().plan(scene, cameraPosition = Vector3f(0f, 0f, 0f))

        check(plan.opaque.map { it.node.name } == listOf("opaque")) { "bad opaque queue ${plan.opaque}" }
        check(plan.alphaMask.map { it.node.name } == listOf("masked")) { "bad alpha-mask queue ${plan.alphaMask}" }
        check(plan.transparent.map { it.node.name } == listOf("farGlass", "nearGlass")) {
            "transparent queue should be back-to-front: ${plan.transparent.map { it.node.name }}"
        }
        check(plan.shadowCasters.map { it.node.name } == listOf("masked", "opaque")) {
            "transparent meshes should not be default shadow casters: ${plan.shadowCasters.map { it.node.name }}"
        }
        check(plan.materials.size == 4) { "expected 4 material records, got ${plan.materials.size}" }
        check(plan.materials[plan.transparent.first().materialId].alphaMode == FabricateAlphaMode.Blend)

        println("FabricateRenderPlanTestMain ok")
    }
}
