// Draws alignment against round as an SVG polyline in the box's output.
val values = listOf(0.468, 0.609, 0.665, 0.702, 0.732, 0.757, 0.779)
val pts = values.mapIndexed { i, v -> "${20 + i * 40},${120 - v * 100}" }.joinToString(" ")
display(
    "main",
    """<svg width="290" height="140" style="background:#111">
  <polyline points="$pts" fill="none" stroke="#48c9ff" stroke-width="2"/>
  <text x="20" y="135" fill="#888" font-size="11">round 0 .. 6</text>
</svg>""",
)
println("drew ${values.size} points")
