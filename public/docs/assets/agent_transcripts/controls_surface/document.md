# Field Document: controls_surface

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - A performer's surface

<!-- field-box id="surface" index="0" -->

```edn [field-box id="surface" role="properties"]
{"id" "surface" "index" 0 "name" "1 - A performer's surface" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A performer's surface" "_tombstone" false "frame" #afield/rect [60.0 60.0 260.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "surface_code_.kt" "lines" 47 "characters" 1510 "sha256" "a4aa21b685d06eea3b1564ae41d55813cd542845ff3168f051df7431738d25fd"}]}
```

```kotlin [field-property box="surface" name="code" path="surface_code_.kt"]
var cutoff = 800.0
var orbit = Vector2f(0.3f, 0.2f)
var armed = true
var title = "orbit"
var tint = Vector4f(0.05f, 0.78f, 0.93f, 1f)
fun draw() {
    val c = "rgba(${(tint.x * 255).toInt()},${(tint.y * 255).toInt()},${(tint.z * 255).toInt()},${tint.w})"
    val r = 10 + 40 * kotlin.math.log10(cutoff / 20.0) / 3.0
    val dot =
        if (armed) "<circle cx='${150 + orbit.x * 120}' cy='${150 - orbit.y * 120}' r='${"%.1f".format(r)}' fill='$c'/>"
        else ""
    display(
        "figure",
        """
        <svg width="300" height="300" style="background:#05080a;border:1px solid #136178">
          <line x1="150" y1="150" x2="${150 + orbit.x * 120}" y2="${150 - orbit.y * 120}" stroke="#136178"/>$dot
        </svg>
        <div style="font:18px Iosevka,monospace;color:#0cc8ed">$title  cutoff ${cutoff.toInt()} Hz</div>
    """
            .trimIndent(),
    )
}
controlGroup("controls") {
    slider("cutoff", min = 20.0, max = 20000.0, initialValue = 800.0, law = ControlLaw.LOGARITHMIC) {
        cutoff = it
        draw()
    }
    vec2Control("orbit", initialValue = Vector2f(0.3f, 0.2f), step = 0.01) {
        orbit = it
        draw()
    }
    checkbox("armed", initialValue = true) {
        armed = it
        draw()
    }
    stringInput("title", initialValue = "orbit") {
        title = it
        draw()
    }
    colorPicker("tint", initialValue = Vector4f(0.05f, 0.78f, 0.93f, 1f)) {
        tint = it
        draw()
    }
}
displayOpen("controls")
draw()
displayOpen("figure")
```

## 2. 2 - A slider that rebuilds itself mid-drag

<!-- field-box id="rerender" index="1" -->

### Narrative

From `code`

Drag "gain" from left to right. When it passes 0.5 the callback rebuilds this group,
replacing the slider's DOM under the pointer; the drag continues on the new one.

```edn [field-box id="rerender" role="properties"]
{"id" "rerender" "index" 1 "name" "2 - A slider that rebuilds itself mid-drag" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A slider that rebuilds itself mid-drag" "_tombstone" false "frame" #afield/rect [680.0 60.0 260.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rerender_code_.kt" "lines" 20 "characters" 664 "sha256" "32999f3d80822e726297a996a4c2149b6116fc93de8774a5baea8663da1e8f73"}]}
```

```kotlin [field-property box="rerender" name="code" path="rerender_code_.kt"]
// Drag "gain" from left to right. When it passes 0.5 the callback rebuilds this group,
// replacing the slider's DOM under the pointer; the drag continues on the new one.
var rebuilt = 0
fun surface() {
    controlGroup("controls") {
        slider("gain", 0.0, 1.0, 0.01, 0.2) {
            display(
                "seen",
                "<div style='font:18px Iosevka,monospace;color:#0cc8ed'>gain ${"%.2f".format(it)}  rebuilt $rebuilt</div>",
            )
            if (it > 0.5 && rebuilt == 0) {
                rebuilt++
                surface()
            }
            if (it < 0.3) rebuilt = 0
        }
    }
}
surface()
displayOpen("controls")
```

