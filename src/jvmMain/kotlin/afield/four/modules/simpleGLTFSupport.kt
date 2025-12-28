@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.Box
import afield.RunsAt
import afield.four.Renderer
import afield.four.buffers.defaultBuffer
import afield.four.modules.GltfSupport.TRS
import afield.four.modules.GltfSupport.TRSSample
import afield.four.modules.GltfSupport.gltVertexShader
import afield.four.modules.GltfSupport.gltfFragmentShader
import afield.four.modules.GltfSupport.gltfFragmentShaderTextured
import afield.four.texture.HasImageView
import afield.four.texture.LoadedTexture
import afield.four.texture.TextureSampler
import afield.four.util.Camera
import afield.macroRegistration
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.macros.MacroRegistry.callRewrite
import afield.stdlib.HasMatrix4f
import afield.stdlib._moduleHere
import afield.stdlib.fromViews
import afield.stdlib.macro_moduleHereWithDefaultShaders
import kotlinx.coroutines.DelicateCoroutinesApi
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.assimp.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memAllocInt
import java.nio.file.Path
import java.nio.file.Paths

// the idea here is that this module can be customized (at least the first time!) for the (first?) model inside the gltf
fun Box.gltfModelHere(p: Parameters.() -> Unit): Pair<ModulePointer, List<GltfSupport.GltfModel>> {
    throw MacroRegistry.MacroNotExpandedException("gltfModelHere should not be callable directly, but should be expanded as a macro")
}

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_modelHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(
        args, vertex = gltVertexShader, fragment = gltfFragmentShader, resolvesToCall = "_gltfModelHere"
    )
}

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_modelWithTextureHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(
        args,
        vertex = gltVertexShader,
        fragment = gltfFragmentShaderTextured,
        resolvesToCall = "_gltfModelHereWithTexture"
    )
}

fun Box._gltfModelHere(
    filename: String, p: Parameters.() -> Unit = {}
): Pair<ModulePointer, List<GltfSupport.GltfModel>> {
    val renderer: Renderer = fromContext()
    val module = _moduleHere(parameters(renderer = renderer, p))
    val loader = GltfSupport.loadGltfBasic(filename)
    return module to listOf(loader)
}

fun Box._gltfModelHereWithTexture(
    filename: String, p: Parameters.() -> Unit = {}
): Pair<ModulePointer, List<GltfSupport.GltfModel>> {
    val renderer: Renderer = fromContext()
        val loader = GltfSupport.loadGltfBasic(filename, GltfSupport.GltfLoadConfig(loadTextures = true))
        val module = _moduleHere(parameters(renderer = renderer) {
            p()
            val was = textureResolver
            textureResolver = { tex ->
                if (tex.name == "uTextures") {
                    val textures = loader.meshes[2].material.textures.map { slot -> slot.texture as HasImageView }
                    val maxMip = textures.maxOf { texture -> texture.imageView.mipLevels }
                    fromViews(textures, sampler = TextureSampler(renderer = fromContext(), maxMip, true))
                } else was(tex)
            }
        })
        return module to listOf(loader)
    }

@RunsAt(macroRegistration)
fun registerGLTFLoader() {
    callRewrite["gltfModelHere"] = { box, id, args ->
        box.macro_modelHere(args)
    }
    callRewrite["gltfModelWithTextureHere"] = { box, id, args ->
        box.macro_modelWithTextureHere(args)
    }
}

object GltfSupport {

    // ------------------------------------------------------------------------
    // Public data model
    // ------------------------------------------------------------------------

    data class TRS(
        val t: Vector3f, val r: Quaternionf, val s: Vector3f
    ) : HasMatrix4f {
        fun toMatrix(): Matrix4f {
            return Matrix4f()
                .translate(t)
                .rotate(r)
                .scale(s)
        }

        override val m: Matrix4f
            get() = toMatrix()
    }

