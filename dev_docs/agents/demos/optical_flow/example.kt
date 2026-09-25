import afield.controlGroup
import afield.displayOpen
import afield.four.vision.*
import afield.four.modules.fromContextWithNameOrNull

prop<OpticalFlowDemo>(lab)
fromContextWithNameOrNull<OpticalFlowDemo>("lab")?.close()
check(renderer.device.supportsOpticalFlow) { "This device has no enabled VK_NV_optical_flow; other Field rendering is unaffected." }
lab=OpticalFlowDemo(renderer)
var gain=1f
var damping=.98f
var cost=80f
var deform=true
var reset=false
controlGroup("optical flow") {
    slider("Advection gain",min=0.0,max=3.0,initialValue=1.0) { gain=it.toFloat() }
    slider("Feedback retention",min=0.0,max=.995,initialValue=.98) { damping=it.toFloat() }
    slider("Reject cost above",min=0.0,max=600.0,initialValue=80.0) { cost=it.toFloat() }
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
