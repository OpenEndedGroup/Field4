# Growing neural cellular automata

A cellular automaton whose rule was learned instead of written. Every cell of a grid holds 16
numbers and runs the same small neural network on itself and its eight neighbours. From a single
seed cell the grid grows a picture, keeps it, and grows it back when part of it is cut away.

This is the model from Mordvintsev, Randazzo, Niklasson and Levin,
[*Growing Neural Cellular Automata*](https://distill.pub/2020/growing-ca) (Distill, 2020, CC-BY 4.0).
Field ships two trained rules, `GrowingNca.pretrained("flower")` and
`GrowingNca.pretrained("fish")`. Both run on the CPU in a box and on the GPU as a compute shader.

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
  only in a random half of the cells each step.
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

```diffstatic {data-lang=kotlin}
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/gpu_window.png" capture="native-window" max-width=1600 wait="6" native-wait-frames="30" embed=true hidden=true timeout="600" duration="0" name="growing-nca-gpu" alt="The Vulkan window: a pink six-petalled flower with a yellow centre and a green stem, grown by the compute shader on a white 96 by 96 grid between grey margins" macro-file="../../dev_docs/agents/transcripts/growing_nca.yaml" capture-box="nca_renderer"}
// Built and launched from dev_docs/agents/transcripts/growing_nca.yaml
// (the CPU strip, a renderer, and the GPU box beneath it).
```

The compute shader is in the box's `compute` property. Open it with the property switcher to see
the network that `MlpWeights.glsl` wrote, `ncaNet`, and the perception loop around it.
`stepsPerSecond` keeps the speed watchable, because the window can draw thousands of frames a
second.

## Training a rule

The training script uses PyTorch to backpropagate through 64 to 96 steps of the whole grid, as in
the article. Field's own learning code (`flowmatching.kt`, `afield.math.autodiff`) does not yet
train through the whole grid.

```diffstatic {data-lang=bash}
uv run --project . --group pen python tools/nca/train_growing_nca.py --target flower \
    --steps 5000 --out flower.json
```

The script follows the article. Each batch draws 8 grids from a pool of 1,024. The worst one is
replaced by a fresh seed and the best three are damaged with a random disc. The batch runs 64 to 96
steps, and the gradient is normalised per parameter before each Adam step. The learning rate is
2 × 10⁻³, dropping tenfold at step 2,000. The flower is drawn by the script itself (discs for
petals, centre and stem), 40 × 40 on a 64 × 64 grid:

![The training target: a pink six-petalled flower with a yellow centre and a short green stem, 64 pixels square](assets/growing_nca/flower_target.png){ width="320" style="image-rendering: pixelated" }

5,000 steps took about 17 minutes on an M-series GPU (MPS). The loss reached 6 × 10⁻⁵ near the end.
Copy the JSON to `src/jvmMain/resources/afield/alife/nca_<name>.json` for
`GrowingNca.pretrained(name)`, or load it from anywhere with `GrowingNca.load(file)`.
`GrowingNcaTest` checks the shipped rule on the CPU. From one seed, 300 steps bring the error to
2 × 10⁻⁵, where an empty grid scores 0.054. After a cut 20 cells wide, 300 more steps bring it to
1.7 × 10⁻⁴.

## Turning the rule without retraining

Every cell senses its neighbours through two fixed filters, Sobel-x and Sobel-y, which measure how
each channel changes across and down the grid. Turn both filters by an angle θ,

$$
K_x = \cos\theta \, S_x - \sin\theta \, S_y, \qquad K_y = \sin\theta \, S_x + \cos\theta \, S_y,
$$

and the cells perceive the world turned by θ. The article's fourth experiment shows that the
trained rule then grows its picture turned by θ, without retraining. `rotation` sets θ in radians,
and a positive angle turns the picture anticlockwise on screen. `GrowingNca.rotateTarget` turns
the target the same way, so there is something to score the result against. This box grows the
flower at eight angles. Under each flower it shows the error against the turned target, then, in
brackets, the error the upright flower would have against that same target:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/turned_sensors.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="180°" timeout="300" padding="16" embed=true name="growing-nca-turned" alt="Eight flowers in a row, grown at 0, 15, 30, 45, 60, 90, 135 and 180 degrees. Each is the same six-petalled pink flower with a yellow centre, its green stem pointing straight down, then down and to the right, then right at 90 degrees and straight up at 180. Under each the error against the turned target, from 1.9e-05 upright to between 5.5e-04 and 9.7e-04 at the angles that are not quarter turns, and 2.0e-05 at 90 and 180 degrees, with the much larger error of an upright flower in brackets"}
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

