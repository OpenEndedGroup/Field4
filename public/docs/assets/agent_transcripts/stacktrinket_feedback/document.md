# Field Document: stacktrinket_feedback

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="stacktrinket_renderer" index="0" -->

### Narrative

From `notes`

Renderer

```edn [field-box id="stacktrinket_renderer" role="properties"]
{"id" "stacktrinket_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["stacktrinket_pulse_ring" "stacktrinket_feedback_fog" "stacktrinket_persistent_bloom" "stacktrinket_store_channels" "stacktrinket_subroutine_cse" "stacktrinket_four_memory_bands" "stacktrinket_long_memory_ecology"] "properties" {"children" #{#afield/box "stacktrinket_pulse_ring" #afield/box "stacktrinket_feedback_fog" #afield/box "stacktrinket_persistent_bloom" #afield/box "stacktrinket_store_channels" #afield/box "stacktrinket_subroutine_cse" #afield/box "stacktrinket_four_memory_bands" #afield/box "stacktrinket_long_memory_ecology"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_renderer_notes_.md" "lines" 5 "characters" 213 "sha256" "beba2e9a27eca000f267e867d1d97359f3de73d1b6808f6598749d77731f5fc3"} {"name" "code" "language" "kotlin" "path" "stacktrinket_renderer_code_.kt" "lines" 12 "characters" 413 "sha256" "736875f16bf7e075d4ed1438dc31191cda020964164ebd9146d84fc79ccc8560"}]}
```

```markdown [field-property box="stacktrinket_renderer" name="notes" path="stacktrinket_renderer_notes_.md"]
# Renderer

Opens the StackTrinket window, 760 by 760, and runs Field's Vulkan renderer in it. Launch this
box first; every program box below draws into its window, and launching one replaces the program
showing.
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

From `notes`

Long memory ecology

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
{"id" "stacktrinket_long_memory_ecology" "index" 1 "name" "long memory ecology" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "long memory ecology" "_tombstone" false "frame" #afield/rect [40.0 1280.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_long_memory_ecology_notes_.md" "lines" 6 "characters" 379 "sha256" "0b4a0be0e8dd2fc44fb33ccdea1d8c376ce8e5f4d0c660e86343dea47e5e9c9c"} {"name" "code" "language" "kotlin" "path" "stacktrinket_long_memory_ecology_code_.kt" "lines" 14 "characters" 742 "sha256" "0426dc183b4013a5a93011527d277b06c2caf8d0c2380ea81599ae9c7f3b2a79"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_long_memory_ecology_stackSL_.stacksl" "lines" 20 "characters" 375 "sha256" "3c6bfb82de5a35553d0c581371435d616877f88acc0c9b8f29e90c7ff045c858"}]}
```

```markdown [field-property box="stacktrinket_long_memory_ecology" name="notes" path="stacktrinket_long_memory_ecology_notes_.md"]
# Long memory ecology

The previous frame steers where it is read from next, two slow waves add a drift, and `ema` keeps
the result as four memories. As on the page it looks like fine moving grain. The line
`dup average … + +` adds each pixel's average back every frame, and `mirror` folds the growing
values. Delete that line and it fills slowly into a pale moving fog instead.
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

From `notes`

Four memory bands

From `code`

Four memory bands: red, green, blue and alpha are four separate memories of one slowly
moving fbm field, each offset a little. `0.015_0.18` is a range vector,
vec4(0.015, 0.07, 0.125, 0.18): as the `ema` rate it makes red the slowest memory and
alpha the fastest, so the colours separate where the field moves.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_four_memory_bands" role="properties"]
{"id" "stacktrinket_four_memory_bands" "index" 2 "name" "four memory bands" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "four memory bands" "_tombstone" false "frame" #afield/rect [640.0 920.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_four_memory_bands_notes_.md" "lines" 5 "characters" 287 "sha256" "ec81bf6d27f04b6be391f8d4d0424a9bd4e61f219672dc341152287e9371f1a7"} {"name" "code" "language" "kotlin" "path" "stacktrinket_four_memory_bands_code_.kt" "lines" 9 "characters" 382 "sha256" "faf1d9ea2b2ca912cc63151ed08896fbb06510115aa482ce5e53cd1a9083dcfd"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_four_memory_bands_stackSL_.stacksl" "lines" 10 "characters" 175 "sha256" "a37c95ae9a39a66aae944356c27f762d068b2530f02a7c8588568ba74bbfe31c"}]}
```

