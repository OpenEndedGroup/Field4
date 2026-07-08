package afield.four.fabricated

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Minimal hierarchical transform node for the fabricated renderer.
 *
 * This is deliberately renderer-agnostic: nodes own local/world transforms and visibility, while
 * renderer adapters decide which nodes are fed into g-buffer, shadow, transparent, or utility
 * queues. Mutating helpers mark the node and descendants dirty; direct mutation of [local] is also
 * supported, but callers should invoke [markDirty] before the next [updateFromRoot].
 */
open class FabricateNode(var name: String = "node") {
    val local: FabricateTransform = FabricateTransform.identity()

    private val _children = mutableListOf<FabricateNode>()
    val children: List<FabricateNode>
        get() = _children

    var parent: FabricateNode? = null
        private set

    var visible: Boolean = true
    var layers: Int = 1

    private val world = Matrix4f()
    private val previousWorld = Matrix4f()
    private var dirty = true

    fun add(child: FabricateNode): FabricateNode {
        require(child !== this) { "cannot parent a node to itself" }
        require(!isDescendantOf(child)) { "cannot add an ancestor as a child" }
        if (child.parent === this) return child
        child.parent?.remove(child)
        child.parent = this
        _children += child
        child.markDirty()
        return child
    }

    operator fun plusAssign(child: FabricateNode) {
        add(child)
    }

    fun remove(child: FabricateNode): Boolean {
        val removed = _children.remove(child)
        if (removed) {
            child.parent = null
            child.markDirty()
        }
        return removed
    }

    fun clearChildren() {
        _children.toList().forEach { remove(it) }
    }

    fun markDirty() {
        dirty = true
        _children.forEach { it.markDirty() }
    }

    fun translate(x: Float, y: Float, z: Float): FabricateNode {
        local.translation.add(x, y, z)
        markDirty()
        return this
    }

    fun setTranslation(x: Float, y: Float, z: Float): FabricateNode {
        local.translation.set(x, y, z)
        markDirty()
        return this
    }

    fun setRotation(rotation: Quaternionf): FabricateNode {
        local.rotation.set(rotation)
        markDirty()
        return this
    }

    fun setScale(x: Float, y: Float, z: Float): FabricateNode {
        local.scale.set(x, y, z)
        markDirty()
        return this
    }

    fun updateFromRoot() {
        root().updateWorld(parentWorld = null, force = false)
    }

    fun updateWorld(parentWorld: Matrix4f? = parent?.world, force: Boolean = true) {
        val shouldUpdate = force || dirty
        if (shouldUpdate) {
            previousWorld.set(world)
            val localMatrix = local.toMatrix()
            if (parentWorld == null) {
                world.set(localMatrix)
            } else {
                world.set(parentWorld).mul(localMatrix)
            }
            dirty = false
        }
        _children.forEach { it.updateWorld(world, force = shouldUpdate) }
    }

    fun worldMatrix(): Matrix4f = Matrix4f(world)

    fun previousWorldMatrix(): Matrix4f = Matrix4f(previousWorld)

    fun root(): FabricateNode {
        var r = this
        while (r.parent != null) r = r.parent!!
        return r
    }

    fun traverse(includeSelf: Boolean = true, visit: (FabricateNode) -> Unit) {
        if (includeSelf) visit(this)
        _children.forEach { it.traverse(includeSelf = true, visit = visit) }
    }

    fun worldPosition(dest: Vector3f = Vector3f()): Vector3f = world.getTranslation(dest)

    private fun isDescendantOf(candidateAncestor: FabricateNode): Boolean {
        var p = parent
        while (p != null) {
            if (p === candidateAncestor) return true
            p = p.parent
        }
        return false
    }
}

class FabricateScene(name: String = "scene") {
    val root = FabricateNode(name)

    fun updateWorld() {
        root.updateWorld(parentWorld = null, force = true)
    }

    fun traverse(visit: (FabricateNode) -> Unit) {
        root.traverse(includeSelf = true, visit = visit)
    }
}
