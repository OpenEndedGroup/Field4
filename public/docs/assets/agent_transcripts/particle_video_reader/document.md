# Field Document: particle_video_reader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Video particles

<!-- field-box id="particle_video_reader_only" index="0" -->

```edn [field-box id="particle_video_reader_only" role="properties"]
{"id" "particle_video_reader_only" "index" 0 "name" "Video particles" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Video particles" "_tombstone" false "frame" #afield/rect [40.0 40.0 400.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_video_reader_only_code_.kt" "lines" 2 "characters" 159 "sha256" "e370254795bc49041129bedc96498d3e0590814b502cca42d0b2776238b1e068"}]}
```

```kotlin [field-property box="particle_video_reader_only" name="code" path="particle_video_reader_only_code_.kt"]
val video = loadParticleVideo("public/docs/assets/particle_video/example.pvt.gz")
particleVideoView(video, initialSeconds = 7.2, scale = 1, pointRadius = 1.5)
```

