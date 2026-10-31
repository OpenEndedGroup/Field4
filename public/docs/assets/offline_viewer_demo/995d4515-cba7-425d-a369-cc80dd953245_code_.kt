// A flock in one dimension: n birds on a ring, each nudging toward the mean heading of its
// neighbours. Prints how aligned the flock is after each round.
val n = 24
val rnd = java.util.Random(4)
var heading = DoubleArray(n) { rnd.nextDouble() * 2 * Math.PI }
fun alignment(h: DoubleArray) = Math.hypot(h.sumOf { Math.cos(it) }, h.sumOf { Math.sin(it) }) / h.size
for (round in 0..6) {
    println("round $round: alignment ${alignment(heading).f3}")
    heading =
        DoubleArray(n) { i ->
            val l = heading[(i + n - 1) % n]
            val r = heading[(i + 1) % n]
            val me = heading[i]
            Math.atan2(Math.sin(l) + Math.sin(me) + Math.sin(r), Math.cos(l) + Math.cos(me) + Math.cos(r))
        }
}
