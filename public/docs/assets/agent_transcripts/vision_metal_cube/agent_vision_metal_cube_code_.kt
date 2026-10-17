import afield.graphics.visionos.VisionMetalGeometryOrigin
import afield.graphics.visionos.visionMetal
import afield.graphics.visionos.visionMetalExampleScript
import afield.graphics.visionos.visionMetalExampleShader

// `geometry` is explicit: there is no fallback to the head's cube, so a program that
// neither ships vertices nor builds them in Kotlin/JS would draw nothing. This one asks
// for the built-in cube by name.
visionMetal(
    shader = visionMetalExampleShader,
    script = visionMetalExampleScript,
    uniforms = listOf("phase", "amount", "tint"),
    geometry = VisionMetalGeometryOrigin.BUILTIN,
).onStatus {
    println("vision-metal ${it.phase}: ${it.message}")
}
println("vision-metal cube published")
