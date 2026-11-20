import afield.geometry.mesh.signedDistance2D
import afield.media.image.*

var simModule = computeModuleHere {}
var sim = ComputeMesh()
simModule += "step" to sim
renderer += "outline-field-gs" to simModule

val W = 256
val H = 256

var stateA = defaultBuffer { size = (W * H).vec2s }.buffer
var stateB = defaultBuffer { size = (W * H).vec2s }.buffer
// The field the shader reads: one float per cell, written by the CPU, never by the GPU.
var wall = defaultBuffer { size = (W * H).floats }.buffer

// The coded outline, in cell coordinates: a ring and a bar, one closed FLine each.
val ring = FLine().circle(96.0, 120.0, 70.0)
val bar = FLine().rect(150.0, 150.0, 90.0, 60.0)
val drawn = mutableListOf<FLine>()
var pendingWalls = false

// FLines -> exact signed distance -> one float per cell in the mapped wall buffer.
var field = listOf(ring, bar).signedDistance2D()
fun uploadWalls() {
    field = (listOf(ring, bar) + drawn).signedDistance2D()
    wall.mapAndWrite { bufferCanvas(this, width = W, height = H).sample(field, x0 = 0.0, y0 = 0.0, cell = 1.0) }
}
uploadWalls()

for (target in [stateA, stateB]) {
    target.mapAndWrite {
        val c = bufferCanvas(this, width = W, channels = 2, height = H)
        c.fill(c.bounds, floatArrayOf(1f, 0f))
        for ((x, y) in [96.0 to 120.0, 60.0 to 90.0, 200.0 to 180.0]) {
            c.paint(x, y, radius = 6.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0)
        }
    }
}

sim.state = { if (renderer.frameCounter % 2L == 0L) stateA else stateB }
sim.prevState = { if (renderer.frameCounter % 2L == 0L) stateB else stateA }
sim.wall = { wall }
sim.w = { W }
sim.h = { H }
sim.run = { 1 }
sim.feed = { 0.037f }
sim.kill = { 0.06f }
sim.x = W / 8
sim.y = H / 8
sim.z = 1

prop<FrameBufferGroup>(ofStateA) = stateA
prop<FrameBufferGroup>(ofStateB) = stateB
prop<FrameBufferGroup>(ofWall) = wall
prop<Int>(ofWidth) = W
prop<Int>(ofHeight) = H
// Other boxes add outlines from code through this, the same path a drawn stroke takes.
prop<(FLine) -> Unit>(addOutline) = { line: FLine ->
    drawn += line
    pendingWalls = true
}

// The drawing layer: a frame stands for the grid, two world units per cell.
notation {
    frame("walls", frame.x + frame.w + 460, frame.y, 2.0 * W, 2.0 * H)
}
val cells = notation.space("walls", x = 0.0..W.toDouble(), y = 0.0..H.toDouble())

// A freehand stroke becomes one more closed outline (signedDistance2D closes it).
notation.onStroke { s ->
    val pts = s.points.map { cells.toSpace(it) }
    if (pts.size < 3) return@onStroke
    val line = FLine()
    line.moveTo(pts[0].x, pts[0].y)
    for (p in pts.drop(1)) line.lineTo(p.x, p.y)
    drawn += line
    pendingWalls = true
}
// An ellipse clears the hand-drawn outlines.
val handled = mutableSetOf<String>()
notation.onChange { e ->
    for (m in e.added + e.updated) {
        if (m.id in handled || m.kind != MarkType.ELLIPSE) continue
        handled += m.id
        drawn.clear()
        pendingWalls = true
    }
}

val shownWall = FloatArray(W * H)
var nextShow = 0L
frames {
    while (frame()) {
        val f = renderer.frameCounter
        if (pendingWalls) {
            pendingWalls = false
            uploadWalls()
        }
        if (f >= nextShow) {
            nextShow = f + 240
            wall.read { get(0, shownWall, 0, shownWall.size) }
            var worst = 0.0
            for (y in 0 until H) for (x in 0 until W) {
                worst = maxOf(worst, Math.abs(shownWall[y * W + x] - field(x + 0.5, y + 0.5)))
            }
            val view = bufferCanvas(shownWall, width = W)
            view.map = ValueMap.Signed(40.0)
            display(
                "field",
                view.toHTML() +
                    "<pre>outlines: ${2 + drawn.size}, renderer frame $f\nmax |wall buffer - CPU field| = ${worst.f3} cells</pre>",
            )
        }
    }
}
