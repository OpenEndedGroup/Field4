// empty the trail, restore the territorial matrices, and scatter the agents again
physarum.settings.depositInto = null
physarum.settings.attraction = listOf(
    vec(1f, -1f, 0f, 0f),
    vec(-1f, 1f, 0f, 0f),
)
physarum.trail.clear()
physarum.resetAgents(System.nanoTime())
println("reset")
