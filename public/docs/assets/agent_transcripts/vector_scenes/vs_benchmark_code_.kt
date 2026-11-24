val white = vec(0.94, 0.93, 0.89, 1.0)
val red = vec(0.82, 0.22, 0.2, 1.0)
val scenes = mapOf(
    "lighthouse" to
        vectorScene {
            segments(20)
            group({ translate(0.0, -6.0, 0.0) }) { box(260.0, 12.0, 200.0, vec(0.35, 0.55, 0.4, 1.0)) }
            cylinder(34.0, 180.0, white, topRadius = 22.0)
            group({ translate(0.0, 180.0, 0.0) }) { cone(24.0, 26.0, red) }
            group({ translate(80.0, 25.0, 30.0) }) { box(70.0, 50.0, 50.0, white, top = red) }
            group({ translate(-70.0, 0.0, 50.0) }) { hemisphere(22.0, vec(0.55, 0.52, 0.5, 1.0)) }
        },
    "village" to
        vectorScene {
            segments(16)
            group({ translate(0.0, -4.0, 0.0) }) { box(400.0, 8.0, 400.0, vec(0.4, 0.6, 0.4, 1.0)) }
            for (i in 0 until 4) for (j in 0 until 4) group(
                {
                    translate(i * 90.0 - 135.0, 20.0, j * 90.0 - 135.0)
                    rotateY(i * 0.7 + j)
                },
            ) {
                box(50.0, 40.0, 36.0, white, top = red)
                group({ translate(30.0, -20.0, 30.0) }) { cylinder(6.0, 50.0, vec(0.5, 0.35, 0.2, 1.0)) }
            }
        },
    "sticks" to
        vectorScene {
            for (k in 0 until 12) group(
                {
                    rotateY(k * 0.52)
                    rotateZ(0.3)
                },
            ) {
                line(
                    -120.0,
                    k * 6.0,
                    -30.0,
                    120.0,
                    k * 6.0,
                    30.0,
                    width = 12.0,
                    color = vec(0.3 + k * 0.05, 0.5, 0.8, 1.0),
                )
            }
        },
)
fun pct(s: VectorScene, mode: DepthOrder): String {
    var wrong = 0
    var covered = 0
    for (k in 0 until 8) {
        val (w, c) = s.wrongOrderShare(yaw = k * Math.PI / 4, pitch = -0.35, mode = mode, w = 200, h = 200)
        wrong += w
        covered += c
    }
    return "%.2f%%".format(100.0 * wrong / covered)
}
val rows =
    scenes.entries.joinToString("") { (name, s) ->
        "<tr><td>$name</td><td>${pct(s, DepthOrder.CENTROID)}</td><td>${pct(s, DepthOrder.PAIRWISE)}</td></tr>"
    }
display(
    "main",
    """<table style="font:14px sans-serif;border-collapse:collapse" cellpadding="6">
<tr><th align="left">scene</th><th>centroid sort</th><th>pairwise</th></tr>$rows</table>""",
)
