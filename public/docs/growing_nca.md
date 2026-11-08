# Growing neural cellular automata

A cellular automaton whose rule was learned instead of written. Every cell of a grid holds 16
numbers and runs the same small neural network on itself and its eight neighbours. From a single
seed cell the grid grows a picture, keeps it, and grows it back when part of it is cut away.

This is the model from Mordvintsev, Randazzo, Niklasson and Levin,
[*Growing Neural Cellular Automata*](https://distill.pub/2020/growing-ca) (Distill, 2020, CC-BY 4.0).
Field ships one trained rule, `GrowingNca.pretrained("flower")`, which runs on the CPU in any box
and on the GPU as a compute shader.

## Grow it, cut it, watch it heal

`GrowingNca` holds the grid and the network. `step()` advances it one step, `damage(x, y, radius)`
zeroes a disc of cells, and `argb()` / `png()` turn the colour channels into a picture. This box
grows the flower from one cell, cuts a disc out of the top, and keeps stepping. It prints the error
against the target picture at each snapshot and shows all seven side by side:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/grow_and_regrow.png" wait="3.0" capture="box" open-outputs="true" padding="16" embed=true alt="Seven snapshots of a pink six-petalled flower with a yellow centre and a green stem: a blurry pink blob at step 30, the finished flower at steps 80 and 300, the flower with its top cut away leaving a pink arc and the stem, a blurred regrowth at step 340, a whole flower again at step 400 with a small stray bud at its edge, and at step 600 the flower plus a second complete flower that grew from the bud"}
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

The printed errors fall from 0.014 at step 30 to under 0.0001 by step 80. The cut raises the error
to 0.048, and it is back to 0.002 sixty steps later.

The last frame shows something else this rule does after a cut. Training held it to the picture
for only 64 to 96 steps at a time, and some time after this cut a cell at the edge keeps going: it
becomes a bud, and the bud grows a second, complete flower beside the first.

## The rule

- **State.** Each cell has 16 channels: red, green, blue and alpha (premultiplied), then 12 hidden
  channels that the network uses to signal to its neighbours.
- **Perception.** Each channel is seen through three fixed 3 × 3 filters: identity, Sobel-x and
  Sobel-y, each Sobel divided by 8. That gives 48 numbers per cell.
- **Update.** A network 48 → 128 (ReLU) → 16 proposes a change, which is *added* to the state, and
  only in a random half of the cells each step. With no global clock, a cell cannot count steps.
- **Alive masking.** A cell is cleared unless it or a neighbour has alpha above 0.1, both before
  and after the update. Empty space stays empty, so the picture can only grow outward from cells
  that already exist.

The network's last layer starts at zero, so an untrained rule does nothing. The rule is an
`MlpWeights`, the same type the [neural particle systems](neural_particles.md) use. That means
`MlpWeights.glsl` writes the GPU version of the network and `GrowingNca.computeShader()` wraps the
perception and masking around it.

## On the GPU

The same rule runs as a compute shader on a 96 × 96 grid beneath a renderer box. The flower was
trained at 64 × 64, but the rule is local, so the grid can be any size. Two grids share one buffer:
one compute pass reads the first and writes the second, a barrier follows, and a second pass writes
back. The fragment shader draws the colour channels. Hold the left mouse button over the window to
erase cells. While `autoCut` is on, the box also cuts a random disc every 600 steps:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/gpu_window.png" capture="native-window" wait="6" native-wait-frames="30" embed=true hidden=true timeout="600" duration="0" name="growing-nca-gpu" alt="The Vulkan window: a pink six-petalled flower with a yellow centre and a green stem, grown by the compute shader on a white 96 by 96 grid between grey margins" macro-file="../../dev_docs/agents/transcripts/growing_nca.yaml" capture-box="nca_renderer"}
// Built and launched from dev_docs/agents/transcripts/growing_nca.yaml
// (the CPU strip, a renderer, and the GPU box beneath it).
```

```kotlin
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
with a barrier between them so the second sees what the first wrote. Each mesh keeps its own fixed
`parity` rather than one mesh flipping it between frames.
*/
val sim = computeModuleHere {}
fun stepMesh(parity: Int): ComputeMesh {
    val m = ComputeMesh(renderer, sim)
    m.cells = { cells }
    m.nw = { weights }
    m.size = { n }
    m.parity = { parity }
    // a fresh random update mask every step
    m.seed = {
        step++
        step
    }
    m.brush = {
        // with no mouse about, cut a disc out of the flower every 600 steps (5 s)
        if (autoCut && parity == 0 && step % 600 == 401) brush = vec(n / 2 + (Math.random() - 0.5) * 16, n / 2 - 4 + (Math.random() - 0.5) * 16, 9.0, 1.0)
        val b = brush
        if (parity == 1) brush = vec(0, 0, 0, 0)
        b
    }
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
sim += "forward" to computeTask { inside, cmd, push ->
    due = step < (System.nanoTime() - started) / 1e9 * stepsPerSecond
    if (due) forward.compute(inside, cmd, push)
}
sim += "between" to computeBarrier()
sim += "back" to computeTask { inside, cmd, push -> if (due) back.compute(inside, cmd, push) }
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

The compute shader is in the box's `compute` property. Open it with the property switcher to see
the network that `MlpWeights.glsl` wrote, `ncaNet`, and the perception loop around it.
`stepsPerSecond` keeps the speed watchable, because the window can draw thousands of frames a
second.

??? aside "Why two meshes and not one flipping a parity flag"
    The first version used one `ComputeMesh` whose `parity` push constant alternated every frame.
    The lambda ran every frame (a counter in it advanced), but a readback showed the second grid
    never changed: every dispatch read grid 1 and wrote grid 0, so the picture stayed one step
    from the seed. Two meshes with a fixed `parity` each, separated by `computeBarrier()`, work.
    Swapping two buffer-reference pointers per dispatch failed the same way. The cause in the push
    path is not yet found. `dev_docs/GROWING_NCA.md` has the details.

## Training a rule

The rule was trained in PyTorch, not in Kotlin: the article's method backpropagates through 64 to
96 steps of the whole grid, which Field's own learning code (`flowmatching.kt`, `afield.math.autodiff`)
does not do.

```text
uv run --project . --group pen python tools/nca/train_growing_nca.py --target flower \
    --steps 5000 --out flower.json
```

The script follows the article. Each batch draws 8 grids from a pool of 1,024. The worst one is
replaced by a fresh seed and the best three are damaged with a random disc. The batch runs 64 to 96
steps, and the gradient is normalised per parameter before each Adam step. The learning rate is
2 × 10⁻³, dropping tenfold at step 2,000. The flower is drawn by the script itself (discs for
petals, centre and stem), 40 × 40 on a 64 × 64 grid:

![The training target: a pink six-petalled flower with a yellow centre and a short green stem, 64 pixels square](assets/growing_nca/flower_target.png)

5,000 steps took about 17 minutes on an M-series GPU (MPS). The loss reached 6 × 10⁻⁵ near the end.
Copy the JSON to `src/jvmMain/resources/afield/alife/nca_<name>.json` for
`GrowingNca.pretrained(name)`, or load it from anywhere with `GrowingNca.load(file)`.
`GrowingNcaTest` checks the shipped rule on the CPU. From one seed, 300 steps bring the error to
2 × 10⁻⁵, where an empty grid scores 0.054. After a cut 20 cells wide, 300 more steps bring it to
1.7 × 10⁻⁴.

## Further work

- **[Cells2Pixels](https://cells2pixels.github.io/)**, which extends neural cellular automata to
  produce high-resolution images and textures from a coarse cell grid.
- The article's later sections: rotating the perception filters so a trained rule can be run at
  any angle, and growing on textures instead of images.

The demo is `dev_docs/agents/transcripts/growing_nca.yaml`. It has four boxes: the strip above, a
live CPU version (launch it), a renderer, and the GPU box. See [Spatial artificial
life](alife_examples.md) for cellular automata whose rules are written by hand.
