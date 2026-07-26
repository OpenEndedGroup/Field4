import afield.graphics.VisionMetalGeometryOrigin
import afield.graphics.visionMetal
import afield.graphics.visionMetalExampleScript
import afield.graphics.visionMetalExampleShader

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
