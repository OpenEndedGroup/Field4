import afield.graphics.nile.*

val W = 120
val H = 48
// Three shapes where the two fill rules differ, in pixel units.
val shape =
    GeziraGpu.pentagram(22.3, 25.4, 21.0) +
        Gezira.circle(54.2, 21.7, 13.0) +
        Gezira.circle(66.6, 27.3, 13.0) +
        GeziraGpu.holedSquare(84.3, 7.6, 32.0, 14.0, sameWay = true)

// The probe owns the buffers and the shader; one dispatch per fill rule.
val probe = GpuProbe(renderer, "gezira-gpu-demo")
val stage = GeziraGpu.stage(probe)
val jobs = FillRule.entries.map { GeziraGpu.dispatch(probe, stage, shape, W, H, it, it.name) }

// One picture, 2 x 3: a row per fill rule; GPU, CPU, and their difference x 100,000.
val gap = 4
val cw = 3 * W + 2 * gap
val white = DoubleArray(cw * (2 * H + gap))
val red = DoubleArray(white.size)
fun place(into: DoubleArray, c: DoubleArray, col: Int, row: Int) {
    for (y in 0 until H) for (x in 0 until W) {
        into[(row * (H + gap) + y) * cw + col * (W + gap) + x] = c[y * W + x]
    }
}
frames {
    try {
        probe.arm(*jobs.map { it.coverage }.toTypedArray())
        while (probe.stillWaiting()) frame()
        for ((n, job) in jobs.withIndex()) {
            val rule = job.packed.rule
            val gpu = job.read()
            val cpu = Gezira.rasterize(shape, W, H, rule)
            val (worst, mean) = Gezira.error(gpu, cpu)
            println("$rule: largest |gpu - cpu| %.2e, mean %.2e over the covered pixels".format(worst, mean))
            place(white, gpu, 0, n)
            place(white, cpu, 1, n)
            place(red, DoubleArray(gpu.size) { Math.abs(gpu[it] - cpu[it]) * 1e5 }, 2, n)
        }
        lines(
            Gezira.pixels(white, cw, 4.0) + Gezira.pixels(red, cw, 4.0, 1.0, 0.25, 0.2),
            channel = "gpu, cpu, difference",
        )
    } finally {
        probe.close()
    }
}
