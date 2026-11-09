// Bulk E = 0 fraction (fit over four patch sizes) at every symmetry, then the Penrose tiling
// with jittered grid lines, then with its offsets moved off a zero sum.
fun fit(graphs: List<TilingGraph>) = ZeroModeFit.of(graphs)
val symmetries = [3, 4, 5, 6, 7, 8, 9, 10, 12]
val bySymmetry = symmetries.map { n ->
    val base = 40.0 / n
    fit([1.0, 1.5, 2.2, 3.0].map { deBruijnTiling(symmetry = n, radius = base * it, scale = 1.0).vertexGraph() })
}
val sizes = [10.0, 16.0, 24.0]
val disorders = [0.0, 0.02, 0.05, 0.1, 0.2, 0.4, 0.8]
val byDisorder = disorders.map { d ->
    fit(sizes.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = d).vertexGraph() })
}
val sums = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5]
val bySum = sums.map { s ->
    val offsets = defaultDeBruijnOffsets(5).map { it + s / 5 }
    fit(sizes.map { deBruijnTiling(symmetry = 5, offsets = offsets, radius = it, scale = 1.0).vertexGraph() })
}
fun fmt(t: Table, vararg cols: String) = t.apply { cols.forEach { c -> format(c) { (it as Double).f4 } } }
display(
    "by symmetry",
    fmt(
        table(
            "symmetry" to symmetries,
            "largest patch" to bySymmetry.map { it.counts.last().first },
            "raw fraction" to bySymmetry.map { it.counts.last().second.toDouble() / it.counts.last().first },
            "bulk fit" to bySymmetry.map { it.bulk },
        ),
        "raw fraction",
        "bulk fit",
    ).heat("bulk fit"),
)
display(
    "by disorder",
    fmt(table("disorder" to disorders, "bulk fit" to byDisorder.map { it.bulk }), "bulk fit").heat("bulk fit"),
)
display(
    "by offset sum",
    fmt(table("offset sum" to sums, "bulk fit" to bySum.map { it.bulk }), "bulk fit").heat("bulk fit"),
)
