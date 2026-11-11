val world = flowLeniaWorld(64, 64, [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()], seedRadius = 10.0)
val m0 = world.totalMass()
var worst = 0.0
repeat(200) {
    world.step()
    worst = maxOf(worst, kotlin.math.abs(world.totalMass() - m0) / m0)
}
println(world)
println("worst relative change in total mass over 200 steps: " + worst)
