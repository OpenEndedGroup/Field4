/*
The mean-field descent on the GPU: the compute tab makes one Euler step per frame on any
DiscreteEnergy, one invocation per variable. The board redraws as it settles; when the beliefs
stop moving, greedy moves and annealed Gibbs finish the board on the CPU. Re-run the check box
after this one has finished: it restarts the GPU descent to compare it with the CPU.
*/
val model = sudokuEnergy
val state = EnergyState(model)

var module = computeModuleHere {}
var m = ComputeMesh()
module += "mainMesh" to m
val clock = BondedClock()
clock.freezeAt = 400
renderer.computeList += "energyStep" to clock.stepping(module)

val pairwise = defaultBuffer { size = model.pairwise.size.floats }.buffer
val gpu = defaultBuffer { size = model.gpuStateFloats.floats }.buffer
pairwise.write { put(model.pairwise) }
val upload = {
    state.reset()
    gpu.write { put(model.gpuState(state)) }
    clock.restart()
}
upload()
prop<FrameBufferGroup>(energyGpuState) = gpu
prop<BondedClock>(energyClock) = clock
prop<() -> Unit>(energyUpload) = upload

m.pairwise = { pairwise }
m.state = { gpu }
m.tick = { clock.tick(renderer.frameCounter) }
m.units = { model.units }
m.variables = { model.variables }
m.eta = { 0.5f }
m.beta = { 1f }
m.x = (model.variables + 63) / 64
m.y = 1
m.z = 1

val buf = FloatArray(model.gpuStateFloats)
val previous = FloatArray(model.units)
var lastStep = -1
frames {
    while (true) {
        gpu.read { get(buf) }
        val step = clock.step
        // compare beliefs only across real GPU steps, not across frames where the clock did not move
        if (step != lastStep) {
            model.readGpuBeliefs(buf, step, state)
            val moved = state.p.indices.maxOf { kotlin.math.abs(state.p[it] - previous[it]) }
            state.p.copyInto(previous)
            lastStep = step
            if (step % 2 == 0) display("board", sudokuBoard(state, "GPU step $step"))
            if ((step > 10 && moved < 1e-4f) || step >= clock.freezeAt) break
        }
        frame()
    }
    println("GPU descent settled after $lastStep steps, energy ${state.energy}")
    GreedyMoves(model).settle(state)
    // annealed Gibbs from where the descent stopped: a long, gentle schedule
    for (attempt in 0 until 3) if (state.energy > 0)
        GibbsAnnealing(model, 5000, 0.6, 0.15, seed = attempt.toLong()).settle(state)
    println("after greedy moves and Gibbs: energy ${state.energy}")
    display("board", sudokuBoard(state, "finished, energy ${state.energy}"))
}
