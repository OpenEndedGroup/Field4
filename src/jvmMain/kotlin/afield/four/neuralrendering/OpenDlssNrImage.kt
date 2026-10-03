package afield.four.neuralrendering

import afield.four.Renderer
import afield.four.Target
import afield.four.texture.LoadedTexture
import afield.four.targets.RenderToTexture
import afield.four.modules.ModulePointer
import afield.four.device.CommandBuffer
import afield.stdlib.linalg.Vector4f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM

/** Editable image study. Install below a renderer; inference precedes the grading/display module.
 * close detaches immediately and retires allocations after submitted frames finish. */
class OpenDlssNrImage(val renderer: Renderer,val path: String,val name: String="opendlssnr-image") : Target,AutoCloseable {
    private val availability=OpenDlssNrSupport.availability(renderer).also {check(it.ready) {it.reason}}
    private val texture=LoadedTexture.fromFile(path,renderer,VK_FORMAT_R8G8B8A8_UNORM)
    val pass=try {OpenDlssNrPass(renderer,texture.width,texture.height)} catch(failure: Throwable) {texture.cleanup();throw failure}
    val output: RenderToTexture get()=pass.output
    var controls: OpenDlssNrControls
        get()=pass.controls
        set(value){pass.controls=value}
    /** Linear RGB multipliers, independent of the network condition. */
    var exposure=Vector4f(1f,1f,1f,1f)
    var gamma=Vector4f(1f,1f,1f,1f)
    var offset=Vector4f(0f,0f,0f,0f)
    private var frame=0L
    private var closed=false
    private var blit: ModulePointer?=null
    fun install(): OpenDlssNrImage {
        check(!closed)
        renderer.renderList.preTargets[name]=this
        blit=output.blitToScreen(name=name)
        return this
    }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        pass.evaluate(command,OpenDlssNrInputs(texture,OpenDlssNrColorEncoding.SRGB_CODE),OpenDlssNrFrame(frame++))
        blit?.current?.meshes?.get(name)?.push?.apply {
            set("exposure",exposure);set("gamma",gamma);set("offset",offset)
        }
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.renderList.preTargets.remove(name,this)
        renderer.renderList.modules.remove(name)
        blit?.current?.cleanup()
        pass.close()
        renderer.cleanup+=Runnable {texture.cleanup()}
    }
}
