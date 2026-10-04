# Field Document: csound_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory" "mainLanguage"]}
```

## 1. keys

<!-- field-box id="cs_keys" index="0" -->

### Narrative

From `code`

Launch "pluck" first, then launch this: MIDI notes into its massign'ed instr Key.

```edn [field-box id="cs_keys" role="properties"]
{"id" "cs_keys" "index" 0 "name" "keys" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "keys" "_tombstone" false "frame" #afield/rect [230.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_keys_code_.kt" "lines" 11 "characters" 309 "sha256" "ade619922f73f14427c12340566cf44565f9ea6daad40236f9c663072157cc70"}]}
```

```kotlin [field-property box="cs_keys" name="code" path="cs_keys_code_.kt"]
// Launch "pluck" first, then launch this: MIDI notes into its massign'ed instr Key.
val pluck = csoundOf("pluck").csound
val notes = listOf(62, 65, 69, 72, 69, 65)
var i = 0
loop {
    val note = notes[i++ % notes.size]
    pluck.noteOn(0, note, 90)
    delay(220)
    pluck.noteOff(0, note)
    delay(30)
}
```

## 2. a table from Kotlin

<!-- field-box id="cs_table" index="1" -->

### Narrative

From `code`

A wavetable computed in Kotlin, copied into ftable 1, played by poscil, and read back.

```edn [field-box id="cs_table" role="properties"]
{"id" "cs_table" "index" 1 "name" "a table from Kotlin" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a table from Kotlin" "_tombstone" false "frame" #afield/rect [610.0 180.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_table_code_.kt" "lines" 20 "characters" 1020 "sha256" "bc6aebeeffc2a47d95b9f70219926a43fe9e683feacfb51462a4e7e0a93441f6"}]}
```

```kotlin [field-property box="cs_table" name="code" path="cs_table_code_.kt"]
// A wavetable computed in Kotlin, copied into ftable 1, played by poscil, and read back.
val cs = afield.csound.CsoundInstance("""
giWave  ftgen  1, 0, 4096, 10, 1
instr Play
  asig  poscil  0.3, 220, giWave
  outs    asig, asig
endin
""", "i \"Play\" 0 0.1")
val partials = listOf(1.0, 0.0, 0.33, 0.0, 0.2, 0.0, 0.14)     // odd harmonics: a square-ish wave
val wave = DoubleArray(cs.tableLength(1)) { i ->
    val ph = 2 * PI * i / 4096
    partials.withIndex().sumOf { (k, a) -> a * sin((k + 1) * ph) }
}
cs.writeTable(1, wave)
val back = cs.table(1)!!
val (left, _) = cs.renderOffline()
cs.close()
println("wrote ${wave.size} values; read back equal: ${wave.contentEquals(back)}; rendered ${left.size} frames, peak ${"%.3f".format(left.maxOf { abs(it) })}")
showSignals("ftable 1, read back" to (0 until 4096 step 8).map { back[it] }, channel = "table", title = "ftable 1, written from Kotlin")
showSignals("poscil 220 Hz" to (0 until 480).map { left[it] }, channel = "played", title = "what poscil plays from it")
```

## 3. csound — children of this box are Csound

<!-- field-box id="cs_root" index="2" -->

### Narrative

From `code`

This root stays Kotlin; its `mainLanguage` makes the boxes under it Csound.

```edn [field-box id="cs_root" role="properties"]
{"id" "cs_root" "index" 2 "name" "csound — children of this box are Csound" "parents" ["root"] "children" ["cs_pluck" "cs_fx" "cs_typo"] "properties" {"children" #{#afield/box "cs_pluck" #afield/box "cs_fx" #afield/box "cs_typo"} "parents" #{#afield/box "root"} "name" "csound — children of this box are Csound" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 80.0] "mainLanguage" "csound"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_root_code_.kt" "lines" 2 "characters" 127 "sha256" "bc49fa7e257e5b0dab9722bbfe117f0d9ed5142e79e6211faa333a9118d40b48"}]}
```

```kotlin [field-property box="cs_root" name="code" path="cs_root_code_.kt"]
// This root stays Kotlin; its `mainLanguage` makes the boxes under it Csound.
println(afield.csound.CsoundLibrary.describe())
```

## 4. typo

<!-- field-box id="cs_typo" index="3" -->

```edn [field-box id="cs_typo" role="properties"]
{"id" "cs_typo" "index" 3 "name" "typo" "parents" ["root" "cs_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cs_root"} "name" "typo" "_tombstone" false "frame" #afield/rect [420.0 180.0 160.0 80.0] "code_language" "Csound"} "blockProperties" [{"name" "code" "language" "csound" "path" "cs_typo_code_.kt" "lines" 11 "characters" 233 "sha256" "01c547fa821dcbe0c0736770446432daaa213b5a5bcdb4905d264ca7c38b8677"}]}
```

```csound [field-property box="cs_typo" name="code" path="cs_typo_code_.kt"]
<CsoundSynthesizer>
<CsInstruments>
instr Beep
  asig  oscilx  0.2, 440       ; there is no oscilx: Csound's error lands on this line
  outs    asig, asig
endin
</CsInstruments>
<CsScore>
i "Beep" 0 1
</CsScore>
</CsoundSynthesizer>
```

## 5. pluck

<!-- field-box id="cs_pluck" index="4" -->

```edn [field-box id="cs_pluck" role="properties"]
{"id" "cs_pluck" "index" 4 "name" "pluck" "parents" ["root" "cs_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cs_root"} "name" "pluck" "_tombstone" false "frame" #afield/rect [40.0 180.0 160.0 80.0] "code_language" "Csound"} "blockProperties" [{"name" "code" "language" "csound" "path" "cs_pluck_code_.kt" "lines" 59 "characters" 1542 "sha256" "03458dcbd4d083612fe3955885d0c377b69343759735e6b8b22d58fb9e1d0742"}]}
```

```csound [field-property box="cs_pluck" name="code" path="cs_pluck_code_.kt"]
<CsoundSynthesizer>
<CsOptions>
-m0
</CsOptions>
<CsInstruments>
; sr, ksmps, nchnls and 0dbfs come from Field: 48000, 32, 2 and 1.

chn_k "cutoff", 1, 3, 2400, 200, 8000   ; written by Field, read here
chn_k "level",  2                       ; written here, read by Field
chn_S "voice",  3

massign 0, "Key"                        ; MIDI notes, from any channel

instr Pluck                             ; p4 MIDI note, p5 level in dBFS
  icps  =         cpsmidinn(p4)
  asig  pluck     ampdbfs(p5), icps, icps, 0, 1
  kcut  chnget    "cutoff"
  kcut  port      kcut, 0.02
  asig  butterlp  asig, kcut
  aenv  linsegr   1, p3, 1, 0.3, 0
  asig  =         asig * aenv
  chnmix    asig * 0.25, "reverb"
  outs      asig, asig
endin

instr Key
  inote notnum
  prints    "Key %d\n", inote
  icps  cpsmidi
  iamp  ampmidi   0.3
  asig  pluck     iamp, icps, icps, 0, 1
  aenv  madsr     0.005, 0.1, 0.6, 0.4
  chnmix    asig * aenv * 0.25, "reverb"
  outs      asig * aenv, asig * aenv
endin

instr Reverb
  ain     chnget    "reverb"
  aL, aR  reverbsc  ain, ain, 0.85, 10000
  outs      aL, aR
  chnclear  "reverb"
  krms    rms       aL + ain
  chnset    krms, "level"
  outvalue  "level", krms
  Svoice  chnget    "voice"
  outvalue  "said", Svoice
endin
</CsInstruments>
<CsScore>
i "Reverb" 0 z
i "Pluck"  0    0.5  57  -12
i .        +    .    60  .
i .        +    .    64  .
i .        +    1.5  67  -9
; Alt-Enter one of these while the box plays:
i "Pluck"  0    1    69  -9
i "Pluck"  0    2    45  -6
</CsScore>
</CsoundSynthesizer>
```

## 6. fx

<!-- field-box id="cs_fx" index="5" -->

```edn [field-box id="cs_fx" role="properties"]
{"id" "cs_fx" "index" 5 "name" "fx" "parents" ["root" "cs_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cs_root"} "name" "fx" "_tombstone" false "frame" #afield/rect [230.0 180.0 160.0 80.0] "code_language" "Csound"} "blockProperties" [{"name" "code" "language" "csound" "path" "cs_fx_code_.kt" "lines" 17 "characters" 367 "sha256" "ccfa6ddb31c12d342ceed04e7a2647a43398c0ef1e2dd19fd044023b41dd1364"}]}
```

```csound [field-property box="cs_fx" name="code" path="cs_fx_code_.kt"]
<CsoundSynthesizer>
<CsInstruments>
; The a-rate channel "in" is fed by Field: launch "Field into fx".
chn_a "in",  1
chn_a "wet", 2

instr Space
  ain     chnget    "in"
  aL, aR  reverbsc  ain, ain, 0.9, 8000
  outs      ain * 0.5 + aL * 0.5, ain * 0.5 + aR * 0.5
  chnset    aL, "wet"
endin
</CsInstruments>
<CsScore>
i "Space" 0 z
</CsScore>
</CsoundSynthesizer>
```

## 7. talk to pluck

<!-- field-box id="cs_talk" index="6" -->

### Narrative

From `code`

Launch "pluck" first. Every line here talks to the Csound it is playing.

```edn [field-box id="cs_talk" role="properties"]
{"id" "cs_talk" "index" 6 "name" "talk to pluck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "talk to pluck" "_tombstone" false "frame" #afield/rect [40.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_talk_code_.kt" "lines" 16 "characters" 817 "sha256" "48f469c0807467c6826a9163cae7487fe06818b5e4f299011e7d2c7ccf42054f"}]}
```

```kotlin [field-property box="cs_talk" name="code" path="cs_talk_code_.kt"]
// Launch "pluck" first. Every line here talks to the Csound it is playing.
val pluck = csoundOf("pluck").csound

pluck.setChannel("cutoff", 900)                 // chnget "cutoff" reads it next k-cycle
pluck.setStringChannel("voice", "from Kotlin")  // an S channel; Reverb outvalues it back
println("level now: ${"%.4f".format(pluck.channel("level"))}")

// Score events with p-fields from Kotlin values: a rising arpeggio, half a second apart.
listOf(57, 60, 64, 69).forEachIndexed { k, note ->
    pluck.event("i \"Pluck\" ${k * 0.5} 0.5 $note -12")
}

// outvalue calls back on Field's main thread.
pluck.onOutvalue("said") { println("Csound said: $it") }
var n = 0
pluck.onOutvalue("level") { v -> if (n++ % 40 == 0) display("level", "<div style='padding:8px'>Reverb rms ${"%.4f".format(v as Double)}</div>") }
```

## 8. Field into fx

<!-- field-box id="cs_into_fx" index="7" -->

### Narrative

From `code`

Launch "fx" first. A Field sawtooth goes into its a-rate channel "in"; the reverb's
left channel, chnset into "wet", comes back as a Field signal that Field plays.

```edn [field-box id="cs_into_fx" role="properties"]
{"id" "cs_into_fx" "index" 7 "name" "Field into fx" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Field into fx" "_tombstone" false "frame" #afield/rect [420.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_into_fx_code_.kt" "lines" 6 "characters" 301 "sha256" "6e4f915163f5241425bad1c276374544225331124a3bfbf19f2f17a4b83de6b2"}]}
```

```kotlin [field-property box="cs_into_fx" name="code" path="cs_into_fx_code_.kt"]
// Launch "fx" first. A Field sawtooth goes into its a-rate channel "in"; the reverb's
// left channel, chnset into "wet", comes back as a Field signal that Field plays.
val fx = csoundOf("fx")
val saw = SawOsc(Param(110f)) * Param(0.15f)
fx.node.connect("in", saw)
fx.node.audioOut("wet").pan(-0.5f)
```

## 9. through Csound, measured

<!-- field-box id="cs_measured" index="8" -->

### Narrative

From `code`

Offline: a Field 1 kHz sine through Csound's butterlp at 500 Hz, and back.

```edn [field-box id="cs_measured" role="properties"]
{"id" "cs_measured" "index" 8 "name" "through Csound, measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "through Csound, measured" "_tombstone" false "frame" #afield/rect [610.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_measured_code_.kt" "lines" 18 "characters" 1010 "sha256" "c3478e06efd46154a5fb8eca0a89bee9e6bd558532b60a9f8ede252bc2828d4c"}]}
```

```kotlin [field-property box="cs_measured" name="code" path="cs_measured_code_.kt"]
// Offline: a Field 1 kHz sine through Csound's butterlp at 500 Hz, and back.
val cs = afield.csound.CsoundInstance("""
instr Filter
  ain   chnget    "in"
  alow  butterlp  ain, 500
  chnset    alow, "low"
endin
""", "i \"Filter\" 0 z")
val node = cs.node(audioIn = mapOf("in" to SineOsc(Param(1000f))))
val (dry, low) = renderSignalsOffline(0.5, SineOsc(Param(1000f)), node.audioOut("low"))
cs.close()

fun rms(x: FloatArray) = sqrt(x.drop(4800).sumOf { it.toDouble() * it } / (x.size - 4800))
val expected = 1.0 / sqrt(1.0 + 16.0)    // a 2nd-order Butterworth at twice its cutoff
println("in rms ${"%.4f".format(rms(dry))}, out rms ${"%.4f".format(rms(low))}: gain ${"%.4f".format(rms(low) / rms(dry))}, Butterworth says ${"%.4f".format(expected)}")
val window = 4800 until 5040
showSignals("Field sine" to window.map { dry[it] }, "after Csound's butterlp" to window.map { low[it] },
    x = window.map { it / 48.0 }, channel = "signals", title = "1 kHz in, butterlp 500 Hz, back to Field", xTitle = "ms")
```

