# Polytopes: topology first, coordinates later

*`afield.stdlib.polytope` — an incidence topology, a Conway operator algebra over it, element
provenance that survives every operator, and only then coordinates.*

*Every declaration used below is listed, with its KDoc, in the codebase reference:
[Uncategorised](reference/uncategorised.md). It is filed there rather than under Graphics because
these source files carry no `@file:CompletionCategory` yet — the pages are grouped by the label the
source itself declares, and this family has not declared one.*

Most "polyhedron libraries" are mesh libraries wearing a hat. You get vertices, faces as triangles,
maybe a normal, and every question you actually want to ask — *which* face is this, where did it
come from, what was it before the last operator ran — has to be reconstructed from floating-point
positions after the fact.

This one is built the other way round. The authoritative value is a **ranked incidence topology**:
elements with identities, incidences between adjacent ranks, and an explicit ordered boundary cycle
for every polygon. It knows nothing about coordinates, `FLine`s, triangles, normals or a renderer.
Coordinates are a **separate, optional, replaceable layer** on top. Triangles are derived last of
all, and they carry a map back to the polygon they came from.

Everything on this page was produced by the docs build running the code shown above it, in a live
Field. No figure is drawn by hand and no number is typed by a human — where a count, a diagnostic or
an error message appears, it was printed by the library.

!!! info "Where the pieces live"

    | what you want | where it is |
    |---|---|
    | ranked elements, incidence, face cycles, validation, abstract dual | `Topology.kt` — `PolytopeTopology`, `PolytopeTopologyBuilder`, `Element`, `ElementId` |
    | the five Platonic seeds, `Realization3`, `PolyhedralObject` | `Solids.kt` |
    | operators, realization policies, lineage, deployment records | `Transforms.kt` — `ConwayOperator`, `RealizationPolicy3`, `PolytopeTransformResult` |
    | prefix notation `daC`, `k4aC` | `Conway.kt` — `parseConwayExpression`, `conway` |
    | selectors and per-element scalar fields | `Parameters.kt` — `ElementSelectors`, `ElementParameters` |
    | constraint relaxation and its diagnostics | `RealizationSolver.kt` — `solveRealization`, `geometryDiagnostics` |
    | one `FLine` per face, provenance-preserving triangles | `FieldAdapters.kt` — `toFaceFLines`, `triangulate` |
    | compact inspection values for a live prompt | `Summaries.kt`, `PolytopeVisualizers.kt` |

    The full architecture note, including the roadmap this page does *not* cover, is
    `dev_docs/POLYTOPE_ALGEBRA.md` in the repository.

## A polyhedron is not a mesh

`PolytopeTopology` stores elements by rank, incidences between **adjacent** ranks only, and — for
rank 2 — an explicit ordered vertex cycle, because unoriented incidence alone does not choose a
traversal of a polygon. Rank 3, for an ordinary polyhedron, is the single whole-polyhedron element.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/topology_readout.png" wait="1.5" capture="box" open-outputs="true" w="800" h="560" padding="14"}
:::static
import afield.stdlib.polytope.*

val t = cube().topology

val face = t.elements(2).first()
val cycle = t.faceCycle(face.id)
val edgesOfFace = t.incidentDown(face.id)
val anEdge = ElementId(edgesOfFace.first())

:::fold format the read-out as a table
val ranks = (0..t.rank).joinToString("\n") { r ->
    "| $r | ${listOf("vertices", "edges", "faces", "the whole polyhedron").getOrElse(r) { "?" }} | ${t.elements(r).size} |"
}
markdown("topology", """
#### `cube().topology` — everything that is actually stored

| rank | what it is | count |
|---|---|---|
$ranks

- **face ${face.id.value}**'s ordered boundary cycle: `${cycle.joinToString(" → ")}` (${cycle.size} vertices)
- that face is incident *down* to edges `${edgesOfFace.joinToString(", ")}`
- **edge ${anEdge.value}** is incident *down* to vertices `${t.incidentDown(anEdge).joinToString(", ")}`
  and *up* to faces `${t.incidentUp(anEdge).joinToString(", ")}`
- Euler characteristic over the proper ranks: **${t.eulerCharacteristic()}**
- `validate()` clean: **${t.validate().isValid}** ·
  `validate(checkManifold = true)` clean: **${t.validate(checkManifold = true).isValid}**

Not one coordinate was consulted above.
""", width = 720)
:::endfold
```

![A table of the cube's four ranks with counts 8, 12, 6, 1, followed by one face's ordered vertex cycle, the edges it is incident to, one edge's two endpoint vertices and two incident faces, Euler characteristic 2, and both validation passes reporting true](assets/polytope_examples/topology_readout.png)

Element ids are **globally scoped within one topology**, not per-rank indices — that is why the
cube's first face is element 20 rather than face 0. An id identifies *this element of this
topology*, so an incidence array can mix ranks without ambiguity and provenance can name a source
element without also having to say which array it was in.

Two details in that read-out are load-bearing:

- **Incidence is reciprocal and adjacent-rank only.** There is no vertex→face incidence stored; you
  walk. `validate()` checks reciprocity, edge endpoint counts, that every face cycle vertex really
  is on one of the face's edges, that every incident edge occurs in the cycle, and that the
  boundary is a single connected degree-2 loop.
- **Manifoldness is an opt-in stricter pass**, not a precondition. `validate(checkManifold = true)`
  additionally requires every edge to have exactly two faces. Non-manifold structures stay legal at
  the core level, because the core is not only for closed surfaces.

## Coordinates are the second layer, and they are optional

`PolyhedralObject` is `topology` + a **nullable** `Realization3` + element attributes. `Realization3`
is an immutable ownership boundary around Field's mutable `Vec3`: public reads hand back copies, and
coordinates are indexed in exactly the same order as `topology.elements(0)`.

The honest way to show that the topology really is independent is to take the coordinates away and
watch which operations still work.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/abstract_dual.png" wait="1.5" capture="box" open-outputs="true" w="980" h="520" padding="14"}
:::static
import afield.stdlib.polytope.*

// Drop the cube's coordinates on purpose. The topology survives verbatim.
val abstractCube = PolyhedralObject(cube().topology)
val d = abstractCube.transform(ConwayOperator.DUAL).polyhedron

// Ask it for geometry anyway, and keep whatever it says.
val refusal = try { d.toFaceFLines(); "no exception — unexpected" }
    catch (e: Throwable) { "${e::class.simpleName}: ${e.message}" }
val triRefusal = try { d.triangulate(); "no exception — unexpected" }
    catch (e: Throwable) { "${e::class.simpleName}: ${e.message}" }

// Now, and only now, choose a policy that says where the dual's vertices go.
val rd = cube().dual(DualCentroidRealization).polyhedron

:::fold lay the two columns side by side
markdown("dual", """
#### A dual with no coordinates at all

| | abstract dual | dual + `DualCentroidRealization` |
|---|---|---|
| `polyhedron.realization` | `${d.realization}` | `Realization3(${rd.realization?.size} vertices)` |
| topology counts | ${d.topology.elements(0).size}v ${d.topology.elements(1).size}e ${d.topology.elements(2).size}f | ${rd.topology.elements(0).size}v ${rd.topology.elements(1).size}e ${rd.topology.elements(2).size}f |
| closed manifold | ${d.topology.validate(checkManifold = true).isValid} | ${rd.topology.validate(checkManifold = true).isValid} |
| isomorphic to `octahedron()` | ${d.topology.isCombinatoriallyIsomorphicTo(octahedron().topology)} | ${rd.topology.isCombinatoriallyIsomorphicTo(octahedron().topology)} |
| `toFaceFLines()` | `$refusal` | ${rd.toFaceFLines().size} FLines |
| `triangulate()` | `$triRefusal` | ${rd.triangulate().triangleCount} triangles |

The left column is a complete, valid, checkable octahedron. It simply has nowhere to be.
""", width = 900)
:::endfold
```

