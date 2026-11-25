/*
A tour of 10 cities as a permutation: variable k is the city visited k-th, all different, and
consecutive stops cost their distance. Simulated annealing with moves that keep it a permutation
(swap two stops, or reverse a run: 2-opt), one sweep per frame; city 0 starts the tour.
*/
val rnd = kotlin.random.Random(3)
val n = 10
val city = List(n) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
fun d(x: Int, y: Int) = city[x].distance(city[y]).toDouble()
val tour = DiscreteEnergy(n, n)
val stops = (0 until n).toList()
tour.allDifferent(stops, weight = 1000.0)
for (k in 0 until n) tour.pairCost(k, (k + 1) % n) { x, y -> d(x, y) }
tour.fix(0, 0)

val moves = [SwapValues(stops), ReverseSegment(stops)]
val s = EnergyState(tour)
for (k in 0 until n) s.assignment[k] = k
val annealing = MetropolisAnnealing(tour, moves, sweeps = 300, startTemperature = 200.0, endTemperature = 2.0, seed = 3)
fun draw() {
    val path = fline {
        moveTo(city[s.assignment[0]].x, city[s.assignment[0]].y)
        for (k in 1..n) lineTo(city[s.assignment[k % n]].x, city[s.assignment[k % n]].y)
        color = vec(0.35, 0.75, 1.0, 1.0)
        thickness = 2.0
    }
    val dots = fline {
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
    lines(path, dots, w = 440.0, h = 340.0, fitToSize = true)
    display(
        "tour",
        "sweep ${annealing.sweep}: length ${(0 until n).sumOf { d(s.assignment[it], s.assignment[(it + 1) % n]) }.f0}",
    )
}
frames {
    while (annealing.step(s)) {
        draw()
        frame()
    }
    GreedyMoves(tour, moves).settle(s)
    draw()
}
