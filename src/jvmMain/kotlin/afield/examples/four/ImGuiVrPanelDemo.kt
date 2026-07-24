package afield.examples.four

import afield.four.Renderer
import afield.four.imgui.ImGuiPanel
import afield.four.imgui.ImGuiRenderToTexture
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.SideBySideStereoTarget
import afield.four.texture.TextureSampler
import afield.four.util.Window
import afield.four.util.forever
import afield.stdlib.fromViews
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import imgui.ImGui
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import imgui.type.ImBoolean
import imgui.type.ImFloat
import org.lwjgl.glfw.GLFW
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone, runnable end-to-end demo of "ImGui as an in-VR panel".
 *
 * Pipeline (all pieces already exist in the tree; this wires them into one loop):
 *   1. [ImGuiRenderToTexture] runs a Dear ImGui frame each render and draws it into an RTT.
 *   2. The RTT is textured onto a world-space quad that is rendered twice — once per eye — by
 *      [SideBySideStereoTarget], which packs the two eye views into a single side-by-side image.
 *   3. That packed side-by-side image is blitted to the desktop window so the stereo result is
 *      visible without a headset (the exact same `packed` RTT is what an OpenXR runtime would copy
 *      into the eye swapchains when a headset is attached — see OpenXRHmdTarget.copySideBySideToEyes).
 *   4. A controller (real in XR, mouse-driven mock on the desktop) casts a ray at the quad; the hit
 *      point is converted to panel UV and injected into ImGui as a mouse position + click, so the
 *      panel is actually interactive.
 *
 * Run interactively:   ./gradlew runImGuiVrPanelDemo
 *   - Move the mouse to aim the ray; left-click to "press". The panel shows the live hit UV.
 *
 * Run a verification capture (renders a few frames aiming at panel centre, writes a PNG, exits):
 *   ./gradlew runImGuiVrPanelDemo --args="capture <output.png>"
 */
