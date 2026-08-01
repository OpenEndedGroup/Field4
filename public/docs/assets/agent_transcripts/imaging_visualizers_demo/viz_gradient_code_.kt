val src = Img.generate(240, 180) { x, y ->
    if (x in 30..90 && y in 26..76) 0.9f
    else if (hypot(x - 172.0, y - 62.0) <= 30.0) 0.7f
    else 0.15f + 0.1f * (y / 179f)
}
src.sobel()
