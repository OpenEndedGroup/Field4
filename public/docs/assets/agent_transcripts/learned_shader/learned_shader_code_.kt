import afield.controlGroup
import afield.displayOpen
import afield.four.neural.*
import afield.four.modules.fromContextWithNameOrNull

prop<LearnedShader>(lab)
fromContextWithNameOrNull<LearnedShader>("lab")?.close()
var cooperative=renderer.device.supportsCooperativeVector
var active=cooperative
var requestedView=2
var animate=true
var reload=false
lab=LearnedShader(renderer,cooperative=cooperative)
controlGroup("learned shader") {
    checkbox("Cooperative vectors",initialValue=cooperative) { cooperative=it }
    slider("0: Original · 1: Learned · 2: Compare",min=0.0,max=2.0,step=1.0,initialValue=2.0) { requestedView=it.toInt() }
    checkbox("Animate time",initialValue=true) { animate=it }
    button("Reload trained weights") { if(it)reload=true }
}
displayOpen("learned shader")
println("Compare: original / learned / absolute RGB error × 8. Weights describe a 16 → 32 → 32 → 3 network.")
frames {
    var time=0.0
    while(true) {
        if(cooperative!=active || reload) {
            runCatching {
                val next=LearnedShader(renderer,cooperative=cooperative,attach=false)
                lab.close();lab=next.attach();active=cooperative
            }.onFailure { println("Keeping working shader: ${it.message}");cooperative=active }
            reload=false
        }
        lab.view=requestedView
        if(animate)time+=.01
        lab.time=kotlin.math.sin(time).toFloat()
        frame()
    }
}.end { lab.close() }
