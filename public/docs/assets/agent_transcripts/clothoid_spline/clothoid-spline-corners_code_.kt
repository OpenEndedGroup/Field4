val d = clothoidSplineFrom(
    [vec(40, 280), vec(40, 40), vec(130, 50), vec(190, 160), vec(130, 270)],
    closed = true,
    corners = [0, 1],
).apply {
    thickness = 4.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val s = clothoidSplineFrom(
    [vec(370, 70), vec(300, 40), vec(250, 100), vec(320, 160), vec(370, 230), vec(310, 280), vec(240, 250)],
).apply {
    thickness = 4.0
    color = vec(1.0, 0.7, 0.3, 1.0)
}
val dComb = d.curvatureComb(scale = 800.0, samples = 160).apply { color = vec(1.0, 1.0, 1.0, 0.35) }
val sComb = s.curvatureComb(scale = 800.0, samples = 160).apply { color = vec(1.0, 1.0, 1.0, 0.35) }

lines(dComb, sComb, d, s, w = 420.0, h = 320.0, fitToSize = true)