```markdown [field-property box="stacktrinket_four_memory_bands" name="notes" path="stacktrinket_four_memory_bands_notes_.md"]
# Four memory bands

Red, green, blue and alpha are four separate memories of one moving fbm field. The range vector
`0.015_0.18` is `vec4(0.015, 0.07, 0.125, 0.18)`. As the `ema` rate it makes red the slowest memory
and alpha the fastest, so the colours separate where the field moves.
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

From `notes`

Subroutine cse

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
{"id" "stacktrinket_subroutine_cse" "index" 3 "name" "subroutine cse" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "subroutine cse" "_tombstone" false "frame" #afield/rect [40.0 920.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_subroutine_cse_notes_.md" "lines" 5 "characters" 302 "sha256" "af936a000c59fa08e2cf9252c40fbd00e093d33a449bfc7b08a3efa21d5ade1b"} {"name" "code" "language" "kotlin" "path" "stacktrinket_subroutine_cse_code_.kt" "lines" 13 "characters" 606 "sha256" "e1c3c9b2bd72898a50b9f9bcf80e3ba53666e5668542add9bf396e27679c8329"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_subroutine_cse_stackSL_.stacksl" "lines" 24 "characters" 295 "sha256" "42d5f25716f9bd827abd9e4c58ca113b5bb0147c236ab255f641c97af27dfdcb"}]}
```

```markdown [field-property box="stacktrinket_subroutine_cse" name="notes" path="stacktrinket_subroutine_cse_notes_.md"]
# Subroutine cse

`: ring-a … ;` defines a word; using its name runs it. Two remembered rings, with ring-a used twice.
The compiler inlines definitions and computes ring-a once (look in the glsl panel). The page's
rings push a value without taking one, which Field refuses, so each starts with `drop`.
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

From `notes`

Store channels

From `code`

Store channels. `0->` reads the red channel of this pixel's store, which here is the
previous frame, and `->0`, `->1`, `->2` write one value into red, green and blue. The
ring's brightness steps at random ten times a second (`|sh10`), and red keeps it as a
slow memory; green and blue are fixed fractions of it, so the colour is a warm orange.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_store_channels" role="properties"]
{"id" "stacktrinket_store_channels" "index" 4 "name" "store channels" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "store channels" "_tombstone" false "frame" #afield/rect [640.0 560.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_store_channels_notes_.md" "lines" 6 "characters" 324 "sha256" "24af5ca8467fac3352277771b2edd1c67de983c5db065426a0e7326d735d7808"} {"name" "code" "language" "kotlin" "path" "stacktrinket_store_channels_code_.kt" "lines" 9 "characters" 415 "sha256" "7a9085d20ecebbaad639af087de92569b25c3748a8fc4aa4369c40ae5379cbed"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_store_channels_stackSL_.stacksl" "lines" 9 "characters" 166 "sha256" "a80074ca3213a4a44b6cdeb5874b7bc1784c4bd21f2a021b9e52c931d3612ee8"}]}
```

