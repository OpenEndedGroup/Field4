import afield.four.physics.ShaderBoids
import afield.four.util.GpuNeighbourGrid
import afield.stdlib.linalg.Vector2f

prop<afield.four.physics.ShaderBoids>(written)

once.written {
    val grid = GpuNeighbourGrid(
        capacity = 12000, cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f), domainSize = 2f,
        renderer = renderer, name = "writtenGrid")
    ShaderBoids(grid, 12000, renderer) {
        name = "written"
        radius = 2f / 64f
    }.also {
        it.reset(Vector2f(-0.95f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

// Live: the push buffer re-reads these every frame.
// Balanced against the measured magnitudes at this density and radius, not against
// the numbers a textbook quotes. With 57 neighbours inside a radius of 0.031,
// separation *sums* 57 terms of about 137 while cohesion *means* an offset of about
// 0.003 -- so the textbook 0.02/1.2/0.8 puts separation 27x above alignment and 4600x
// above cohesion, and the flock spaces itself evenly instead of gathering. These three
// bring the terms to the same order.
written.settings.separation = 0.02f
written.settings.separationRadiusFraction = 0.25f
written.settings.alignment = 3.0f
written.settings.cohesion = 200f
written.settings.maxSpeed = 0.25f
// Weak and wide on purpose. A stiff containment squeezes everything into one disc;
// give the flock room and cohesion only reaches one interaction radius, so groups that
// drift apart can never find each other again and you get several clumps instead of one.
written.settings.containment = 1.5f
written.settings.containmentRadius = 0.44f
written.settings.containmentCenter = Vector2f(-0.45f, 0f)
written.settings.dt = 1f / 60f
written.settings.pointSize = 2f
println("written rule: ${written.count} boids")
