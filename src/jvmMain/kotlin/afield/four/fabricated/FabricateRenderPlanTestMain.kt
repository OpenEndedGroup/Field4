package afield.four.fabricated

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/** Pure JVM smoke test for material records and opaque/masked/transparent queue planning. */
object FabricateRenderPlanTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val scene = FabricateScene(FabricateNode("render-plan"))
        scene.mesh("opaque", material = FabricateMaterial(baseColor = Vector4f(1f, 0f, 0f, 1f))) {
            transform.position.set(0f, 0f, 2f)
        }
        scene.mesh("masked", material = FabricateMaterial(alphaMode = FabricateAlphaMode.MASK)) {
            transform.position.set(0f, 0f, 3f)
        }
        scene.mesh("farGlass", material = FabricateMaterial(alphaMode = FabricateAlphaMode.BLEND)) {
            transform.position.set(0f, 0f, 10f)
        }
        scene.mesh("nearGlass", material = FabricateMaterial(alphaMode = FabricateAlphaMode.BLEND)) {
            transform.position.set(0f, 0f, 1f)
        }

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
        check(plan.materials[plan.transparent.first().materialId].alphaMode == FabricateAlphaMode.BLEND)

        println("FabricateRenderPlanTestMain ok")
    }
}
