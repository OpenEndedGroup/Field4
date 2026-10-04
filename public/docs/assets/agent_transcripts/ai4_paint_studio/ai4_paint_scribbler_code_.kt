import afield.paint.*
import kotlin.random.Random

// A brush the painter does not have yet: a scribble, painted on the Studio's easel.
val studio = thisBox.inside.boxes.values.single { it.name == "Studio" }

fun inside(ring: List<List<Double>>, x: Double, y: Double) = ring.indices.count { i ->
    val a = ring[i]; val b = ring[(i + 1) % ring.size]
    (a[1] > y) != (b[1] > y) && x < a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0])
} % 2 == 1

fun ink(spec: String): Vec4 = if (spec.startsWith("#")) {
    val v = spec.drop(1).chunked(2).map { it.toInt(16) / 255.0 }
    rgb(v[0], v[1], v[2])
} else Tubes[spec].color

aiTool("scribble") {
    describe("Scribble inside a region: rows of looping, wandering line that fill the outline the way a quick hand " +
        "does. Dense scribbles read as tone, loose ones as texture; layer colours for depth.")
    effects(ToolEffect.CONTROL_RUNTIME)
    val outline by param<List<Double>>("the region, [x0, y0, x1, y1, ...] in canvas units")
    val color by param<String>("#rrggbb, or a tube name")
    val density by param<Double>("rows of loops per 100 units: 1 loose .. 8 dense", default = 3.0)
    val width by param<Double>("brush width", default = 5.0)
    action {
        val canvas = studio.properties["studio.canvas"] as PaintCanvas
        val ring = outline().chunked(2)
        val rnd = Random(color().hashCode() + outline().hashCode())
        val loop = 50.0 / density()
        val left = ring.minOf { it[0] }; val right = ring.maxOf { it[0] }
        val brush = canvas.brush(BrushKind.Round, width())
        var row = ring.minOf { it[1] }
        var dir = 1.0
        while (row <= ring.maxOf { it[1] }) {
            val line = FLine()
            var x = if (dir > 0) left else right
            var a = rnd.nextDouble(6.28)
            var r = loop          // the loop size wanders, and the hand drifts off its row
            var drift = 0.0
            var pen = false
            while (x in left..right) {
                r = (r + rnd.nextDouble(-0.08, 0.08) * loop).coerceIn(loop * 0.4, loop * 1.7)
                drift = (drift + rnd.nextDouble(-0.05, 0.05) * loop).coerceIn(-loop, loop)
                val px = x + cos(a) * r
                val py = row + drift + sin(a) * r * 0.8
                pen = if (inside(ring, px, py)) { if (pen) line.lineTo(px, py) else line.moveTo(px, py); true } else false
                x += dir * loop * rnd.nextDouble(0.06, 0.2)
                a += rnd.nextDouble(0.2, 0.55)
            }
            brush.load(ink(color()), hiding = 0.8, wipe = 0.6)
            canvas.stroke(line, brush, pressure = 0.4..0.8)
            row += loop * 1.1
            dir = -dir
        }
        (studio.properties["studio.refresh"] as () -> Unit)()
        "scribbled ${color()} over the region at density ${density()}"
    }
}
