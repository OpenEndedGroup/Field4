// The claim a runtime blend makes is not that it produces a plausible picture in the
// middle. It is that at 0 it *is* the left keyframe and at 1 it *is* the right one. That
// is checkable, and this box checks it: one program, five draws, and the two ends
// compared pixel for pixel against the programs they are supposed to be.
import afield.stacksl.cpu.GlslValue
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.keyframeBlendVector
import afield.stdlib.planStackSLCodeBlend

// The convention, in a shader: a vec4 called keyframeBlend in the push-constant block.
// Nothing declares it for you — a `stack.auto` slot cannot declare a uniform of its own,
// which is why the parameter arrives by agreement rather than by machinery.
val template = """
    #version 450
    layout(location = 0) in vec2 fragUV;
    layout(location = 0) out vec4 outColor;

    layout(push_constant) uniform Controls {
        vec4 keyframeBlend;
    } pc;

    {{ stack.auto.program }}

    void main() {
        vec4 stackInput = vec4(fragUV, 0.0, 0.0);
        outColor = vec4(clamp(program(stackInput).rgb, 0.0, 1.0), 1.0);
    }
""".trimIndent()

val warm = "uv 3.0 * fbm 0.15 + [1.0,0.55,0.2] *"
val cool = "uv 8.0 * fbm 0.45 + [0.2,0.5,1.0] *"
val blended = planStackSLCodeBlend(warm, cool, CodeBlendOptions(mode = CodeBlendMode.RUNTIME)).runtimeSource

val dim = 56
// Expanded once. A runtime program does not depend on t, so this is the only compile.
val expanded = StackSLCpu.expandStackAuto(template, blended)

val strip = StringBuilder("<div style='display:flex;gap:8px;align-items:flex-start'>")
val rasters = mutableMapOf<Double, FloatArray>()
for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
    val v = keyframeBlendVector(t)
    val raster = StackSLCpu.renderFragment(
        expanded, dim, dim,
        uniforms = mapOf("pc.keyframeBlend" to GlslValue.vec4(v.x, v.y, v.z, v.w))
    )
    rasters[t] = raster.rgba
    val b64 = java.util.Base64.getEncoder().encodeToString(raster.toPngBytes())
    strip.append("<div style='font:10px ui-monospace,monospace;color:#999;text-align:center'>")
    strip.append("<img src='data:image/png;base64,")
    strip.append(b64)
    strip.append("' width='")
    strip.append(dim * 2)
    strip.append("' height='")
    strip.append(dim * 2)
    strip.append("' style='image-rendering:pixelated;border-radius:4px;display:block'/>")
    strip.append("blend ")
    strip.append(t)
    strip.append("</div>")
}
strip.append("</div>")
display("blend-strip", strip.toString())

// The endpoints, drawn from the endpoint programs themselves.
val ends = listOf(0.0 to warm, 1.0 to cool).map { (t, program) ->
    val v = keyframeBlendVector(t)
    val raster = StackSLCpu.renderFragment(
        StackSLCpu.expandStackAuto(template, program), dim, dim,
        uniforms = mapOf("pc.keyframeBlend" to GlslValue.vec4(v.x, v.y, v.z, v.w))
    )
    t to raster.rgba
}
ends.forEach { (t, want) ->
    val got = rasters.getValue(t)
    var worst = 0.0f
    for (i in want.indices) worst = maxOf(worst, kotlin.math.abs(want[i] - got[i]))
    println("at blend %.1f the one program differs from the keyframe it should be by %.4f".format(t, worst))
}
println()
println("one program, five draws, only the push constant changing:")
println(blended)
