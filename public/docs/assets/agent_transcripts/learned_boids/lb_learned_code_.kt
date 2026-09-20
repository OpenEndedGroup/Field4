import afield.four.physics.ShaderBoids
import afield.four.util.GpuNeighbourGrid
import afield.stdlib.linalg.Vector2f

prop<afield.four.physics.ShaderBoids>(learned)

// `learnedRule` is the trained network from the parent box. The only difference from
// the written flock is this argument.
once.learned {
    val grid = GpuNeighbourGrid(
        capacity = 12000, cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f), domainSize = 2f,
        renderer = renderer, name = "learnedGrid")
    ShaderBoids(grid, 12000, renderer, learned = learnedRule) {
        name = "learned"
        radius = 2f / 64f
    }.also {
        it.reset(Vector2f(-0.05f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

learned.settings.separationRadiusFraction = 0.25f
learned.settings.maxSpeed = 0.25f
learned.settings.containment = 1.5f
learned.settings.containmentRadius = 0.44f
learned.settings.containmentCenter = Vector2f(0.45f, 0f)
learned.settings.dt = 1f / 60f
learned.settings.pointSize = 2f
learned.settings.slowColor = vec(0.95, 0.25, 0.45, 1.0)
learned.settings.fastColor = vec(1.0, 0.85, 0.45, 1.0)
println("learned rule: ${learned.count} boids on ${learned.learned}")
