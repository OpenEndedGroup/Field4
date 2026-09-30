class Target(val originalFramePosition: Any)

fun drag(target: Target, tdx: Double, tdy: Double): Double {
    val odx = (target.originalFramePosition as List<Number>)[0].toDouble() + tdx.toDouble()
    val ody = (target.originalFramePosition as List<Number>)[1].toDouble() + tdy.toDouble()
    var total = 0.0
    visitAll(listOf(listOf(odx, ody))) { total += it }
    return total
}

fun main() {
    println(drag(Target(listOf(1, 2)), 0.5, 0.25))
}
