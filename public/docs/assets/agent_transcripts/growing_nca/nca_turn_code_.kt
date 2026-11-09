import java.util.Base64

/*
Grow the flower upright, then turn its sensors 45° and keep stepping. Last, erase everything but
the 3 x 3 cells at the seed and let that grow again with the sensors still turned.
*/
val target = GrowingNca.pretrainedTarget("flower")
val turned = GrowingNca.rotateTarget(target, Math.toRadians(45.0))
val nca = GrowingNca.pretrained("flower")
val parts = mutableListOf<String>()

fun snap(label: String) {
    val png = Base64.getEncoder().encodeToString(nca.png(scale = 2))
    val upright = "%.1e".format(nca.loss(target))
    val at45 = "%.1e".format(nca.loss(turned))
    println("$label (step ${nca.steps}): error upright $upright, turned 45° $at45")
    parts += "<img src='data:image/png;base64,$png' width='128'><br>**$label**<br>step ${nca.steps}"
}

nca.step(300)
snap("grown upright")
nca.rotation = Math.toRadians(45.0)
nca.step(100)
snap("turned 45°")
nca.step(300)
snap("turned 45°")
nca.damage { x, y -> !(x in 31..33 && y in 31..33) }
snap("only the seed left")
nca.step(400)
snap("regrown")
showRow("main", parts, width = 720)
