# Field Document: remotear_scene_motion

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "mainLanguage"]}
```

## 1. RemoteAR Scene + Motion

<!-- field-box id="agent_remotear_scene_motion" index="0" -->

### Narrative

From `code`

{{ remoteAR }}
A lit torus-knot rig, an FLine orbit ring transformed by rotate()/+vec/*scale, and a
glTF asset loaded + animated through the auto AnimationMixer.

```edn [field-box id="agent_remotear_scene_motion" role="properties"]
{"id" "agent_remotear_scene_motion" "index" 0 "name" "RemoteAR Scene + Motion" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "RemoteAR Scene + Motion" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0] "mainLanguage" "kotlinjs"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_remotear_scene_motion_code_.kt" "lines" 61 "characters" 2642 "sha256" "44e13fbeb32794b2733311ce8731aa0e003a0d2967e6be605a5f9f91ea8db11c"}]}
```

```kotlin [field-property box="agent_remotear_scene_motion" name="code" path="agent_remotear_scene_motion_code_.kt"]
// {{ remoteAR }}
// A lit torus-knot rig, an FLine orbit ring transformed by rotate()/+vec/*scale, and a
// glTF asset loaded + animated through the auto AnimationMixer.
onStart {
    // --- richer materials / geometries / lights (roadmap item 3) ---
    val gold = THREE.MeshStandardMaterial()
    gold.metalness = 0.9
    gold.roughness = 0.25
    gold.color.setRGB(0.85, 0.65, 0.2)

    val knot = THREE.Mesh(THREE.TorusKnotGeometry(0.4, 0.12, 96, 12), gold)
    knot.name = "knot"
    knot.castShadow = true
    knot.position.set(0.0, 1.2, -1.5)
    knot.lookAt(0.0, 1.6, 0.0)

    val rig = THREE.Group()
    rig.name = "rig"
    rig.add(knot)
    SCENE.add(rig)

    val key = THREE.DirectionalLight(0xffffff, 1.4)
    key.position.set(2.0, 4.0, 2.0)
    SCENE.add(key)
    SCENE.add(THREE.AmbientLight(0x404050, 0.7))

    // --- FLine transform operators (roadmap item 1) ---
    var ring = FLine()
    ring.moveTo(0.6, 0.0, 0.0)
    var a = 0.0
    while (a < 6.3) {
        ring.lineTo(0.6 * kotlin.math.cos(a), 0.0, 0.6 * kotlin.math.sin(a))
        a += 0.3
    }
    // rotate the whole ring, lift + push it back, then shrink it: `+ Matrix4`, `+ Vector3`, `* scalar`
    val orbit = ((ring + rotate(0.4)) + vec(0.0, 1.2, -1.5)) * 0.9
    orbit.stroked = true
    orbit.pointed = false
    stage.orbit = orbit

    // --- assets + animation (roadmap item 2): load a glTF and drive the mixer via play() ---
    // A head can only fetch over http, so the asset is a URL and not a path. This one is
    // the CC0 Khronos ToyCar committed at public/docs/assets/fabricate_demo/ToyCar.glb,
    // which Field's server mounts at /sample-assets — so nothing here needs a download or
    // a directory of your own. (`mapDirectory` looks like the way to serve a folder of
    // your own and is not: it sends a message the server does not handle. Copy the model
    // next to ToyCar.glb, or add a static mount beside that one in Server.kt.)
    loadGLTF("/sample-assets/ToyCar.glb", "toycar") { g ->
        g.scene.position.set(0.0, 0.9, -1.2)
        g.scene.scale.set(6.0, 6.0, 6.0)          // the file is in metres and it is a 7cm toy
        log("toy car loaded: meshes " + g.meshes + ", anims " + g.animations)
        // clipAction(clip).play() on every clip, updated by the XR AnimationMixer. The
        // ToyCar ships no clips, so this does nothing you can see — swap in a model that
        // has some (the log line above prints how many arrived) and it animates.
        g.play()
    }

    // gently spin the rig on the head's own render clock
    onFrame { rig.rotation.y += 0.01 }
    log("AR scene + motion ready")
}
```

