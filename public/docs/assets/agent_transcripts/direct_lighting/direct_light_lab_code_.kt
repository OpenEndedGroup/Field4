import afield.controlGroup
import afield.displayOpen
import afield.four.lighting.*
import afield.four.fabricated.rtxdiSettings
import afield.stdlib.linalg.Vector3f
import kotlin.math.*
import afield.four.modules.fromContext
import afield.four.modules.fromContextWithNameOrNull
import afield.four.device.lightingCapabilityReport
import afield.four.util.keyCamera

val renderer: Renderer = fromContext()
prop<DirectLightingDemo>(lab)
// Re-executing this box replaces the experiment at a frame boundary.
fromContextWithNameOrNull<DirectLightingDemo>("lab")?.close()
lab = DirectLightingDemo(renderer, 640, 360)
println(renderer.device.lightingCapabilityReport().toString(2))
var cameraControl by keyCamera(renderer, lab.bundle.scene.camera)

val restir = lab.bundle.rtxdiSettings()
val sdkReady = lab.visibility.available && runCatching {
    lab.lighting.enableRtxdi(RtxdiProgram.load(), restir).getOrThrow()
}.onFailure { println("Optional RTXDI unavailable: ${it.message}") }.isSuccess
var useRtxdi = sdkReady
var reference = false
var temporal = true
var neighbors = 4
var lightCount = 16
var reset = false
var source = false
var shadows = lab.visibility.available
var animate = true
var budget = if (sdkReady) 4 else 16
var weight = 1f
var power = 60f
var phase = 0.0
var diagnostic = 0
controlGroup("lighting") {
    checkbox("Original renderer", initialValue = source) { source = it }
    checkbox("Ray-query shadows", initialValue = shadows) { shadows = it }
    if (sdkReady) {
        checkbox("RTXDI", initialValue = useRtxdi) { useRtxdi = it }
        checkbox("Reference estimator", initialValue = reference) { reference = it }
        checkbox("Temporal reuse", initialValue = temporal) { temporal = it }
        slider("Spatial neighbors", min = 0.0, max = 16.0, step = 1.0, initialValue = 4.0) { neighbors = it.toInt() }
        slider("Area lights", min = 1.0, max = 128.0, step = 1.0, initialValue = 16.0) { lightCount = it.toInt() }
        button("Reset history") { if (it) reset = true }
    }
    checkbox("Animate light", initialValue = animate) { animate = it }
    slider("Samples", min = 1.0, max = 128.0, step = 1.0, initialValue = budget.toDouble()) { budget = it.toInt() }
    slider("Effect weight", initialValue = 1.0) { weight = it.toFloat() }
    slider("Radiance", min = 0.0, max = 150.0, initialValue = 60.0) { power = it.toFloat() }
    slider("Light phase", min = 0.0, max = 6.283, initialValue = 0.0) { phase = it }
    slider("View: RGB / normal / depth / visibility / motion / IDs / M / weight / rejection", min = 0.0, max = if (sdkReady) 8.0 else 4.0, step = 1.0, initialValue = 0.0) { diagnostic = it.toInt() }
}
displayOpen("lighting")
println("Direct lighting ready: use the lighting controls and arrow-key camera.")
// Controls publish values; Vulkan changes happen here, between frames.
frames {
    var seconds = 0.0
    while (true) {
        restir.temporal = temporal
        restir.spatialSamples = neighbors
        restir.shadows = shadows
        if (reset) { restir.resetHistory(); reset = false }
        if (sdkReady) restir.commitLights(List(lightCount) { i ->
            val a = i * 2.0 * PI / lightCount + seconds + phase
            IdentifiedAreaLight(100 + i, AreaEmitter(
                Vector3f((cos(a)*3.0).toFloat(), 4.5f, (sin(a)*2.0).toFloat()),
                Vector3f(0.6f,0f,0f), Vector3f(0f,0f,0.6f), Vector3f(power*3f/lightCount)))
        })
        lab.lighting.mode = if (source) DirectLighting.Mode.SOURCE
            else if (useRtxdi && sdkReady) {
                if (reference) DirectLighting.Mode.RTXDI_REFERENCE else DirectLighting.Mode.RTXDI
            }
            else if (shadows && lab.visibility.available) DirectLighting.Mode.RAY_QUERY
            else DirectLighting.Mode.UNSHADOWED
        lab.lighting.view = DirectLighting.View.entries[diagnostic]
        lab.lighting.samples = budget
        lab.lighting.effectWeight = weight
        lab.lighting.emitter.radiance.set(power)
        lab.animate(seconds + phase)
        if (animate) seconds += 1.0 / 60.0
        frame()
    }
}
