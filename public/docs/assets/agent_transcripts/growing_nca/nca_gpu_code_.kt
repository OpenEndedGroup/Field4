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
