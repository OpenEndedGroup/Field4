package afield.four.lighting

import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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
