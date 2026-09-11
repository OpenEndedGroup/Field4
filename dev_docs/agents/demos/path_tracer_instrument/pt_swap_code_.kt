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

// 3. A real swap: direct light only. One line changes, but the whole ~30 KB integrator is
//    recompiled and hot-swapped under a running camera. `integratorSource()` hands you the
//    packaged source with this device's HARDWARE_QUERY define already applied -- the step
//    that used to exist only at two private call sites, in no document and no API.
val directOnly = tracer.integratorSource()
    .replace("return vec4(radiance+indirect,1);", "return vec4(radiance,1);")
println(tracer.reloadIntegrator(directOnly))

// The gold sphere loses the bounce light under its equator and goes black; the blue one
// loses its warm floor bounce. The camera has not moved by a single bit.
println("reset: '" + tracer.lastResetReason + "' generation=" + tracer.resetGeneration)
