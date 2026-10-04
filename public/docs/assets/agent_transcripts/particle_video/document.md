# Field Document: particle_video

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 3 · track a movie

<!-- field-box id="particle_video_track" index="0" -->

```edn [field-box id="particle_video_track" role="properties"]
{"id" "particle_video_track" "index" 0 "name" "3 · track a movie" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · track a movie" "_tombstone" false "frame" #afield/rect [920.0 40.0 400.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_video_track_code_.kt" "lines" 10 "characters" 359 "sha256" "64939fb58f33a1ae610109bac2ab124bd58a07aa36ae1364d16da32ff3deb86c"}]}
```

```kotlin [field-property box="particle_video_track" name="code" path="particle_video_track_code_.kt"]
val tracker = particleTracker()
suspend fun trackMovie() {
    val run = tracker.track(
        "public/docs/assets/particle_video/source.mkv",
        ParticleTrackingOptions(particles = 24000, colorLimit = 1.0),
    )
    println("cached=${run.cacheHit}; tracks=${run.tracksFile}")
    particleVideoView(run.load(), pointRadius = .45)
}
start(::trackMovie)
```

## 2. 2 · scrub recorded particles

<!-- field-box id="particle_video_reader" index="1" -->

```edn [field-box id="particle_video_reader" role="properties"]
{"id" "particle_video_reader" "index" 1 "name" "2 · scrub recorded particles" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · scrub recorded particles" "_tombstone" false "frame" #afield/rect [480.0 40.0 400.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_video_reader_code_.kt" "lines" 2 "characters" 159 "sha256" "e370254795bc49041129bedc96498d3e0590814b502cca42d0b2776238b1e068"}]}
```

```kotlin [field-property box="particle_video_reader" name="code" path="particle_video_reader_code_.kt"]
val video = loadParticleVideo("public/docs/assets/particle_video/example.pvt.gz")
particleVideoView(video, initialSeconds = 7.2, scale = 1, pointRadius = 1.5)
```

## 3. 1 · video particles

<!-- field-box id="particle_video_guide" index="2" -->

```edn [field-box id="particle_video_guide" role="properties"]
{"id" "particle_video_guide" "index" 2 "name" "1 · video particles" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · video particles" "_tombstone" false "frame" #afield/rect [40.0 40.0 400.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_video_guide_code_.kt" "lines" 11 "characters" 528 "sha256" "c3eda6f2487862f66d082dd19041e356e9b786bd026cb71e05b42a5589f2f3d5"}]}
```

```kotlin [field-property box="particle_video_guide" name="code" path="particle_video_guide_code_.kt"]
markdown("guide", """
# Video particles
Box 2 scrubs recorded tracks without Python. Its portable example retains complete
trajectories from a representative subset of the 24,000-particle dance run.

Prepare the optional environment before launching box 3, or set
`FIELD_PARTICLE_TRACKER_PYTHON` to an existing Torch/Torchvision interpreter.
Replace the movie filename to track your own footage. Width 0 retains native resolution.

[Video tracking and setup](https://openendedgroup.com/Field4/particle_video/)
""".trimIndent())
```

