/*
ShaderTrinket in Field. This box's `fragment` property is a ShaderToy Image pass: mainImage
gets fragCoord in pixels (y up) and the ShaderToy uniforms (iResolution, iTime, iMouse, iDate,
iChannel0..3). Edit it and press Alt+Enter to recompile; a mistake is marked on its own line.

Every shader box below draws into the same canvas, so launching one replaces the picture, the
way choosing a preset does. Launch this box again to come back to Flat Rings.
*/
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
