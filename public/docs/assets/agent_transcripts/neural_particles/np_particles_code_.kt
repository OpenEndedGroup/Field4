import afield.stdlib.ShaderNeuralParticles

prop<afield.stdlib.ShaderNeuralParticles>(particles)

// `renderer` and `neuralExport` are the parent boxes' props, visible through the
// preamble. The `once` is real rather than habit: the shader has the network's shape
// compiled into it, so rebuilding on every run would leak a module per keystroke.
once.particles {
    ShaderNeuralParticles(neuralExport, renderer) {
        particleCount = 300_000
        stepsPerCycle = 150
        dwellCycles = 3f
    }.also { it.installInto() }
}

// Everything below is live. The push buffer re-reads these lambdas every frame, so
// editing a number here and re-running changes the picture without touching the GPU
// allocation or recompiling a shader.
particles.settings.intensity = 1.1f
particles.settings.arrivalBias = 0.5f
// Seeded slightly off the trained source so 300,000 particles are not 12,000 threads.
particles.settings.sourceJitter = 0.02f
particles.settings.pointSize = 1.5f
particles.settings.viewScale = 2.6f
particles.settings.stepsPerCycle = 150
particles.settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
particles.settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)

// `particleCount` and `phaseSpread` are the two that are not live: they decide the
// buffer's size and its contents. Change one and call `particles.reset()`.

println("${particles.settings.particleCount} particles on ${particles.export.network}")