![A two-column table. The abstract dual has realization null, counts 6v 12e 8f, closed manifold true, isomorphic to octahedron true, and both toFaceFLines and triangulate refusing with IllegalArgumentException FLine conversion requires a 3D realization and IllegalStateException triangulation requires a 3D realization. The realized column has the same topology plus a six-vertex Realization3, eight FLines and eight triangles](assets/polytope_examples/abstract_dual.png)

Both refusals are real caught exceptions, not descriptions of them. That is the whole point of the
column: the combinatorial claims — valid, closed, isomorphic to an octahedron — are all *decidable*
with no coordinates in the object, and the geometry-shaped calls fail loudly rather than inventing a
placeholder embedding.

`isCombinatoriallyIsomorphicTo` is deliberately small: it is a rank-3 helper that tries vertex
bijections filtered by a degree/face-size signature and then compares edge and face multisets up to
rotation and reversal. It is not a general incidence-poset graph-isomorphism solver, and it says so.

## The operator vocabulary

Thirteen operators. Five are primitives implemented directly on the topology; eight are named
composites that rewrite into primitives before anything runs. This table is generated from
`ConwayOperator.entries` and `primitiveExpansion`, so it cannot drift from the enum.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/operator_table.png" wait="1.5" capture="box" open-outputs="true" w="860" h="640" padding="14"}
:::static
import afield.stdlib.polytope.*

val rows = ConwayOperator.entries.joinToString("\n") { op ->
    // primitiveExpansion is in EXECUTION order (seed outward). Prefix notation writes it
    // the other way round, so reverse it to get the string you would actually type.
    val execution = op.primitiveExpansion.joinToString("") { it.symbol.toString() }
    val written = op.primitiveExpansion.reversed().joinToString("") { it.symbol.toString() }
    "| `${op.symbol}` | ${op.displayName} | ${if (op.primitive) "primitive" else "composite"} " +
        "| `$execution` | `${written}C` |"
}
markdown("ops", """
#### `ConwayOperator.entries`

| letter | name | kind | primitives, seed outward | equivalent notation |
|---|---|---|---|---|
$rows
""", width = 780)
```

![A thirteen-row table listing d dual, a ambo, k kis, t truncate and g gyro as primitives expanding to themselves, then j join, n needle, z zip, o ortho, e expand, s snub, m meta and b bevel as composites with their primitive expansions and equivalent notations daC, kdC, dkC, jjC, aaC, dgdC, kjC and taC](assets/polytope_examples/operator_table.png)

Read the last two columns together and the direction convention becomes obvious.
`primitiveExpansion` is in **execution order, seed outward** — `join` runs *ambo* and then *dual* —
while prefix notation writes the outermost operator first, so you type `daC`. The library keeps both
orders and reports both: `notationHistory` is what you asked for, `history` is what actually ran.

### The seeds

Five Platonic seeds, each a fully realized `PolyhedralObject`. All the pictures below are Field's own
CPU mesh rasteriser (`TriMesh.renderPng`) fed with `toTriMesh()`; the captions under each are read
off the **topology**, not off the mesh.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/seeds_gallery.png" wait="2.5" capture="box" open-outputs="true" w="1240" h="820" padding="14"}
:::static
import afield.stdlib.polytope.*
import java.util.Base64

:::fold one small gallery helper, reused by the two galleries below
fun Box.gallery(channel: String, columns: Int, cell: Int, panels: List<Pair<String, PolyhedralObject>>) {
    val view = MeshView(width = cell, height = (cell * 0.84).toInt(), wireframe = false, distance = 2.3)
    display(channel, buildString {
        append("<div style='display:grid;grid-template-columns:repeat($columns,minmax(0,1fr));gap:10px'>")
        for ((label, p) in panels) {
            val t = p.topology
            val png = Base64.getEncoder().encodeToString(p.toTriMesh().renderPng(view, caption = "", title = null))
            append("<div><div style='color:#e6ecf4;font-weight:600;font-family:monospace;font-size:13px;padding-bottom:3px'>$label</div>")
            append("<img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/>")
            append("<div style='color:#93a4ba;font-family:monospace;font-size:11px;padding-top:4px'>")
            append("${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f · ")
            append(t.elements(2).groupingBy { t.faceCycle(it.id).size }.eachCount().toSortedMap()
                .entries.joinToString(" ") { "${it.value}×${it.key}-gon" })
            append(" · &chi;=${t.eulerCharacteristic()}</div></div>")
        }
        append("</div>")
    })
}
:::endfold

gallery("seeds", columns = 3, cell = 380, panels = listOf(
    "T  tetrahedron" to tetrahedron(),
    "C  cube" to cube(),
    "O  octahedron" to octahedron(),
    "I  icosahedron" to icosahedron(),
    "D  dodecahedron" to dodecahedron(),
))
```

![Five shaded polyhedra — tetrahedron, cube, octahedron, icosahedron, dodecahedron — each captioned with its vertex, edge and face counts, its face-degree histogram, and Euler characteristic 2](assets/polytope_examples/seeds_gallery.png)