```markdown [field-property box="stacktrinket_store_channels" name="notes" path="stacktrinket_store_channels_notes_.md"]
# Store channels

`0->` reads the red channel of the previous frame at this pixel; `->0`, `->1` and `->2` write one
value into red, green and blue. The ring's brightness jumps at random ten times a second (`|sh10`),
red keeps it as a slow memory, and green and blue are fixed fractions of red, so the ring is a warm
orange.
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

From `notes`

StackTrinket in Field

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
{"id" "stacktrinket_pulse_ring" "index" 5 "name" "pulse ring" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "pulse ring" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_pulse_ring_notes_.md" "lines" 65 "characters" 3744 "sha256" "c6cdd8848640aa59461921e56d06ca2c97872d7866f0b3c4a0038f2053d4b201"} {"name" "code" "language" "kotlin" "path" "stacktrinket_pulse_ring_code_.kt" "lines" 17 "characters" 958 "sha256" "d79f5a29191f4358db9dc2de60b55b2f4c8bcfac343119de10ff370fa488ada5"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_pulse_ring_stackSL_.stacksl" "lines" 4 "characters" 32 "sha256" "ece101b5c6da5aefb58631bf5cd9d5f70462a232c1d543bbc8fc965a6a699165"}]}
```

```markdown [field-property box="stacktrinket_pulse_ring" name="notes" path="stacktrinket_pulse_ring_notes_.md"]
# StackTrinket in Field

StackTrinket (`stacktrinket.html` in the madp classroom demos) is a live editor for **StackSL**, a
small Forth-like language in which every value on the stack is a `vec4`. A program is a list of
words. Each word takes its arguments off the top of the stack and pushes its result, and whatever
is left on top at the end is the colour of the pixel. The program runs for every pixel of the
window, every frame.

What StackTrinket adds to plain StackSL is **memory**. The frame drawn last time is bound as
`state`, so a program can read its own previous picture: `prev` reads it at this pixel, `sample`
reads it a little to the side, and `0->` … `3->` read one channel of it. Decay what was there,
add something new, and pictures build up over time into fog, trails, blooms and slow drifting
memories. The presets below are the page's feedback presets.

## How to play

1. Launch **renderer**, then this box (it is launched for you when the document opens).
2. Open this box's **stackSL** tab. That is the program. Edit it while it runs: each edit that
   compiles takes over on the next frame, and the picture carries on from where it was.
3. Run the box again to clear the picture and start from black.
4. Launch any other box to switch program, the way the page's preset menu does.
5. The **lowering** panel under the box says whether the program compiled, how deep the stack ends
   and which helpers it used, with any error at its line and column. Open the **glsl** panel to
   read the GLSL the program becomes.

Things to try here: change `0.5` to move the ring, `0.08` to soften it, `white` to `red`. Put
`prev 0.95 * +` at the end and the ring leaves a trail when you move it.

## Words to start with

| Word | Pushes |
| --- | --- |
| `uv` | `vec4(uv, 0, 1)`, 0..1 with y up |
| `p` | `uv` centred: `vec4(uv - 0.5, 0, 1)` |
| `time`, `0.2t` | seconds, or seconds times 0.2 |
| `prev` | the previous frame at this pixel |
| `x y vec2 sample` | the previous frame at an offset |
| `length`, `pulse`, `fbm`, `palette` | distance, a soft band, noise, a cosine colour |
| `dup`, `drop`, `swap` | copy, discard, swap the top values |

The full vocabulary is in Field's StackSL cheatsheet.

## How Field's version maps to the page

- The page passes `vec4(uv, time, hash)`; Field passes `vec4(uv, hash, time)`. The presets use the
  `time` and `hash` words, never the raw lanes, so they run unchanged.
- The page compiles with `stacksl.py`, a June 2026 port of Field's StackSL compiler. For these
  presets both produce the same GLSL line for line.
- Field requires a definition (`: name … ;`) to leave the stack as deep as it found it.
  `subroutine cse` breaks that rule on the page, so here each of its rings starts with `drop`.
- The page draws as often as the browser does, about 60 times a second. A feedback picture
  depends on that rate, because `prev 0.95 *` keeps 95% *each frame*. Field's window can draw much
  faster, so `stackFeedback()` steps the program 60 times a second (`fps`).
- The state is RGBA16F, linear and repeating, as on the page. The browser shows the program's
  numbers as they are, and Field's window expects linear light, so the window applies a 2.2
  display gamma (`displayGamma`).
- Not ported: the Sweep button (ten variants of the number under the cursor), local saves and the
  chat panel. Field's own documents, history and AI4 cover the last two.

## In code

`stackFeedback()` is the whole renderer: a float16 ping-pong target with the last frame bound as
`state`, a fullscreen pass that runs this box's `stackSL`, and the lowering panels. It returns the
running host, which has `reset()`, and settings such as `paused`, `fps` and `timeScale`
(`stackFeedback { fps = 30.0 }`).
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

From `notes`

Feedback fog

From `code`

Feedback fog: each frame keeps 98.2% of the last one and adds a little moving fbm,
coloured by the cosine palette. A pixel settles where the decay and the addition
balance, about 0.08 / 0.018 = 4.4 times the palette colour, so most of it clips to
bright. Try `prev 0.9 *` for a thinner fog that follows the noise more closely.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_feedback_fog" role="properties"]
{"id" "stacktrinket_feedback_fog" "index" 6 "name" "feedback fog" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "feedback fog" "_tombstone" false "frame" #afield/rect [640.0 200.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_feedback_fog_notes_.md" "lines" 6 "characters" 313 "sha256" "a0fb802b1d8a564e06f2be941c72813124e2f219b61faff44a35cb64cf6386d2"} {"name" "code" "language" "kotlin" "path" "stacktrinket_feedback_fog_code_.kt" "lines" 9 "characters" 398 "sha256" "a97a1b0a1788d92d535c74325b2993bc27ac2970a3ff020d2ed97a300db43a22"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_feedback_fog_stackSL_.stacksl" "lines" 4 "characters" 51 "sha256" "706f77d72f209fbeb6647c870b1271c665930968baf2e8adb46ef6be77816a35"}]}
```

