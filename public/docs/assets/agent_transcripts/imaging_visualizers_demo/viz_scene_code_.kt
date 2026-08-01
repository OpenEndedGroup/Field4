// A synthetic scene with something for every detector: a bright rectangle (corners), a
// disc (a Hough circle), two rulings (Hough lines), a checker patch (flow texture).
// The value of the box IS the image, so the inline visualizer is what you see below.
Img.generateRGB(240, 180) { x, y ->
    var r = 0.10f + 0.30f * (x / 239f)
    var g = 0.12f + 0.22f * (y / 179f)
    var b = 0.34f - 0.20f * (x / 239f)
    if (x in 30..90 && y in 26..76) { r = 0.94f; g = 0.86f; b = 0.42f }
    if (hypot(x - 172.0, y - 62.0) <= 30.0) { r = 0.30f; g = 0.80f; b = 0.92f }
    if (x in 150..152 || y in 140..142) { r = 0.98f; g = 0.98f; b = 0.98f }
    if (x in 24..104 && y in 104..164 && ((x / 8 + y / 8) % 2 == 0)) {
        r = 0.88f; g = 0.30f; b = 0.55f
    }
    Triple(r, g, b)
}
