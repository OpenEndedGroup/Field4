import afield.controlGroup
import afield.displayOpen
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.fromContextWithNameOrNull
import afield.four.targets.PingPongRenderToTexture
import afield.four.texture.HasImageView
import afield.four.vision.OpticalFlow
import afield.stdlib.linalg.Vector2f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*

prop<AutoCloseable>(flowSession)
fromContextWithNameOrNull<AutoCloseable>("flowSession")?.close()
check(renderer.device.supportsOpticalFlow) { "This device has no enabled VK_NV_optical_flow." }

// One editable module, two retained frames. Rendering rotates the ring automatically.
prop<PingPongRenderToTexture>(history) = PingPongRenderToTexture(renderer) {
    width=960; height=540; useDepth=false; format=VK_FORMAT_R16G16B16A16_SFLOAT
}
val scene = moduleHere { history.apply(); depthTest=false; depthWrite=false; blend=false }
val triangle = Mesh(renderer,scene)
triangle.resize(3,0,scene)
triangle.push.setMembers(scene.current.fi.pushConstants.single().members)
scene += "image" to triangle
history += "image" to scene

// These providers follow the ring. Do not snapshot their .imageView handles.
prop<HasImageView>(previousImage) = history.previousImageView
prop<HasImageView>(currentImage) = history.latestImageView
prop<OpticalFlow>(flow) = OpticalFlow(renderer,previousImage,currentImage,grid=4)
prop<RenderToTexture>(motion) = flow.output

// OpticalFlow.evaluate submits to its own queue, so run it between Field frames.
// Submit the source first. The final fence makes the produced pair ready to sample.
val owner=renderer
val ownedHistory=history
val ownedFlow=flow
val command=CommandBuffer(owner.commandPool,true,false)
val fence=Fence(owner.device,false)
fun renderSource() {
    vkResetCommandBuffer(command.vkCommandBuffer,0)
    command.beginRecording(); ownedHistory.render(command,0); command.endRecording()
    MemoryStack.stackPush().use { owner.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
    fence.fenceWait(); fence.reset()
}
var animate=true
var reset=true
var time=0f
var previousTime=0f
// Analytical displacement of this example, useful for checking the measured texture.
prop<Vector2f>(expectedMotion)=Vector2f()
var shaderVersion=scene.current.fi
var closed=false
flowSession=AutoCloseable {
    if(!closed) {
        closed=true
        // A consumer must stop sampling before its parent's resources retire.
        owner.renderList.modules.remove("pingpong flow texture")
        if(!owner.isClosed) {
            ownedFlow.close()
            val views=ownedHistory.frameImageViews.imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }
            owner.cleanup+=Runnable {
                scene.current.cleanup(); command.cleanup(); fence.cleanup()
                views.forEach { it.cleanup(); it.underlyingImage?.cleanup() }
                ownedHistory.defaultSampler?.cleanup()
            }
        }
    }
}
controlGroup("pingpong motion") {
    checkbox("Animate source",initialValue=true) { animate=it; reset=true }
    button("Reset the pair") { if(it)reset=true }
}
displayOpen("pingpong motion")
println("Render source → previous/current pair → optical flow → motion texture. Edit fragment here; edit the child to sample motion.")
val ownedSession=flowSession
frames {
    while(!closed && !owner.isClosed) {
        if(shaderVersion!==scene.current.fi) { shaderVersion=scene.current.fi; reset=true }
        triangle.push["time"]=time
        if(reset)previousTime=time
        expectedMotion=Vector2f(
            (kotlin.math.sin(time)-kotlin.math.sin(previousTime))*180,
            (kotlin.math.cos(time*1.3f)-kotlin.math.cos(previousTime*1.3f))*120)
        // Seed both slots on the first frame, a cut, or a shader edit: no old/new comparison.
        if(reset)renderSource()
        renderSource()
        // A paused pair is repeated, not a new consecutive observation.
        ownedFlow.evaluate(reset=reset || !animate)
        reset=false
        previousTime=time
        if(animate)time+=1f/30f
        frame()
    }
}.end { ownedSession.close() }
