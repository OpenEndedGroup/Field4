# Field Document: stacksl_fit_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["stacksl-wires" "stacksl-fit" "stacksl-roundtrip"] "properties" {"children" #{#afield/box "stacksl-wires" #afield/box "stacksl-fit" #afield/box "stacksl-roundtrip"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. Differentiable StackSL — gradient descent on the shader's own numbers

<!-- field-box id="stacksl-fit" index="1" -->

### Narrative

From `code`

Gradient descent on a shader's own literals.

The target is the SAME program with twelve different palette numbers, so this fit has a known
answer and "did it work" is a question you can settle by looking: panels are target, seed,
fitted, and the residual amplified eight times.

What makes this exact rather than approximate: StackSL is a per-pixel field. `main()` runs once
per pixel with `fragUV` as its only spatial input, so d(pixel)/d(literal) is the ordinary chain
rule. There is no rasteriser in the pipeline, no coverage approximation and therefore no bias to
characterise — which is exactly why the same idea was correctly abandoned for RectSL, where a
pixel depends on a parameter through coverage.

The gradient flows through the emitted GLSL itself, interpreted node by node on
`afield.stdlib`'s reverse-mode AD tape, one tape per pixel. No second evaluator.

```edn [field-box id="stacksl-fit" role="properties"]
{"id" "stacksl-fit" "index" 1 "name" "Differentiable StackSL — gradient descent on the shader's own numbers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Differentiable StackSL — gradient descent on the shader's own numbers" "frame" #afield/rect [1020.0 60.0 880.0 900.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacksl-fit_code_.kt" "lines" 112 "characters" 4524 "sha256" "628ede11ecea727fd1d65fd0b8d20d0a0588be6bcb8e072233e9bcfb9d14ddfb"}]}
```

```kotlin [field-property box="stacksl-fit" name="code" path="stacksl-fit_code_.kt"]
// Gradient descent on a shader's own literals.
//
// The target is the SAME program with twelve different palette numbers, so this fit has a known
// answer and "did it work" is a question you can settle by looking: panels are target, seed,
// fitted, and the residual amplified eight times.
//
// What makes this exact rather than approximate: StackSL is a per-pixel field. `main()` runs once
// per pixel with `fragUV` as its only spatial input, so d(pixel)/d(literal) is the ordinary chain
// rule. There is no rasteriser in the pipeline, no coverage approximation and therefore no bias to
// characterise — which is exactly why the same idea was correctly abandoned for RectSL, where a
// pixel depends on a parameter through coverage.
//
// The gradient flows through the emitted GLSL itself, interpreted node by node on
// `afield.stdlib`'s reverse-mode AD tape, one tape per pixel. No second evaluator.

import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLFit
import afield.util.PngEncoder
import kotlin.math.abs

run {

    val seedProgram = """
        uv 3.0 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    val targetProgram = """
        uv 3.0 * fbm
        0.62 0.41 0.35 1 vec4
        0.31 0.45 0.52 1 vec4
        1.0 1.0 1.0 1 vec4
        0.12 0.55 0.88 1 vec4
        palette
    """.trimIndent()

    val template = StackSLCpu.stillTemplate()
    val dim = 64

    // 64 square is sized from the measured cost, not from optimism: this interpreter does about
    // 1.3e4 pixel-shader invocations per second per core and 4.4e4 across ten, so one forward
    // image pass is a tenth of a second and eighty gradient steps are a handful.
    val target = StackSLCpu.renderProgram(targetProgram, template, dim, dim)

    val started = System.nanoTime()
    val result = StackSLFit.fit(
        seedProgram, target, template,
        dim = dim, iterations = 80, learningRate = 0.05
    )
    val seconds = (System.nanoTime() - started) / 1e9

    println("${result.wired.wires.size} wires, ${result.fittedIndices.size} fitted")
    println(
        "loss %.4e -> %.4e (%.0fx) in 80 Adam steps, ${dim}x$dim, %.1fs"
            .format(result.seedLoss, result.fittedLoss, result.lossRatio, seconds)
    )
    println()
    println("what moved:")
    result.report().forEach { println("    $it") }

    // The four alpha wires do not move, and that is correct: the `w` component of a `palette`
    // operand never reaches RGB, so its gradient is exactly zero. A fit cannot invent a reason to
    // change a parameter the image does not depend on.

    val seed = result.loss.render(result.seedWires)
    val fitted = result.loss.render(result.fittedWires)
    val residual = CpuRaster(
        dim, dim,
        FloatArray(fitted.rgba.size) { i ->
            if (i % 4 == 3) 1f else (abs(fitted.rgba[i] - target.rgba[i]) * 8f).coerceIn(0f, 1f)
        }
    )

    var sum = 0.0
    var count = 0
    for (i in 0 until dim * dim) for (ch in 0 until 3) {
        sum += abs(fitted.rgba[i * 4 + ch] - target.rgba[i * 4 + ch]).toDouble()
        count++
    }
    println()
    println("fitted vs target: %.5f mean absolute error per RGB sample over $count samples".format(sum / count))
    println("panels: target | seed | fitted | residual x8")

    // Stitch the four panels into one strip at 3x, nearest-neighbour, with a gutter.
    val scale = 3
    val panel = dim * scale
    val gutter = 6
    val width = 4 * panel + 3 * gutter
    val strip = IntArray(width * panel) { 0xFF14161A.toInt() }
    listOf(target, seed, fitted, residual).forEachIndexed { index, raster ->
        val argb = raster.toArgb()
        val x0 = index * (panel + gutter)
        for (y in 0 until panel) for (x in 0 until panel) {
            strip[y * width + x0 + x] = argb[(y / scale) * dim + (x / scale)]
        }
    }
    display(PngEncoder.encode(strip, width, panel))

    println()
    println("the fitted program, which is a program:")
    println(result.fittedProgram.prependIndent("    "))

    // The residual is the panel that decides this, not the loss number. A sum-of-squares objective
    // rewards colour and luminance statistics, so a fit can drive the loss a long way down while
    // producing an image that does not resemble the target at all. Here the residual is nearly
    // black, which is what "it found the picture" looks like.
}
```

