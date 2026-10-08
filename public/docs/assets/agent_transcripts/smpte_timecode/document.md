# Field Document: smpte_timecode

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. 2 - LTC encode, mangle, decode

<!-- field-box id="tc_ltc_roundtrip" index="0" -->

```edn [field-box id="tc_ltc_roundtrip" role="properties"]
{"id" "tc_ltc_roundtrip" "index" 0 "name" "2 - LTC encode, mangle, decode" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - LTC encode, mangle, decode" "_tombstone" false "frame" #afield/rect [520.0 60.0 400.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tc_ltc_roundtrip_code_.kt" "lines" 37 "characters" 2081 "sha256" "b4058a58c9c450ea05ef945b5809c61b41fd0046b344baec7c3a15062531933d"}]}
```

```kotlin [field-property box="tc_ltc_roundtrip" name="code" path="tc_ltc_roundtrip_code_.kt"]
import afield.timecode.*

// Four seconds of 29.97 drop-frame LTC across the one-minute boundary.
val start = Timecode.parse("00:00:58;00")
val ltc = LtcEncoder.render(start, 4.0, sampleRate = 48000)

// Play it back badly: quiet, offset, noisy, and speeding up from 0.9x to 1.1x.
val rng = java.util.Random(1)
val played = ArrayList<Float>()
var p = 0.0
while (p < ltc.size - 1) {
    val k = p.toInt()
    val v = ltc[k] + (ltc[k + 1] - ltc[k]) * (p - k).toFloat()
    played += v * 0.1f + 0.2f + rng.nextGaussian().toFloat() * 0.01f
    p += 0.9 + 0.2 * p / ltc.size
}

val frames = LtcDecoder(48000, TimecodeRate.FPS_2997_DF).decodeAll(played.toFloatArray())

// Decoded frame number against playback time, and the speed the decoder measured.
val w = 640.0; val h = 220.0
val tMax = played.size / 48000.0
val fMax = frames.maxOf { it.timecode - start }.toDouble()
val pts = frames.joinToString(" ") { "%.1f,%.1f".format(it.sampleStart / 48000 / tMax * w, h - (it.timecode - start) / fMax * h) }
val spd = frames.joinToString(" ") { "%.1f,%.1f".format(it.sampleStart / 48000 / tMax * w, h - (it.speed - 0.8) / 0.4 * h) }
display("main", """<svg width="${w + 60}" height="${h + 40}" style="font: 12px sans-serif">
  <g transform="translate(40,10)">
    <rect width="$w" height="$h" fill="none" stroke="#999"/>
    <polyline points="$pts" fill="none" stroke="#2a7" stroke-width="2"/>
    <polyline points="$spd" fill="none" stroke="#c52" stroke-width="1.5"/>
    <text x="4" y="14" fill="#2a7">decoded frame (0 … ${fMax.toInt()})</text>
    <text x="4" y="${h - 6}" fill="#c52">measured speed (0.8 … 1.2)</text>
    <text x="${w / 2 - 40}" y="${h + 22}" fill="#999">playback time, s (0 … ${"%.2f".format(tMax)})</text>
  </g></svg>""")
markdown("info", "decoded **${frames.size}** frames, ${frames.first().timecode} … ${frames.last().timecode}; " +
    "consecutive: ${frames.zipWithNext().all { (a, b) -> a.timecode + 1 == b.timecode }}; " +
    "across the minute: ${frames.map { it.timecode.toString() }.filter { it.startsWith("00:00:59;2") || it.startsWith("00:01:00;0") }}")
```

## 2. 1 - Drop-frame arithmetic

<!-- field-box id="tc_arithmetic" index="1" -->

