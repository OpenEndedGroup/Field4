# Field Document: remotear_start

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. KotlinJS → RemoteAR

<!-- field-box id="agent_remotear_start_root" index="0" -->

```edn [field-box id="agent_remotear_start_root" role="properties"]
{"id" "agent_remotear_start_root" "index" 0 "name" "KotlinJS → RemoteAR" "parents" ["root"] "children" ["agent_remotear_start"] "properties" {"children" #{#afield/box "agent_remotear_start"} "parents" #{#afield/box "root"} "name" "KotlinJS → RemoteAR" "_tombstone" false "frame" #afield/rect [50.0 50.0 300.0 100.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" []}
```

## 2. RemoteAR Starter

<!-- field-box id="agent_remotear_start" index="1" -->

### Narrative

From `code`

{{ remoteAR }}
Open /extensions/remote_ar/index.html?debug=1 on the browser or headset.
Edit this box and Launch it again to replace the running scene.

```edn [field-box id="agent_remotear_start" role="properties"]
{"id" "agent_remotear_start" "index" 1 "name" "RemoteAR Starter" "parents" ["root" "agent_remotear_start_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_remotear_start_root"} "name" "RemoteAR Starter" "_tombstone" false "frame" #afield/rect [90.0 150.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_remotear_start_code_.kt" "lines" 19 "characters" 502 "sha256" "bcc8d399b77056d05f4a2d309d8fb22b274c6be89bc140c3c5ff3510f2afc35e"}]}
```

```kotlin [field-property box="agent_remotear_start" name="code" path="agent_remotear_start_code_.kt"]
// {{ remoteAR }}
// Open /extensions/remote_ar/index.html?debug=1 on the browser or headset.
// Edit this box and Launch it again to replace the running scene.
onStart {
    val cube = addBox("starter-cube", 0.3).mesh
    cube.position.set(0.0, 1.2, -1.5)

    onFrame { frame ->
        cube.rotation.x = frame.time * 0.35
        cube.rotation.y = frame.time
    }

    onStop {
        SCENE.remove(cube)
        println("RemoteAR starter stopped")
    }

    println("RemoteAR starter running")
}
```

