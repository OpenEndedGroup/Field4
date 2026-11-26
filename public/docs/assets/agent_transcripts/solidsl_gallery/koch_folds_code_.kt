import afield.lang.solidsl.*

/*
---- the Koch snowflake as a fold stack -----------------------------------------------
After Matt Keeter's snowflake SDF and Alexa Schor's fold version of his hexify step. Each
level is four folds around one third-size copy, so the expression grows by a constant
per level where six explicit copies grow it six-fold.
*/
fun isolines(level: Int): List<FLine> {
    val d = kochSnowflake(level).toField2()
    return [-0.12, -0.06, 0.0, 0.06, 0.12, 0.18].flatMap { iso ->
        generateIsoContours({ x, y -> d.at(x, y) }, -1.4, 1.4, -1.4, 1.4, 280, 280, iso).map { c ->
            fline {
                moveTo(c[0].x * 100, -c[0].y * 100)
                c.drop(1).forEach { lineTo(it.x * 100, -it.y * 100) }
                thickness = if (iso == 0.0) 2.0 else 1.0
                color =
                    if (iso < 0) vec(0.95, 0.55, 0.3, 1.0)
                    else if (iso == 0.0) vec(1, 1, 1, 1) else vec(0.4, 0.7, 1.0, 1.0)
            }
        }
    }
}

fun show(level: Int) {
    val fold = kochSnowflake(level)
    val naive = kochSnowflakeNaive(level)
    println(
        "level $level: fold ${fold.profileNodeCount()} nodes, ${fold.toGlsl2().length} GLSL chars; " +
            "naive ${naive.profileNodeCount()} nodes",
    )
    lines(*isolines(level).toTypedArray(), w = 420.0, h = 420.0, fitToSize = true)
}

show(4)
controlGroup("Koch snowflake") {
    slider("level", min = 1.0, max = 6.0, initialValue = 4.0) { show(it.toInt().coerceIn(1, 6)) }
}
