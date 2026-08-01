// The rectangle is x 30..90, y 26..76, so the four strongest corners should read back as
// (30, 26), (89, 26), (30, 75), (89, 75) — check the disclosure against those numbers.
val src = Img.generate(240, 180) { x, y ->
    if (x in 30..90 && y in 26..76) 0.9f
    else if (x in 150..152 || y in 140..142) 0.98f
    else 0.15f
}
src.harrisCorners(minDistance = 8, maxCount = 40)
