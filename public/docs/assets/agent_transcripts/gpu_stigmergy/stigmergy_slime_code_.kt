/*
Two species on one 512 x 512 trail, one channel each. Row s of `attraction` is how
species s weighs the four channels when it senses: +1 for its own, -1 for the other's.
Each follows its own roads and turns away from the other's, so the field divides into
amber and cyan territories that keep re-negotiating their borders.
*/
prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = 512
    height = 512
    agentCount = 250_000
    speciesCount = 2
    exposure = 0.06f
    attraction = listOf(
        vec(1f, -1f, 0f, 0f),
        vec(-1f, 1f, 0f, 0f),
    )
}

// steps once a frame and draws the trail, one colour per species
physarum.installInto()
println("${physarum.settings.agentCount} agents, ${physarum.settings.speciesCount} species")
