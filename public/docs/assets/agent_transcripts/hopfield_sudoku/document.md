# Field Document: hopfield_sudoku

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="sudoku_renderer" index="0" -->

```edn [field-box id="sudoku_renderer" role="properties"]
{"id" "sudoku_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sudoku_train"] "properties" {"children" #{#afield/box "sudoku_train"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_renderer_code_.kt" "lines" 12 "characters" 361 "sha256" "ae1fb4399948e90af9053dcd54056d6b91478e6d3322aaab64b91d4e72433d82"}]}
```

```kotlin [field-property box="sudoku_renderer" name="code" path="sudoku_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Hopfield Sudoku", initialDimensions = Rect(80.0, 80.0, 480.0, 320.0))
    Renderer(window)
}
start(renderer::run)
```

## 2. train the energy

<!-- field-box id="sudoku_train" index="1" -->

### Narrative

From `code`

Fits the couplings W and biases B of a 9×9 Sudoku energy to 400 solved grids. The model is
told nothing about rows, columns or boxes; the image shows what it learned: for each pair
of cells, how much more it costs them to hold the same digit than different ones.

```edn [field-box id="sudoku_train" role="properties"]
{"id" "sudoku_train" "index" 1 "name" "train the energy" "parents" ["root" "sudoku_renderer"] "children" ["sudoku_gpu" "sudoku_rate"] "properties" {"children" #{#afield/box "sudoku_gpu" #afield/box "sudoku_rate"} "parents" #{#afield/box "root" #afield/box "sudoku_renderer"} "name" "train the energy" "_tombstone" false "frame" #afield/rect [360.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_train_code_.kt" "lines" 20 "characters" 876 "sha256" "0d0761ef514c10b0c90d05f38b34fc10e331f32b2cd6f304a7d0a606359bc228"}]}
```

```kotlin [field-property box="sudoku_train" name="code" path="sudoku_train_code_.kt"]
/*
Fits the couplings W and biases B of a 9×9 Sudoku energy to 400 solved grids. The model is
told nothing about rows, columns or boxes; the image shows what it learned: for each pair
of cells, how much more it costs them to hold the same digit than different ones.
*/
val model = HopfieldSudoku(3)
val grids = kotlin.random.Random(1).let { r -> List(400) { model.generate(r, 0).second } }
val ms = model.train(grids, epochs = 10, lr = 0.2f, l1 = 1e-4f)
println("trained on ${grids.size} grids in $ms ms, pseudolikelihood loss ${model.pseudoNll(grids)}")
prop<HopfieldSudoku>(sudokuModel) = model

val cost = model.sameDigitCost()
showHeatmap(
    Array(model.cells) { a -> DoubleArray(model.cells) { b -> cost[a * model.cells + b].toDouble() } },
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 486,
    channel = "same-digit cost, cell × cell",
)
```

## 3. solve rate on the CPU

<!-- field-box id="sudoku_rate" index="2" -->

### Narrative

From `code`

Held-out puzzles at five difficulties, solved on the CPU: mean-field descent, then greedy
single-cell moves and annealed Gibbs search, up to four chains, stopping at the energy
threshold fitted from the training grids.

```edn [field-box id="sudoku_rate" role="properties"]
{"id" "sudoku_rate" "index" 2 "name" "solve rate on the CPU" "parents" ["root" "sudoku_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sudoku_train"} "name" "solve rate on the CPU" "_tombstone" false "frame" #afield/rect [1300.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_rate_code_.kt" "lines" 36 "characters" 1186 "sha256" "36dba05b0a1a105495b57c037f488ea1b73772f37339fe0ac9446a2d15efde02"}]}
```

```kotlin [field-property box="sudoku_rate" name="code" path="sudoku_rate_code_.kt"]
/*
Held-out puzzles at five difficulties, solved on the CPU: mean-field descent, then greedy
single-cell moves and annealed Gibbs search, up to four chains, stopping at the energy
threshold fitted from the training grids.
*/
val model = sudokuModel
val test = kotlin.random.Random(99)
val blanks = [30, 40, 45, 50, 55]
val rates = mutableListOf<Double>()
val steps = mutableListOf<Double>()
for (b in blanks) {
    var ok = 0
    var s = 0
    repeat(10) { k ->
        val (p, _) = model.generate(test, b)
        val r = model.solve(p, seed = k.toLong())
        if (model.isSolution(p, r.grid)) ok++
        s += r.descentSteps
    }
    rates += ok / 10.0
    steps += s / 10.0
    println("$b blanks: $ok/10 solved, ${s / 10} descent steps on average")
}
val xs = blanks.map { it.toDouble() }
display(
    "steps to settle",
    Chart(title = "harder puzzles take more steps", xLabel = "blanks", yLabel = "mean descent steps")
        .line("steps", xs, steps)
        .points("steps", xs, steps),
)
display(
    "solve rate",
    Chart(title = "solve rate", xLabel = "blanks", yLabel = "fraction solved")
        .line("solved", xs, rates)
        .points("solved", xs, rates),
)
```

