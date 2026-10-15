# Field Document: matrix_profile

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 - contrast profile

<!-- field-box id="mp_contrast" index="0" -->

```edn [field-box id="mp_contrast" role="properties"]
{"id" "mp_contrast" "index" 0 "name" "4 - contrast profile" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - contrast profile" "_tombstone" false "frame" #afield/rect [60.0 2160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_contrast_code_.kt" "lines" 21 "characters" 967 "sha256" "64202d7404524787e411166adc66e5cdb15401cac52233171439cc97e0c5ff75"}]}
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
val platos = cp.platos(2)
val plato = platos.first()

showSignals(
    "positives" to cp.positive.toList(), "contrast" to cp.profile,
    regions = platos.map { SignalRegion("plato", it.index, it.index + cp.m, "#ffe066") },
    title = "plato in example ${plato.example} at ${plato.offset}, contrast ${"%.2f".format(plato.contrast)}",
    channel = "main", normalize = true, width = 1000, height = 420, floatingLegend = true,
)
```

## 2. 1 - motifs

<!-- field-box id="mp_motifs" index="1" -->

```edn [field-box id="mp_motifs" role="properties"]
{"id" "mp_motifs" "index" 1 "name" "1 - motifs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - motifs" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_motifs_code_.kt" "lines" 18 "characters" 766 "sha256" "a9fa432767deed6dd25354a6513df339e9ee9dac183a7bfe8112f241de10f8cc"}]}
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
val motif = mp.motifs(1).first()

showSignals(
    "series" to series, "matrix profile" to mp.profile,
    regions = motif.indices.map { SignalRegion("motif", it, it + mp.m, "#8fff9a") },
    title = "motif at ${motif.indices}, pair distance ${"%.2f".format(motif.pairDistance)}",
    channel = "main", normalize = true, width = 1000, height = 420, floatingLegend = true,
)
```

## 3. 3 - several channels (mSTAMP)

<!-- field-box id="mp_multidimensional" index="2" -->

```edn [field-box id="mp_multidimensional" role="properties"]
{"id" "mp_multidimensional" "index" 2 "name" "3 - several channels (mSTAMP)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - several channels (mSTAMP)" "_tombstone" false "frame" #afield/rect [60.0 1460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_multidimensional_code_.kt" "lines" 20 "characters" 833 "sha256" "878e93d42d473322dbf8df3d7d69faa4186744bd51414033932f86fd96e64820"}]}
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
val motif = mmp.motifs(k = 2, count = 1).first()

showSignals(
    *Array(4) { k -> "${k + 1} channel(s)" to mmp.profile(k + 1) },
    regions = motif.indices.map { SignalRegion("motif", it, it + mmp.m, "#8fff9a") },
    title = "two-channel motif at ${motif.indices}, channels ${motif.channels.sorted()}",
    channel = "main", width = 1000, height = 420, floatingLegend = true,
)
```

## 4. 2 - discords

<!-- field-box id="mp_discords" index="3" -->

```edn [field-box id="mp_discords" role="properties"]
{"id" "mp_discords" "index" 3 "name" "2 - discords" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - discords" "_tombstone" false "frame" #afield/rect [60.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mp_discords_code_.kt" "lines" 21 "characters" 908 "sha256" "efefd915dc65f14ecc99d88573b8390fbae84afbd69a0ccf402b729cd2ad5733"}]}
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
val discord = mp.discords(1).first()

showSignals(
    "beats" to beats, "matrix profile" to mp.profile,
    regions = listOf(SignalRegion("discord", discord.index, discord.index + mp.m, "#ff7a9e")),
    title = "discord at ${discord.index} (beat ${discord.index / 80}), distance ${"%.2f".format(discord.distance)}",
    channel = "main", normalize = true, width = 1000, height = 420, floatingLegend = true,
)
```

