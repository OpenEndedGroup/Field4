import afield.controlGroup
import afield.displayOpen
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.fromContextWithNameOrNull
import afield.four.reconstruction.*
import org.lwjgl.vulkan.VK13.*

prop<AutoCloseable>(plumbing)
fromContextWithNameOrNull<AutoCloseable>("plumbing")?.close()

// Change this to DLAA, PERFORMANCE or ULTRA_PERFORMANCE and rerun the box.
val mode = ReconstructionMode.QUALITY
val outputSize = ReconstructionSize(1920,1080)
val available = NgxSupport.availability(renderer.device)
val inputSize = if(available.isSuccess)
    DlssReconstruction.recommend(renderer,outputSize,mode).optimal else outputSize

// Three ordinary raster outputs. Depth here is a sampled R32F color attachment;
// the target also has its own depth-test attachment.
prop<RenderToTexture>(source)
source = RenderToTexture(renderer) {
    width=inputSize.width; height=inputSize.height; useDepth=true
    colorAttachments=3
    colorFormats=listOf(VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT)
}
val sceneModule = moduleHere { source.apply(); depthTest=true; depthWrite=true; blend=false }
val mesh = Mesh(renderer,sceneModule)
mesh.resize(3+135*36,0,sceneModule)
mesh.push.setMembers(sceneModule.current.fi.pushConstants.single().members)
sceneModule += "geometry" to mesh
source += "scene" to sceneModule
val neural = if(available.isSuccess) DlssReconstruction(renderer,inputSize,outputSize,mode) else null
available.onFailure { println("Source only: ${it.message}") }

var bypass = neural==null
var animate = true
var cut = false
var time = 0f
var previousTime = 0f
var frameIndex = 0L
var revision = 0L
var shaderVersion = sceneModule.current.fi
val pass = object: Target {
    override fun render(command: CommandBuffer,index: Int) {
        // Shader edits invalidate temporal history, even when their layout stays compatible.
        if(shaderVersion!==sceneModule.current.fi) { shaderVersion=sceneModule.current.fi; revision++ }
        val jitter = if(bypass) ReconstructionJitter(0f,0f)
            else ReconstructionJitter.halton(frameIndex,inputSize,outputSize)
        mesh.push["sizeJitter"]=vec(inputSize.width,inputSize.height,jitter.x,jitter.y)
        mesh.push["times"]=vec(time,previousTime,0,0)
        mesh.push["settings"]=vec(0,1,0,0)

        // Record producer, then consumer, on this same command buffer.
        source.render(command,index)
        if(!bypass) neural?.evaluate(command,
            ReconstructionInputs(source.imageViewArray[0],source.imageViewArray[1],source.imageViewArray[2]),
            ReconstructionFrame(frameIndex,jitter,revision=revision))
        previousTime=time
        if(animate)time+=1f/60
        frameIndex++
    }
}

renderer.renderList.preTargets["module reconstruction"]=pass
(neural?.output ?: source).blitToScreen(name="module reconstruction")
// Capture this allocation, not the mutable box prop that a rerun replaces.
val ownedTarget=source
val owner=renderer // Cleanup must not depend on a still-running parent box.
var closed=false
plumbing=AutoCloseable {
    if(!closed) {
        closed=true
        owner.renderList.preTargets.remove("module reconstruction",pass)
        owner.renderList.modules.remove("module reconstruction")
        neural?.close()
        owner.cleanup+=Runnable {
            sceneModule.current.cleanup()
            ownedTarget.imageViewArray.forEach { it.imageView.cleanup() }
            ownedTarget.colorImageRefs.forEach { it?.cleanup() }
            ownedTarget.depthImageView?.cleanup(); ownedTarget.depthImage?.cleanup(); ownedTarget.defaultSampler?.cleanup()
        }
    }
}
controlGroup("module to SR") {
    checkbox("Bypass reconstruction",initialValue=bypass) { bypass=it || neural==null }
    checkbox("Animate",initialValue=true) { animate=it }
    button("Cut / reset history") { if(it)cut=true }
}
displayOpen("module to SR")
println("${inputSize.width} × ${inputSize.height} → 1920 × 1080. Inspect vertex / fragment: RGB, depth and motion are written together.")
frames {
    var wasBypass=bypass
    while(true) {
        if(bypass!=wasBypass) {
            neural?.reset("bypass changed")
            (if(bypass)source else neural?.output ?: source).blitToScreen(name="module reconstruction")
            wasBypass=bypass
        }
        if(cut) { time+=4f;previousTime=time;revision++;neural?.reset("camera cut");cut=false }
        frame()
    }
}.end { plumbing.close() }
