prop<afield.ai.learning.EquivariantWeights>(learnedRule)

val radius = 2f / 64f
val minSeparation = radius * 0.05f
val velocityScale = 0.4f
val random = java.util.Random(5)

/*
The rule being imitated, evaluated on random neighbourhoods. No simulation is needed
to generate training data when you already have the law -- and having the law is what
makes the result gradeable at all.
*/
fun sample(): InteractionExample {
    val v = floatArrayOf(
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
    )
    /*
    Up to 70 neighbours: the gathered flock runs at about 57 per boid, so train on the
    density you will run at. Cohesion weights the *mean* offset by 200, so a small error
    in the meaned branch becomes a large force.
    */
    val neighbours = (0..random.nextInt(70)).map {
        val r = radius * Math.sqrt(random.nextDouble()).toFloat()
        val a = random.nextDouble() * 2 * Math.PI
        floatArrayOf(
            (r * Math.cos(a)).toFloat(),
            (r * Math.sin(a)).toFloat(),
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        )
    }
    var sx = 0f
    var sy = 0f
    var dvx = 0f
    var dvy = 0f
    var dx = 0f
    var dy = 0f
    for (n in neighbours) {
        val d = Math.hypot(n[0].toDouble(), n[1].toDouble()).toFloat()
        // Separation is short range; cohesion and alignment see the whole radius. One
        // radius for all three cannot clump -- see BoidSettings.separationRadiusFraction.
        if (d <= radius * 0.25f) {
            val soft = maxOf(d, minSeparation)
            sx -= n[0] / (soft * soft)
            sy -= n[1] / (soft * soft)
        }
        dvx += n[2]
        dvy += n[3]
        dx += n[0]
        dy += n[1]
    }
    // Same weights as the written box: separation summed, alignment and cohesion meaned.
    val c = neighbours.size.toFloat()
    return InteractionExample(
        v,
        neighbours,
        floatArrayOf(
            0.02f * sx + 3.0f * dvx / c + 200f * dx / c,
            0.02f * sy + 3.0f * dvy / c + 200f * dy / c,
        ),
    )
}

// Published to the children as `learnedRule`; rerun this box to retrain.
learnedRule = trainEquivariant(
    radius = radius,
    velocityScale = velocityScale,
    accelScale = 20f,
    sumScale = 8f,
    minSeparation = minSeparation,
    /*
    The *equivariant* form: psi emits scalars that multiply the neighbour's own
    direction and velocity difference, so rotating the flock rotates the force
    exactly and a symmetric neighbourhood gives identically zero. Without that, a
    fixed-direction residual grows with crowding and drifts the flock out of its
    containment.

    psi runs once per neighbour per boid, so it is nearly all of this scene's GPU cost.
    8x8 with a 4-wide message (library default 16x16 and 6) is about three times
    cheaper for a slightly worse fit. Keep two layers: one layer of 16 costs more and
    fits worse.
    */
    options = DeepSetsOptions(
        steps = 4000,
        batch = 16,
        seed = 5,
        psiHidden = intArrayOf(8, 8),
        messageWidth = 4,
    ),
) {
        List(16) { sample() }
    }

println("trained ${learnedRule.parameterCount} parameters: ${learnedRule}")
