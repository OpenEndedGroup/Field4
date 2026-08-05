// Lower a RectSL scene to a GLSL signed distance field.
//
// RectSL is a staged language: the stack VM runs once at compile time, so
// `array`, `grid`, selections, charts and the {{ }} escapes are all spent
// before this point. What reaches the GPU is a flat list of rects whose
// channels are tracks — which is why so little of the language has to be
// ported. See rectsl_cheatsheet.md, "Lowering to GLSL".
import afield.rectsl.RectAnimationOracle
import afield.rectsl.RectSLGlsl

val rectsl = """
#rectsl
->wall 8 5 abstract
->duration 4

"bar" rect
  0.9 2.6 ->wh
  '( 0=-2.6  4=2.6 ) ->x
  '( 0=0  2=1.1  4=0 ) tcb ->y
  '( 0=0  4=1.5708 ) ->rot
;
"""

val program = RectAnimationOracle.compile(rectsl).program
val sdf = RectSLGlsl.emitSdf(program, functionName = "scene")

println("rects unioned      : ${sdf.rectCount}")
println("curves emitted     : ${sdf.trackFunctionCount}")
println("fidelity caveats   : ${if (sdf.ok) "none" else sdf.notes.joinToString("; ")}")
println("shader source chars: ${sdf.glsl.length}")
println()
println(sdf.glsl)

// `sdf.glsl` is self-contained: no uniforms, no buffers, no samplers.
// Paste it into any shader that has a world-space point and a time.
