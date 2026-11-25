/*
A tour of 10 cities as a permutation: variable k is the city visited k-th, all different, and
consecutive stops cost their distance. Annealed Gibbs, one sweep per frame; city 0 starts the tour.
*/
val rnd = kotlin.random.Random(3)
val n = 10
val city = List(n) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
val tour = DiscreteEnergy(n, n)
tour.allDifferent((0 until n).toList(), weight = 2.0 * 500)
for (k in 0 until n) tour.pairCost(k, (k + 1) % n) { x, y -> city[x].distance(city[y]).toDouble() }
tour.fix(0, 0)

val s = EnergyState(tour).reset(rnd)
val gibbs = GibbsAnnealing(tour, sweeps = 400, startTemperature = 500.0, endTemperature = 5.0, seed = 3)
fun draw() {
    val path = fline {
        moveTo(city[s.assignment[0]].x, city[s.assignment[0]].y)
        for (k in 1..n) lineTo(city[s.assignment[k % n]].x, city[s.assignment[k % n]].y)
        color = vec(0.35, 0.75, 1.0, 1.0)
        thickness = 2.0
    }
    val stops = fline {
        for (c in city) {
            moveTo(c.x - 4, c.y - 4)
            lineTo(c.x + 4, c.y - 4)
            lineTo(c.x + 4, c.y + 4)
            lineTo(c.x - 4, c.y + 4)
            close()
        }
        filled = true
        stroked = false
        color = vec(1.0, 0.85, 0.3, 1.0)
    }
    lines(path, stops, w = 440.0, h = 340.0, fitToSize = true)
    val visits = s.assignment.toSet().size
    display(
        "tour",
        "sweep ${gibbs.sweep}: $visits of $n cities visited, length ${(0 until n).sumOf { city[s.assignment[it]].distance(city[s.assignment[(it + 1) % n]]).toDouble() }.f0}",
    )
}
frames {
    while (gibbs.step(s)) {
        if (gibbs.sweep % 4 == 0) draw()
        frame()
    }
    GreedyMoves(tour).settle(s)
    draw()
}