```markdown [field-property box="stacktrinket_feedback_fog" name="notes" path="stacktrinket_feedback_fog_notes_.md"]
# Feedback fog

Keeps 98.2% of the last frame and adds a little moving fbm noise, coloured by the cosine palette.
Each pixel settles where the decay and the addition balance, about 4.4 times the palette colour,
so most of it is pushed to bright. Try `prev 0.9 *` for a thinner fog that follows the noise
closely.
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

From `notes`

Persistent bloom

From `code`

Persistent bloom: a ring at radius 0.32 whose colour drifts round the palette
(`0.08t palette`), added on top of 96.5% of the previous frame. The ring remembers
every colour it has been, so it slowly burns towards white.

The program is in the [[property stackSL]] tab.

```edn [field-box id="stacktrinket_persistent_bloom" role="properties"]
{"id" "stacktrinket_persistent_bloom" "index" 7 "name" "persistent bloom" "parents" ["root" "stacktrinket_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "stacktrinket_renderer"} "name" "persistent bloom" "_tombstone" false "frame" #afield/rect [40.0 560.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "stacktrinket_persistent_bloom_notes_.md" "lines" 4 "characters" 202 "sha256" "3d561ca0ce3d5e760a33282edf72a2fdaddc7d593e65ec781440f64cd8f38bfa"} {"name" "code" "language" "kotlin" "path" "stacktrinket_persistent_bloom_code_.kt" "lines" 8 "characters" 292 "sha256" "e08672d54148280a1d68eb4edcf7b14c7a0959d3bea2ab08f78f15ef5d50da32"} {"name" "stackSL" "language" "stacksl" "path" "stacktrinket_persistent_bloom_stackSL_.stacksl" "lines" 5 "characters" 56 "sha256" "94bf231f4254836dc2f2a59910cdfcda510402455c5a91e5993cc8a04a4c3c98"}]}
```

```markdown [field-property box="stacktrinket_persistent_bloom" name="notes" path="stacktrinket_persistent_bloom_notes_.md"]
# Persistent bloom

A ring whose colour drifts round the palette (`0.08t palette`), added on top of 96.5% of the last
frame. The ring remembers every colour it has been, and slowly burns towards white.
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

