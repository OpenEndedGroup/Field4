/*
LAUNCH this box (undo stops it). The same rule, live on the CPU: it grows from a seed, and every
400 steps a disc in a random place is erased so you can watch it heal. The step count and the
error against the target are printed underneath.
*/
val nca = GrowingNca.pretrained("flower")
val target = GrowingNca.pretrainedTarget("flower")

frames {
    while (true) {
        nca.step(2)
        if (nca.steps % 400 == 200) nca.damage(20.0 + Math.random() * 24, 20.0 + Math.random() * 24, 10.0)
        display("main", nca.png(scale = 6))
        if (nca.steps % 20 == 0) println("step ${nca.steps}, error ${nca.loss(target).f4}")
        frame()
    }
}
