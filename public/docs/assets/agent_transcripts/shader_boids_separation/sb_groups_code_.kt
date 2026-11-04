import afield.simulation.physics.ShaderBoids

prop<afield.simulation.physics.ShaderBoids>(groups)

once.groups {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "groupsGrid",
    )
    ShaderBoids(grid, 12000, renderer) {
            name = "groups"
            radius = 2f / 64f
        }
        .also {
            it.reset(Vector2f(-0.05f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

// The default weights; only separation's reach differs between the two boxes.
groups.settings.separation = 0.02f
groups.settings.separationRadiusFraction = 0.25f
groups.settings.alignment = 3.0f
groups.settings.cohesion = 200f
groups.settings.maxSpeed = 0.25f
groups.settings.containment = 1.5f
groups.settings.containmentRadius = 0.44f
groups.settings.containmentCenter = Vector2f(0.45f, 0f)
groups.settings.dt = 1f / 60f
groups.settings.pointSize = 3f
groups.settings.intensity = 1.5f
// No driver: left alone, the flock settles into the shape its rule makes.
groups.settings.driverStrength = 0f
println("groups: separationRadiusFraction = ${groups.settings.separationRadiusFraction}")
