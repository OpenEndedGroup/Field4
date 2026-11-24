# Field Document: speech

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 5 - Analyse what was said

<!-- field-box id="analyse" index="0" -->

### Narrative

From `code`

Analyse what was said: the LPC envelope of the stressed vowel in each word.

```edn [field-box id="analyse" role="properties"]
{"id" "analyse" "index" 0 "name" "5 - Analyse what was said" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - Analyse what was said" "_tombstone" false "frame" #afield/rect [440.0 230.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_code_.kt" "lines" 17 "characters" 650 "sha256" "e099c7691ba85bf6eadd62bcbb82ff35f3b95650c3b66331e0cbb16a02cf3ede"}]}
```

```kotlin [field-property box="analyse" name="code" path="analyse_code_.kt"]
// Analyse what was said: the LPC envelope of the stressed vowel in each word.
val said = speak("Speak, analyse, and plot.")
val audio = said.resampled(48_000)
val curves =
    said.words.mapNotNull { w ->
        val v = w.phones.firstOrNull { it.stress == 1 } ?: return@mapNotNull null
        val mid = ((v.start + v.end) / 2 * 48_000).toInt()
        "${w.text} /${v.symbol}/" to
            Lpc.analyze(audio.copyOfRange(mid - 1024, mid + 1024), 52).envelopeDb(1025).take(214).toList()
    }
showSignals(
    *curves.toTypedArray(),
    x = 214 { it * 48_000.0 / 2048 },
    channel = "main",
    xTitle = "frequency (Hz)",
    yTitle = "dB",
)
```

## 2. 1 - Speak

<!-- field-box id="speak" index="1" -->

```edn [field-box id="speak" role="properties"]
{"id" "speak" "index" 1 "name" "1 - Speak" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Speak" "_tombstone" false "frame" #afield/rect [60.0 60.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "speak_code_.kt" "lines" 3 "characters" 175 "sha256" "43465eb97cc48b3b28a22187541d628a3397cbe55e27d08f77a76868ad0d28eb"}]}
```

```kotlin [field-property box="speak" name="code" path="speak_code_.kt"]
val said = speak("Hello! I am a box in Field, and this is my voice.")
displayAudioSamples(said.samples, said.sampleRate, label = "F2: ${said.words.size} words")
print(said.h)
```

## 3. 2 - Word timing

<!-- field-box id="word_timing" index="2" -->

```edn [field-box id="word_timing" role="properties"]
{"id" "word_timing" "index" 2 "name" "2 - Word timing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Word timing" "_tombstone" false "frame" #afield/rect [440.0 60.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "word_timing_code_.kt" "lines" 13 "characters" 539 "sha256" "35f77e81adda170e868aa423ae69d1fa2b93aa98fb0349eb2ca08c8ba78211c5"}]}
```

```kotlin [field-property box="word_timing" name="code" path="word_timing_code_.kt"]
val said = speak("Every word arrives with its own start and end.", voice = "M2")
val hop = 240 // 10 ms at 24 kHz
val loudness = (said.samples.size / hop) { i -> said.samples.slice(i * hop until (i + 1) * hop).maxOf { abs(it) } }
showSignals(
    "level" to loudness,
    x = loudness.indices.map { it * 0.01 },
    marks = said.words.map { it.text to it.start },
    channel = "main",
    xTitle = "seconds",
)
said.words.forEach {
    println("%-8s %.2f s  %s".format(it.text, it.start, it.phones.joinToString(" ") { p -> p.symbol }))
}
```

## 4. 4 - Sung on a pitch you choose (live)

<!-- field-box id="sung" index="3" -->

### Narrative

From `code`

The speech's envelope on a pulse whose pitch you choose: a sung contour.

```edn [field-box id="sung" role="properties"]
{"id" "sung" "index" 3 "name" "4 - Sung on a pitch you choose (live)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Sung on a pitch you choose (live)" "_tombstone" false "frame" #afield/rect [60.0 230.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sung_code_.kt" "lines" 4 "characters" 247 "sha256" "6c1d02f47b108034efc89567d2234b7839f35e9d2faf64617c1b5f1e6dc46e79"}]}
```

```kotlin [field-property box="sung" name="code" path="sung_code_.kt"]
// The speech's envelope on a pulse whose pitch you choose: a sung contour.
val said = speak("I can sing whatever pitch you give me.", voice = "M2")
val pitch = Param(110f).also { it.mod = SineOsc(0.5.p) * 30f }
said.sungOn(Impulse(pitch)) * 0.5f
```

## 5. 3 - Words play the drums

<!-- field-box id="words_play_drums" index="4" -->

### Narrative

From `code`

Stressed words strike a kick, the rest a hat, exactly on the words.

```edn [field-box id="words_play_drums" role="properties"]
{"id" "words_play_drums" "index" 4 "name" "3 - Words play the drums" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Words play the drums" "_tombstone" false "frame" #afield/rect [820.0 60.0 340.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "words_play_drums_code_.kt" "lines" 12 "characters" 668 "sha256" "5276f83beef2d27b450e2a71b633ded84b860744c55919eb68176cca2c92d544"}]}
```

```kotlin [field-property box="words_play_drums" name="code" path="words_play_drums_code_.kt"]
// Stressed words strike a kick, the rest a hat, exactly on the words.
val said = speak("Speech can play the drums, if you let it.")
val drums = said.pattern { if (it.stressed) "bd" else "hh" }
val dirt = DirtRender.requirePinnedLibrary() // real kick and hat samples, one shot each
val beat = patRenderStereoDirt(drums, 1, 1 / said.seconds, dirt, sampleRate = 48_000, strict = true).audio.mono
val voice = said.resampled(48_000)
val mix = (voice.size) { i -> 0.8f * voice[i] + 0.5f * beat.getOrElse(i) { 0f } }.toFloatArray()
displayAudioSamples(
    mix,
    48_000,
    label = "words as a pattern: ${drums.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value }}",
)
```

