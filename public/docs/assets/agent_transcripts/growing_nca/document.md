# Field Document: growing_nca

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 12 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. growing nca — an angle per cell

<!-- field-box id="nca_fields" index="0" -->

```edn [field-box id="nca_fields" role="properties"]
{"id" "nca_fields" "index" 0 "name" "growing nca — an angle per cell" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — an angle per cell" "_tombstone" false "frame" #afield/rect [40.0 1160.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_fields_code_.kt" "lines" 23 "characters" 1050 "sha256" "ba6d6cb07eae0d3b5819eb9f911a09ae04aab8e7728bb8a6ed6d59824ffa6668"}]}
```

```kotlin [field-property box="nca_fields" name="code" path="nca_fields_code_.kt"]
import java.util.Base64

/*
A different sensor angle for every cell, so each perceives its neighbours in its own frame. Four
angle fields over the 64 x 64 grid (x, y in cells, the seed at 32, 32), each grown 1,000 steps from
one seed with the upright rule.
*/
val fields = [
    "left 0°, right 90°" to { x: Int, _: Int -> if (x < 32) 0.0 else Math.PI / 2 },
    "−45° to 45°, left to right" to { x: Int, _: Int -> (x - 32) / 32.0 * Math.PI / 4 },
    "90° per 20 cells out" to { x: Int, y: Int -> Math.hypot(x - 32.0, y - 32.0) / 20.0 * Math.PI / 2 },
    "down points outward" to { x: Int, y: Int -> Math.atan2(y - 32.0, x - 32.0) - Math.PI / 2 },
]
val parts = mutableListOf<String>()
for ((name, angle) in fields) {
    val nca = GrowingNca.pretrained("flower")
    nca.rotationAt = angle
    nca.step(1000)
    println("$name: ${nca.liveCells()} live cells")
    val png = Base64.getEncoder().encodeToString(nca.png(scale = 2))
    parts += "<img src='data:image/png;base64,$png' width='128'><br>**$name**"
}
showRow("main", parts, width = 600)
```

## 2. growing nca — live (CPU), launch me

<!-- field-box id="nca_live" index="1" -->

### Narrative

From `code`

LAUNCH this box (undo stops it). The same rule, live on the CPU: it grows from a seed, and every
400 steps a disc in a random place is erased so you can watch it heal. The step count and the
error against the target are printed underneath.

```edn [field-box id="nca_live" role="properties"]
{"id" "nca_live" "index" 1 "name" "growing nca — live (CPU), launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — live (CPU), launch me" "_tombstone" false "frame" #afield/rect [420.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_live_code_.kt" "lines" 17 "characters" 655 "sha256" "6206b17dbbed8aa2b80029ba69597230636edfa5f0a957aede6a4f60a9596929"}]}
```

```kotlin [field-property box="nca_live" name="code" path="nca_live_code_.kt"]
/*
LAUNCH this box (undo stops it). The same rule, live on the CPU: it grows from a seed, and every
400 steps a disc in a random place is erased so you can watch it heal. The step count and the
error against the target are printed underneath.
*/
val nca = GrowingNca.pretrained("flower")
val target = GrowingNca.pretrainedTarget("flower")

frames {
    while (true) {
        nca.step(2)
        if (nca.steps % 400 == 200) nca.damage(20.0 + Math.random() * 24, 20.0 + Math.random() * 24, 10.0)
        display("main", nca.png(scale = 6))
        if (nca.steps % 20 == 0) println("step ${nca.steps}, error ${nca.loss(target).f4}")
        frame()
    }
}
```

## 3. growing nca — draw a target

<!-- field-box id="nca_fish_target" index="2" -->

```edn [field-box id="nca_fish_target" role="properties"]
{"id" "nca_fish_target" "index" 2 "name" "growing nca — draw a target" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — draw a target" "_tombstone" false "frame" #afield/rect [40.0 2560.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_fish_target_code_.kt" "lines" 44 "characters" 1194 "sha256" "c3fbfae788535a2099b13356792c5b2832a3d29847115966a8b2813de31a6add"}]}
```

```kotlin [field-property box="nca_fish_target" name="code" path="nca_fish_target_code_.kt"]
import afield.media.image.util.PngEncoder
import java.io.File

/*
Draw the picture for a new rule with FLines, in the 40 x 40 square the trainer uses (pixels, y down),
and save it as a training target.
*/
val body = fline {
    moveTo(33, 20)
    cubicTo(28, 8, 14, 11, 11, 20)
    cubicTo(14, 29, 28, 32, 33, 20)
    filled = true
    color = vec(0.95, 0.5, 0.12, 1.0)
}
val tail = fline {
    moveTo(13, 20)
    lineTo(4, 12)
    lineTo(7, 20)
    lineTo(4, 28)
    close()
    filled = true
    color = vec(0.2, 0.45, 0.85, 1.0)
}
val fin = fline {
    moveTo(17, 14)
    lineTo(21, 7)
    lineTo(26, 13)
    close()
    filled = true
    color = vec(0.2, 0.45, 0.85, 1.0)
}
val eye = fline {
    circle(27.5, 18, 2.0)
    filled = true
    color = vec(0.1, 0.1, 0.15, 1.0)
}

val target = GrowingNca.targetFromFLines([tail, fin, body, eye], size = 40)
val file = File(System.getProperty("java.io.tmpdir"), "nca/fish_target.json")
GrowingNca.writeTarget(file, target, size = 40, name = "fish")
println("wrote $file")
val picture = PngEncoder.encode(GrowingNca.targetArgb(target, 40, scale = 8), 320, 320)
File(file.parentFile, "fish_target.png").writeBytes(picture)
display("main", picture)
```

## 4. renderer (fish)

