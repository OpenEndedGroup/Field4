// SDF Sphere Trace: launch to show it in the window. mainImage is in this box's `fragment`.
val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
}