`cube()` is the odd one out and it is worth knowing: the other four place their vertices on the unit
sphere, while `cube()` has side length 2, so its vertices sit at radius √3. That matters later,
because the realization solver's default targets are derived from the *incoming* mean edge length
and mean radius.

### The five primitives

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/primitives_gallery.png" wait="3.0" capture="box" open-outputs="true" w="1240" h="800" padding="14"}
:::static
import afield.stdlib.polytope.*
import java.util.Base64

:::fold the same gallery helper as above
fun Box.gallery(channel: String, columns: Int, cell: Int, panels: List<Pair<String, PolyhedralObject>>) {
    val view = MeshView(width = cell, height = (cell * 0.84).toInt(), wireframe = false, distance = 2.3)
    display(channel, buildString {
        append("<div style='display:grid;grid-template-columns:repeat($columns,minmax(0,1fr));gap:10px'>")
        for ((label, p) in panels) {
            val t = p.topology
            val png = Base64.getEncoder().encodeToString(p.toTriMesh().renderPng(view, caption = "", title = null))
            append("<div><div style='color:#e6ecf4;font-weight:600;font-family:monospace;font-size:13px;padding-bottom:3px'>$label</div>")
            append("<img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/>")
            append("<div style='color:#93a4ba;font-family:monospace;font-size:11px;padding-top:4px'>")
            append("${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f · ")
            append(t.elements(2).groupingBy { t.faceCycle(it.id).size }.eachCount().toSortedMap()
                .entries.joinToString(" ") { "${it.value}×${it.key}-gon" })
            append(" · &chi;=${t.eulerCharacteristic()}</div></div>")
        }
        append("</div>")
    })
}
:::endfold

// One seed, five primitives. `conway(...)` parses and evaluates in one call.
gallery("primitives", columns = 3, cell = 380, panels = listOf(
    "C    the seed" to conway("C").polyhedron,
    "dC   dual" to conway("dC").polyhedron,
    "aC   ambo" to conway("aC").polyhedron,
    "kC   kis" to conway("kC").polyhedron,
    "tC   truncate" to conway("tC").polyhedron,
    "gC   gyro" to conway("gC").polyhedron,
))
```

![Six shaded polyhedra in two rows: the cube, its dual the octahedron, the ambo cuboctahedron, the kis tetrakis hexahedron with a pyramid on every square, the truncated cube with octagons and triangles, and the gyro pentagonal icositetrahedron made entirely of pentagons — each captioned with counts and its face-degree histogram](assets/polytope_examples/primitives_gallery.png)

Each caption's face-degree histogram tells you what the operator did combinatorially:

- **`d` dual** swaps vertices and faces and leaves edges alone: the cube's 8/12/6 becomes 6/12/8.
- **`a` ambo** puts a vertex at every edge midpoint; the new faces are one per old face *plus* one
  per old vertex, so `aC` has 6 squares and 8 triangles.
- **`k` kis** raises an apex over every face and fans it into triangles.
- **`t` truncate** cuts every vertex off; the old faces grow to twice as many sides (squares →
  octagons) and each cut leaves a new face.
- **`g` gyro** is the genuinely interesting one — a real topology transform, not a fake-up: every
  *directed* half-edge becomes a pentagon, so `gC` is 24 pentagons and nothing else.

The `gC` panel is the one to stop at, because it looks like a bug and is not. Its caption says
38 vertices, 60 edges and 24 pentagons, and its picture is a cube. Both are true:
`GyroSubdivisionRealization` places the new directed-edge points *along* the existing edges and the
new face centres *at* the existing face centroids, so every one of those 24 pentagons lies flat in
one of the cube's six original square planes. Flat shading has nothing to shade differently. The
combinatorics changed completely; the embedding did not move a single point off the surface.

Draw the polygon boundaries instead of the shading and the 24 pentagons appear:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/gyro_boundaries.png" wait="2.5" capture="box" open-outputs="true" w="960" h="700" padding="14"}
:::static
import afield.stdlib.polytope.*
import afield.stdlib.TriMesh
import java.util.Base64

// Shading cannot separate coplanar faces, so give each polygon its own colour instead. The
// triangle -> source-polygon map is what makes that possible on a triangle mesh at all.
val gyro = conway("gC").polyhedron
val g = gyro.triangulate()
val faceOrder = gyro.topology.elements(2).mapIndexed { i, f -> f.id.value to i }.toMap()

val colors = FloatArray(g.mesh.vertexCount * 3)
for (tri in 0 until g.triangleCount) {
    // A hue wheel over the 24 pentagons; nothing here knows or cares where they are in space.
    val turn = faceOrder.getValue(g.sourceFace(tri).value) * 2.0 * Math.PI / faceOrder.size
    for (k in 0..2) {
        val vi = g.mesh.indices[tri * 3 + k]
        colors[vi * 3] = (0.55 + 0.42 * Math.cos(turn)).toFloat()
        colors[vi * 3 + 1] = (0.55 + 0.42 * Math.cos(turn - 2.09)).toFloat()
        colors[vi * 3 + 2] = (0.55 + 0.42 * Math.cos(turn + 2.09)).toFloat()
    }
}
val painted = TriMesh(g.mesh.positions, g.mesh.indices, g.mesh.normals, null, colors)

:::fold one figure with its own caption
val png = Base64.getEncoder().encodeToString(painted.renderPng(
    MeshView(wireframe = false, width = 860, height = 560, distance = 2.4), caption = "", title = null,
))
// The claim, measured rather than asserted: 24 faces sharing only 6 distinct outward normals.
val distinctNormals = gyro.topology.elements(2)
    .map { gyro.faceNormal(it.id) }
    .map { "%.3f,%.3f,%.3f".format(it.x, it.y, it.z) }
    .distinct()

display("gyro", """
    <img src='data:image/png;base64,$png' style='width:100%;border-radius:6px;display:block'/>
    <div style='color:#93a4ba;font:12px monospace;padding-top:8px;line-height:1.6'>
      gC &middot; ${gyro.topology.elements(2).size} faces, all
      ${gyro.topology.elements(2).map { gyro.topology.faceCycle(it.id).size }.distinct().joinToString("/")}-gons
      &middot; ${distinctNormals.size} distinct face normals: ${distinctNormals.joinToString(" ") { "($it)" }}
    </div>
""".trimIndent())
:::endfold
```

![A shaded cube whose six square faces are each divided into four differently coloured pentagons meeting at the face centre, twenty-four in all. The caption reports 24 faces, all 5-gons, with only 6 distinct face normals — the six axis directions of the original cube](assets/polytope_examples/gyro_boundaries.png)

