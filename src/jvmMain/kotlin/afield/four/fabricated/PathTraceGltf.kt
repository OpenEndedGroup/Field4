package afield.four.fabricated

import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.assimp.*
import org.lwjgl.assimp.Assimp.*
import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.*
import java.nio.file.Path
import kotlin.math.pow
import kotlinx.serialization.json.*

/**
 * glTF and GLB into a [PathTraceScene]: hierarchy baked to world space, PBR parameters mapped onto
 * [PathTraceMaterial], and an extension check that refuses rather than renders the wrong thing.
 *
 * The tracer has no scene graph — it holds world-space triangles — so this importer flattens the
 * glTF node hierarchy as it reads, composing each node's transform with the caller's. Textures are
 * copied out and every Assimp resource is released before the call returns, which is why no
 * renderer or device is involved and an import is testable from a plain `main`.
 *
 * Two behaviours are easier to find here than to rediscover from a wrong picture. The first is that
 * the extension set is read from the JSON directly, ahead of Assimp: an extension in
 * `extensionsRequired` that this importer does not implement fails the call, while one in
 * `extensionsUsed` produces a warning and an import of the base material. Declining to load is the
 * right answer for the former because a required extension means the asset is wrong without it.
 * The second is that the import is all-or-nothing — geometry accumulates into a staging scene and
 * is appended only on success, so a failure leaves the target scene exactly as it was rather than
 * half-populated.
 *
 * Colour space is the detail that most often looks like a renderer bug. Base colour and emission
 * textures are sRGB and are decoded; metallic-roughness and normal maps are linear data and are
 * not. Vertex colours and the base colour factor multiply after decoding, alpha is never squared,
 * and `PathTraceGltfGpuSmoke` pins all of it against known byte values through real GPU traversal.
 */

/**
 * Which pose a **skinned** mesh is traced in.
 *
 * The tracer holds world-space triangles and has no scene graph, so a skinned mesh has to be
 * deformed before it can be traced at all. Until 2026-09-19 `importGltf` refused one outright
 * (`"Skinned glTF meshes need skin deformation before path tracing"`); it now runs
 * [afield.four.modules.GltfSkinning] and traces the deformed surface. This says at what time.
 *
 * `null` — the default — is the rest pose, which for a file whose first keys are its bind
 * transforms is the bind pose and is identical to what the old refusal was protecting you from
 * getting silently.
 *
 * @param clip the animation's name, or `null` for the rest pose. An unknown name fails the import
 *   rather than quietly tracing the rest pose.
 * @param seconds the time within the clip, clamped at both ends.
 */
data class GltfSkinPose(val clip: String? = null, val seconds: Float = 0f)

/** Details of one static import. Material IDs also select materials in shadeMaterial(). */
data class PathTraceGltfImport(val triangleCount: Int, val materialIds: Map<String, Int>,
    val boundsMin: Vector3f, val boundsMax: Vector3f, val warnings: List<String>)

/**
 * Append a static glTF/GLB scene, baking hierarchy and [transform] into world-space geometry.
 * Copies textures and releases Assimp resources before returning; no renderer is required.
 * [material] can replace or amend imported PBR parameters, for example to choose solid glass.
 * An import failure leaves this scene unchanged.
 */
