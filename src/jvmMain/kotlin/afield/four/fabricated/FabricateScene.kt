package afield.four.fabricated

import afield.four.modules.GltfSupport
import afield.four.modules.fromContext
import afield.four.modules.sample
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.max

/**
 * A small Three.js-like scene layer for Fabricate. It is deliberately independent of Vulkan
 * resources: nodes own transforms, renderables own material/rendering intent, and renderer
 * bindings can consume the ordered draw lists.
 */
class FabricateScene(
    val root: FabricateNode = FabricateNode("root")
) {
    init {
        root.scene = this
    }

    var renderProfile: FabricateRenderProfile = FabricateRenderProfile.outOfBox()
    var animationTime: Float = 0f

    fun node(name: String, parent: FabricateNode = root, configure: FabricateNode.() -> Unit = {}): FabricateNode {
        val n = FabricateNode(name)
        parent.add(n)
        n.configure()
        return n
    }

    fun mesh(
        name: String,
        parent: FabricateNode = root,
        material: FabricateMaterial = FabricateMaterial(),
        configure: FabricateNode.() -> Unit = {}
    ): FabricateNode = node(name, parent) {
        renderable = FabricateRenderable(material = material)
        configure()
    }

    fun updateWorldTransforms(time: Float = animationTime): FabricateScene {
        root.updateWorld(Matrix4f().identity(), time)
        return this
    }

    fun advance(seconds: Float, loop: Boolean = true): FabricateScene {
        val duration = animationDuration()
        animationTime = if (loop && duration > 0f) {
            positiveModulo(animationTime + seconds, duration)
        } else {
            animationTime + seconds
        }
        updateWorldTransforms(animationTime)
        return this
    }

    fun animationDuration(): Float =
        root.walk()
            .mapNotNull { node -> node.animationSamples?.lastOrNull()?.time }
            .maxOrNull() ?: 0f

    fun drawLists(cameraPosition: Vector3f = Vector3f(0f, 0f, 0f), time: Float = animationTime): FabricateDrawLists {
        updateWorldTransforms(time)
        val draws = root.walk()
            .asSequence()
            .filter { it.visible }
            .mapNotNull { node ->
                val renderable = node.renderable ?: return@mapNotNull null
                val worldCenter = node.worldPoint(renderable.boundsCenter)
                FabricateDraw(
                    node = node,
                    renderable = renderable,
                    worldMatrix = Matrix4f(node.worldMatrix),
                    worldCenter = worldCenter,
                    distanceToCamera = worldCenter.distance(cameraPosition),
                    sortKey = renderable.sortKey(node)
                )
            }
            .toList()

        val opaque = draws
            .filter { it.renderable.material.alphaMode == FabricateAlphaMode.OPAQUE }
            .sortedWith(compareBy<FabricateDraw> { it.sortKey }.thenBy { it.distanceToCamera })

        val transparent = draws
            .filter { it.renderable.material.alphaMode != FabricateAlphaMode.OPAQUE }
            .sortedWith(compareBy<FabricateDraw> { it.sortKey }.thenByDescending { it.distanceToCamera })

        val shadowCasters = draws
            .filter { it.renderable.castShadow && it.renderable.material.alphaMode != FabricateAlphaMode.BLEND }
            .sortedWith(compareBy<FabricateDraw> { it.sortKey }.thenBy { it.node.name })

        return FabricateDrawLists(opaque = opaque, transparent = transparent, shadowCasters = shadowCasters)
    }

    fun importGltf(
        model: GltfSupport.GltfModel,
        namePrefix: String = "gltf",
        parent: FabricateNode = root,
        material: (GltfSupport.MeshInstance) -> FabricateMaterial = { FabricateMaterial.fromGltf(it.material) }
    ): List<FabricateNode> {
        return model.meshes.mapIndexed { index, instance ->
            mesh(
                name = "${namePrefix}-${instance.name.ifBlank { "mesh" }}-$index",
                parent = parent,
                material = material(instance)
            ) {
                transform.set(instance.bindPose.toMatrix())
                animationSamples = instance.animationSamples
                source = instance
                renderable?.boundsRadius = estimateRadius(instance)
            }
        }.also {
            updateWorldTransforms(animationTime)
        }
    }

    companion object {
        private fun positiveModulo(value: Float, modulus: Float): Float {
            val r = value % modulus
            return if (r < 0f) r + modulus else r
        }

        private fun estimateRadius(instance: GltfSupport.MeshInstance): Float {
            val floats = instance.buffers.vertexData.asFloatBuffer()
            var radiusSquared = 0f
            for (i in 0 until instance.buffers.vertexCount) {
                val base = i * 12
                val x = floats.get(base)
                val y = floats.get(base + 1)
                val z = floats.get(base + 2)
                radiusSquared = max(radiusSquared, x * x + y * y + z * z)
            }
            return kotlin.math.sqrt(radiusSquared)
        }
    }
}

