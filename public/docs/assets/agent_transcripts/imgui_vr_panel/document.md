# Field Document: imgui_vr_panel

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. window + renderer

<!-- field-box id="imgui_vr_renderer" index="0" -->

```edn [field-box id="imgui_vr_renderer" role="properties"]
{"id" "imgui_vr_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["imgui_vr_panel"] "properties" {"children" #{#afield/box "imgui_vr_panel"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imgui_vr_renderer_code_.kt" "lines" 14 "characters" 252 "sha256" "719fbd4aeeadb0cbb67eaea63960664a2c1bdfa88c7af809300f2cc8d50446ab"}]}
```

```kotlin [field-property box="imgui_vr_renderer" name="code" path="imgui_vr_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("ImGui VR panel")
    Renderer(window)
}

println("imgui vr renderer ready")
start(renderer::run)
```

## 2. imgui vr panel

<!-- field-box id="imgui_vr_panel" index="1" -->

### Narrative

From `code`

Edit the UI, panel geometry and ray mapping here; shaders are box properties.

```edn [field-box id="imgui_vr_panel" role="properties"]
{"id" "imgui_vr_panel" "index" 1 "name" "imgui vr panel" "parents" ["root" "imgui_vr_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "imgui_vr_renderer"} "name" "imgui vr panel" "_tombstone" false "frame" #afield/rect [300.0 140.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imgui_vr_panel_code_.kt" "lines" 174 "characters" 5621 "sha256" "244681d9e9e18cf079d711a7f7cc452f1323af713b402ae0764407123b5ef62f"} {"name" "vertex" "language" "glsl" "path" "imgui_vr_panel_vertex_.glsl" "lines" 11 "characters" 273 "sha256" "4cc7f810a0274abf0b4a2efab1ad1791ff1aedf01640618d5db331679e50630a"} {"name" "fragment" "language" "glsl" "path" "imgui_vr_panel_fragment_.glsl" "lines" 11 "characters" 426 "sha256" "2e66829953f2b81ca1a9a5ef2350ad8dd4d22d8e1b06725b9526109d0fa811fe"}]}
```

```kotlin [field-property box="imgui_vr_panel" name="code" path="imgui_vr_panel_code_.kt"]
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
```

```glsl [field-property box="imgui_vr_panel" name="vertex" path="imgui_vr_panel_vertex_.glsl"]
#version 460
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUV;
layout(location = 0) out vec2 fragUV;
layout(push_constant) uniform Push {
    mat4 mvp;
} push;
void main() {
    fragUV = inUV;
    gl_Position = push.mvp * vec4(inPosition, 1.0);
}
```

```glsl [field-property box="imgui_vr_panel" name="fragment" path="imgui_vr_panel_fragment_.glsl"]
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
```

