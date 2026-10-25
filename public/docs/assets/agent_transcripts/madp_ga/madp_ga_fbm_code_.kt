// Layered noise along a diagonal, and two noises in x and y added into a hue.
colorRpnEvolution(
    "x y + 7 * 2.2 fbm 0.65 0.55 hsl",
    "x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl",
)
