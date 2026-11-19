val circular = squircleRectFrom(20.0, 20.0, 380.0, 160.0, radius = 70.0, gauge = Math.sqrt(0.5)).apply {
    thickness = 1.5
    color = vec(1.0, 1.0, 1.0, 0.8)
}
val figma = squircleRectFrom(20.0, 20.0, 380.0, 160.0, radius = 70.0, gauge = 0.79, style = SquircleStyle.Figma).apply {
    thickness = 3.0
    color = vec(0.7, 0.6, 1.0, 1.0)
}
val clothoid = squircleRectFrom(20.0, 20.0, 380.0, 160.0, radius = 70.0, gauge = 0.79, style = SquircleStyle.Clothoid).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val figmaComb = figma.curvatureComb(scale = 600.0, samples = 400).apply { color = vec(0.7, 0.6, 1.0, 0.45) }
val clothoidComb = clothoid.curvatureComb(scale = 600.0, samples = 400).apply { color = vec(0.5, 0.8, 1.0, 0.45) }

lines(figmaComb, clothoidComb, circular, figma, clothoid, w = 420.0, h = 200.0, fitToSize = true)
