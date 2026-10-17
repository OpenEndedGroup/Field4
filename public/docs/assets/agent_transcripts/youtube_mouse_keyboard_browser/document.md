# Field Document: youtube_mouse_keyboard_browser

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 16:9 window + renderer

<!-- field-box id="youtube_desktop_renderer" index="0" -->

```edn [field-box id="youtube_desktop_renderer" role="properties"]
{"id" "youtube_desktop_renderer" "index" 0 "name" "16:9 window + renderer" "parents" ["root"] "children" ["youtube_desktop_panel"] "properties" {"children" #{#afield/box "youtube_desktop_panel"} "parents" #{#afield/box "root"} "name" "16:9 window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "youtube_desktop_renderer_code_.kt" "lines" 20 "characters" 438 "sha256" "15a0022082c7da47187c8ea09ef16c80467cb98b944a484ae62b668d368467e1"}]}
```

```kotlin [field-property box="youtube_desktop_renderer" name="code" path="youtube_desktop_renderer_code_.kt"]
import afield.app.protocol.Rect
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = false
Options.profiling = false

once.renderer {
    window = Window(
        "YouTube · mouse + keyboard",
        initialDimensions = Rect(80.0, 80.0, 1280.0, 720.0)
    )
    Renderer(window)
}

println("YouTube mouse + keyboard renderer ready")
start(renderer::run)
```

## 2. YouTube — direct mouse + keyboard

<!-- field-box id="youtube_desktop_panel" index="1" -->

```edn [field-box id="youtube_desktop_panel" role="properties"]
{"id" "youtube_desktop_panel" "index" 1 "name" "YouTube — direct mouse + keyboard" "parents" ["root" "youtube_desktop_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "youtube_desktop_renderer"} "name" "YouTube — direct mouse + keyboard" "_tombstone" false "frame" #afield/rect [330.0 140.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "youtube_desktop_panel_code_.kt" "lines" 36 "characters" 1299 "sha256" "30794eecb5bd95f762e363b36a13561a5b7fcf3465215cc5eafa6831d6a75791"}]}
```

```kotlin [field-property box="youtube_desktop_panel" name="code" path="youtube_desktop_panel_code_.kt"]
import afield.graphics.vulkan.Target
import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.PuppetBrowserWindowPanel

prop<afield.media.browser.PuppetBrowser>(browserControl)

val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired
)
browserControl = browser

renderer.renderList.preTargets["youtube-desktop-browser-upload"] =
    Target { command, _ -> browser.prepareSampledImagesFor(command) }

// This is direct window input, not a synthetic controller ray. The panel letterboxes if
// the window is resized and uses that same fitted rectangle for its mouse hit test.
val screen = PuppetBrowserWindowPanel(renderer, browser, "youtube-desktop-browser")
screen.build(takeFocus = true)

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        println("YouTube is live: click, scroll and type directly in the Chromium page")
    }
    frame()
}.end {
    renderer.renderList.preTargets.remove("youtube-desktop-browser-upload")
    screen.close()
    browser.close()
}
```

