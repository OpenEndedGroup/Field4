# Field Document: discrete_energy

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="energy_renderer" index="0" -->

```edn [field-box id="energy_renderer" role="properties"]
{"id" "energy_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sudoku_rules"] "properties" {"children" #{#afield/box "sudoku_rules"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "energy_renderer_code_.kt" "lines" 12 "characters" 361 "sha256" "62e3e24b1e9ba11bc26f77276b39cbf40e1a8867bd10ca97362f0ab468dba546"}]}
```

```kotlin [field-property box="energy_renderer" name="code" path="energy_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Discrete energy", initialDimensions = Rect(80.0, 80.0, 480.0, 320.0))
    Renderer(window)
}
start(renderer::run)
```

## 2. Sudoku, stated

<!-- field-box id="sudoku_rules" index="1" -->

### Narrative

From `code`

Sudoku stated by hand: 81 variables (cells) of 9 values (digits), and an allDifferent over each
row, column and box. The clues are fixed. The image shows, for every pair of cells, how much it
costs them to hold the same digit: rows, columns and boxes are the bright structure.

```edn [field-box id="sudoku_rules" role="properties"]
{"id" "sudoku_rules" "index" 1 "name" "Sudoku, stated" "parents" ["root" "energy_renderer"] "children" ["sudoku_gpu" "sudoku_learned"] "properties" {"children" #{#afield/box "sudoku_gpu" #afield/box "sudoku_learned"} "parents" #{#afield/box "root" #afield/box "energy_renderer"} "name" "Sudoku, stated" "_tombstone" false "frame" #afield/rect [360.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_rules_code_.kt" "lines" 43 "characters" 2084 "sha256" "0893b76628748b1fe4f68ba030214773fe5680f8dcb5060aa7953795961f4419"}]}
```

```kotlin [field-property box="sudoku_rules" name="code" path="sudoku_rules_code_.kt"]
/*
Sudoku stated by hand: 81 variables (cells) of 9 values (digits), and an allDifferent over each
row, column and box. The clues are fixed. The image shows, for every pair of cells, how much it
costs them to hold the same digit: rows, columns and boxes are the bright structure.
*/
val clues = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
val sudoku = DiscreteEnergy(81, 9)
for (k in 0 until 9) {
    sudoku.allDifferent((0 until 9).map { k * 9 + it }) // row k
    sudoku.allDifferent((0 until 9).map { it * 9 + k }) // column k
    sudoku.allDifferent((0 until 9).map { (k / 3 * 3 + it / 3) * 9 + k % 3 * 3 + it % 3 }) // box k
}
clues.forEachIndexed { cell, ch -> if (ch != '0') sudoku.fix(cell, ch - '1') }
prop<DiscreteEnergy>(sudokuEnergy) = sudoku

// a board drawn from beliefs: each free cell shows its strongest digit, brighter as it sharpens
val board = { s: EnergyState, caption: String ->
    val cells =
        (0 until 81).joinToString("") { c ->
            val d = s.assignment[c]
            val a = s.p[sudoku.unit(c, d)]
            val clue = sudoku.fixed[c] >= 0
            val edge =
                (if (c % 9 == 2 || c % 9 == 5) "border-right:2px solid #888;" else "") +
                    (if (c / 9 == 2 || c / 9 == 5) "border-bottom:2px solid #888;" else "")
            "<div style='${edge}display:flex;align-items:center;justify-content:center;" +
                "color:${if (clue) "#fff" else "#7fd4ff"};opacity:${if (clue) 1.0 else 0.25 + 0.75 * a};" +
                "font-weight:${if (clue) 700 else 400}'>${d + 1}</div>"
        }
    "<div style='font:14px monospace;color:#ccc'>$caption</div>" +
        "<div style='display:grid;grid-template-columns:repeat(9,24px);grid-auto-rows:24px;" +
        "font:16px monospace;border:2px solid #888;width:max-content'>$cells</div>"
}
prop<(EnergyState, String) -> String>(sudokuBoard) = board

showHeatmap(
    sudoku.sameValueImage(),
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 405,
    channel = "stated: same-digit cost",
)
```

## 3. N-queens

