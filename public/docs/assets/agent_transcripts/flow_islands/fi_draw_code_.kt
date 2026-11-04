fun dots(points: List<Vec3>, size: Double, c: Vec4) = fline {
    points.forEach { moveTo(it) }
    pointed = true
    pointSize = size
    color = c
}

val island = islandModel.query("island(A, L)").rows.associate { (it[0] as Long).toInt() to it[1] as Long }
val labels = island.values.toSortedSet().toList()
val palette = [vec(0.44, 0.83, 0.78, 1.0), vec(0.98, 0.80, 0.35, 1.0), vec(0.6, 0.5, 0.95, 1.0)]
val strays = islandModel.query("stray(A)").column("A").map { moonSamples[(it as Long).toInt()] }

val byIsland = labels.mapIndexed { k, label ->
    dots(moonSamples.filterIndexed { i, _ -> island[i] == label }, 1.8, palette[k % palette.size])
}
lines(
    buildList {
        add(dots(moonTarget, 3.2, vec(0.45, 0.45, 0.52, 0.9)))
        addAll(byIsland)
        add(dots(strays, 2.6, vec(0.95, 0.30, 0.30, 1.0)))
    },
    w = 900.0,
    h = 440.0,
    fitToSize = true,
)
println("drawn: ${labels.size} islands and ${strays.size} strays")
