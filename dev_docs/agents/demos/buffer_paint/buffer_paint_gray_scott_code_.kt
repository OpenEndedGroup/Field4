val n = 160
val u = FloatArray(n * n) { 1f }
val v = FloatArray(n * n)
val un = FloatArray(n * n)
val vn = FloatArray(n * n)

// The canvas is a view onto v, the simulation's own state: no copy is made.
val canvas = bufferCanvas(v, width = n, wrapX = true, wrapY = true)
canvas.zoom = 2
canvas.map = ValueMap.Linear(0.0, 0.5) // v lives in 0..~0.45; said so in the caption
canvas.paint(80.0, 80.0, radius = 6.0, value = 0.5f, hardness = 1.0)

val feed = 0.037f
val kill = 0.06f
fun step() {
    for (y in 0 until n) for (x in 0 until n) {
        val i = y * n + x
        val l = (x + n - 1) % n + y * n
        val r = (x + 1) % n + y * n
        val t = x + ((y + n - 1) % n) * n
        val b = x + ((y + 1) % n) * n
        val lu = u[l] + u[r] + u[t] + u[b] - 4 * u[i]
        val lv = v[l] + v[r] + v[t] + v[b] - 4 * v[i]
        val uvv = u[i] * v[i] * v[i]
        un[i] = u[i] + 0.2f * lu - uvv + feed * (1 - u[i])
        vn[i] = v[i] + 0.1f * lv + uvv - (feed + kill) * v[i]
    }
    un.copyInto(u)
    vn.copyInto(v)
}

/*
Edits scheduled against the running simulation, by step number. Each one lands in
empty space (u = 1, v = 0) or on pattern the seed has already grown, and the
reaction takes it from there.
*/
val edits = mapOf(
    150 to
        ("stroke" to
            {
                canvas.stroke(
                    [15.0 to 20.0, 60.0 to 35.0, 140.0 to 18.0],
                    radius = 3.0,
                    value = floatArrayOf(0.5f),
                )
            }),
    500 to ("clone" to { canvas.clone(CellRect(60, 60, 40, 40), 8, 112) }),
    850 to ("blur" to { canvas.blur(CellRect(60, 60, 40, 40), sigma = 4.0) }),
    1200 to ("motion blur" to { canvas.motionBlur(CellRect(0, 105, n, 50), length = 31, dx = 1.0, dy = 0.0) }),
    1550 to ("fill" to { canvas.fill(CellRect(104, 30, 12, 120), 0f) }),
)

// A small copy of the state just after each edit, viewed through its own BufferCanvas.
val strip = mutableListOf<String>()
frames {
    var s = 0
    var next = System.nanoTime()
    while (frame() && s < 2200) {
        if (System.nanoTime() < next) continue
        next += 30_000_000 // ~33 frames a second, 5 steps a frame
        repeat(5) {
            step()
            s++
            edits[s]?.let { (what, edit) ->
                edit()
                val after = bufferCanvas(v.copyOf(), width = n)
                after.map = canvas.map
                strip +=
                    "<div style='display:inline-block;margin:0 6px 6px 0'><div>step $s: $what</div>" +
                        "<img src='data:image/png;base64," +
                        java.util.Base64.getEncoder().encodeToString(after.png()) +
                        "' style='image-rendering:pixelated'/></div>"
                display("edits", strip.joinToString(""))
            }
        }
        display("state", canvas.toHTML() + "<pre>step $s</pre>")
    }
}
