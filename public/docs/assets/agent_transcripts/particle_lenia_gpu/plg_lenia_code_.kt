import afield.simulation.physics.ShaderParticleLenia

prop<ShaderParticleLenia>(lenia)

// 64 clusters of 100 particles under the rotator rule: 6400 particles, one creature per cluster.
once.lenia {
    val grid = GpuNeighbourGrid(
        capacity = 6400,
        cellsPerSide = 40,
        domainMin = Vector2f(-120f, -120f),
        domainSize = 240f,
        renderer = renderer,
        name = "leniaGrid",
    )
    ShaderParticleLenia(grid, 6400, renderer, ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3), name = "lenia").also {
        it.reset(creatures = 64, perCreature = 100, side = 8.5f, spacing = 26f, seed = 1)
        it.install(renderer.renderList.others)
        it.installDrawInto()
    }
}

lenia.substeps = 20
lenia.pointSize = 3f
lenia.viewScale = 110f
println("particles: " + lenia.count + ", cutoff " + lenia.cutoff + ", cell " + lenia.grid.cellSize)
