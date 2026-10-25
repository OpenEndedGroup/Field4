# Field Document: youtube_vr_browser

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. window + renderer

<!-- field-box id="youtube_vr_renderer" index="0" -->

```edn [field-box id="youtube_vr_renderer" role="properties"]
{"id" "youtube_vr_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["youtube_vr_panel"] "properties" {"children" #{#afield/box "youtube_vr_panel"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "youtube_vr_renderer_code_.kt" "lines" 22 "characters" 629 "sha256" "d7b86120583e7054b2a123d01f70d92b40b44b8bf67c2e811b8b5369ca924926"}]}
```

```kotlin [field-property box="youtube_vr_renderer" name="code" path="youtube_vr_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false
// OpenXR is the default on its supported desktop platforms. macOS remains the stereo
// preview/development path because it has no system OpenXR runtime.
Options.enableVR = !Options.isMac

once.renderer {
    window = Window("YouTube · puppet browser")
    val created = Renderer(window)
    if (Options.enableVR) {
        created.openXRHmdTarget = afield.graphics.vulkan.targets.OpenXRHmdTarget(created)
    }
    created
}

println("YouTube VR renderer ready")
start(renderer::run)
```

## 2. YouTube — interactive Chromium panel

<!-- field-box id="youtube_vr_panel" index="1" -->

```edn [field-box id="youtube_vr_panel" role="properties"]
{"id" "youtube_vr_panel" "index" 1 "name" "YouTube — interactive Chromium panel" "parents" ["root" "youtube_vr_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "youtube_vr_renderer"} "name" "YouTube — interactive Chromium panel" "_tombstone" false "frame" #afield/rect [320.0 140.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "youtube_vr_panel_code_.kt" "lines" 66 "characters" 2291 "sha256" "a52099e0b7fa4fb26b1de3a1f488422bcf121fcf383082a5e2c00cdc470b7311"}]}
```

```kotlin [field-property box="youtube_vr_panel" name="code" path="youtube_vr_panel_code_.kt"]
import afield.graphics.vulkan.Target
import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.PuppetBrowser
import afield.media.browser.PuppetBrowserVrPanel
import afield.media.browser.PuppetBrowserWindowInput

// Publish browser controls to connected boxes.
prop<PuppetBrowser>(browserControl)

// The producer is a full Chromium page, not an embed: consent, account state, player,
// navigation and audio all behave as they do in an ordinary browser.
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired,
)
browserControl = browser

// Import the newest cross-process frame before either eye samples the resident texture.
renderer.renderList.preTargets["youtube-browser-upload"] = Target { command, _ ->
    browser.prepareSampledImagesFor(command)
}

val stereo = SideBySideStereoTarget(renderer, openXR = renderer.openXRHmdTarget)
val panel = PuppetBrowserVrPanel(
    renderer = renderer,
    stereo = stereo,
    browser = browser,
    widthMeters = 1.6f,
    distanceMeters = 1.7f,
    name = "youtube-browser-panel",
)
panel.build()
panel.enableDesktopInteraction()

// The controller owns pointer input. Once the panel has focus, physical keyboard/IME
// events from Field's Window remain available for search boxes and other text fields.
val keyboard = PuppetBrowserWindowInput(browser, renderer.window) { _, _ -> null }
keyboard.takeFocus()

// Desktop mirror of the same stereo texture that can be handed to OpenXR eye swapchains.
stereo.packed.blitToScreen(
    renderer = renderer,
    target = renderer.renderList,
    name = "youtube-stereo-screen",
)

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        println(
            "YouTube is live on a ${browser.spec.width}x${browser.spec.height} " +
                "texture; trigger accepts consent and controls playback",
        )
    }
    frame()
}
    .end {
        renderer.renderList.preTargets.remove("youtube-browser-upload")
        keyboard.close()
        browser.close()
    }
```

