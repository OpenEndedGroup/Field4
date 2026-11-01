import afield.simulation.physics.ShaderBoids

prop<afield.simulation.physics.ShaderBoids>(even)

once.even {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "evenGrid",
    )
    ShaderBoids(grid, 12000, renderer) {
            name = "even"
            radius = 2f / 64f
        }
        .also {
            it.reset(Vector2f(-0.95f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

// The default weights; only separation's reach differs between the two boxes.
even.settings.separation = 0.02f
even.settings.separationRadiusFraction = 1.0f
even.settings.alignment = 3.0f
even.settings.cohesion = 200f
even.settings.maxSpeed = 0.25f
even.settings.containment = 1.5f
even.settings.containmentRadius = 0.44f
even.settings.containmentCenter = Vector2f(-0.45f, 0f)
even.settings.dt = 1f / 60f
even.settings.pointSize = 3f
even.settings.intensity = 1.5f
// No driver: left alone, the flock settles into the shape its rule makes.
even.settings.driverStrength = 0f
println("even: separationRadiusFraction = ${even.settings.separationRadiusFraction}")
