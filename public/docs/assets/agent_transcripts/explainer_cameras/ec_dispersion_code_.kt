import afield.simulation.optics.*

// White light is every colour at once, and glass bends blue more than red. Through a prism the
// colours fan out; through a lens each colour comes to its own focus.
val cyan = vec(0.4, 0.9, 1.0, 1.0)
var spread = 2.0
var angle = 50.0
val waves = listOf(410.0, 450.0, 490.0, 530.0, 570.0, 610.0, 650.0, 690.0)

fun index(nm: Double) = Refraction.cauchyIndex(nm, b = 4200.0 * spread)
fun colour(nm: Double) = Refraction.wavelengthColor(nm).let { vec(it[0], it[1], it[2], 0.9) }

// Distance along the ray (px, py) + t (dx, dy) to the segment a-b, or null.
fun hit(px: Double, py: Double, dx: Double, dy: Double, a: DoubleArray, b: DoubleArray): Double? {
    val ex = b[0] - a[0]
    val ey = b[1] - a[1]
    val det = dx * -ey + ex * dy
    if (abs(det) < 1e-12) return null
    val t = ((a[0] - px) * -ey + ex * (a[1] - py)) / det
    val u = (dx * (a[1] - py) - dy * (a[0] - px)) / det
    return if (t > 1e-6 && u in 0.0..1.0) t else null
}

fun prism() {
    val a = doubleArrayOf(320.0, 50.0)
    val b = doubleArrayOf(190.0, 275.0)
    val c = doubleArrayOf(450.0, 275.0)
    val parts = mutableListOf(
        fline {
            moveTo(a[0], a[1])
            lineTo(b[0], b[1])
            lineTo(c[0], c[1])
            close()
            filled = true
            color = vec(0.4, 0.9, 1.0, 0.15)
        },
    )
    val phi = (30.0 - angle) * PI / 180
    val dx = cos(phi)
    val dy = sin(phi)
    val px = (a[0] + b[0]) / 2
    val py = (a[1] + b[1]) / 2
    val from = frameExit(px, py, -dx, -dy, 640.0, 320.0)
    parts += fline {
        moveTo(from[0], from[1])
        lineTo(px, py)
        color = vec(1.0, 1.0, 1.0, 1.0)
        thickness = 3.0
    }
    for (nm in waves) {
        val n = index(nm)
        val inside = Refraction.refract(dx, dy, 0.866, 0.5, 1.0, n) ?: continue
        val faces = listOf(a to c, b to c)
        val (t, face) =
            faces
                .mapNotNull { (p, q) -> hit(px, py, inside[0], inside[1], p, q)?.let { it to (p to q) } }
                .minByOrNull { it.first } ?: continue
        val qx = px + t * inside[0]
        val qy = py + t * inside[1]
        val ex = face.second[0] - face.first[0]
        val ey = face.second[1] - face.first[1]
        val len = sqrt(ex * ex + ey * ey)
        val out = Refraction.refract(inside[0], inside[1], ey / len, -ex / len, n, 1.0)
        parts += fline {
            moveTo(px, py)
            lineTo(qx, qy)
            if (out != null) frameExit(qx, qy, out[0], out[1], 640.0, 320.0).let { lineTo(it[0], it[1]) }
            color = colour(nm)
            thickness = 1.5
        }
    }
    showPlane(
        parts,
        channel = "prism",
        width = 600,
        height = 300,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 320.0),
        interactive = false,
    )
}

fun lens() {
    val parts = mutableListOf(
        fline {
            moveTo(160, 20)
            lineTo(160, 220)
            color = vec(0.4, 0.9, 1.0, 0.5)
            thickness = 6.0
        },
        fline {
            moveTo(0, 120)
            lineTo(640, 120)
            color = vec(1.0, 1.0, 1.0, 0.35)
            thickness = 1.0
        },
    )
    val green = index(550.0)
    for (nm in listOf(650.0, 550.0, 450.0)) {
        val focal = 300.0 * (green - 1) / (index(nm) - 1)
        for (h in listOf(-90.0, -60.0, 60.0, 90.0)) parts += fline {
            moveTo(0.0, 120 + h)
            lineTo(160.0, 120 + h)
            frameExit(160.0, 120 + h, 1.0, -h / focal, 640.0, 240.0).let { lineTo(it[0], it[1]) }
            color = colour(nm)
            thickness = 1.3
        }
    }
    showPlane(
        parts,
        channel = "lens",
        width = 600,
        height = 225,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 240.0),
        interactive = false,
    )
}

fun draw() {
    prism()
    lens()
}

draw()
controlGroup {
    slider("dispersion", 0.0, 4.0, step = 0.05, initialValue = 2.0) {
        spread = it
        draw()
    }
    slider("angle", 20.0, 80.0, step = 0.5, initialValue = 50.0) {
        angle = it
        draw()
    }
}
