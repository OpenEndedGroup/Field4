import afield.math.distribution.*

fun show(label: String, e: Estimate, exact: Double) =
    println("%-24s %-28s exact %.5f   z = %+.2f".format(label, e, exact, e.zFrom(exact)))

val x = die(6)
val y = die(6)
show("E(5 + X + Y)", E(5 + x + y), 12.0)
show("P(X + Y < 10)", P(x + y lt 10), 30.0 / 36)
show("E(X - X)", E(x - x), 0.0)
show("Var(X - X')", Var(x - x.iid()), 35.0 / 6)

val birthday = draws(23, uniformInt(1, 365))
val noShared = (0 until 23).fold(1.0) { p, k -> p * (365.0 - k) / 365.0 }
show("P(shared birthday)", P(hasDuplicates(birthday)), 1 - noShared)

val u = uniform(-1, 1)
val v = uniform(-1, 1)
show("4 P(u² + v² < 1)", P(u * u + v * v lt 1) * 4, Math.PI)
show("median of exp(1)", quantile(exponential(1), 0.5), kotlin.math.ln(2.0))
