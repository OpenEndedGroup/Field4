// A study of this box's `rings` property: three numbers, a ring count, the brightness of
// the dark bands, and warmth 0..1. Run this box first. It gives the document's AI4 chat ten
// tools (InspectRingsStudy ... RestoreRingsRevision); the two boxes below drive the same verbs
// from code. See public/docs/studies.md.

// A property is saved with the document only if its name is in Box.ioPersistentProperties,
// and `rings` is not by default: add it, and give it its starting text the first time.
Box.ioPersistentProperties.add("rings")
if ((thisBox.getPropertyRaw("rings", "") as? String).isNullOrBlank()) thisBox.setPropertyRaw("rings", "7 rings 0.05 ink 0.8 warmth")

val rings = sourceFramesStudy("rings", studyFrames("rings, drawn on the CPU") { source: String, w: Int, h: Int, t: Double ->
    val (count, ink, warmth) = StudyKnobs.plainNumbers(source).map { it.value.toFloat() }
    val px = FloatArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val d = Math.hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0) / w
        val v = (0.5 + 0.5 * Math.cos(d * count * 12.0 - t * 2.0)).toFloat()
        val i = (y * w + x) * 4
        px[i] = v * warmth + ink * (1 - v); px[i + 1] = v * 0.5f + ink * (1 - v); px[i + 2] = v * (1 - warmth) + ink * (1 - v); px[i + 3] = 1f
    }
    afield.stacksl.cpu.CpuRaster(w, h, px)
})

registerStudyTools("Rings", rings, about = "The source is three numbers: ring count, ink brightness, warmth 0..1.")
println("rings study ready: " + rings.inspect(StudyCall.Inspect()).revision)
