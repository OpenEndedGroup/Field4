// One edge under each decay law, starting at weight 1, stepped at 60 frames a second for four seconds.
val exponential = DynamicEdgeList(2)
val linear = DynamicEdgeList(2)
exponential.reinforce(0, 1, 1.0, ttl = 1000f, maxWeight = 1.0)
linear.reinforce(0, 1, 1.0, ttl = 1000f, maxWeight = 1.0)

val expo = mutableListOf<Double>()
val lin = mutableListOf<Double>()
repeat(240) {
    expo += exponential.get(0, 1)?.weight ?: 0.0
    lin += linear.get(0, 1)?.weight ?: 0.0
    exponential.decayExponential(1f / 60f, 0.35)
    linear.decayLinear(1f / 60f, 0.35)
}

showSignals(
    "exponential, λ = 0.35" to expo,
    "linear, 0.35 / s" to lin,
    x = List(240) { it / 60.0 },
)