## 3. The round trip — the fit's output is a program you can paste back

<!-- field-box id="stacksl-roundtrip" index="2" -->

### Narrative

From `code`

The step that turns a number into a capability: the fit's output is StackSL SOURCE.

A fitted wire vector is a `DoubleArray` and nobody can run a `DoubleArray`. So the fitted values
are written back into the program at the token positions the compiler recorded, and the result
is an ordinary StackSL program — which this box then compiles from scratch through the ORDINARY
non-wired path and renders at 256. Paste the text it prints into a `stackSL` box and a GPU will
draw it.

The number to watch is the last one: the maximum per-channel difference between the re-emitted
program's render and the fitted wire vector's render. If the literal rewriting were wrong — a
column off by one, a token mismatched — it would not be small, it would be a different picture.

```edn [field-box id="stacksl-roundtrip" role="properties"]
{"id" "stacksl-roundtrip" "index" 2 "name" "The round trip — the fit's output is a program you can paste back" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The round trip — the fit's output is a program you can paste back" "frame" #afield/rect [1980.0 60.0 880.0 900.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacksl-roundtrip_code_.kt" "lines" 90 "characters" 3966 "sha256" "420786fcaaaf26cb4c9ff3d7239663ab6a5deec2d24dac7a4664818f27cf937e"}]}
```

```kotlin [field-property box="stacksl-roundtrip" name="code" path="stacksl-roundtrip_code_.kt"]
// The step that turns a number into a capability: the fit's output is StackSL SOURCE.
//
// A fitted wire vector is a `DoubleArray` and nobody can run a `DoubleArray`. So the fitted values
// are written back into the program at the token positions the compiler recorded, and the result
// is an ordinary StackSL program — which this box then compiles from scratch through the ORDINARY
// non-wired path and renders at 256. Paste the text it prints into a `stackSL` box and a GPU will
// draw it.
//
// The number to watch is the last one: the maximum per-channel difference between the re-emitted
// program's render and the fitted wire vector's render. If the literal rewriting were wrong — a
// column off by one, a token mismatched — it would not be small, it would be a different picture.

import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLFit
import afield.util.PngEncoder
import kotlin.math.abs

run {

    val seedProgram = """
        uv 3.0 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    val targetProgram = """
        uv 3.0 * fbm
        0.62 0.41 0.35 1 vec4
        0.31 0.45 0.52 1 vec4
        1.0 1.0 1.0 1 vec4
        0.12 0.55 0.88 1 vec4
        palette
    """.trimIndent()

    val template = StackSLCpu.stillTemplate()

    // 48 square and 40 steps, because this box is about the round trip and not about the fit — the
    // box next door does the careful version at 64 with 80 steps.
    val dim = 48
    val target = StackSLCpu.renderProgram(targetProgram, template, dim, dim)
    val result = StackSLFit.fit(seedProgram, target, template, dim = dim, iterations = 40, learningRate = 0.06)
    println("fitted at ${dim}x$dim: loss %.4e -> %.4e".format(result.seedLoss, result.fittedLoss))

    // Same wire vector, two routes to an image.
    val viaWires = result.loss.render(result.fittedWires)
    val viaSource = StackSLCpu.renderProgram(result.fittedProgram, template, dim, dim)

    var worst = 0.0
    for (i in 0 until dim * dim) for (ch in 0 until 3) {
        val d = abs(viaWires.rgba[i * 4 + ch] - viaSource.rgba[i * 4 + ch]).toDouble()
        if (d > worst) worst = d
    }

    println()
    println("paste this into a stackSL box and a GPU will draw it:")
    println(result.fittedProgram.prependIndent("    "))
    println()

    // Render the re-emitted program big, through the ordinary compiler, with no wires involved.
    val started = System.nanoTime()
    val big = StackSLCpu.renderStill(result.fittedProgram, dim = 256)
    println("re-emitted program rendered at 256x256 in %.1fs".format((System.nanoTime() - started) / 1e9))
    println("round trip, max per-channel difference over ${dim * dim * 3} samples: %.3e".format(worst))
    display(big.toPngBytes())

    // A side-by-side of the two routes at fit resolution, 4x, so the claim above is visible and
    // not only printed.
    val scale = 4
    val panel = dim * scale
    val gutter = 6
    val width = 2 * panel + gutter
    val strip = IntArray(width * panel) { 0xFF14161A.toInt() }
    listOf(viaWires, viaSource).forEachIndexed { index, raster ->
        val argb = raster.toArgb()
        val x0 = index * (panel + gutter)
        for (y in 0 until panel) for (x in 0 until panel) {
            strip[y * width + x0 + x] = argb[(y / scale) * dim + (x / scale)]
        }
    }
    println("panels: rendered from the fitted WIRES | rendered from the re-emitted SOURCE")
    display(PngEncoder.encode(strip, width, panel))

    // The one lossy step in the trip is the printing: `%.8f` is eight significant decimals and
    // binary32 needs nine to round-trip in general, so a fitted value can land one ulp away in the
    // source. In the committed measurement it did not — the round trip is exactly zero — but the
    // guard in `StackSLFitFigureTest` allows 1e-4 for that reason rather than demanding zero.
}
```

