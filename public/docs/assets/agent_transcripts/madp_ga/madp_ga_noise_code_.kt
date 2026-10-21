// Two of the demo's example programs as parents: a perlin mask blended with its own
// inverse, and rings crossed with spokes.
colorRpnEvolution(
    "x 8 * perlin dup invert 0.3 blend",
    "r 12 * sin a 9 * cos + 0.6 0.5 hsl",
)