<!-- field-box id="queens_box" index="2" -->

### Narrative

From `code`

N-queens: one variable per row, its value the column of that row's queen. Columns must differ,
and two rows a apart may not have queens a columns apart. Annealed Gibbs, one sweep per frame.

```edn [field-box id="queens_box" role="properties"]
{"id" "queens_box" "index" 2 "name" "N-queens" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "N-queens" "_tombstone" false "frame" #afield/rect [40.0 1000.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "queens_box_code_.kt" "lines" 33 "characters" 1345 "sha256" "b04d5a2025e19eec22fdb679490c8d8969701d75bdedaf0f027b2dab1d7cea87"}]}
```

```kotlin [field-property box="queens_box" name="code" path="queens_box_code_.kt"]
/*
N-queens: one variable per row, its value the column of that row's queen. Columns must differ,
and two rows a apart may not have queens a columns apart. Annealed Gibbs, one sweep per frame.
*/
val n = 12
val queens = DiscreteEnergy(n, n)
queens.allDifferent((0 until n).toList())
for (a in 0 until n) for (b in a + 1 until n) queens.pairCost(a, b) { x, y ->
    if (kotlin.math.abs(x - y) == b - a) 1.0 else 0.0
}

val s = EnergyState(queens).reset(kotlin.random.Random(4))
val gibbs = GibbsAnnealing(queens, sweeps = 200, startTemperature = 1.0, endTemperature = 0.05, seed = 4)
fun board() =
    "<div style='font:14px monospace;color:#ccc'>sweep ${gibbs.sweep}, ${s.energy.toInt()} attacks</div>" +
        "<div style='display:grid;grid-template-columns:repeat($n,20px);grid-auto-rows:20px;width:max-content'>" +
        (0 until n * n).joinToString("") { i ->
            val r = i / n
            val c = i % n
            "<div style='background:${if ((r + c) % 2 == 0) "#333" else "#222"};color:#ffcf6b;text-align:center;font:15px monospace'>" +
                (if (s.assignment[r] == c) "♛" else "") +
                "</div>"
        } +
        "</div>"

frames {
    while (s.energy > 0 && gibbs.step(s)) {
        display("board", board())
        frame()
    }
    GreedyMoves(queens).settle(s)
    display("board", board())
}
```

## 4. descend on the GPU

<!-- field-box id="sudoku_gpu" index="3" -->

### Narrative

From `code`

The mean-field descent on the GPU: the compute tab makes one Euler step per frame on any
DiscreteEnergy, one invocation per variable. The board redraws as it settles; when the beliefs
stop moving, greedy moves and annealed Gibbs finish the board on the CPU. Re-run the check box
after this one has finished: it restarts the GPU descent to compare it with the CPU.

```edn [field-box id="sudoku_gpu" role="properties"]
{"id" "sudoku_gpu" "index" 3 "name" "descend on the GPU" "parents" ["root" "sudoku_rules"] "children" ["sudoku_check"] "properties" {"children" #{#afield/box "sudoku_check"} "parents" #{#afield/box "root" #afield/box "sudoku_rules"} "name" "descend on the GPU" "_tombstone" false "frame" #afield/rect [800.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_gpu_code_.kt" "lines" 66 "characters" 2484 "sha256" "c2359267910f71084ba6e0b830235bc2550ac8f22b283e7f10fcfa93e33e817d"} {"name" "compute" "language" "glsl" "path" "sudoku_gpu_compute_.glsl" "lines" 53 "characters" 1878 "sha256" "5e638635a8ba9d70e4ba87e4cc9d67b9a97ad60bb9726a02f8df5106c3059a42"}]}
```

