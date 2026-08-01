// Edge magnitude x25 lands far outside 0..1. Clamping would show a flat white rectangle;
// the visualizer rescales for display and prints the range it rescaled from.
val src = Img.generate(240, 180) { x, y ->
    if (x in 30..90 && y in 26..76) 0.9f
    else if (hypot(x - 172.0, y - 62.0) <= 30.0) 0.7f
    else 0.15f
}
src.sobelMagnitude() * 25f
