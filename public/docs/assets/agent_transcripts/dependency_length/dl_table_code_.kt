import afield.graphics.text.sentences.*

val rows =
    SentenceTexts.all().map { t ->
        val m = t.measure()
        val observed = m.sumOf { it.lengths.observed }.toDouble()
        "| ${t.title} | ${m.size} | %.1f | %.2f | %.2f | %.2f | %.0f%% |"
            .format(
                m.map { it.length }.average(),
                observed / m.sumOf { it.lengths.freeMean },
                observed / m.sumOf { it.lengths.fixedOrderMean },
                observed / m.sumOf { it.lengths.optimal },
                100.0 * m.count { it.lengths.observed < it.lengths.freeMean } / m.size,
            )
    }
markdown(
    """
    | text | sentences | mean words | ÷ free random | ÷ fixed order | ÷ shortest | shorter than chance |
    | --- | ---: | ---: | ---: | ---: | ---: | ---: |
    ${rows.joinToString("\n    ")}
    """
        .trimIndent(),
)