At 90° and 180° the error is the upright flower's, 2.0 × 10⁻⁵, because a quarter turn of the
Sobel pair is the same pair relabelled. At the other angles the flower is plainly there and plainly
turned, with an error between 5.5 × 10⁻⁴ and 9.7 × 10⁻⁴. That is 30 to 50 times the quarter-turn
error, but 15 to 35 times smaller than the error of leaving the flower upright. Some of it is the
target itself, which `rotateTarget` resamples between cells. The article shows this experiment as
a video and reports no error, noting only that a rotation on a grid of cells is not exact.

A turned flower also keeps less well. In a 4,000-step run at 30° the error rises from 7.5 × 10⁻⁴ at
step 300 to 1.7 × 10⁻³ by step 2,000 and stays there. At 45° it reaches 2.8 × 10⁻³ by step 2,000,
and a bud starts at the edge around step 3,600. Upright, the error stays at 2 × 10⁻⁵ for all 4,000
steps.

### Turning a flower that has already grown

Turning the sensors of a grown flower does not turn the flower. The cells' hidden channels still
hold the upright pattern, which now contradicts what they perceive. This box grows the flower
upright, turns its sensors 45°, then erases everything but the 3 × 3 cells at the seed:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/turn_grown.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="regrown" timeout="300" padding="16" embed=true name="growing-nca-turn-grown" alt="Five snapshots: the upright flower at step 300; at step 400, 100 steps after its sensors were turned 45 degrees, a ragged flower with its stem moved round to the lower right; at step 700 the same ragged flower with a second flower growing off the top edge of the grid; at step 700 the grid erased except for a yellow 3 by 3 seed; and at step 1100 a whole flower regrown from that seed, turned 45 degrees with its stem at the lower right"}
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

A hundred steps after the turn the flower is ragged. Three hundred steps later it has sent a
second flower off the top of the grid, and its error against either target is about 3 × 10⁻². The
seed left behind grows a turned flower with an error of 2.7 × 10⁻³ against the turned target,
three times that of a flower grown turned from a fresh seed, because those nine cells still carry
some of the upright pattern.

### An angle for every cell

