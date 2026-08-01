// Adjacent label ids get unrelated hues (golden-ratio rotation), which is what makes two
// touching blobs distinguishable at thumbnail size.
Img.generate(240, 180) { x, y ->
    val a = x in 20..70 && y in 20..70
    val b = hypot(x - 150.0, y - 50.0) <= 32.0
    val c = x in 40..90 && y in 110..160
    val d = x in 170..220 && y in 120..150
    if (a || b || c || d) 1f else 0f
}.connectedComponents()
