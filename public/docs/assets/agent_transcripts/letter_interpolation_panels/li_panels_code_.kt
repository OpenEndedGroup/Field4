import afield.stdlib.ShaderNeuralParticles
import afield.stdlib.blendWith

prop<List<afield.stdlib.ShaderNeuralParticles>>(panels)

// 0.5 is the ring. The other two are where the form is buckling -- a ring with the S's
// spine pressing into it -- and they are the reason this figure is not just "a circle".
// 0.78 rather than 0.75 because the buckle is deepest a little past three quarters.
val STOPS = listOf(0.25f, 0.5f, 0.78f)

// `name` keeps each system's buffers and modules apart; without it the second one would
// bind over the first. `viewCenter` is subtracted before the divide, so a negative x
// moves that panel to the right of the screen.
once.panels {
    STOPS.mapIndexed { i, _ ->
        ShaderNeuralParticles(fieldA, renderer) {
            name = "panel$i"
            particleCount = 150_000
            stepsPerCycle = 150
            // Higher than the sweeping scene's: a still wants the arrived distribution
            // crisp rather than the stream between shapes.
            dwellCycles = 3f
            viewScale = 4.6f
            viewCenter = Vector2f((1 - i) * 5.2f, 0f)
        }.also { it.installInto() }
    }
}

// Set once, not per frame. Each panel is a different field from the same trained pair.
STOPS.forEachIndexed { i, t ->
    panels[i].updateWeights(fieldA.blendWith(fieldB, t).network)
    panels[i].settings.intensity = 1.1f
    panels[i].settings.arrivalBias = 0.6f
    panels[i].settings.pointSize = 1.5f
    panels[i].settings.sourceColor = vec(0.15, 0.45, 1.0, 1.0)
    panels[i].settings.targetColor = vec(1.0, 0.62, 0.22, 1.0)
}
println("panels at ${STOPS.joinToString(", ")}")
