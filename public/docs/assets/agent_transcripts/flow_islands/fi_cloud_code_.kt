prop<List<Vec3>>(moonTarget)
prop<List<Vec3>>(moonSamples)

// Two half-moons that interlock without touching: a target in two separate pieces.
val rnd = java.util.Random(3)
fun moon(i: Int, upper: Boolean): Vec3 {
    val a = PI * i / 99.0
    val jitter = vec(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, 0.0) * 14.0
    return if (upper) vec(100 * cos(a), 100 * sin(a), 0.0) + jitter
    else vec(100 - 100 * cos(a), 40 - 100 * sin(a), 0.0) + jitter
}
moonTarget = [true, false].flatMap { upper -> (0 until 100).map { moon(it, upper) } }

// From a Gaussian to the moons, then 200 points that were never in the training data.
val field = flowMatch(moonTarget, options = FlowMatchOptions(steps = 6000, seed = 11))
moonSamples = field.sample(200, seed = 12)
println("${moonSamples.size} generated, chamfer to target ${chamferDistance(moonSamples, moonTarget).f2}")
