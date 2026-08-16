import afield.stdlib.polytope.*
import afield.stdlib.MeshView

// Until 2026-08-05 none of these compiled: the eight composites were declared on
// PolyhedralObject only, so cube().ambo() had no .bevel(), and needle, zip and ortho had no
// named function at all. Every operator is now on both receivers, so a chain reads the same
// wherever a step sits.
val chains: List<Pair<String, PolytopeTransformResult>> = listOf(
    "cube().ambo().join()" to cube().ambo().join(),
    "cube().ambo().needle()" to cube().ambo().needle(),
    "cube().ambo().zip()" to cube().ambo().zip(),
    "cube().ambo().ortho()" to cube().ambo().ortho(),
    "cube().ambo().expand()" to cube().ambo().expand(),
    "cube().ambo().snub()" to cube().ambo().snub(),
    "cube().ambo().meta()" to cube().ambo().meta(),
    "cube().ambo().bevel()" to cube().ambo().bevel(),
)

val view = MeshView(wireframe = false, distance = 2.4)
val cells = chains.map { (source, result) ->
    val topology = result.polyhedron.topology
    ThreeMeshPanel(
        label = source,
        mesh = result.polyhedron.canonicalize().polyhedron.toTriMesh(),
        facts = "asked ${result.notationHistory.joinToString("") { it.symbol.toString() }}" +
            " · ran ${result.history.joinToString("") { it.symbol.toString() }}" +
            " · ${topology.elements(0).size}v ${topology.elements(1).size}e ${topology.elements(2).size}f" +
            " · χ=${topology.eulerCharacteristic()}",
    )
}
threeGrid(cells, channel = "chains", name = "chains", columns = 4, height = 440, cellWidth = 260, view = view)

// The composite is recorded as what you asked for; the history is what actually ran. And a
// longer chain is just more of the same.
val long = cube().ambo().bevel().kis().dual()
println(
    """
    asked for: ${long.notationHistory.joinToString(" ") { it.displayName }}
    actually ran: ${long.history.joinToString(" ") { it.displayName }}
    result: ${long.polyhedron.topology.elements(0).size}v ${long.polyhedron.topology.elements(2).size}f,
        chi = ${long.polyhedron.topology.eulerCharacteristic()}
    a composite still refuses a single realization policy:
        ${runCatching { cube().ambo().transform(ConwayOperator.BEVEL, DualCentroidRealization) }.exceptionOrNull()?.message}
    """.trimIndent(),
)
viz("chain", long.summary())
