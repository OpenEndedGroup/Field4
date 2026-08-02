import afield.stdlib.polytope.*
import java.util.Base64

// ***** CHANGE THIS LINE *****  Any prefix expression over the seeds T C O I D and the
// operators d a k t g j n z o e s m b, with optional kN / tN degree indices. Re-run and
// the whole contact sheet rebuilds — including the captions, which are read off the
// topology and not off the pictures.
val expressions = listOf("C", "dC", "aC", "kC", "tC", "gC", "bC", "k4aC", "sD")

val view = MeshView(width = 300, height = 252, wireframe = false, distance = 2.3)
val cells = expressions.map { source ->
    val result = conway(source)
    val topology = result.polyhedron.topology
    val faces = topology.elements(2)
        .groupingBy { topology.faceCycle(it.id).size }
        .eachCount()
        .toSortedMap()
    val png = Base64.getEncoder().encodeToString(
        result.polyhedron.toTriMesh().renderPng(view, caption = "", title = null),
    )
    """
    <div>
      <div style='color:#e6ecf4;font:600 13px monospace;padding-bottom:3px'>$source
        <span style='color:#6d7787;font-weight:400'>
          &nbsp;${result.history.joinToString("") { it.symbol.toString() }}
        </span>
      </div>
      <img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/>
      <div style='color:#93a4ba;font:11px monospace;padding-top:4px'>
        ${topology.elements(0).size}v ${topology.elements(1).size}e ${topology.elements(2).size}f
        &middot; ${faces.entries.joinToString(" ") { "${it.value}&times;${it.key}-gon" }}
        &middot; &chi;=${topology.eulerCharacteristic()}
      </div>
    </div>
    """.trimIndent()
}
display(
    "gallery",
    "<div style='display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px'>" +
        cells.joinToString("") + "</div>",
)

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
