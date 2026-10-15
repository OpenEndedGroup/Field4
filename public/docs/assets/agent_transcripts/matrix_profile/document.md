# Field Document: matrix_profile

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 - contrast profile

<!-- field-box id="mp_contrast" index="0" -->

```edn [field-box id="mp_contrast" role="properties"]
{"id" "mp_contrast" "index" 0 "name" "4 - contrast profile" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - contrast profile" "_tombstone" false "frame" #afield/rect [60.0 2160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_contrast_code_.kt" "lines" 16 "characters" 800 "sha256" "af4f5f93f2df4a06182d5a8b19bdf64603b20eba2ccf18ce7be6b7f91f355ac3"}]}
```

```kotlin [field-property box="mp_contrast" name="code" path="mp_contrast_code_.kt"]
import afield.math.matrixprofile.*
import kotlin.math.*

// Every example has a sine bump at 100. Only the positive ones have a step at 400.
val rnd = java.util.Random(14)
fun example(withStep: Boolean) = 600 { i ->
    rnd.nextGaussian() * 0.3 +
        (if (i in 100 until 140) 3 * sin(2 * PI * (i - 100) / 40) else 0.0) +
        (if (withStep && i in 400 until 440) (if (i < 420) 3.0 else -3.0) else 0.0)
}.toDoubleArray()

val cp = contrastProfile(listOf(example(true), example(true)), listOf(example(false), example(false)), m = 40)
showSignals("positives" to cp.positive.toList(), "contrast" to cp.profile, channel = "main", normalize = true)

val plato = cp.platos(1).first()
markdown("info", "plato in example ${plato.example} at ${plato.offset}, contrast ${"%.2f".format(plato.contrast)}")
```

## 2. 1 - motifs

<!-- field-box id="mp_motifs" index="1" -->

```edn [field-box id="mp_motifs" role="properties"]
{"id" "mp_motifs" "index" 1 "name" "1 - motifs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - motifs" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_motifs_code_.kt" "lines" 14 "characters" 624 "sha256" "68cf1fcd51ee8c21e941a294c23a6f2b13007ea51f4c0e9451145dc2dc7f6c0c"}]}
```

```kotlin [field-property box="mp_motifs" name="code" path="mp_motifs_code_.kt"]
import afield.math.matrixprofile.*
import kotlin.math.*

// A slow random walk with the same wiggle hidden in it three times.
val rnd = java.util.Random(4)
var level = 0.0
val series = 3000 { level += rnd.nextGaussian() * 0.1; level }.toMutableList()
for (at in listOf(500, 1400, 2500)) for (k in 0 until 60) series[at + k] += 2 * sin(2 * PI * k / 60)

val mp = matrixProfile(series, m = 60)
showSignals("series" to series, "matrix profile" to mp.profile, channel = "main", normalize = true)

val motif = mp.motifs(1).first()
markdown("info", "motif at ${motif.indices}, pair distance ${"%.2f".format(motif.pairDistance)}")
```

## 3. 3 - several channels (mSTAMP)

<!-- field-box id="mp_multidimensional" index="2" -->

```edn [field-box id="mp_multidimensional" role="properties"]
{"id" "mp_multidimensional" "index" 2 "name" "3 - several channels (mSTAMP)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - several channels (mSTAMP)" "_tombstone" false "frame" #afield/rect [60.0 1460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_multidimensional_code_.kt" "lines" 16 "characters" 690 "sha256" "16a4d855177d883466f71243882a1a9391f98a40e7b048285fb752652157c327"}]}
```

```kotlin [field-property box="mp_multidimensional" name="code" path="mp_multidimensional_code_.kt"]
import afield.math.matrixprofile.*
import kotlin.math.*

// Four channels of noise. A 40-sample gesture appears in channels 1 and 3, at two times.
val rnd = java.util.Random(13)
val channels = 4 { 1500 { rnd.nextGaussian() }.toMutableList() }
for (at in listOf(300, 1000)) for (k in 0 until 40) {
    channels[1][at + k] += 4 * sin(2 * PI * k / 40)
    channels[3][at + k] -= 4 * sin(2 * PI * k / 40)
}

val mmp = multiMatrixProfile(channels, m = 40)
showSignals(*Array(4) { k -> "${k + 1} channel(s)" to mmp.profile(k + 1) }, channel = "main")

val motif = mmp.motifs(k = 2, count = 1).first()
markdown("info", "two-channel motif at ${motif.indices}, channels ${motif.channels.sorted()}")
```

## 4. 2 - discords

<!-- field-box id="mp_discords" index="3" -->

```edn [field-box id="mp_discords" role="properties"]
{"id" "mp_discords" "index" 3 "name" "2 - discords" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - discords" "_tombstone" false "frame" #afield/rect [60.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_discords_code_.kt" "lines" 17 "characters" 756 "sha256" "a63f1961181a2cf3cb18a52b8577e45d1a268852a33f85542f6a693d7584a8ba"}]}
```

```kotlin [field-property box="mp_discords" name="code" path="mp_discords_code_.kt"]
import afield.math.matrixprofile.*
import kotlin.math.*

// 40 beats of 80 samples. Beat 21 has a doubled, late pulse.
val rnd = java.util.Random(15)
fun bump(t: Double, at: Double, w: Double) = exp(-((t - at) / w).pow(2))
val beats = (40 * 80) { i ->
    val t = (i % 80).toDouble()
    val pulse = if (i / 80 == 21) 2 * (bump(t, 35.0, 3.0) + bump(t, 50.0, 3.0)) else 3 * bump(t, 30.0, 3.0)
    pulse - 0.6 * bump(t, 45.0, 6.0) + rnd.nextGaussian() * 0.05
}

val mp = matrixProfile(beats, m = 80)
showSignals("beats" to beats, "matrix profile" to mp.profile, channel = "main", normalize = true)

val discord = mp.discords(1).first()
markdown("info", "discord at ${discord.index} (beat ${discord.index / 80}), distance ${"%.2f".format(discord.distance)}")
```

