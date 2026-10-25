prop<afield.simulation.particles.ShaderNeuralParticles>(particles)

once.particles {
    ShaderNeuralParticles(fieldA, renderer) {
            particleCount = 200_000
            stepsPerCycle = 150
            /*
            Low, on purpose. A high dwell shows the *distribution* a field settles on,
            which is the right choice for reading a word and the wrong one here: the
            interesting thing is a shape that is continuously becoming another, so the
            stream between the shapes has to stay visible.
            */
            dwellCycles = 1.2f
        }
        .also { it.installInto() }
}

particles.settings.intensity = 1.1f
particles.settings.arrivalBias = 0.55f
particles.settings.pointSize = 1.5f
particles.settings.viewScale = 3.4f
particles.settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
particles.settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)

val PERIOD = 2400.0 // frames for a full there-and-back: forty seconds at sixty

frames {
    while (true) {
        // A constant-speed triangle wave gives every interpolation value equal time,
        // including the buckling shapes on either side of the clean middle ring.
        val cycle = (renderer.frameCounter % PERIOD.toLong()) / PERIOD
        val t = (if (cycle < 0.5) cycle * 2 else 2 - cycle * 2).toFloat()
        // The whole idea, once a frame: a field that was never trained on anything.
        particles.updateWeights(fieldA.blendWith(fieldB, t).network)
        frame()
    }
}