## 4. The wires — every literal in a StackSL program, made addressable

<!-- field-box id="stacksl-wires" index="3" -->

### Narrative

From `code` tags: `#0` `#1..` `#16`

Every number you typed in a StackSL program is a free parameter. Here they are.

`StackSLCompileOptions.externalizeConstants` lifts each numeric literal out of a program into a
wire, and records where it came from: the token, the line, the column. `StackSLWires.lift` turns
that into something the CPU backend can actually run — the emitted array becomes scalar globals,
so a new parameter vector costs a `setGlobal` rather than a recompile.

Read the table below against the program above it. Wire #0 is the `3.0` you can see; wires
#1..#16 are the four `vec4` operands of `palette`. Nothing here invented syntax for a parameter.

```edn [field-box id="stacksl-wires" role="properties"]
{"id" "stacksl-wires" "index" 3 "name" "The wires — every literal in a StackSL program, made addressable" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The wires — every literal in a StackSL program, made addressable" "frame" #afield/rect [60.0 60.0 880.0 900.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacksl-wires_code_.kt" "lines" 65 "characters" 2930 "sha256" "2bde48a1f0997ae0cb57c0d3db8f5de106b07981fe7b376432c0f8b8041e9a10"}]}
```

```kotlin [field-property box="stacksl-wires" name="code" path="stacksl-wires_code_.kt"]
// Every number you typed in a StackSL program is a free parameter. Here they are.
//
// `StackSLCompileOptions.externalizeConstants` lifts each numeric literal out of a program into a
// wire, and records where it came from: the token, the line, the column. `StackSLWires.lift` turns
// that into something the CPU backend can actually run — the emitted array becomes scalar globals,
// so a new parameter vector costs a `setGlobal` rather than a recompile.
//
// Read the table below against the program above it. Wire #0 is the `3.0` you can see; wires
// #1..#16 are the four `vec4` operands of `palette`. Nothing here invented syntax for a parameter.

import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLWires

run {

    val program = """
        uv 3.0 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    println("The StackSL program:")
    println(program.prependIndent("    "))
    println()

    val wired = StackSLWires.lift(program)
    println("${wired.wires.size} wires, ${wired.literalWires.size} of them literal-backed:")
    println("    idx  token   at      initial  note")
    wired.wires.forEach { wire ->
        println(
            "    #%-3d %-7s %-7s %-8s %s".format(
                wire.index,
                wire.token ?: "-",
                if (wire.line != null) "${wire.line}:${wire.column}" else "-",
                wire.initial,
                wire.note
            )
        )
    }

    // The distinction that matters for writing a fitted value BACK into the source: a wire noted
    // "number" stands for a literal a human typed, so it has a token to replace. A wire noted
    // "optional default ..." is a default the emitter supplied for a word that was given no
    // operand — there is nothing in the source to rewrite. This program has 17 of the first kind
    // and 0 of the second, which is why it is the one the fit uses.
    println()
    println("The lowered GLSL starts with the wires as ordinary globals:")
    println(wired.glsl.lines().take(4).joinToString("\n").prependIndent("    "))
    println("    ... then ${wired.glsl.lines().size - 4} more lines, with no array anywhere:")
    println("    " + (if (wired.glsl.contains("${wired.prefix}_wires")) "FAILED" else "ok"))

    // And it renders, on the CPU, with no GPU and no window.
    val started = System.nanoTime()
    val raster = StackSLCpu.renderStill(program, dim = 220)
    println()
    println("rendered ${raster.width}x${raster.height} in %.2fs".format((System.nanoTime() - started) / 1e9))
    display(raster.toPngBytes())

    // Try it: change the `3.0`, or any of the twelve palette numbers, and run again. Then look at
    // the table — the token and the column move with your edit, because they are read off the
    // compiler's own record of where it found each number.
}
```