*Self-Organising Textures* (Niklasson, Mordvintsev, Randazzo and Levin, Distill 2021,
[doi:10.23915/distill.00027.003](https://doi.org/10.23915/distill.00027.003), free at
[distill.pub](https://distill.pub/selforg/2021/textures/)) turns the same filters for its texture
rules and notes that the turn "can even be done on a per-cell level". `rotationAt` takes a function
of the cell's position and turns each cell's sensors by its own angle. This box tries four angle
fields with the upright flower rule:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/angle_fields.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="down points outward" timeout="300" padding="16" embed=true name="growing-nca-angle-fields" alt="Four grids after 1,000 steps. Left 0 degrees, right 90 degrees: a column of flowers stacked the full height of the grid along the middle, with stems pointing right. Minus 45 to 45 degrees across: one whole flower, its stem turned slightly to the left. 90 degrees per 20 cells outward: a ragged flower with its stem at the lower right, and a second flower growing in from the left edge. Down pointing outward: a cluster of three flowers grown back to back, their stems pointing away from the centre"}
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

A field that changes gently across the flower grows one flower: 413 live cells for the twist, as
many as the upright flower has, with the stem following the angle where it grows. The swirl, which
turns 90° every 20 cells, is too steep. Its flower is ragged, and a second flower grows in from the
edge (682 live cells). Two halves that disagree by 90° never settle.
Each half grows the half-flower its frame asks for, the join keeps seeding new ones, and after
1,000 steps a column of 1,309 live cells fills the grid from top to bottom. When "down" points away
from the centre, the stem wants to grow in every direction at once, and the cluster of flowers
grown back to back reaches 1,087 cells.

### On the GPU

The compute shader takes the angle as its last push constant, `angle`, and turns the Sobel pair the
same way. The GPU box above already binds it as `m.angle = { angle.toFloat() }`, so setting `angle`
in that box turns the flower in the window. The GPU capture in [Drawing a new
target](#drawing-a-new-target) below runs a rule turned 30° and reads its grid back to score it.

## A damage sweep

The article cuts its flowers in a few ways: a half removed, or a square from the middle. This box
makes thirteen cuts in a grown flower: discs of four sizes through the top, two through the centre,
a centred square, the stem, each half, and everything except the 3 × 3 cells at the seed. It
follows each cut for 1,600 steps, four times over with different random update masks. A run ends
in one of four ways:

- **healed:** the error is back under 10⁻⁴, near the uncut flower's 2 × 10⁻⁵;
- **moved:** a whole flower regrew a few cells away. The error is under 10⁻⁴ against the target
  shifted by up to four cells, and the box prints the shift;
- **second growth:** cells have grown well away from the picture, usually a second flower;
- **scarred:** a flower is back, but its error stays above 10⁻⁴ even allowing for a shift.

`damage { x, y -> ... }` erases every cell where its function is true, which covers the half-planes,
the square and the stem; `damage(x, y, radius)` cuts the discs. `liveCells()` and `strayAlpha(target)`
are what the box uses to tell the outcomes apart. The 52 runs are independent, so the box runs them
in parallel:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/damage_sweep.png" capture="box" scale="2" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="only the seed left:" timeout="600" padding="16" embed=true name="growing-nca-damage-sweep" alt="Thirteen pairs of pictures in three rows, each pair a flower just after its cut and the same flower 1,600 steps later, captioned with how its four runs ended. The small discs, the stem, the left, right and top halves and the seed alone healed in every run or nearly so; the larger discs through the top and the centre are scarred, moved, or grow a second flower. Below them a chart of error against steps since the cut, both axes logarithmic, for six of the cuts: the small disc and the stem fall fastest, to 2e-05 within 200 steps; the seed alone takes about 800 steps and the bottom half 1,600; the disc of radius 12 through the top levels off near 8e-04; and the disc of radius 12 through the centre falls to 1e-02 at 100 steps and then rises again"}
import afield.ui.output.FieldMarkdown
import afield.ui.visualization.ScaleKind
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

Nothing died in this sweep. What decides the outcome is how much of the flower is left around the
hole:

- **Small cuts heal quickly.** The disc of radius 4 and the stem are back to the uncut error within
  200 steps in every run. The disc of radius 8 through the top heals in three runs of four.
- **Every half-plane heals**: left, right and top in every run, the bottom in three of four. The
  square and the half-planes are shapes the rule never saw in training, which cut only discs. The
  article found the same of its damage-trained models, which also repaired rectangular cuts.
- **Erasing all but the seed heals in every run.** It takes about 800 steps, because the whole
  flower grows again.
- **A large hole is harder to repair than no flower at all.** The discs of radius 12 and 16 through
  the top heal in none of these runs. The ring of old cells left round the hole still holds hidden
  state, and it steers the regrowth. Most such runs stay scarred near 7 × 10⁻⁴ (radius 12) or
  5 × 10⁻³ (radius 16), and some grow a second flower. The radius-12 cut is the one in the strip
  at the top of the page. A longer sweep (six seeds, cuts at eight times between steps 50 and
  2,000, 48 runs) healed it 15 times, left it scarred 20 times and grew a second flower 13 times.
  When the cut was made did not change those odds; the random update mask did.
- **Losing the centre moves the flower.** After the disc of radius 12 through the centre, no run
  healed where it was. Two regrew a whole flower 2–3 cells higher, and two grew a second flower.
  Plain error calls the moved flower a failure, at 10⁻²; scored against the shifted target it is
  as good as the uncut one.

## Drawing a new target

The flower was drawn by the training script itself. For a rule of your own, draw the picture in a
box with FLines, in the 40 × 40 square the trainer pads to 64 × 64. Pixels are the units, y points
down, and each filled line is painted in its own `color`. `GrowingNca.targetFromFLines` rasterises
them, antialiased, into the premultiplied RGBA the trainer expects, and `writeTarget` saves that as
JSON. This box draws a fish:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/fish_target.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="wrote" timeout="300" padding="16" embed=true name="growing-nca-fish-target" alt="A fish drawn in flat colours on white, 40 pixels square shown large: an orange body facing right with a round black eye near the nose, a blue triangular fin on its back and a blue forked tail on the left"}
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

The trainer reads the file with `--target-file`. The box prints where it wrote it, and the learning
rate drop moves with `--lr-drop`:

```diffstatic {data-lang=bash}
uv run --project . --group pen python tools/nca/train_growing_nca.py \
    --target-file <the path the box printed> --steps 2000 --lr-drop 1200 --out fish.json
```

The fish trained for 2,000 steps rather than the flower's 5,000, with the learning rate dropping at
step 1,200. That took 7.6 minutes on an M-series GPU (MPS), and the batch loss ended at 1.3 × 10⁻³
against the flower's 6 × 10⁻⁵. The rule ships as `GrowingNca.pretrained("fish")`. Here it is on the
CPU, growing, cut and regrowing, as the flower does at the top of the page:

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/fish_strip.png" capture="box" open-outputs="true" wait-output=true wait-output-kind="all" wait-output-contains="step 600" timeout="300" padding="16" embed=true name="growing-nca-fish-strip" alt="Seven snapshots of the fish: a speckled orange blob at step 30, the whole fish at steps 100 and 300, the fish with a disc cut out of its back leaving the tail and the lower curve of the body, a speckled body regrowing at step 340, and at steps 400 and 600 the whole fish again with its eye back"}
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

The fish's error is 1.2 × 10⁻² at step 30, 5 × 10⁻⁴ at step 100 and 2 × 10⁻⁴ by step 300. The cut
takes it to 2.2 × 10⁻². A hundred steps later the whole fish is back, eye included, at
1.9 × 10⁻³, and it holds near 2 × 10⁻³: ten times its uncut error, with the eye a little blurred.
Two thousand training steps were enough for a fish that grows and grows back.

On the GPU the fish runs in the same box as the flower, with three changes: the rule's name, the
angle (30° here), and a readback. Every 600 steps, just before the box's automatic cut, it copies
grid 0 out of the cell buffer into the CPU grid and prints the error against the turned fish:

```diffstatic {data-lang=kotlin}
val nca = GrowingNca.pretrained("fish", size = 96)
// ...
var angle = Math.toRadians(30.0)
// what the grid should hold: the fish, turned by the same angle about the seed
val turned = GrowingNca.rotateTarget(GrowingNca.pretrainedTarget("fish", size = n), angle, size = n)
// ... and in the `forward` compute task, after forward.compute(inside, cmd, push):
if (step % 600 == 399) {
    cells.read { get(nca.state) }
    println("step $step: error against the turned fish ${"%.1e".format(nca.loss(turned))}")
}
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/growing_nca/gpu_fish.png" capture="native-window" max-width=1600 wait="6" native-wait-frames="30" embed=true hidden=true timeout="600" duration="0" name="growing-nca-gpu-fish" alt="The second Vulkan window: the orange fish with its blue tail and fin and black eye, grown by the compute shader on a white 96 by 96 grid and turned 30 degrees anticlockwise, so that its nose points up and to the right" macro-file="../../dev_docs/agents/transcripts/growing_nca.yaml" capture-box="nca_renderer_fish"}
// Built and launched from dev_docs/agents/transcripts/growing_nca.yaml
// ("renderer (fish)" and the GPU box beneath it).
```

The readback prints 2.4 × 10⁻⁴ at step 399, before the first cut. After each cut the turned fish
grows back a little less exactly: 5.2 × 10⁻⁴ at step 999, then 1.1 × 10⁻³, 1.9 × 10⁻³ and
2.9 × 10⁻³ by step 2,799.

## Further work

- **[Cells2Pixels](https://cells2pixels.github.io/)**, which extends neural cellular automata to
  produce high-resolution images and textures from a coarse cell grid.
- **Textures.** *Self-Organising Textures* trains the same kind of rule against the statistics of
  a texture image (features of a pretrained image network) instead of a fixed picture. The
  trainer's loss would change; the CPU and GPU runners would not.
- **Training for turning.** Off-axis flowers drift and eventually bud because training only ever
  saw upright perception. Training with a random sensor angle per batch, against the target turned
  to match, should make every angle as good as a quarter turn.
- **A target from the drawing layer.** `targetFromFLines` takes any FLines, so strokes drawn on the
  canvas with ++alt+d++ and read back through `notation.strokes` could be the next target, with no
  code to write. Likewise an angle field for `rotationAt` drawn as arrows.
- **The sweep on the GPU.** Every damage run is independent, so the 52 runs could be 52 grids side
  by side in one compute dispatch.

Open the [Growing NCA starting point](agent_transcripts.md#growing_nca) to try these boxes: the
strip at the top, a live CPU version (launch it), the turning, angle-field, damage and fish boxes,
and two renderers, each with a GPU box beneath it. See [Spatial artificial life](alife_examples.md)
for cellular automata whose rules are written by hand.
