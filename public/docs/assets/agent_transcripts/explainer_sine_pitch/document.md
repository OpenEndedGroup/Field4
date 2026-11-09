# Field Document: explainer_sine_pitch

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. sine

<!-- field-box id="esp_sine" index="0" -->

### Narrative

From `code`

One sine wave, drawn and heard. How often it repeats is its pitch.

```edn [field-box id="esp_sine" role="properties"]
{"id" "esp_sine" "index" 0 "name" "sine" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "sine" "_tombstone" false "frame" #afield/rect [380.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "esp_sine_code_.kt" "lines" 27 "characters" 759 "sha256" "b9c6d4e3ace582fc53c7fefbe3f4d41ea2ca01f325e83158fcd8b10a88a14887"}]}
```

```kotlin [field-property box="esp_sine" name="code" path="esp_sine_code_.kt"]
// One sine wave, drawn and heard. How often it repeats is its pitch.
val pitch = Param(220f)
val loudness = Param(0f)

// Twenty milliseconds of the wave, 640 pixels wide.
fun draw(hz: Double) = lines(
    fline {
        moveTo(0.0, 120.0)
        for (x in 1..640) lineTo(x.toDouble(), 120.0 - 90.0 * sin(2 * PI * hz * 0.02 * x / 640))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    },
    channel = "wave",
    w = 640.0,
    h = 240.0,
)

draw(220.0)
controlGroup {
    slider("frequency", 110.0, 880.0, step = 1.0, initialValue = 220.0, law = ControlLaw.LOGARITHMIC) {
        pitch.base = it.toFloat()
        draw(it)
    }
    checkbox("sound") { loudness.base = if (it) 0.15f else 0f }
}

audio(SineOsc(pitch), gain = loudness)
```

## 2. deck

<!-- field-box id="esp_deck" index="1" -->

```edn [field-box id="esp_deck" role="properties"]
{"id" "esp_deck" "index" 1 "name" "deck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "deck" "_tombstone" false "frame" #afield/rect [60.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "esp_deck_code_.kt" "lines" 74 "characters" 2887 "sha256" "4762fb9edd21032a5dccb97c62b911ce83592818e3f81b56dc5221f364b95f7f"}]}
```

```kotlin [field-property box="esp_deck" name="code" path="esp_deck_code_.kt"]
import kotlinx.html.*

// Four slides: prose, a live figure and its slider, a cue that moves the slider, and
// the same figure heard. "sine" is the box that draws and plays the wave.
presentation(title = "A sine wave and its pitch") {
        step("A sine wave and its pitch", id = "opening") {
            audience {
                +"""
                # A sine wave and its pitch

                A sound is air pushed back and forth against your ear. The smoothest
                push is a sine wave: one even swing, repeated.

                How often the swing repeats is what you hear as pitch.
            """
                    .md
                p(classes = "muted") {
                    +"After “Sound” by Bartosz Ciechanowski, ciechanow.ski/sound. Words and figures made in Field."
                }
            }
            markdownNote("The question: what makes one note higher than another? [[next]]")
        }
        step("How often it repeats", id = "wave") {
            audience {
                figureBeside("sine") {
                    +"""
                    ## How often it repeats

                    The line is twenty milliseconds of the wave. Drag **frequency**:
                    as the number rises, more swings fit into the same time.

                    Frequency counts swings per second, in hertz.
                """
                        .md
                }
            }
            control("sine", "frequency", 220.0)
            control("sine", "sound", false)
            markdownNote("Let someone drag the slider. [[back]] [[next]]")
        }
        step("Twice as often", id = "octave") {
            audience {
                figureBeside("sine") {
                    +"""
                    ## Twice as often

                    220 Hz is the A below middle C. Doubling the frequency fits
                    twice as many swings in, and we hear the same note an octave up.
                """
                        .md
                }
            }
            control("sine", "frequency", 220.0)
            control("sine", "sound", false)
            cue("Up an octave") { control("sine", "frequency", 440.0, glideMs = 1500) }
            markdownNote("Next glides the slider from 220 to 440 Hz. [[back]] [[next]]")
        }
        step("Hear it", id = "listen") {
            audience {
                figureBeside("sine") {
                    +"""
                    ## Hear it

                    Tick **sound**, then drag **frequency**. The wave you see is the
                    tone you hear: one box draws it and plays it.
                """
                        .md
                }
            }
            cue("Sound on") { control("sine", "sound", true) }
            markdownNote("Next turns the sound on; leaving the talk stops it. [[back]] [[finish]]")
        }
    }
    .present()
```

