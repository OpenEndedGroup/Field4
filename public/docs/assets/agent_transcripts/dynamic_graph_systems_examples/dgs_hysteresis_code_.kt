// A pair not yet joined must come within rEnter; a pair already joined only has to stay
// within rExit. The control arm is rExit == rEnter -- the identical system with the memory
// removed and nothing else changed.
fun walk(rExit: Double): Pair<Boolean, Boolean> {
    val state = DynamicPointState(mutableListOf(Vec3(0f, 0f, 0f), Vec3(0.05f, 0f, 0f)))
    val edges = DynamicEdgeList(2)
    val hash = Vec3SpatialHash(0.2)
    val system = Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.10, rExit = rExit, ttl = 0.2f, reinforce = 4.0, maxWeight = 8.0)
    repeat(30) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
    val formed = edges.get(0, 1) != null
    state.points[1] = Vec3(0.13f, 0f, 0f)
    repeat(60) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
    return formed to (edges.get(0, 1) != null)
}
println("rExit 0.16 (hysteresis):  formed at d=0.05 = %s, survives d=0.13 = %s".format(
    walk(0.16).first, walk(0.16).second))
println("rExit 0.10 (control arm): formed at d=0.05 = %s, survives d=0.13 = %s".format(
    walk(0.10).first, walk(0.10).second))

// On a wobbling cloud the difference is how much WORK the graph does to stay put.
fun flicker(rExit: Double): List<Any> {
    val n = 200
    val base = List(n) { i ->
        val a = i * 2.39996322972865332
        val r = 0.45 * Math.sqrt((i + 0.5) / n)
        Vec3((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f)
    }
    val state = DynamicPointState(base.map { Vec3(it) }.toMutableList())
    val edges = DynamicEdgeList(n)
    val hash = Vec3SpatialHash(0.06)
    val system = Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.055, rExit = rExit, ttl = 0.35f, reinforce = 3.0, maxWeight = 3.0)
    var appeared = 0
    var vanished = 0
    var present = HashSet<Pair<Int, Int>>()
    repeat(150) { frame ->
        for (i in 0 until n) {
            val phase = 0.31 * i + 0.16 * frame
            state.points[i] = Vec3(base[i]).add(
                vec(0.012 * Math.cos(phase), 0.012 * Math.sin(1.7 * phase), 0.0))
        }
        stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.9)
        val now = edges.values().map { it.i to it.j }.toHashSet()
        appeared += (now - present).size
        vanished += (present - now).size
        present = now
    }
    val window = dynamicGraphWindow(base, padFraction = 0.06)
    return listOf(appeared, vanished, present.size,
        dynamicGraphPanel(state.points, edges.values(), window, 460, 460, 3.0, "magma"))
}
val on = flicker(rExit = 0.075)
val off = flicker(rExit = 0.055)
println("hysteresis on:  ${on[0]} created, ${on[1]} lost, ${on[2]} standing after 150 frames")
println("hysteresis off: ${off[0]} created, ${off[1]} lost, ${off[2]} standing after 150 frames")
println("...the memoryless arm creates %.2fx as many and loses %.1fx as many".format(
    (off[0] as Int).toDouble() / (on[0] as Int), (off[1] as Int).toDouble() / (on[1] as Int)))
showImg(montage(listOf(
    "rExit 0.075: ${on[0]} in, ${on[1]} out" to on[3] as Img,
    "rExit = rEnter: ${off[0]} in, ${off[1]} out" to off[3] as Img
), columns = 2, cellSide = 460))