<!-- field-box id="nca_renderer_fish" index="3" -->

```edn [field-box id="nca_renderer_fish" role="properties"]
{"id" "nca_renderer_fish" "index" 3 "name" "renderer (fish)" "parents" ["root"] "children" ["nca_gpu_fish"] "properties" {"children" #{#afield/box "nca_gpu_fish"} "parents" #{#afield/box "root"} "name" "renderer (fish)" "_tombstone" false "frame" #afield/rect [40.0 2960.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_renderer_fish_code_.kt" "lines" 12 "characters" 269 "sha256" "baf4bcc3331faa1e40875763e9988cebb9f8985f7754ca83247f251d7caf64b4"}]}
```

```kotlin [field-property box="nca_renderer_fish" name="code" path="nca_renderer_fish_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Growing NCA, fish")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 5. growing nca — sensors turned

<!-- field-box id="nca_rotate" index="4" -->

```edn [field-box id="nca_rotate" role="properties"]
{"id" "nca_rotate" "index" 4 "name" "growing nca — sensors turned" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — sensors turned" "_tombstone" false "frame" #afield/rect [40.0 360.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_rotate_code_.kt" "lines" 25 "characters" 1116 "sha256" "6a0303f391f9f5277cb0726e59301820d9939163436192582c10477743bff06f"}]}
```

```kotlin [field-property box="nca_rotate" name="code" path="nca_rotate_code_.kt"]
import java.util.Base64

/*
Grow the flower with its cells' two gradient sensors turned by each angle. The rule was trained
upright and is not retrained. Under each flower: its error against the target turned by the same
angle, then (in brackets) the error the upright flower would have against that turned target.
*/
val target = GrowingNca.pretrainedTarget("flower")
val upright = GrowingNca.pretrained("flower")
upright.step(300)

fun img(png: ByteArray) = "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' width='96'>"

val parts = mutableListOf<String>()
for (degrees in [0, 15, 30, 45, 60, 90, 135, 180]) {
    val nca = GrowingNca.pretrained("flower")
    nca.rotation = Math.toRadians(degrees.toDouble())
    nca.step(300)
    val turned = GrowingNca.rotateTarget(target, nca.rotation)
    val error = "%.1e".format(nca.loss(turned))
    val ifUpright = "%.1e".format(upright.loss(turned))
    println("$degrees°: error $error (upright flower: $ifUpright)")
    parts += "${img(nca.png(scale = 2))}<br>**$degrees°**<br>$error<br>($ifUpright)"
}
showRow("main", parts, width = 900)
```

## 6. growing nca — turning a grown flower

<!-- field-box id="nca_turn" index="5" -->

```edn [field-box id="nca_turn" role="properties"]
{"id" "nca_turn" "index" 5 "name" "growing nca — turning a grown flower" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — turning a grown flower" "_tombstone" false "frame" #afield/rect [40.0 760.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_turn_code_.kt" "lines" 31 "characters" 1072 "sha256" "b2fded6dc0396d0fa02960a76ea5f825f5c60d666a5f38e80a64575799203033"}]}
```

```kotlin [field-property box="nca_turn" name="code" path="nca_turn_code_.kt"]
import java.util.Base64

/*
Grow the flower upright, then turn its sensors 45° and keep stepping. Last, erase everything but
the 3 x 3 cells at the seed and let that grow again with the sensors still turned.
*/
val target = GrowingNca.pretrainedTarget("flower")
val turned = GrowingNca.rotateTarget(target, Math.toRadians(45.0))
val nca = GrowingNca.pretrained("flower")
val parts = mutableListOf<String>()

fun snap(label: String) {
    val png = Base64.getEncoder().encodeToString(nca.png(scale = 2))
    val upright = "%.1e".format(nca.loss(target))
    val at45 = "%.1e".format(nca.loss(turned))
    println("$label (step ${nca.steps}): error upright $upright, turned 45° $at45")
    parts += "<img src='data:image/png;base64,$png' width='128'><br>**$label**<br>step ${nca.steps}"
}

nca.step(300)
snap("grown upright")
nca.rotation = Math.toRadians(45.0)
nca.step(100)
snap("turned 45°")
nca.step(300)
snap("turned 45°")
nca.damage { x, y -> !(x in 31..33 && y in 31..33) }
snap("only the seed left")
nca.step(400)
snap("regrown")
showRow("main", parts, width = 720)
```

## 7. growing nca — damage sweep

<!-- field-box id="nca_damage" index="6" -->

```edn [field-box id="nca_damage" role="properties"]
{"id" "nca_damage" "index" 6 "name" "growing nca — damage sweep" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — damage sweep" "_tombstone" false "frame" #afield/rect [40.0 1560.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_damage_code_.kt" "lines" 105 "characters" 5098 "sha256" "b948807d3d10d349e45882f6b5804fee6d45140384a77dd5387c63e5c14155a7"}]}
```

```kotlin [field-property box="nca_damage" name="code" path="nca_damage_code_.kt"]
import java.util.Base64

