// Click a point and drag it: the edit changes these FLines.
val petal = fline {
    moveTo(0, -120)
    cubicTo(90, -100, 140, -20, 0, 120)
    cubicTo(-140, -20, -90, -100, 0, -120)
    close()
    filled = true
    stroked = true
    thickness = 1.0
    color = vec(0.95, 0.42, 0.6, 0.34)
}
val stem = fline {
    moveTo(0, 120)
    cubicTo(10, 180, -20, 230, 0, 300)
    thickness = 1.0
    color = vec(0.45, 0.95, 0.62, 1.0)
}
lines(petal, stem, w = 420.0, h = 420.0, fitToSize = true, editable = true)
displayOpen("lines")
