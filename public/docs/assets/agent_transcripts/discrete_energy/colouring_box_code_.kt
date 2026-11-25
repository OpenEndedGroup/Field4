/*
Graph colouring: 40 random points, each joined to its three nearest neighbours, coloured with
four colours so that no edge joins two of the same. Mean-field descent, one step per frame,
then annealed Gibbs; the dots show their strongest colour.
*/
val rnd = kotlin.random.Random(5)
val pts = List(40) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
val edges = HashSet<Pair<Int, Int>>()
for (i in pts.indices) pts.indices
    .filter { it != i }
    .sortedBy { pts[it].distance(pts[i]) }
    .take(3)
    .forEach { edges += minOf(i, it) to maxOf(i, it) }

val colouring = DiscreteEnergy(pts.size, 4)
for ((a, b) in edges) colouring.notEqual(a, b)

val palette = [vec(0.95, 0.4, 0.35, 1.0), vec(0.35, 0.75, 1.0, 1.0), vec(0.98, 0.85, 0.3, 1.0), vec(0.5, 0.9, 0.5, 1.0)]
val s = EnergyState(colouring).reset(rnd)
fun draw(caption: String) {
    val wires = fline {
        for ((a, b) in edges) {
            moveTo(pts[a].x, pts[a].y)
            lineTo(pts[b].x, pts[b].y)
        }
        color = vec(0.6, 0.6, 0.6, 0.6)
        thickness = 1.0
    }
    val dots =
        pts.indices.map { i ->
            fline {
                val r = 3.0 + 5.0 * s.p[colouring.unit(i, s.assignment[i])]
                moveTo(pts[i].x - r, pts[i].y - r)
                lineTo(pts[i].x + r, pts[i].y - r)
                lineTo(pts[i].x + r, pts[i].y + r)
                lineTo(pts[i].x - r, pts[i].y + r)
                close()
                filled = true
                stroked = false
                color = palette[s.assignment[i]]
            }
        }
    lines(wires, *dots.toTypedArray(), w = 440.0, h = 340.0, fitToSize = true)
    display("conflicts", caption)
}

val descent = MeanFieldDescent(colouring)
val gibbs = GibbsAnnealing(colouring, sweeps = 100, startTemperature = 1.0, endTemperature = 0.05, seed = 2)
frames {
    var step = 0
    while (descent.step(s) > 1e-4f && step < 200) {
        step++
        draw("mean-field step $step: ${s.energy.toInt()} conflicts")
        frame()
    }
    while (s.energy > 0 && gibbs.step(s)) {
        s.beliefsFromAssignment()
        draw("Gibbs sweep ${gibbs.sweep}: ${s.energy.toInt()} conflicts")
        frame()
    }
    draw("${edges.size} edges, ${s.energy.toInt()} conflicts")
}
