import afield.stdlib.DeepSetsOptions
import afield.stdlib.InteractionExample
import afield.stdlib.trainEquivariant

prop<afield.stdlib.EquivariantWeights>(learnedRule)

val radius = 2f / 64f
val minSeparation = radius * 0.05f
val velocityScale = 0.4f
val random = java.util.Random(5)

// The rule being imitated, evaluated on random neighbourhoods. No simulation is needed
// to generate training data when you already have the law -- and having the law is what
// makes the result gradeable at all.
fun sample(): InteractionExample {
    val v = floatArrayOf(
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        (random.nextDouble() * 2 - 1).toFloat() * velocityScale)
    // Up to 70, not 14. The flock actually runs at about 57 neighbours per boid once
    // cohesion has gathered it, and a model trained only on sparse neighbourhoods is
    // extrapolating in deployment -- which matters enormously here because cohesion is
    // weighted 200x the *mean* offset, so a small error in the meaned branch becomes a
    // large force. Train on the density you will run at.
    val neighbours = (0..random.nextInt(70)).map {
        val r = radius * Math.sqrt(random.nextDouble()).toFloat()
        val a = random.nextDouble() * 2 * Math.PI
        floatArrayOf((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(),
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale)
    }
    var sx = 0f; var sy = 0f; var dvx = 0f; var dvy = 0f; var dx = 0f; var dy = 0f
    for (n in neighbours) {
        val d = Math.hypot(n[0].toDouble(), n[1].toDouble()).toFloat()
        // Separation is short range; cohesion and alignment see the whole radius. One
        // radius for all three cannot clump -- see BoidSettings.separationRadiusFraction.
        if (d <= radius * 0.25f) {
            val soft = maxOf(d, minSeparation)
            sx -= n[0] / (soft * soft); sy -= n[1] / (soft * soft)
        }
        dvx += n[2]; dvy += n[3]; dx += n[0]; dy += n[1]
    }
    val c = neighbours.size.toFloat()
    return InteractionExample(v, neighbours, floatArrayOf(
        0.02f * sx + 3.0f * dvx / c + 200f * dx / c,
        0.02f * sy + 3.0f * dvy / c + 200f * dy / c))
}

learnedRule = trainEquivariant(
    radius = radius, velocityScale = velocityScale,
    accelScale = 20f, sumScale = 8f, minSeparation = minSeparation,
    // The *equivariant* form: psi emits scalars that multiply the neighbour's own
    // direction and velocity difference, so rotating the flock rotates the force
    // exactly and a symmetric neighbourhood gives identically zero. The plain form has
    // neither property — it scores 0.50 on a forty-eight-neighbour ring where the true
    // force is zero, a fixed-direction residual that grows with crowding — and that is
    // what used to drift this flock out of its containment while the written one, under
    // the same containment, stayed put. It also fits better: 0.262 against 0.302.
    //
    // 8x8 with a 4-wide message rather than the library default of 16x16 and 6. Swept
    // against this exact rule: 160 multiply-adds a neighbour instead of 480, for a
    // relative error of 0.302 against 0.287 — three times cheaper for five percent
    // worse. The cost of this scene is *entirely* psi (the written flock runs at 116 fps
    // on the identical grid, the learned one at 17.6), so this is the only dial that
    // matters. Depth is what to keep: one hidden layer of 16 costs more than two of 8
    // and scores 0.350.
    options = DeepSetsOptions(
        steps = 4000, batch = 16, seed = 5,
        psiHidden = intArrayOf(8, 8), messageWidth = 4,
    )
) { List(16) { sample() } }

println("trained ${learnedRule.parameterCount} parameters: ${learnedRule}")
