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
