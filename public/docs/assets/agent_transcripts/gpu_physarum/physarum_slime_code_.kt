/*
A million agents on a 1024 x 1024 trail. Each one looks at the trail three ways ahead,
turns toward the strongest, steps a pixel and leaves a mark; the trail blurs and fades
by itself. The defaults are the CPU PhysarumParams defaults, so this is the same model
as the page's 6000-agent swarm at about 170 times the population.
*/
prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = 1024
    height = 1024
    agentCount = 1_000_000
    speciesCount = 2
}

// steps once a frame and draws the trail, one colour per species
physarum.installInto()
println("${physarum.settings.agentCount} agents, ${physarum.settings.speciesCount} species")
