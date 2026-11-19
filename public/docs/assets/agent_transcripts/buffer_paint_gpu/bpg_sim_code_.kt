import afield.media.image.*

var simModule = computeModuleHere {}
var sim = ComputeMesh()
simModule += "step" to sim
renderer += "buffer-paint-gs" to simModule

val W = 256
val H = 256

// Host-visible, one buffer each (not one per swapchain image): the CPU maps exactly the
// memory the shader reads.
var stateA = defaultBuffer { size = (W * H).vec2s }.buffer
var stateB = defaultBuffer { size = (W * H).vec2s }.buffer

// The canvas view of a mapped buffer: two floats per cell, wrapping like the shader's grid.
fun canvasOver(memory: java.nio.FloatBuffer) =
    bufferCanvas(memory, width = W, channels = 2, height = H, wrapX = true, wrapY = true)

// Both halves start as u = 1, v = 0 with one seed, because the shader reads either.
for (target in [stateA, stateB]) {
    target.mapAndWrite {
        val c = canvasOver(this)
        c.fill(c.bounds, floatArrayOf(1f, 0f))
        c.paint(128.0, 128.0, radius = 6.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0)
    }
}

/*
Editing the live state. An edit is queued; the loop below sets `hold`, which makes the
shader return before writing, waits until every frame that might still be writing has
finished, maps buffer A, runs the edits on a BufferCanvas over that mapping, copies A into
B, and lets the simulation go again. The next dispatch evolves from the edited cells.
*/
var hold = false
var heldAt = 0L
val pending = mutableListOf<BufferCanvas.() -> Unit>()
fun edit(op: BufferCanvas.() -> Unit) {
    pending += op
}

sim.state = { if (renderer.frameCounter % 2L == 0L) stateA else stateB }
sim.prevState = { if (renderer.frameCounter % 2L == 0L) stateB else stateA }
sim.w = { W }
sim.h = { H }
sim.run = { if (hold) 0 else 1 }
sim.feed = { 0.037f }
sim.kill = { 0.06f }
sim.x = W / 8
sim.y = H / 8
sim.z = 1

prop<FrameBufferGroup>(bpStateA) = stateA
prop<FrameBufferGroup>(bpStateB) = stateB
prop<Int>(bpWidth) = W
prop<Int>(bpHeight) = H

/*
The drawing layer. A frame on the canvas stands for the buffer, two world units per cell,
and a space maps it to cell coordinates. Gestures become edits.
*/
val pad = 2.0 * W
notation {
    frame("gpu buffer", frame.x + frame.w + 460, frame.y, pad, 2.0 * H)
}
val cells = notation.space("gpu buffer", x = 0.0..W.toDouble(), y = 0.0..H.toDouble())
fun cellRect(m: Mark): CellRect {
    val a = cells.toSpace(vec(m.bounds.x, m.bounds.y))
    val b = cells.toSpace(vec(m.bounds.x + m.bounds.w, m.bounds.y + m.bounds.h))
    return CellRect(a.x.toInt(), a.y.toInt(), maxOf(1, (b.x - a.x).toInt()), maxOf(1, (b.y - a.y).toInt()))
}
val log = mutableListOf<String>()
fun note(s: String) {
    log += s
    markdown("gestures", log.takeLast(8).joinToString("\n\n"))
}

// A freehand stroke paints: v = 0.25 under the brush, which the reaction grows.
notation.onStroke { s ->
    val path = s.points.map { cells.toSpace(it).let { p -> p.x.toDouble() to p.y.toDouble() } }
    edit { stroke(path, radius = 3.0, value = floatArrayOf(0.5f, 0.25f), opacity = 0.8) }
    note("stroke: painted ${path.size} points")
}
// A rectangle blurs what is under it, an ellipse erases it back to u = 1, v = 0.
val handled = mutableSetOf<String>()
notation.onChange { e ->
    for (m in e.added + e.updated) {
        if (m.id in handled || m.box != null) continue
        val r = cellRect(m)
        if (r.w < 3 || r.h < 3) continue
        when (m.kind) {
            MarkType.RECTANGLE -> {
                handled += m.id
                edit { blur(r, sigma = 3.0) }
                note("rectangle: blurred $r")
            }
            MarkType.ELLIPSE -> {
                handled += m.id
                edit { fill(r, floatArrayOf(1f, 0f)) }
                note("ellipse: erased $r")
            }
            else -> {}
        }
    }
}
// An arrow from inside a rectangle copies that rectangle to the arrow's end.
notation.onArrow { a ->
    val start = a.start ?: return@onArrow
    val end = a.end ?: return@onArrow
    val source = notation.marks.firstOrNull {
        it.kind == MarkType.RECTANGLE &&
            start.x in it.bounds.x..(it.bounds.x + it.bounds.w) &&
            start.y in it.bounds.y..(it.bounds.y + it.bounds.h)
    } ?: return@onArrow
    val r = cellRect(source)
    val to = cells.toSpace(end)
    edit { clone(r, to.x.toInt() - r.w / 2, to.y.toInt() - r.h / 2) }
    note("arrow: copied $r to (${to.x.toInt()}, ${to.y.toInt()})")
}

// Edits from code go through the same queue: two seeds that will meet.
edit { paint(60.0, 60.0, radius = 5.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0) }
edit { paint(200.0, 190.0, radius = 5.0, value = floatArrayOf(0.5f, 0.25f), hardness = 1.0) }

val inFlight = renderer.numImages + 2L
val shown = FloatArray(W * H * 2)
var nextShow = 0L
frames {
    while (frame()) {
        val f = renderer.frameCounter
        if (pending.isNotEmpty() && !hold) {
            hold = true
            heldAt = f
        }
        if (hold && f >= heldAt + inFlight) {
            val ops = pending.toList()
            pending.clear()
            stateA.mapAndWrite {
                val c = canvasOver(this)
                ops.forEach { c.it() }
                get(0, shown, 0, shown.size)
            }
            stateB.mapAndWrite { put(0, shown, 0, shown.size) }
            hold = false
        }
        if (!hold && f >= nextShow) {
            nextShow = f + 120
            // The half the last completed frame wrote; at worst a frame or two stale.
            val latest = if ((f - 1) % 2L == 0L) stateA else stateB
            latest.read { get(0, shown, 0, shown.size) }
            val view = bufferCanvas(shown, width = W, channels = 2)
            view.display = intArrayOf(1, 1, 1) // show v, the second float of each cell
            view.map = ValueMap.Linear(0.0, 0.5)
            val row60 = (48..72 step 4).joinToString(" ") { x -> "%.3f".format(view[x, 60, 1]) }
            display("state", view.toHTML() + "<pre>renderer frame $f\nv along row 60, x = 48..72 step 4: $row60</pre>")
        }
    }
}
