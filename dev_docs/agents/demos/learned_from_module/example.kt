import afield.controlGroup
import afield.displayOpen
import afield.four.neural.*
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK13.*
import java.io.File

prop<ShaderLearningView>(lab)
fromContextWithNameOrNull<ShaderLearningView>("lab")?.close()
val source = RenderToTexture(renderer) {
    width=1920; height=1080; useDepth=false
    format=VK_FORMAT_R32G32B32A32_SFLOAT
}
val teacher = moduleHere { source.apply(); depthTest=false; depthWrite=false; blend=false }
val mesh = Mesh(renderer, teacher)
mesh.resize(3,0,teacher)
mesh.push.setMembers(teacher.current.fi.pushConstants.single().members)
teacher += "fullscreen" to mesh
source += "teacher" to teacher
val setTime: (Float)->Unit = { t -> mesh.push["state"]=vec(source.width,source.height,t,0) }
lab = ShaderLearningView(renderer,source,setSourceParameter=setTime)
var capture=false
var requestedView=0
var animate=true
var trainingJob: ShaderTrainingJob?=null
var reported=false
var steps=12000
controlGroup("learn this module") {
    button("Capture shader and train") { if(it)capture=true }
    slider("Training steps",min=1000.0,max=30000.0,step=1000.0,initialValue=12000.0) { steps=it.toInt() }
    slider("0: Source · 1: Learned · 2: Compare",min=0.0,max=2.0,step=1.0,initialValue=0.0) { requestedView=it.toInt() }
    checkbox("Animate parameter",initialValue=true) { animate=it }
}
displayOpen("learn this module")
println("Edit this box's fragment shader, run the box, then Capture shader and train. The source stays visible while training.")
val owner=renderer // Retain ownership even if the parent renderer box stops first.
frames {
    var phase=0.0
    while(true) {
        if(capture) {
            capture=false
            if(trainingJob?.result?.isDone!=false) {
                runCatching {
                    val run=File("build/shader-learning/module-${System.currentTimeMillis()}")
                    val dataset=ShaderTraining.capture(renderer,source,File(run,"dataset"),setParameter=setTime)
                    trainingJob=ShaderTraining.trainAsync(dataset,File(run,"model"),steps)
                    reported=false
                    println("Captured actual GPU RGB. Training log: ${trainingJob!!.log}")
                }.onFailure { println("Capture failed: ${it.message}") }
            }
        }
        val activeJob=trainingJob
        if(activeJob!=null && activeJob.result.isDone && !reported) {
            reported=true
            runCatching {
                val model=activeJob.result.join()
                lab.load(model)
                println("LEARNED MODULE READY: $model — choose view 1 or 2; weights.json records held-out error.")
                File("build/shader-learning/latest-model.txt").writeText(model.absolutePath)
            }.onFailure { println("Keeping the working view: ${it.message}") }
        }
        lab.view=requestedView
        if(animate)phase+=.01
        lab.parameter=kotlin.math.sin(phase).toFloat()
        frame()
    }
}.end {
    trainingJob?.close(); lab.close()
    owner.cleanup+=Runnable {
        teacher.current.cleanup()
        source.imageViewArray.forEach { it.imageView.cleanup() }
        source.colorImageRefs.forEach { it?.cleanup() }; source.defaultSampler?.cleanup()
    }
}
