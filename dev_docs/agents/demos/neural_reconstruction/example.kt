import afield.controlGroup
import afield.displayOpen
import afield.four.reconstruction.*
import afield.four.modules.fromContextWithNameOrNull

prop<ReconstructionDemo>(lab)
fromContextWithNameOrNull<ReconstructionDemo>("lab")?.close()
val available = NgxSupport.availability(renderer.device)
available.onFailure { println("Neural reconstruction unavailable: ${it.message}; showing the source.") }
val modes = listOf(null, ReconstructionMode.DLAA, ReconstructionMode.QUALITY, ReconstructionMode.PERFORMANCE,
    ReconstructionMode.ULTRA_PERFORMANCE)
val smallest = if(available.isSuccess) DlssReconstruction.recommend(renderer,ReconstructionSize(1920,1080),
    ReconstructionMode.ULTRA_PERFORMANCE).smallestFor(ReconstructionSize(1920,1080)) else null
var requested = if (available.isSuccess) 1 else 0
var active = requested
var abstractScene = false
var animate = true
var camera = true
var cut = false
var reload = false
lab = ReconstructionDemo(renderer, modes[active])
controlGroup("reconstruction") {
    if (available.isSuccess) {
        slider("0: Source · 1: DLAA · 2: Quality · 3: Performance · 4: Ultra", min=0.0, max=4.0, step=1.0, initialValue=1.0) { requested=it.toInt() }
    }
    checkbox("Abstract colors and particles", initialValue=false) { abstractScene=it }
    checkbox("Animate", initialValue=true) { animate=it }
    checkbox("Move camera", initialValue=true) { camera=it }
    button("Camera cut / reset") { if(it)cut=true }
    button("Reload edited shaders") { if(it)reload=true }
}
displayOpen("reconstruction")
if(smallest!=null)println("Ultra minimum input: ${smallest.width} × ${smallest.height}. Mode 4 uses the SDK recommendation.")
println("1920 × 1080 output. Edit this box's vertex / fragment shaders, then reload.")
frames {
    var previousAbstract = abstractScene
    var previousCamera = camera
    while(true) {
        if(requested!=active) {
            runCatching {
                val next = ReconstructionDemo(renderer, modes[requested], attach=false)
                next.time=lab.time
                lab.close()
                lab=next.attach()
                active=requested
                println("${modes[active] ?: "Source"}: ${lab.inputSize.width} × ${lab.inputSize.height} → 1920 × 1080")
            }.onFailure { println("Keeping current view: ${it.message}");requested=active }
        }
        if(previousAbstract!=abstractScene || previousCamera!=camera)lab.reset("scene controls")
        previousAbstract=abstractScene;previousCamera=camera
        lab.abstractScene=abstractScene;lab.animate=animate;lab.cameraMotion=camera
        if(cut) { lab.time+=4f;lab.reset("camera cut");cut=false }
        if(reload) {
            val vertexSource = thisBox.getPropertyRaw("vertex") as String
            val fragmentSource = thisBox.getPropertyRaw("fragment") as String
            lab.reload(vertexSource,fragmentSource).onFailure { println("Keeping working shaders: ${it.message}") }
            reload=false
        }
        frame()
    }
}
