package afield.four.diffusion

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.stdlib.fromViews
import afield.four.texture.HasImageViewArray
import afield.four.texture.PreparesSampledImages
import org.lwjgl.vulkan.VK10.*

/** Two history targets at their source times, plus a display target at the current render time. */
internal class StreamDiffusionTemporalOutput(
    private val renderer: Renderer, private val source: RenderToTexture,
    private val raw: RenderToTexture, private val motion: StreamDiffusionMotionProvider
) : AutoCloseable {
    private val targets = mutableListOf<RenderToTexture>()
    private val modules = mutableListOf<Module>()
    private fun target(format: Int = source.colorFormats[0]) = RenderToTexture(renderer) {
        width = source.width; height = source.height; useDepth = false; this.format = format
    }.also { it.setDefaultSampler(anisotropyEnable = false, filter = if (format == VK_FORMAT_R32G32B32A32_SFLOAT) VK_FILTER_NEAREST else VK_FILTER_LINEAR); targets += it }
    private val history = arrayOf(target(), target())
    val output = target()
    private val invalid = target(VK_FORMAT_R32G32B32A32_SFLOAT)
    private var freshImage = raw
    private var historyImage = raw
    private var motionImage = invalid
    private fun pass(target: RenderToTexture, name: String): Pair<Module, Mesh> {
        val module = Module(parameters(renderer) {
            target.apply(); vertex = VERTEX; fragment = FRAGMENT
            depthTest = false; depthWrite = false; blend = false
            textureResolver = { t ->
                val select = when (t.name) {
                    "freshImage" -> { { freshImage } }
                    "historyImage" -> { { historyImage } }
                    "motionImage" -> { { motionImage } }
                    else -> error("Unknown temporal binding ${t.name}")
                }
                // Resolvers run at pipeline construction. Forward views/layout preparation
                // on every draw so ping-pong history and provider targets actually change.
                val provider = object : HasImageViewArray, PreparesSampledImages {
                    override val imageViewArray get() = select().imageViewArray
                    override fun prepareSampledImagesFor(command: CommandBuffer) =
                        select().prepareSampledImagesFor(command)
                }
                fromViews(provider, t.name, select().defaultSampler!!)
            }
        })
        modules += module
        check(module.valid) { "Diffusion temporal shader failed" }
        val pointer = module.pointer()
        val mesh = Mesh(renderer, pointer).resize(3, 0)
        module.meshes[name] = mesh; target.modules[name] = pointer
        return module to mesh
    }
    private var closed = false
    private val historyPasses: Array<Pair<Module, Mesh>>
    private val displayPass: Pair<Module, Mesh>
    init {
        try {
            historyPasses = arrayOf(pass(history[0], "history-0"), pass(history[1], "history-1"))
            displayPass = pass(output, "reproject-display")
        } catch (e: Throwable) { close(); throw e }
    }
    private var historyIndex = 0
    private var historyFrame: StreamDiffusionFrame? = null
    private var invalidInitialized = false
    var displayReprojection = false
    var blend = 0.0
    var displayRequests = 0L; private set
    var historyRequests = 0L; private set

    fun resolve(command: CommandBuffer, index: Int, current: StreamDiffusionFrame, arrived: StreamDiffusionFrame?) {
        if (!invalidInitialized) { invalid.render(command, index); invalidInitialized = true }
        if (arrived != null) {
            val previous = historyFrame
            val mapping = if (previous != null && previous.generation == arrived.generation && blend > 0) {
                historyRequests++
                checkedMotion(motion.record(command, StreamDiffusionMotionRequest(arrived, previous, StreamDiffusionMotionUse.HISTORY)), source)
            } else null
            val next = 1 - historyIndex
            freshImage = raw; historyImage = if (previous != null) history[historyIndex] else raw
            motionImage = mapping ?: invalid
            historyPasses[next].second.push["mode"] = 0
            historyPasses[next].second.push["amount"] = if (mapping != null) blend.toFloat() else 0f
            history[next].render(command, index)
            historyIndex = next; historyFrame = arrived
        }
        val reference = historyFrame
        val mapping = if (displayReprojection && reference != null) {
            displayRequests++
            checkedMotion(motion.record(command, StreamDiffusionMotionRequest(current, reference, StreamDiffusionMotionUse.DISPLAY)), source)
        } else null
        freshImage = source; historyImage = if (reference != null) history[historyIndex] else raw
        motionImage = mapping ?: invalid
        displayPass.second.push["mode"] = if (mapping != null) 1 else 2
        displayPass.second.push["amount"] = 0f
        output.render(command, index)
    }

    override fun close() {
        if (closed) return
        closed = true
        renderer.cleanup += Runnable {
            modules.forEach { it.cleanup() }
            targets.forEach { target ->
                target.imageViewArray.forEach { it.imageView.cleanup() }
                target.colorImageRefs.forEach { it?.cleanup() }; target.defaultSampler?.cleanup()
            }
        }
    }
    companion object {
        fun checkedMotion(target: RenderToTexture?, source: RenderToTexture): RenderToTexture? {
            if (target == null) return null
            require(target.width == source.width && target.height == source.height && target.colorLayerCount == 1 &&
                target.colorFormats[0] == VK_FORMAT_R32G32B32A32_SFLOAT && target.defaultSampler != null &&
                target.parameters.colorTransferSrc && target.parameters.colorSampled &&
                target.colorImageRefs[0]?.imageData?.sampleCount == VK_SAMPLE_COUNT_1_BIT) {
                "Diffusion motion must be a sampled, transfer-source, single-sample RGBA32F RTT at source resolution"
            }
            return target
        }
        private const val VERTEX = """#version 460
            layout(location=0) out vec2 uv;
            void main() {
                vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                uv=vec2(p.x,1-p.y); gl_Position=vec4(p*2-1,0,1);
            }
        """
        private const val FRAGMENT = """#version 460
            layout(location=0) in vec2 uv;
            layout(set=0,binding=0) uniform sampler2D freshImage;
            layout(set=0,binding=1) uniform sampler2D historyImage;
            layout(set=0,binding=2) uniform sampler2D motionImage;
            layout(push_constant) uniform P { int mode; float amount; } p;
            layout(location=0) out vec4 color;
            void main() {
                if (p.mode==2) { color=texture(historyImage,uv); return; }
                vec4 fresh=texture(freshImage,uv);
                vec4 m=texelFetch(motionImage,ivec2(gl_FragCoord.xy),0);
                bool valid=!any(isnan(m)) && !any(isinf(m)) &&
                    all(greaterThanEqual(m.xy,vec2(0))) && all(lessThanEqual(m.xy,vec2(1)));
                float trust=valid?clamp(m.z,0,1)*(1-clamp(m.w,0,1)):0;
                if (trust<=0) { color=fresh; return; }
                vec4 old=texture(historyImage,m.xy);
                if (p.mode==1) { color=mix(fresh,old,trust); return; }
                // Clamp history to fresh local colours to reduce trails when geometry changes.
                vec3 lo=fresh.rgb, hi=fresh.rgb;
                vec2 texel=1./vec2(textureSize(freshImage,0));
                for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
                    vec3 c=texture(freshImage,uv+vec2(x,y)*texel).rgb;
                    lo=min(lo,c); hi=max(hi,c);
                }
                old.rgb=clamp(old.rgb,lo,hi);
                color=mix(fresh,old,clamp(p.amount,0,1)*trust);
            }
        """
    }
}
