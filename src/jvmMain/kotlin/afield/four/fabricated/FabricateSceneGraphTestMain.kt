package afield.four.fabricated

import org.joml.setTranslation
import org.joml.translate
import afield.stdlib.linalg.Vector3f

/** Pure JVM smoke test for hierarchical transform composition. */
object FabricateSceneGraphTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val scene = FabricateScene("test-scene")
        val parent = FabricateNode("parent").setTranslation(10f, 0f, 0f)
        val child = FabricateNode("child").setTranslation(0f, 2f, 0f)
        val grandchild = FabricateNode("grandchild").setTranslation(0f, 0f, 3f)

        scene.root += parent
        parent += child
        child += grandchild
        scene.updateWorld()

        assertPosition(parent, 10f, 0f, 0f)
        assertPosition(child, 10f, 2f, 0f)
        assertPosition(grandchild, 10f, 2f, 3f)

        parent.translate(-4f, 1f, 0f)
        scene.updateWorld()
        assertPosition(parent, 6f, 1f, 0f)
        assertPosition(child, 6f, 3f, 0f)
        assertPosition(grandchild, 6f, 3f, 3f)

        val names = mutableListOf<String>()
        scene.traverse { names += it.name }
        check(names == listOf("test-scene", "parent", "child", "grandchild")) {
            "unexpected traversal order $names"
        }

        check(parent.remove(child)) { "child should be removable" }
        scene.updateWorld()
        child.updateWorld(parentWorld = null, force = true)
        assertPosition(child, 0f, 2f, 0f)

        println("FabricateSceneGraphTestMain ok")
    }

    private fun assertPosition(node: FabricateNode, x: Float, y: Float, z: Float) {
        val p = node.worldPosition(Vector3f())
        check(p.distance(Vector3f(x, y, z)) < 1e-5f) {
            "${node.name} was at $p, expected ($x, $y, $z)"
        }
    }
}
