# Field Document: keyframe_code_blend

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Where a runtime blend cannot reach

<!-- field-box id="kfb_static" index="0" -->

### Narrative

From `code`

Two StackSL token forms carry their numbers inside a grammar that wants literals: a
modifier chain (`0.35s3` — an amount modulated by a sine at frequency 3) and a random
range (`0.2_0.5`). No mix expression can be written at either position, so a runtime
blend cannot reach them and says so rather than pretending.

```edn [field-box id="kfb_static" role="properties"]
{"id" "kfb_static" "index" 0 "name" "Where a runtime blend cannot reach" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Where a runtime blend cannot reach" "_tombstone" false "frame" #afield/rect [80.0 720.0 420.0 102.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kfb_static_code_.kt" "lines" 26 "characters" 1204 "sha256" "2312aae3be1cd91e8cfde0ee6f379b8dd029237bed09ae5a12247d4aec2ee197"}]}
```

```kotlin [field-property box="kfb_static" name="code" path="kfb_static_code_.kt"]
// Two StackSL token forms carry their numbers inside a grammar that wants literals: a
// modifier chain (`0.35s3` — an amount modulated by a sine at frequency 3) and a random
// range (`0.2_0.5`). No mix expression can be written at either position, so a runtime
// blend cannot reach them and says so rather than pretending.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.planStackSLCodeBlend

val quiet = "uv 0.35s3 * fbm 0.2_0.5 +"
val loud = "uv 0.80s3 * fbm 0.6_0.9 +"

val plan = planStackSLCodeBlend(quiet, loud, CodeBlendOptions(mode = CodeBlendMode.RUNTIME))
println(plan.describe())
println("asked for RUNTIME, will run " + plan.effectiveMode)
println("because " + plan.downgradeReason)
println()

// A different program per t, and therefore a compile per t: fine for a scrub, not for a
// frame loop. Note the modifier's frequency: unchanged between the two, so it comes back
// as `3` rather than `3.0`. A site is atomic — one moving component rewrites the whole
// token — and keeping an untouched number's spelling is what stops that showing.
listOf(0.0, 0.5, 1.0).forEach { t ->
    println("t=" + t + "   " + plan.bake(t))
}

display(plan)
```

## 2. The generated Kotlin, evaluated

<!-- field-box id="kfb_kotlin_run" index="1" -->

### Narrative

From `code`

The same plan, rebuilt here — building one is pure, so it costs an alignment and
nothing else. Then the blended expression is handed to a SourceBackedBlock whose box is
kfb_kotlin, so it compiles and runs *there* while this box is the one evaluating.

```edn [field-box id="kfb_kotlin_run" role="properties"]
{"id" "kfb_kotlin_run" "index" 1 "name" "The generated Kotlin, evaluated" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The generated Kotlin, evaluated" "_tombstone" false "frame" #afield/rect [80.0 1360.0 420.0 102.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kfb_kotlin_run_code_.kt" "lines" 27 "characters" 1097 "sha256" "f2b156bafb80bccf5e520c45d43904dc45b1bf0b4c3ed828acd698f6281d21e1"}]}
```

```kotlin [field-property box="kfb_kotlin_run" name="code" path="kfb_kotlin_run_code_.kt"]
// The same plan, rebuilt here — building one is pure, so it costs an alignment and
// nothing else. Then the blended expression is handed to a SourceBackedBlock whose box is
// kfb_kotlin, so it compiles and runs *there* while this box is the one evaluating.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.SourceBackedBlock
import afield.stdlib.planKotlinCodeBlend

val plan = planKotlinCodeBlend(
    "2.0f * 3.0f + 1.0f",
    "8.0f * 3.0f + 5.0f",
    CodeBlendOptions(mode = CodeBlendMode.RUNTIME)
)
val block = SourceBackedBlock<Any?>("kfb_kotlin", plan.runtimeSource)
println(plan.runtimeSource)
println()

// One block, five values of t. The first invocation compiles the generated text through
// K2; the rest reuse the lambda, and only the registry slot changes.
listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { t ->
    plan.drive(t)
    val got = block() as Float
    val want = (2.0f + 6.0f * t.toFloat()) * 3.0f + (1.0f + 4.0f * t.toFloat())
    println("t=%.2f   block() = %8.4f   independently %8.4f".format(t, got, want))
}

display(plan)
```

## 3. What the alignment finds, and what it refuses

<!-- field-box id="kfb_breakdown" index="2" -->

### Narrative

From `code`

A code keyframe is a program. Two of them have no midpoint in general — half a program
is not a program — so a keyframe blend steps them. Unless they differ only in numbers,
which is the case worth finding, because that is the case a person authoring two looks
for one patch keeps producing.