class FabricateNode(val name: String) {
    internal var scene: FabricateScene? = null
    var parent: FabricateNode? = null
        private set
    val children: MutableList<FabricateNode> = mutableListOf()
    val transform: FabricateTransform = FabricateTransform()
    var worldMatrix: Matrix4f = Matrix4f().identity()
        private set
    var renderable: FabricateRenderable? = null
    var visible: Boolean = true
    var animationSamples: List<GltfSupport.TRSSample>? = null
    var source: Any? = null

    fun add(child: FabricateNode): FabricateNode {
        require(child !== this) { "Cannot parent a node to itself" }
        require(!child.walk().contains(this)) { "Cannot create a FabricateScene cycle with ${child.name}" }
        child.parent?.children?.remove(child)
        child.parent = this
        child.scene = scene
        children += child
        child.propagateScene(scene)
        return child
    }

    fun remove(child: FabricateNode): Boolean {
        val removed = children.remove(child)
        if (removed) {
            child.parent = null
            child.propagateScene(null)
        }
        return removed
    }

    fun walk(): Sequence<FabricateNode> = sequence {
        yield(this@FabricateNode)
        children.forEach { child -> yieldAll(child.walk()) }
    }

    internal fun updateWorld(parentWorld: Matrix4f, time: Float) {
        animationSamples?.sample(time)?.let { transform.set(it.toMatrix()) }
        worldMatrix = Matrix4f(parentWorld).mul(transform.matrix())
        children.forEach { it.updateWorld(worldMatrix, time) }
    }

    fun worldPoint(local: Vector3f = Vector3f()): Vector3f =
        worldMatrix.transformPosition(Vector3f(local))

    private fun propagateScene(nextScene: FabricateScene?) {
        scene = nextScene
        children.forEach { it.propagateScene(nextScene) }
    }
}

data class FabricateTransform(
    val position: Vector3f = Vector3f(),
    val rotation: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f, 1f, 1f)
) {
    fun matrix(): Matrix4f =
        Matrix4f().translate(position).rotate(rotation).scale(scale)

    fun set(matrix: Matrix4f): FabricateTransform {
        matrix.getTranslation(position)
        matrix.getUnnormalizedRotation(rotation)
        rotation.normalize()
        matrix.getScale(scale)
        return this
    }

    fun set(position: Vector3f, rotation: Quaternionf = Quaternionf(), scale: Vector3f = Vector3f(1f, 1f, 1f)): FabricateTransform {
        this.position.set(position)
        this.rotation.set(rotation).normalize()
        this.scale.set(scale)
        return this
    }
}

enum class FabricateAlphaMode {
    OPAQUE,
    MASK,
    BLEND
}

