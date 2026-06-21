package afield.four.targets

import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.pointer
import afield.four.texture.HasDefaultTextureSampler
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.util.Window
import org.lwjgl.vulkan.VK10
import kotlin.math.max

/**
 * Multi-pass ping-pong render target.
 *
 * Executes in this order each frame:
 * 1) optional init pass
 * 2) [otherPassCount] "other" passes
 * 3) optional final pass
 *
 * Passes bounce between two internal single-color RenderToTexture targets.
 * [inputImageView] always points at the current read/source image for the active pass,
 * allowing modules to bind it in texture resolvers and sample the previous pass output.
 *
 * For shaders, this class updates mesh push constants (when declared) with:
 * - `passIndex` (0-based over all passes in the frame)
 * - `passCount` (total passes in the frame)
 * - `isInitPass` (1 for init pass, else 0)
 * - `isFinalPass` (1 for final pass, else 0)
 * - `otherPassIndex` (-1 outside other-pass block, otherwise 0..otherPassCount-1)
 */
class MultiPingPongRenderToTexture(
    val renderer: Renderer = fromContext(),
    configure: RTTParameters.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray, HasDefaultTextureSampler {

    enum class PassKind {
        INIT,
        OTHER,
        FINAL,
        NONE
    }

    var parameters = RTTParameters().apply(configure)
    override var defaultSampler: TextureSampler? = null

    fun setDefaultSampler(
        anisotropyEnable: Boolean = true,
        filter: Int = VK10.VK_FILTER_LINEAR,
        mipmapMode: Int = if (filter == VK10.VK_FILTER_LINEAR) VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR else VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST,
        addressModeU: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeV: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    ): TextureSampler {
        val sampler = TextureSampler(
            renderer = renderer,
            mipLevels = parameters.mipLevels,
            anisotropyEnable = anisotropyEnable,
            filter = filter,
            mipmapMode = mipmapMode,
            addressModeU = addressModeU,
            addressModeV = addressModeV,
            addressModeW = addressModeW
        )
        defaultSampler = sampler
        return sampler
    }

    fun repeatU(
        anisotropyEnable: Boolean = true,
        filter: Int = VK10.VK_FILTER_LINEAR,
        mipmapMode: Int = if (filter == VK10.VK_FILTER_LINEAR) VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR else VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST
    ): TextureSampler = setDefaultSampler(
        anisotropyEnable = anisotropyEnable,
        filter = filter,
        mipmapMode = mipmapMode,
        addressModeU = VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT,
        addressModeV = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    )

    init {
        require(parameters.colorAttachments == 1) {
            "MultiPingPongRenderToTexture currently supports colorAttachments == 1"
        }
        require(parameters.externalColorViews.isEmpty()) {
            "MultiPingPongRenderToTexture does not support externalColorViews"
        }
    }

    private val ping = RenderToTexture(renderer) {
        this.width = this@MultiPingPongRenderToTexture.parameters.width
        this.height = this@MultiPingPongRenderToTexture.parameters.height
        this.mipLevels = this@MultiPingPongRenderToTexture.parameters.mipLevels
        this.format = this@MultiPingPongRenderToTexture.parameters.format
        this.colorAttachments = 1
        this.colorFormats = if (this@MultiPingPongRenderToTexture.parameters.colorFormats.isNotEmpty()) listOf(this@MultiPingPongRenderToTexture.parameters.colorFormats.first()) else emptyList()
        this.colorLayerCount = this@MultiPingPongRenderToTexture.parameters.colorLayerCount
        this.forceColorArrayView = this@MultiPingPongRenderToTexture.parameters.forceColorArrayView
        this.viewports = this@MultiPingPongRenderToTexture.parameters.viewports
        this.multiviewViewMask = this@MultiPingPongRenderToTexture.parameters.multiviewViewMask
        this.clearColor = this@MultiPingPongRenderToTexture.parameters.clearColor
        this.clearFirst = this@MultiPingPongRenderToTexture.parameters.clearFirst
        this.colorStoreOp = this@MultiPingPongRenderToTexture.parameters.colorStoreOp
        this.colorSampled = this@MultiPingPongRenderToTexture.parameters.colorSampled
        this.colorTransferSrc = this@MultiPingPongRenderToTexture.parameters.colorTransferSrc
        this.colorTransferDst = this@MultiPingPongRenderToTexture.parameters.colorTransferDst
        this.generateColorMipmaps = this@MultiPingPongRenderToTexture.parameters.generateColorMipmaps
        this.mipBlitFilter = this@MultiPingPongRenderToTexture.parameters.mipBlitFilter
        this.useDepth = false
    }

    private val pong = RenderToTexture(renderer) {
        this.width = this@MultiPingPongRenderToTexture.parameters.width
        this.height = this@MultiPingPongRenderToTexture.parameters.height
        this.mipLevels = this@MultiPingPongRenderToTexture.parameters.mipLevels
        this.format = this@MultiPingPongRenderToTexture.parameters.format
        this.colorAttachments = 1
        this.colorFormats = if (this@MultiPingPongRenderToTexture.parameters.colorFormats.isNotEmpty()) listOf(this@MultiPingPongRenderToTexture.parameters.colorFormats.first()) else emptyList()
        this.colorLayerCount = this@MultiPingPongRenderToTexture.parameters.colorLayerCount
        this.forceColorArrayView = this@MultiPingPongRenderToTexture.parameters.forceColorArrayView
        this.viewports = this@MultiPingPongRenderToTexture.parameters.viewports
        this.multiviewViewMask = this@MultiPingPongRenderToTexture.parameters.multiviewViewMask
        this.clearColor = this@MultiPingPongRenderToTexture.parameters.clearColor
        this.clearFirst = this@MultiPingPongRenderToTexture.parameters.clearFirst
        this.colorStoreOp = this@MultiPingPongRenderToTexture.parameters.colorStoreOp
        this.colorSampled = this@MultiPingPongRenderToTexture.parameters.colorSampled
        this.colorTransferSrc = this@MultiPingPongRenderToTexture.parameters.colorTransferSrc
        this.colorTransferDst = this@MultiPingPongRenderToTexture.parameters.colorTransferDst
        this.generateColorMipmaps = this@MultiPingPongRenderToTexture.parameters.generateColorMipmaps
        this.mipBlitFilter = this@MultiPingPongRenderToTexture.parameters.mipBlitFilter
        this.useDepth = false
    }

    private val targets = listOf(ping, pong)

    private var readIndex = 1
    private var writeIndex = 0
    private var lastWrittenIndex = 0


    var currentPassIndex: Int = -1
        private set

    var currentPassKind: PassKind = PassKind.NONE
        private set

    var otherPassCount: Int = 1
    var runInitPass: Boolean = true
    var runFinalPass: Boolean = true

    val initModules = mutableMapOf<String, ModulePointer>()
    val otherPassModules = mutableMapOf<String, ModulePointer>()
    val finalModules = mutableMapOf<String, ModulePointer>()

    val inputImageView: HasImageView = object : HasImageView, HasDefaultTextureSampler {
        override val imageView: ImageView
            get() = targets[readIndex].imageView
        override var defaultSampler: TextureSampler?
            get() = this@MultiPingPongRenderToTexture.defaultSampler
            set(value) {
                this@MultiPingPongRenderToTexture.defaultSampler = value
            }
    }

    val inputImageViewArray: HasImageViewArray = object : HasImageViewArray, HasDefaultTextureSampler {
        override val imageViewArray: List<HasImageView>
            get() = listOf(inputImageView)
        override var defaultSampler: TextureSampler?
            get() = this@MultiPingPongRenderToTexture.defaultSampler
            set(value) {
                this@MultiPingPongRenderToTexture.defaultSampler = value
            }

    }

    val writeImageView: HasImageView = object : HasImageView, HasDefaultTextureSampler {
        override val imageView: ImageView
            get() = targets[writeIndex].imageView
        override var defaultSampler: TextureSampler?
            get() = this@MultiPingPongRenderToTexture.defaultSampler
            set(value) {
                this@MultiPingPongRenderToTexture.defaultSampler = value
            }
    }

    override val imageView: ImageView
        get() = targets[lastWrittenIndex].imageView

    override val imageViewArray: List<HasImageView>
        get() = listOf(this)

    /**
     * Matches [RenderToTexture.blitToScreen], delegating to whichever internal target
     * contains the final output for the configured pass count parity.
     */
    fun blitToScreen(
        renderer: Renderer = this.renderer,
        target: RenderTarget = renderer.renderList,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: RenderToTexture.ColorGrade.() -> Unit = {}
    ): ModulePointer {
        require(attachment == 0) { "MultiPingPongRenderToTexture only supports attachment 0" }
        val outputTarget = targetForConfiguredPassParity()
        return outputTarget.blitToScreen(
            renderer = renderer,
            target = target,
            attachment = 0,
            name = name,
            viewport = viewport,
            sourceRect = sourceRect,
            blend = blend,
            additive = additive,
            sourceLayer = sourceLayer,
            warper = warper,
            numSegmentsW = numSegmentsW,
            numSegmentsH = numSegmentsH,
            configure = configure
        )
    }

    fun blitToScreen(
        windowIndex: Int,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: RenderToTexture.ColorGrade.() -> Unit = {}
    ): ModulePointer = blitToScreen(
        renderer = renderer,
        target = renderer.renderTargetFor(windowIndex),
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun blitToScreen(
        window: Window,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: RenderToTexture.ColorGrade.() -> Unit = {}
    ): ModulePointer = blitToScreen(
        windowIndex = renderer.windowIndex(window),
        renderer = renderer,
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun debugBlitToScreen(
        renderer: Renderer = this.renderer,
        target: RenderTarget = renderer.renderList,
        attachment: Int = 0,
        name: String = "rtt-debug-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: RenderToTexture.ColorGrade.() -> Unit = {}
    ): ModulePointer = blitToScreen(
        renderer = renderer,
        target = target,
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun debugBlitToScreen(
        windowIndex: Int,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-debug-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: RenderToTexture.ColorGrade.() -> Unit = {}
    ): ModulePointer = debugBlitToScreen(
        renderer = renderer,
        target = renderer.renderTargetFor(windowIndex),
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun debugBlitToScreen(
        window: Window,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-debug-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: RenderToTexture.ColorGrade.() -> Unit = {}
    ): ModulePointer = debugBlitToScreen(
        windowIndex = renderer.windowIndex(window),
        renderer = renderer,
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun addInit(name: String, module: ModulePointer) {
        validateSingleColorModule(module.current)
        initModules[name] = module
    }

    fun addInit(name: String, module: Module) {
        addInit(name, module.pointer())
    }

    fun addOther(name: String, module: ModulePointer) {
        validateSingleColorModule(module.current)
        otherPassModules[name] = module
    }

    fun addOther(name: String, module: Module) {
        addOther(name, module.pointer())
    }

    fun addFinal(name: String, module: ModulePointer) {
        validateSingleColorModule(module.current)
        finalModules[name] = module
    }

    fun addFinal(name: String, module: Module) {
        addFinal(name, module.pointer())
    }

    @JvmName("plusAssign_other")
    operator fun plusAssign(m: Pair<String, ModulePointer>) {
        addOther(m.first, m.second)
    }

    @JvmName("plusAssign_other_module")
    operator fun plusAssign(m: Pair<String, Module>) {
        addOther(m.first, m.second)
    }

    override fun render(command: CommandBuffer, index: Int) {
        resetPassStateForFrame()

        val stableOtherCount = max(0, otherPassCount)
        val totalPasses = (if (runInitPass) 1 else 0) + stableOtherCount + (if (runFinalPass) 1 else 0)

        if (totalPasses == 0) {
            currentPassIndex = -1
            currentPassKind = PassKind.NONE
            return
        }

        var passCursor = 0

        if (runInitPass) {
            runPass(
                kind = PassKind.INIT,
                passIndex = passCursor,
                passCount = totalPasses,
                otherIndex = -1,
                modules = initModules,
                command = command,
                swapchainIndex = index
            )
            passCursor++
        }

        for (i in 0 until stableOtherCount) {
//            println(" -- pass ${passCursor}")
            runPass(
                kind = PassKind.OTHER,
                passIndex = passCursor,
                passCount = totalPasses,
                otherIndex = i,
                modules = otherPassModules,
                command = command,
                swapchainIndex = index
            )
            passCursor++
        }

        if (runFinalPass) {
            runPass(
                kind = PassKind.FINAL,
                passIndex = passCursor,
                passCount = totalPasses,
                otherIndex = -1,
                modules = finalModules,
                command = command,
                swapchainIndex = index
            )
            passCursor++
        }

        currentPassIndex = -1
        currentPassKind = PassKind.NONE
    }

    private fun runPass(
        kind: PassKind,
        passIndex: Int,
        passCount: Int,
        otherIndex: Int,
        modules: Map<String, ModulePointer>,
        command: CommandBuffer,
        swapchainIndex: Int
    ) {
        currentPassIndex = passIndex
        currentPassKind = kind

        val target = targets[writeIndex]
        target.modules.clear()
        target.modules.putAll(modules)

        updatePassPushConstants(
            modules = modules,
            passIndex = passIndex,
            passCount = passCount,
            isInit = kind == PassKind.INIT,
            isFinal = kind == PassKind.FINAL,
            otherIndex = otherIndex
        )

        target.render(command, swapchainIndex)
        lastWrittenIndex = writeIndex
        swap()
    }

    private fun swap() {
        val t = readIndex
        readIndex = writeIndex
        writeIndex = t
    }

    private fun resetPassStateForFrame() {
        // Keep a deterministic start orientation per frame so output parity is stable and
        // blit selection based on pass count does not flicker frame-to-frame.
        readIndex = 1
        writeIndex = 0
    }

    private fun updatePassPushConstants(
        modules: Map<String, ModulePointer>,
        passIndex: Int,
        passCount: Int,
        isInit: Boolean,
        isFinal: Boolean,
        otherIndex: Int
    ) {
        modules.values.forEach { modulePointer ->
            modulePointer.current.meshes.values.forEach { mesh ->
                if (mesh.push.offsets.contains("passIndex")) mesh.push["passIndex"] = passIndex
                if (mesh.push.offsets.contains("passCount")) mesh.push["passCount"] = passCount
                if (mesh.push.offsets.contains("isInitPass"))mesh.push["isInitPass"] = if (isInit) 1 else 0
                if (mesh.push.offsets.contains("isFinalPass"))mesh.push["isFinalPass"] = if (isFinal) 1 else 0
                if (mesh.push.offsets.contains("otherPassIndex"))mesh.push["otherPassIndex"] = otherIndex
            }
        }
    }

    private fun validateSingleColorModule(module: Module) {
        val moduleColorCount = if (module.parameters.colorFormats.isNotEmpty()) {
            module.parameters.colorFormats.size
        } else {
            module.parameters.color
        }
        require(moduleColorCount == 1) {
            "MultiPingPongRenderToTexture expects modules with exactly 1 color attachment, but module '${module.parameters.name}' declares $moduleColorCount"
        }

        val expectedFormat = if (parameters.colorFormats.isNotEmpty()) parameters.colorFormats.first() else parameters.format
        if (module.parameters.colorFormats.isNotEmpty()) {
            require(module.parameters.colorFormats.first() == expectedFormat) {
                "MultiPingPongRenderToTexture color format mismatch for module '${module.parameters.name}': module=${module.parameters.colorFormats.first()} target=$expectedFormat. Call jfa.apply() inside module parameters."
            }
        }
    }

    override fun toString(): String {
        return "MultiPingPongRenderToTexture[${parameters.width}x${parameters.height}, otherPassCount=$otherPassCount, init=$runInitPass, final=$runFinalPass]"
    }

    private fun targetForConfiguredPassParity(): RenderToTexture {
        val stableOtherCount = max(0, otherPassCount)
        val totalPasses = (if (runInitPass) 1 else 0) + stableOtherCount + (if (runFinalPass) 1 else 0)
        if (totalPasses <= 0) return targets[lastWrittenIndex]
        val outputIndex = if ((totalPasses and 1) == 1) 0 else 1
        return targets[outputIndex]
    }

    companion object {
        fun jumpFloodOtherPassCount(width: Int, height: Int): Int {
            val m = max(width, height).coerceAtLeast(1)
            val maxPow2Ceil = highestPowerOfTwoCeil(m)
            var step = maxPow2Ceil / 2
            var count = 0
            while (step >= 1) {
                count++
                step /= 2
            }
            return count
        }

        private fun highestPowerOfTwoCeil(value: Int): Int {
            var v = 1
            while (v < value) v = v shl 1
            return v
        }
    }
}
