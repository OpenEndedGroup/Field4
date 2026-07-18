// RectSL example: inline-functions
//
// This program uses a `{{ ... }}` Kotlin escape, so it is compiled with a
// *dedicated* Kotlin engine rather than this box's own engine — the box engine is
// busy running this code and a nested compile on it would re-enter and deadlock.
// See the "RectSL examples" docs page for the escape mechanism.

import afield.rectsl.compileRectSL
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
wall 22 8 meters
duration 3
fps 30

"bar" rect
  0 0 xy
  0.42 1 wh
  0x38e1b0 solid fill
  32 '(
    ic 0.66 * 0 offset
    '( 0 0.15  0.5 1.15  1 0.15 ) 1 loop-time
    {{ (gaussian(0.5, 0.2) + fbm(9) * 0.4).eval(f) }}  5.5 *  0.25 +  value*
    h
  ) array
  0.05 stagger-time
;
"""

val engine = afield.KotlinInterface2(filePrefix = "INLINE_FN_VIEW_")
val program = compileRectSL(rectsl, engine = engine).program!!
val shader  = rectMaterialShaderCode(program)

val module = moduleHere {
    vertex = RECT_ANIMATION_VERTEX_SHADER
    fragment = shader.exampleFragmentShader
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % program.duration, m)
    t += 1.0 / 60.0
    true
}