```edn [field-box id="tc_arithmetic" role="properties"]
{"id" "tc_arithmetic" "index" 1 "name" "1 - Drop-frame arithmetic" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Drop-frame arithmetic" "_tombstone" false "frame" #afield/rect [60.0 60.0 400.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tc_arithmetic_code_.kt" "lines" 4 "characters" 312 "sha256" "643daedcfb5421aa6778d11208581e4c91976197150cc1b22dedf9df4a926f80"}]}
```

```kotlin [field-property box="tc_arithmetic" name="code" path="tc_arithmetic_code_.kt"]
import afield.timecode.*

val t = Timecode.parse("00:00:59;29")
markdown("main", "`$t` + 1 frame = `${t + 1}`  \n`${Timecode.parse("00:09:59;29")}` + 1 frame = `${Timecode.parse("00:09:59;29") + 1}`  \nan hour of drop-frame labels is ${"%.3f".format(Timecode.parse("01:00:00;00").totalSeconds)} s of wall time")
```

## 3. 3 - MIDI Time Code into a clock

<!-- field-box id="tc_mtc" index="2" -->

```edn [field-box id="tc_mtc" role="properties"]
{"id" "tc_mtc" "index" 2 "name" "3 - MIDI Time Code into a clock" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - MIDI Time Code into a clock" "_tombstone" false "frame" #afield/rect [60.0 260.0 400.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tc_mtc_code_.kt" "lines" 15 "characters" 795 "sha256" "5cfd800e55770c6a0da0b4f14124e737557a07dd65eea3d80a46df1824b94eba"}]}
```

```kotlin [field-property box="tc_mtc" name="code" path="tc_mtc_code_.kt"]
import afield.timecode.*

// A locate, then two seconds of running MTC at 25 fps, decoded as it would be on the far end of a cable.
val decoder = MtcDecoder()
val clock = TimecodeClock(TimecodeRate.FPS_25).followMtc(decoder)

var tc = Timecode.parse("09:59:58:00", TimecodeRate.FPS_25)
decoder.accept(Mtc.fullFrameMessage(tc))
val afterLocate = clock.now()
repeat(25) {                      // 25 cycles of 8 quarter frames = 50 frames
    Mtc.quarterFrameMessages(tc).forEach { decoder.accept(it) }
    tc += 2
}
markdown("main", "full frame: `${Mtc.fullFrame(Timecode.parse("09:59:58:00", TimecodeRate.FPS_25)).joinToString(" ") { "%02X".format(it) }}`  \n" +
    "clock after the locate: `$afterLocate`  \nclock after 50 frames of quarter frames: `${clock.now()}` (locked: ${clock.locked})")
```

## 4. 4 - A clock following LTC

<!-- field-box id="tc_follow_ltc" index="3" -->

```edn [field-box id="tc_follow_ltc" role="properties"]
{"id" "tc_follow_ltc" "index" 3 "name" "4 - A clock following LTC" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - A clock following LTC" "_tombstone" false "frame" #afield/rect [520.0 260.0 400.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tc_follow_ltc_code_.kt" "lines" 12 "characters" 651 "sha256" "8ba6477f07f8419fecf3f2745c0a797b27c6278830397670058adf873741c32a"}]}
```

```kotlin [field-property box="tc_follow_ltc" name="code" path="tc_follow_ltc_code_.kt"]
import afield.timecode.*

// Follow LTC delivered in audio-sized blocks, and read the clock between blocks, as a draw loop would.
val clock = TimecodeClock(TimecodeRate.FPS_24).followLtc(48000)
val ltc = LtcEncoder.render(Timecode.parse("01:00:00:00", TimecodeRate.FPS_24), 1.0)
val readings = ArrayList<String>()
for (i in ltc.indices step 512) {
    clock.feedLtc(ltc, i, minOf(512, ltc.size - i))
    Thread.sleep(10)              // ~ the real duration of 512 samples
    if (clock.locked && i % (512 * 16) == 0) readings += "${clock.now()} (${clock.source}, speed ${"%.2f".format(clock.speed)})"
}
markdown("main", readings.joinToString("  \n"))
```

