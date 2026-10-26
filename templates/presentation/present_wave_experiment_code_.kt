val frequency = 1.0
val points = (0..160).joinToString(" ") { i ->
    val x = i * 4
    val y = 110 - 65 * kotlin.math.sin(i * 0.08 * frequency)
    "$x,$y"
}
display("wave", """<div style="background:#000;padding:20px"><svg viewBox="0 0 640 220" width="640" height="220"><polyline points="$points" fill="none" stroke="white" stroke-width="3"/></svg></div>""")
displayOpen("wave")
