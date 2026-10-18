// A shaded sphere on a white floor, as an Img: lit from up and to the left (y grows down).
val sphere =
    Img.generate(480, 360) { x, y ->
        val dx = (x - 220) / 140.0
        val dy = (y - 170) / 140.0
        val r2 = dx * dx + dy * dy
        val tone =
            if (r2 < 1.0) 0.1 + 0.9 * max(0.0, -0.45 * dx - 0.55 * dy + 0.7 * sqrt(1.0 - r2))
            else 1.0 - 0.6 * exp(-2.0 * ((x - 280) / 150.0).pow(2) - 2.0 * ((y - 318) / 30.0).pow(2))
        tone.toFloat()
    }

// 6000 dots wherever it is dark (`invert`, as ink on paper), evenly spaced rather than clumped.
val dots = sphere.stipple(6000, invert = true)
showImg(stipplesToImg(dots, sphere.width, sphere.height))

// The same dots as geometry, for a plotter or an SVG: lines(stipplesToFLine(dots, size = 0.4))

// Try this: 2000 or 20000 dots; `densityPower = 2f` for harder contrast (point density goes
// as tone^densityPower); drop `invert` to stipple the light instead of the shadow.
