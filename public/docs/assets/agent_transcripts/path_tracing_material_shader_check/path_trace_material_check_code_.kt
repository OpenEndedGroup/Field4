import afield.four.fabricated.*
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.reloadComputeShader
import kotlinx.coroutines.delay

val renderer: Renderer = fromContext()
val scene = PathTraceScene().apply {
    val m = material(PathTraceMaterial.emissive(0f,0f,0f))
    quad(Vector3f(-20f,-20f,0f),Vector3f(20f,-20f,0f),Vector3f(20f,20f,0f),Vector3f(-20f,20f,0f),m)
}
val camera = Camera(renderer).also { it.at.set(0f,0f,3f); it.target.set(0f,0f,0f) }
prop<PathTracingRenderer>(tracer)
tracer = pathTracingHere(scene, PathTraceEnvironment.constant(0f), width=32, height=24, camera=camera, name="editable-study")
tracer.materialMesh.gain = { 2f }
tracer.maxBounces = 1
tracer.blitToScreen()
fun afield.Box.ownerForTest() = this
val owner = ownerForTest()
val verify: suspend () -> Unit = {
    PathTraceGpuSmoke.awaitSamples(tracer, 8)
    tracer.paused = true
    check(kotlin.math.abs(tracer.readLinear()[0]-2f)<1e-5) { "Initial box material hook was not evaluated" }
    val original = tracer.materialModule.current
    owner.setPropertyRaw("pathTracePaint", "invalid GLSL")
    check(!reloadComputeShader(owner))
    check(tracer.materialModule.current === original) { "Failed reload replaced the working pipeline" }
    owner.setPropertyRaw("pathTracePaint", """
        layout(push_constant) uniform PaintPC { float gain; float bias; } pc;
        Material shadeMaterial(Material material, PathTraceSurface surface) {
            material.base.rgb = vec3(0);
            material.emission.rgb = vec3(pc.gain + pc.bias, 0, 0);
            return material;
        }
    """.trimIndent())
    check(reloadComputeShader(owner))
    check(tracer.materialModule.current !== original)
    check(tracer.materialModule.current.ci.pushConstants.single().members.map { it.name } == listOf("gain", "bias"))
    tracer.materialMesh.push["bias"] = 1f
    tracer.paused = false
    delay(100)
    PathTraceGpuSmoke.awaitSamples(tracer, 4)
    tracer.paused = true
    check(kotlin.math.abs(tracer.readLinear()[0]-3f)<1e-5) { "Reload retained old accumulation or failed to preserve push values" }
    println("PASS EDITABLE MATERIAL BOX: seeded templates, includes, reflected push constants, changed ABI, last-valid reload, accumulation reset")
    tracer.close()
}
start(verify)
