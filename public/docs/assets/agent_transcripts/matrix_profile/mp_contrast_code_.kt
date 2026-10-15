import afield.math.matrixprofile.*
import kotlin.math.*

// Every example has a sine bump at 100. Only the positive ones have a step at 400.
val rnd = java.util.Random(14)
fun example(withStep: Boolean) = 600 { i ->
    rnd.nextGaussian() * 0.3 +
        (if (i in 100 until 140) 3 * sin(2 * PI * (i - 100) / 40) else 0.0) +
        (if (withStep && i in 400 until 440) (if (i < 420) 3.0 else -3.0) else 0.0)
}.toDoubleArray()

val cp = contrastProfile(listOf(example(true), example(true)), listOf(example(false), example(false)), m = 40)
showSignals("positives" to cp.positive.toList(), "contrast" to cp.profile, channel = "main", normalize = true)

val plato = cp.platos(1).first()
markdown("info", "plato in example ${plato.example} at ${plato.offset}, contrast ${"%.2f".format(plato.contrast)}")
