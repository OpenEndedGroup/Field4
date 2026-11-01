# Field Document: field_vr_ide

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - VR IDE renderer

<!-- field-box id="vr_ide_renderer" index="0" -->

```edn [field-box id="vr_ide_renderer" role="properties"]
{"id" "vr_ide_renderer" "index" 0 "name" "1 - VR IDE renderer" "parents" ["root"] "children" ["vr_ide_panel"] "properties" {"children" #{#afield/box "vr_ide_panel"} "parents" #{#afield/box "root"} "name" "1 - VR IDE renderer" "_tombstone" false "frame" #afield/rect [60.0 60.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vr_ide_renderer_code_.kt" "lines" 37 "characters" 1589 "sha256" "db3bb2491242b052478d237e9bae09f2338df6d6327bd6a6592f30fe449c5488"}]}
```

```kotlin [field-property box="vr_ide_renderer" name="code" path="vr_ide_renderer_code_.kt"]
import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
// Start with the desktop companion. Connect WiVRn, set true and restart Field for VR.
val useHeadset = false
Options.enableVR = useHeadset
Options.enableHDR = false
once.renderer {
    window = Window("Field · VR IDE", initialDimensions = Rect(80.0, 80.0, 1440.0, 800.0))
    val created = Renderer(window)
    if (useHeadset) created.openXRHmdTarget = OpenXRHmdTarget(created)
    created
}
renderer.render()
display(
    "guide",
    """
    <div style="width:420px;font:16px/1.6 system-ui">
    <b>Field in your headset</b><br>
    Launch 1, then 2. The browser opens this same document.<br>
    Point with either controller; trigger clicks and drags, stick scrolls.<br>
    Aim at the panel and hold grip to move and rotate it; release to leave it there.<br>
    With tracked hands, point the hand and pinch to click.<br>
    Keyboard opens text and editing keys; Commands opens the palette.<br>
    Select the Playground, edit its code, then Run box (Alt+Up).<br>
    Recenter brings the panel in front of your current head position.<br>
    The desktop companion accepts normal keyboard and mouse input when focused.<br>
    Quest keyboard and mouse use WiVRn HID forwarding after setup; see the saved guide.<br>
    Another browser can edit this same document with its own input focus.<br>
    Stop box 2 to close the browser. Restart Field to switch VR mode or eye size.
    </div>
    """
        .trimIndent(),
)
displayOpen("guide")
```

## 2. 2 - Live IDE in VR

<!-- field-box id="vr_ide_panel" index="1" -->

```edn [field-box id="vr_ide_panel" role="properties"]
{"id" "vr_ide_panel" "index" 1 "name" "2 - Live IDE in VR" "parents" ["root" "vr_ide_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "vr_ide_renderer"} "name" "2 - Live IDE in VR" "_tombstone" false "frame" #afield/rect [580.0 60.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vr_ide_panel_code_.kt" "lines" 24 "characters" 729 "sha256" "5552403af8ef31ea4c8dc27e097ddf57ebfb377bd96a567f39c82d8b6ff257f5"}]}
```

```kotlin [field-property box="vr_ide_panel" name="code" path="vr_ide_panel_code_.kt"]
import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.FieldVrWorkspace
import afield.media.browser.PuppetBrowser

prop<PuppetBrowser>(browserControl)
val ownedRenderer = renderer
val vr = FieldVrWorkspace(
    renderer = ownedRenderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 2560,
    height = 1600,
    readyBoxUid = thisBox.id,
    widthMeters = 2.0f,
    distanceMeters = 1.35f,
)
browserControl = vr.browser
println("Live IDE: ${vr.url}; ${vr.stereo.eyeWidth} × ${vr.stereo.eyeHeight} per eye")
// Capture the renderer before entering the asynchronous scope.
loop {
    ownedRenderer.window.pollEvents()
    ownedRenderer.render()
    frame()
}
    .end { vr.close() }
```

## 3. Playground - edit and run from VR

<!-- field-box id="vr_ide_playground" index="2" -->

### Narrative

From `code`

Select this box, open its editor, change the colour or message, then Run box (Alt+Up).

```edn [field-box id="vr_ide_playground" role="properties"]
{"id" "vr_ide_playground" "index" 2 "name" "Playground - edit and run from VR" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Playground - edit and run from VR" "_tombstone" false "frame" #afield/rect [580.0 300.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "vr_ide_playground_code_.kt" "lines" 13 "characters" 382 "sha256" "c45441dd78ad57a39384759b8d42d803b88253dd664e8b302fff0a21c7ed9ed2"}]}
```

```kotlin [field-property box="vr_ide_playground" name="code" path="vr_ide_playground_code_.kt"]
// Select this box, open its editor, change the colour or message, then Run box (Alt+Up).
val colour = "#237bc2"
display(
    "hello",
    """
    <div style="width:320px;padding:24px;background:$colour;color:white;font:24px system-ui">
    Hello from inside Field VR!
    </div>
"""
        .trimIndent(),
)
displayOpen("hello")
println("Code executed in the same Field document")
```

