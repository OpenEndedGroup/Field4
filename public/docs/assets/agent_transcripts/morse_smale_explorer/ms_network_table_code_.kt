import afield.geometry.topology.morsesmale.*

val bumps = [
    Triple(70.0, 60.0, 1.0),
    Triple(165.0, 50.0, 0.8),
    Triple(120.0, 125.0, 0.9),
    Triple(45.0, 140.0, -0.6),
    Triple(195.0, 135.0, -0.7),
]
val field =
    Img.generate(240, 180) { x, y ->
        bumps.sumOf { (cx, cy, a) -> a * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 900.0) }.toFloat()
    }
val result = MorseSmale.compute(field)

// The saddle between the two upper hills, and the separatrices that leave it.
val saddle = result.criticalPoints.filter { it.type == CriticalType.SADDLE }.minBy { abs(it.x - 118) + abs(it.y - 55) }
val rows =
    result.network.curvesAt(saddle.id).joinToString("\n") { c ->
        val end = result.complex.critical(c.endNode!!)!!
        "| ${c.id} | ${end.type.name.lowercase()} ${end.id} | ${c.attributes.getValue("persistence").f3} | ${c.vertexCount} |"
    }
markdown(
    """
${result.network}

| curve | ends at | persistence | points |
| --- | --- | ---: | ---: |
$rows
"""
        .trimIndent(),
)
