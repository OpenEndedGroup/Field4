import afield.graphics.nile.*

val n = 300
val svg = GeziraSvg.tiger()
val doc = GeziraSvg.parse(svg)
val probe = GpuProbe(renderer, "gezira-gpu-tiger-compare")
val scene = GeziraGpuScene.of(probe, doc.lines, n, n, doc.fit(n, n))

frames {
    try {
        probe.arm(scene.image)
        while (probe.stillWaiting()) frame()
        // a written image is not a finished chain: let all five stages run whole
        while (probe.stageList.any { !it.valid }) frame()
        repeat(4) { frame() }
        // the renderer runs on its own thread: hold the picture still before reading it
        scene.hold = true
        repeat(3) { frame() }
        val gpu = scene.read()
        scene.hold = false
        val cpuStart = System.nanoTime()
        val cpu = GeziraSvg.render(doc, n, n).canvas.rgba
        val cpuMs = (System.nanoTime() - cpuStart) / 1e6
        // Field's frames over a second, with this scene and the live tiger box both drawing.
        val frameStart = System.nanoTime()
        repeat(60) { frame() }
        val frameMs = (System.nanoTime() - frameStart) / 1e6 / 60
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
        for ((k, img) in [gpu, cpu, diff].withIndex()) {
            for (y in 0 until n) for (x in 0 until n) {
                val s = 4 * (y * n + x)
                val d = 4 * (y * w + k * (n + gap) + x)
                for (c in 0..3) sheet[d + c] = img[s + c]
            }
        }
        display("gpu | cpu | difference x10", GeziraSvg.png(sheet, w, n))
        display(
            "readout",
            "<pre style='margin:0'>" +
                "${scene.paths.size} paths, ${scene.curveCount} curves, ${n} x ${n}\n" +
                "gpu vs cpu per channel: largest %.4f, mean %.1e\n".format(worst, sum / gpu.size) +
                "frame, both scenes on the gpu: %.1f ms\n".format(frameMs) +
                "cpu GeziraSvg.render: %.0f ms".format(cpuMs) +
                "</pre>",
        )
    } finally {
        probe.close()
    }
}
