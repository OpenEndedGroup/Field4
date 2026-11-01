# Field Document: linalg_thumbnails_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. mat3 heatmap

<!-- field-box id="linalg_mat3" index="0" -->

```edn [field-box id="linalg_mat3" role="properties"]
{"id" "linalg_mat3" "index" 0 "name" "mat3 heatmap" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mat3 heatmap" "_tombstone" false "frame" #afield/rect [80.0 240.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "linalg_mat3_code_.kt" "lines" 1 "characters" 84 "sha256" "bf41beeee70029abb49052dc50bf05fa03c300ed8e2fb6698bf0b3d6443c33b9"}]}
```

```kotlin [field-property box="linalg_mat3" name="code" path="linalg_mat3_code_.kt"]
afield.math.linalg.Matrix3f().set(1f, 0.5f, -0.5f, 0.25f, -1f, 0.75f, 0f, 0.1f, 2f)
```

## 2. vec2 arrow

<!-- field-box id="linalg_vec2" index="1" -->

```edn [field-box id="linalg_vec2" role="properties"]
{"id" "linalg_vec2" "index" 1 "name" "vec2 arrow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "vec2 arrow" "_tombstone" false "frame" #afield/rect [80.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "linalg_vec2_code_.kt" "lines" 1 "characters" 10 "sha256" "a13201f1b55e1a1e851c2f2b76d9180f269fdb8f1d67f664292326615a0a2eea"}]}
```

```kotlin [field-property box="linalg_vec2" name="code" path="linalg_vec2_code_.kt"]
vec(1, 2)
```

## 3. vec3 isometric arrow

<!-- field-box id="linalg_vec3" index="2" -->

```edn [field-box id="linalg_vec3" role="properties"]
{"id" "linalg_vec3" "index" 2 "name" "vec3 isometric arrow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "vec3 isometric arrow" "_tombstone" false "frame" #afield/rect [420.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "linalg_vec3_code_.kt" "lines" 1 "characters" 13 "sha256" "465e3a1d404ea6a4a93a14555a8637f8679a7eae01aea54eab0af88012d5785e"}]}
```

```kotlin [field-property box="linalg_vec3" name="code" path="linalg_vec3_code_.kt"]
vec(1, 2, 3)
```

## 4. mat4 translation heatmap

<!-- field-box id="linalg_mat4" index="3" -->

```edn [field-box id="linalg_mat4" role="properties"]
{"id" "linalg_mat4" "index" 3 "name" "mat4 translation heatmap" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mat4 translation heatmap" "_tombstone" false "frame" #afield/rect [420.0 240.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "linalg_mat4_code_.kt" "lines" 1 "characters" 54 "sha256" "9a2620038cf0fecba05bc9a003ced8fe989050e993c66901c428216e3f20af4e"}]}
```

```kotlin [field-property box="linalg_mat4" name="code" path="linalg_mat4_code_.kt"]
afield.math.linalg.Matrix4f().translation(3f, 4f, 5f)
```