fun PathTraceScene.importGltf(path: String, transform: Matrix4f = Matrix4f(),
    material: (name: String, value: PathTraceMaterial) -> PathTraceMaterial = { _, value -> value },
    warning: (String) -> Unit = { println("glTF: $it") },
    pose: GltfSkinPose? = null): PathTraceGltfImport {
    require(Path.of(path).fileName.toString().substringAfterLast('.').lowercase() in setOf("gltf", "glb")) {
        "Expected a glTF 2.0 .gltf or .glb file"
    }
    val messages = linkedSetOf<String>()
    val json = if (path.endsWith(".glb",ignoreCase=true)) java.nio.file.Files.newInputStream(Path.of(path)).use { input ->
        val header=java.nio.ByteBuffer.wrap(input.readNBytes(20)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(header.remaining()==20 && header.getInt(0)==0x46546c67 && header.getInt(4)==2 && header.getInt(16)==0x4e4f534a) { "Invalid glTF 2.0 GLB header" }
        val length=header.getInt(12);require(length in 2..64_000_000) { "Invalid or oversized GLB JSON chunk" }
        Json.parseToJsonElement(String(input.readNBytes(length),Charsets.UTF_8)).jsonObject
    } else Json.parseToJsonElement(java.nio.file.Files.readString(Path.of(path))).jsonObject
    require(json["asset"]?.jsonObject?.get("version")?.jsonPrimitive?.content=="2.0") { "Expected glTF 2.0" }
    val supported=setOf("KHR_texture_transform","KHR_materials_ior","KHR_materials_emissive_strength",
        "KHR_materials_transmission","KHR_materials_volume","KHR_mesh_quantization")
    val required=json["extensionsRequired"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    require(required.all { it in supported }) { "Unsupported required glTF extensions: ${required.filter { it !in supported }}" }
    json["extensionsUsed"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it !in supported }?.forEach {
        messages += "Optional $it is not evaluated; importing the base material/geometry."
    }
    val staging = PathTraceScene()
    val imported = GltfSupport.loadModel(path)
    val names = linkedMapOf<String,Int>()
    val lo = Vector3f(Float.POSITIVE_INFINITY); val hi = Vector3f(Float.NEGATIVE_INFINITY)
    try {
        require(imported.mFlags() and AI_SCENE_FLAGS_INCOMPLETE == 0) { "Incomplete glTF scene" }
        val root = requireNotNull(imported.mRootNode()) { "glTF has no scene root" }
        // Node-level (rigid) animation is still not evaluated. Skin deformation IS, per-mesh, below.
        if (imported.mNumAnimations()>0) messages += "Node animation is not evaluated; importing the default node transforms."
        val textureCache = mutableMapOf<List<Any>,Int>()
        data class Slot(val texture: Int = -1, val uv: Int = 0,
            val ox: Float = 0f, val oy: Float = 0f, val sx: Float = 1f, val sy: Float = 1f, val rotation: Float = 0f)
        val materialSlots = mutableListOf<List<Slot>>()
        fun floatProperty(m: AIMaterial, key: String, default: Float, type: Int = 0): Float {
            val values=floatArrayOf(default); val count=intArrayOf(1)
            return if (aiGetMaterialFloatArray(m,key,type,0,values,count)==aiReturn_SUCCESS) values[0] else default
        }
        fun intProperty(m: AIMaterial, key: String, default: Int, type: Int = 0): Int {
            val values=intArrayOf(default); val count=intArrayOf(1)
            return if (aiGetMaterialIntegerArray(m,key,type,0,values,count)==aiReturn_SUCCESS && count[0]>0) values[0] else default
        }
        fun stringProperty(m: AIMaterial, key: String, default: String): String = MemoryStack.stackPush().use { stack ->
            val value=AIString.calloc(stack)
            if (aiGetMaterialString(m,key,0,0,value)==aiReturn_SUCCESS) value.dataString() else default
        }
        fun colorProperty(m: AIMaterial, key: String, default: Vector4f): Vector4f = MemoryStack.stackPush().use { stack ->
            val c=AIColor4D.calloc(stack)
            if (aiGetMaterialColor(m,key,0,0,c)==aiReturn_SUCCESS) Vector4f(c.r(),c.g(),c.b(),c.a()) else default
        }
        fun slot(m: AIMaterial, type: Int, srgb: Boolean, info: JsonObject?): Slot = MemoryStack.stackPush().use { stack ->
            if (aiGetMaterialTextureCount(m,type)==0) return@use Slot()
            val ref=AIString.calloc(stack); val uv=stack.callocInt(1); val wrap=stack.callocInt(3)
            check(aiGetMaterialTexture(m,type,0,ref,null,uv,null,null,wrap,null)==aiReturn_SUCCESS)
            val uri=ref.dataString()
            fun wrapMode(v: Int)=when(v) { aiTextureMapMode_Clamp -> 1; aiTextureMapMode_Mirror -> 2; else -> 0 }
            val u=wrapMode(wrap[0]); val v=wrapMode(wrap[1])
            val nearest=intProperty(m,_AI_MATKEY_GLTF_MAPPINGFILTER_MAG_BASE,9729,type)==9728
            val key=listOf(uri,srgb,u,v,nearest)
            val id=textureCache.getOrPut(key) {
                staging.texture(readGltfTexture(imported,Path.of(path).toAbsolutePath().parent,uri,srgb,u,v,nearest))
            }
            val tx=info?.get("extensions")?.jsonObject?.get("KHR_texture_transform")?.jsonObject
            fun component(key: String, index: Int, default: Float)=tx?.get(key)?.jsonArray?.get(index)?.jsonPrimitive?.float ?: default
            Slot(id,tx?.get("texCoord")?.jsonPrimitive?.int ?: info?.get("texCoord")?.jsonPrimitive?.int ?: uv[0],
                component("offset",0,0f),component("offset",1,0f),component("scale",0,1f),component("scale",1,1f),
                tx?.get("rotation")?.jsonPrimitive?.float ?: 0f)
        }
        for (i in 0 until imported.mNumMaterials()) {
            val m=AIMaterial.create(imported.mMaterials()!![i])
            val name=stringProperty(m,AI_MATKEY_NAME,"material-$i").ifBlank { "material-$i" }
            val base=colorProperty(m,AI_MATKEY_BASE_COLOR,Vector4f(1f))
            val emission=colorProperty(m,AI_MATKEY_COLOR_EMISSIVE,Vector4f(0f))
            val strength=floatProperty(m,AI_MATKEY_EMISSIVE_INTENSITY,1f)
            // Assimp preserves glTF material order and appends a default material. Read UV
            // transforms in glTF space: its pivot/order differs from Assimp's UV-transform pass.
            val source=json["materials"]?.jsonArray?.getOrNull(i)?.jsonObject
            val pbr=source?.get("pbrMetallicRoughness")?.jsonObject
            val slots=listOf(slot(m,aiTextureType_BASE_COLOR,true,pbr?.get("baseColorTexture")?.jsonObject),
                slot(m,aiTextureType_METALNESS,false,pbr?.get("metallicRoughnessTexture")?.jsonObject),
                slot(m,aiTextureType_EMISSIVE,true,source?.get("emissiveTexture")?.jsonObject),
                slot(m,aiTextureType_NORMALS,false,source?.get("normalTexture")?.jsonObject))
            val transmission=floatProperty(m,AI_MATKEY_TRANSMISSION_FACTOR,0f)
            val thickness=floatProperty(m,AI_MATKEY_VOLUME_THICKNESS_FACTOR,0f)
            val roughness=floatProperty(m,AI_MATKEY_ROUGHNESS_FACTOR,1f)
            if (transmission>0f && transmission<1f) messages += "$name: partial transmission is approximated as full dielectric transmission."
            if (floatProperty(m,AI_MATKEY_CLEARCOAT_FACTOR,0f)>0f) messages += "$name: clearcoat is not evaluated."
            if (aiGetMaterialTextureCount(m,aiTextureType_TRANSMISSION)>0) messages += "$name: transmission texture is not evaluated."
            val thin=transmission>0f && thickness==0f && roughness<=0.001f
            if (transmission>0f && thickness==0f && !thin) messages += "$name: rough transmission uses solid glass; provide closed geometry."
            val attenuation=colorProperty(m,AI_MATKEY_VOLUME_ATTENUATION_COLOR,Vector4f(1f))
            val distance=floatProperty(m,AI_MATKEY_VOLUME_ATTENUATION_DISTANCE,Float.POSITIVE_INFINITY)
            val transparent=transmission>0f
            val value=PathTraceMaterial(
                red=base.x,green=base.y,blue=base.z,
                metallic=if (transparent) 0f else floatProperty(m,AI_MATKEY_METALLIC_FACTOR,1f),
                roughness=if (thin) 0f else roughness,
                emissionRed=emission.x*strength,emissionGreen=emission.y*strength,emissionBlue=emission.z*strength,
                transmission=transparent,ior=floatProperty(m,AI_MATKEY_REFRACTI,1.5f),thinWalled=thin,
                attenuationRed=if (thin) base.x else if (distance.isFinite()) attenuation.x.coerceAtLeast(1e-6f) else 1f,
                attenuationGreen=if (thin) base.y else if (distance.isFinite()) attenuation.y.coerceAtLeast(1e-6f) else 1f,
                attenuationBlue=if (thin) base.z else if (distance.isFinite()) attenuation.z.coerceAtLeast(1e-6f) else 1f,
                attenuationDistance=if (distance.isFinite() && distance>0f) distance else 1f,
                baseColorTexture=slots[0].texture,metallicRoughnessTexture=slots[1].texture,
                emissionTexture=slots[2].texture,normalTexture=slots[3].texture,
                // Assimp also writes baseColor.a into OPACITY: multiplying them would square alpha.
                opacity=base.w,alphaMode=PathTraceMaterial.AlphaMode.valueOf(stringProperty(m,AI_MATKEY_GLTF_ALPHAMODE,"OPAQUE")),
                alphaCutoff=floatProperty(m,AI_MATKEY_GLTF_ALPHACUTOFF,0.5f),
                doubleSided=intProperty(m,AI_MATKEY_TWOSIDED,0)!=0,
                normalScale=floatProperty(m,_AI_MATKEY_GLTF_SCALE_BASE,1f,aiTextureType_NORMALS)
            )
            val id=staging.material(material(name,value));materialSlots+=slots
            names[if (name in names) "$name#$i" else name]=materials.size+id
        }
        // The skeleton is scene-wide, so it is read once and only when some mesh actually has bones.
        val skeletonNodes by lazy { GltfSkinning.readNodes(imported) }
        val skeletonClips by lazy { GltfSkinning.readClips(imported,skeletonNodes,60f) }
        fun deform(mesh: AIMesh): GltfSkinning.SkinnedVertices {
            val skin=requireNotNull(GltfSkinning.readMeshSkin(mesh,skeletonNodes,skeletonClips)) {
                "Skinned glTF mesh '${mesh.mName().dataString()}' has bones Assimp would not hand over"
            }
            val n=mesh.mNumVertices()
            val p=FloatArray(n*3); val src=mesh.mVertices()
            for (j in 0 until n) { val v=src[j];p[j*3]=v.x();p[j*3+1]=v.y();p[j*3+2]=v.z() }
            val ns=mesh.mNormals()?.let { normals -> FloatArray(n*3).also {
                for (j in 0 until n) { val v=normals[j];it[j*3]=v.x();it[j*3+1]=v.y();it[j*3+2]=v.z() } } }
            val clip=pose?.clip?.let { name -> requireNotNull(skin.skeleton.clip(name)) {
                "glTF has no animation named '$name'; it has ${skin.skeleton.clips.map { c -> c.name }}" } }
            return GltfSkinning.skin(p,ns,skin.influences,skin.skeleton.palette(clip,pose?.seconds ?: 0f))
        }
        fun walk(node: AINode, parent: Matrix4f) {
            val m=node.mTransformation()
            val local=Matrix4f(m.a1(),m.b1(),m.c1(),m.d1(),m.a2(),m.b2(),m.c2(),m.d2(),
                m.a3(),m.b3(),m.c3(),m.d3(),m.a4(),m.b4(),m.c4(),m.d4())
            val world=Matrix4f(parent).mul(local)
            val determinant=world.determinant()
            require(determinant.isFinite() && kotlin.math.abs(determinant)>1e-20f) { "Singular glTF node transform: ${node.mName().dataString()}" }
            val normalMatrix=Matrix4f(world).invert().transpose()
            for (k in 0 until node.mNumMeshes()) {
                val mesh=AIMesh.create(imported.mMeshes()!![node.mMeshes()!![k]])
                require(mesh.mNumAnimMeshes()==0) { "Morph-target glTF meshes are not supported yet" }
                // Skinned meshes are traced DEFORMED at `pose`, not silently traced at bind. Linear
                // blend skinning puts them in scene space already -- glTF 2.0 3.7.3.2 ignores a
                // skinned mesh node's own transform -- so the node's world matrix must NOT be applied
                // on top; only the caller's root `transform` is.
                val skinned=if (mesh.mNumBones()==0) null else deform(mesh)
                if (skinned!=null) messages += "Skinned mesh '${mesh.mName().dataString()}' is traced " +
                    "deformed at ${pose?.let { "'${it.clip ?: "rest"}' t=${it.seconds}s" } ?: "the rest pose"}."
                val place=if (skinned!=null) transform else world
                val placeDeterminant=if (skinned!=null) transform.determinant() else determinant
                require(placeDeterminant.isFinite() && kotlin.math.abs(placeDeterminant)>1e-20f) { "Singular glTF import transform" }
                val placeNormal=if (skinned!=null) Matrix4f(transform).invert().transpose() else normalMatrix
                val mat=mesh.mMaterialIndex();val slots=materialSlots[mat]
                val positions=if (skinned!=null)
                    List(mesh.mNumVertices()) { j -> place.transformPosition(Vector3f(skinned.position[j*3],skinned.position[j*3+1],skinned.position[j*3+2])) }
                else List(mesh.mNumVertices()) { j -> mesh.mVertices()[j].let { world.transformPosition(Vector3f(it.x(),it.y(),it.z())) } }
                val normals=if (skinned?.normal!=null)
                    List(mesh.mNumVertices()) { j -> placeNormal.transformDirection(Vector3f(skinned.normal[j*3],skinned.normal[j*3+1],skinned.normal[j*3+2])).normalize() }
                else mesh.mNormals()?.let { ns -> List(mesh.mNumVertices()) { j -> ns[j].let { normalMatrix.transformDirection(Vector3f(it.x(),it.y(),it.z())).normalize() } } }
                val coords=List(mesh.mNumVertices()) { j -> FloatArray(8).also { out ->
                    slots.forEachIndexed { t,s -> if (s.texture>=0) {
                        require(s.uv in 0..7) { "Unsupported UV channel ${s.uv}" }
                        val uv=requireNotNull(mesh.mTextureCoords(s.uv)) { "Material texture requires missing TEXCOORD_${s.uv}" }[j]
                        // Assimp uses bottom-left UVs; decoded PNG/JPEG rows and glTF use top-left.
                        val x=uv.x()*s.sx; val y=(1f-uv.y())*s.sy
                        val c=kotlin.math.cos(s.rotation); val sn=kotlin.math.sin(s.rotation)
                        out[t*2]=s.ox+c*x-sn*y;out[t*2+1]=s.oy+sn*x+c*y
                    } }
                } }
                val colors=mesh.mColors(0)?.let { cs -> List(mesh.mNumVertices()) { j -> cs[j].let { Vector4f(it.r(),it.g(),it.b(),it.a()) } } }
                for (f in 0 until mesh.mNumFaces()) {
                    val face=mesh.mFaces()[f]
                    if (face.mNumIndices()!=3) { messages += "Point and line primitives are omitted.";continue }
                    val ids=IntArray(3) { face.mIndices()[it] }
                    if (placeDeterminant<0f) { val temp=ids[1];ids[1]=ids[2];ids[2]=temp }
                    val p=ids.map { positions[it] }
                    val area=Vector3f(p[1]).sub(p[0]).cross(Vector3f(p[2]).sub(p[0])).lengthSquared()
                    if (area<=1e-16f) { messages += "Degenerate triangles are omitted.";continue }
                    staging.triangle(p[0],p[1],p[2],mat,normals?.get(ids[0]),normals?.get(ids[1]),normals?.get(ids[2]))
                    val last=staging.triangles.lastIndex
                    staging.triangles[last]=staging.triangles[last].copy(uv=ids.map { coords[it] },color=colors?.let { c -> ids.map { c[it] } })
                    p.forEach { lo.min(it);hi.max(it) }
                }
            }
            for (i in 0 until node.mNumChildren()) walk(AINode.create(node.mChildren()!![i]),world)
        }
        walk(root,transform)
        require(staging.triangles.isNotEmpty()) { "glTF scene contains no usable triangles" }
    } finally { aiReleaseImport(imported) }
    staging.materials.forEach { m ->
        require(listOf(m.baseColorTexture,m.metallicRoughnessTexture,m.emissionTexture,m.normalTexture).all { it == -1 || it in staging.textureData.indices }) { "Material override references an unknown imported texture" }
    }
    messages.forEach(warning)
    // Commit only after decoding and validation succeeds; remap textures/materials when appending.
    val textureBase=textureData.size; val materialBase=materials.size
    staging.materials.forEach { m ->
        fun remap(id: Int)=if (id<0) -1 else id+textureBase
        materials += m.copy(baseColorTexture=remap(m.baseColorTexture),metallicRoughnessTexture=remap(m.metallicRoughnessTexture),
            emissionTexture=remap(m.emissionTexture),normalTexture=remap(m.normalTexture))
    }
    textureData+=staging.textureData
    triangles+=staging.triangles.map { it.copy(material=it.material+materialBase) }
    return PathTraceGltfImport(staging.triangles.size,names,lo,hi,messages.toList())
}

private fun readGltfTexture(scene: AIScene, folder: Path, uri: String, srgb: Boolean,
    wrapU: Int, wrapV: Int, nearest: Boolean): PathTraceTexture = MemoryStack.stackPush().use { stack ->
    fun decode(bytes: java.nio.ByteBuffer): PathTraceTexture {
        val w=stack.mallocInt(1);val h=stack.mallocInt(1);val channels=stack.mallocInt(1)
        stbi_set_flip_vertically_on_load_thread(0)
        val pixels=stbi_load_from_memory(bytes,w,h,channels,4) ?: error("Cannot decode glTF texture $uri: ${stbi_failure_reason()}")
        try {
            val rgba=FloatArray(w[0]*h[0]*4) { i ->
                val c=(pixels[i].toInt() and 255)/255f
                if (srgb && i%4!=3) { if(c<=0.04045f) c/12.92f else ((c+0.055f)/1.055f).pow(2.4f) } else c
            }
            return PathTraceTexture(w[0],h[0],rgba,wrapU,wrapV,nearest)
        } finally { stbi_image_free(pixels) }
    }
    if (uri.startsWith("*")) {
        val index=uri.drop(1).toInt();require(index in 0 until scene.mNumTextures())
        val texture=AITexture.create(scene.mTextures()!![index])
        if (texture.mHeight()==0) return@use decode(memByteBuffer(texture.pcData().address(),texture.mWidth()))
        val data=texture.pcData()
        val rgba=FloatArray(texture.mWidth()*texture.mHeight()*4) { i ->
            val texel=data[i/4]
            val c=((when(i%4) { 0->texel.r();1->texel.g();2->texel.b();else->texel.a() }).toInt() and 255)/255f
            if(srgb && i%4!=3) { if(c<=0.04045f)c/12.92f else ((c+0.055f)/1.055f).pow(2.4f) } else c
        }
        PathTraceTexture(texture.mWidth(),texture.mHeight(),rgba,wrapU,wrapV,nearest)
    } else {
        val decoded=java.net.URLDecoder.decode(uri.replace("+","%2B"),Charsets.UTF_8)
        val bytes=java.nio.file.Files.readAllBytes(folder.resolve(decoded))
        val direct=memAlloc(bytes.size)
        try { direct.put(bytes).flip();decode(direct) } finally { memFree(direct) }
    }
}