    data class TRSSample(
        val time: Float, val trs: TRS
    )

    data class MeshBuffers(
        val vertexData: java.nio.ByteBuffer, // tightly packed floats
        val indexData: java.nio.IntBuffer,   // 32-bit indices
        val vertexCount: Int, val indexCount: Int
    )

    data class TextureSlot(
        val type: Int,         // Assimp aiTextureType_*
        val uvSet: Int,        // which UV channel to use
        val texture: LoadedTexture, val valid: Boolean
    )

    data class MaterialTextured(
        val baseColor: Vector4f,            // RGBA (treat as linear in your pipeline)
        val textures: List<TextureSlot> = emptyList()
    )

    data class MeshInstance(
        val name: String,
        val meshIndex: Int,                 // index into scene meshes as loaded
        val buffers: MeshBuffers,           // CPU-side; you decide how to pack into SSBOs
        val bindPose: TRS,                  // node transform at import time (world from root)
        val material: MaterialTextured,
        val doTextures: Boolean = false,
        val animationSamples: List<TRSSample>? // sampled TRS for this node, or null
    ) {
        fun applyToMesh(m: Mesh, camera: Camera = fromContext()) {

            m.inside.current.liveUniformStructures["VertexData"]!!["modelView"] = camera.m()
            m.inside.current.liveUniformStructures["VertexData"]!!["proj"] = camera.p()
            m.inside.current.liveUniformStructures["VertexData"]!!["vertexOffset"] = 0
            m.inside.current.liveUniformStructures["VertexData"]!!["indexOffset"] = 0
            m.inside.current.liveUniformStructures["VertexData"]!!["vertexStride"] = 12
            m.inside.current.liveUniformStructures["VertexData"]!!["materialBaseColor"] = material.baseColor

            val vertexData = defaultBuffer {
                size = (buffers.vertexCount * 12).floats
            }.buffer

            val indexData = defaultBuffer {
                size = buffers.indexCount.ints
            }.buffer

            m.initializePushConstantsFromModule(m.inside)
            m.push["vertexBuffer"] = vertexData
            m.push["indexBuffer"] = indexData

            vertexData.write { this.put(buffers.vertexData.asFloatBuffer()) }

            val f = buffers.vertexData.asFloatBuffer()
            for (n in 0 until f.capacity()) {
                val x = f.get(n)
                print("$x ")
                if (n % 12 == 11) println()
            }
            indexData.write {
                // TODO!
                for (n in 0 until buffers.indexCount) {
                    val x = buffers.indexData.get(n)
                    print("$x ")
                    this.putInt(x)
                    if (n % 3 == 2) println()
                }
            }

            m.resize(buffers.indexCount) // not buffers.vertexCound, because we're not using any vertex / index buffers at all

        }
    }

    data class GltfModel(
        val meshes: List<MeshInstance>
    )

    /**
     * Config:
     * - sampleRate: Hz for animation sampling (if any)
     * - includeVertexColors: when true, always writes RGBA per-vertex.
     * - loadTextures: when true, collect material textures (diffuse/baseColor/normal/etc.)
     *
     * NOTE: The default shaders below assume a fixed layout:
     *   [pos.xyz][normal.xyz][uv.xy][color.rgba] = 12 floats per vertex.
     * For that path, keep includeVertexColors = true so color is present
     * (we fill white if source has no COLOR_0).
     */
    data class GltfLoadConfig(
        val sampleRate: Float = 60f, val includeVertexColors: Boolean = true, val loadTextures: Boolean = true
    )

    // ------------------------------------------------------------------------
    // Assimp loading
    // ------------------------------------------------------------------------

    private const val POST_FLAGS: Int =
        Assimp.aiProcess_Triangulate or Assimp.aiProcess_GenNormals or Assimp.aiProcess_JoinIdenticalVertices or Assimp.aiProcess_ImproveCacheLocality or Assimp.aiProcess_SortByPType

