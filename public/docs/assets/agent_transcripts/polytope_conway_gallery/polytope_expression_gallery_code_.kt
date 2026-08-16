import afield.stdlib.polytope.*

// ***** CHANGE THIS LINE *****  Any prefix expression over the seeds T C O I D and the
// operators d a k t g j n z o e s m b, with optional kN / tN degree indices. Re-run and
// the whole contact sheet rebuilds — including the captions, which are read off the
// topology and not off the pictures.
val expressions = listOf("C", "dC", "aC", "kC", "tC", "gC", "bC", "k4aC", "sD")

val view = MeshView(wireframe = false, distance = 2.3)
val cells = expressions.map { source ->
    val result = conway(source)
    val topology = result.polyhedron.topology
    val faces = topology.elements(2)
        .groupingBy { topology.faceCycle(it.id).size }
        .eachCount()
        .toSortedMap()
    ThreeMeshPanel(
        label = "$source  ${result.history.joinToString("") { it.symbol.toString() }}",
        mesh = result.polyhedron.toTriMesh(),
        facts = "${topology.elements(0).size}v ${topology.elements(1).size}e ${topology.elements(2).size}f" +
            " · ${faces.entries.joinToString(" ") { "${it.value}×${it.key}-gon" }}" +
            " · χ=${topology.eulerCharacteristic()}",
    )
}
threeGrid(cells, channel = "gallery", name = "gallery", columns = 3, height = 756, cellWidth = 300, view = view)

// Second column of the caption is `history`: the PRIMITIVES that actually ran, seed
// outward. Composites are rewritten, never reimplemented, so `bC` reports `at`.
println(expressions.joinToString("\n") { source ->
    val r = conway(source)
    "%-6s  notationHistory %-6s  history %-6s".format(
        source,
        r.notationHistory.joinToString("") { it.symbol.toString() },
        r.history.joinToString("") { it.symbol.toString() },
    )
})
