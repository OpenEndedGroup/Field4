/**
 * Runs one image file through [OpenDlssNrPass] and shows the result on screen.
 *
 * [OpenDlssNrImage] loads the image, requires [OpenDlssNrSupport.availability] to be ready, and after
 * [OpenDlssNrImage.install] evaluates the pass each frame under the renderer, with `controls` for the network and
 * `exposure`, `gamma` and `offset` for grading afterwards.
 */

package afield.graphics.render.neural

import afield.graphics.render.ModulePointer
import afield.graphics.texture.LoadedTexture
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Vector4f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM

/**
 * Editable image study. Install below a renderer; inference precedes the grading/display module. close detaches
 * immediately and retires allocations after submitted frames finish.
 */
class OpenDlssNrImage(val renderer: Renderer, val path: String, val name: String = "opendlssnr-image") :
    Target, AutoCloseable {
    private val availability = OpenDlssNrSupport.availability(renderer).also { check(it.ready) { it.reason } }
    private val texture = LoadedTexture.fromFile(path, renderer, VK_FORMAT_R8G8B8A8_UNORM)
    val pass = try {
        OpenDlssNrPass(renderer, texture.width, texture.height)
    } catch (failure: Throwable) {
            texture.cleanup()
            throw failure
        }
    val output: RenderToTexture
        get() = pass.output

    var controls: OpenDlssNrControls
        get() = pass.controls
        set(value) {
            pass.controls = value
        }

    /** Linear RGB multipliers, independent of the network condition. */
    var exposure = Vector4f(1f, 1f, 1f, 1f)
    var gamma = Vector4f(1f, 1f, 1f, 1f)
    var offset = Vector4f(0f, 0f, 0f, 0f)
    private var frame = 0L
    private var closed = false
    private var blit: ModulePointer? = null

    fun install(): OpenDlssNrImage {
        check(!closed)
        blit = output.blitToScreen(name = name)
        renderer.renderList.preTargets[name] = this
        return this
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (closed) return
        pass.evaluate(command, OpenDlssNrInputs(texture, OpenDlssNrColorEncoding.SRGB_CODE), OpenDlssNrFrame(frame++))
        blit?.current?.meshes?.get(name)?.push?.apply {
            set("exposure", exposure)
            set("gamma", gamma)
            set("offset", offset)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        renderer.renderList.preTargets.remove(name, this)
        renderer.renderList.modules.remove(name, blit)
        blit?.current?.cleanup()
        pass.close()
        renderer.cleanup += Runnable { texture.cleanup() }
    }
}
