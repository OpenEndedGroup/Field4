// Nine creatures from the demo's zoo, each from the demo's starting positions under its own rule:
// 2000 steps to settle, then a measured window of 4000.
val tiles = mutableListOf<Pair<String, Img>>()
for (c in ParticleLeniaZoo.creatures) {
    val pl = c.lenia().stepTimes(2000)
    val w = pl.measure(4000)
    println("%-14s %3d  %s".format(c.name, c.count, w))
    tiles +=
        "%s: %s".format(c.name, w.motion.name.lowercase()) to
            LeniaMapCell(0.0, 0.0, pl, w).tile(extent = 16.0, size = 240)
}

// The gliders again under the demo's integrator, which carries half of each step into the next.
for (name in ["Iguanmousauru", "Agilienster", "Tarbosaurolop", "Coelopasaurop", "Sauropeltad"]) {
    val euler = ParticleLeniaZoo[name].lenia().stepTimes(2000).measure(4000)
    val demo = ParticleLeniaZoo[name].lenia().also { it.inertia = 0.5 }.stepTimes(2000).measure(4000)
    println("%-14s speed %.4f (Euler)  %.4f (demo's integrator)".format(name, euler.speed, demo.speed))
}

showImg(montage(tiles, columns = 3, cellSide = 240))
