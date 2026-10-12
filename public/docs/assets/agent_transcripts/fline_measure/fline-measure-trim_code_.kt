val curve = fline {
    moveTo(40, 200)
    cubicTo(100, 20, 220, 20, 250, 130)
    lineTo(380, 60)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.35)
}

val total = curve.length()

// from a quarter to 60% of the way, by distance, drawn over the original
val middle = curve.trim(0.25f * total, 0.6f * total).apply {
    thickness = 5.0
    color = vec(0.4, 0.9, 1.0, 1.0)
}

// cut at 100 units: two pieces whose lengths add up to the whole
val (head, tail) = curve.splitAt(100f)
head.apply { thickness = 2.0; color = vec(1.0, 0.6, 0.2, 1.0) }

print("total ${"%.1f".format(total)} = ${"%.1f".format(head.length())} + ${"%.1f".format(tail.length())}")

lines(listOf(curve, head, middle), w = 420.0, h = 260.0, fitToSize = true)