class ImGuiVrPanelDemo(
    val window: Window,
    val renderer: Renderer,
    val sbs: SideBySideStereoTarget,
) {
    // Live UI state (read by the ImGui closure, written by interaction).
    private var clicks = 0
    private val showGrid = ImBoolean(true)
    private val gain = ImFloat(0.5f)
    @Volatile var hitU = -1f
        private set
    @Volatile var hitV = -1f
        private set
    @Volatile var hitActive = false
        private set
    var lastHitWorld: Vector3f? = null
        private set

    private lateinit var imgui: ImGuiRenderToTexture
    private lateinit var panel: ImGuiPanel

    // Panel is 1.5 m in front of the eyes. Half-extents in metres (height fixed, width follows aspect).
    private val panelHalfH = 0.5f
    private val panelDistance = 1.5f

    fun build() {
        // Eyes at the origin looking down +Z, up = +Y. With this pose the camera-right vector is +X,
        // so world +X maps to screen-right and the ImGui text reads correctly (looking down -Z instead
        // mirrors +X to screen-left, which flips the panel horizontally). The panel sits at z = +distance.
        sbs.stereoCamera.base.at.set(0f, 0f, 0f)
        sbs.stereoCamera.base.target.set(0f, 0f, 1f)

        imgui = ImGuiRenderToTexture(
            renderer = renderer,
            window = window,
            parameters = {
                width = 2048
                height = 2048
                clearFirst = true
                format = VK_FORMAT_B8G8R8A8_SRGB
                mipLevels = 4
            },
            displayScale = 1f,
            fontSupersample = 1f,
        )
        // Pointer comes from the controller ray, not the OS cursor.
        imgui.useWindowMouseEvents = false
        // Field's custom "Iosevka Field" build (subset from the editor's woff2), not stock Iosevka.
        imgui.loadFontFromResource("IosevkaField-Light.ttf", sizePixels = 12f * 8)

        imgui.addUi("panel") {
            gui {
                ImGui.setNextWindowSize(1960f, 1960f)
                ImGui.setNextWindowPos(44f, 44f)
            }
            window("Field · VR Panel") {
                ImGui.text("Dear ImGui, rendered to a texture,")
                ImGui.text("mapped onto a quad, shown in both eyes.")
                ImGui.separator()
                if (ImGui.button("  Click me  ")) clicks++
                ImGui.sameLine()
                ImGui.text("clicks: $clicks")
                ImGui.checkbox("show grid", showGrid)
                ImGui.sliderFloat("gain", gain.data, 0f, 1f)
                ImGui.separator()
                if (hitActive) {
                    ImGui.text("ray hit  u=%.3f  v=%.3f".format(hitU, hitV))
                } else {
                    ImGui.text("ray hit  (off panel)")
                }
            }
        }

        // Draw the ImGui panel into its RTT every frame.
        renderer.renderList.preTargets["imgui-rtt"] = imgui

        // Panel geometry, sized to the RTT aspect ratio.
        val aspect = imgui.width.toFloat() / imgui.height.toFloat()
        val halfH = panelHalfH
        val halfW = halfH * aspect
        val positions = floatArrayOf(
            -halfW, +halfH, 0f,
            -halfW, -halfH, 0f,
            +halfW, -halfH, 0f,
            +halfW, +halfH, 0f,
        )
        val uvs = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)

        // Place the panel `panelDistance` in front of the eyes, upright (+x right, +y up). Rebinding it
        // to a hand later is just another place(handPos, handRight, handUp) call each frame.
        panel = ImGuiPanel(imgui, halfW, halfH)
        panel.place(Vector3f(0f, 0f, panelDistance), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f))

        val sampler = TextureSampler(renderer, mipLevels = 4, anisotropyEnable = true)

        // Register the panel module; SideBySideStereoTarget instantiates it once per eye.
        sbs += "imgui-panel" to {
            val module = Module(
                parameters(renderer) {
                    name = "imgui-vr-panel"
                    color = 1
                    depth = 1
                    depthTest = true
                    colorFormats = listOf(VK_FORMAT_B8G8R8A8_SRGB)
                    vertex = panelVertexShader
                    fragment = panelFragmentShader
                    textureResolver = { tex ->
                        if (tex.name == "panelTex") fromViews(listOf(imgui), tex.name, sampler) else null
                    }
                }
            )

            val mp = module.pointer()
            val mesh = Mesh(renderer, mp)
            mesh.resize(4, 2)
            mp.current.meshes["panel"] = mesh

            mesh.v[0]!!.mapAndWrite { positions.forEach { put(it) }; rewind() }
            mesh.v[1]!!.mapAndWrite { uvs.forEach { put(it) }; rewind() }
            mesh.t!!.mapAndWrite_ints { indices.forEach { put(it) }; rewind() }

            mesh.update["mvp"] = forever {
                val view = sbs.currentEyeViewMatrix(Matrix4f())
                val proj = sbs.currentEyeProjectionMatrix(Matrix4f())
                mesh.push["mvp"] = panel.panelMvp(view, proj)
                updateRayHit()
                true
            }
            mp
        }

        // Show the packed side-by-side result on the desktop window.
        sbs.packed.blitToScreen(
            renderer = renderer,
            target = renderer.renderList,
            name = "sbs-screen",
        )
    }

    /** Aim the mock controller straight at the panel centre (for headless/deterministic runs). */
    fun aimControllerAtPanelCentre() {
        sbs.updateMockControllerFromRay(Vector3f(0f, 0f, 0f), Vector3f(0f, 0f, -1f), distanceMeters = 0.001f)
    }

    /** Release ImGui/Vulkan resources owned by the demo (caller still cleans renderer + window). */
    fun cleanup() {
        imgui.cleanup()
    }

    /** Build the controller ray, hit-test it against the panel, and inject the hit into ImGui. */
    private fun updateRayHit() {
        val ctrl = sbs.controllerState()
        if (!ctrl.active || !ctrl.poseValid) {
            hitActive = false
            lastHitWorld = null
            imgui.injectPointer(imgui.width * 2f, imgui.height * 2f, false)
            return
        }
        val (origin, dir) = ImGuiPanel.controllerRay(ctrl)
        val hit = panel.interact(origin, dir, ctrl.selectPressed || ctrl.triggerPressed)
        lastHitWorld = hit?.worldPoint
        if (hit != null && hit.onPanel) {
            hitActive = true
            hitU = hit.u
            hitV = hit.v
        } else {
            hitActive = false
        }
    }

    /** Interactive desktop loop: mouse drives the mock controller ray. */
    fun runInteractive() {
        sbs.enableMouseController(distanceMeters = 0.001f)
        var frame = 0
        while (running && !window.shouldClose()) {
            window.pollEvents()
            renderer.render()
            if (++frame % 120 == 0) println("frame $frame  hit=${if (hitActive) "u=%.2f v=%.2f".format(hitU, hitV) else "off"}")
        }
    }

    /**
     * Verification loop: aim the mock controller straight at the panel centre, render a warm-up of
     * frames, then grab the *live macOS window* with `screencapture` (the same tool doc.kt uses for
     * afield.four window shots). We deliberately avoid the Vulkan swapchain readback here because that
     * path is Y-flipped for our blitted content; screencapture records exactly what is on screen.
     */
    fun runCapture(outputPath: String) {
        GLFW.glfwShowWindow(window.windowHandle)
        GLFW.glfwFocusWindow(window.windowHandle)

        var frame = 0
        val warmup = 60
        while (running && !window.shouldClose()) {
            window.pollEvents()
            aimControllerAtPanelCentre()
            renderer.render()
            frame++

            if (frame == 3 || frame == warmup) {
                val ctrl = sbs.controllerState()
                println("CAPTURE-DIAG frame=$frame ctrlPos=${ctrl.position} hitActive=$hitActive hitU=$hitU hitV=$hitV lastHitWorld=$lastHitWorld")
            }

            if (frame == warmup) {
                screencaptureWindow(outputPath)
                break
            }
            if (frame > warmup + 600) { println("CAPTURE-TIMEOUT"); break }
        }
    }

    /**
     * Headless verification capture: aim at the panel centre, render a warm-up of frames, then read
     * the swapchain back to a PNG with [Renderer.captureWindowToFile] (a Vulkan transfer-source copy —
     * unlike `screencapture` it needs no Screen-Recording permission and works over SSH/CI). The
     * captured pixels are the blitted side-by-side packed image and are vertically flipped by that path
     * (see the KDoc on captureWindowToFile / Window.saveFramebufferToTempFile); un-flip downstream if
     * you want an upright still.
     */
    fun runCaptureGpu(outputPath: String) {
        GLFW.glfwShowWindow(window.windowHandle)
        var frame = 0
        val warmup = 60
        while (running && !window.shouldClose()) {
            window.pollEvents()
            aimControllerAtPanelCentre()
            renderer.render()
            frame++
            if (frame == warmup) {
                val ctrl = sbs.controllerState()
                println("CAPTURE-DIAG frame=$frame hitActive=$hitActive hitU=$hitU hitV=$hitV lastHitWorld=$lastHitWorld")
                println("CAPTURE-SUPPORTS-DIRECT ${renderer.supportsDirectWindowCapture(window)}")
                val abs = File(outputPath).absoluteFile
                abs.parentFile?.mkdirs()
                // captureWindowToFile enqueues onto the render loop and only completes once a later
                // frame records the transfer, so it must run OFF the render thread while we keep
                // pumping frames here — running it inline would deadlock.
                val done = CompletableDeferred<Result<String>>()
                GlobalScope.launch(Dispatchers.IO) {
                    done.complete(runCatching { renderer.captureWindowToFile(window, abs.path) })
                }
                var pumped = 0
                while (!done.isCompleted && pumped++ < 300) { window.pollEvents(); renderer.render() }
                val result = runBlocking { done.await() }
                result.onSuccess { println(if (abs.exists()) "CAPTURE-OK $it" else "CAPTURE-FAIL not-written $it") }
                    .onFailure { println("CAPTURE-FAIL ${it.message}") }
                break
            }
            if (frame > warmup + 600) { println("CAPTURE-TIMEOUT"); break }
        }
    }

    /** Capture the on-screen window rectangle to a PNG via macOS `screencapture -R x,y,w,h`. */
    private fun screencaptureWindow(outputPath: String) {
        val px = IntArray(1); val py = IntArray(1)
        GLFW.glfwGetWindowPos(window.windowHandle, px, py)
        val sw = IntArray(1); val sh = IntArray(1)
        GLFW.glfwGetWindowSize(window.windowHandle, sw, sh)
        val region = "${px[0]},${py[0]},${sw[0]},${sh[0]}"
        val abs = File(outputPath).absoluteFile
        abs.parentFile?.mkdirs()
        println("CAPTURE-REGION $region -> ${abs.path}")
        try {
            val proc = ProcessBuilder("screencapture", "-x", "-R", region, abs.path)
                .redirectErrorStream(true).start()
            val ok = proc.waitFor()
            println(if (ok == 0 && abs.exists()) "CAPTURE-OK ${abs.path}" else "CAPTURE-FAIL exit=$ok exists=${abs.exists()}")
        } catch (t: Throwable) {
            println("CAPTURE-FAIL ${t.message}")
        }
    }

    @Volatile var running = true

    companion object {
        private val panelVertexShader = """
            #version 460
            layout(location = 0) in vec3 inPosition;
            layout(location = 1) in vec2 inUV;
            layout(location = 0) out vec2 fragUV;
            layout(push_constant) uniform Push { mat4 mvp; } push;
            void main() {
                fragUV = inUV;
                gl_Position = push.mvp * vec4(inPosition, 1.0);
            }
        """.trimIndent()

        private val panelFragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            layout(set = 0, binding = 0) uniform sampler2D panelTex;
            void main() {
                vec4 c = texture(panelTex, fragUV);
                // Faint border so an all-black panel is still visibly a quad.
                float edge = min(min(fragUV.x, 1.0 - fragUV.x), min(fragUV.y, 1.0 - fragUV.y));
                if (edge < 0.004) c = vec4(0.25, 0.35, 0.5, 1.0);
                outColor = c;
            }
        """.trimIndent()

        @JvmStatic
        fun main(args: Array<String>) {
            val window = Window("Field · ImGui VR Panel")
            val renderer = Renderer(window)
            val sbs = SideBySideStereoTarget(renderer)
            val demo = ImGuiVrPanelDemo(window, renderer, sbs)
            demo.build()

            if (args.isNotEmpty() && args[0] == "capture") {
                val out = args.getOrNull(1) ?: "build/imgui-vr-panel.png"
                demo.runCapture(out)
                println("ImGuiVrPanelDemo capture done")
            } else if (args.isNotEmpty() && args[0] == "capture-gpu") {
                val out = args.getOrNull(1) ?: "build/imgui-vr-panel.png"
                demo.runCaptureGpu(out)
                println("ImGuiVrPanelDemo capture-gpu done")
            } else {
                demo.runInteractive()
            }

            // GLFW + Vulkan + the coroutine IO pool keep non-daemon threads alive, so the JVM won't
            // exit on its own (the window would sit there beachballing). Force a clean process exit.
            exitProcess(0)
        }
    }
}