    fun loadGltfBasic(path: String, config: GltfLoadConfig = GltfLoadConfig()): GltfModel {
        val scene = Assimp.aiImportFile(path, POST_FLAGS)
            ?: error("Assimp import failed for '$path': ${Assimp.aiGetErrorString()}")
        val baseDir: Path? = try {
            Paths.get(path).parent
        } catch (_: Exception) {
            null
        }

        if (scene.mFlags() and Assimp.AI_SCENE_FLAGS_INCOMPLETE != 0 || scene.mRootNode() == null) {
            Assimp.aiReleaseImport(scene)
            error("Invalid or incomplete scene for '$path'")
        }

        val meshes = mutableListOf<MeshInstance>()
        val root = scene.mRootNode()
        val identity = Matrix4f()

        traverseNode(scene, root!!, identity, meshes, config, baseDir)

        Assimp.aiReleaseImport(scene)
        return GltfModel(meshes)
    }

    private fun traverseNode(
        scene: AIScene,
        node: AINode,
        parentTransform: Matrix4f,
        outMeshes: MutableList<MeshInstance>,
        config: GltfLoadConfig,
        baseDir: Path?
    ) {
        val local = aiToMatrix4f(node.mTransformation())
        val world = Matrix4f(parentTransform).mul(local)

        val meshIndices = node.mMeshes()
        if (meshIndices != null) {
            for (i in 0 until meshIndices.remaining()) {
                val meshIndex = meshIndices[i]
                val aiMesh = getMesh(scene, meshIndex) ?: continue

                val buffers = extractMeshBuffers(aiMesh, config.includeVertexColors)
                val bindTrs = decomposeToTRS(world)
                val material = extractMaterial(scene, aiMesh, baseDir, config)
                val animSamples = buildAnimationSamplesForNode(scene, node, config.sampleRate)

                outMeshes += MeshInstance(
                    name = node.mName().dataString(),
                    meshIndex = meshIndex,
                    buffers = buffers,
                    bindPose = bindTrs,
                    material = material,
                    animationSamples = animSamples.ifEmpty { null })
            }
        }

        val children = node.mChildren()
        if (children != null) {
            for (i in 0 until children.remaining()) {
                val child = AINode.create(children[i]) ?: continue
                traverseNode(scene, child, world, outMeshes, config, baseDir)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Mesh data extraction
    // ------------------------------------------------------------------------

    private fun extractMeshBuffers(
        aiMesh: AIMesh, includeVertexColors: Boolean
    ): MeshBuffers {
        val vertexCount = aiMesh.mNumVertices()
        val posBuf = aiMesh.mVertices()
        val normalBuf = aiMesh.mNormals()
        val texBuf = aiMesh.mTextureCoords(0)
        val colorBuf = if (includeVertexColors) aiMesh.mColors(0) else null

        val hasNormals = normalBuf != null
        val hasTex = texBuf != null
        val hasColors = colorBuf != null

        // Fixed layout to match default shaders:
        // [pos.xyz][normal.xyz][uv.xy][color.rgba] = 12 floats
        val componentsPerVertex = 12
        val strideBytes = componentsPerVertex * 4
        val vertexData = memAlloc(vertexCount * strideBytes)

        for (i in 0 until vertexCount) {
            // pos
            val v = posBuf[i]
            vertexData.putFloat(v.x())
            vertexData.putFloat(v.y())
            vertexData.putFloat(v.z())

            // normal or default
            if (hasNormals) {
                val n = normalBuf!![i]
                vertexData.putFloat(n.x())
                vertexData.putFloat(n.y())
                vertexData.putFloat(n.z())
            } else {
                vertexData.putFloat(0f)
                vertexData.putFloat(0f)
                vertexData.putFloat(1f)
            }

            // uv or default
            if (hasTex) {
                val t = texBuf!![i]
                println(" tc at load time is ${t} -> ${t.x()} ${t.y()} from ${i} / ${texBuf}")
                vertexData.putFloat(t.x())
                vertexData.putFloat(t.y())
            } else {
                vertexData.putFloat(0f)
                vertexData.putFloat(0f)
            }

            // color: if includeVertexColors: actual or white; else: white
            if (includeVertexColors && hasColors) {
                val c = colorBuf!![i]
                vertexData.putFloat(c.r())
                vertexData.putFloat(c.g())
                vertexData.putFloat(c.b())
                vertexData.putFloat(c.a())
            } else {
                vertexData.putFloat(1f)
                vertexData.putFloat(1f)
                vertexData.putFloat(1f)
                vertexData.putFloat(1f)
            }
        }
        vertexData.flip()

        // Indices (uint32). You can compress to 16-bit in your own step if desired.
        val faceBuf = aiMesh.mFaces()
        var indexCount = 0
        for (i in 0 until aiMesh.mNumFaces()) {
            indexCount += faceBuf[i].mNumIndices()
        }

        val indexData = memAllocInt(indexCount)
        for (i in 0 until aiMesh.mNumFaces()) {
            val face = faceBuf[i]
            val idx = face.mIndices()
            for (j in 0 until face.mNumIndices()) {
                indexData.put(idx[j])
            }
        }
        indexData.flip()

        return MeshBuffers(
            vertexData = vertexData, indexData = indexData, vertexCount = vertexCount, indexCount = indexCount
        )
    }

    // ------------------------------------------------------------------------
    // Material extraction (colors + textures)
    // ------------------------------------------------------------------------

    private fun extractMaterial(
        scene: AIScene, aiMesh: AIMesh, baseDir: Path?, config: GltfLoadConfig
    ): MaterialTextured {
        val defaultColor = Vector4f(1f, 1f, 1f, 1f)

        val matIndex = aiMesh.mMaterialIndex()
        if (matIndex < 0 || scene.mNumMaterials() == 0) {
            return MaterialTextured(defaultColor)
        }

        if (scene.mMaterials() == null || matIndex >= scene.mNumMaterials()) {
            return MaterialTextured(defaultColor)
        }

        val aiMat = getMaterial(scene, matIndex) ?: return MaterialTextured(defaultColor)

        val color = AIColor4D.calloc()
        val resultBase = Assimp.aiGetMaterialColor(aiMat, "\$clr.base", 0, 0, color)
        val resultDiffuse = if (resultBase != 0) {
            Assimp.aiGetMaterialColor(aiMat, "\$clr.diffuse", 0, 0, color)
        } else 0

        val baseColor = when {
            resultBase == 0 -> Vector4f(color.r(), color.g(), color.b(), color.a())
            resultDiffuse == 0 -> Vector4f(color.r(), color.g(), color.b(), color.a())
            else -> defaultColor
        }
        color.free()

        if (!config.loadTextures) return MaterialTextured(baseColor)

        val slots = mutableListOf<TextureSlot>()
        val renderer: Renderer = fromContext()

        val types = listOf(
            Assimp.aiTextureType_BASE_COLOR,
            Assimp.aiTextureType_DIFFUSE,
            Assimp.aiTextureType_NORMALS,
            Assimp.aiTextureType_EMISSIVE,
            Assimp.aiTextureType_METALNESS,
            Assimp.aiTextureType_DIFFUSE_ROUGHNESS
        )

        val typeMapping = types.mapIndexed { i, type -> type to i }.toMap()

        types.forEach {
            slots += TextureSlot(
                type = it,
                uvSet = 0,
                texture = LoadedTexture.stubTexture(renderer),
                valid = false
            )
        }

        fun loadSlot(type: Int) {
            val count = Assimp.aiGetMaterialTextureCount(aiMat, type)
            if (count <= 0) return
            MemoryStack.stackPush().use { stack ->
                for (i in 0 until count) {
                    val path = AIString.calloc(stack)
                    val uvIndex = stack.mallocInt(1)
                    val res = Assimp.aiGetMaterialTexture(
                        aiMat, type, i, path, null, uvIndex, null, null, null, null
                    )
                    if (res != Assimp.aiReturn_SUCCESS) {
                        continue
                    }
                    val tex = loadTextureFromPathOrEmbedded(scene, path.dataString(), baseDir, renderer)
                    if (tex != null) {
                        slots[typeMapping[type]!!] = TextureSlot(type = type, uvSet = uvIndex[0], texture = tex, valid = true)
                    } else {
                    }
                }
            }
        }

        types.forEach { loadSlot(it) }

        return MaterialTextured(baseColor, slots)
    }

    private fun loadTextureFromPathOrEmbedded(
        scene: AIScene, path: String, baseDir: Path?, renderer: Renderer
    ): LoadedTexture? {
        if (path.isEmpty()) return null
        // Embedded texture reference: "*0", "*1", ...
        if (path.startsWith("*")) {
            println("Skipping embedded texture $path: embedded textures not yet handled")
            return null
        } else {
            val p = Paths.get(path)
            val resolved = if (p.isAbsolute) p else baseDir?.resolve(p) ?: p
            return try {
                val t = LoadedTexture.fromFile(resolved.toString(), renderer)
                t.transferNow()
                t
            } catch (_: Exception) {
                null
            }
        }
    }

    // ------------------------------------------------------------------------
    // Animation sampling (node-level TRS only)
    // ------------------------------------------------------------------------

    private fun buildAnimationSamplesForNode(
        scene: AIScene, node: AINode, sampleRate: Float
    ): List<TRSSample> {
        val animCount = scene.mNumAnimations()
        if (animCount == 0) return emptyList()

        // For now: just use the first animation.
        val anim = AIAnimation.create(scene.mAnimations()!![0])

        val nodeName = node.mName().dataString()

        // Find the channel that targets this node
        val numChannels = anim.mNumChannels()
        val channels = anim.mChannels()!!

        var target: AINodeAnim? = null
        for (i in 0 until numChannels) {
            val ch = AINodeAnim.create(channels[i])
            if (ch.mNodeName().dataString() == nodeName) {
                target = ch
                break
            }
        }

        val channel = target ?: return emptyList()

        val ticksPerSecond = if (anim.mTicksPerSecond() != 0.0) {
            anim.mTicksPerSecond()
        } else {
            25.0 // Assimp default fallback
        }

        val durationTicks = anim.mDuration()
        if (durationTicks <= 0.0 || ticksPerSecond <= 0.0) return emptyList()

        val durationSec = (durationTicks / ticksPerSecond).toFloat()
        if (durationSec <= 0f) return emptyList()

        val dt = 1f / sampleRate
        val samples = ArrayList<TRSSample>()

        var t = 0f
        while (t <= durationSec + 1e-4f) {
            val tickTime = t * ticksPerSecond

            val pos = sampleVec3(channel.mPositionKeys(), channel.mNumPositionKeys(), tickTime)
            val rot = sampleQuat(channel.mRotationKeys(), channel.mNumRotationKeys(), tickTime)
            val scl = sampleVec3(channel.mScalingKeys(), channel.mNumScalingKeys(), tickTime)

            samples += TRSSample(
                time = t, trs = TRS(pos, rot.normalize(), scl)
            )

            t += dt
        }

        return samples
    }

    private fun sampleVec3(
        keys: AIVectorKey.Buffer?, count: Int, time: Double
    ): Vector3f {
        if (keys == null || count == 0) return Vector3f(0f, 0f, 0f)
        if (count == 1) {
            val v = keys[0].mValue()
            return Vector3f(v.x(), v.y(), v.z())
        }

        var i = 0
        while (i < count - 1 && time >= keys[i + 1].mTime()) {
            i++
        }

        val a = keys[i]
        if (i == count - 1) {
            val v = a.mValue()
            return Vector3f(v.x(), v.y(), v.z())
        }

        val b = keys[i + 1]
        val denom = (b.mTime() - a.mTime()).coerceAtLeast(1e-8)
        val f = ((time - a.mTime()) / denom).coerceIn(0.0, 1.0)

        val av = a.mValue()
        val bv = b.mValue()
        return Vector3f(
            (av.x() + (bv.x() - av.x()) * f).toFloat(),
            (av.y() + (bv.y() - av.y()) * f).toFloat(),
            (av.z() + (bv.z() - av.z()) * f).toFloat()
        )
    }


    private fun sampleQuat(
        keys: AIQuatKey.Buffer?, count: Int, time: Double
    ): Quaternionf {
        if (keys == null || count == 0) return Quaternionf()
        if (count == 1) {
            val q = keys[0].mValue()
            return Quaternionf(q.x(), q.y(), q.z(), q.w())
        }

        var i = 0
        while (i < count - 1 && time >= keys[i + 1].mTime()) {
            i++
        }

        val a = keys[i]
        if (i == count - 1) {
            val q = a.mValue()
            return Quaternionf(q.x(), q.y(), q.z(), q.w())
        }

        val b = keys[i + 1]
        val denom = (b.mTime() - a.mTime()).coerceAtLeast(1e-8)
        val f = ((time - a.mTime()) / denom).coerceIn(0.0, 1.0).toFloat()

        val qa = a.mValue()
        val qb = b.mValue()

        val qaJ = Quaternionf(qa.x(), qa.y(), qa.z(), qa.w())
        val qbJ = Quaternionf(qb.x(), qb.y(), qb.z(), qb.w())

        return qaJ.slerp(qbJ, f)
    }


    // ------------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------------

    private fun aiToMatrix4f(m: AIMatrix4x4): Matrix4f = Matrix4f(
        m.a1(),
        m.b1(),
        m.c1(),
        m.d1(),
        m.a2(),
        m.b2(),
        m.c2(),
        m.d2(),
        m.a3(),
        m.b3(),
        m.c3(),
        m.d3(),
        m.a4(),
        m.b4(),
        m.c4(),
        m.d4()
    )

    private fun decomposeToTRS(m: Matrix4f): TRS {
        val t = Vector3f()
        val r = Quaternionf()
        val s = Vector3f()
        m.getTranslation(t)
        m.getUnnormalizedRotation(r)
        m.getScale(s)
        return TRS(t, r.normalize(), s)
    }

    private fun getMesh(scene: AIScene, index: Int): AIMesh? {
        if (index < 0 || index >= scene.mNumMeshes()) return null
        val meshes = scene.mMeshes() ?: return null
        return AIMesh.create(meshes[index])
    }

    private fun getMaterial(scene: AIScene, index: Int): AIMaterial? {
        if (index < 0 || index >= scene.mNumMaterials()) return null
        val materials = scene.mMaterials() ?: return null
        return AIMaterial.create(materials[index])
    }

    // ------------------------------------------------------------------------
    // Default shaders (GLSL) for SSBO vertex pulling + push constants
    // ------------------------------------------------------------------------

    // Assumes:
    // - Vertex SSBO at set=0,binding=0: float vertexData[]
    // - Index SSBO at set=0,binding=1:  uint indexData[]
    // - Push constants:
    //     mat4 modelView;
    //     mat4 proj;
    //     vec4 materialBaseColor;
    //     uint vertexOffset;   // in floats
    //     uint indexOffset;    // in indices
    //     uint vertexStride;   // in floats (12 for this layout)
    //     uint useVertexColor; // 0 or 1
    //
    // - Vertex layout exactly: 12 floats
    //   0..2: pos, 3..5: normal, 6..7: uv, 8..11: color

    val gltVertexShader = """
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require

layout(std430, buffer_reference) readonly buffer VertexBuffer {
    float vertexData[];
};

layout(std430, buffer_reference) readonly buffer IndexBuffer {
    uint indexData[];
};

layout (binding = 0) uniform VertexData {
    mat4 modelView;        // view * model
    mat4 proj;             // projection

    vec4 materialBaseColor;

    uint vertexOffset;     // in floats
    uint indexOffset;      // in indices
    uint vertexStride;     // in floats
    uint useVertexColor;   // bool as uint
} data;


layout(push_constant) uniform PushConstants {
    VertexBuffer vertexBuffer;
    IndexBuffer indexBuffer;
} pc;

layout(location = 0) out vec3 vViewPos;
layout(location = 1) out vec3 vNormalVS;
layout(location = 2) out vec2 vUV;
layout(location = 3) out vec4 vColor;

void main()
{
    uint idx = pc.indexBuffer.indexData[data.indexOffset+uint(gl_VertexIndex)];
    uint base = data.vertexOffset + idx * data.vertexStride;

    vec3 pos = vec3(
        pc.vertexBuffer.vertexData[base + 0],
        pc.vertexBuffer.vertexData[base + 1],
        pc.vertexBuffer.vertexData[base + 2]
    );

    vec3 normal = vec3(
        pc.vertexBuffer.vertexData[base + 3],
        pc.vertexBuffer.vertexData[base + 4],
        pc.vertexBuffer.vertexData[base + 5]
    );

    vec2 uv = vec2(
        pc.vertexBuffer.vertexData[base + 6],
        pc.vertexBuffer.vertexData[base + 7]
    );

    vec4 color = vec4(
        pc.vertexBuffer.vertexData[base + 8],
        pc.vertexBuffer.vertexData[base + 9],
        pc.vertexBuffer.vertexData[base + 10],
        pc.vertexBuffer.vertexData[base + 11]
    );

    if (data.useVertexColor == 0u) {
        color = vec4(1.0);
    }

    vec4 viewPos =  data.proj * data.modelView*vec4(pos, 1.0);

    mat3 mv3 = mat3(data.modelView);
    vec3 normalVS = normalize(mv3 * normal);

    vViewPos = viewPos.xyz;
    vNormalVS = normalVS;
    vUV = uv;
    vColor = color * data.materialBaseColor;

    gl_Position = viewPos;
}
""".trimIndent()

    val gltfFragmentShader = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 1) in vec3 vNormalVS;

void main()
{
    float d = abs(dot(vNormalVS.xyz, normalize(vec3(1, 0.4, -0.2))));
    uFragColor = vec4(vNormalVS.rgb*d,1);
}
""".trimIndent()

    // Simple textured fragment: samples first available texture; falls back to vertex color if none bound.
    // Expects descriptor array of combined image samplers at set=0, binding=0, and a push constant for count.
    val gltfFragmentShaderTextured = """
#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 2) in vec2 vUV;

layout(set = 0, binding = 1) uniform sampler2D uTextures[6];

void main()
{
    uFragColor = vColor*texture(uTextures[0], vUV);
}
""".trimIndent()
}


fun List<TRSSample>.sample(time: Float): TRS? {
    if (isEmpty()) return null
    if (size == 1) return this[0].trs
    if (time <= this[0].time) return this[0].trs
    if (time >= last().time) return last().trs

    // Find this to interpolate between
    var i = 0
    while (i < size - 1 && time > this[i + 1].time) {
        i++
    }

    val a = this[i]
    val b = this[i + 1]
    val t = ((time - a.time) / (b.time - a.time)).coerceIn(0f, 1f)

    return TRS(
        Vector3f(a.trs.t).lerp(b.trs.t, t),
        Quaternionf(a.trs.r).slerp(b.trs.r, t),
        Vector3f(a.trs.s).lerp(b.trs.s, t)
    )
}
