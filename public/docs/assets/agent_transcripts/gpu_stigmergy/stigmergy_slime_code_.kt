/*
Three species on one 1024 x 1024 trail, one channel each. Row s of `attraction` is how
species s weighs the four channels when it senses: +1 for its own, -2 for each other's.
`exclusive` is Jones' one-agent-per-pixel rule, so no road can hold more agents than it
has pixels. Low diffusion keeps the trails sharp. The three pack the field into an
interlocking labyrinth whose colours do not cross; one species alone, at the same
density, closes into a net of cells instead.
*/
prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = 1024
    height = 1024
    agentCount = 300_000
    speciesCount = 3
    exclusive = true
    exposure = 0.06f
    attraction = listOf(
        vec(1f, -2f, -2f, 0f),
        vec(-2f, 1f, -2f, 0f),
        vec(-2f, -2f, 1f, 0f),
    )
}
physarum.trail.settings.diffusion = 0.2f

// steps once a frame and draws the trail, one colour per species
physarum.installInto()
println("${physarum.settings.agentCount} agents, ${physarum.settings.speciesCount} species")
