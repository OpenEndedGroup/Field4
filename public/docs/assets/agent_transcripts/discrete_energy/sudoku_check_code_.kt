/*
Restarts the GPU descent, lets it take exactly 40 steps, freezes it, reads the beliefs back and
compares them with 40 steps of MeanFieldDescent on the CPU. Then lets it run on.
*/
val steps = 40
val clock = energyClock
clock.freezeAt = 0
frames {
    repeat(4) { frame() }
    energyUpload()
    clock.freezeAt = steps
    while (clock.step < steps) frame()
    repeat(4) { frame() }
    val buf = FloatArray(sudokuEnergy.gpuStateFloats)
    energyGpuState.read { get(buf) }
    val gpu = EnergyState(sudokuEnergy)
    sudokuEnergy.readGpuBeliefs(buf, clock.step, gpu)
    val cpu = EnergyState(sudokuEnergy)
    val descent = MeanFieldDescent(sudokuEnergy)
    repeat(clock.step) { descent.step(cpu) }
    val worst = gpu.p.indices.maxOf { kotlin.math.abs(gpu.p[it] - cpu.p[it]) }
    println(
        "after ${clock.step} steps: largest GPU-CPU belief difference $worst, same board = ${gpu.assignment.contentEquals(cpu.assignment)}",
    )
    clock.freezeAt = 400
}
