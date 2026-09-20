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
    ShaderBoids(grid, 12000, renderer, equivariant = learnedRule) {
        name = "learned"
        radius = 2f / 64f
    }.also {
        it.reset(Vector2f(-0.05f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

// The identical walk about this flock's own centre, so the two see the same forcing.
learned.driver = {
    val t = renderer.frameCounter / 70.0
    val i = Math.floor(t).toLong()
    fun at(k: Long): Vector2f {
        val r = java.util.Random(k * 0x9E3779B9L)
        return Vector2f(0.45f + (r.nextFloat() * 2 - 1) * 0.30f,
                        0f + (r.nextFloat() * 2 - 1) * 0.30f)
    }
    val a = at(i); val b = at(i + 1)
    val f = (t - i).toFloat()
    val e = f * f * (3 - 2 * f)
    Vector2f(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
}
learned.settings.driverStrength = -0.9f
learned.settings.driverRadius = 0.22f

learned.settings.separationRadiusFraction = 0.25f
learned.settings.maxSpeed = 0.25f
learned.settings.containment = 1.5f
learned.settings.containmentRadius = 0.44f
learned.settings.containmentCenter = Vector2f(0.45f, 0f)
learned.settings.dt = 1f / 60f
learned.settings.pointSize = 3f
learned.settings.intensity = 1.5f
learned.settings.slowColor = vec(0.95, 0.25, 0.45, 1.0)
learned.settings.fastColor = vec(1.0, 0.85, 0.45, 1.0)
println("learned rule: ${learned.count} boids on ${learned.learned}")
