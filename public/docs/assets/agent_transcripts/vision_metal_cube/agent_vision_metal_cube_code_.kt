import afield.graphics.visionMetal
import afield.graphics.visionMetalExampleScript
import afield.graphics.visionMetalExampleShader

visionMetal(
    shader = visionMetalExampleShader,
    script = visionMetalExampleScript,
    uniforms = listOf("phase", "amount", "tint")
).onStatus {
    println("vision-metal ${it.phase}: ${it.message}")
}
println("vision-metal cube published")
