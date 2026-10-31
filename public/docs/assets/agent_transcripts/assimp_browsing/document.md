# Field Document: assimp_browsing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. browse a glTF scene

<!-- field-box id="assimp_scene" index="0" -->

```edn [field-box id="assimp_scene" role="properties"]
{"id" "assimp_scene" "index" 0 "name" "browse a glTF scene" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "browse a glTF scene" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "assimp_scene_code_.kt" "lines" 8 "characters" 320 "sha256" "a7f4634209d2ee421f2daa62461833116a9a29f5e945978623f8b121d96ebd91"}]}
```

```kotlin [field-property box="assimp_scene" name="code" path="assimp_scene_code_.kt"]
import afield.graphics.scene.GltfSupport

// Assimp's view of a glTF file: nodes, meshes, materials, animations
val scene = GltfSupport.loadModel("dev_docs/agents/fixtures/path_tracing/transforms.gltf")!!
column("columns", scene, width = 1300)
tree("tree", scene, width = 700)
displayOpen("columns")
displayOpen("tree")
```

