import afield.math.matrixprofile.*

// Every example has a sine bump at 100. Only the positive ones have a step at 400.
val rnd = java.util.Random(14)
fun example(withStep: Boolean) = 600 { i ->
    rnd.nextGaussian() * 0.3 +
        (if (i in 100 until 140) 3 * sin(2 * PI * (i - 100) / 40) else 0.0) +
        (if (withStep && i in 400 until 440) (if (i < 420) 3.0 else -3.0) else 0.0)
}
    .toDoubleArray()

val cp = contrastProfile([example(true), example(true)], [example(false), example(false)], m = 40)
val platos = cp.platos(2)
val plato = platos.first()

showSignals(
    "positives" to cp.positive.toList(),
    "contrast" to cp.profile,
    regions = platos.map { SignalRegion("plato", it.index, it.index + cp.m, "#ffe066") },
    title = "plato in example ${plato.example} at ${plato.offset}, contrast ${plato.contrast.f2}",
    channel = "main",
    normalize = true,
    width = 1000,
    height = 420,
    floatingLegend = true,
)