That is the separation the library is built around, in one picture: **the operator changed the
topology, and a realization policy — a different, replaceable argument — decided that the new
vertices should stay on the old surface.** Hand `gyro` a different `RealizationPolicy3` and the same
24 pentagons can be anywhere you like.

### The named composites

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/composites_gallery.png" wait="3.5" capture="box" open-outputs="true" w="1240" h="800" padding="14"}
:::static
import afield.stdlib.polytope.*
import java.util.Base64

:::fold the same gallery helper as above
fun Box.gallery(channel: String, columns: Int, cell: Int, panels: List<Pair<String, PolyhedralObject>>) {
    val view = MeshView(width = cell, height = (cell * 0.84).toInt(), wireframe = false, distance = 2.3)
    display(channel, buildString {
        append("<div style='display:grid;grid-template-columns:repeat($columns,minmax(0,1fr));gap:10px'>")
        for ((label, p) in panels) {
            val t = p.topology
            val png = Base64.getEncoder().encodeToString(p.toTriMesh().renderPng(view, caption = "", title = null))
            append("<div><div style='color:#e6ecf4;font-weight:600;font-family:monospace;font-size:13px;padding-bottom:3px'>$label</div>")
            append("<img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/>")
            append("<div style='color:#93a4ba;font-family:monospace;font-size:11px;padding-top:4px'>")
            append("${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f · ")
            append(t.elements(2).groupingBy { t.faceCycle(it.id).size }.eachCount().toSortedMap()
                .entries.joinToString(" ") { "${it.value}×${it.key}-gon" })
            append(" · &chi;=${t.eulerCharacteristic()}</div></div>")
        }
        append("</div>")
    })
}
:::endfold

gallery("composites", columns = 4, cell = 290, panels = listOf(
    "jC   join = da" to conway("jC").polyhedron,
    "nC   needle = kd" to conway("nC").polyhedron,
    "zC   zip = dk" to conway("zC").polyhedron,
    "oC   ortho = jj" to conway("oC").polyhedron,
    "eC   expand = aa" to conway("eC").polyhedron,
    "sC   snub = dgd" to conway("sC").polyhedron,
    "mC   meta = kj" to conway("mC").polyhedron,
    "bC   bevel = ta" to conway("bC").polyhedron,
))
```

![Eight shaded polyhedra in two rows of four: join, needle, zip, ortho, expand, snub, meta and bevel applied to the cube, each captioned with its element counts, face-degree histogram and Euler characteristic 2](assets/polytope_examples/composites_gallery.png)

Composites are *rewritten*, not reimplemented — `conway("bC")` runs `ambo` and then `truncate`, and
the result reports exactly that. Three of the thirteen (`n` needle, `z` zip, `o` ortho) currently
have no named Kotlin extension function; they are reachable through the parser and through
`transform(ConwayOperator.NEEDLE)`, which is why every panel above uses the notation form.

## Prefix notation, and what it refuses

`parseConwayExpression` parses; `conway` parses and evaluates. Whitespace is ignored anywhere, the
seed must be the final character, and evaluation is right-to-left — `daC` is `d(a(C))`. Only `k` and
`t` accept a degree index; `kN` restricts kis to faces of degree *N* and `tN` restricts truncate to
vertices of degree *N*.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/parser_table.png" wait="2.0" capture="box" open-outputs="true" w="1100" h="620" padding="14"}
:::static
import afield.stdlib.polytope.*

fun row(src: String): String = try {
    val e = parseConwayExpression(src)
    val r = e.evaluate()
    val t = r.polyhedron.topology
    "| `$src` | `${e.seed.symbol}` " +
        "| ${e.invocations.joinToString(" then ") { i -> i.operator.displayName + (i.degree?.let { "($it)" } ?: "") }.ifEmpty { "— (the bare seed)" }} " +
        "| ${r.history.joinToString("") { it.symbol.toString() }.ifEmpty { "—" }} " +
        "| ${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f |"
} catch (ex: ConwayParseException) {
    "| `$src` | — | **${ex.message}** | — | — |"
}

val cases = listOf("C", "dC", "daC", "adC", "bC", "k4aC", "t4kC", "sD", "qC", "a4C", "4kC", "k2C", "dx")
markdown("parser", """
| written | seed | invocations, seed outward | primitives run | output |
|---|---|---|---|---|
${cases.joinToString("\n") { row(it) }}
""", width = 1020)
```

