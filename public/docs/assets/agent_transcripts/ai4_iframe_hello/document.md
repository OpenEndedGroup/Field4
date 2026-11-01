# Field Document: ai4_iframe_hello

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Iframe hello · run me

<!-- field-box id="ai4_iframe_hello" index="0" -->

```edn [field-box id="ai4_iframe_hello" role="properties"]
{"id" "ai4_iframe_hello" "index" 0 "name" "Iframe hello · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Iframe hello · run me" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ai4_iframe_hello_code_.kt" "lines" 5 "characters" 177 "sha256" "6635ccf45e6a417d4f0ee893917496117550fc4230826981de49f4bd88c8057f"}]}
```

```kotlin [field-property box="ai4_iframe_hello" name="code" path="ai4_iframe_hello_code_.kt"]
display(
    "page",
    """<iframe src="/iframe-ai4-demo.html" style="border:0;width:640px;height:380px;display:block"></iframe>""",
)
exposeOutputIframeTools("page", "hello")
```