```edn [field-box id="kfb_breakdown" role="properties"]
{"id" "kfb_breakdown" "index" 2 "name" "What the alignment finds, and what it refuses" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What the alignment finds, and what it refuses" "_tombstone" false "frame" #afield/rect [80.0 80.0 420.0 102.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kfb_breakdown_code_.kt" "lines" 30 "characters" 1442 "sha256" "83e635123c35cbd11614550dbb5b217bf8c5c86a061a702389b823b0a1850d07"}]}
```

```kotlin [field-property box="kfb_breakdown" name="code" path="kfb_breakdown_code_.kt"]
// A code keyframe is a program. Two of them have no midpoint in general — half a program
// is not a program — so a keyframe blend steps them. Unless they differ only in numbers,
// which is the case worth finding, because that is the case a person authoring two looks
// for one patch keeps producing.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.planStackSLCodeBlend

val warm = "uv 3.0 * fbm 0.15 + [1.0,0.55,0.2] *"
val cool = "uv 8.0 * fbm 0.45 + [0.2,0.5,1.0] *"

val plan = planStackSLCodeBlend(warm, cool, CodeBlendOptions(mode = CodeBlendMode.RUNTIME))
println(plan.describe())
println("blendable=" + plan.blendable + "  runtimeCapable=" + plan.runtimeCapable)
println()

// One program, for every t. Each moving literal became a push of both endpoints, a read of
// the blend push constant, and `mix` — which leaves one value on the stack, exactly as the
// literal it replaced did, so the surrounding stack arithmetic is untouched.
println("the one program a runtime blend runs:")
println(plan.runtimeSource)
println()

// And the refusal. One word different is a structural difference, and there is no honest
// program between the two, so the blend steps whatever was asked for.
val elsewhere = "uv 3.0 * noise 0.15 + [1.0,0.55,0.2] *"
val stepped = planStackSLCodeBlend(warm, elsewhere, CodeBlendOptions(mode = CodeBlendMode.RUNTIME))
println(stepped.describe())

display(plan)
```

## 4. The same blend, in Kotlin

<!-- field-box id="kfb_kotlin" index="3" -->

### Narrative

From `code`

A Kotlin keyframe is a `sourceBlock { }`: a callable that remembers its own source. Two
of them blend the same way, and for the same reason — the interesting pair is the one
that differs only in numbers. The transport differs: a shader reads a push constant,
and a generated Kotlin expression reads a registry slot addressed by an integer written
into its text, because generated text cannot close over a value.

```edn [field-box id="kfb_kotlin" role="properties"]
{"id" "kfb_kotlin" "index" 3 "name" "The same blend, in Kotlin" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The same blend, in Kotlin" "_tombstone" false "frame" #afield/rect [80.0 1040.0 420.0 102.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kfb_kotlin_code_.kt" "lines" 36 "characters" 1690 "sha256" "5157bf323ecc83177688aa0c42feb44cef3c5ecb712dc87ae3c07c849ef60b3b"}]}
```

```kotlin [field-property box="kfb_kotlin" name="code" path="kfb_kotlin_code_.kt"]
// A Kotlin keyframe is a `sourceBlock { }`: a callable that remembers its own source. Two
// of them blend the same way, and for the same reason — the interesting pair is the one
// that differs only in numbers. The transport differs: a shader reads a push constant,
// and a generated Kotlin expression reads a registry slot addressed by an integer written
// into its text, because generated text cannot close over a value.
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.KeyframeScope
import afield.stdlib.sourceBlock

val quiet = sourceBlock { 2.0f * 3.0f + 1.0f }
val loud = sourceBlock { 8.0f * 3.0f + 5.0f }
println("left  source: " + quiet.source)
println("right source: " + loud.source)
println()

var amount: Any? = quiet
val scope = KeyframeScope()
scope.target("amount", get = { amount }, set = { v -> amount = v })
scope.codeBlend = CodeBlendOptions(mode = CodeBlendMode.RUNTIME)

val a = scope.Key(mutableMapOf<String, Any?>("amount" to quiet))
val b = scope.Key(mutableMapOf<String, Any?>("amount" to loud))

val plan = scope.codeBlendPlans(a, b).getValue("amount")
println(plan.describe())
println("the one expression a runtime blend evaluates:")
println(plan.runtimeSource)
println()
println("the middle literal is unchanged between the two, so it is left alone.")

// The invocation is in kfb_kotlin_run, not here. A SourceBackedBlock hydrates by
// compiling and evaluating its source in its own box, and asking for that from inside
// this box's own running code re-enters: the nested evaluation replays this snippet,
// which hydrates again. Invoke one from a frame loop, a control callback, or another box.
display(plan)
```

