/*
Drag the four yellow points. The curve, its control polygon and the length at its end
follow every move, because each drag calls draw() again with the point where it was put.
*/
val points = mutableListOf(vec(-60, 0, 0), vec(-30, 60, 30), vec(30, -50, -30), vec(60, 20, 0))

fun draw() {
    three(name = "curve", height = 480) {
        val curve =
            fline {
                moveTo(points[0])
                cubicTo(points[1], points[2], points[3])
            }
        val polygon =
            fline {
                moveTo(points[0])
                lineTo(points[1])
                lineTo(points[2])
                lineTo(points[3])
                color = vec(1.0, 1.0, 1.0, 0.3)
            }
        lines([curve, polygon])
        val end = points[3]
        label(vec(end.x, end.y + 14, end.z), "%.1f long".format(curve.length()), style = "tag")
        points.forEachIndexed { i, p ->
            handle("p$i", at = p, label = "p$i") { drag ->
                points[i] = drag.position
                draw()
            }
        }
    }
}
draw()