## 4. descend on the GPU

<!-- field-box id="sudoku_gpu" index="3" -->

### Narrative

From `code`

Runs the descent as a compute shader (the `compute` tab), one Euler step per frame, on a
held-out puzzle with 40 blanks, and redraws the board as it settles: each blank shows
its strongest digit in blue, brighter as its belief sharpens; clues are bold white.

```edn [field-box id="sudoku_gpu" role="properties"]
{"id" "sudoku_gpu" "index" 3 "name" "descend on the GPU" "parents" ["root" "sudoku_train"] "children" ["sudoku_check"] "properties" {"children" #{#afield/box "sudoku_check"} "parents" #{#afield/box "root" #afield/box "sudoku_train"} "name" "descend on the GPU" "_tombstone" false "frame" #afield/rect [900.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_gpu_code_.kt" "lines" 78 "characters" 3005 "sha256" "4c8b01a26c7c84862d513b8bdc1a67ea6e859170e3559885509f80b2c248bc9d"} {"name" "compute" "language" "glsl" "path" "sudoku_gpu_compute_.glsl" "lines" 49 "characters" 1708 "sha256" "8821d2a5a0e9b026d677e5e812ddaf3994cf194970028c1cd06c2c1f31951742"}]}
```

```kotlin [field-property box="sudoku_gpu" name="code" path="sudoku_gpu_code_.kt"]
/*
Runs the descent as a compute shader (the `compute` tab), one Euler step per frame, on a
held-out puzzle with 40 blanks, and redraws the board as it settles: each blank shows
its strongest digit in blue, brighter as its belief sharpens; clues are bold white.
*/
val model = sudokuModel
val (puzzle, solution) = model.generate(kotlin.random.Random(7), 40)

var module = computeModuleHere {}
var m = ComputeMesh()
module += "mainMesh" to m
val clock = BondedClock()
clock.freezeAt = 300
renderer.computeList += "sudokuStep" to clock.stepping(module)

val weights = defaultBuffer { size = model.weights.size.floats }.buffer
val state = defaultBuffer { size = model.gpuStateFloats.floats }.buffer
weights.write { put(model.weights) }
val upload = {
    state.write { put(model.gpuState(puzzle)) }
    clock.restart()
}
upload()
prop<FrameBufferGroup>(sudokuState) = state
prop<BondedClock>(sudokuClock) = clock
prop<IntArray>(sudokuPuzzle) = puzzle
prop<() -> Unit>(sudokuUpload) = upload

m.weights = { weights }
m.state = { state }
m.tick = { clock.tick(renderer.frameCounter) }
m.n = { model.n }
m.eta = { 0.5f }
m.beta = { 1f }
m.x = (model.cells + 63) / 64
m.y = 1
m.z = 1

fun board(p: FloatArray, step: Int): String {
    val cells =
        (0 until model.cells).joinToString("") { c ->
            val d = (0 until model.n).maxBy { p[c * model.n + it] }
            val a = p[c * model.n + d]
            val clue = puzzle[c] > 0
            val edge =
                (if (c % 9 == 2 || c % 9 == 5) "border-right:2px solid #888;" else "") +
                    (if (c / 9 == 2 || c / 9 == 5) "border-bottom:2px solid #888;" else "")
            val colour = if (clue) "#fff" else "#7fd4ff"
            "<div style='$edge;display:flex;align-items:center;justify-content:center;" +
                "color:$colour;opacity:${if (clue) 1.0 else 0.25 + 0.75 * a};font-weight:${if (clue) 700 else 400}'>${d + 1}</div>"
        }
    return "<div style='font:15px monospace;color:#ccc'>step $step</div>" +
        "<div style='display:grid;grid-template-columns:repeat(9,24px);grid-auto-rows:24px;" +
        "font:16px monospace;border:2px solid #888;width:max-content'>$cells</div>"
}

val buf = FloatArray(model.gpuStateFloats)
var previous = FloatArray(model.units)
var reported = -1
frames {
    while (true) {
        state.read { get(buf) }
        val step = clock.step
        val p = model.gpuBeliefs(buf, step)
        if (step % 3 == 0) display("board", board(p, step))
        val moved = p.indices.maxOf { kotlin.math.abs(p[it] - previous[it]) }
        if (step > 2 && moved < 1e-4f && reported < 0) {
            reported = step
            val grid = model.decode(puzzle, p)
            println(
                "settled after about $step steps; ${grid.indices.count { grid[it] != solution[it] }} cells differ from the solution, valid = ${model.isSolution(puzzle, grid)}",
            )
            display("board", board(p, step))
        }
        previous = p
        frame()
    }
}
```

