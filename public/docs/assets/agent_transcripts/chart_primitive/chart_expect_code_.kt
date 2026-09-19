import kotlin.math.exp

// `Chart.expect` is `Table.expect`'s counterpart, and it DRAWS the claim rather than
// only checking it: the accepted range becomes a band, the bound it names is ruled,
// the data outside recolours, and the footnote counts them. An unbounded side draws no
// edge — that is the visual difference between "at most 1e-6" and "0 to 1e-6".
val iterations = (0..40).toList()
val residual = iterations.map { i ->
    val base = exp(-i * 0.22) * 1.5e-6
    if (i == 11 || i == 23) base + 2.4e-6 else base   // two real overshoots
}

val c = Chart(
    title = "residual per iteration",
    xLabel = "iteration",
    yLabel = "|r|",
    note = "Adam, lr 0.05",
)
    .line("residual", iterations, residual)
    .points("residual", iterations, residual)
    .expect(max = 1e-6, because = "converged below 1e-6")

showChart(c)

// And `deviations` hands the same list back, so a test asserts on the claim the figure
// makes rather than on its markup. This is the whole reason the primitive earns its keep.
facts(c.deviations.map { "iteration ${it.index}" to it.y })