/*
Grow the flower for 300 steps, cut it in one of thirteen ways, and follow it for 1,600 steps after
the cut, four times each with a different random update mask. Each cut is shown just after it is
made and at the end of its first run, with how its four runs ended; the chart shows the median
error against steps since the cut for six of them. Takes about 15 seconds on a 10-core machine.
*/
val target = GrowingNca.pretrainedTarget("flower")
val cuts = [
    "disc r 4, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 4.0) },
    "disc r 8, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 8.0) },
    "disc r 12, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 12.0) },
    "disc r 16, top" to { n: GrowingNca -> n.damage(32.0, 26.0, 16.0) },
    "disc r 8, centre" to { n: GrowingNca -> n.damage(32.0, 32.0, 8.0) },
    "disc r 12, centre" to { n: GrowingNca -> n.damage(32.0, 32.0, 12.0) },
    "square 16, centre" to { n: GrowingNca -> n.damage { x, y -> x in 24 until 40 && y in 24 until 40 } },
    "stem" to { n: GrowingNca -> n.damage { x, y -> y >= 36 && x in 26..38 } },
    "left half" to { n: GrowingNca -> n.damage { x, _ -> x < 32 } },
    "right half" to { n: GrowingNca -> n.damage { x, _ -> x >= 32 } },
    "top half" to { n: GrowingNca -> n.damage { _, y -> y < 32 } },
    "bottom half" to { n: GrowingNca -> n.damage { _, y -> y >= 32 } },
    "only the seed left" to { n: GrowingNca -> n.damage { x, y -> !(x in 31..33 && y in 31..33) } },
]
// steps since the cut at which the error is recorded
val at = [1, 10, 25, 50, 100, 200, 400, 800, 1600]

fun img(n: GrowingNca) =
    "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(n.png(scale = 2))}' width='80'>"

// the target moved by (dx, dy) cells
fun shifted(dx: Int, dy: Int) =
    FloatArray(target.size) { i ->
        val x = i / 4 % 64 - dx
        val y = i / 4 / 64 - dy
        if (x in 0 until 64 && y in 0 until 64) target[(y * 64 + x) * 4 + i % 4] else 0f
    }
val moves = (-4..4).flatMap { dx -> (-4..4).map { dy -> Triple(dx, dy, shifted(dx, dy)) } }

// healed: back to the uncut error; moved: the same, against the target moved (dx, dy) cells (y down);
// second growth: alpha far from the picture; died: nothing alive; otherwise scarred
fun outcome(n: GrowingNca): String {
    if (n.liveCells() == 0) return "died"
    if (n.strayAlpha(target) > 50) return "second growth"
    if (n.loss(target) < 1e-4) return "healed"
    val best = moves.minBy { n.loss(it.third) }
    return if (n.loss(best.third) < 1e-4) "moved (${best.first}, ${best.second})" else "scarred"
}

// One run: grow 300 steps, cut, follow the error. Returns the error curve, how it ended, and the
// pictures just after the cut and at the end.
fun run(cut: (GrowingNca) -> Unit, seed: Int): Triple<List<Double>, String, String> {
    val nca = GrowingNca.pretrained("flower", random = kotlin.random.Random(seed))
    nca.step(300)
    cut(nca)
    var pictures = img(nca)
    val curve = mutableListOf<Double>()
    var done = 0
    for (s in at) {
        nca.step(s - done)
        done = s
        curve += nca.loss(target)
    }
    pictures += img(nca)
    return Triple(curve, outcome(nca), pictures)
}

// The 52 runs are independent, so they run in parallel, one per core.
val started = System.nanoTime()
val jobs = cuts.indices.flatMap { c -> (1..4).map { seed -> c to seed } }
val runs = jobs.parallelStream().map { (c, seed) -> run(cuts[c].second, seed) }.toList()
println("${runs.size} runs in ${((System.nanoTime() - started) / 1e9).f1} s")

val figures = mutableListOf<String>()
val curves = mutableListOf<Pair<String, List<Number>>>()
for ((c, cut) in cuts.withIndex()) {
    val mine = runs.slice(c * 4 until c * 4 + 4)
    val median = at.indices.map { i -> mine.map { it.first[i] }.sorted().let { (it[1] + it[2]) / 2 } }
    curves += cut.first to median
    val tally =
        mine
            .groupingBy { it.second.substringBefore(" (") }
            .eachCount()
            .entries
            .joinToString(", ") { "${it.key} ${it.value}" }
    val shifts = mine.map { it.second }.filter { it.startsWith("moved") }.joinToString("") { " " + it }
    println("${cut.first}: $tally$shifts; median error at 1,600 steps ${"%.1e".format(median.last())}")
    figures += "${mine[0].third}<br>**${cut.first}**<br>$tally"
}

