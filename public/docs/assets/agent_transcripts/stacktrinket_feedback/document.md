# Field Document: stacktrinket_feedback

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="stacktrinket_renderer" index="0" -->

```edn [field-box id="stacktrinket_renderer" role="properties"]
{"id" "stacktrinket_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["stacktrinket_pulse_ring" "stacktrinket_feedback_fog" "stacktrinket_persistent_bloom" "stacktrinket_store_channels" "stacktrinket_subroutine_cse" "stacktrinket_four_memory_bands" "stacktrinket_long_memory_ecology"] "properties" {"children" #{#afield/box "stacktrinket_pulse_ring" #afield/box "stacktrinket_feedback_fog" #afield/box "stacktrinket_persistent_bloom" #afield/box "stacktrinket_store_channels" #afield/box "stacktrinket_subroutine_cse" #afield/box "stacktrinket_four_memory_bands" #afield/box "stacktrinket_long_memory_ecology"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_renderer_code_.kt" "lines" 12 "characters" 413 "sha256" "736875f16bf7e075d4ed1438dc31191cda020964164ebd9146d84fc79ccc8560"}]}
```

```kotlin [field-property box="stacktrinket_renderer" name="code" path="stacktrinket_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("StackTrinket", initialDimensions = Rect(80.0, 80.0, 760.0, 760.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. long memory ecology

<!-- field-box id="stacktrinket_long_memory_ecology" index="1" -->

### Narrative

From `code`

Long memory ecology: the previous frame steers where it is read from next. Its own red
and green, then blue and alpha, become offsets into itself; two slow waves add a drift;
fbm warped by the old picture is mixed in; and `ema` stores the result as four
memories at rates from 0.006 to 0.09. `mirror` folds the total back into 0..1.

The fold is what decides the look. `dup average … + +` adds each pixel's average back
on top every frame, so its values keep climbing, and `mirror` folds them into grain
that never settles. Delete that line and the reads, which together take in about 1.65
times the old picture, slowly fill the memories instead, into a pale moving fog.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_long_memory_ecology" role="properties"]
{"id" "stacktrinket_long_memory_ecology" "index" 1 "name" "long memory ecology" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "long memory ecology" "_tombstone" false "frame" #afield/rect [40.0 1280.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_long_memory_ecology_code_.kt" "lines" 14 "characters" 742 "sha256" "0426dc183b4013a5a93011527d277b06c2caf8d0c2380ea81599ae9c7f3b2a79"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_long_memory_ecology_stackSL_.stacksl" "lines" 20 "characters" 375 "sha256" "3c6bfb82de5a35553d0c581371435d616877f88acc0c9b8f29e90c7ff045c858"}]}
```

```kotlin [field-property box="stacktrinket_long_memory_ecology" name="code" path="stacktrinket_long_memory_ecology_code_.kt"]
/*
Long memory ecology: the previous frame steers where it is read from next. Its own red
and green, then blue and alpha, become offsets into itself; two slow waves add a drift;
fbm warped by the old picture is mixed in; and `ema` stores the result as four
memories at rates from 0.006 to 0.09. `mirror` folds the total back into 0..1.

The fold is what decides the look. `dup average … + +` adds each pixel's average back
on top every frame, so its values keep climbing, and `mirror` folds them into grain
that never settles. Delete that line and the reads, which together take in about 1.65
times the old picture, slowly fill the memories instead, into a pale moving fog.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_long_memory_ecology" name="stackSL" path="stacktrinket_long_memory_ecology_stackSL_.stacksl"]
# Nearby reads move through previous RGBA; the result is stored as four slow memories.
prev
dup xy 0.012 * sample
swap zw 0.009 * sample
+ 0.5 *
p ]wr9 0.006 * 0.004c0.17 +
p ]wa7 0.006 * 0.004s0.11 +
vec2 sample
0.65 * +
uv 5.0 * prev 0.4 * + 0.02t + fbm
dup 0.23 + dup 0.37 + dup 0.61 + vec4
0.08 * +
prev
0.006_0.09
ema
dup average
0.015pulse0.23d0.09
+ +
mirror
saturate
```

