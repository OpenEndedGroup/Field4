import afield.controlGroup
import afield.displayOpen
import afield.four.neural.*
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK13.*
import java.io.File

prop<GpuShaderLearningView>(lab)
fromContextWithNameOrNull<GpuShaderLearningView>("lab")?.close()
check(renderer.device.supportsCooperativeVectorTraining) {
    "GPU learning needs enabled cooperative-vector training. learned_from_module provides offline training."
}
val source=RenderToTexture(renderer) {
    width=1920;height=1080;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT
}
val teacher=moduleHere { source.apply();depthTest=false;depthWrite=false;blend=false }
val mesh=Mesh(renderer,teacher)
mesh.resize(3,0,teacher)
mesh.push.setMembers(teacher.current.fi.pushConstants.single().members)
teacher += "fullscreen" to mesh
source += "teacher" to teacher
lab=GpuShaderLearningView(renderer,source) { t -> mesh.push["state"]=vec(source.width,source.height,t,0) }.install()
var learn=true
var view=2
var reset=false
var save=false
var iterations=8
controlGroup("GPU shader learning") {
    checkbox("Learn on GPU",initialValue=true) { learn=it }
    slider("Updates per frame",min=1.0,max=32.0,step=1.0,initialValue=8.0) { iterations=it.toInt() }
    slider("0: Source · 1: Learned · 2: Compare",min=0.0,max=2.0,step=1.0,initialValue=2.0) { view=it.toInt() }
    button("Reset model and optimizer") { if(it)reset=true }
    button("Save frozen weights") { if(it)save=true }
}
displayOpen("GPU shader learning")
println("Edit fragment; training samples actual linear RGB. Freeze learning and choose Learned to stop the teacher pass. Compare is source / learned / error × 8.")
val owner=renderer
frames {
    var phase=0
    var sourceHash=ShaderTraining.sourceDigest(source)
    while(true) {
        val hash=ShaderTraining.sourceDigest(source)
        if(hash!=sourceHash) { sourceHash=hash;reset=true;println("Teacher changed; restarting the fit.") }
        if(reset){lab.model.reset();reset=false}
        lab.learning=learn;lab.iterationsPerFrame=iterations;lab.view=view
        lab.parameter=if(learn)(phase%13)/6f-1 else kotlin.math.sin(phase*.018f)
        if(save) {
            save=false
            runCatching {
                val directory=File("build/shader-learning/gpu-${System.currentTimeMillis()}")
                lab.model.save(directory,sourceHash,"Fullscreen module; camera, textures and other controls fixed; normalized time varies")
                println("FROZEN MODEL SAVED: $directory — load weights.f16 and weights.json with LearnedShader.")
            }.onFailure { println("Save failed: ${it.message}") }
        }
        phase++;frame()
    }
}.end {
    lab.close()
    owner.cleanup+=Runnable {
        teacher.current.cleanup();source.imageViewArray.forEach { it.imageView.cleanup() }
        source.colorImageRefs.forEach { it?.cleanup() };source.defaultSampler?.cleanup()
    }
}
