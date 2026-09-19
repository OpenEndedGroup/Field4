// Three calls, three different answers, and that is the whole point of the type.
import afield.four.fabricated.*

prop<PathTracingRenderer>(tracer)

// 1. No argument offers the packaged integrator -- which is exactly what is already
//    running. UNCHANGED. `ComputeModulePointer.reload` answers a bare `false` here, the
//    same `false` it answers for a compile failure, so a `check(...)` around it throws on
//    a no-op. That is why this returns three outcomes instead of a boolean.
println(tracer.reloadIntegrator())

// 2. A source that does not compile. FAILED, with the compiler's own messages, and the
//    last working pipeline keeps rendering -- watch the window: the image does not blink.
println(tracer.reloadIntegrator("#version 460\nvoid main() { this is not glsl }"))

// 3. A real swap, and a deliberately unphysical one: multiply the direct and indirect
//    terms where the integrator adds them, scale, and curve the result. One line changes,
//    but the whole ~30 KB integrator is recompiled and hot-swapped under a running camera.
//    `integratorSource()` hands you the packaged source with this device's HARDWARE_QUERY
//    define already applied -- the step that used to exist only at two private call sites,
//    in no document and no API.
//
//    This is the line to play with. `radiance` is what reached the camera directly and
//    `indirect` is what arrived by a bounce; both are vec3 and both are in scope at the end
//    of the integrator, so anything you can write over the two of them compiles and is in
//    the window about a second later.
val remix = tracer.integratorSource()
    .replace("return vec4(radiance+indirect,1);",
        "return vec4(pow(radiance*indirect*2, vec3(0.76)),1);")
println(tracer.reloadIntegrator(remix))

// Anything the camera sees with no bounce behind it multiplies to zero, so the sunset
// environment drops to black and the three spheres become the only lit things in the frame,
// each keeping its own colour over a soft pool on the floor; the gold one holds just its sun
// glint. The camera has not moved by a single bit.
println("reset: '" + tracer.lastResetReason + "' generation=" + tracer.resetGeneration)
