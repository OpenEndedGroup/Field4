import afield.io.timecode.*

// Four seconds of 29.97 drop-frame LTC across the one-minute boundary.
val start = Timecode.parse("00:00:58;00")
val ltc = LtcEncoder.render(start, 4.0, sampleRate = 48000)

// Play it back badly: quiet, offset, noisy, and speeding up from 0.9x to 1.1x.
val rng = java.util.Random(1)
val played = ArrayList<Float>()
var p = 0.0
while (p < ltc.size - 1) {
    val k = p.toInt()
    val v = ltc[k] + (ltc[k + 1] - ltc[k]) * (p - k).toFloat()
    played += v * 0.1f + 0.2f + rng.nextGaussian().toFloat() * 0.01f
    p += 0.9 + 0.2 * p / ltc.size
}

val frames = LtcDecoder(48000, TimecodeRate.FPS_2997_DF).decodeAll(played.toFloatArray())

// Decoded frame number against playback time, and the speed the decoder measured.
val w = 640.0
val h = 220.0
val tMax = played.size / 48000.0
val fMax = frames.maxOf { it.timecode - start }.toDouble()
val pts = frames.joinToString(" ") {
    "%.1f,%.1f".format(it.sampleStart / 48000 / tMax * w, h - (it.timecode - start) / fMax * h)
}
val spd =
    frames.joinToString(" ") { "%.1f,%.1f".format(it.sampleStart / 48000 / tMax * w, h - (it.speed - 0.8) / 0.4 * h) }
display(
    "main",
    """<svg width="${w + 60}" height="${h + 40}" style="font: 12px sans-serif">
  <g transform="translate(40,10)">
    <rect width="$w" height="$h" fill="none" stroke="#999"/>
    <polyline points="$pts" fill="none" stroke="#2a7" stroke-width="2"/>
    <polyline points="$spd" fill="none" stroke="#c52" stroke-width="1.5"/>
    <text x="4" y="14" fill="#2a7">decoded frame (0 … ${fMax.toInt()})</text>
    <text x="4" y="${h - 6}" fill="#c52">measured speed (0.8 … 1.2)</text>
    <text x="${w / 2 - 40}" y="${h + 22}" fill="#999">playback time, s (0 … ${"%.2f".format(tMax)})</text>
  </g></svg>""",
)
markdown(
    "info",
    "decoded **${frames.size}** frames, ${frames.first().timecode} … ${frames.last().timecode}; " +
        "consecutive: ${frames.zipWithNext().all { (a, b) -> a.timecode + 1 == b.timecode }}; " +
        "across the minute: ${frames.map { it.timecode.toString() }.filter { it.startsWith("00:00:59;2") || it.startsWith("00:01:00;0") }}",
)