```kotlin [field-property box="sudoku_gpu" name="code" path="sudoku_gpu_code_.kt"]
/*
The mean-field descent on the GPU: the compute tab makes one Euler step per frame on any
DiscreteEnergy, one invocation per variable. The board redraws as it settles; when the beliefs
stop moving, greedy moves and annealed Gibbs finish the board on the CPU. Re-run the check box
after this one has finished: it restarts the GPU descent to compare it with the CPU.
*/
val model = sudokuEnergy
val state = EnergyState(model)

var module = computeModuleHere {}
var m = ComputeMesh()
module += "mainMesh" to m
val clock = BondedClock()
clock.freezeAt = 400
renderer.computeList += "energyStep" to clock.stepping(module)

val pairwise = defaultBuffer { size = model.pairwise.size.floats }.buffer
val gpu = defaultBuffer { size = model.gpuStateFloats.floats }.buffer
pairwise.write { put(model.pairwise) }
val upload = {
    state.reset()
    gpu.write { put(model.gpuState(state)) }
    clock.restart()
}
upload()
prop<FrameBufferGroup>(energyGpuState) = gpu
prop<BondedClock>(energyClock) = clock
prop<() -> Unit>(energyUpload) = upload

m.pairwise = { pairwise }
m.state = { gpu }
m.tick = { clock.tick(renderer.frameCounter) }
m.units = { model.units }
m.variables = { model.variables }
m.eta = { 0.5f }
m.beta = { 1f }
m.x = (model.variables + 63) / 64
m.y = 1
m.z = 1

val buf = FloatArray(model.gpuStateFloats)
val previous = FloatArray(model.units)
var lastStep = -1
frames {
    while (true) {
        gpu.read { get(buf) }
        val step = clock.step
        // compare beliefs only across real GPU steps, not across frames where the clock did not move
        if (step != lastStep) {
            model.readGpuBeliefs(buf, step, state)
            val moved = state.p.indices.maxOf { kotlin.math.abs(state.p[it] - previous[it]) }
            state.p.copyInto(previous)
            lastStep = step
            if (step % 2 == 0) display("board", sudokuBoard(state, "GPU step $step"))
            if ((step > 10 && moved < 1e-4f) || step >= clock.freezeAt) break
        }
        frame()
    }
    println("GPU descent settled after $lastStep steps, energy ${state.energy}")
    GreedyMoves(model).settle(state)
    // annealed Gibbs from where the descent stopped: a long, gentle schedule
    for (attempt in 0 until 3) if (state.energy > 0)
        GibbsAnnealing(model, 5000, 0.6, 0.15, seed = attempt.toLong()).settle(state)
    println("after greedy moves and Gibbs: energy ${state.energy}")
    display("board", sudokuBoard(state, "finished, energy ${state.energy}"))
}
```

```glsl [field-property box="sudoku_gpu" name="compute" path="sudoku_gpu_compute_.glsl"]
#version 450
#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

layout(std430, buffer_reference) buffer Pairwise {
    float w[];
};
layout(std430, buffer_reference) buffer State {
    float s[];
};

layout(push_constant) uniform dat {
    Pairwise pairwise; // units × units costs W, row-major
    State state; // DiscreteEnergy.gpuState: header, beliefs A, beliefs B, v, unary, then offset/size/fixed per variable
    float tick; // step number; negative freezes the descent
    uint units;
    uint variables;
    float eta;
    float beta;
} pc;

void main() {
    uint a = gl_GlobalInvocationID.x;
    uint U = pc.units;
    if (a >= pc.variables || pc.tick < 0.0) return;
    uint k = uint(pc.tick);
    uint src = 4u + (k % 2u) * U;
    uint dst = 4u + ((k + 1u) % 2u) * U;
    uint V = 4u + 2u * U;
    uint B = 4u + 3u * U;
    uint meta = 4u + 4u * U + 3u * a;
    uint o = uint(pc.state.s[meta]);
    uint n = uint(pc.state.s[meta + 1u]);
    float fixedValue = pc.state.s[meta + 2u];
    // a fixed variable stays one-hot on its value
    if (fixedValue >= 0.0) {
        for (uint x = 0u; x < n; x++) pc.state.s[dst + o + x] = (float(x) == fixedValue) ? 1.0 : 0.0;
        return;
    }
    // v <- (1 - eta) v - eta (W p + B), then p = softmax(beta v) over this variable's values
    float mx = -1e30;
    for (uint x = 0u; x < n; x++) {
        uint u = o + x;
        float h = pc.state.s[B + u];
        for (uint j = 0u; j < U; j++) h += pc.pairwise.w[u * U + j] * pc.state.s[src + j];
        float v = (1.0 - pc.eta) * pc.state.s[V + u] - pc.eta * h;
        pc.state.s[V + u] = v;
        mx = max(mx, pc.beta * v);
    }
    float z = 0.0;
    for (uint x = 0u; x < n; x++) z += exp(pc.beta * pc.state.s[V + o + x] - mx);
    for (uint x = 0u; x < n; x++) pc.state.s[dst + o + x] = exp(pc.beta * pc.state.s[V + o + x] - mx) / z;
}
```

