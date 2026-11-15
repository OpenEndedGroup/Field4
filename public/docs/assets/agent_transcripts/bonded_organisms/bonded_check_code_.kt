/*
Restarts the GPU world, lets it take exactly 600 steps, freezes it, reads the cells back
and compares them with a CPU BondedWorld built by the same factory and stepped 600 times.
Then lets it run on.
*/
val n = 600
val clock = bondedClock
clock.freezeAt = 0
frames {
    repeat(4) { frame() }
    bondedUpload()
    clock.freezeAt = n
    while (clock.step < n) frame()
    repeat(4) { frame() }
    val gpu = FloatArray(bonded.capacity * 16)
    bondedCells.read { get(gpu) }
    val cpu = bondedFactory()
    cpu.run(n)
    val half = clock.latestHalf * bonded.capacity
    var worst = 0.0
    var travel = 0.0
    val start = bondedFactory()
    for (i in 0 until cpu.count) {
        val o = (half + i) * 8
        worst = maxOf(worst, Math.hypot((gpu[o] - cpu.x[i]).toDouble(), (gpu[o + 1] - cpu.y[i]).toDouble()))
        travel = maxOf(travel, Math.hypot((cpu.x[i] - start.x[i]).toDouble(), (cpu.y[i] - start.y[i]).toDouble()))
    }
    println(
        "after $n steps: largest GPU-CPU distance $worst, largest distance a cell moved $travel, bonds ${cpu.bondCount()}",
    )
    clock.freezeAt = -1
}
