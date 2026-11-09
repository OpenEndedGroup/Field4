import afield.audio.modal.*

// Forty random uniform drums: f₂/f₁ across (1.0–2.1), distance from 1:2:3:4:5 up (0–400 cents).
// The white line is f₂/f₁ = 1.593, the most any uniform drum can reach; yellow is the syahi.
val disk = polarDrumMesh(0.08) { 1.0 }
val rnd = java.util.Random(3)
val shapes = (0 until 40).map {
    PolarShape.circle(5).apply {
        for (k in 2..5) {
            cosine[k] = 0.5 * (rnd.nextDouble() - 0.5) / k
            sine[k] = 0.5 * (rnd.nextDouble() - 0.5) / k
        }
    }
}
val scores =
    shapes
        .parallelStream()
        .map { s ->
            val m = disk.polarMorphed(s).modes(15)
            m.ratios()[1] to m.ramanHarmonicity().rms
        }
        .toList()
val syahi = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.07).modes(15)
fun dot(f: Double, cents: Double) = fline { circle((f - 1.0) * 400, 400 - cents, 6.0) }.apply { filled = true }
val out = ArrayList<FLine>()
out +=
    fline {
        moveTo(0.0, 0.0)
        lineTo(440.0, 0.0)
        lineTo(440.0, 400.0)
        lineTo(0.0, 400.0)
        lineTo(0.0, 0.0)
    }
        .apply { color = vec(0.4, 0.4, 0.4, 1.0) }
out +=
    fline {
        moveTo(0.593 * 400, 0.0)
        lineTo(0.593 * 400, 400.0)
    }
        .apply {
            color = vec(1.0, 1.0, 1.0, 1.0)
            thickness = 1.5
        }
scores.forEach { (f, c) -> out += dot(f, c).apply { color = vec(0.35, 0.6, 1.0, 1.0) } }
out += dot(syahi.ratios()[1], syahi.ramanHarmonicity().rms).apply { color = vec(1.0, 0.85, 0.3, 1.0) }
lines(out, w = 440.0, h = 400.0, fitToSize = true)
println(
    "highest f₂/f₁ of 40 shapes: ${scores.maxOf { it.first }.f3}; closest to 1:2:3:4:5: ${scores.minOf { it.second }.f1} cents",
)
println("loaded disk: f₂/f₁ ${syahi.ratios()[1].f3}, ${syahi.ramanHarmonicity().rms.f1} cents")
