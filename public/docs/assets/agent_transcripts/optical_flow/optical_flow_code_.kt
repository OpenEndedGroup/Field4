import afield.controlGroup
import afield.displayOpen
import afield.four.vision.*
import afield.four.modules.fromContextWithNameOrNull

prop<OpticalFlowDemo>(lab)
fromContextWithNameOrNull<OpticalFlowDemo>("lab")?.close()
// AUTO: the VK_NV_optical_flow engine where the device has one, portable pyramidal Lucas–Kanade everywhere else.
lab=OpticalFlowDemo(renderer,backend=OpticalFlowBackend.AUTO)
println("Optical flow backend: ${lab.flow.backend}")
var gain=1f
var damping=.98f
// The two backends report cost on different scales, so the rejection threshold starts at the demo's per-backend default.
var cost=lab.costThreshold
var deform=true
var reset=false
controlGroup("optical flow") {
    slider("Advection gain",min=0.0,max=3.0,initialValue=1.0) { gain=it.toFloat() }
    slider("Feedback retention",min=0.0,max=.995,initialValue=.98) { damping=it.toFloat() }
    slider("Reject cost above",min=0.0,max=6.0*lab.costDisplayRange,initialValue=lab.costThreshold.toDouble()) { cost=it.toFloat() }
    checkbox("Consecutive-frame wiggle",initialValue=true) { deform=it;reset=true }
    button("Reset feedback and motion hints") { if(it)reset=true }
}
displayOpen("optical flow")
println("Top: consecutive frames. Below: motion/cost and advected feedback. Turn off wiggle for the (+12, -8) translation check.")
frames {
    var time=0f
    while(true) {
        lab.dx=if(deform)0f else 12f;lab.dy=if(deform)0f else -8f;lab.displayRange=if(deform)3f else 20f;lab.gain=gain;lab.damping=damping;lab.costThreshold=cost;lab.deformation=deform
        lab.update(time,reset);reset=false;time+=lab.pairStep
        frame()
    }
}.end { lab.close() }
