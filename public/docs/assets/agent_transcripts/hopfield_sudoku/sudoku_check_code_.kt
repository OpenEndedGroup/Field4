/*
Restarts the GPU descent, lets it take exactly 40 steps, freezes it, reads the beliefs back
and compares them with HopfieldSudoku.beliefsAfter, the CPU reference. Then lets it run on.
*/
val steps = 40
val clock = sudokuClock
clock.freezeAt = 0
frames {
    repeat(4) { frame() }
    sudokuUpload()
    clock.freezeAt = steps
    while (clock.step < steps) frame()
    repeat(4) { frame() }
    val buf = FloatArray(sudokuModel.gpuStateFloats)
    sudokuState.read { get(buf) }
    val gpu = sudokuModel.gpuBeliefs(buf, clock.step)
    val cpu = sudokuModel.beliefsAfter(sudokuPuzzle, clock.step)
    val worst = gpu.indices.maxOf { kotlin.math.abs(gpu[it] - cpu[it]) }
    val same = sudokuModel.decode(sudokuPuzzle, gpu).contentEquals(sudokuModel.decode(sudokuPuzzle, cpu))
    println("after ${clock.step} steps: largest GPU-CPU belief difference $worst, same decoded board = $same")
    clock.freezeAt = 300
}