## 5. GPU against CPU

<!-- field-box id="sudoku_check" index="4" -->

### Narrative

From `code`

Restarts the GPU descent, lets it take exactly 40 steps, freezes it, reads the beliefs back and
compares them with 40 steps of MeanFieldDescent on the CPU. Then lets it run on.

```edn [field-box id="sudoku_check" role="properties"]
{"id" "sudoku_check" "index" 4 "name" "GPU against CPU" "parents" ["root" "sudoku_gpu"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sudoku_gpu"} "name" "GPU against CPU" "_tombstone" false "frame" #afield/rect [800.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_check_code_.kt" "lines" 26 "characters" 972 "sha256" "bc259d0f48517f9d5bec44426d75f73fe5e131546075e5c936bcd705840138c8"}]}
```

```kotlin [field-property box="sudoku_check" name="code" path="sudoku_check_code_.kt"]
/*
Restarts the GPU descent, lets it take exactly 40 steps, freezes it, reads the beliefs back and
compares them with 40 steps of MeanFieldDescent on the CPU. Then lets it run on.
*/
val steps = 40
val clock = energyClock
clock.freezeAt = 0
frames {
    repeat(4) { frame() }
    energyUpload()
    clock.freezeAt = steps
    while (clock.step < steps) frame()
    repeat(4) { frame() }
    val buf = FloatArray(sudokuEnergy.gpuStateFloats)
    energyGpuState.read { get(buf) }
    val gpu = EnergyState(sudokuEnergy)
    sudokuEnergy.readGpuBeliefs(buf, clock.step, gpu)
    val cpu = EnergyState(sudokuEnergy)
    val descent = MeanFieldDescent(sudokuEnergy)
    repeat(clock.step) { descent.step(cpu) }
    val worst = gpu.p.indices.maxOf { kotlin.math.abs(gpu.p[it] - cpu.p[it]) }
    println(
        "after ${clock.step} steps: largest GPU-CPU belief difference $worst, same board = ${gpu.assignment.contentEquals(cpu.assignment)}",
    )
    clock.freezeAt = 400
}
```

## 6. Sudoku, learned

<!-- field-box id="sudoku_learned" index="5" -->

### Narrative

From `code`

The same 81 × 9 energy with nothing stated: its costs are learned from 400 solved grids by
pseudolikelihood, relabelling the digits at random each time. Its same-digit image should match
the stated one, and it should solve the same puzzle.

```edn [field-box id="sudoku_learned" role="properties"]
{"id" "sudoku_learned" "index" 5 "name" "Sudoku, learned" "parents" ["root" "sudoku_rules"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sudoku_rules"} "name" "Sudoku, learned" "_tombstone" false "frame" #afield/rect [1200.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sudoku_learned_code_.kt" "lines" 65 "characters" 2306 "sha256" "b6881e6a79f1666ff0ab1095289c8f2a054dcf40e76f770215a99b86417a86cd"}]}
```

