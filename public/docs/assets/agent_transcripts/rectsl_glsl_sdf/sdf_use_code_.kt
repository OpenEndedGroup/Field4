// A distance is worth more than a coverage mask. Everything below comes
// from the single float that `scene` returns, and none of it is available
// from the rasterized-quad path that RectSL normally renders through.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.RectSLGlsl
import afield.rectsl.RectSLSdfLook

val rectsl = """
#rectsl
->wall 8 5 abstract
->duration 4
"c" rect  0.7 ->square
  '( 0=0  4=6.28318 ) ->rot
  9 '( ic 0.8 * 0 ->offset ) array
;
"""

val sdf = RectSLGlsl.emitSdf(RectAnimationOracle.compile(rectsl).program, "scene")

// Nine copies, one shared rotation curve: the per-copy offsets are constant
// in time, so they fold to literals instead of nine more functions.
println("rects=${sdf.rectCount} curves=${sdf.trackFunctionCount}")

val fragment = buildString {
    appendLine("#version 450")
    appendLine("layout(location = 0) in vec2 v_world;")
    appendLine("layout(location = 0) out vec4 outColor;")
    appendLine("layout(push_constant) uniform Push { float time; } push;")
    appendLine()
    appendLine(sdf.glsl)
    appendLine()
    appendLine("void main() {")
    appendLine("    float d = scene(v_world, push.time);")
    appendLine("    float fill    = 1.0 - smoothstep(0.0, 0.012, d);")
    appendLine("    float outline = 1.0 - smoothstep(0.0, 0.012, abs(d) - 0.03);")
    appendLine("    float glow    = exp(-6.0 * max(d, 0.0));")
    appendLine("    vec3 c = vec3(0.06, 0.07, 0.10) * glow;")
    appendLine("    c = mix(c, vec3(0.18, 0.62, 0.95), fill);")
    appendLine("    c = mix(c, vec3(1.0), outline * 0.85);")
    appendLine("    outColor = vec4(c, 1.0);")
    appendLine("}")
}

println(fragment.lines().takeLast(12).joinToString("\n"))
println()
println("full fragment: ${fragment.length} chars")
println()
// This box assembles the shader by hand to show what the pieces are. You do not
// have to: RectSLSdfLook is that vocabulary as data, RectSLSdfShader assembles the
// whole thing, and there is one preset per capability the field is good for.
println("presets that do this for you: " + RectSLSdfLook.PRESETS.joinToString(", ") { it.name })
println("to see it drawn and moving, replay the rectsl_sdf_live transcript")
