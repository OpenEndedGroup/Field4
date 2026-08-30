// Live regression for dust_three's shared-soup seed. Connect beneath dust - field;
// run after seeding and BEFORE launching the forces box (checks both initial GPU states).
import drowning.*
val cloud = _cloud
val links = _links
val owner = _particleCells
check(owner.all { it == -1 })
for (i in 0 until cloud.count) {
    check((0..2).all { cloud.position[i*3+it].isFinite() })
    for (s in 0 until DustLayout.EDGES) {
        val k = i*DustLayout.EDGES+s
        val j = links.neighbour[k]
        if (j != DustLayout.NONE) {
            check(abs(cloud.distance(i,j) - links.restLength[k]) < 1e-5f)
        }
    }
}
check(cloud.count == 40000)
check(links.isolated() == 0)
for (state in listOf(_dust.stateA, _dust.stateB)) {
    state.mapAndRead_bytes {
        order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until cloud.count) {
            val k = i*DustLayout.PARTICLE_BYTES
            for (a in 0..2) {
                check(getFloat(k+4*a) == cloud.position[i*3+a])
                check(getFloat(k+16+4*a) == cloud.position[i*3+a])
            }
            check(getFloat(k+32) == -1f)
            check(getFloat(k+36) == -1f)
        }
    }
}
println("SEED_ALL_PASS 40000 finite particles in one shared network; no fixed cell IDs or isolated particles; both GPU states match")
println(cloud.describe())
println(links.describe())
