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
