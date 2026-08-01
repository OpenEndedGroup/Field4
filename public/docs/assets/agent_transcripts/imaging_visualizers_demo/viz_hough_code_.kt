// Two rulings planted at x=151 and y=141; the peaks should come back as rho~149 theta=0
// and rho~139 theta=pi/2. A HoughLine is infinite, so the left panel has to invent a
// canvas — it says which one, and points at linesToFLines for the in-place version.
val src = Img.generate(240, 180) { x, y ->
    if (x in 150..152 || y in 140..142) 0.98f
    else if (x in 30..90 && y in 26..76) 0.9f
    else 0.12f
}
houghLines(src.canny(0.10, 0.25), maxLines = 8, threshold = 40)
