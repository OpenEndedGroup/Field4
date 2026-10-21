import afield.media.reconstruction.*

prop<ReconstructionDemo>(lab)
val runScope = requireNotNull(RunScope.current()) { "Run this example in a Field box." }
val available = NgxSupport.availability(renderer.device)
available.onFailure { println("Neural reconstruction unavailable: ${it.message}; showing the source.") }
val modes = [
    null,
    ReconstructionMode.DLAA,
    ReconstructionMode.QUALITY,
    ReconstructionMode.PERFORMANCE,
    ReconstructionMode.ULTRA_PERFORMANCE,
]
val smallest =
    if (available.isSuccess)
        DlssReconstruction.recommend(
                renderer,
                ReconstructionSize(1920, 1080),
                ReconstructionMode.ULTRA_PERFORMANCE,
            )
            .smallestFor(ReconstructionSize(1920, 1080))
    else null
var requested = if (available.isSuccess) 1 else 0
var active = requested
var abstractScene = false
var animate = true
var camera = true
var cut = false
var reload = false
fun createView(mode: ReconstructionMode?): ReconstructionDemo {
    val next = ReconstructionDemo(renderer, mode, attach = false)
    try {
        // Each mode starts from the edited properties, including after a successful reload.
        val vertexSource = vertex
        val fragmentSource = fragment
        if (vertexSource != null || fragmentSource != null) {
            next
                .reload(
                    requireNotNull(vertexSource) { "Missing vertex shader" },
                    requireNotNull(fragmentSource) { "Missing fragment shader" },
                )
                .getOrThrow()
        }
        return next.attach()
    } catch (failure: Throwable) {
        next.close()
        throw failure
    }
}
var ownedLab = createView(modes[active])
lab = ownedLab
runScope.own { ownedLab.close() }
controlGroup("reconstruction") {
    if (available.isSuccess) {
        slider(
            "0: Source · 1: DLAA · 2: Quality · 3: Performance · 4: Ultra",
            min = 0.0,
            max = 4.0,
            step = 1.0,
            initialValue = 1.0,
        ) {
            requested = it.toInt()
        }
    }
    checkbox("Abstract colors and particles", initialValue = false) { abstractScene = it }
    checkbox("Animate", initialValue = true) { animate = it }
    checkbox("Move camera", initialValue = true) { camera = it }
    button("Camera cut / reset") { if (it) cut = true }
    button("Reload edited shaders") { if (it) reload = true }
}
displayOpen("reconstruction")
if (smallest != null)
    println("Ultra minimum input: ${smallest.width} × ${smallest.height}. Mode 4 uses the SDK recommendation.")
println("1920 × 1080 output. Edit this box's vertex / fragment shaders, then reload.")
frames {
    var previousAbstract = abstractScene
    var previousCamera = camera
    while (true) {
        if (requested != active) {
            runCatching {
                val next = createView(modes[requested])
                next.time = ownedLab.time
                val previous = ownedLab
                ownedLab = next
                lab = next
                previous.close()
                active = requested
                println(
                    "${modes[active] ?: "Source"}: ${ownedLab.inputSize.width} × ${ownedLab.inputSize.height} → 1920 × 1080",
                )
            }
                .onFailure {
                    println("Keeping current view: ${it.message}")
                    requested = active
                }
        }
        if (previousAbstract != abstractScene || previousCamera != camera) ownedLab.reset("scene controls")
        previousAbstract = abstractScene
        previousCamera = camera
        ownedLab.abstractScene = abstractScene
        ownedLab.animate = animate
        ownedLab.cameraMotion = camera
        if (cut) {
            ownedLab.time += 4f
            ownedLab.reset("camera cut")
            cut = false
        }
        if (reload) {
            val vertexSource = requireNotNull(vertex) { "Missing vertex shader on this box or its parents" }
            val fragmentSource = requireNotNull(fragment) { "Missing fragment shader on this box or its parents" }
            ownedLab.reload(vertexSource, fragmentSource).onFailure {
                println("Keeping working shaders: ${it.message}")
            }
            reload = false
        }
        frame()
    }
}
    .end { ownedLab.close() }
