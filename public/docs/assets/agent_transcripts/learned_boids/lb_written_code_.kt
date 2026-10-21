import afield.simulation.physics.ShaderBoids

prop<afield.simulation.physics.ShaderBoids>(written)

// Built once: GPU buffers, neighbour grid and draw pass. Everything below is live.
once.written {
    val grid = GpuNeighbourGrid(
        capacity = 12000,
        cellsPerSide = 64,
        domainMin = Vector2f(-1f, -1f),
        domainSize = 2f,
        renderer = renderer,
        name = "writtenGrid",
    )
    ShaderBoids(grid, 12000, renderer) {
            name = "written"
            radius = 2f / 64f
        }
        .also {
            it.reset(Vector2f(-0.95f, -0.5f), 1.0f, speed = 0.2f, seed = 3)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

/*
The rule's weights. Live: the push buffer re-reads these every frame, so edit and
rerun. They are far from the textbook 0.02/1.2/0.8 because separation *sums* over
~57 neighbours while alignment and cohesion take *means*; these bring the three
terms to the same order at this density and radius. The training box hard-codes the
same three numbers -- rerun it after changing them.
*/
written.settings.separation = 0.02f
written.settings.separationRadiusFraction = 0.25f
written.settings.alignment = 3.0f
written.settings.cohesion = 200f
written.settings.maxSpeed = 0.25f
/*
Weak and wide on purpose. A stiff containment squeezes everything into one disc;
give the flock room and cohesion only reaches one interaction radius, so groups that
drift apart can never find each other again and you get several clumps instead of one.
*/
written.settings.containment = 1.5f
written.settings.containmentRadius = 0.44f
written.settings.containmentCenter = Vector2f(-0.45f, 0f)
written.settings.dt = 1f / 60f
written.settings.pointSize = 3f
written.settings.intensity = 1.5f

/*
A wandering point the flock flees (negative driverStrength). Left alone the flock
settles into a near-static disc, so the motion comes from outside the rule. The
learned box runs the identical walk about its own centre, so the A/B stays fair.

Called every frame. It is stateless: a seeded random target every 70 frames,
smoothstepped between, so calling it twice in a frame gives the same point.
*/
written.driver = {
    val t = renderer.frameCounter / 70.0
    val i = Math.floor(t).toLong()
    fun at(k: Long): Vector2f {
        val r = java.util.Random(k * 0x9E3779B9L)
        return Vector2f(
            -0.45f + (r.nextFloat() * 2 - 1) * 0.30f,
            0f + (r.nextFloat() * 2 - 1) * 0.30f,
        )
    }
    val a = at(i)
    val b = at(i + 1)
    val f = (t - i).toFloat()
    val e = f * f * (3 - 2 * f)
    Vector2f(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
}
written.settings.driverStrength = -0.9f
written.settings.driverRadius = 0.22f

println("written rule: ${written.count} boids")