```kotlin [field-property box="sudoku_learned" name="code" path="sudoku_learned_code_.kt"]
/*
The same 81 × 9 energy with nothing stated: its costs are learned from 400 solved grids by
pseudolikelihood, relabelling the digits at random each time. Its same-digit image should match
the stated one, and it should solve the same puzzle.
*/
val rnd = kotlin.random.Random(1)
// solved grids by randomized backtracking, digits 0..8
fun solvedGrid(): IntArray {
    val g = IntArray(81) { -1 }
    fun ok(c: Int, d: Int) =
        (0 until 9).none { m ->
            g[c / 9 * 9 + m] == d || g[m * 9 + c % 9] == d || g[(c / 27 * 3 + m / 3) * 9 + c % 9 / 3 * 3 + m % 3] == d
        }
    fun fill(c: Int): Boolean {
        if (c == 81) return true
        for (d in (0 until 9).shuffled(rnd)) if (ok(c, d)) {
            g[c] = d
            if (fill(c + 1)) return true
            g[c] = -1
        }
        return false
    }
    fill(0)
    return g
}
val grids = List(400) { solvedGrid() }

val learned = DiscreteEnergy(81, 9)
val learner = PseudolikelihoodLearner(
    learned,
    rate = 0.2f,
    l1 = 1e-4f,
    augment = { g, r ->
        val relabel = (0 until 9).shuffled(r)
        IntArray(81) { relabel[g[it]] }
    },
)
val t0 = System.currentTimeMillis()
repeat(10) { learner.epoch(grids) }
println("learned from ${grids.size} grids in ${System.currentTimeMillis() - t0} ms, loss ${learner.loss(grids)}")

showHeatmap(
    learned.sameValueImage(),
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 405,
    channel = "learned: same-digit cost",
)

// solve the stated puzzle with the learned energy, stopping at the training solutions' energy
val target = grids.maxOf { learned.energy(it) } + 1e-3
for (c in 0 until 81) if (sudokuEnergy.fixed[c] >= 0) learned.fix(c, sudokuEnergy.fixed[c])
val s = EnergyState(learned)
val steps = MeanFieldDescent(learned).settle(s)
GreedyMoves(learned).settle(s)
val scale = learned.pairwise.maxOf { kotlin.math.abs(it) }.toDouble()
for (attempt in 0 until 3) if (s.energy > target) {
    GibbsAnnealing(learned, 5000, 0.6 * scale, 0.15 * scale, seed = attempt.toLong()).settle(s)
    GreedyMoves(learned).settle(s)
}
println(
    "learned energy: $steps mean-field steps; the board breaks ${sudokuEnergy.energy(s.assignment)} stated constraints",
)
display("learned solve", sudokuBoard(s, "solved by the learned energy"))
```

## 7. travelling salesman

<!-- field-box id="tsp_box" index="6" -->

### Narrative

From `code`

A tour of 10 cities as a permutation: variable k is the city visited k-th, all different, and
consecutive stops cost their distance. Annealed Gibbs, one sweep per frame; city 0 starts the tour.

```edn [field-box id="tsp_box" role="properties"]
{"id" "tsp_box" "index" 6 "name" "travelling salesman" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "travelling salesman" "_tombstone" false "frame" #afield/rect [1100.0 1000.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tsp_box_code_.kt" "lines" 48 "characters" 1728 "sha256" "f326b069a5ab9d7476635f99118950675aab3a080f1a1d12e21f17b993858938"}]}
```

```kotlin [field-property box="tsp_box" name="code" path="tsp_box_code_.kt"]
/*
A tour of 10 cities as a permutation: variable k is the city visited k-th, all different, and
consecutive stops cost their distance. Annealed Gibbs, one sweep per frame; city 0 starts the tour.
*/
val rnd = kotlin.random.Random(3)
val n = 10
val city = List(n) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
val tour = DiscreteEnergy(n, n)
tour.allDifferent((0 until n).toList(), weight = 2.0 * 500)
for (k in 0 until n) tour.pairCost(k, (k + 1) % n) { x, y -> city[x].distance(city[y]).toDouble() }
tour.fix(0, 0)

val s = EnergyState(tour).reset(rnd)
val gibbs = GibbsAnnealing(tour, sweeps = 400, startTemperature = 500.0, endTemperature = 5.0, seed = 3)
fun draw() {
    val path = fline {
        moveTo(city[s.assignment[0]].x, city[s.assignment[0]].y)
        for (k in 1..n) lineTo(city[s.assignment[k % n]].x, city[s.assignment[k % n]].y)
        color = vec(0.35, 0.75, 1.0, 1.0)
        thickness = 2.0
    }
    val stops = fline {
        for (c in city) {
            moveTo(c.x - 4, c.y - 4)
            lineTo(c.x + 4, c.y - 4)
            lineTo(c.x + 4, c.y + 4)
            lineTo(c.x - 4, c.y + 4)
            close()
        }
        filled = true
        stroked = false
        color = vec(1.0, 0.85, 0.3, 1.0)
    }
    lines(path, stops, w = 440.0, h = 340.0, fitToSize = true)
    val visits = s.assignment.toSet().size
    display(
        "tour",
        "sweep ${gibbs.sweep}: $visits of $n cities visited, length ${"%.0f".format((0 until n).sumOf { city[s.assignment[it]].distance(city[s.assignment[(it + 1) % n]]).toDouble() })}",
    )
}
frames {
    while (gibbs.step(s)) {
        if (gibbs.sweep % 4 == 0) draw()
        frame()
    }
    GreedyMoves(tour).settle(s)
    draw()
}
```

