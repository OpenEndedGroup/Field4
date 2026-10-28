/*
Scene -> stereo RTT -> FXAA composite -> OpenXR + desktop mirror.
The fragment property reuses Fabricate's FXAA shader, adding per-eye sample clamping.
Toggle FXAA below to compare at the same render resolution. No temporal history is used.
*/
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.targets.SideBySideStereoTarget
import java.util.concurrent.atomic.AtomicBoolean

val sourceStereo = fromContext<SideBySideStereoTarget>()
val rawStereo = sourceStereo.packed
val aaEnabled = AtomicBoolean(true)
val composite = RenderToTexture(renderer) {
    width = sourceStereo.eyeWidth * 2
    height = sourceStereo.eyeHeight
    format = rawStereo.parameters.format
    colorSampled = true
    colorTransferSrc = true
    useDepth = false
}
val sourceSampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
val pass = moduleHere {
    name = "stereo-fxaa-composite"
    color = 1
    colorFormats = listOf(composite.parameters.format)
    imageFormat = composite.parameters.format
    depth = 0
    depthTest = false
    depthWrite = false
    blend = false
    textureResolver = { tex ->
        if (tex.name == "sourceTex") fromViews(listOf(rawStereo), tex.name, sourceSampler) else null
    }
}
composite += "fxaa" to pass
val quad = StandardRenderer.run { pass.ensureFullscreenTriangle("fxaa-quad") }
quad.push["edgeThreshold"] = 0.125f
quad.push["edgeThresholdMin"] = 0.0312f
quad.push["spanMax"] = 8f
quad.update["aa-toggle"] = updateForever {
    quad.push["aaBlend"] = if (aaEnabled.get()) 1f else 0f
    true
}

// preTargets retain insertion order: the scene packs its two eyes before this composite reads it.
renderer.renderList.preTargets["stereo-fxaa"] = composite
controlGroup("antialias") {
    checkbox("FXAA", initialValue = true) { aaEnabled.set(it) }
}
println(
    "FXAA composite: ${composite.parameters.width} x ${composite.parameters.height}; current-frame input, isolated eye taps",
)
displayOpen("antialias")
