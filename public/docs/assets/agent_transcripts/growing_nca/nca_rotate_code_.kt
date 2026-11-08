import java.util.Base64

/*
Grow the flower with its cells' two gradient sensors turned by each angle. The rule was trained
upright and is not retrained. Under each flower: its error against the target turned by the same
angle, then (in brackets) the error the upright flower would have against that turned target.
*/
val target = GrowingNca.pretrainedTarget("flower")
val upright = GrowingNca.pretrained("flower")
upright.step(300)

fun img(png: ByteArray) = "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' width='96'>"

val parts = mutableListOf<String>()
for (degrees in [0, 15, 30, 45, 60, 90, 135, 180]) {
    val nca = GrowingNca.pretrained("flower")
    nca.rotation = Math.toRadians(degrees.toDouble())
    nca.step(300)
    val turned = GrowingNca.rotateTarget(target, nca.rotation)
    val error = "%.1e".format(nca.loss(turned))
    val ifUpright = "%.1e".format(upright.loss(turned))
    println("$degrees°: error $error (upright flower: $ifUpright)")
    parts += "${img(nca.png(scale = 2))}<br>**$degrees°**<br>$error<br>($ifUpright)"
}
showRow("main", parts, width = 900)
