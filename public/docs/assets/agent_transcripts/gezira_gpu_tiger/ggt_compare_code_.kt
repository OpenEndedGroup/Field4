import afield.graphics.nile.*
import afield.graphics.vulkan.util.GpuProbe

val n = 300
val svg = GeziraSvg.tiger()
val doc = GeziraSvg.parse(svg)
val probe = GpuProbe(renderer, "gezira-gpu-tiger-compare")
val scene = GeziraGpuScene(probe, GeziraGpuScene.paths(doc.lines), n, n, transform = doc.fit(n, n))

frames {
    try {
        probe.arm(scene.image)
        while (probe.stillWaiting()) frame()
        // a written image is not a finished chain: let all five stages run whole
        while (probe.stageList.any { !it.valid }) frame()
        repeat(4) { frame() }
        val gpu = scene.read()
        val cpu = GeziraSvg.render(doc, n, n).canvas.rgba
        var worst = 0.0
        var sum = 0.0
        for (i in gpu.indices) {
            val d = Math.abs(gpu[i] - cpu[i]).toDouble()
            worst = maxOf(worst, d)
            sum += d
        }
        println("gpu vs cpu: largest %.4f, mean %.2e per channel".format(worst, sum / gpu.size))
        val diff = GeziraSvg.difference(gpu, cpu, 10.0)

        // Three panels side by side: the GPU, the CPU, and the difference times ten.
        val gap = 12
        val w = 3 * n + 2 * gap
        val sheet = FloatArray(4 * w * n)
        for ((k, img) in listOf(gpu, cpu, diff).withIndex()) {
            for (y in 0 until n) for (x in 0 until n) {
                val s = 4 * (y * n + x)
                val d = 4 * (y * w + k * (n + gap) + x)
                for (c in 0..3) sheet[d + c] = img[s + c]
            }
        }
        display("gpu | cpu | difference x10", GeziraSvg.png(sheet, w, n))
    } finally {
        probe.close()
    }
}
