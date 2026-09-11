package afield.four.fabricated

import afield.four.modules.GltfSupport
import afield.four.modules.fromContext
import afield.four.modules.sample
import afield.four.texture.LoadedTexture
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.assimp.Assimp
import kotlin.math.abs
import kotlin.math.max

/**
 * The scene graph: nodes, transforms, materials, lights, animation playback, and the glTF import
 * that fills them.
 *
 * This file owns no Vulkan resources and imports no device type, which is a constraint rather than
 * an accident. A `FabricateNode` carries a transform and children; a renderable carries a
 * `FabricateMaterial` and the intent to be drawn; `updateWorldTransforms` composes the hierarchy
 * into world matrices. None of that needs a GPU, so the scene graph, the animation sampler and the
 * draw-order planning in `FabricateRenderPlan.kt` are all exercisable from a plain `main` — which
 * `FabricateSceneGraphTestMain` and `FabricateSceneTestMain` do, deliberately without a device.
 *
 * `FabricateTransform` is a position/rotation/scale triple rather than a matrix, and that asymmetry
 * has bitten callers: handing the graph a composed `Matrix4f` is not enough on its own, so
 * `FabricateTransform.set(Matrix4f)` decomposes it back into the triple. A transform that cannot
 * be expressed as one — a shear — will not survive the round trip.
 *
 * Lights are a list, and an empty list is not the same as no lighting: it preserves the single
 * default directional light Fabricate has always had, so scenes written before the list existed
 * render unchanged. One shadow-casting directional light is treated as the key light and drives the
 * shadow map; the rest reach the deferred lighting pass through a lights buffer.
 *
 * `FabricateAlphaMode` is the field that decides which of the three draw queues a renderable joins,
 * and it is the same OPAQUE/MASK/BLEND distinction glTF makes, carried through deliberately rather
 * than collapsed.
 */

/**
 * A small Three.js-like scene layer for Fabricate. It is deliberately independent of Vulkan
 * resources: nodes own transforms, renderables own material/rendering intent, and renderer
 * bindings can consume the ordered draw lists.
 */