## 3. four memory bands

<!-- field-box id="stacktrinket_four_memory_bands" index="2" -->

### Narrative

From `code`

Four memory bands: red, green, blue and alpha are four separate memories of one slowly
moving fbm field, each offset a little. `0.015_0.18` is a range vector,
vec4(0.015, 0.07, 0.125, 0.18): as the `ema` rate it makes red the slowest memory and
alpha the fastest, so the colours separate where the field moves.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_four_memory_bands" role="properties"]
{"id" "stacktrinket_four_memory_bands" "index" 2 "name" "four memory bands" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "four memory bands" "_tombstone" false "frame" #afield/rect [640.0 920.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_four_memory_bands_code_.kt" "lines" 9 "characters" 382 "sha256" "faf1d9ea2b2ca912cc63151ed08896fbb06510115aa482ce5e53cd1a9083dcfd"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_four_memory_bands_stackSL_.stacksl" "lines" 10 "characters" 175 "sha256" "a37c95ae9a39a66aae944356c27f762d068b2530f02a7c8588568ba74bbfe31c"}]}
```

```kotlin [field-property box="stacktrinket_four_memory_bands" name="code" path="stacktrinket_four_memory_bands_code_.kt"]
/*
Four memory bands: red, green, blue and alpha are four separate memories of one slowly
moving fbm field, each offset a little. `0.015_0.18` is a range vector,
vec4(0.015, 0.07, 0.125, 0.18): as the `ema` rate it makes red the slowest memory and
alpha the fastest, so the colours separate where the field moves.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_four_memory_bands" name="stackSL" path="stacktrinket_four_memory_bands_stackSL_.stacksl"]
# RGBA are independent memories; the alpha vector gives each channel a different rate.
uv 4.0 * 0.06t + fbm
dup 0.17 +
dup 0.41 +
dup 0.73 +
vec4
prev
0.015_0.18
ema
saturate
```

## 4. subroutine cse

<!-- field-box id="stacktrinket_subroutine_cse" index="3" -->

### Narrative

From `code`

Definitions: `: ring-a … ;` names a piece of program, and using the name runs it.
ring-a and ring-b are two remembered rings; ring-a is called twice. The compiler
inlines definitions and notices the repeat: in the lowered GLSL in the glsl panel,
ring-a is computed once and its value used twice. The average of the three goes back
into red, and the palette colours it.

A definition must leave the stack as deep as it found it: each ring is called with
`input` on top, so it starts with `drop` and ends with its one ring in its place.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_subroutine_cse" role="properties"]
{"id" "stacktrinket_subroutine_cse" "index" 3 "name" "subroutine cse" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "subroutine cse" "_tombstone" false "frame" #afield/rect [40.0 920.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_subroutine_cse_code_.kt" "lines" 13 "characters" 606 "sha256" "e1c3c9b2bd72898a50b9f9bcf80e3ba53666e5668542add9bf396e27679c8329"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_subroutine_cse_stackSL_.stacksl" "lines" 24 "characters" 295 "sha256" "42d5f25716f9bd827abd9e4c58ca113b5bb0147c236ab255f641c97af27dfdcb"}]}
```

```kotlin [field-property box="stacktrinket_subroutine_cse" name="code" path="stacktrinket_subroutine_cse_code_.kt"]
/*
Definitions: `: ring-a … ;` names a piece of program, and using the name runs it.
ring-a and ring-b are two remembered rings; ring-a is called twice. The compiler
inlines definitions and notices the repeat: in the lowered GLSL in the glsl panel,
ring-a is computed once and its value used twice. The average of the three goes back
into red, and the palette colours it.

A definition must leave the stack as deep as it found it: each ring is called with
`input` on top, so it starts with `drop` and ends with its one ring in its place.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_subroutine_cse" name="stackSL" path="stacktrinket_subroutine_cse_stackSL_.stacksl"]
# ring-a and ring-b are deliberately similar; ring-a is called twice.
: ring-a
  drop
  0-> 0.982 *
  p length 0.42 0.035 pulse 0.08 *
  +
;

: ring-b
  drop
  0-> 0.982 *
  p length 0.52 0.035 pulse 0.05 *
  +
;

drop
input ring-a
input ring-b
+
input ring-a
+ 0.333 *
dup ->0
palette
saturate
```

## 5. store channels

<!-- field-box id="stacktrinket_store_channels" index="4" -->

### Narrative

From `code`

Store channels. `0->` reads the red channel of this pixel's store, which here is the
previous frame, and `->0`, `->1`, `->2` write one value into red, green and blue. The
ring's brightness steps at random ten times a second (`|sh10`), and red keeps it as a
slow memory; green and blue are fixed fractions of it, so the colour is a warm orange.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_store_channels" role="properties"]
{"id" "stacktrinket_store_channels" "index" 4 "name" "store channels" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "store channels" "_tombstone" false "frame" #afield/rect [640.0 560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_store_channels_code_.kt" "lines" 9 "characters" 415 "sha256" "7a9085d20ecebbaad639af087de92569b25c3748a8fc4aa4369c40ae5379cbed"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_store_channels_stackSL_.stacksl" "lines" 9 "characters" 166 "sha256" "a80074ca3213a4a44b6cdeb5874b7bc1784c4bd21f2a021b9e52c931d3612ee8"}]}
```

```kotlin [field-property box="stacktrinket_store_channels" name="code" path="stacktrinket_store_channels_code_.kt"]
/*
Store channels. `0->` reads the red channel of this pixel's store, which here is the
previous frame, and `->0`, `->1`, `->2` write one value into red, green and blue. The
ring's brightness steps at random ten times a second (`|sh10`), and red keeps it as a
slow memory; green and blue are fixed fractions of it, so the colour is a warm orange.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_store_channels" name="stackSL" path="stacktrinket_store_channels_stackSL_.stacksl"]
# 0-> reads the red memory for this pixel; ->0 explicitly keeps it.
0-> 0.985 *
p length 0.5 0.08 |sh10 pulse
0.08 *
+
dup ->0
dup 0.65 * ->1
dup 0.35 * ->2
saturate
```

## 6. pulse ring

<!-- field-box id="stacktrinket_pulse_ring" index="5" -->

### Narrative

From `code`

StackSL is a stack language in which every value is a vec4. A program starts with
input = vec4(uv, hash, time) on the stack; each word takes values off the top and
pushes its result, and whatever is on top at the end is the colour of the pixel.

This box's program is in its [[property stackSL]] tab. `p` is uv centred on the
middle, `length` its distance from there, `0.5 0.08 pulse` a soft band at radius 0.5,
and `white *` colours it.

stackFeedback() draws the program over the whole window every frame, with the frame
before bound as `state`: `prev` reads it at this pixel, `0.004 0 vec2 sample` a little
to the side. Edit the program while it runs and the new one takes over without
clearing the picture; run this box again to start from black. The lowering panel
shows how deep the stack ends (here 2: the input is still under the ring) and any
error at its line and column; open the glsl panel for the GLSL the program becomes.

```edn [field-box id="stacktrinket_pulse_ring" role="properties"]
{"id" "stacktrinket_pulse_ring" "index" 5 "name" "pulse ring" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "pulse ring" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_pulse_ring_code_.kt" "lines" 17 "characters" 958 "sha256" "d79f5a29191f4358db9dc2de60b55b2f4c8bcfac343119de10ff370fa488ada5"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_pulse_ring_stackSL_.stacksl" "lines" 4 "characters" 32 "sha256" "ece101b5c6da5aefb58631bf5cd9d5f70462a232c1d543bbc8fc965a6a699165"}]}
```

```kotlin [field-property box="stacktrinket_pulse_ring" name="code" path="stacktrinket_pulse_ring_code_.kt"]
/*
StackSL is a stack language in which every value is a vec4. A program starts with
input = vec4(uv, hash, time) on the stack; each word takes values off the top and
pushes its result, and whatever is on top at the end is the colour of the pixel.

This box's program is in its [[property stackSL]] tab. `p` is uv centred on the
middle, `length` its distance from there, `0.5 0.08 pulse` a soft band at radius 0.5,
and `white *` colours it.

stackFeedback() draws the program over the whole window every frame, with the frame
before bound as `state`: `prev` reads it at this pixel, `0.004 0 vec2 sample` a little
to the side. Edit the program while it runs and the new one takes over without
clearing the picture; run this box again to start from black. The lowering panel
shows how deep the stack ends (here 2: the input is still under the ring) and any
error at its line and column; open the glsl panel for the GLSL the program becomes.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_pulse_ring" name="stackSL" path="stacktrinket_pulse_ring_stackSL_.stacksl"]
p
length
0.5 0.08 pulse
white *
```

## 7. feedback fog

<!-- field-box id="stacktrinket_feedback_fog" index="6" -->

### Narrative

From `code`

Feedback fog: each frame keeps 98.2% of the last one and adds a little moving fbm,
coloured by the cosine palette. A pixel settles where the decay and the addition
balance, about 0.08 / 0.018 = 4.4 times the palette colour, so most of it clips to
bright. Try `prev 0.9 *` for a thinner fog that follows the noise more closely.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_feedback_fog" role="properties"]
{"id" "stacktrinket_feedback_fog" "index" 6 "name" "feedback fog" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "feedback fog" "_tombstone" false "frame" #afield/rect [640.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_feedback_fog_code_.kt" "lines" 9 "characters" 398 "sha256" "a97a1b0a1788d92d535c74325b2993bc27ac2970a3ff020d2ed97a300db43a22"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_feedback_fog_stackSL_.stacksl" "lines" 4 "characters" 51 "sha256" "706f77d72f209fbeb6647c870b1271c665930968baf2e8adb46ef6be77816a35"}]}
```

```kotlin [field-property box="stacktrinket_feedback_fog" name="code" path="stacktrinket_feedback_fog_code_.kt"]
/*
Feedback fog: each frame keeps 98.2% of the last one and adds a little moving fbm,
coloured by the cosine palette. A pixel settles where the decay and the addition
balance, about 0.08 / 0.018 = 4.4 times the palette colour, so most of it clips to
bright. Try `prev 0.9 *` for a thinner fog that follows the noise more closely.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_feedback_fog" name="stackSL" path="stacktrinket_feedback_fog_stackSL_.stacksl"]
prev 0.982 *
uv 5.5 * 0.12t + fbm
palette 0.08 *
+
```

## 8. persistent bloom

<!-- field-box id="stacktrinket_persistent_bloom" index="7" -->

### Narrative

From `code`

Persistent bloom: a ring at radius 0.32 whose colour drifts round the palette
(`0.08t palette`), added on top of 96.5% of the previous frame. The ring remembers
every colour it has been, so it slowly burns towards white.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_persistent_bloom" role="properties"]
{"id" "stacktrinket_persistent_bloom" "index" 7 "name" "persistent bloom" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "persistent bloom" "_tombstone" false "frame" #afield/rect [40.0 560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacktrinket_persistent_bloom_code_.kt" "lines" 8 "characters" 292 "sha256" "e08672d54148280a1d68eb4edcf7b14c7a0959d3bea2ab08f78f15ef5d50da32"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_persistent_bloom_stackSL_.stacksl" "lines" 5 "characters" 56 "sha256" "94bf231f4254836dc2f2a59910cdfcda510402455c5a91e5993cc8a04a4c3c98"}]}
```

```kotlin [field-property box="stacktrinket_persistent_bloom" name="code" path="stacktrinket_persistent_bloom_code_.kt"]
/*
Persistent bloom: a ring at radius 0.32 whose colour drifts round the palette
(`0.08t palette`), added on top of 96.5% of the previous frame. The ring remembers
every colour it has been, so it slowly burns towards white.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
```

```stacksl [field-property box="stacktrinket_persistent_bloom" name="stackSL" path="stacktrinket_persistent_bloom_stackSL_.stacksl"]
prev 0.965 *
p length 0.32 0.05 pulse
0.08t palette
*
+
```

