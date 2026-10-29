package afield.graphics.render.lighting

import afield.graphics.shader.Introspection
import afield.graphics.shader.ShaderKind
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Loads the optional RTXDI shaders, and refuses a binary whose interface disagrees with the host
 * code written against it.
 *
 * Field does not ship NVIDIA's sampling SDK and loads nothing from it at startup. Running
 * `tools/lighting/build_rtxdi.py --prepare` clones the SDK and fetches a shader compiler into
 * ignored build storage, and the build leaves a manifest under
 * `build/lighting-experiment/rtxdi-shaders/`. `load` reads that manifest; with no manifest there it
 * fails with the command to run.
 *
 * What `load` then checks is the interface rather than the file. The manifest has to record the SDK
 * commit this source was written against, the SPIR-V has to hash to what the manifest recorded, and
 * the shader's own reflection has to still carry the workgroup size, the push-constant block's size
 * and member offsets, the reservoir and offset array strides, and the surface and light struct
 * layouts that the host side writes into. Shaders rebuilt from a different SDK revision therefore
 * fail to load, instead of being handed the host's bytes at offsets that have moved.
 *
 * A loaded program is passed to [DirectLighting] through `enableRtxdi`, which builds the pass and
 * leaves the current one in place if that fails.
 */

/** Optional compiled SDK shaders. Build with tools/lighting/build_rtxdi.py; no SDK is loaded at startup. */
class RtxdiProgram private constructor(val manifest: JSONObject, internal val reflection: Introspection) {
    companion object {
        const val SDK_COMMIT = "f12037fa8e97ebc08e9e3edfd2de528ed1772a4b"
        fun load(manifestFile: File = File("build/lighting-experiment/rtxdi-shaders/current.json")): RtxdiProgram {
            require(manifestFile.isFile) { "Build the optional RTXDI shaders first: uv run python tools/lighting/build_rtxdi.py --prepare" }
            val manifest=JSONObject(manifestFile.readText())
            require(manifest.getInt("schema")==1 && manifest.getString("sdkCommit")==SDK_COMMIT)
            val binary=File(manifest.getString("spirv")).readBytes()
            val hash=MessageDigest.getInstance("SHA-256").digest(binary).joinToString("") { "%02x".format(it) }
            require(hash==manifest.getString("spirvSha256")) { "RTXDI SPIR-V checksum mismatch" }
            val info=Introspection.fromSpirv("Field RTXDI bridge",ShaderKind.COMPUTE_SHADER,binary)
            require(info.valid && info.fixedWorkgroupSize==listOf(8,8,1))
            require(info.pushConstants.single().size==112) { "RTXDI host/shader push ABI changed" }
            require(info.pushConstants.single().members.map { it.offset } ==
                listOf(0,8,16,24)+(32..108 step 4).toList()) { "RTXDI push member offsets changed" }
            require(info.ssbo.single { it.name=="reservoirs" }.members.single().arrayStride==24)
            require(info.ssbo.single { it.name=="offsets" }.members.single().arrayStride==8)
            for(name in listOf("SurfaceData","RAB_LightInfo")) {
                val type=info.structureTypes.single { it.name==name }
                require(type.size==80 && type.members.map { it.offset }==listOf(0,16,32,48,64)) { "RTXDI $name ABI changed" }
            }
            return RtxdiProgram(manifest,info)
        }
    }
}
