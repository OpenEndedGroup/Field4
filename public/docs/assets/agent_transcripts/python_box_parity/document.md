# Field Document: python_box_parity

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage"]}
```

## 1. Python root -- children of this box run as Python

<!-- field-box id="parity_root" index="0" -->

### Narrative

From `code`

The language root stays Kotlin; its `mainLanguage` makes its children Python.

```edn [field-box id="parity_root" role="properties"]
{"id" "parity_root" "index" 0 "name" "Python root -- children of this box run as Python" "parents" ["root"] "children" ["parity_error" "parity_controls"] "properties" {"children" #{#afield/box "parity_error" #afield/box "parity_controls"} "parents" #{#afield/box "root"} "name" "Python root -- children of this box run as Python" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0] "mainLanguage" "python" "isPythonRoot" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "parity_root_code_.kt" "lines" 2 "characters" 114 "sha256" "65f98a78ad5b8a80ceaa1f12c4aa7989ca1a014b60c7a5b72934f9ff4461e892"}]}
```

```kotlin [field-property box="parity_root" name="code" path="parity_root_code_.kt"]
// The language root stays Kotlin; its `mainLanguage` makes its children Python.
println("the root stays Kotlin")
```

## 2. an error is marked where it happened

<!-- field-box id="parity_error" index="1" -->

### Narrative

From `code`

The marker lands on line 6, inside mean(), where the division failed --
not on line 1, and not on the call on line 8.

```edn [field-box id="parity_error" role="properties"]
{"id" "parity_error" "index" 1 "name" "an error is marked where it happened" "parents" ["root" "parity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "parity_root"} "name" "an error is marked where it happened" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0] "code_language" "Python"} "blockProperties" [{"name" "code" "language" "python" "path" "parity_error_code_.kt" "lines" 8 "characters" 208 "sha256" "3eda0623c5564182f829fc4b3c1a2c3e8196a0e893b4d88bec0d2ba4382ccacc"}]}
```

```python [field-property box="parity_error" name="code" path="parity_error_code_.kt"]
# The marker lands on line 6, inside mean(), where the division failed --
# not on line 1, and not on the call on line 8.
readings = []

def mean(values):
    return sum(values) / len(values)

mean(readings)
```

## 3. a slider and a checkbox, with Python callbacks

<!-- field-box id="parity_controls" index="2" -->

### Narrative

From `code`

The same controls a Kotlin box draws, calling back into Python.

```edn [field-box id="parity_controls" role="properties"]
{"id" "parity_controls" "index" 2 "name" "a slider and a checkbox, with Python callbacks" "parents" ["root" "parity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "parity_root"} "name" "a slider and a checkbox, with Python callbacks" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0] "code_language" "Python"} "blockProperties" [{"name" "code" "language" "python" "path" "parity_controls_code_.kt" "lines" 19 "characters" 465 "sha256" "9f17e74559ab93e5a8fc0e702be47b4b814e7cbc3731d589ebe1e02fecfff9b9"}]}
```

```python [field-property box="parity_controls" name="code" path="parity_controls_code_.kt"]
# The same controls a Kotlin box draws, calling back into Python.
state = {"freq": 3.0, "square": False}

def redraw():
    f, sq = state["freq"], state["square"]
    wave = "square" if sq else "sine"
    display("main", f"<b>{wave}</b> at {f:.1f} Hz")

@slider("frequency", min=1, max=10, step=0.1, value=3.0)
def on_freq(v):
    state["freq"] = v
    redraw()

@checkbox("square wave")
def on_square(checked):
    state["square"] = checked
    redraw()

redraw()
```