data class FabricateMaterial(
    val baseColor: Vector4f = Vector4f(1f, 0.56f, 0.24f, 1f),
    var metallic: Float = 0.08f,
    var roughness: Float = 0.42f,
    var alphaMode: FabricateAlphaMode = FabricateAlphaMode.OPAQUE,
    var alphaCutoff: Float = 0.5f,
    var doubleSided: Boolean = false,
    var receiveShadow: Boolean = true,
    var receiveAo: Boolean = true,
    var sortOrder: Int = 0
) {
    fun transparent(opacity: Float = baseColor.w): FabricateMaterial {
        baseColor.w = opacity
        alphaMode = FabricateAlphaMode.BLEND
        return this
    }

    companion object {
        fun fromGltf(material: GltfSupport.MaterialTextured): FabricateMaterial =
            FabricateMaterial(
                baseColor = Vector4f(material.baseColor),
                alphaMode = if (material.baseColor.w < 0.999f) FabricateAlphaMode.BLEND else FabricateAlphaMode.OPAQUE
            )
    }
}

data class FabricateRenderable(
    val material: FabricateMaterial = FabricateMaterial(),
    var castShadow: Boolean = true,
    var boundsCenter: Vector3f = Vector3f(),
    var boundsRadius: Float = 1f,
    var userData: Any? = null
) {
    fun sortKey(node: FabricateNode): Int =
        material.sortOrder * 31 + node.name.hashCode()
}

data class FabricateDraw(
    val node: FabricateNode,
    val renderable: FabricateRenderable,
    val worldMatrix: Matrix4f,
    val worldCenter: Vector3f,
    val distanceToCamera: Float,
    val sortKey: Int
)

data class FabricateDrawLists(
    val opaque: List<FabricateDraw>,
    val transparent: List<FabricateDraw>,
    val shadowCasters: List<FabricateDraw>
) {
    val allVisible: List<FabricateDraw>
        get() = opaque + transparent
}

data class FabricateRenderProfile(
    var ambientOcclusion: Boolean = true,
    var aoStrength: Float = 0.6f,
    var aoRadius: Float = 0.35f,
    var softShadows: Boolean = true,
    var shadowStrength: Float = 1f,
    var shadowSoftness: Float = 1.75f,
    var motionBlur: Boolean = true,
    var antiAlias: Boolean = true
) {
    fun applyTo(bundle: StandardRenderer.Bundle): StandardRenderer.Bundle {
        bundle.scene.aoStrength = if (ambientOcclusion) aoStrength else 0f
        bundle.scene.aoRadius = aoRadius
        bundle.scene.shadowStrength = if (softShadows) shadowStrength else 0f
        bundle.scene.shadowSoftness = if (softShadows) shadowSoftness else 0f
        bundle.scene.motionBlurStrength = if (motionBlur) bundle.scene.motionBlurStrength else 0f
        bundle.scene.aaBlend = if (antiAlias) 1f else 0f
        bundle.sceneGraph.renderProfile = this
        return bundle
    }

    companion object {
        fun outOfBox(): FabricateRenderProfile = FabricateRenderProfile()
    }
}

data class FabricateRegisteredGltf(
    val nodes: List<FabricateNode>,
    val opaqueMeshes: List<StandardRenderer.Bundle.PullingMeshPair>,
    val transparentMeshes: List<StandardRenderer.PullingMesh>
) {
    fun animate(time: Float): FabricateRegisteredGltf {
        nodes.firstOrNull()?.scene?.updateWorldTransforms(time)
        return this
    }

    val meshes: List<StandardRenderer.Bundle.PullingMeshPair>
        get() = opaqueMeshes
}

fun StandardRenderer.Bundle.applyRenderProfile(profile: FabricateRenderProfile = FabricateRenderProfile.outOfBox()): StandardRenderer.Bundle =
    profile.applyTo(this)

fun StandardRenderer.Bundle.sceneDrawLists(cameraPosition: Vector3f = Vector3f(scene.camera.at)): FabricateDrawLists =
    sceneGraph.drawLists(cameraPosition)

fun StandardRenderer.Bundle.registerGltf(
    path: String,
    namePrefix: String = "gltf",
    config: GltfSupport.GltfLoadConfig = GltfSupport.GltfLoadConfig(),
    renderer: afield.four.Renderer = fromContext()
): FabricateRegisteredGltf =
    registerGltf(GltfSupport.loadGltfBasic(path, config), namePrefix, renderer)

