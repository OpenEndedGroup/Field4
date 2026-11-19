/*
Each genome is a body, one character per segment: M muscle, L and R muscles that also
curve the body left or right (so it swims in circles), S stiff, W weak (tears first).
Edit them and re-run this box: the world is rebuilt and uploaded again.
*/
val genomes = ["SMMMMMMMMMMS", "SLLLLLLLLLS", "SMMMMMMMMMMMMMMS", "SRRRRRRRRS"]

val makeWorld = {
    BondedWorld(1400).apply {
        params.wallX = 30f
        params.wallY = 18f
        // where each one starts and the way it heads (radians): the straight worms cross the
        // world, the L and R worms turn
        val starts = [
            Triple(-22f, -12f, 0f),
            Triple(-16f, 8f, 0f),
            Triple(21f, 8f, PI.toFloat()),
            Triple(16f, -2f, PI.toFloat()),
        ]
        genomes.forEachIndexed { k, g ->
            val (sx, sy, heading) = starts[k]
            grow(g, sx, sy, angle = heading)
        }
        scatter(500, -29f, -17f, 29f, 17f)
    }
}
val world = makeWorld()
prop<BondedWorld>(bonded) = world
prop<() -> BondedWorld>(bondedFactory) = makeWorld

var module = computeModuleHere {}
var m = ComputeMesh()
module += "mainMesh" to m
// several steps a frame, each reading what the last one wrote: raise it to speed the worms up
val clock = BondedClock()
clock.stepsPerFrame = 2
renderer.computeList += "bondedStep" to clock.stepping(module)

// two halves of cells (the shader reads one and writes the other) and the bond slots
var cells = defaultBuffer { size = (world.capacity * 4).vec4s }.buffer
var bonds = defaultBuffer { size = (world.capacity * 6).vec4s }.buffer
val upload = {
    cells.write { put(world.cellFloats()) }
    bonds.write { put(world.bondFloats()) }
    clock.restart()
}
upload()
prop<FrameBufferGroup>(bondedCells) = cells
prop<FrameBufferGroup>(bondedBonds) = bonds
prop<BondedClock>(bondedClock) = clock
prop<() -> Unit>(bondedUpload) = upload

// push constants, in the order the compute tab declares them
val p = world.params
m.cells = { cells }
m.bonds = { bonds }
m.tick = { clock.tick(renderer.frameCounter) }
m.count = { world.count }
m.capacity = { world.capacity }
m.dt = { p.dt }
m.stiffness = { p.stiffness }
m.bondDamping = { p.bondDamping }
m.dragNormal = { p.dragNormal }
m.dragTangent = { p.dragTangent }
m.omega = { p.omega }
m.repulsionRadius = { p.repulsionRadius }
m.repulsionStiffness = { p.repulsionStiffness }
m.energyDiffusion = { p.energyDiffusion }
m.muscleCost = { p.muscleCost }
m.fullEnergy = { p.fullEnergy }
m.wallX = { p.wallX }
m.wallY = { p.wallY }
m.wallStiffness = { p.wallStiffness }
m.x = (world.capacity + 63) / 64
m.y = 1
m.z = 1
println("${world.organisms.size} organisms, ${world.count} cells, ${world.bondCount()} bonds")
