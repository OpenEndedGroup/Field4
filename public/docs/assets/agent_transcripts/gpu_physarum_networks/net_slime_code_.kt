/*
Jones' Physarum on a 512 x 512 trail, fed at the dots in the frame "network". `exclusive`
is Jones' one-agent-per-pixel rule; `recycle` gives the field an edge, and an agent that
reaches it re-enters at a random food source, so the 4000 agents are a budget of road:
fewer give a tree between the dots, more give loops.
*/
val side = 512
// the frame, read as trail pixels: x across, and y up, as the window draws the field
val map = notation.space("network", x = 0.0..side.toDouble(), y = side.toDouble()..0.0)

fun dots() = map.marks.filter { it.kind == MarkType.ELLIPSE }.map { map.toSpace(it.center) }

prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = side
    height = side
    agentCount = 4000
    exclusive = true
    recycle = true
    food = dots()
    exposure = 0.25f
}
// steps once a frame and draws the trail
physarum.installInto()

// every edit to the drawing moves the food
notation.onChange { physarum.settings.food = dots() }
println("${physarum.settings.food.size} food sources, ${physarum.settings.agentCount} agents")