## 8. graph colouring

<!-- field-box id="colouring_box" index="7" -->

### Narrative

From `code`

Graph colouring: 40 random points, each joined to its three nearest neighbours, coloured with
four colours so that no edge joins two of the same. Mean-field descent, one step per frame,
then annealed Gibbs; the dots show their strongest colour.

```edn [field-box id="colouring_box" role="properties"]
{"id" "colouring_box" "index" 7 "name" "graph colouring" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "graph colouring" "_tombstone" false "frame" #afield/rect [520.0 1000.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "colouring_box_code_.kt" "lines" 62 "characters" 2261 "sha256" "5149b9b1e448b77f0a0a210366607d4a0e31c97b7ead1401fa0ad381762d43b2"}]}
```

```kotlin [field-property box="colouring_box" name="code" path="colouring_box_code_.kt"]
/*
Graph colouring: 40 random points, each joined to its three nearest neighbours, coloured with
four colours so that no edge joins two of the same. Mean-field descent, one step per frame,
then annealed Gibbs; the dots show their strongest colour.
*/
val rnd = kotlin.random.Random(5)
val pts = List(40) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
val edges = HashSet<Pair<Int, Int>>()
for (i in pts.indices) pts.indices
    .filter { it != i }
    .sortedBy { pts[it].distance(pts[i]) }
    .take(3)
    .forEach { edges += minOf(i, it) to maxOf(i, it) }

val colouring = DiscreteEnergy(pts.size, 4)
for ((a, b) in edges) colouring.notEqual(a, b)

val palette = [vec(0.95, 0.4, 0.35, 1.0), vec(0.35, 0.75, 1.0, 1.0), vec(0.98, 0.85, 0.3, 1.0), vec(0.5, 0.9, 0.5, 1.0)]
val s = EnergyState(colouring).reset(rnd)
fun draw(caption: String) {
    val wires = fline {
        for ((a, b) in edges) {
            moveTo(pts[a].x, pts[a].y)
            lineTo(pts[b].x, pts[b].y)
        }
        color = vec(0.6, 0.6, 0.6, 0.6)
        thickness = 1.0
    }
    val dots =
        pts.indices.map { i ->
            fline {
                val r = 3.0 + 5.0 * s.p[colouring.unit(i, s.assignment[i])]
                moveTo(pts[i].x - r, pts[i].y - r)
                lineTo(pts[i].x + r, pts[i].y - r)
                lineTo(pts[i].x + r, pts[i].y + r)
                lineTo(pts[i].x - r, pts[i].y + r)
                close()
                filled = true
                stroked = false
                color = palette[s.assignment[i]]
            }
        }
    lines(wires, *dots.toTypedArray(), w = 440.0, h = 340.0, fitToSize = true)
    display("conflicts", caption)
}

val descent = MeanFieldDescent(colouring)
val gibbs = GibbsAnnealing(colouring, sweeps = 100, startTemperature = 1.0, endTemperature = 0.05, seed = 2)
frames {
    var step = 0
    while (descent.step(s) > 1e-4f && step < 200) {
        step++
        draw("mean-field step $step: ${s.energy.toInt()} conflicts")
        frame()
    }
    while (s.energy > 0 && gibbs.step(s)) {
        s.beliefsFromAssignment()
        draw("Gibbs sweep ${gibbs.sweep}: ${s.energy.toInt()} conflicts")
        frame()
    }
    draw("${edges.size} edges, ${s.energy.toInt()} conflicts")
}
```

