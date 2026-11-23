/*
A ShaderToy Image pass drawn into the window of the renderer above. The program is this box's
fragment property: mainImage gets fragCoord in pixels (y up) and the ShaderToy uniforms
(iResolution, iTime, iMouse, iDate, iChannel0..3). Edit it and press Alt+Enter to recompile; a
mistake is marked on its own line and the last good program keeps running. ShaderToy code
pasted from the web works as is. Copies share one canvas, so launching one shows its picture.
*/
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