fun StandardRenderer.Bundle.registerGltf(
    model: GltfSupport.GltfModel,
    namePrefix: String = "gltf",
    renderer: afield.four.Renderer = fromContext()
): FabricateRegisteredGltf {
    val nodes = sceneGraph.importGltf(model, namePrefix)
    val opaque = mutableListOf<StandardRenderer.Bundle.PullingMeshPair>()
    val transparent = mutableListOf<StandardRenderer.PullingMesh>()

    model.meshes.zip(nodes).forEachIndexed { index, (instance, node) ->
        val material = node.renderable?.material ?: FabricateMaterial.fromGltf(instance.material)
        val meshName = "${namePrefix}-${index}"
        node.renderable?.userData = meshName
        if (material.alphaMode == FabricateAlphaMode.BLEND) {
            val mesh = registerTransparentPullingMesh(
                name = meshName,
                maxVertices = instance.buffers.indexCount.coerceAtLeast(3),
                renderer = renderer
            )
            writeGltfMesh(instance, mesh)
            mesh.bindModel { Matrix4f(node.worldMatrix) }
            mesh.applyFabricateMaterial(material)
            transparent += mesh
        } else {
            val pair = registerPullingMesh(
                name = meshName,
                maxVertices = instance.buffers.indexCount.coerceAtLeast(3),
                renderer = renderer
            )
            writeGltfMesh(instance, pair)
            pair.bindModel { Matrix4f(node.worldMatrix) }
            pair.applyFabricateMaterial(material)
            opaque += pair
        }
    }
    return FabricateRegisteredGltf(nodes, opaque, transparent)
}

fun StandardRenderer.Bundle.PullingMeshPair.applyFabricateMaterial(
    material: FabricateMaterial
): StandardRenderer.Bundle.PullingMeshPair {
    val push = gBuffer.mesh.push
    if (push.offsets.containsKey("baseColor")) push["baseColor"] = Vector4f(material.baseColor)
    if (push.offsets.containsKey("metallic")) push["metallic"] = material.metallic
    if (push.offsets.containsKey("roughness")) push["roughness"] = material.roughness
    if (push.offsets.containsKey("ao")) push["ao"] = if (material.receiveAo) 1f else 0f
    return this
}

fun StandardRenderer.PullingMesh.applyFabricateMaterial(
    material: FabricateMaterial
): StandardRenderer.PullingMesh {
    val push = mesh.push
    if (push.offsets.containsKey("baseColor")) push["baseColor"] = Vector4f(material.baseColor)
    if (push.offsets.containsKey("metallic")) push["metallic"] = material.metallic
    if (push.offsets.containsKey("roughness")) push["roughness"] = material.roughness
    if (push.offsets.containsKey("ao")) push["ao"] = if (material.receiveAo) 1f else 0f
    return this
}

private fun writeGltfMesh(
    instance: GltfSupport.MeshInstance,
    pair: StandardRenderer.Bundle.PullingMeshPair
) {
    writeGltfMesh(instance, pair.gBuffer)
    pair.shadow.setVertexCount(pair.gBuffer.mesh.numVert)
}

private fun writeGltfMesh(
    instance: GltfSupport.MeshInstance,
    mesh: StandardRenderer.PullingMesh
) {
    val vertexData = instance.buffers.vertexData.asFloatBuffer()
    val indexData = instance.buffers.indexData
    val indexCount = instance.buffers.indexCount

    mesh.position.write {
        for (i in 0 until indexCount) {
            val base = indexData.get(i) * 12
            put(vertexData.get(base), vertexData.get(base + 1), vertexData.get(base + 2), 1f)
        }
    }
    mesh.normal.write {
        for (i in 0 until indexCount) {
            val base = indexData.get(i) * 12
            put(vertexData.get(base + 3), vertexData.get(base + 4), vertexData.get(base + 5), 0f)
        }
    }
    mesh.setVertexCount(indexCount)
}
