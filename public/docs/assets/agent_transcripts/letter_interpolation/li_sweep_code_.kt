import afield.stdlib.ShaderNeuralParticles
import afield.stdlib.blendWith

prop<afield.stdlib.ShaderNeuralParticles>(particles)

once.particles {
    ShaderNeuralParticles(fieldA, renderer) {
        particleCount = 200_000
        stepsPerCycle = 150
        // Low, on purpose. A high dwell shows the *distribution* a field settles on,
        // which is the right choice for reading a word and the wrong one here: the
        // interesting thing is a shape that is continuously becoming another, so the
        // stream between the shapes has to stay visible.
        dwellCycles = 1.2f
    }.also { it.installInto() }
}

particles.settings.intensity = 1.1f
particles.settings.arrivalBias = 0.55f
particles.settings.pointSize = 1.5f
particles.settings.viewScale = 3.4f
particles.settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
particles.settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)

val PERIOD = 2400.0    // frames for a full there-and-back: forty seconds at sixty

frames {
    while (true) {
        // A plain triangle wave -- constant speed, every value of t given the same
        // time. An earlier version cubed this about the centre to linger at t = 0.5,
        // on the assumption that the middle was the interesting part. Captured frame
        // by frame it is not: t = 0.5 is a clean closed ring, the tidiest and least
        // ambiguous form in the whole sweep, and the shapes worth watching are the
        // buckling ones either side of it at roughly 0.2-0.3 and 0.7-0.9. Easing
        // towards the centre parks on the dullest frame. Slow everywhere beats clever.
        val cycle = (renderer.frameCounter % PERIOD.toLong()) / PERIOD
        val t = (if (cycle < 0.5) cycle * 2 else 2 - cycle * 2).toFloat()
        // The whole idea, once a frame: a field that was never trained on anything.
        particles.updateWeights(fieldA.blendWith(fieldB, t).network)
        frame()
    }
}