class FabricateScene(
    val root: FabricateNode = FabricateNode("root")
) {
    constructor(name: String) : this(FabricateNode(name))

    init {
        root.scene = this
    }

    var renderProfile: FabricateRenderProfile = FabricateRenderProfile.outOfBox()
    var animationTime: Float = 0f

    /**
     * Scene lights, Three.js style. The renderer treats one shadow-casting directional light as the
     * key light (it drives the shadow map and the built-in `LightData` uniform); every other light
     * is uploaded to the deferred lighting pass through a lights buffer. An empty list keeps the
     * historical single default directional light untouched, so existing scenes render unchanged.
     */
    val lights: MutableList<FabricateLight> = mutableListOf()

    fun addLight(light: FabricateLight): FabricateLight {
        lights += light
        return light
    }

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

    fun updateWorld(): FabricateScene =
        updateWorldTransforms()

    fun traverse(visit: (FabricateNode) -> Unit) {
        root.walk().forEach(visit)
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
                // The node's true world matrix, NOT bindPose.toMatrix(): a TRS cannot carry a mirror
                // or a shear, and decomposeToTRS flattens a mirrored node to a pure translation with
                // no error raised. See GltfSupport.MeshInstance.bindMatrix.
                transform.set(instance.bindMatrix ?: instance.bindPose.toMatrix())
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
    var previousWorldMatrix: Matrix4f = Matrix4f().identity()
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

    operator fun plusAssign(child: FabricateNode) {
        add(child)
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

    fun translate(x: Float, y: Float, z: Float): FabricateNode {
        transform.position.add(x, y, z)
        return this
    }

    fun setTranslation(x: Float, y: Float, z: Float): FabricateNode {
        transform.position.set(x, y, z)
        return this
    }

    fun updateFromRoot(): FabricateNode {
        scene?.updateWorldTransforms() ?: updateWorld(Matrix4f().identity(), 0f)
        return this
    }

    fun updateWorld(parentWorld: Matrix4f? = parent?.worldMatrix, force: Boolean = true): FabricateNode {
        val base = parentWorld ?: Matrix4f().identity()
        updateWorld(base, scene?.animationTime ?: 0f)
        return this
    }

    internal fun updateWorld(parentWorld: Matrix4f, time: Float) {
        animationSamples?.sample(time)?.let { transform.set(it.toMatrix()) }
        previousWorldMatrix = Matrix4f(worldMatrix)
        worldMatrix = Matrix4f(parentWorld).mul(transform.matrix())
        children.forEach { it.updateWorld(worldMatrix, time) }
    }

    fun worldPoint(local: Vector3f = Vector3f()): Vector3f =
        worldMatrix.transformPosition(Vector3f(local))

    fun worldPosition(dest: Vector3f = Vector3f()): Vector3f =
        worldMatrix.getTranslation(dest)

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
    /**
     * The exact matrix [set] was handed, kept only when the TRS triple above cannot reproduce it.
     *
     * A translation/rotation/scale triple is not a general 4x4. `Matrix4f.getScale` is `sqrt(...)`
     * per basis column and so never negative, which means a **mirror** — a negative-determinant
     * transform, `scale (-1, 1, 1)` in a glTF node — decomposes to a *positive* scale and recomposes
     * with determinant `+1`: the reflection is gone and the geometry is placed un-mirrored with no
     * error raised. A shear does not survive either. See `GltfSupport.MeshInstance.bindMatrix`, which
     * is the same defect one layer up, and `afield.stdlib.AssimpSceneBridgeTest`.
     *
     * So [set] measures its own decomposition: if recomposing the TRS does not return the matrix it
     * was given, it keeps the original here and [matrix] returns it verbatim. For every transform
     * that *is* a TRS — the overwhelming majority, and every scene that worked before this existed —
     * this stays null and nothing changes.
     *
     * The cost of that, stated plainly: while this is non-null the TRS fields are a *lossy summary*
     * and mutating them in place (`transform.position.add(...)`, `FabricateNode.translate`) has no
     * effect on what is drawn. Call [clearMatrix] first, or go through the TRS [set] overload, which
     * clears it for you.
     *
     * A body property rather than a constructor parameter, so it is not a `data class` component:
     * [Matrix4f] has no structural equality, and putting it in `equals` / `copy` would change the
     * identity of every transform in every scene. The consequence is that `copy()` drops it — no
     * caller does that today, and a node's `transform` is a `val` built with the node.
     */
    var matrixOverride: Matrix4f? = null
        private set

    /** Discard [matrixOverride] and go back to composing the TRS fields. */
    fun clearMatrix(): FabricateTransform {
        matrixOverride = null
        return this
    }

    fun matrix(): Matrix4f =
        matrixOverride?.let { Matrix4f(it) }
            ?: Matrix4f().translate(position).rotate(rotation).scale(scale)

    fun set(matrix: Matrix4f): FabricateTransform {
        matrix.getTranslation(position)
        matrix.getUnnormalizedRotation(rotation)
        rotation.normalize()
        matrix.getScale(scale)
        // Does the decomposition actually round-trip? A mirror or a shear does not, and silently
        // placing that geometry somewhere plausible-but-wrong is the failure this guards.
        val recomposed = Matrix4f().translate(position).rotate(rotation).scale(scale)
        matrixOverride = if (matrix.approximatelyEquals(recomposed)) null else Matrix4f(matrix)
        return this
    }

    fun set(position: Vector3f, rotation: Quaternionf = Quaternionf(), scale: Vector3f = Vector3f(1f, 1f, 1f)): FabricateTransform {
        this.position.set(position)
        this.rotation.set(rotation).normalize()
        this.scale.set(scale)
        matrixOverride = null
        return this
    }

    companion object {
        /**
         * Element-wise comparison with a tolerance scaled to the magnitudes involved, because a
         * ToyCar node's `1e-4` scale and a metre-scale translation cannot share an absolute epsilon.
         */
        private fun Matrix4f.approximatelyEquals(other: Matrix4f, relative: Float = 1e-4f): Boolean {
            for (i in 0 until 16) {
                val a = this[i]
                val b = other[i]
                val tolerance = relative * max(1f, max(abs(a), abs(b)))
                if (abs(a - b) > tolerance) return false
            }
            return true
        }
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
    var baseColorTexture: LoadedTexture? = null,
    var normalTexture: LoadedTexture? = null,
    var metallicRoughnessTexture: LoadedTexture? = null,
    var occlusionTexture: LoadedTexture? = null,
    var emissiveTexture: LoadedTexture? = null,
    var clearcoatTexture: LoadedTexture? = null,
    var emissiveFactor: Vector3f = Vector3f(0f, 0f, 0f),
    var clearcoatFactor: Float = 0f,
    var clearcoatRoughnessFactor: Float = 0f,
    var uvTransform: Vector4f = Vector4f(0f, 0f, 1f, 1f),
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
        fun fromGltf(material: GltfSupport.MaterialTextured): FabricateMaterial {
            val emissiveTexture = material.textures.firstOrNull {
                it.valid && it.type == Assimp.aiTextureType_EMISSIVE
            }?.texture
            val metallicRoughnessTexture = material.textures.firstOrNull {
                it.valid && (it.type == Assimp.aiTextureType_GLTF_METALLIC_ROUGHNESS || it.type == Assimp.aiTextureType_DIFFUSE_ROUGHNESS || it.type == Assimp.aiTextureType_METALNESS)
            }?.texture
            val occlusionTexture = material.textures.firstOrNull {
                it.valid && (it.type == Assimp.aiTextureType_AMBIENT_OCCLUSION || it.type == Assimp.aiTextureType_LIGHTMAP)
            }?.texture ?: metallicRoughnessTexture
            val clearcoatTexture = material.textures.firstOrNull {
                it.valid && it.type == Assimp.aiTextureType_CLEARCOAT
            }?.texture
            val transmission = material.transmissionFactor.coerceIn(0f, 1f)
            val base = Vector4f(material.baseColor)
            if (transmission > 0.001f && base.w >= 0.999f) {
                base.w = (1f - transmission * 0.72f).coerceIn(0.18f, 0.42f)
            }
            val alphaMode = when {
                material.alphaMode.equals("MASK", ignoreCase = true) -> FabricateAlphaMode.MASK
                material.alphaMode.equals("BLEND", ignoreCase = true) -> FabricateAlphaMode.BLEND
                transmission > 0.001f -> FabricateAlphaMode.BLEND
                base.w < 0.999f -> FabricateAlphaMode.BLEND
                else -> FabricateAlphaMode.OPAQUE
            }
            return FabricateMaterial(
                baseColor = base,
                metallic = material.metallicFactor,
                roughness = material.roughnessFactor.coerceIn(0.02f, 1f),
                baseColorTexture = material.textures.firstOrNull {
                    it.valid && (it.type == Assimp.aiTextureType_BASE_COLOR || it.type == Assimp.aiTextureType_DIFFUSE)
                }?.texture,
                normalTexture = material.textures.firstOrNull {
                    it.valid && it.type == Assimp.aiTextureType_NORMALS
                }?.texture,
                metallicRoughnessTexture = metallicRoughnessTexture,
                occlusionTexture = occlusionTexture,
                emissiveTexture = emissiveTexture,
                clearcoatTexture = clearcoatTexture,
                emissiveFactor = if (emissiveTexture != null) Vector3f(1f, 1f, 1f) else Vector3f(0f, 0f, 0f),
                clearcoatFactor = material.clearcoatFactor,
                clearcoatRoughnessFactor = material.clearcoatRoughnessFactor,
                alphaMode = alphaMode,
                alphaCutoff = material.alphaCutoff,
                doubleSided = material.doubleSided,
                receiveShadow = transmission <= 0.001f,
                receiveAo = transmission <= 0.001f
            )
        }
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

enum class FabricateLightType {
    DIRECTIONAL,
    POINT,
    SPOT
}

/**
 * A light source. Directional lights model the sun (parallel rays, no falloff); point lights radiate
 * from a position with inverse-square falloff clamped by [range]; spot lights add a cone defined by
 * [innerConeDegrees]/[outerConeDegrees]. Only one directional light with [castShadow] can drive the
 * shadow map at a time (the first such light wins); the rest are shaded without shadows.
 */
data class FabricateLight(
    var type: FabricateLightType = FabricateLightType.DIRECTIONAL,
    var position: Vector3f = Vector3f(0f, 3f, 0f),
    var direction: Vector3f = Vector3f(-0.35f, -1f, -0.25f),
    var color: Vector3f = Vector3f(1f, 1f, 1f),
    var intensity: Float = 1f,
    var range: Float = 0f,
    var innerConeDegrees: Float = 20f,
    var outerConeDegrees: Float = 30f,
    var castShadow: Boolean = false
) {
    companion object {
        fun directional(
            direction: Vector3f,
            color: Vector3f = Vector3f(1f, 1f, 1f),
            intensity: Float = 3.5f,
            castShadow: Boolean = true
        ): FabricateLight = FabricateLight(
            type = FabricateLightType.DIRECTIONAL,
            direction = Vector3f(direction),
            color = Vector3f(color),
            intensity = intensity,
            castShadow = castShadow
        )

        fun point(
            position: Vector3f,
            color: Vector3f = Vector3f(1f, 1f, 1f),
            intensity: Float = 6f,
            range: Float = 8f
        ): FabricateLight = FabricateLight(
            type = FabricateLightType.POINT,
            position = Vector3f(position),
            color = Vector3f(color),
            intensity = intensity,
            range = range
        )

        fun spot(
            position: Vector3f,
            direction: Vector3f,
            color: Vector3f = Vector3f(1f, 1f, 1f),
            intensity: Float = 8f,
            range: Float = 12f,
            innerConeDegrees: Float = 16f,
            outerConeDegrees: Float = 26f
        ): FabricateLight = FabricateLight(
            type = FabricateLightType.SPOT,
            position = Vector3f(position),
            direction = Vector3f(direction),
            color = Vector3f(color),
            intensity = intensity,
            range = range,
            innerConeDegrees = innerConeDegrees,
            outerConeDegrees = outerConeDegrees
        )
    }
}

data class FabricateRegisteredGltf(
    val nodes: List<FabricateNode>,
    val opaqueMeshes: List<StandardRenderer.Bundle.PullingMeshPair>,
    val transparentMeshes: List<StandardRenderer.PullingMesh>,
    val meshBindings: List<FabricateGltfMeshBinding> = emptyList()
) {
    fun animate(time: Float): FabricateRegisteredGltf {
        nodes.firstOrNull()?.scene?.updateWorldTransforms(time)
        return this
    }

    val meshes: List<StandardRenderer.Bundle.PullingMeshPair>
        get() = opaqueMeshes
}

data class FabricateGltfMeshBinding(
    val index: Int,
    val node: FabricateNode,
    val opaque: StandardRenderer.Bundle.PullingMeshPair? = null,
    val transparent: StandardRenderer.PullingMesh? = null
) {
    fun applyMaterial(material: FabricateMaterial): FabricateGltfMeshBinding {
        opaque?.applyFabricateMaterial(material)
        transparent?.applyFabricateMaterial(material)
        return this
    }
}

fun StandardRenderer.Bundle.applyRenderProfile(profile: FabricateRenderProfile = FabricateRenderProfile.outOfBox()): StandardRenderer.Bundle =
    profile.applyTo(this)

fun StandardRenderer.Bundle.sceneDrawLists(cameraPosition: Vector3f = Vector3f(scene.camera.at)): FabricateDrawLists =
    sceneGraph.drawLists(cameraPosition)

fun StandardRenderer.Bundle.planScene(
    cameraPosition: Vector3f = Vector3f(scene.camera.at),
    planner: FabricateScenePlanner = FabricateScenePlanner()
): FabricateRenderPlan =
    planner.plan(sceneGraph, cameraPosition)

fun StandardRenderer.Bundle.applyScenePlan(plan: FabricateRenderPlan = planScene()): FabricateRenderPlan {
    require(plan.allDrawItems.isEmpty() || plan.materials.isNotEmpty()) {
        "Fabricate planner returned draw items without material records"
    }

    reorderFabricateMeshes(
        gBufferModule.current.meshes,
        (plan.opaque + plan.alphaMask).fabricateMeshNames()
    )
    reorderFabricateMeshes(
        shadowModule.current.meshes,
        plan.shadowCasters.fabricateMeshNames()
    )
    reorderFabricateMeshes(
        transparentModule.current.meshes,
        plan.transparent.fabricateMeshNames()
    )
    return plan
}

private fun List<FabricateRenderItem>.fabricateMeshNames(): List<String> =
    mapNotNull { it.renderable.userData as? String }

private fun <T> reorderFabricateMeshes(meshes: MutableMap<String, T>, orderedNames: List<String>) {
    if (orderedNames.isEmpty()) return
    val reordered = linkedMapOf<String, T>()
    orderedNames.forEach { name ->
        meshes[name]?.let { reordered[name] = it }
    }
    meshes.forEach { (name, mesh) ->
        if (name !in reordered) reordered[name] = mesh
    }
    meshes.clear()
    meshes.putAll(reordered)
}

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
    val bindings = mutableListOf<FabricateGltfMeshBinding>()

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
            bindings += FabricateGltfMeshBinding(index, node, transparent = mesh)
        } else {
            val pair = material.baseColorTexture?.let {
                registerTexturedPullingMesh(
                    name = meshName,
                    maxVertices = instance.buffers.indexCount.coerceAtLeast(3),
                    material = material,
                    renderer = renderer
                )
            } ?: registerPullingMesh(
                    name = meshName,
                    maxVertices = instance.buffers.indexCount.coerceAtLeast(3),
                    renderer = renderer
                )
            writeGltfMesh(instance, pair)
            pair.bindModel { Matrix4f(node.worldMatrix) }
            pair.applyFabricateMaterial(material)
            opaque += pair
            bindings += FabricateGltfMeshBinding(index, node, opaque = pair)
        }
    }
    return FabricateRegisteredGltf(nodes, opaque, transparent, bindings)
}

fun StandardRenderer.Bundle.PullingMeshPair.applyFabricateMaterial(
    material: FabricateMaterial
): StandardRenderer.Bundle.PullingMeshPair {
    val push = gBuffer.mesh.push
    if (push.offsets.containsKey("baseColor")) push["baseColor"] = Vector4f(material.baseColor)
    if (push.offsets.containsKey("metallic")) push["metallic"] = material.metallic
    if (push.offsets.containsKey("roughness")) push["roughness"] = material.roughness
    if (push.offsets.containsKey("ao")) push["ao"] = if (material.receiveAo) 1f else 0f
    if (push.offsets.containsKey("hasNormalMap")) push["hasNormalMap"] = if (material.normalTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasMetallicRoughnessMap")) push["hasMetallicRoughnessMap"] = if (material.metallicRoughnessTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasOcclusionMap")) push["hasOcclusionMap"] = if (material.occlusionTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasEmissiveMap")) push["hasEmissiveMap"] = if (material.emissiveTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasClearcoatMap")) push["hasClearcoatMap"] = if (material.clearcoatTexture != null) 1f else 0f
    if (push.offsets.containsKey("emissiveFactor")) push["emissiveFactor"] = Vector4f(material.emissiveFactor, 1f)
    if (push.offsets.containsKey("clearcoatFactor")) push["clearcoatFactor"] = material.clearcoatFactor
    if (push.offsets.containsKey("clearcoatRoughness")) push["clearcoatRoughness"] = material.clearcoatRoughnessFactor
    if (push.offsets.containsKey("uvTransform")) push["uvTransform"] = Vector4f(material.uvTransform)
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
    if (push.offsets.containsKey("hasNormalMap")) push["hasNormalMap"] = if (material.normalTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasMetallicRoughnessMap")) push["hasMetallicRoughnessMap"] = if (material.metallicRoughnessTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasOcclusionMap")) push["hasOcclusionMap"] = if (material.occlusionTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasEmissiveMap")) push["hasEmissiveMap"] = if (material.emissiveTexture != null) 1f else 0f
    if (push.offsets.containsKey("hasClearcoatMap")) push["hasClearcoatMap"] = if (material.clearcoatTexture != null) 1f else 0f
    if (push.offsets.containsKey("emissiveFactor")) push["emissiveFactor"] = Vector4f(material.emissiveFactor, 1f)
    if (push.offsets.containsKey("clearcoatFactor")) push["clearcoatFactor"] = material.clearcoatFactor
    if (push.offsets.containsKey("clearcoatRoughness")) push["clearcoatRoughness"] = material.clearcoatRoughnessFactor
    if (push.offsets.containsKey("uvTransform")) push["uvTransform"] = Vector4f(material.uvTransform)
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
    val tangentData = instance.buffers.tangentData?.asFloatBuffer()
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
    mesh.uv?.write {
        for (i in 0 until indexCount) {
            val base = indexData.get(i) * 12
            put(vertexData.get(base + 6), vertexData.get(base + 7), 0f, 0f)
        }
    }
    mesh.tangent?.write {
        for (i in 0 until indexCount) {
            val base = indexData.get(i) * 4
            if (tangentData != null) {
                put(
                    tangentData.get(base),
                    tangentData.get(base + 1),
                    tangentData.get(base + 2),
                    tangentData.get(base + 3)
                )
            } else {
                put(1f, 0f, 0f, 0f)
            }
        }
    }
    mesh.setVertexCount(indexCount)
}