```glsl [field-property box="sudoku_gpu" name="compute" path="sudoku_gpu_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Weights {
    float w[];
};
layout(std430, buffer_reference) buffer State {
    float s[];
};

layout(push_constant) uniform dat {
    Weights weights; // units × units couplings, row-major
    State state; // header, beliefs A, beliefs B, v, biases, clue mask (HopfieldSudoku.gpuState)
    float tick; // step number; negative freezes the board
    uint n;
    float eta;
    float beta;
} pc;

void main() {
    uint c = gl_GlobalInvocationID.x;
    uint n = pc.n;
    uint cells = n * n;
    uint U = cells * n;
    if (c >= cells || pc.tick < 0.0) return;
    uint k = uint(pc.tick);
    uint src = 4u + (k % 2u) * U;
    uint dst = 4u + ((k + 1u) % 2u) * U;
    uint V = 4u + 2u * U;
    // a clue cell copies its one-hot belief across
    if (pc.state.s[4u + 4u * U + c] > 0.5) {
        for (uint d = 0u; d < n; d++) pc.state.s[dst + c * n + d] = pc.state.s[src + c * n + d];
        return;
    }
    // v <- (1 - eta) v - eta (W p + B), then p = softmax(beta v) over this cell's digits
    float mx = -1e30;
    for (uint d = 0u; d < n; d++) {
        uint u = c * n + d;
        float h = pc.state.s[4u + 3u * U + u];
        for (uint j = 0u; j < U; j++) h += pc.weights.w[u * U + j] * pc.state.s[src + j];
        float v = (1.0 - pc.eta) * pc.state.s[V + u] - pc.eta * h;
        pc.state.s[V + u] = v;
        mx = max(mx, pc.beta * v);
    }
    float z = 0.0;
    for (uint d = 0u; d < n; d++) z += exp(pc.beta * pc.state.s[V + c * n + d] - mx);
    for (uint d = 0u; d < n; d++) pc.state.s[dst + c * n + d] = exp(pc.beta * pc.state.s[V + c * n + d] - mx) / z;
}
```

## 5. GPU against CPU

<!-- field-box id="sudoku_check" index="4" -->

### Narrative

From `code`

Restarts the GPU descent, lets it take exactly 40 steps, freezes it, reads the beliefs back
and compares them with HopfieldSudoku.beliefsAfter, the CPU reference. Then lets it run on.

```edn [field-box id="sudoku_check" role="properties"]
{"id" "sudoku_check" "index" 4 "name" "GPU against CPU" "parents" ["root" "sudoku_gpu"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sudoku_gpu"} "name" "GPU against CPU" "_tombstone" false "frame" #afield/rect [900.0 480.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_check_code_.kt" "lines" 22 "characters" 917 "sha256" "666e988930af6d760d0281f8ee6419182b6c038d400fafd8831a55e0d1312bd5"}]}
```

```kotlin [field-property box="sudoku_check" name="code" path="sudoku_check_code_.kt"]
/*
Restarts the GPU descent, lets it take exactly 40 steps, freezes it, reads the beliefs back
and compares them with HopfieldSudoku.beliefsAfter, the CPU reference. Then lets it run on.
*/
val steps = 40
val clock = sudokuClock
clock.freezeAt = 0
frames {
    repeat(4) { frame() }
    sudokuUpload()
    clock.freezeAt = steps
    while (clock.step < steps) frame()
    repeat(4) { frame() }
    val buf = FloatArray(sudokuModel.gpuStateFloats)
    sudokuState.read { get(buf) }
    val gpu = sudokuModel.gpuBeliefs(buf, clock.step)
    val cpu = sudokuModel.beliefsAfter(sudokuPuzzle, clock.step)
    val worst = gpu.indices.maxOf { kotlin.math.abs(gpu[it] - cpu[it]) }
    val same = sudokuModel.decode(sudokuPuzzle, gpu).contentEquals(sudokuModel.decode(sudokuPuzzle, cpu))
    println("after ${clock.step} steps: largest GPU-CPU belief difference $worst, same decoded board = $same")
    clock.freezeAt = 300
}
```

