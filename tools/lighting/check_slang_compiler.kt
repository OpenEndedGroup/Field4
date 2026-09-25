import afield.four.modules.SlangCompiler
import afield.four.modules.ShaderKind
check(System.getenv("AFIELD_SLANGC").isNullOrBlank())
check(System.getProperty("afield.slangc").isNullOrBlank())
val source="""
struct Varying { float4 position : SV_Position; };
[shader("vertex")] Varying vsMain(uint id : SV_VertexID) {
    Varying v; v.position=float4(float2((id<<1)&2,id&2)*2-1,0,1); return v;
}
[shader("fragment")] float4 fsMain(Varying v) : SV_Target0 { return float4(.2,.4,.8,1); }
"""
val stages=SlangCompiler.compileVertexFragment(source)
check(stages.first.entryPoint.name=="vsMain" && stages.second.entryPoint.name=="fsMain")
stages.first.spirv.free();stages.second.spirv.free()
val compute=SlangCompiler.compileToGlsl("[shader(\"compute\")] [numthreads(1,1,1)] void main(uint3 id:SV_DispatchThreadID) {}",ShaderKind.COMPUTE_SHADER,"check.slang")
check(compute.contains("local_size_x"))
System.setProperty("afield.slangc","/field-intentionally-missing/slangc")
try { check(runCatching { SlangCompiler.compileVertexFragment(source) }.isFailure) }
finally { System.clearProperty("afield.slangc") }
println("EXISTING SLANG COMPILER CACHE FALLBACK CHECK DONE")