## 5. The push-constant convention, rendered

<!-- field-box id="kfb_runtime" index="4" -->

### Narrative

From `code`

The claim a runtime blend makes is not that it produces a plausible picture in the
middle. It is that at 0 it *is* the left keyframe and at 1 it *is* the right one. That
is checkable, and this box checks it: one program, five draws, and the two ends
compared pixel for pixel against the programs they are supposed to be.

```edn [field-box id="kfb_runtime" role="properties"]
{"id" "kfb_runtime" "index" 4 "name" "The push-constant convention, rendered" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The push-constant convention, rendered" "_tombstone" false "frame" #afield/rect [80.0 400.0 420.0 102.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kfb_runtime_code_.kt" "lines" 82 "characters" 3351 "sha256" "e6065aa7a2056ccb98fbc46c2919bccfba9d5cb8a98ac892288b8122ed92b86d"}]}
```

```kotlin [field-property box="kfb_runtime" name="code" path="kfb_runtime_code_.kt"]
// The claim a runtime blend makes is not that it produces a plausible picture in the
// middle. It is that at 0 it *is* the left keyframe and at 1 it *is* the right one. That
// is checkable, and this box checks it: one program, five draws, and the two ends
// compared pixel for pixel against the programs they are supposed to be.
import afield.stacksl.cpu.GlslValue
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.CodeBlendMode
import afield.stdlib.CodeBlendOptions
import afield.stdlib.keyframeBlendVector
import afield.stdlib.planStackSLCodeBlend

// The convention, in a shader: a vec4 called keyframeBlend in the push-constant block.
// Nothing declares it for you — a `stack.auto` slot cannot declare a uniform of its own,
// which is why the parameter arrives by agreement rather than by machinery.
val template = """
    #version 450
    layout(location = 0) in vec2 fragUV;
    layout(location = 0) out vec4 outColor;

    layout(push_constant) uniform Controls {
        vec4 keyframeBlend;
    } pc;

    {{ stack.auto.program }}

    void main() {
        vec4 stackInput = vec4(fragUV, 0.0, 0.0);
        outColor = vec4(clamp(program(stackInput).rgb, 0.0, 1.0), 1.0);
    }
""".trimIndent()

val warm = "uv 3.0 * fbm 0.15 + [1.0,0.55,0.2] *"
val cool = "uv 8.0 * fbm 0.45 + [0.2,0.5,1.0] *"
val blended = planStackSLCodeBlend(warm, cool, CodeBlendOptions(mode = CodeBlendMode.RUNTIME)).runtimeSource

val dim = 56
// Expanded once. A runtime program does not depend on t, so this is the only compile.
val expanded = StackSLCpu.expandStackAuto(template, blended)

val strip = StringBuilder("<div style='display:flex;gap:8px;align-items:flex-start'>")
val rasters = mutableMapOf<Double, FloatArray>()
for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
    val v = keyframeBlendVector(t)
    val raster = StackSLCpu.renderFragment(
        expanded, dim, dim,
        uniforms = mapOf("pc.keyframeBlend" to GlslValue.vec4(v.x, v.y, v.z, v.w))
    )
    rasters[t] = raster.rgba
    val b64 = java.util.Base64.getEncoder().encodeToString(raster.toPngBytes())
    strip.append("<div style='font:10px ui-monospace,monospace;color:#999;text-align:center'>")
    strip.append("<img src='data:image/png;base64,")
    strip.append(b64)
    strip.append("' width='")
    strip.append(dim * 2)
    strip.append("' height='")
    strip.append(dim * 2)
    strip.append("' style='image-rendering:pixelated;border-radius:4px;display:block'/>")
    strip.append("blend ")
    strip.append(t)
    strip.append("</div>")
}
strip.append("</div>")
display("blend-strip", strip.toString())

// The endpoints, drawn from the endpoint programs themselves.
val ends = listOf(0.0 to warm, 1.0 to cool).map { (t, program) ->
    val v = keyframeBlendVector(t)
    val raster = StackSLCpu.renderFragment(
        StackSLCpu.expandStackAuto(template, program), dim, dim,
        uniforms = mapOf("pc.keyframeBlend" to GlslValue.vec4(v.x, v.y, v.z, v.w))
    )
    t to raster.rgba
}
ends.forEach { (t, want) ->
    val got = rasters.getValue(t)
    var worst = 0.0f
    for (i in want.indices) worst = maxOf(worst, kotlin.math.abs(want[i] - got[i]))
    println("at blend %.1f the one program differs from the keyframe it should be by %.4f".format(t, worst))
}
println()
println("one program, five draws, only the push constant changing:")
println(blended)
```