// six of the thirteen curves, so the legend fits on one line
val charted = ["disc r 4, top", "disc r 12, top", "disc r 12, centre", "stem", "bottom half", "only the seed left"]
val recovery = chart(
    *curves.filter { it.first in charted }.toTypedArray(),
    x = at,
    title = "Recovery after a cut (median of four runs)",
    xLabel = "steps since the cut",
    yLabel = "error against the target",
)
recovery.xKind = ScaleKind.LOG
recovery.yKind = ScaleKind.LOG
recovery.width = 1100.0
// the figures five to a row, then the chart beneath them as SVG
val rows = figures.chunked(5).joinToString("\n\n") { FieldMarkdown.row(it) }
markdown("main", rows + "\n\n" + drawToSVG(recovery.toFLines(), recovery.width, recovery.height), width = 1150)
```

## 8. growing nca — the fish rule (CPU)

<!-- field-box id="nca_fish_strip" index="7" -->

```edn [field-box id="nca_fish_strip" role="properties"]
{"id" "nca_fish_strip" "index" 7 "name" "growing nca — the fish rule (CPU)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — the fish rule (CPU)" "_tombstone" false "frame" #afield/rect [420.0 2560.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_fish_strip_code_.kt" "lines" 39 "characters" 1086 "sha256" "7a8ddc916ed57c7e8391e5ca89edc7ed71d2283230592b38eab8fd3f6af216a2"}]}
```

```kotlin [field-property box="nca_fish_strip" name="code" path="nca_fish_strip_code_.kt"]
import afield.media.image.util.PngEncoder

/*
The rule trained on the drawn fish, on the CPU: grow it from one cell, cut a disc out of its back,
and let it grow back, as the flower does above.
*/
val nca = GrowingNca.pretrained("fish")
val target = GrowingNca.pretrainedTarget("fish")
val scale = 4
val w = nca.size * scale
val shots = mutableListOf<IntArray>()

fun snap(label: String) {
    shots += nca.argb(scale)
    println("$label: step ${nca.steps}, error ${nca.loss(target).f4}")
}

for (s in [30, 100, 300]) {
    nca.step(s - nca.steps)
    snap("growing")
}
// erase a disc 20 cells across through the fish's back
nca.damage(34.0, 26.0, 10.0)
snap("damaged")
for (s in [340, 400, 600]) {
    nca.step(s - nca.steps)
    snap("regrowing")
}

val gap = 4
val stripW = shots.size * (w + gap) - gap
val strip = IntArray(stripW * w) { 0xffdddddd.toInt() }
for ((k, shot) in shots.withIndex()) {
    for (y in 0 until w) {
        val at = y * stripW + k * (w + gap)
        System.arraycopy(shot, y * w, strip, at, w)
    }
}
display("main", PngEncoder.encode(strip, stripW, w))
```

## 9. renderer

<!-- field-box id="nca_renderer" index="8" -->

```edn [field-box id="nca_renderer" role="properties"]
{"id" "nca_renderer" "index" 8 "name" "renderer" "parents" ["root"] "children" ["nca_gpu"] "properties" {"children" #{#afield/box "nca_gpu"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_renderer_code_.kt" "lines" 12 "characters" 263 "sha256" "c3f4dfacb4a318c3cbc01337c0a4b3817a2cb5b980459192209dd36da8d1f0de"}]}
```

```kotlin [field-property box="nca_renderer" name="code" path="nca_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Growing NCA")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 10. growing nca (GPU)

<!-- field-box id="nca_gpu" index="9" -->

### Narrative

From `code`

A growing neural cellular automaton on the GPU. Run this box beneath the renderer (`window`) box.

Every cell of a 96 x 96 grid holds 16 numbers and runs the same small network twice a frame: the
`compute` shader (open it with the property switcher) is GrowingNca.computeShader(), whose network
part MlpWeights.glsl wrote. The `vertex` and `fragment` shaders draw the first four numbers, the
cell's colour. Hold the left mouse button over the window to erase cells and watch them grow back.

```edn [field-box id="nca_gpu" role="properties"]
{"id" "nca_gpu" "index" 9 "name" "growing nca (GPU)" "parents" ["root" "nca_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "nca_renderer"} "name" "growing nca (GPU)" "_tombstone" false "frame" #afield/rect [420.0 40.0 340.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "nca_gpu_compute_.glsl" "lines" 103 "characters" 3684 "sha256" "4757e0ca4d6b016ae44d3d19ff1a24387a0620b10f20ba76c9fea674299c8d75"} {"name" "vertex" "language" "glsl" "path" "nca_gpu_vertex_.glsl" "lines" 12 "characters" 345 "sha256" "c55d55eab8d8b7e6dd2cf26f915903dfb3364de1defda77b9dc14489a101cf01"} {"name" "fragment" "language" "glsl" "path" "nca_gpu_fragment_.glsl" "lines" 31 "characters" 1302 "sha256" "d30ac0135056781a0bd0945a066ceb50998316ee79395725ac8cbc3c397a78f2"} {"name" "code" "language" "kotlin" "path" "nca_gpu_code_.kt" "lines" 104 "characters" 4051 "sha256" "98985dbd461aebecd2f2d1313c86aaa43728794af3c5a4736a15b39c5156b8c6"}]}
```

```glsl [field-property box="nca_gpu" name="compute" path="nca_gpu_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// MlpWeights(48->128->16, RELU, 8336 parameters)
// Generated by MlpWeights.glsl(); the weight layout is documented on MlpWeights.
layout(std430, buffer_reference) buffer ncaNetWeights {
    float w[];
};

void ncaNet(ncaNetWeights nw, float x[48], out float y[16]) {
    float h0[128];
    // layer 0: 48 -> 128, relu
    for (int o = 0; o < 128; ++o) {
        float s = nw.w[6144 + o];
        for (int i = 0; i < 48; ++i) s += nw.w[0 + o * 48 + i] * x[i];
        h0[o] = max(s, 0.0);
    }
    // layer 1: 128 -> 16, linear
    for (int o = 0; o < 16; ++o) {
        float s = nw.w[8320 + o];
        for (int i = 0; i < 128; ++i) s += nw.w[6272 + o * 128 + i] * h0[i];
        y[o] = s;
    }
}
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, buffer_reference) buffer NcaState {
    vec4 v[];
};
layout(push_constant) uniform pcs {
    NcaState cells;
    ncaNetWeights nw;
    uint size;
    uint parity;
    uint seed;
    vec4 brush;
    float angle;
} pc;

float cellAlpha(int x, int y) {
    if (x < 0 || y < 0 || x >= int(pc.size) || y >= int(pc.size)) return 0.0;
    return pc.cells.v[int(pc.parity * pc.size * pc.size * 4u) + (y * int(pc.size) + x) * 4].w;
}
float hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return float(v.x & 0xffffffu) / 16777216.0;
}

void main() {
    int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), n = int(pc.size);
    if (x >= n || y >= n) return;
    int src = int(pc.parity) * n * n * 4, dst = (1 - int(pc.parity)) * n * n * 4;
    int base = (y * n + x) * 4;
    float s[16];
    for (int k = 0; k < 4; ++k) {
        vec4 q = pc.cells.v[src + base + k];
        s[4*k] = q.x;
        s[4*k+1] = q.y;
        s[4*k+2] = q.z;
        s[4*k+3] = q.w;
    }

    bool pre = false;
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) pre = pre || cellAlpha(x + dx, y + dy) > 0.1;

    if (pre && hash(uvec3(x, y, pc.seed)) <= 0.5) {
        float ca = cos(pc.angle), sa = sin(pc.angle);
        float p[48];
        for (int c = 0; c < 48; ++c) p[c] = 0.0;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            int xx = x + dx, yy = y + dy;
            if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
            float sobelX = float(dx) * (dy == 0 ? 2.0 : 1.0) / 8.0;
            float sobelY = float(dy) * (dx == 0 ? 2.0 : 1.0) / 8.0;
            float sx = ca * sobelX - sa * sobelY, sy = sa * sobelX + ca * sobelY;
            int nb = (yy * n + xx) * 4;
            for (int k = 0; k < 4; ++k) {
                vec4 q = pc.cells.v[src + nb + k];
                for (int j = 0; j < 4; ++j) {
                    int c = 4 * k + j;
                    p[16 + c] += sx * q[j];
                    p[32 + c] += sy * q[j];
                }
            }
        }
        for (int c = 0; c < 16; ++c) p[c] = s[c];
        float ds[16];
        ncaNet(pc.nw, p, ds);
        for (int c = 0; c < 16; ++c) s[c] += ds[c];
    }
    // The second half of alive masking needs the neighbours' *updated* alpha, which this
    // pass cannot see; the draw pass and the next step's pre-check apply it instead
    // (see dev_docs/GROWING_NCA.md).
    if (!pre) for (int c = 0; c < 16; ++c) s[c] = 0.0;
    if (pc.brush.w > 0.0 && distance(vec2(x, y) + 0.5, pc.brush.xy) < pc.brush.z) for (int c = 0; c < 16; ++c) s[c] = 0.0;
    for (int k = 0; k < 4; ++k) pc.cells.v[dst + base + k] = vec4(s[4*k], s[4*k+1], s[4*k+2], s[4*k+3]);
}
```

```glsl [field-property box="nca_gpu" name="vertex" path="nca_gpu_vertex_.glsl"]
#version 450

// one triangle that covers the whole window; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
```

```glsl [field-property box="nca_gpu" name="fragment" path="nca_gpu_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(location = 0) out vec4 uFragColor;

// The simulation's two grids, one after the other: four vec4s per cell, the first being red,
// green, blue, alpha. `shown` picks the grid the last step wrote.
layout(std430, buffer_reference) buffer NcaCells {
    vec4 v[];
};

layout(push_constant) uniform pcs {
    NcaCells state;
    uint shown;
    vec2 viewport;
    uint gridSize;
} pc;

void main() {
    // The grid is drawn as the largest centred square that fits the window, one square per cell.
    float side = min(pc.viewport.x, pc.viewport.y);
    vec2 cell = (gl_FragCoord.xy - 0.5 * (pc.viewport - side)) / side * float(pc.gridSize);
    if (any(lessThan(cell, vec2(0))) || any(greaterThanEqual(cell, vec2(pc.gridSize)))) {
        uFragColor = vec4(0.12, 0.12, 0.12, 1);
        return;
    }
    ivec2 c = ivec2(cell);
    vec4 rgba = clamp(pc.state.v[int(pc.shown * pc.gridSize * pc.gridSize * 4u) + (c.y * int(pc.gridSize) + c.x) * 4], 0.0, 1.0);
    // Colours are premultiplied, so this is "over white", the way the article shows them. The window
    // is sRGB, so the colour is linearised here and comes out as the CPU picture's bytes.
    uFragColor = vec4(pow(clamp(1.0 - rgba.a + rgba.rgb, 0.0, 1.0), vec3(2.2)), 1.0);
}
```

```kotlin [field-property box="nca_gpu" name="code" path="nca_gpu_code_.kt"]
/*
A growing neural cellular automaton on the GPU. Run this box beneath the renderer (`window`) box.

Every cell of a 96 x 96 grid holds 16 numbers and runs the same small network twice a frame: the
`compute` shader (open it with the property switcher) is GrowingNca.computeShader(), whose network
part MlpWeights.glsl wrote. The `vertex` and `fragment` shaders draw the first four numbers, the
cell's colour. Hold the left mouse button over the window to erase cells and watch them grow back.
*/
val nca = GrowingNca.pretrained("flower", size = 96)
val n = nca.size

// Two whole grids in one buffer, four vec4s (16 floats) per cell: each step reads one grid and
// writes the other, and `parity` says which is which.
val cells = defaultBuffer { size = (2 * n * n * 4).vec4s }.buffer
// The network's 8,336 weights, in MlpWeights' layout, which is what the shader's ncaNet reads.
val weights = defaultBuffer { size = ((nca.mlp.flat.size + 3) / 4).vec4s }.buffer
weights.write { put(nca.mlp.flat) }
// Both grids start as the CPU grid: empty except one seed cell in the middle.
cells.write {
    put(nca.state)
    put(nca.state)
}

var step = 0
var brush = vec(0, 0, 0, 0)
// set to false to leave the cutting to the mouse alone
var autoCut = true
// the angle, in radians, the cells' two gradient sensors are turned by; 0 grows the flower upright
var angle = 0.0

/*
Two steps a frame: `forward` reads grid 0 and writes grid 1, `back` reads grid 1 and writes grid 0,
with a barrier between them so the second sees what the first wrote.
*/
val sim = computeModuleHere {}
fun stepMesh(parity: Int): ComputeMesh {
    val m = ComputeMesh(renderer, sim)
    m.cells = { cells }
    m.nw = { weights }
    m.size = { n }
    m.parity = { parity }
    // `seed` gives a fresh random update mask every step. The bindings only read: the
    // compute tasks below advance `step` and set and clear `brush`.
    m.seed = { step }
    m.brush = { brush }
    m.angle = { angle.toFloat() }
    m.x = (n + 7) / 8
    m.y = (n + 7) / 8
    m.z = 1
    return m
}
/*
The window draws as fast as it can, often thousands of frames a second, which would grow and heal
the flower faster than you can see. So the pair of steps runs only when the clock says it is due:
`stepsPerSecond` of them a second, whatever the frame rate.
*/
var stepsPerSecond = 120.0
val started = System.nanoTime()
var due = false
val forward = stepMesh(0)
val back = stepMesh(1)
sim +=
    "forward" to
        computeTask { inside, cmd, push ->
            due = step < (System.nanoTime() - started) / 1e9 * stepsPerSecond
            if (due) {
                step++
                // with no mouse about, cut a disc out of the flower every 600 steps (5 s)
                if (autoCut && step % 600 == 401)
                    brush = vec(n / 2 + (Math.random() - 0.5) * 16, n / 2 - 4 + (Math.random() - 0.5) * 16, 9.0, 1.0)
                forward.compute(inside, cmd, push)
            }
        }
sim += "between" to computeBarrier()
sim +=
    "back" to
        computeTask { inside, cmd, push ->
            if (due) {
                step++
                back.compute(inside, cmd, push)
                // the cut (or the mouse's disc) has been applied to both steps
                brush = vec(0, 0, 0, 0)
            }
        }
sim += "publish" to computeBarrier(graphics = true)
renderer += "nca-sim" to sim

// The draw pass reads grid 0, which `back` wrote last.
val draw = moduleHere()
val quad = fullscreenTriangle(draw)
draw += "quad" to quad
renderer += "nca-draw" to draw
quad.state = { cells }
quad.shown = { 0 }
quad.viewport = { vec(window.width.toFloat(), window.height.toFloat()) }
quad.gridSize = { n }

// The mouse erases a disc of cells 6 wide, mapped through the same centred square the shader draws.
WindowBrush.install(window, "growing-nca") { fx, fy ->
    val w = window.width.toDouble()
    val h = window.height.toDouble()
    val side = minOf(w, h)
    brush = vec((fx * w - (w - side) / 2) / side * n, (fy * h - (h - side) / 2) / side * n, 6.0, 1.0)
}
```

## 11. growing nca — grow and regrow

<!-- field-box id="nca_strip" index="10" -->

```edn [field-box id="nca_strip" role="properties"]
{"id" "nca_strip" "index" 10 "name" "growing nca — grow and regrow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "growing nca — grow and regrow" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "nca_strip_code_.kt" "lines" 40 "characters" 1201 "sha256" "2f7638380c2e4ef983ecbd66079c01da235f194ecb23fce8174db6ddd4deb1da"}]}
```

```kotlin [field-property box="nca_strip" name="code" path="nca_strip_code_.kt"]
import afield.media.image.util.PngEncoder

/*
Grow a flower from one cell, cut a hole in it, and let it grow back: seven snapshots side by side.
Runs on the CPU (no renderer needed); the rule is the one the GPU box below runs.
*/
val nca = GrowingNca.pretrained("flower")
val target = GrowingNca.pretrainedTarget("flower")
val scale = 4
val w = nca.size * scale
val shots = mutableListOf<IntArray>()

fun snap(label: String) {
    shots += nca.argb(scale)
    println("$label: step ${nca.steps}, error ${nca.loss(target).f4}")
}

for (s in [30, 80, 300]) {
    nca.step(s - nca.steps)
    snap("growing")
}
// erase a disc 24 cells across through the top of the flower
nca.damage(32.0, 26.0, 12.0)
snap("damaged")
for (s in [340, 400, 600]) {
    nca.step(s - nca.steps)
    snap("regrowing")
}

// lay the snapshots out in a row, with a 4-pixel grey gap between them
val gap = 4
val stripW = shots.size * (w + gap) - gap
val strip = IntArray(stripW * w) { 0xffdddddd.toInt() }
for ((k, shot) in shots.withIndex()) {
    for (y in 0 until w) {
        val at = y * stripW + k * (w + gap)
        System.arraycopy(shot, y * w, strip, at, w)
    }
}
display("main", PngEncoder.encode(strip, stripW, w))
```

## 12. growing nca (GPU), the drawn fish turned 30°

<!-- field-box id="nca_gpu_fish" index="11" -->

### Narrative

From `code`

The rule trained on the drawn fish, on the GPU with its cells' sensors turned 30°: the box above
with the rule's name and `angle` changed, and a readback that prints the error. Run it beneath
the "renderer (fish)" box.

Every cell of a 96 x 96 grid holds 16 numbers and runs the same small network twice a frame: the
`compute` shader (open it with the property switcher) is GrowingNca.computeShader(), whose network
part MlpWeights.glsl wrote. The `vertex` and `fragment` shaders draw the first four numbers, the
cell's colour. Hold the left mouse button over the window to erase cells and watch them grow back.

```edn [field-box id="nca_gpu_fish" role="properties"]
{"id" "nca_gpu_fish" "index" 11 "name" "growing nca (GPU), the drawn fish turned 30°" "parents" ["root" "nca_renderer_fish"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "nca_renderer_fish"} "name" "growing nca (GPU), the drawn fish turned 30°" "_tombstone" false "frame" #afield/rect [420.0 2960.0 340.0 100.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "nca_gpu_fish_compute_.glsl" "lines" 103 "characters" 3684 "sha256" "4757e0ca4d6b016ae44d3d19ff1a24387a0620b10f20ba76c9fea674299c8d75"} {"name" "vertex" "language" "glsl" "path" "nca_gpu_fish_vertex_.glsl" "lines" 12 "characters" 345 "sha256" "c55d55eab8d8b7e6dd2cf26f915903dfb3364de1defda77b9dc14489a101cf01"} {"name" "fragment" "language" "glsl" "path" "nca_gpu_fish_fragment_.glsl" "lines" 31 "characters" 1302 "sha256" "d30ac0135056781a0bd0945a066ceb50998316ee79395725ac8cbc3c397a78f2"} {"name" "code" "language" "kotlin" "path" "nca_gpu_fish_code_.kt" "lines" 113 "characters" 4654 "sha256" "dacdf3aee7267fa4cc53c3426111c401c3f5cc7102fccf8ecbecba9aefedae93"}]}
```

```glsl [field-property box="nca_gpu_fish" name="compute" path="nca_gpu_fish_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require

// MlpWeights(48->128->16, RELU, 8336 parameters)
// Generated by MlpWeights.glsl(); the weight layout is documented on MlpWeights.
layout(std430, buffer_reference) buffer ncaNetWeights {
    float w[];
};

void ncaNet(ncaNetWeights nw, float x[48], out float y[16]) {
    float h0[128];
    // layer 0: 48 -> 128, relu
    for (int o = 0; o < 128; ++o) {
        float s = nw.w[6144 + o];
        for (int i = 0; i < 48; ++i) s += nw.w[0 + o * 48 + i] * x[i];
        h0[o] = max(s, 0.0);
    }
    // layer 1: 128 -> 16, linear
    for (int o = 0; o < 16; ++o) {
        float s = nw.w[8320 + o];
        for (int i = 0; i < 128; ++i) s += nw.w[6272 + o * 128 + i] * h0[i];
        y[o] = s;
    }
}
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, buffer_reference) buffer NcaState {
    vec4 v[];
};
layout(push_constant) uniform pcs {
    NcaState cells;
    ncaNetWeights nw;
    uint size;
    uint parity;
    uint seed;
    vec4 brush;
    float angle;
} pc;

float cellAlpha(int x, int y) {
    if (x < 0 || y < 0 || x >= int(pc.size) || y >= int(pc.size)) return 0.0;
    return pc.cells.v[int(pc.parity * pc.size * pc.size * 4u) + (y * int(pc.size) + x) * 4].w;
}
float hash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return float(v.x & 0xffffffu) / 16777216.0;
}

void main() {
    int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), n = int(pc.size);
    if (x >= n || y >= n) return;
    int src = int(pc.parity) * n * n * 4, dst = (1 - int(pc.parity)) * n * n * 4;
    int base = (y * n + x) * 4;
    float s[16];
    for (int k = 0; k < 4; ++k) {
        vec4 q = pc.cells.v[src + base + k];
        s[4*k] = q.x;
        s[4*k+1] = q.y;
        s[4*k+2] = q.z;
        s[4*k+3] = q.w;
    }

    bool pre = false;
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) pre = pre || cellAlpha(x + dx, y + dy) > 0.1;

    if (pre && hash(uvec3(x, y, pc.seed)) <= 0.5) {
        float ca = cos(pc.angle), sa = sin(pc.angle);
        float p[48];
        for (int c = 0; c < 48; ++c) p[c] = 0.0;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            int xx = x + dx, yy = y + dy;
            if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
            float sobelX = float(dx) * (dy == 0 ? 2.0 : 1.0) / 8.0;
            float sobelY = float(dy) * (dx == 0 ? 2.0 : 1.0) / 8.0;
            float sx = ca * sobelX - sa * sobelY, sy = sa * sobelX + ca * sobelY;
            int nb = (yy * n + xx) * 4;
            for (int k = 0; k < 4; ++k) {
                vec4 q = pc.cells.v[src + nb + k];
                for (int j = 0; j < 4; ++j) {
                    int c = 4 * k + j;
                    p[16 + c] += sx * q[j];
                    p[32 + c] += sy * q[j];
                }
            }
        }
        for (int c = 0; c < 16; ++c) p[c] = s[c];
        float ds[16];
        ncaNet(pc.nw, p, ds);
        for (int c = 0; c < 16; ++c) s[c] += ds[c];
    }
    // The second half of alive masking needs the neighbours' *updated* alpha, which this
    // pass cannot see; the draw pass and the next step's pre-check apply it instead
    // (see dev_docs/GROWING_NCA.md).
    if (!pre) for (int c = 0; c < 16; ++c) s[c] = 0.0;
    if (pc.brush.w > 0.0 && distance(vec2(x, y) + 0.5, pc.brush.xy) < pc.brush.z) for (int c = 0; c < 16; ++c) s[c] = 0.0;
    for (int k = 0; k < 4; ++k) pc.cells.v[dst + base + k] = vec4(s[4*k], s[4*k+1], s[4*k+2], s[4*k+3]);
}
```

```glsl [field-property box="nca_gpu_fish" name="vertex" path="nca_gpu_fish_vertex_.glsl"]
#version 450

// one triangle that covers the whole window; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
```

```glsl [field-property box="nca_gpu_fish" name="fragment" path="nca_gpu_fish_fragment_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(location = 0) out vec4 uFragColor;

// The simulation's two grids, one after the other: four vec4s per cell, the first being red,
// green, blue, alpha. `shown` picks the grid the last step wrote.
layout(std430, buffer_reference) buffer NcaCells {
    vec4 v[];
};

layout(push_constant) uniform pcs {
    NcaCells state;
    uint shown;
    vec2 viewport;
    uint gridSize;
} pc;

void main() {
    // The grid is drawn as the largest centred square that fits the window, one square per cell.
    float side = min(pc.viewport.x, pc.viewport.y);
    vec2 cell = (gl_FragCoord.xy - 0.5 * (pc.viewport - side)) / side * float(pc.gridSize);
    if (any(lessThan(cell, vec2(0))) || any(greaterThanEqual(cell, vec2(pc.gridSize)))) {
        uFragColor = vec4(0.12, 0.12, 0.12, 1);
        return;
    }
    ivec2 c = ivec2(cell);
    vec4 rgba = clamp(pc.state.v[int(pc.shown * pc.gridSize * pc.gridSize * 4u) + (c.y * int(pc.gridSize) + c.x) * 4], 0.0, 1.0);
    // Colours are premultiplied, so this is "over white", the way the article shows them. The window
    // is sRGB, so the colour is linearised here and comes out as the CPU picture's bytes.
    uFragColor = vec4(pow(clamp(1.0 - rgba.a + rgba.rgb, 0.0, 1.0), vec3(2.2)), 1.0);
}
```

```kotlin [field-property box="nca_gpu_fish" name="code" path="nca_gpu_fish_code_.kt"]
/*
The rule trained on the drawn fish, on the GPU with its cells' sensors turned 30°: the box above
with the rule's name and `angle` changed, and a readback that prints the error. Run it beneath
the "renderer (fish)" box.

Every cell of a 96 x 96 grid holds 16 numbers and runs the same small network twice a frame: the
`compute` shader (open it with the property switcher) is GrowingNca.computeShader(), whose network
part MlpWeights.glsl wrote. The `vertex` and `fragment` shaders draw the first four numbers, the
cell's colour. Hold the left mouse button over the window to erase cells and watch them grow back.
*/
val nca = GrowingNca.pretrained("fish", size = 96)
val n = nca.size

// Two whole grids in one buffer, four vec4s (16 floats) per cell: each step reads one grid and
// writes the other, and `parity` says which is which.
val cells = defaultBuffer { size = (2 * n * n * 4).vec4s }.buffer
// The network's 8,336 weights, in MlpWeights' layout, which is what the shader's ncaNet reads.
val weights = defaultBuffer { size = ((nca.mlp.flat.size + 3) / 4).vec4s }.buffer
weights.write { put(nca.mlp.flat) }
// Both grids start as the CPU grid: empty except one seed cell in the middle.
cells.write {
    put(nca.state)
    put(nca.state)
}

var step = 0
var brush = vec(0, 0, 0, 0)
// set to false to leave the cutting to the mouse alone
var autoCut = true
// the angle, in radians, the cells' two gradient sensors are turned by
var angle = Math.toRadians(30.0)
// what the grid should hold: the fish, turned by the same angle about the seed
val turned = GrowingNca.rotateTarget(GrowingNca.pretrainedTarget("fish", size = n), angle, size = n)

/*
Two steps a frame: `forward` reads grid 0 and writes grid 1, `back` reads grid 1 and writes grid 0,
with a barrier between them so the second sees what the first wrote.
*/
val sim = computeModuleHere {}
fun stepMesh(parity: Int): ComputeMesh {
    val m = ComputeMesh(renderer, sim)
    m.cells = { cells }
    m.nw = { weights }
    m.size = { n }
    m.parity = { parity }
    // `seed` gives a fresh random update mask every step. The bindings only read: the
    // compute tasks below advance `step` and set and clear `brush`.
    m.seed = { step }
    m.brush = { brush }
    m.angle = { angle.toFloat() }
    m.x = (n + 7) / 8
    m.y = (n + 7) / 8
    m.z = 1
    return m
}
/*
The window draws as fast as it can, often thousands of frames a second, which would grow and heal
the flower faster than you can see. So the pair of steps runs only when the clock says it is due:
`stepsPerSecond` of them a second, whatever the frame rate.
*/
var stepsPerSecond = 120.0
val started = System.nanoTime()
var due = false
val forward = stepMesh(0)
val back = stepMesh(1)
sim +=
    "forward" to
        computeTask { inside, cmd, push ->
            due = step < (System.nanoTime() - started) / 1e9 * stepsPerSecond
            if (due) {
                step++
                // with no mouse about, cut a disc out of the flower every 600 steps (5 s)
                if (autoCut && step % 600 == 401)
                    brush = vec(n / 2 + (Math.random() - 0.5) * 16, n / 2 - 4 + (Math.random() - 0.5) * 16, 9.0, 1.0)
                forward.compute(inside, cmd, push)
                // every 600 steps, just before the next cut, copy grid 0 back and score it
                if (step % 600 == 399) {
                    cells.read { get(nca.state) }
                    println("step $step: error against the turned fish ${"%.1e".format(nca.loss(turned))}")
                }
            }
        }
sim += "between" to computeBarrier()
sim +=
    "back" to
        computeTask { inside, cmd, push ->
            if (due) {
                step++
                back.compute(inside, cmd, push)
                // the cut (or the mouse's disc) has been applied to both steps
                brush = vec(0, 0, 0, 0)
            }
        }
sim += "publish" to computeBarrier(graphics = true)
renderer += "nca-sim" to sim

// The draw pass reads grid 0, which `back` wrote last.
val draw = moduleHere()
val quad = fullscreenTriangle(draw)
draw += "quad" to quad
renderer += "nca-draw" to draw
quad.state = { cells }
quad.shown = { 0 }
quad.viewport = { vec(window.width.toFloat(), window.height.toFloat()) }
quad.gridSize = { n }

// The mouse erases a disc of cells 6 wide, mapped through the same centred square the shader draws.
WindowBrush.install(window, "growing-nca-fish") { fx, fy ->
    val w = window.width.toDouble()
    val h = window.height.toDouble()
    val side = minOf(w, h)
    brush = vec((fx * w - (w - side) / 2) / side * n, (fy * h - (h - side) / 2) / side * n, 6.0, 1.0)
}
```

