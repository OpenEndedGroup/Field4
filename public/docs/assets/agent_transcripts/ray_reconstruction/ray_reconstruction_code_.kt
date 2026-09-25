import afield.controlGroup
import afield.displayOpen
import afield.four.reconstruction.*
import afield.four.modules.fromContextWithNameOrNull

prop<RayReconstructionDemo>(lab)
fromContextWithNameOrNull<RayReconstructionDemo>("lab")?.close()
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
lab = RayReconstructionDemo(renderer)
var requested = 3
var animate = true
var camera = true
var cut = false
controlGroup("ray reconstruction") {
    slider("0: Raw · 1: Spatial · 2: Spatial + DLAA · 3: RR", min=0.0,max=3.0,step=1.0,initialValue=3.0) { requested=it.toInt() }
    checkbox("Move light",initialValue=true) { animate=it }
    checkbox("Move camera",initialValue=true) { camera=it }
    button("Cut / reset history") { if(it)cut=true }
}
displayOpen("ray reconstruction")
println("1920 × 1080. RR consumes raw RTXDI. The spatial filter and DLAA are alternative paths.")
frames {
    while(true) {
        lab.path=RayReconstructionDemo.Path.entries[requested]
        lab.animate=animate;lab.cameraMotion=camera
        if(cut){lab.time+=4;lab.reset("live cut");cut=false}
        frame()
    }
}.end { lab.close() }
