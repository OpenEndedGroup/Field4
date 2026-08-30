package afield.four.imgui

import afield.Generator
import afield.four.Renderer
import afield.four.buffers.VertexBuffer
import afield.four.device.CommandBuffer
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.targets.RTTParameters
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageViewArray
import afield.four.texture.StreamingTexture
import afield.four.texture.TextureSampler
import afield.four.util.MapOfGenerators
import afield.four.util.Window
import afield.stdlib.f
import imgui.*
import imgui.ImGui.text
import imgui.flag.ImGuiKey
import imgui.internal.ImGuiContext
import imgui.type.ImInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import java.net.URI
import java.nio.ByteOrder
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Minimal imgui-java renderer that draws Dear ImGui into a Field [RenderToTexture].
 *
 * Notes / limitations (intentional for a first step):
 * - Renders using Field's vertex-buffer convention (one buffer per attribute).
 * - Supports ImGui clip rects via dynamic scissor.
 * - Currently binds a single texture (the font atlas); additional texture IDs are ignored.
 */
class ImGuiRenderToTexture(
    renderer: Renderer = fromContext(),
    private val window: Window? = renderer.window,
    parameters: RTTParameters.() -> Unit = {},
    /**
     * Supersample the font atlas: scales the rasterization size and keeps on-screen size stable via FontGlobalScale.
     */
    var fontSupersample: Float = 4f,
    /**
     * If true, converts the current style colors to grayscale for a monochrome look.
     */
    var monochromeTheme: Boolean = true,
    /**
     * When true, feeds window mouse/scroll into ImGui when no pointer override is provided.
     * Set false when you inject pointers manually (e.g., from a stereo controller).
     */
    var useWindowMouseEvents: Boolean = true,
    /**
     * Optional UI scale applied via DisplayFramebufferScale (acts like supersampling for layout).
     */
    var displayScale: Float = 1f,
    var ui: (ImGuiIO) -> Unit = {},
) : RenderToTexture(
    renderer,
    parameters = {
        useDepth = false
        format = VK_FORMAT_B8G8R8A8_SRGB
        parameters()
    }
) {
    private val context: ImGuiContext = ImGui.createContext()
    private val fontTexId = 1L

    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var fontTexture: StreamingTexture
    private val fontSampler: TextureSampler
    private val module: Module
    @Volatile private var pendingFont: PendingFont? = null
    private val uiGenerators = MapOfGenerators<ImGuiIO, Unit>()
    @Volatile private var pointerOverride: PointerOverride? = null
    private val pendingKeyEvents = ArrayDeque<KeyEvent>()
    private val pendingChars = ArrayDeque<Int>()
    private var removeKeyListener: (() -> Unit)? = null
    private var removeCharListener: (() -> Unit)? = null
    private var modCtrl = false
    private var modShift = false
    private var modAlt = false
    private var modSuper = false

    private var lastFrameNs = System.nanoTime()
    private var drawData: ImDrawData? = null

    private var vertexCapacity = 0
    private var indexCapacity = 0

    private var posBuffer: VertexBuffer? = null
    private var uvBuffer: VertexBuffer? = null
    private var colorBuffer: VertexBuffer? = null
    private var indexBuffer: VertexBuffer? = null

    init {
        val prev = ImGui.getCurrentContext()
        ImGui.setCurrentContext(context)
        try {
            val io = ImGui.getIO()
            io.setIniFilename(null)
            io.setBackendPlatformName("field-glfw")
            io.setBackendRendererName("field-vulkan-rtt")
            io.setBackendUsingLegacyKeyArrays(0.toShort())
            io.setBackendUsingLegacyNavInputArray(false)
            io.setDisplayFramebufferScale(1f, 1f)
            ImGui.styleColorsDark()
            if (monochromeTheme) applyMonochromeStyle()
            applyTightSpacing()

            addLegacyUi("default-ui", ui)

            fontTexture = rebuildFontTexture(fontData = null, sizePixels = DEFAULT_FONT_SIZE)
            fontSampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
            val provider = object : HasImageViewArray {
                override val imageViewArray get() = listOf(fontTexture)
            }

            module = Module(
                parameters(renderer) {
                    name = "imgui-rtt"
                    colorFormats = this@ImGuiRenderToTexture.colorFormats
                    color = colorFormats.size
                    depth = 0
                    depthTest = false
                    blend = true
                    vertex = imguiVertexShader
                    fragment = imguiFragmentShader
                    // For now we only support the built-in font atlas texture. Be permissive about the name to avoid
                    // reflection/name-mangling surprises (a common "blank output" failure mode).
                    textureResolver = { _ -> provider to fontSampler }
                }
            )
        } finally {
            ImGui.setCurrentContext(prev)
        }

        installInputCallbacks()
    }

    /**
     * Convenience: make sure this RTT is rendered every frame (as a preTarget) and blitted to the screen.
     *
     * The most common "it's blank" failure mode is calling [blitToScreen] but never actually adding
     * this [Target] into the render graph. This helper does both.
     */
    fun attachTo(
        target: RenderTarget = renderer.renderList,
        name: String = "imgui-rtt",
        blitName: String = "$name-blit",
    ): ModulePointer {
        target.preTargets[name] = this
        // No sourceRect: this used to pass Rect(0.0, 1.0, 1.0, -1.0) to cancel a vertical mirror in
        // RenderToTexture.blitVertexShader. That shader now samples texel-aligned, so the identity
        // crop is the correct one and a flip here would re-invert the panel.
        return blitToScreen(renderer = renderer, target = target, name = blitName, blend = true)
    }

    fun cleanup() {
        posBuffer?.cleanup()
        uvBuffer?.cleanup()
        colorBuffer?.cleanup()
        indexBuffer?.cleanup()
        fontSampler.cleanup()
        fontTexture.cleanup()
        module.cleanup()
        removeKeyListener?.invoke()
        removeKeyListener = null
        removeCharListener?.invoke()
        removeCharListener = null

        val prev = ImGui.getCurrentContext()
        ImGui.setCurrentContext(context)
        try {
            ImGui.destroyContext(context)
        } finally {
            ImGui.setCurrentContext(prev)
        }
    }

    override fun prepareAdditionalResources(command: CommandBuffer, index: Int) {
        val prev = ImGui.getCurrentContext()
        ImGui.setCurrentContext(context)
        try {
            val io = ImGui.getIO()
            pendingFont?.let { pf ->
                rebuildFontTexture(pf.bytes, pf.sizePixels)
                pendingFont = null
            }
            updateIo(io)

            ImGui.newFrame()
            uiGenerators.update(io)
            ImGui.render()

            val dd = ImGui.getDrawData()
            drawData = dd
            uploadDrawData(dd, command)
        } finally {
            ImGui.setCurrentContext(prev)
        }
    }

    override fun renderAdditional(command: CommandBuffer, index: Int, stack: MemoryStack) {
        val dd = drawData ?: return
        if (!dd.getValid()) return
        if (dd.getTotalVtxCount() <= 0 || dd.getTotalIdxCount() <= 0) return
        if (!module.valid) return

        val pos = posBuffer ?: return
        val uv = uvBuffer ?: return
        val col = colorBuffer ?: return
        val idx = indexBuffer ?: return

        val cmd = command.vkCommandBuffer

        // Ensure a predictable viewport for ImGui (Field flips Y via negative viewport height).
        val viewport = VkViewport.calloc(1, stack)
        viewport[0]
            .x(0f)
            .y(height.toFloat())
            .width(width.toFloat())
            .height(-height.toFloat())
            .minDepth(0f)
            .maxDepth(1f)
        vkCmdSetViewport(cmd, 0, viewport)

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, module.pipeline)
        vkCmdSetDepthTestEnable(cmd, false)
        if (renderer.device.supportsExtendedDynamicState) {
            vkCmdSetPrimitiveTopology(cmd, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            vkCmdSetCullMode(cmd, VK_CULL_MODE_NONE)
            vkCmdSetFrontFace(cmd, VK_FRONT_FACE_CLOCKWISE)
        }
        if (renderer.device.supportsExtendedDynamicState3) {
            EXTExtendedDynamicState3.vkCmdSetDepthClampEnableEXT(cmd, false)
        }

        // Bind descriptors (font texture)
        module.uniforms.values.forEach { upd ->
            upd.pushDescriptorSet(command, module.pipelineLayout, VK_PIPELINE_BIND_POINT_GRAPHICS)
        }

        // Push constants: map ImGui coordinates (origin top-left) into Field's flipped viewport (OpenGL-style NDC).
        val displayW = dd.getDisplaySizeX()
        val displayH = dd.getDisplaySizeY()
        if (displayW <= 0f || displayH <= 0f) return

        val scaleX = 2f / displayW
        val scaleY = -2f / displayH
        val translateX = -1f - dd.getDisplayPosX() * scaleX
        val translateY = 1f - dd.getDisplayPosY() * scaleY

        val pc = stack.malloc(16)
        pc.putFloat(0, scaleX)
        pc.putFloat(4, scaleY)
        pc.putFloat(8, translateX)
        pc.putFloat(12, translateY)
        vkCmdPushConstants(
            cmd,
            module.pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            pc
        )

        // Vertex/index buffers
        run {
            val inputs = module.vi.inputs
            require(inputs.size == 3) { "Unexpected ImGui vertex input count ${inputs.size} (expected 3)" }

            val vb = stack.mallocLong(inputs.size)
            val offs = stack.mallocLong(inputs.size)
            inputs.forEachIndexed { bindingIndex, input ->
                val handle = when (input.name) {
                    "inPosition" -> pos.dest.buffer
                    "inUV" -> uv.dest.buffer
                    "inColor" -> col.dest.buffer
                    else -> error("Unexpected ImGui vertex input '${input.name}'")
                }
                vb.put(bindingIndex, handle)
                offs.put(bindingIndex, 0L)
            }
            vkCmdBindVertexBuffers(cmd, 0, vb, offs)
        }
        vkCmdBindIndexBuffer(cmd, idx.dest.buffer, 0L, VK_INDEX_TYPE_UINT32)

        val clipOffX = dd.getDisplayPosX()
        val clipOffY = dd.getDisplayPosY()
        val clipScaleX = dd.getFramebufferScaleX()
        val clipScaleY = dd.getFramebufferScaleY()

        val fbW = (dd.getDisplaySizeX() * clipScaleX).toInt()
        val fbH = (dd.getDisplaySizeY() * clipScaleY).toInt()
        if (fbW <= 0 || fbH <= 0) return

        val scissor = VkRect2D.calloc(1, stack)
        val scissorOffset = scissor[0].offset()
        val scissorExtent = scissor[0].extent()

        var globalVtxOffset = 0
        var globalIdxOffset = 0

        val cmdLists = dd.getCmdListsCount()
        for (listIndex in 0 until cmdLists) {
            val cmdCount = dd.getCmdListCmdBufferSize(listIndex)
            for (cmdIndex in 0 until cmdCount) {
                val elemCount = dd.getCmdListCmdBufferElemCount(listIndex, cmdIndex)
                if (elemCount <= 0) continue

                // Texture IDs other than the font atlas are currently ignored.
                // val textureId = dd.getCmdListCmdBufferTextureId(listIndex, cmdIndex)

                val clip = dd.getCmdListCmdBufferClipRect(listIndex, cmdIndex)
                val clipMinX = (clip.x - clipOffX) * clipScaleX
                val clipMinY = (clip.y - clipOffY) * clipScaleY
                val clipMaxX = (clip.z - clipOffX) * clipScaleX
                val clipMaxY = (clip.w - clipOffY) * clipScaleY

                if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) continue

                val x0 = clipMinX.toInt()
                val y0 = clipMinY.toInt()
                val x1 = clipMaxX.toInt()
                val y1 = clipMaxY.toInt()

                val sx = x0.coerceIn(0, fbW)
                val sy = y0.coerceIn(0, fbH)
                val sw = (x1 - x0).coerceIn(0, fbW - sx)
                val sh = (y1 - y0).coerceIn(0, fbH - sy)
                if (sw == 0 || sh == 0) continue

                scissorOffset.x(sx).y(sy)
                scissorExtent.width(sw).height(sh)
                vkCmdSetScissor(cmd, 0, scissor)

                val firstIndex = globalIdxOffset + dd.getCmdListCmdBufferIdxOffset(listIndex, cmdIndex)
                val baseVertex = globalVtxOffset + dd.getCmdListCmdBufferVtxOffset(listIndex, cmdIndex)
                vkCmdDrawIndexed(cmd, elemCount, 1, firstIndex, baseVertex, 0)
            }

            globalVtxOffset += dd.getCmdListVtxBufferSize(listIndex)
            globalIdxOffset += dd.getCmdListIdxBufferSize(listIndex)
        }
    }

    private fun updateIo(io: ImGuiIO) {
        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastFrameNs).coerceAtLeast(1L) / 1e9).toFloat()
        lastFrameNs = nowNs

        io.setDeltaTime(dt)
        flushInputEvents(io)

        if (width <= 0 || height <= 0) return

        val fbScaleX = displayScale
        val fbScaleY = displayScale

        // ImGui logical size == RTT size; framebuffer scale is an optional global scale.
        io.setDisplaySize(width.toFloat()*fbScaleX, height.toFloat()*fbScaleY)
        io.setDisplayFramebufferScale(fbScaleX, fbScaleY)

        var skipWindowMouse = false
        pointerOverride?.let { override ->
            pointerOverride = null
            io.addMousePosEvent(override.x / fbScaleX, override.y / fbScaleY)
            io.addMouseButtonEvent(0, override.down)
            if (override.scrollY != 0f) {
                io.addMouseWheelEvent(0f, override.scrollY)
            }
            skipWindowMouse = true
        }

        if (useWindowMouseEvents && !skipWindowMouse) {
            val w = window ?: return
            val cursor = w.cursor
            val winW = w.width.coerceAtLeast(1)
            val winH = w.height.coerceAtLeast(1)
            val u = (cursor.x / winW.toDouble()).coerceIn(0.0, 1.0)
            val v = (cursor.y / winH.toDouble()).coerceIn(0.0, 1.0)
            io.addMousePosEvent(
                (u * width / fbScaleX * 2).toFloat(),
                (v * height / fbScaleY * 2).toFloat()
            )

            io.addMouseButtonEvent(0, w.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT))
            io.addMouseButtonEvent(1, w.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
            io.addMouseButtonEvent(2, w.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE))

            val scroll = w.consumeScrollDelta()
            if (scroll.x != 0f || scroll.y != 0f) {
                io.addMouseWheelEvent(scroll.x, scroll.y)
            }
        }
    }

    private fun flushInputEvents(io: ImGuiIO) {
        while (pendingKeyEvents.isNotEmpty()) {
            val ev = pendingKeyEvents.removeFirst()
            if (ev.nativeKey >= 0) {
                io.setKeyEventNativeData(ev.imguiKey, ev.nativeKey, ev.nativeScancode)
            }
            io.addKeyEvent(ev.imguiKey, ev.down)
        }
        while (pendingChars.isNotEmpty()) {
            io.addInputCharacter(pendingChars.removeFirst())
        }
    }

    private fun installInputCallbacks() {
        val w = window ?: return
        removeKeyListener = w.addKeyListener { key, scancode, action, mods ->
            val imguiKey = glfwKeyToImGuiKey(key)
            if (imguiKey != ImGuiKey.None) {
                val down = action != GLFW.GLFW_RELEASE
                pendingKeyEvents.add(KeyEvent(imguiKey, down, key, scancode))
            }
            updateModKeys(mods)
        }
        removeCharListener = w.addCharListener { codepoint ->
            pendingChars.add(codepoint)
        }
    }

    private fun updateModKeys(mods: Int) {
        val ctrl = mods and GLFW.GLFW_MOD_CONTROL != 0
        val shift = mods and GLFW.GLFW_MOD_SHIFT != 0
        val alt = mods and GLFW.GLFW_MOD_ALT != 0
        val sup = mods and GLFW.GLFW_MOD_SUPER != 0
        if (ctrl != modCtrl) {
            modCtrl = ctrl
            pendingKeyEvents.add(KeyEvent(ImGuiKey.ModCtrl, ctrl, -1, -1))
        }
        if (shift != modShift) {
            modShift = shift
            pendingKeyEvents.add(KeyEvent(ImGuiKey.ModShift, shift, -1, -1))
        }
        if (alt != modAlt) {
            modAlt = alt
            pendingKeyEvents.add(KeyEvent(ImGuiKey.ModAlt, alt, -1, -1))
        }
        if (sup != modSuper) {
            modSuper = sup
            pendingKeyEvents.add(KeyEvent(ImGuiKey.ModSuper, sup, -1, -1))
        }
    }

    private fun glfwKeyToImGuiKey(key: Int): Int = when (key) {
        GLFW.GLFW_KEY_TAB -> ImGuiKey.Tab
        GLFW.GLFW_KEY_LEFT -> ImGuiKey.LeftArrow
        GLFW.GLFW_KEY_RIGHT -> ImGuiKey.RightArrow
        GLFW.GLFW_KEY_UP -> ImGuiKey.UpArrow
        GLFW.GLFW_KEY_DOWN -> ImGuiKey.DownArrow
        GLFW.GLFW_KEY_PAGE_UP -> ImGuiKey.PageUp
        GLFW.GLFW_KEY_PAGE_DOWN -> ImGuiKey.PageDown
        GLFW.GLFW_KEY_HOME -> ImGuiKey.Home
        GLFW.GLFW_KEY_END -> ImGuiKey.End
        GLFW.GLFW_KEY_INSERT -> ImGuiKey.Insert
        GLFW.GLFW_KEY_DELETE -> ImGuiKey.Delete
        GLFW.GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace
        GLFW.GLFW_KEY_SPACE -> ImGuiKey.Space
        GLFW.GLFW_KEY_ENTER -> ImGuiKey.Enter
        GLFW.GLFW_KEY_ESCAPE -> ImGuiKey.Escape
        GLFW.GLFW_KEY_APOSTROPHE -> ImGuiKey.Apostrophe
        GLFW.GLFW_KEY_COMMA -> ImGuiKey.Comma
        GLFW.GLFW_KEY_MINUS -> ImGuiKey.Minus
        GLFW.GLFW_KEY_PERIOD -> ImGuiKey.Period
        GLFW.GLFW_KEY_SLASH -> ImGuiKey.Slash
        GLFW.GLFW_KEY_SEMICOLON -> ImGuiKey.Semicolon
        GLFW.GLFW_KEY_EQUAL -> ImGuiKey.Equal
        GLFW.GLFW_KEY_LEFT_BRACKET -> ImGuiKey.LeftBracket
        GLFW.GLFW_KEY_BACKSLASH -> ImGuiKey.Backslash
        GLFW.GLFW_KEY_RIGHT_BRACKET -> ImGuiKey.RightBracket
        GLFW.GLFW_KEY_GRAVE_ACCENT -> ImGuiKey.GraveAccent
        GLFW.GLFW_KEY_CAPS_LOCK -> ImGuiKey.CapsLock
        GLFW.GLFW_KEY_SCROLL_LOCK -> ImGuiKey.ScrollLock
        GLFW.GLFW_KEY_NUM_LOCK -> ImGuiKey.NumLock
        GLFW.GLFW_KEY_PRINT_SCREEN -> ImGuiKey.PrintScreen
        GLFW.GLFW_KEY_PAUSE -> ImGuiKey.Pause
        GLFW.GLFW_KEY_KP_0 -> ImGuiKey.Keypad0
        GLFW.GLFW_KEY_KP_1 -> ImGuiKey.Keypad1
        GLFW.GLFW_KEY_KP_2 -> ImGuiKey.Keypad2
        GLFW.GLFW_KEY_KP_3 -> ImGuiKey.Keypad3
        GLFW.GLFW_KEY_KP_4 -> ImGuiKey.Keypad4
        GLFW.GLFW_KEY_KP_5 -> ImGuiKey.Keypad5
        GLFW.GLFW_KEY_KP_6 -> ImGuiKey.Keypad6
        GLFW.GLFW_KEY_KP_7 -> ImGuiKey.Keypad7
        GLFW.GLFW_KEY_KP_8 -> ImGuiKey.Keypad8
        GLFW.GLFW_KEY_KP_9 -> ImGuiKey.Keypad9
        GLFW.GLFW_KEY_KP_DECIMAL -> ImGuiKey.KeypadDecimal
        GLFW.GLFW_KEY_KP_DIVIDE -> ImGuiKey.KeypadDivide
        GLFW.GLFW_KEY_KP_MULTIPLY -> ImGuiKey.KeypadMultiply
        GLFW.GLFW_KEY_KP_SUBTRACT -> ImGuiKey.KeypadSubtract
        GLFW.GLFW_KEY_KP_ADD -> ImGuiKey.KeypadAdd
        GLFW.GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter
        GLFW.GLFW_KEY_KP_EQUAL -> ImGuiKey.KeypadEqual
        GLFW.GLFW_KEY_LEFT_SHIFT -> ImGuiKey.LeftShift
        GLFW.GLFW_KEY_LEFT_CONTROL -> ImGuiKey.LeftCtrl
        GLFW.GLFW_KEY_LEFT_ALT -> ImGuiKey.LeftAlt
        GLFW.GLFW_KEY_LEFT_SUPER -> ImGuiKey.LeftSuper
        GLFW.GLFW_KEY_RIGHT_SHIFT -> ImGuiKey.RightShift
        GLFW.GLFW_KEY_RIGHT_CONTROL -> ImGuiKey.RightCtrl
        GLFW.GLFW_KEY_RIGHT_ALT -> ImGuiKey.RightAlt
        GLFW.GLFW_KEY_RIGHT_SUPER -> ImGuiKey.RightSuper
        GLFW.GLFW_KEY_MENU -> ImGuiKey.Menu
        GLFW.GLFW_KEY_0 -> ImGuiKey._0
        GLFW.GLFW_KEY_1 -> ImGuiKey._1
        GLFW.GLFW_KEY_2 -> ImGuiKey._2
        GLFW.GLFW_KEY_3 -> ImGuiKey._3
        GLFW.GLFW_KEY_4 -> ImGuiKey._4
        GLFW.GLFW_KEY_5 -> ImGuiKey._5
        GLFW.GLFW_KEY_6 -> ImGuiKey._6
        GLFW.GLFW_KEY_7 -> ImGuiKey._7
        GLFW.GLFW_KEY_8 -> ImGuiKey._8
        GLFW.GLFW_KEY_9 -> ImGuiKey._9
        GLFW.GLFW_KEY_A -> ImGuiKey.A
        GLFW.GLFW_KEY_B -> ImGuiKey.B
        GLFW.GLFW_KEY_C -> ImGuiKey.C
        GLFW.GLFW_KEY_D -> ImGuiKey.D
        GLFW.GLFW_KEY_E -> ImGuiKey.E
        GLFW.GLFW_KEY_F -> ImGuiKey.F
        GLFW.GLFW_KEY_G -> ImGuiKey.G
        GLFW.GLFW_KEY_H -> ImGuiKey.H
        GLFW.GLFW_KEY_I -> ImGuiKey.I
        GLFW.GLFW_KEY_J -> ImGuiKey.J
        GLFW.GLFW_KEY_K -> ImGuiKey.K
        GLFW.GLFW_KEY_L -> ImGuiKey.L
        GLFW.GLFW_KEY_M -> ImGuiKey.M
        GLFW.GLFW_KEY_N -> ImGuiKey.N
        GLFW.GLFW_KEY_O -> ImGuiKey.O
        GLFW.GLFW_KEY_P -> ImGuiKey.P
        GLFW.GLFW_KEY_Q -> ImGuiKey.Q
        GLFW.GLFW_KEY_R -> ImGuiKey.R
        GLFW.GLFW_KEY_S -> ImGuiKey.S
        GLFW.GLFW_KEY_T -> ImGuiKey.T
        GLFW.GLFW_KEY_U -> ImGuiKey.U
        GLFW.GLFW_KEY_V -> ImGuiKey.V
        GLFW.GLFW_KEY_W -> ImGuiKey.W
        GLFW.GLFW_KEY_X -> ImGuiKey.X
        GLFW.GLFW_KEY_Y -> ImGuiKey.Y
        GLFW.GLFW_KEY_Z -> ImGuiKey.Z
        GLFW.GLFW_KEY_F1 -> ImGuiKey.F1
        GLFW.GLFW_KEY_F2 -> ImGuiKey.F2
        GLFW.GLFW_KEY_F3 -> ImGuiKey.F3
        GLFW.GLFW_KEY_F4 -> ImGuiKey.F4
        GLFW.GLFW_KEY_F5 -> ImGuiKey.F5
        GLFW.GLFW_KEY_F6 -> ImGuiKey.F6
        GLFW.GLFW_KEY_F7 -> ImGuiKey.F7
        GLFW.GLFW_KEY_F8 -> ImGuiKey.F8
        GLFW.GLFW_KEY_F9 -> ImGuiKey.F9
        GLFW.GLFW_KEY_F10 -> ImGuiKey.F10
        GLFW.GLFW_KEY_F11 -> ImGuiKey.F11
        GLFW.GLFW_KEY_F12 -> ImGuiKey.F12
        GLFW.GLFW_KEY_F13 -> ImGuiKey.F13
        GLFW.GLFW_KEY_F14 -> ImGuiKey.F14
        GLFW.GLFW_KEY_F15 -> ImGuiKey.F15
        GLFW.GLFW_KEY_F16 -> ImGuiKey.F16
        GLFW.GLFW_KEY_F17 -> ImGuiKey.F17
        GLFW.GLFW_KEY_F18 -> ImGuiKey.F18
        GLFW.GLFW_KEY_F19 -> ImGuiKey.F19
        GLFW.GLFW_KEY_F20 -> ImGuiKey.F20
        GLFW.GLFW_KEY_F21 -> ImGuiKey.F21
        GLFW.GLFW_KEY_F22 -> ImGuiKey.F22
        GLFW.GLFW_KEY_F23 -> ImGuiKey.F23
        GLFW.GLFW_KEY_F24 -> ImGuiKey.F24
        else -> ImGuiKey.None
    }

    private fun estimateContentScale(): Float {
        val w = window ?: return 1f
        val winW = IntArray(1)
        val winH = IntArray(1)
        val fbW = IntArray(1)
        val fbH = IntArray(1)
        GLFW.glfwGetWindowSize(w.windowHandle, winW, winH)
        GLFW.glfwGetFramebufferSize(w.windowHandle, fbW, fbH)
        val logicalW = winW[0].coerceAtLeast(1).toFloat()
        val logicalH = winH[0].coerceAtLeast(1).toFloat()
        val scaleX = fbW[0].toFloat() / logicalW
        val scaleY = fbH[0].toFloat() / logicalH
        return max(1f, max(scaleX, scaleY))
    }

    /**
     * Download a TTF font asynchronously and use it once ready.
     */
    fun loadFontFromUrlAsync(url: String, sizePixels: Float = DEFAULT_FONT_SIZE, onFinished: (Boolean) -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = URI(url).toURL().readBytes()
                pendingFont = PendingFont(bytes, sizePixels)
                onFinished(true)
            } catch (_: Exception) {
                onFinished(false)
            }
        }
    }

    /**
     * Load a TTF font from application resources (classpath).
     */
    fun loadFontFromResource(resourcePath: String, sizePixels: Float = DEFAULT_FONT_SIZE, onFinished: (Boolean) -> Unit = {}) {
        try {
            val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
                ?: javaClass.classLoader.getResourceAsStream("/$resourcePath")
                ?: error("Resource not found: $resourcePath")
            stream.use { pendingFont = PendingFont(it.readBytes(), sizePixels) }
            onFinished(true)
        } catch (_: Exception) {
            onFinished(false)
        }
    }

    /**
     * Load a TTF font from an in-memory byte array immediately (next frame).
     */
    fun loadFontFromBytes(bytes: ByteArray, sizePixels: Float = DEFAULT_FONT_SIZE) {
        pendingFont = PendingFont(bytes, sizePixels)
    }

    private fun rebuildFontTexture(fontData: ByteArray?, sizePixels: Float): StreamingTexture {
        val io = ImGui.getIO()
        val fonts = io.getFonts()
        val fontScale = estimateContentScale()
        val superSample = fontSupersample.coerceAtLeast(1f)
        io.setFontGlobalScale(1f / (fontScale * superSample))

        fonts.clear()
        val fontConfig = ImFontConfig()
        try {
            fontConfig.setSizePixels(sizePixels * fontScale * superSample)
            val oversample = superSample.roundToInt().coerceAtLeast(1)
            fontConfig.setOversampleH(oversample)
            fontConfig.setOversampleV(oversample)
            if (fontData != null) {
                fonts.addFontFromMemoryTTF(fontData, fontConfig.getSizePixels(), fontConfig)
            } else {
                fonts.addFontDefault(fontConfig)
            }
        } finally {
            fontConfig.destroy()
        }

        val w = ImInt()
        val h = ImInt()
        val rgba = fonts.getTexDataAsRGBA32(w, h)
        val width = w.get()
        val height = h.get()
        val expectedBytes = width * height * 4

        val copy = java.nio.ByteBuffer.allocateDirect(expectedBytes).order(ByteOrder.nativeOrder())
        val src = rgba.duplicate().order(ByteOrder.nativeOrder())
        src.limit(src.position() + expectedBytes)
        copy.put(src)
        copy.flip()

        val newTex = StreamingTexture(
            source = copy,
            width = width,
            height = height,
            renderer = renderer,
            imageFormat = VK_FORMAT_R8G8B8A8_UNORM,
            name = "imgui_font_${width}x$height"
        )
        newTex.uploadNow(copy)
        newTex.cleanupStgBuffer()

        fonts.setTexID(fontTexId)

        if (this::fontTexture.isInitialized) {
            fontTexture.cleanup()
        }
        fontTexture = newTex
        return newTex
    }

    fun add(name: String, block: ImGuiDsl.(ImGuiIO) -> Unit) {
        uiGenerators[name] = generatorFromDsl(block)
    }

    fun addUi(name: String, block: ImGuiDsl.() -> Unit) {
        add(name) { _ -> block() }
    }

    fun addLegacyUi(name: String, block: (ImGuiIO) -> Unit) {
        uiGenerators[name] = generatorFromLegacy(block)
    }

    fun removeUi(name: String) {
        uiGenerators.starts.remove(name)
        uiGenerators.ongoings.remove(name)
    }

    /**
     * Inject a pointer in RTT pixel coordinates (origin top-left).
     * Overrides window mouse for the next frame.
     */
    fun injectPointer(x: Float, y: Float, down: Boolean, scrollY: Float = 0f) {
        pointerOverride = PointerOverride(x, y, down, scrollY)
    }

    private fun applyMonochromeStyle() {
        val style = ImGui.getStyle()
        val colors: Array<ImVec4> = style.colors
        for (c in colors) {
            val lum = (0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z)
            c.x = lum
            c.y = lum
            c.z = lum
        }
        style.colors = colors
    }

    private fun applyTightSpacing() {
        val style = ImGui.getStyle()
        style.windowPadding.set(6f, 6f)
        style.framePadding.set(4f, 3f)
        style.itemSpacing.set(5f, 4f)
        style.itemInnerSpacing.set(4f, 3f)
        style.scrollbarSize = 12f
        style.indentSpacing = 16f
        style.windowRounding = 4f
        style.frameRounding = 3f
        style.grabRounding = 3f
    }

    private fun generatorFromLegacy(block: (ImGuiIO) -> Unit): Generator<ImGuiIO, Unit> =
        Generator {
            var io = yield(Unit)
            while (true) {
                block(io)
                io = yield(Unit)
            }
        }

    private fun generatorFromDsl(block: ImGuiDsl.(ImGuiIO) -> Unit): Generator<ImGuiIO, Unit> =
        Generator {
            val dsl = ImGuiDsl()
            var io = yield(Unit)
            while (true) {
                dsl.block(io)
                io = yield(Unit)
            }
        }

    private fun uploadDrawData(dd: ImDrawData, command: CommandBuffer) {
        if (!dd.getValid()) return

        val totalVtx = dd.getTotalVtxCount()
        val totalIdx = dd.getTotalIdxCount()
        if (totalVtx <= 0 || totalIdx <= 0) return

        ensureCapacity(totalVtx, totalIdx)

        val pos = requireNotNull(posBuffer)
        val uv = requireNotNull(uvBuffer)
        val col = requireNotNull(colorBuffer)
        val idx = requireNotNull(indexBuffer)

        pos.trimTo(totalVtx * 8L)
        uv.trimTo(totalVtx * 8L)
        col.trimTo(totalVtx * 4L)
        idx.trimTo(totalIdx * 4L)

        val vtxStride = ImDrawData.sizeOfImDrawVert()
        val idxStride = ImDrawData.sizeOfImDrawIdx()
        require(vtxStride >= 20) { "Unexpected ImDrawVert stride: $vtxStride" }
        require(idxStride == 2 || idxStride == 4) { "Unexpected ImDrawIdx stride: $idxStride" }

        // Write staging buffers (src) in one pass.
        val posPtr = pos.src.map()
        val uvPtr = uv.src.map()
        val colPtr = col.src.map()
        val idxPtr = idx.src.map()
        try {
            val posFb = MemoryUtil.memFloatBuffer(posPtr, (pos.src.requestedSize / 4L).toInt())
            val uvFb = MemoryUtil.memFloatBuffer(uvPtr, (uv.src.requestedSize / 4L).toInt())
            val colIb = MemoryUtil.memIntBuffer(colPtr, (col.src.requestedSize / 4L).toInt())
            val idxIb = MemoryUtil.memIntBuffer(idxPtr, (idx.src.requestedSize / 4L).toInt())

            posFb.clear()
            uvFb.clear()
            colIb.clear()
            idxIb.clear()

            val cmdLists = dd.getCmdListsCount()
            for (listIndex in 0 until cmdLists) {
                val vtxCount = dd.getCmdListVtxBufferSize(listIndex)
                val idxCount = dd.getCmdListIdxBufferSize(listIndex)

                val vtxBuf = dd.getCmdListVtxBufferData(listIndex).order(ByteOrder.nativeOrder())
                for (i in 0 until vtxCount) {
                    val base = i * vtxStride
                    val x = vtxBuf.getFloat(base + 0)
                    val y = vtxBuf.getFloat(base + 4)
                    val u = vtxBuf.getFloat(base + 8)
                    val v = vtxBuf.getFloat(base + 12)
                    val c = vtxBuf.getInt(base + 16)

                    posFb.put(x).put(y)
                    uvFb.put(u).put(v)
                    colIb.put(c)
                }

                val idxBuf = dd.getCmdListIdxBufferData(listIndex).order(ByteOrder.nativeOrder())
                if (idxStride == 2) {
                    for (i in 0 until idxCount) {
                        val v = idxBuf.getShort(i * 2).toInt() and 0xFFFF
                        idxIb.put(v)
                    }
                } else {
                    for (i in 0 until idxCount) {
                        idxIb.put(idxBuf.getInt(i * 4))
                    }
                }
            }
        } finally {
            pos.src.unmap(0, pos.neededSize)
            uv.src.unmap(0, uv.neededSize)
            col.src.unmap(0, col.neededSize)
            idx.src.unmap(0, idx.neededSize)

            pos.watermark++
            uv.watermark++
            col.watermark++
            idx.watermark++
        }

        // Enqueue copies into the device-local buffers.
        pos.clean(hostReading = false, cmd = command)
        uv.clean(hostReading = false, cmd = command)
        col.clean(hostReading = false, cmd = command)
        idx.clean(hostReading = false, cmd = command)

        // Ensure the transfer writes are visible to subsequent vertex input fetches in this same command buffer.
        MemoryStack.stackPush().use { stack ->
            val dstAccess = VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT or VK_ACCESS_2_INDEX_READ_BIT
            val barriers = VkBufferMemoryBarrier2.calloc(4, stack)
            fun fill(i: Int, buf: VertexBuffer, size: Long) {
                barriers[i]
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT)
                    .dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buf.dest.buffer)
                    .offset(0L)
                    .size(size)
            }
            fill(0, pos, pos.neededSize)
            fill(1, uv, uv.neededSize)
            fill(2, col, col.neededSize)
            fill(3, idx, idx.neededSize)

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(barriers)
            vkCmdPipelineBarrier2(command.vkCommandBuffer, dep)
        }
    }

    private fun ensureCapacity(vertexCount: Int, indexCount: Int) {
        val wantV = max(1024, vertexCount)
        val wantI = max(2048, indexCount)

        if (posBuffer != null && vertexCapacity >= wantV && indexCapacity >= wantI) return

        val nextV = max(wantV, (vertexCapacity * 1.5f).toInt())
        val nextI = max(wantI, (indexCapacity * 1.5f).toInt())

        posBuffer?.cleanup()
        uvBuffer?.cleanup()
        colorBuffer?.cleanup()
        indexBuffer?.cleanup()

        posBuffer = VertexBuffer(renderer, nextV * 8L, usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        uvBuffer = VertexBuffer(renderer, nextV * 8L, usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        colorBuffer = VertexBuffer(renderer, nextV * 4L, usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        indexBuffer = VertexBuffer(renderer, nextI * 4L, usage = VK_BUFFER_USAGE_INDEX_BUFFER_BIT)

        vertexCapacity = nextV
        indexCapacity = nextI
    }

    class ImGuiDsl {
        private val instance = ImGui()

        fun window(title: String, flags: Int = 0, block: ImGui.() -> Unit) {
            if (ImGui.begin(title, flags)) {
                with(instance) { block() }
            }
            ImGui.end()
        }

        fun gui(block: ImGui.() -> Unit) {
            with(instance) { block() }
        }
    }

    private data class KeyEvent(val imguiKey: Int, val down: Boolean, val nativeKey: Int, val nativeScancode: Int)
    private data class PendingFont(val bytes: ByteArray, val sizePixels: Float)
    private data class PointerOverride(val x: Float, val y: Float, val down: Boolean, val scrollY: Float)

    companion object {
        private const val DEFAULT_FONT_SIZE = 13f
        private const val DEFAULT_NAME = "imgui-rtt"
        private val instances = WeakHashMap<Renderer, MutableMap<String, ImGuiRenderToTexture>>()
        private val imguiVertexShader = """
            #version 460
            
            layout(location = 0) in vec2 inPosition;
            layout(location = 1) in vec2 inUV;
            layout(location = 2) in uint inColor;
            
            layout(location = 0) out vec2 fragUV;
            layout(location = 1) out vec4 fragColor;
            
            layout(push_constant) uniform Push {
                vec2 scale;
                vec2 translate;
            } push;
            
            void main() {
                fragUV = inUV;
                fragColor = unpackUnorm4x8(inColor);
                gl_Position = vec4(inPosition * push.scale + push.translate, 0.0, 1.0);
            }
        """.trimIndent()

        private val imguiFragmentShader = """
            #version 460
            
            layout(location = 0) in vec2 fragUV;
            layout(location = 1) in vec4 fragColor;
            
            layout(location = 0) out vec4 outColor;
            
            layout(set = 0, binding = 0) uniform sampler2D fontTexture;
            
            void main() {
                outColor = fragColor * texture(fontTexture, fragUV);
            }
        """.trimIndent()

        /**
         * Return a per-[Renderer] singleton (keyed by [name]) created on demand.
         */
        @JvmStatic
        fun get(
            renderer: Renderer = fromContext(),
            window: Window = renderer.window,
            name: String = DEFAULT_NAME,
            overSample: Int = 2,
            fontSuperSample: Float = 0f,
            target: RenderTarget = renderer.renderList
        ): ImGuiRenderToTexture {
            val byName = instances.getOrPut(renderer) { mutableMapOf() }
            return byName.getOrPut(name) {
                create(renderer, window, name, overSample, fontSuperSample, target)
            }
        }

        operator fun invoke(
            renderer: Renderer = fromContext(),
            window: Window = renderer.window,
            name: String = DEFAULT_NAME,
            overSample: Int = 2,
            fontSuperSample: Float = 4f,
            target: RenderTarget = renderer.renderList
        ): ImGuiRenderToTexture = get(renderer, window, name, overSample, fontSuperSample, target)

        private fun create(
            renderer: Renderer,
            window: Window,
            name: String,
            overSample: Int,
            fontSuperSample: Float,
            target: RenderTarget
        ): ImGuiRenderToTexture {
            val imgui = ImGuiRenderToTexture(
                renderer = renderer,
                window = window,
                parameters = {
                    width = window.width * overSample
                    height = window.height * overSample
                    mipLevels = 4
                    generateColorMipmaps = true
                    clearFirst = true
                },
                fontSupersample = fontSuperSample,
                displayScale = 1f // overSample.f
            )

            imgui.addUi("test") {
                window("ImGui → RenderToTexture") {
                    text("Hello from imgui-java")
                    text("Renderer: Vulkan → RTT → screen blit")
                }
            }

            imgui.loadFontFromResource("IosevkaField-Light.ttf", sizePixels = 12 * 2 * overSample.f)
            imgui.attachTo(target = target, name = name, blitName = "$name-blit")
            return imgui
        }
    }
}
