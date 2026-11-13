// Edit the UI, panel geometry and ray mapping here; shaders are box properties.
import afield.graphics.render.Module
import afield.graphics.vulkan.imgui.ImGuiPanel
import afield.graphics.vulkan.imgui.ImGuiRenderToTexture
import imgui.ImGui
import imgui.type.ImBoolean
import imgui.type.ImFloat
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB

// Keep native targets across reruns; replace the named UI and per-eye modules below.
val sbs = cached<SideBySideStereoTarget>(renderer) { SideBySideStereoTarget(renderer) }
var clicks = 0
val showGrid = ImBoolean(true)
val gain = ImFloat(0.5f)
var hitU = -1f
var hitV = -1f
var hitActive = false

val panelVertex = checkNotNull(vertex) { "Set the vertex property" }
val panelFragment = checkNotNull(fragment) { "Set the fragment property" }

// Eyes at the origin looking down +Z, up = +Y. The panel sits at z = +1.5 and faces them.
sbs.stereoCamera.base.at.set(0f, 0f, 0f)
sbs.stereoCamera.base.target.set(0f, 0f, 1f)

val panelPixels = 2048
val fontSize = 96f
val imgui = cached<ImGuiRenderToTexture>(renderer, panelPixels, fontSize) {
    ImGuiRenderToTexture(
        renderer = renderer,
        window = window,
        parameters = {
            width = panelPixels
            height = panelPixels
            format = VK_FORMAT_B8G8R8A8_SRGB
            mipLevels = 4
        },
        displayScale = 1f,
        fontSupersample = 1f,
    ).also { texture ->
        texture.useWindowMouseEvents = false
        texture.loadFontFromResource("IosevkaField-Light.ttf", sizePixels = fontSize)
        RunScope.current()!!.own(policy = ReleasePolicy.KEYED, release = texture::cleanup)
    }
}
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
val halfH = 0.5f
val halfW = halfH * aspect
val positions = floatArrayOf(
    -halfW,
    +halfH,
    0f,
    -halfW,
    -halfH,
    0f,
    +halfW,
    -halfH,
    0f,
    +halfW,
    +halfH,
    0f,
)
val uvs = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
val indices = intArrayOf(0, 1, 2, 0, 2, 3)

// Place the panel 1.5 metres in front of the eyes, upright and facing them.
val panel = ImGuiPanel(imgui, halfW, halfH)
panel.faceToward(Vector3f(0f, 0f, 1.5f), Vector3f(0f, 0f, 0f))

fun updateRayHit() {
    val ctrl = sbs.controllerState()
    if (!ctrl.active || !ctrl.poseValid) {
        hitActive = false
        imgui.injectPointer(imgui.width * 2f, imgui.height * 2f, false)
        return
    }
    val (origin, dir) = ImGuiPanel.controllerRay(ctrl)
    val hit = panel.interact(origin, dir, ctrl.selectPressed || ctrl.triggerPressed)
    if (hit != null && hit.onPanel) {
        hitActive = true
        hitU = hit.u
        hitV = hit.v
    } else {
        hitActive = false
    }
}

val sampler = cached<TextureSampler>(renderer) {
    TextureSampler(renderer, mipLevels = 4, anisotropyEnable = true)
}

// Register the panel module; SideBySideStereoTarget instantiates it once per eye.
sbs +=
    "imgui-panel" to
        {
            val module = Module(
                parameters(renderer) {
                    name = "imgui-vr-panel"
                    color = 1
                    depth = 1
                    depthTest = true
                    colorFormats = [VK_FORMAT_B8G8R8A8_SRGB]
                    vertex = panelVertex
                    fragment = panelFragment
                    textureResolver = { tex ->
                        if (tex.name == "panelTex") fromViews([imgui], tex.name, sampler) else null
                    }
                },
            )

            val mp = module.pointer()
            val mesh = Mesh(renderer, mp)
            mesh.resize(4, 2)
            mp.current.meshes["panel"] = mesh

            mesh.v[0]!!.mapAndWrite {
                positions.forEach { put(it) }
                rewind()
            }
            mesh.v[1]!!.mapAndWrite {
                uvs.forEach { put(it) }
                rewind()
            }
            mesh.t!!.mapAndWrite_ints {
                indices.forEach { put(it) }
                rewind()
            }

            mesh.update["mvp"] = updateForever {
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
// Mouse aims the mock controller; XR supplies a real controller pose.
sbs.enableMouseController(distanceMeters = 0.001f)
println("imgui vr panel ready — move the mouse to aim the ray, click to press")
