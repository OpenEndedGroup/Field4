import afield.io.dmx.*
import afield.lang.rectsl.*

/*
The page's measurements, on this machine: nine frames sent and received back on a free port the
system picks (so this can run beside boxes 1 and 2), and a thin line swept across the rig,
sampled two ways.
*/
val trip = SceneOnARig.roundTrip()
println(trip)

val frameCount = 121
val lineWidth = 0.25
val frameWidth = 256
val frameHeight = 128
val line = RectSLImager(
    """
    #rectsl
    ->wall 16 8 abstract
    ->background 0x000000
    ->duration 4
    ->fps 30

    "sweep" rect
      $lineWidth 8 ->wh
      0xffffff solid ->fill
      '( 0=-8  4=8 ) linear ->x
    ;""".trimIndent(),
)
val sampleRig = SceneOnARig.Rig()
val point = SceneOnARig.SamplingTrace("point", DoubleArray(frameCount), DoubleArray(frameCount))
val area = SceneOnARig.SamplingTrace("area", DoubleArray(frameCount), DoubleArray(frameCount))
val lineTruth = lineWidth * SceneOnARig.ROWS

// Keep the line wholly on the wall; compare both routes at exactly the same times.
for (k in 0 until frameCount) {
    val t = 0.5 + 3.0 * k / (frameCount - 1.0)
    val image = line.frame(t, frameWidth, frameHeight, linear = true)
    for ((trace, averageFirst) in [point to false, area to true]) {
        val sampled = if (averageFirst) image.resize(SceneOnARig.COLS, SceneOnARig.ROWS) else image
        sampleRig.patch.mapImage(sampled, only = sampleRig.grid)
        var total = 0.0
        var peak = 0.0
        for (fixture in sampleRig.grid) {
            // A fully white fixture contributes one unit of light.
            val brightness = (fixture.color.x + fixture.color.y + fixture.color.z) / 3.0
            total += brightness
            peak = maxOf(peak, brightness)
        }
        trace.total[k] = total
        trace.peak[k] = peak
    }
}
println(
    "point samples: dark in ${point.darkFrames} of ${point.total.size} frames, light on the rig ${point.min.f2}..${point.max.f2}",
)
println(
    "area average:  dark in ${area.darkFrames} frames, light on the rig ${area.min.f3}..${area.max.f3} (the line is worth ${lineTruth})",
)