![A thirteen-row table. The first eight rows parse successfully, showing that daC runs ambo then dual while adC runs dual then ambo, that bC expands to at, that k4aC and t4kC carry degree selectors, and that sD produces sixty vertices, a hundred and fifty edges and ninety-two faces. The last five rows show the parser's own error messages for an unknown operator q, a degree on ambo, a leading digit, a kis degree below three, and a missing seed](assets/polytope_examples/parser_table.png)

Compare rows two and three: `daC` and `adC` are different objects, 14v/24e/12f against 12v/24e/14f,
which is the clearest possible demonstration that the notation is read right-to-left. The last five
rows are the parser's real messages, including the byte offset it failed at.

## Provenance: every output element remembers where it came from

This is the part a mesh cannot give you. Each `PolytopeTransformResult` carries, for every output
element, an `ElementLineage` tree: either `Source` (an element of the original seed) or `Derived`
with the operation, a **role** string, and its parents. `sourceElements(id)` flattens the tree back
to the seed.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/lineage.png" wait="2.0" capture="box" open-outputs="true" w="1000" h="560" padding="14"}
:::static
import afield.stdlib.polytope.*

val result = conway("bC")   // bevel = ambo then truncate

fun describe(l: ElementLineage): String = when (l) {
    is ElementLineage.Source -> "seed r${l.element.rank}#${l.element.id.value}"
    is ElementLineage.Derived ->
        "${l.operation.displayName}/${l.role}(" + l.parents.joinToString(", ") { describe(it) } + ")"
}

val faces = result.polyhedron.topology.elements(2)
val roles = faces.groupingBy { (result.lineage(it.id) as ElementLineage.Derived).role }.eachCount().toSortedMap()
val sample = faces.first()

:::fold format
markdown("lineage", """
#### `conway("bC")` — what every output face remembers

- written: `${result.notationHistory.joinToString("") { it.symbol.toString() }}`
  (${result.notationHistory.joinToString { it.displayName }})
- primitives actually run, seed outward: `${result.history.joinToString("") { it.symbol.toString() }}`
- output: ${result.polyhedron.topology.elements(0).size}v ${result.polyhedron.topology.elements(1).size}e ${faces.size}f

| output face role | count |
|---|---|
${roles.entries.joinToString("\n") { "| `${it.key}` | ${it.value} |" }}

Output face **${sample.id.value}**, in full:

~~~
${describe(result.lineage(sample.id))}
~~~

flattened to seed elements: ${result.sourceElements(sample.id).joinToString { "rank ${it.rank} / id ${it.id.value}" }}
""", width = 920)
:::endfold
```

![A card showing that bC was written as b bevel, ran the primitives a then t, and produced 48 vertices, 72 edges and 26 faces; a role histogram of 14 truncated-face and 12 vertex-face; the full lineage of one output face reading truncate/truncated-face of ambo/original-face of seed rank-2 element 20; and that face flattening to a single seed element](assets/polytope_examples/lineage.png)

The role histogram is the useful shape of this. After a bevel, 14 of the 26 faces descend from the
cube's own faces and vertices via `ambo`, and 12 are the faces the truncation cut. You did not have
to guess that from geometry; you asked.

The roles the primitives emit are a small fixed vocabulary — `face-center`, `vertex-face`,
`edge-midpoint`, `original-face`, `original-vertex`, `face-apex`, `kis-triangle`,
`vertex-edge-point`, `truncated-face`, `partially-truncated-face`, `directed-edge-point`,
`gyro-pentagon` — and they are addressable, which is the next section.

## Selectors: naming a set of elements without naming indices

`ElementSelector` is a SAM predicate over an `ElementSelectionContext` (the object, the element, and
its lineage when there is one). `ElementSelectors` supplies the useful ones and they compose with
`and`, `or` and `!`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/selectors.png" wait="2.0" capture="box" open-outputs="true" w="1020" h="560" padding="14"}
:::static
import afield.stdlib.polytope.*

val kisCube = cube().kis()          // apex over every square

val cases = listOf<Pair<String, ElementSelector>>(
    "ElementSelectors.rank(0)" to ElementSelectors.rank(0),
    "ElementSelectors.rank(2)" to ElementSelectors.rank(2),
    "faceDegree(3)" to ElementSelectors.faceDegree(3),
    "vertexDegree(4)" to ElementSelectors.vertexDegree(4),
    "vertexDegree(6)" to ElementSelectors.vertexDegree(6),
    "provenanceRole(\"face-apex\")" to ElementSelectors.provenanceRole("face-apex"),
    "provenanceRole(\"original-vertex\")" to ElementSelectors.provenanceRole("original-vertex"),
    "vertexDegree(4) and !provenanceRole(\"face-apex\")" to
        (ElementSelectors.vertexDegree(4) and !ElementSelectors.provenanceRole("face-apex")),
)

// Two receivers, deliberately different: on a PolyhedralObject there is no lineage to consult,
// so the provenance selectors match nothing. On a transform result they can see it.
val rows = cases.joinToString("\n") { (label, s) ->
    "| `$label` | ${kisCube.polyhedron.selectElements(s).size} | ${kisCube.selectElements(s).size} |"
}
markdown("selectors", """
#### `cube().kis()` — 14 vertices, 36 edges, 24 triangles

| selector | matches on the `PolyhedralObject` | matches on the `PolytopeTransformResult` |
|---|---|---|
$rows

The two columns differ exactly where a selector needs lineage.
""", width = 940)
```

![A table of eight selectors evaluated against a kis-ed cube on both a PolyhedralObject and a PolytopeTransformResult. Rank, face-degree and vertex-degree selectors give identical counts in both columns, while the two provenanceRole selectors match nothing on the bare object and match six face apexes and eight original vertices on the transform result. The final composed row differs the other way, matching six on the bare object and none on the transform result](assets/polytope_examples/selectors.png)

That difference is the design, not a wart. A selector runs against a context, and a bare
`PolyhedralObject` has no transform history to have a role in. Ask a transform result and lineage is
there.

The last row is worth a second look, because it is the trap. `vertexDegree(4)` matches exactly the
six apexes kis raised. Negating a `provenanceRole` selector on the *bare object* negates a predicate
that matched nothing, so `!provenanceRole(...)` is vacuously true and the composed selector still
matches all six. Ask the transform result and it correctly matches none. A negated provenance test is
only meaningful where lineage exists.

## Deployed parameters: a scalar field over the selected elements

Selection says *where*; an `ElementDoubleParameter` says *how much*, per element. The selective
`kis`, `truncate` and `gyro` overloads take both, evaluate the parameter once per selected source,
and record what they resolved in a `TransformDeployment` — so the operation is inspectable and
replayable after the fact.

Below, a scalar is written onto each of a dodecahedron's twelve faces as an ordinary attribute, kis
is deployed with `ElementParameters.attribute("spike")`, and then — the part that matters — the same
scalar is painted back onto the *rendered triangles* by following `triangulate()`'s
triangle → source-polygon map.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/parameter_field.png" wait="3.0" capture="box" open-outputs="true" w="900" h="760" padding="14"}
:::static
import afield.stdlib.polytope.*
import afield.stdlib.TriMesh
import java.util.Base64

// One scalar per face of a dodecahedron: spike height rises from left to right.
var source = dodecahedron()
val realization = source.realization!!
val index = source.topology.elements(0).mapIndexed { i, v -> v.id.value to i }.toMap()
for (f in source.topology.elements(2)) {
    val x = source.topology.faceCycle(f.id).map { realization[index.getValue(it)].x }.average()
    source = source.withAttribute(f.id, "spike", 0.08 + 0.80 * (x + 1.0) / 2.0)
}

// Selection and parameter are separate arguments, on purpose.
val result = source.kis(ElementSelectors.rank(2), ElementParameters.attribute("spike"))
val out = result.polyhedron
val g = out.triangulate()

val deployed = result.deployments.single().scalarParameters
val values = deployed.values.mapNotNull { it["heightScale"] }
val lo = values.min()
val hi = values.max()

// Paint the deployed scalar back onto the render mesh. This is only possible because
// triangulate() kept sourceFace(triangle) -> the polygon each triangle came from.
val colors = FloatArray(g.mesh.vertexCount * 3)
for (tri in 0 until g.triangleCount) {
    val v = (out.attributes[g.sourceFace(tri)]["spike"] as? Number)?.toDouble() ?: lo
    val u = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    for (k in 0..2) {
        val vi = g.mesh.indices[tri * 3 + k]
        colors[vi * 3] = (0.20 + 1.10 * u).toFloat()
        colors[vi * 3 + 1] = (0.86 - 0.30 * u).toFloat()
        colors[vi * 3 + 2] = (1.00 - 0.78 * u).toFloat()
    }
}
val painted = TriMesh(g.mesh.positions, g.mesh.indices, g.mesh.normals, null, colors)

:::fold one figure, with its own caption, so nothing is read off a mesh-level summary
val png = Base64.getEncoder().encodeToString(painted.renderPng(
    MeshView(wireframe = false, width = 820, height = 560, distance = 2.3, yawDegrees = 0.0, pitchDegrees = 6.0),
    caption = "", title = null,
))
display("field", """
    <img src='data:image/png;base64,$png' style='width:100%;border-radius:6px;display:block'/>
    <div style='color:#93a4ba;font:12px monospace;padding-top:8px;line-height:1.6'>
      kis deployed over ${deployed.size} faces &middot;
      heightScale resolved ${"%.3f".format(lo)} &hellip; ${"%.3f".format(hi)}<br/>
      output ${out.topology.elements(0).size}v ${out.topology.elements(1).size}e ${out.topology.elements(2).size}f
      &middot; ${g.triangleCount} triangles over ${g.triangleFaceIds.toSet().size} source polygons
    </div>
""".trimIndent())
:::endfold
```

![A shaded spiked dodecahedron. The pyramids on the left-hand faces are short and coloured cyan, those on the right are long and coloured orange, with a smooth gradient across the middle. Its caption reports kis deployed over 12 faces with heightScale resolved from 0.210 to 0.750, and an output of 32 vertices, 90 edges and 60 faces made of 60 triangles over 60 source polygons](assets/polytope_examples/parameter_field.png)

Three separate facts are visible in that one picture:

1. **The parameter really is non-uniform** — the spikes get longer left to right, and the printed
   range is the range the deployment record resolved, not a range chosen for the caption.
2. **The colour survived the trip through triangulation.** The attribute lives on the *polygon*.
   Triangles are derived. Painting the field onto the render mesh only works because
   `TriangulatedGeometry` kept `sourceFace(triangle)`.
3. **Selection and parameter are orthogonal.** Here the selector is "every face", and only the
   scalar varies. Restrict the selector and you get selective *and* non-uniform kis, which is what
   the loadable demo at the bottom of this page does.

## Realization: repair, not canonicalisation

`solveRealization()` runs a pure-Kotlin nonlinear least-squares pass over the vertex coordinates.
Five weighted residual families: edge lengths toward a common target, face planarity against each
face's own best-fit plane, the centroid pinned where it started, a common radius, and a weak tether
back to the input embedding. Topology and attributes are untouched — the result is
`copy(realization = solved)` and nothing else.

!!! warning "What this is not"

    Quoting the source, which is careful about it:

    > *This is a general constraint relaxation, not a claim of a unique Conway "canonical form". The
    > defaults are useful for the symmetric finite polyhedra produced here. Disable common-radius or
    > common-edge constraints for deliberately irregular objects.*

    Levenberg–Marquardt from the supplied embedding is a **local** method, and the `referenceWeight`
    term deliberately biases it toward where it started. There is no symmetry detection, no midsphere
    or tangency condition, and nothing checks for self-intersection or inversion — degenerate faces
    are *reported*, not repaired. The distinction the architecture note wants to keep visible is
    `realize` / `relax` / `canonicalize`, and only the first two exist today.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/realization.png" wait="3.0" capture="box" open-outputs="true" w="1220" h="780" padding="14"}
:::static
import afield.stdlib.polytope.*
import afield.stdlib.Vec3
import java.util.Base64
import kotlin.random.Random

// Take a real Conway output and jitter every vertex. The topology is not touched at all.
val clean = conway("aC").polyhedron
val rng = Random(7)
val jittered = clean.copy(realization = Realization3(clean.realization!!.vertexCoordinates.map {
    Vec3(it).add(
        (rng.nextDouble() - 0.5).toFloat() * 0.62f,
        (rng.nextDouble() - 0.5).toFloat() * 0.62f,
        (rng.nextDouble() - 0.5).toFloat() * 0.62f,
    )
}))

val solved = jittered.solveRealization()

:::fold render the three embeddings and their diagnostics as one figure
val view = MeshView(width = 400, height = 340, wireframe = false, distance = 2.5)
fun panel(label: String, p: PolyhedralObject): String {
    val png = Base64.getEncoder().encodeToString(p.toTriMesh().renderPng(view, caption = "", title = null))
    return "<div><div style='color:#e6ecf4;font:600 13px monospace;padding-bottom:3px'>$label</div>" +
        "<img src='data:image/png;base64,$png' style='width:100%;border-radius:5px;display:block'/></div>"
}
fun row(label: String, d: RealizationDiagnostics) =
    ("<tr><td style='padding:2px 14px 2px 0'>%s</td><td>%.5f</td><td>%.3e</td>" +
        "<td>%.3e</td><td>%.3e</td><td>%d</td></tr>").format(
        label, d.edgeLengthMean, d.edgeLengthRmsDeviation, d.maxFacePlanarityError,
        d.facePlanarityRmsError, d.degenerateFaces.size)

display("solve", """
    <div style='display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px'>
      ${panel("aC as built", clean)}
      ${panel("every vertex jittered", jittered)}
      ${panel("solveRealization()", solved.polyhedron)}
    </div>
    <table style='color:#c3ccd9;font:12px monospace;border-collapse:collapse;padding-top:10px;margin-top:10px'>
      <thead style='color:#6d7787'><tr>
        <th style='text-align:left;padding-right:14px'>embedding</th><th>edge mean</th>
        <th>edge RMS dev</th><th>max planarity err</th><th>planarity RMS</th><th>degenerate</th>
      </tr></thead>
      <tbody>
        ${row("aC as built", clean.geometryDiagnostics())}
        ${row("jittered", solved.initialDiagnostics)}
        ${row("after solveRealization()", solved.finalDiagnostics)}
      </tbody>
    </table>
    <div style='color:#93a4ba;font:12px monospace;padding-top:8px'>
      converged = ${solved.converged} &middot; iterations = ${solved.iterations} &middot;
      rmsResidual = ${"%.3e".format(solved.rmsResidual)} &middot; reason = ${solved.reason} &middot;
      topology object identical = ${solved.polyhedron.topology === jittered.topology}
    </div>
""".trimIndent())
:::endfold
```

![Three shaded cuboctahedra side by side — the clean one, a visibly lumpy jittered one, and the solved one which is regular again — above a table showing edge RMS deviation falling from 2.741e-01 to near zero and max planarity error falling from 1.293e-01 to near zero, with the solver converging in five iterations on the gradient criterion and the topology object being identical](assets/polytope_examples/realization.png)

Read the table, not the pictures: the pictures only tell you the shape came back, whereas the
diagnostics say by how much. Note the middle row is `solved.initialDiagnostics` and the last is
`solved.finalDiagnostics` — the solver measures its own input and output, so a solve that did
nothing useful would say so rather than being invisible.

Note also that the recovered edge mean is the *jittered* mean, not the original: with
`targetEdgeLength = null` the target is whatever the input's mean edge length happened to be. The
solver regularises the shape it was given; it does not know what shape you wish you had given it.

## Into Field: one FLine per face

`toFaceFLines()` produces one closed, outward-wound `FLine` per polygonal face, with the face's id,
rank and propagated attributes attached as line properties. Not a triangle mesh with a face id
bolted on — the polygons *are* the output, so Field's ordinary `filled`, `stroked`, `hoverColor` and
`onClick` all work per face with no parallel rendering model.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/face_flines.png" wait="3.0" capture="box" open-outputs="true" w="1240" h="700" padding="14"}
:::static
import afield.stdlib.polytope.*

// Truncate four of an ambo-ed cube's twelve vertices, then colour every output face by the
// role its lineage reports. Nothing here inspects a coordinate to decide a colour.
val seed = conway("aC")
val chosen = seed.polyhedron.topology.elements(0).filterIndexed { i, _ -> i % 3 == 0 }.map { it.id }
val result = seed.truncate(ElementSelectors.ids(chosen), ElementParameters.constant(0.30))
val out = result.polyhedron

val palette = mapOf(
    "original-face" to vec(0.30, 0.82, 0.96, 0.30),
    "truncated-face" to vec(1.00, 0.62, 0.24, 0.34),
    "partially-truncated-face" to vec(0.72, 0.58, 1.00, 0.32),
    "vertex-face" to vec(0.55, 0.95, 0.62, 0.32),
)
val faceLines = out.toFaceFLines { face, line ->
    val role = (result.lineage(face.id) as ElementLineage.Derived).role
    line.filled = true
    line.stroked = true
    line.thickness = 1.6
    line.color = palette[role] ?: vec(0.5, 0.5, 0.55, 0.25)
    line.hoverColor = vec(0.96, 0.98, 1.00, 0.95)
}

threeLines(faceLines, channel = "faces", name = "role-coloured faces", height = 460,
    autoFrame = true, editable = false)
displayOpen("faces")
```

![An orbitable Three.js view of a partially truncated cuboctahedron drawn as filled, stroked polygons, with faces tinted purple, green and teal according to the lineage role each one reports](assets/polytope_examples/face_flines.png)

That view is the live `threeLines(...)` viewer, so in a real box you can orbit it, hover a face and —
if you set `onClick` — pick one and read its provenance back. The
[loadable demo](#a-loadable-demo) at the foot of this page does exactly that.

What each of those lines is carrying, from the same transform:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/face_fline_properties.png" wait="2.0" capture="box" open-outputs="true" w="1020" h="480" padding="14"}
:::static
import afield.stdlib.polytope.*

val seed = conway("aC")
val chosen = seed.polyhedron.topology.elements(0).filterIndexed { i, _ -> i % 3 == 0 }.map { it.id }
val result = seed.truncate(ElementSelectors.ids(chosen), ElementParameters.constant(0.30))
val out = result.polyhedron
val faceLines = out.toFaceFLines()

val roles = out.topology.elements(2)
    .groupingBy { (result.lineage(it.id) as ElementLineage.Derived).role }.eachCount().toSortedMap()
val first = faceLines.first()

markdown("carried", """
| | |
|---|---|
| deployments recorded | ${result.deployments.joinToString(", ") { d -> "`${d.operation.displayName}` → " + (d.selectedSources?.let { "${it.size} selected" } ?: "whole natural domain (`null`)") }} |
| selected source vertices | ${result.deployments.last().selectedSources?.size} of ${seed.polyhedron.topology.elements(0).size} |
| output | ${out.topology.elements(0).size}v ${out.topology.elements(1).size}e ${out.topology.elements(2).size}f |
| `toFaceFLines()` returned | ${faceLines.size} FLines — one per polygonal face |
| roles present | ${roles.entries.joinToString(", ") { "`${it.key}` × ${it.value}" }} |
| properties on the first line | `${first.properties.filterKeys { it.startsWith("polytope") }}` |
| its node count | ${first.nodes.size} nodes for a ${out.topology.faceCycle(ElementId(first.properties[POLYTOPE_FACE_ID_PROPERTY] as Int)).size}-gon — the extra one is the `Close` marker |
""", width = 940)
```

![A table reporting two recorded deployments — ambo over its whole natural domain and truncate over four selected vertices — that four of twelve source vertices were selected, that the output is 24 vertices, 40 edges and 18 faces, that toFaceFLines returned 18 FLines, the role histogram of three original faces, eleven partially truncated faces and four vertex faces, the polytopeFaceId and polytopeFaceRank properties carried on the first line, and that line's node count against the face's polygon degree](assets/polytope_examples/face_fline_properties.png)

Both deployments are recorded, not just the selective one: a uniform operator's `selectedSources` is
`null`, which means "the operator's complete natural domain", and an explicit selection is a set. The
distinction survives into the replay record, so a chain can be re-run exactly.

### Triangles, derived last, that still know where they came from

`triangulate()` projects each face from its actual 3D plane by dropping its Newell normal's dominant
axis, runs Field's existing `FloatEarcut` in 2D, and flips any triangle whose winding disagrees with
the face. It **duplicates polygon corners between faces** on purpose: `TriMesh` stores normals per
vertex, and a creased polyhedron needs a different flat normal on each side of an edge.

That duplication has a consequence worth seeing rather than being told about.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/triangulation.png" wait="2.5" capture="box" open-outputs="true" w="1060" h="560" padding="14"}
:::static
import afield.stdlib.polytope.*

val p = conway("tC").polyhedron       // truncated cube: octagons and triangles
val g = p.triangulate()

// The polytope's own view of the object, and the render mesh's view of the same object.
markdown("tri", """
#### `conway("tC").polyhedron.triangulate()`

| | the polytope says | the render mesh says |
|---|---|---|
| vertices | ${p.topology.elements(0).size} abstract | ${g.mesh.vertexCount} render vertices |
| faces | ${p.topology.elements(2).size} polygons ${p.topology.elements(2).groupingBy { p.topology.faceCycle(it.id).size }.eachCount().toSortedMap().entries.joinToString(", ") { "(${it.value}×${it.key}-gon)" }} | ${g.triangleCount} triangles |
| Euler characteristic | ${p.topology.eulerCharacteristic()} | ${g.mesh.topology().eulerCharacteristic} |
| closed | ${p.topology.validate(checkManifold = true).isValid} | watertight = ${g.mesh.topology().isWatertight} |

Both are right. `triangulate()` duplicates every polygon corner so each face can carry its own flat
normal, so the *render* mesh is ${p.topology.elements(2).size} disconnected polygons. The topology
is the thing that knows the solid is closed.

- distinct source polygons across all triangles: **${g.triangleFaceIds.toSet().size}**
- distinct abstract vertices across all render vertices: **${g.vertexElementIds.toSet().size}**
- flat normals present: **${g.mesh.normals != null}**
- `mesh.summary()`: `${g.mesh.summary()}`
""", width = 980)
```

![A two-column table comparing the truncated cube as a polytope — 24 abstract vertices, 14 polygons made of 8 triangles and 6 octagons, Euler characteristic 2, closed — with the same object as a render mesh: many more render vertices, 44 triangles, a different Euler characteristic and not watertight. Below it, the counts of distinct source polygons and distinct abstract vertices recovered through the provenance arrays](assets/polytope_examples/triangulation.png)

If you only ever had the mesh you would have to conclude the truncated cube was fourteen loose
polygons with a boundary. The topology is not a lossy summary of the triangles; the triangles are a
lossy projection of the topology, and `sourceFace` / `sourceVertex` are the receipt.

`FanFaceTriangulator` remains available as an explicitly convex-only strategy. `EarcutFaceTriangulator`
is the default and handles simple concave faces; faces with holes need a richer boundary model than
the current single face cycle, and that is named as future work rather than quietly half-supported.

## The registered value printers

The mathematical types contain no HTML. `PolytopeVisualizers.kt` registers printers that reduce each
large immutable value to a public data class from `Summaries.kt` and then hand *that* to Field's
ordinary reflective visualizer. So the topology stays independent of presentation, and any of these
is a useful last expression in a live box.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/value_printers.png" wait="2.5" capture="box" open-outputs="true" w="1100" h="720" padding="14"}
:::static
import afield.stdlib.polytope.*

var seed = cube()
seed.topology.elements(2).take(2).forEach { seed = seed.withAttribute(it.id, "lift", 0.4) }
val result = seed.kis(ElementSelectors.attributeEquals("lift", 0.4), ElementParameters.attribute("lift"))

// Registered printers, not bespoke HTML: each of these reduces to a data class in Summaries.kt.
viz("inspection", listOf(result, result.polyhedron.triangulate()))
```

![Field's reflective value visualizer showing a PolytopeTransformSummary — a TransformDeploymentSummary naming the kis operation and a selected count of two, then the output PolyhedralObjectSummary with the lift attribute key, a Realization3Summary of ten vertices, and a PolytopeTopologySummary reporting closedManifold true, element counts 10, 20, 12, 1, Euler characteristic 2, face degree counts of eight triangles and four squares, and valid true — followed by a TriangulatedGeometrySummary of 40 render vertices, 16 triangles, 12 source polygon faces, 10 source vertex elements and flat normals present](assets/polytope_examples/value_printers.png)

The `…` links are the visualizer's ordinary lazy expansion for nested values, not a rendering
failure — click one in a live box and it fetches the rest.

`println(result.summary())` and `viz("inspection", result)` report the same underlying facts, because
they go through the same public summary functions. Useful last expressions:

```diffstatic {data-lang=kotlin}
:::static
polyhedron
polyhedron.topology
result
result.deployments.first()
polyhedron.triangulate()
polyhedron.solveRealization()
```

## A loadable demo

Two operation transcripts build a working document you can open, edit and re-run:

- **`dev_docs/agents/transcripts/polytope_conway_demo.yaml`** — one compact box that marks two
  opposite cube faces with different heights, applies `kis` only there, and sends the polygon faces
  to the on-canvas viewer. Hover a face, click it, and a provenance card reports the output face id,
  the transform role, the deployed height, the source elements and the 3D hit position.
- **`dev_docs/agents/transcripts/polytope_conway_gallery.yaml`** — the four questions this page is
  organised around, as four small boxes: a Conway-expression gallery you can retype, a topology-only
  dual shown honestly before a policy supplies coordinates, a non-uniform parameter field painted
  onto the output faces, and a realization before/after that leaves the diagnostics as its final
  value.

Replay either one from the repository root:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write polytope_conway_gallery --file dev_docs/agents/transcripts/polytope_conway_gallery.yaml' \
  --command 'macro run polytope_conway_gallery'
```

Both are exercised by the transcript execution smoke, so their box bodies cannot rot silently.

## What this does not do yet

Kept here because the architecture note is careful about it and a documentation page that quietly
drops the caveats is worse than no page:

- A face is a **single** ordered vertex cycle. No holes, no annuli, and star/self-intersecting
  polygons are not represented — pretending Earcut handles them would make their winding and filling
  rules ambiguous.
- Transform parameters are maps of `String` to `Double` in the deployment records. There are no typed
  parameter descriptors, domains, units or composition yet.
- Realization is `realize` and `relax`. There is no `canonicalize` with stated guarantees, no
  symmetry-orbit constraints and no Wythoff construction to supply a good initial embedding.
- Normals are flat only. Smooth and crease-aware generation, and stable face/edge/vertex picking ids
  through the *triangle* path, are still to come — the `FLine` path already has picking via callbacks.
- The core is rank-general but only rank-3 seeds exist, and only rank 3 has an isomorphism helper.
  A rank-4 object would need a projection policy above realization, which is a separate decision
  from realization itself.

## See also

- [Meshes & 3D Geometry](mesh_examples.md) — `TriMesh`, `MeshView`, `showMesh` and the CPU rasteriser
  every gallery on this page is rendered by.
- [FLine Examples](fline_examples.md) — `threeLines(...)`, `onClick`, `hoverColor` and the rest of
  the output surface `toFaceFLines()` plugs into.
- [Voronoi, Contours & Clusters](spatial_geometry_examples.md) — the other geometry family that
  hands back structure rather than pixels.
- [Agent Transcript Gallery](agent_transcripts.md) — how the loadable demos above are stored and
  replayed.
