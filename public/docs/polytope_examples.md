# Polytopes: topology first, coordinates later

`afield.geometry.polytope` builds polyhedra with Conway operators (`conway("kbD")`, `cube().kis()`)
and keeps track of which element of the input each output element came from. A polyhedron here is
a ranked incidence topology first: vertices, edges and faces with identities, which of them touch,
and an ordered boundary for every face. Coordinates are a separate layer on top, which you can
drop, replace or solve for. Triangles for drawing are derived last, and each one knows which face
it belongs to.

Every declaration used below is listed, with its KDoc, in the codebase reference
[Polytopes](reference/polytopes.md). The solver behind `relax` has its own page,
[Solvers](reference/solvers.md).

The example below applies three operators to a dodecahedron and colours each output face by the
dodecahedron face it descends from. The colours come from the faces' history, not from their
positions:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/hero.png" wait="8.0" capture="box" open-outputs="true" w="1180" h="720" padding="14"}
:::static
// One expression: kis of bevel of the dodecahedron. Three chained operators, 360 output faces.
val chain = conway("kbD")
val run = chain.polyhedron.canonicalize()
val solid = if (run.converged) run.polyhedron else chain.polyhedron

// Now colour every one of those faces by the element of the ORIGINAL DODECAHEDRON it descends
// from. Not by position, not by normal, not by size — `sourceElements` walks each output face's
// lineage back through kis, through truncate, through ambo, to a single seed element. The
// symmetry you can see is therefore a picture of the incidence structure, not of the geometry.
:::fold colour each face by the seed pentagon its lineage flattens to, and draw
fun hsv(hue: Double, s: Double, v: Double): Triple<Double, Double, Double> {
    val h = ((hue % 1.0) + 1.0) % 1.0
    val i = Math.floor(h * 6).toInt()
    val f = h * 6 - i
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    return when (i % 6) {
        0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
        3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
    }
}

// Which of the dodecahedron's twelve pentagons does an output face descend from? Element ids are
// allocated rank by rank, so "the lowest source id" would silently pick a vertex every time — ask
// for the rank-2 sources specifically. A face over the interior of one pentagon has exactly one; a
// face straddling an original edge or corner has two or three, and gets a neutral grey.
val faces = solid.topology.elements(2)
fun seedFacesOf(id: ElementId) =
    chain.sourceElements(id).filter { it.rank == 2 }.map { it.id.value }.sorted()

val pentagons = faces.flatMap { seedFacesOf(it.id) }.distinct().sorted()
val share = faces.groupingBy { seedFacesOf(it.id).size }.eachCount().toSortedMap()

// A face over one pentagon takes that pentagon's hue. A face straddling two or three of them takes
// the blend — so the seams of the original dodecahedron colour themselves in between, and the
// twelve-fold pattern reads without a single line being drawn. Saturation and brightness carry a
// second signal: how many seed vertices and edges the face also touches.
fun hueOf(pentagon: Int) = pentagons.indexOf(pentagon).toDouble() / pentagons.size
val faceLines = solid.toFaceFLines { face, line ->
    val owners = seedFacesOf(face.id)
    val sources = chain.sourceElements(face.id)
    val corners = sources.count { it.rank == 0 }
    val edges = sources.count { it.rank == 1 }
    val sat = (1.00 - 0.16 * corners).coerceIn(0.38, 1.0)
    val value = (0.78 + 0.16 * edges + 0.12 * owners.size).coerceIn(0.74, 1.0)
    val mixed = if (owners.isEmpty()) Triple(0.70, 0.74, 0.80) else owners
        .map { hsv(hueOf(it), sat, value) }
        .reduce { a, b -> Triple(a.first + b.first, a.second + b.second, a.third + b.third) }
        .let { Triple(it.first / owners.size, it.second / owners.size, it.third / owners.size) }
    line.filled = true
    line.stroked = false
    line.color = vec(mixed.first, mixed.second, mixed.third, if (owners.size == 1) 0.32 else 0.21)
    line.hoverColor = vec(0.98, 0.99, 1.00, 0.95)
}

threeLines(faceLines, channel = "hero", name = "kbD, canonical, coloured by seed provenance",
    height = 560, autoFrame = true, editable = false)
displayOpen("hero")

val topology = solid.topology
markdown("what you are looking at", listOf(
    "`kbD` = kis · bevel · dodecahedron, canonicalized",
    "${topology.elements(0).size} vertices · ${topology.elements(1).size} edges · " +
        "${topology.elements(2).size} faces · chi = ${topology.eulerCharacteristic()}",
    "seed pentagons found in the lineage: ${pentagons.size}",
    "output faces by how many of those pentagons they straddle: $share",
    "canonical: converged = ${run.converged}, reason = ${run.reason}, " +
        "tangency radius spread ${"%.3e".format(run.initialDiagnostics.tangencyRadiusSpread)} " +
        "→ ${"%.3e".format(run.finalDiagnostics.tangencyRadiusSpread)}",
).joinToString("<br/>"))
:::endfold
```

![A near-spherical solid built from 360 small translucent triangles, layered so the far side shows through the near side. The colours run right around the spectrum — magenta, purple, blue, teal, green, yellow, orange — and gather into twelve rounded patches, one for each pentagon of the original dodecahedron, with blended in-between colours along the original edges where a triangle straddles two pentagons. A readout underneath reports 182 vertices, 540 edges, 360 faces, twelve seed pentagons, and that 240 faces sit over one pentagon while 120 straddle two](assets/polytope_examples/hero.png)

That is three chained Conway operators on a dodecahedron (`kis · bevel · D`, 360 output faces),
canonicalized, and drawn as translucent polygons. Each output face asks `sourceElements` which of
the dodecahedron's pentagons it descends from, back through `kis`, `truncate` and `ambo`, and takes
that pentagon's hue. Faces that straddle two pentagons take the blend, so the dodecahedron's
original edges show up in the in-between colours.

No position, normal or face size was used to choose a colour. The pattern comes from the incidence
structure alone, which each operator carries through even though it computes new coordinates. The
rest of this page shows how. The hero example is long; the sections below start small.

??? info "Where the pieces live"

    | what you want | where it is |
    |---|---|
    | ranked elements, incidence, face cycles, validation, abstract dual | `Topology.kt` — `PolytopeTopology`, `PolytopeTopologyBuilder`, `Element`, `ElementId` |
    | the five Platonic seeds, `Realization3`, `PolyhedralObject` | `Solids.kt` |
    | operators, realization policies, lineage, deployment records | `Transforms.kt` — `ConwayOperator`, `RealizationPolicy3`, `PolytopeTransformResult` |
    | prefix notation `daC`, `k4aC` | `Conway.kt` — `parseConwayExpression`, `conway` |
    | selectors and per-element scalar fields | `Parameters.kt` — `ElementSelectors`, `ElementParameters` |
    | constraint relaxation and its diagnostics | `RealizationSolver.kt` — `relax`, `solveRealization`, `geometryDiagnostics` |
    | the named constraint families a plan is made of | `Constraints.kt` — `RealizationConstraint`, `RelaxationPlan` |
    | coordinates from combinatorics, and the gauge | `Realize.kt` — `realize`, `SpectralRealization`, `RigidGauge` |
    | the canonical form and its diagnostics | `Canonical.kt` — `canonicalize`, `CanonicalDiagnostics` |
    | combinatorial orbits and the symmetry readout | `Symmetry.kt` — `combinatorialOrbits`, `symmetryDeviation` |
    | rank-4 seeds, derived face lattices, projections | `Polytope4.kt` — `tesseract`, `twentyFourCell`, `Projection4` |
    | one `FLine` per face, provenance-preserving triangles | `FieldAdapters.kt` — `toFaceFLines`, `triangulate`, `toEdgeFLines` |
    | compact inspection values for a live prompt | `Summaries.kt`, `PolytopeVisualizers.kt` |

    The full architecture note, including the roadmap this page does *not* cover, is
    [`dev_docs/POLYTOPE_ALGEBRA.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/POLYTOPE_ALGEBRA.md).

## Ranked elements and incidence

`PolytopeTopology` stores elements by rank (0 for vertices, 1 for edges, 2 for faces), the
incidences between adjacent ranks, and an ordered vertex cycle for each face, since knowing which
edges a face touches does not say which way round to walk it. For an ordinary polyhedron, rank 3 is
the single element standing for the whole solid. This example reads all of that back from a cube:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/topology_readout.png" wait="1.5" capture="box" open-outputs="true" w="800" h="560" padding="14"}
:::static
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

Element ids are numbered across the whole topology, not per rank, which is why the cube's first
face is element 20 rather than face 0. An id names one element of one topology, so provenance can
point at a source element without saying which rank it was.

- **Only adjacent ranks are linked.** No vertex-to-face incidence is stored; go through the edges.
  `validate()` checks that incidences agree in both directions, that edges have two endpoints, and
  that each face's cycle matches its edges and forms a single loop.
- **Manifoldness is an extra check, not a requirement.** `validate(checkManifold = true)` also
  requires every edge to have exactly two faces. Topologies that are not closed surfaces are still
  legal.

## Coordinates are optional

A `PolyhedralObject` is a `topology`, a `Realization3` that may be null, and element attributes.
`Realization3` holds the vertex positions in the same order as `topology.elements(0)`, and hands
back copies when you read them.

This example drops the cube's coordinates, takes the dual anyway, and shows which calls still work:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/abstract_dual.png" wait="1.5" capture="box" open-outputs="true" w="980" h="520" padding="14"}
:::static
// Drop the cube's coordinates. The topology survives verbatim.
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

The combinatorial questions (valid, closed, isomorphic to an octahedron) all have answers without
coordinates. The calls that need geometry throw, as shown, rather than making up positions.

`isCombinatoriallyIsomorphicTo` works for rank-3 polyhedra only: it compares vertex degrees, face
sizes and the edge and face lists up to rotation and reversal.

## The operators

Some operators are primitives that work directly on the topology; the rest are named composites
that rewrite into primitives before anything runs. This table is generated from
`ConwayOperator.entries` and `primitiveExpansion`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/operator_table.png" wait="1.5" capture="box" open-outputs="true" w="860" h="640" padding="14"}
:::static
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

![A thirteen-row table listing d dual, a ambo, k kis, t truncate and g gyro as primitives expanding to themselves, then j join, n needle, z zip, o ortho, e expand, s snub, m meta and b bevel as composites with their primitive expansions ad, dk, kd, adad, aa, dgd, adk and at, and equivalent notations daC, kdC, dkC, dadaC, aaC, dgdC, kdaC and taC](assets/polytope_examples/operator_table.png)

The last two columns run in opposite directions. `primitiveExpansion` lists operators in the order
they run, from the seed outward (`join` runs ambo, then dual), while prefix notation writes the
outermost operator first, so you type `daC`. A result reports both: `notationHistory` is what you
asked for, `history` is what ran.

### The seeds

The seeds are the five Platonic solids, each with coordinates. The galleries below use Field's
Three.js viewer (`threeGrid(...)`) on `toTriMesh()`, so you can orbit every panel at once. The grey
line under each name is read from the topology.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/seeds_gallery.png" wait="2.5" capture="box" open-outputs="true" w="1240" h="820" padding="14"}
:::static
:::fold one small gallery helper, reused by the two galleries below
fun Box.gallery(channel: String, columns: Int, cell: Int, panels: List<Pair<String, PolyhedralObject>>) {
    val view = MeshView(wireframe = false, distance = 2.3)
    val rows = (panels.size + columns - 1) / columns
    threeGrid(
        panels.map { (label, p) ->
            val t = p.topology
            ThreeMeshPanel(label, p.toTriMesh(), facts =
                "${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f · " +
                t.elements(2).groupingBy { t.faceCycle(it.id).size }.eachCount().toSortedMap()
                    .entries.joinToString(" ") { "${it.value}×${it.key}-gon" } +
                " · χ=${t.eulerCharacteristic()}")
        },
        channel = channel,
        name = "gallery-$channel",
        columns = columns,
        height = rows * (cell * 0.84).toInt(),
        cellWidth = cell,
        view = view,
    )
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

`cube()` is the odd one out: the other four place their vertices on the unit
sphere, while `cube()` has side length 2, so its vertices sit at radius √3. That matters later,
because the realization solver's default targets are derived from the *incoming* mean edge length
and mean radius.

### The five primitives

This gallery applies each primitive to the cube, with the cube itself first for comparison:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/primitives_gallery.png" wait="3.0" capture="box" open-outputs="true" w="1240" h="800" padding="14"}
:::static
:::fold the same gallery helper as above
fun Box.gallery(channel: String, columns: Int, cell: Int, panels: List<Pair<String, PolyhedralObject>>) {
    val view = MeshView(wireframe = false, distance = 2.3)
    val rows = (panels.size + columns - 1) / columns
    threeGrid(
        panels.map { (label, p) ->
            val t = p.topology
            ThreeMeshPanel(label, p.toTriMesh(), facts =
                "${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f · " +
                t.elements(2).groupingBy { t.faceCycle(it.id).size }.eachCount().toSortedMap()
                    .entries.joinToString(" ") { "${it.value}×${it.key}-gon" } +
                " · χ=${t.eulerCharacteristic()}")
        },
        channel = channel,
        name = "gallery-$channel",
        columns = columns,
        height = rows * (cell * 0.84).toInt(),
        cellWidth = cell,
        view = view,
    )
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
- **`g` gyro** is a genuine topology transform: every *directed* half-edge becomes a pentagon, so
  `gC` is 24 pentagons and nothing else.

The `gC` panel's caption says 24 pentagons, but the picture is a cube. Both are right:
`GyroSubdivisionRealization` places the new points along the existing edges and the new face
centres at the old face centroids, so every pentagon lies flat in one of the cube's square faces,
and flat shading cannot tell them apart. The topology changed; the surface did not.

Giving each polygon its own colour, using the map from triangles back to polygons, shows the
pentagons:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/gyro_boundaries.png" wait="2.5" capture="box" open-outputs="true" w="960" h="700" padding="14"}
:::static
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
// A separate channel from the caption below: `display` REPLACES a channel's content, so a caption
// written to "gyro" would silently take the viewer's place and the figure would lose its mesh.
three(
    painted, channel = "gyro3d", name = "gyro",
    view = MeshView(wireframe = false, width = 860, height = 560, distance = 2.4), facts = false,
)
// The count behind the flatness: 24 faces sharing only 6 distinct outward normals.
val distinctNormals = gyro.topology.elements(2)
    .map { gyro.faceNormal(it.id) }
    .map { "%.3f,%.3f,%.3f".format(it.x, it.y, it.z) }
    .distinct()

markdown("gyro", """
    gC · ${gyro.topology.elements(2).size} faces, all
    ${gyro.topology.elements(2).map { gyro.topology.faceCycle(it.id).size }.distinct().joinToString("/")}-gons ·
    ${distinctNormals.size} distinct face normals: ${distinctNormals.joinToString(" ") { "($it)" }.md}
""")
:::endfold
```

![A shaded cube whose six square faces are each divided into four differently coloured pentagons meeting at the face centre, twenty-four in all. The caption reports 24 faces, all 5-gons, with only 6 distinct face normals — the six axis directions of the original cube](assets/polytope_examples/gyro_boundaries.png)

The operator decides the topology, and a realization policy, a separate argument you can replace,
decides where the new vertices go. Give `gyro` a different `RealizationPolicy3` and the same
pentagons land somewhere else.

### The named composites

The same gallery for the composites, each labelled with the primitives it expands to:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/composites_gallery.png" wait="3.5" capture="box" open-outputs="true" w="1240" h="800" padding="14"}
:::static
:::fold the same gallery helper as above
fun Box.gallery(channel: String, columns: Int, cell: Int, panels: List<Pair<String, PolyhedralObject>>) {
    val view = MeshView(wireframe = false, distance = 2.3)
    val rows = (panels.size + columns - 1) / columns
    threeGrid(
        panels.map { (label, p) ->
            val t = p.topology
            ThreeMeshPanel(label, p.toTriMesh(), facts =
                "${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f · " +
                t.elements(2).groupingBy { t.faceCycle(it.id).size }.eachCount().toSortedMap()
                    .entries.joinToString(" ") { "${it.value}×${it.key}-gon" } +
                " · χ=${t.eulerCharacteristic()}")
        },
        channel = channel,
        name = "gallery-$channel",
        columns = columns,
        height = rows * (cell * 0.84).toInt(),
        cellWidth = cell,
        view = view,
    )
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

A composite is rewritten into its primitives: `conway("bC")` runs `ambo` and then `truncate`, and
the result says so.

Every operator is a method on both `PolyhedralObject` and a transform result, so you can chain them
directly, composites after primitives:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/operator_chaining.png" wait="2.0" capture="box" open-outputs="true" w="900" h="360" padding="14"}
:::static
// Each of these is a composite applied to the result of a primitive.
val chains = listOf(
    "cube().ambo().bevel()" to cube().ambo().bevel(),
    "cube().kis().needle()" to cube().kis().needle(),
    "cube().truncate().ortho()" to cube().truncate().ortho(),
    "cube().dual().zip()" to cube().dual().zip(),
)

:::fold one row per chain: what was asked for, and what actually ran
markdown("chains", """
| chain | result | asked for | actually ran | &chi; |
|---|---|---|---|---|
${chains.joinToString("\n") { (source, r) ->
    val t = r.polyhedron.topology
    "| `$source` | ${t.elements(0).size}v ${t.elements(1).size}e ${t.elements(2).size}f " +
        "| `${r.notationHistory.joinToString(" ") { it.displayName }}` " +
        "| `${r.history.joinToString(" ") { it.displayName }}` " +
        "| ${t.eulerCharacteristic()} |"
}}

`notationHistory` keeps the operators you named; `history` is the primitive trace that ran. A
composite is rewritten before anything executes, so the two columns differ exactly where a chain
used one — `bevel` is `ta`.
""", width = 900)
:::endfold
```

![A four-row table with an asked-for column and an actually-ran column. cube().ambo().bevel() gives 96 vertices, 144 edges and 50 faces, asked for as ambo bevel and run as ambo ambo truncate. cube().kis().needle() gives 38v 108e 72f, asked for as kis needle and run as kis dual kis. cube().truncate().ortho() gives 74v 144e 72f, run as truncate ambo dual ambo dual. cube().dual().zip() gives 24v 36e 14f, run as dual kis dual. Every row has Euler characteristic 2](assets/polytope_examples/operator_chaining.png)

Where you asked for `bevel`, the result records both the `bevel` you named and the `ambo truncate`
that ran, so the lineage stays accurate wherever a composite sits in the chain.

## Prefix notation

`parseConwayExpression` parses; `conway` parses and evaluates. Whitespace is ignored anywhere, the
seed must be the final character, and evaluation is right-to-left (`daC` is `d(a(C))`). Only `k` and
`t` accept a degree index; `kN` restricts kis to faces of degree *N* and `tN` restricts truncate to
vertices of degree *N*.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/parser_table.png" wait="2.0" capture="box" open-outputs="true" w="1100" h="620" padding="14"}
:::static
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
which is what right-to-left evaluation means. The last five rows are the parser's own messages,
including the byte offset it failed at.

## Provenance: every output element remembers where it came from

Each `PolytopeTransformResult` carries, for every output element, an `ElementLineage` tree: either
`Source` (an element of the original seed) or `Derived`, with the operation, a role string and its
parents. `sourceElements(id)` flattens the tree back to the seed. This example prints the lineage
of a bevelled cube:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/lineage.png" wait="2.0" capture="box" open-outputs="true" w="1000" h="560" padding="14"}
:::static
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

The role table answers questions geometry would make you guess at: after a bevel, 14 of the 26
faces descend from the cube's own faces and vertices through `ambo`, and 12 are the faces the
truncation cut.

The roles the primitives emit are a fixed list: `face-center`, `vertex-face`,
`edge-midpoint`, `original-face`, `original-vertex`, `face-apex`, `kis-triangle`,
`vertex-edge-point`, `truncated-face`, `partially-truncated-face`, `directed-edge-point`,
`gyro-pentagon`. Selectors, in the next section, can pick elements by role.

## Selectors: choosing elements without naming indices

An `ElementSelector` is a predicate over an `ElementSelectionContext`: the object, the element, and
its lineage when there is one. `ElementSelectors` supplies the common ones, and they combine with
`and`, `or` and `!`. This example runs a set of them against a kis-ed cube:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/selectors.png" wait="2.0" capture="box" open-outputs="true" w="1020" h="560" padding="14"}
:::static
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

// Two receivers that behave differently: on a PolyhedralObject there is no lineage to consult,
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

That difference follows from what a selector runs against: a bare `PolyhedralObject` has no
transform history to have a role in, and a transform result does.

In the last row, `vertexDegree(4)` matches the six apexes kis raised. On the bare object
`provenanceRole(...)` matches nothing, so its negation matches everything and the combined selector
still finds all six; the transform result correctly finds none. Use negated provenance selectors
only on a transform result.

## Deployed parameters: a scalar field over the selected elements

A selector says where; an `ElementDoubleParameter` says how much, per element. The selective
`kis`, `truncate` and `gyro` overloads take both, evaluate the parameter once per selected element,
and record the values they used in a `TransformDeployment`, so you can inspect or replay the
operation afterwards.

Below, a scalar is written onto each of a dodecahedron's twelve faces as an ordinary attribute, kis
is deployed with `ElementParameters.attribute("spike")`, and then the same scalar is painted back
onto the *rendered triangles* by following `triangulate()`'s triangle → source-polygon map.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/parameter_field.png" wait="3.0" capture="box" open-outputs="true" w="900" h="760" padding="14"}
:::static
// One scalar per face of a dodecahedron: spike height rises from left to right.
var source = dodecahedron()
val realization = source.realization!!
val index = source.topology.elements(0).mapIndexed { i, v -> v.id.value to i }.toMap()
for (f in source.topology.elements(2)) {
    val x = source.topology.faceCycle(f.id).map { realization[index.getValue(it)].x }.average()
    source = source.withAttribute(f.id, "spike", 0.08 + 0.80 * (x + 1.0) / 2.0)
}

// Selection and parameter are separate arguments.
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
// Its own channel — see the note on the gyro figure: a later display() to the same channel replaces.
three(
    painted, channel = "field3d", name = "field",
    view = MeshView(wireframe = false, width = 820, height = 560, distance = 2.3, yawDegrees = 0.0, pitchDegrees = 6.0),
    facts = false,
)
markdown("field", """
    kis deployed over ${deployed.size} faces ·
    heightScale resolved ${"%.3f".format(lo)} … ${"%.3f".format(hi)}

    output ${out.topology.elements(0).size}v ${out.topology.elements(1).size}e ${out.topology.elements(2).size}f ·
    ${g.triangleCount} triangles over ${g.triangleFaceIds.toSet().size} source polygons
""")
:::endfold
```

![A shaded spiked dodecahedron. The pyramids on the left-hand faces are short and coloured cyan, those on the right are long and coloured orange, with a smooth gradient across the middle. Its caption reports kis deployed over 12 faces with heightScale resolved from 0.210 to 0.750, and an output of 32 vertices, 90 edges and 60 faces made of 60 triangles over 60 source polygons](assets/polytope_examples/parameter_field.png)

The spikes lengthen from left to right, and the printed range is the one the deployment record
holds. The colour reaches the triangles because the attribute lives on the polygon and
`TriangulatedGeometry` keeps `sourceFace(triangle)`. Here the selector is every face and only the
height varies; restrict the selector as well and you get kis that is both selective and
non-uniform, as the demo at the bottom of this page does.

## Realization: `realize`, `relax` and `canonicalize`

Everything so far has been combinatorics. There are three calls for turning it into coordinates,
and they promise different things:

| verb | what you give it | what it promises |
|---|---|---|
| `realize(policy)` | a topology, with or without coordinates | coordinates built from the combinatorics by a declared construction |
| `relax(plan)` | an embedding you already have | that embedding, improved under a declared set of named constraints |
| `canonicalize(...)` | an embedding, or none | the symmetric representative, with stated guarantees, and a reported failure when they do not apply |

The realization policies each operator uses by default (centroid dual, edge-midpoint ambo,
face-normal kis, along-edge truncation, gyro subdivision) are local rules of thumb. In the `gyro`
figure the new faces stay flat on the squares they subdivided: the combinatorics of `gC` are those
of the pentagonal icositetrahedron, but its coordinates are not.

### `relax(...)`: repair the embedding you have

`relax(plan)` runs a pure-Kotlin nonlinear least-squares pass over the vertex coordinates. A
`RelaxationPlan` is a list of named, immutable constraint values (`edge-length`, `face-planarity`,
`centroid`, `common-radius`, `proximity`, `edge-tangency`, `tangency-centroid`, `symmetry-orbit`)
plus a least-squares configuration and an optional gauge. Because the plan is a value you can print, compare and reweight
(`plan.withWeight("edge-length", 0.0)`), and the result reports each family's error before and
after, you can see which constraint gave way. Topology and attributes are unchanged; only the
coordinates are replaced. The figure below calls `solveRealization()`, a shorthand that turns five
weights into `RelaxationPlan.regularize()`.

!!! warning "`relax` is a local least-squares pass"

    Levenberg–Marquardt from the supplied embedding is a **local** method, and the `referenceWeight`
    term biases it toward where it started, so what you get is a general constraint relaxation and
    not the unique Conway canonical form. Nothing here checks for self-intersection or inversion,
    and degenerate faces are *reported* rather than repaired.

    The defaults suit the symmetric finite polyhedra this page produces. For a shape that is meant
    to be irregular, turn the common-radius and edge-tangency families off. For the canonical form,
    ask for it by name: that is the next section.

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/realization.png" wait="3.0" capture="box" open-outputs="true" w="1220" h="780" padding="14"}
:::static
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
threeGrid(
    "aC as built" to clean.toTriMesh(),
    "every vertex jittered" to jittered.toTriMesh(),
    "solveRealization()" to solved.polyhedron.toTriMesh(),
    channel = "solve3d", name = "embeddings", columns = 3, height = 340, cellWidth = 400,
    view = MeshView(wireframe = false, distance = 2.5),
)
// A Table for the diagnostics — the numeric columns right-align themselves and each carries its
// own format — inside a markdown card that holds the solver's summary as prose.
val sci = { v: Any? -> "%.3e".format(v) }
fun row(label: String, d: RealizationDiagnostics) = listOf(
    label, d.edgeLengthMean, d.edgeLengthRmsDeviation, d.maxFacePlanarityError,
    d.facePlanarityRmsError, d.degenerateFaces.size,
)

val metrics = Table(listOf(
    "embedding", "edge mean", "edge RMS dev", "max planarity err", "planarity RMS", "degenerate",
))
    .rows(listOf(
        row("aC as built", clean.geometryDiagnostics()),
        row("jittered", solved.initialDiagnostics),
        row("after solveRealization()", solved.finalDiagnostics),
    ))
    .format("edge mean") { "%.5f".format(it) }
    .format("edge RMS dev", sci)
    .format("max planarity err", sci)
    .format("planarity RMS", sci)

markdown("solve", """
    ${metrics.h}

    converged = ${solved.converged} · iterations = ${solved.iterations} ·
    rmsResidual = ${sci(solved.rmsResidual)} · reason = ${solved.reason} ·
    topology object identical = ${solved.polyhedron.topology === jittered.topology}
""")
:::endfold
```

![Three shaded cuboctahedra side by side — the clean one, a visibly lumpy jittered one, and the solved one which is regular again — above a table showing edge RMS deviation falling from 2.741e-01 to near zero and max planarity error falling from 1.293e-01 to near zero, with the solver converging in five iterations on the gradient criterion and the topology object being identical](assets/polytope_examples/realization.png)

The pictures show the shape coming back; the table says by how much. The middle row is
`solved.initialDiagnostics` and the last is `solved.finalDiagnostics`, so a solve that did nothing
useful shows in the numbers.

The recovered edge mean is the jittered one, not the original: with `targetEdgeLength = null` the
target is the input's mean edge length. Pass a target if you want a particular size.

### `canonicalize(...)`: the form the combinatorics already determined

The canonical form of a polyhedron is the embedding where every edge is tangent to one common
sphere, the centroid of those tangency points is the origin, and every face is planar. For the
polyhedra this library produces, it is unique:

!!! info "When the canonical form is unique"

    For a convex polyhedron (by Steinitz's theorem, for any 3-connected planar graph) the canonical
    form **exists and is unique** up to Möbius transformations of the sphere. Pinning the tangency
    centroid at the origin picks one representative; the gauge removes the remaining rigid and scale
    freedom. That covers every Conway operator over a Platonic seed, which is to say everything on
    this page.

    Nothing is guaranteed for non-convex, self-intersecting, higher-genus or non-3-connected input.
    There, the run still returns `converged` and its diagnostics, and expects you to look.

There are two methods. `RECIPROCAL` is Hart's dual reciprocation, which converges in tens of
iterations. `LOCAL_RELAXATION` alternates planarizing, tangentifying and recentring, and takes
thousands of small steps. The default runs the first and polishes with the second. This example
canonicalizes the gyro-cube from earlier:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/canonical.png" wait="4.0" capture="box" open-outputs="true" w="1220" h="820" padding="14"}
:::static
// The gyro-cube, exactly as the operator built it, and the same combinatorics canonicalized.
val built = conway("gC").polyhedron
val run = built.canonicalize()

// Both panels are gauged to the same mean edge length, so the comparison is about shape, not size.
val gauge = RigidGauge()
val before = built.normalize(gauge)
val after = run.polyhedron.normalize(gauge)

:::fold render the two embeddings and the three defining conditions
threeGrid(
    "gC as the operator built it" to before.toTriMesh(),
    "gC.canonicalize()" to after.toTriMesh(),
    channel = "canon3d", name = "embeddings", columns = 2, height = 380, cellWidth = 430,
    view = MeshView(wireframe = false, distance = 3.2),
)
val sci = { v: Any? -> "%.3e".format(v) }
fun row(label: String, d: CanonicalDiagnostics) = listOf(
    label, d.tangencyRadiusSpread, d.tangencyCentroidOffset, d.maxFacePlanarityError,
    d.dihedralAngleMean, d.dihedralAngleSpread,
)
fun degrees(o: PolyhedralObject) =
    o.topology.elements(2).mapNotNull { o.topology.faceCycleOrNull(it.id)?.size }.distinct().sorted()

val metrics = Table(listOf(
    "embedding", "tangency radius spread", "tangency centroid offset", "max planarity err",
    "dihedral mean", "dihedral spread",
))
    .rows(listOf(row("as built", run.initialDiagnostics), row("canonical", run.finalDiagnostics)))
    .format("tangency radius spread", sci)
    .format("tangency centroid offset", sci)
    .format("max planarity err", sci)
    .format("dihedral mean") { "%.4f".format(it) }
    .format("dihedral spread", sci)

markdown("canon", """
    ${metrics.h}

    converged = ${run.converged} · reason = ${run.reason} ·
    reciprocal iterations = ${run.reciprocalIterations} ·
    relaxation iterations = ${run.relaxationIterations} · reseeded = ${run.reseeded}

    distinct face degrees, before and after = ${degrees(before).md} →
    ${degrees(after).md} · topology object identical =
    ${run.polyhedron.topology === built.topology}
""")
:::endfold
```

![Two shaded solids side by side. The left is a plain grey cube — no creases are visible at all, because its twenty-four pentagonal faces lie flat in groups of four on the six square faces of the original cube. The right is the pentagonal icositetrahedron: twenty-four congruent pentagons on a near-spherical body. Below them a table shows the tangency radius spread falling from 3.318e-01 to 9.184e-08 and the dihedral angle spread from 1.571 to 2.807e-07, while the tangency centroid offset and max planarity error start at exactly zero](assets/polytope_examples/canonical.png)

The left panel looks like a plain cube because its pentagons lie flat on the cube's squares. Both
panels share the same topology object (the readout checks it with `===`), and both have only
pentagons; only the coordinates moved.

In the table, the flat cube's planarity error and tangency centroid offset are already zero: it is
planar and centred. What fails is `tangencyRadiusSpread`, the relative spread of distances from the
centre to each edge, which is zero only when one sphere touches every edge. That is the condition
canonicalization fixes, so an embedding can be planar and centred and still far from canonical.
The dihedral spread falls from `1.571` (π/2: the flat cube's faces meet at a right angle or are
coplanar) to `2.8e-07`, so in the canonical form every pentagon meets its neighbours at one angle.

`reseeded = true` in the readout means the iteration could not get there from the flat cube and
restarted from a spectral embedding, described in `realize(...)` below.

??? warning "Where it gives up"

    Because the canonical form is fixed by the combinatorics, a starting embedding the iteration
    cannot use is not by itself a reason to fail: when an attempt diverges or plateaus, the run
    restarts from a spectral embedding and `CanonicalizeResult.reseeded` records which embedding the
    answer came from.

    Not every input converges. Where one does not, the result has `converged = false`,
    `reason = "max iterations"` and finite coordinates. `kkC` below is an example.

    `Realization3` stores Field's single-precision `Vec3`, so the achievable residual floor is about
    `1e-7` relative, however good the iteration is.

#### The counter-example: kkC

`kkC` (kis applied twice to the cube) is a case `canonicalize()` cannot handle. This example shows
three embeddings of the same topology: as built, after `canonicalize()`, and after
`relaxWithCeres`, which needs the Ceres native library:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/kkc_canonical.png" wait="6.0" capture="box" open-outputs="true" w="1240" h="620" padding="14" requires="ceres"}
:::static
// kis applied twice to the cube: 38 vertices, 108 edges, 72 faces. The standing counter-example.
// relaxWithCeres needs the native wrapper: ./gradlew buildCeresRedistributable
val seed = conway("kkC").polyhedron
val iterated = seed.canonicalize()
val solved = seed.relaxWithCeres(RelaxationPlan.canonicalConditions(), CeresRelaxationOptions(maxIterations = 500))

:::fold gauge all three to one scale, render them, and tabulate the three defining conditions
val gauge = RigidGauge()
val panels = listOf(
    "kkC as built" to seed.normalize(gauge),
    "canonicalize()" to iterated.polyhedron.normalize(gauge),
    "relaxWithCeres()" to solved.realization.polyhedron.normalize(gauge),
)
threeGrid(
    panels.map { (label, p) -> ThreeMeshPanel(label, p.toTriMesh()) },
    channel = "kkc3d", name = "embeddings", columns = 3, height = 340, cellWidth = 380,
    view = MeshView(wireframe = false, distance = 3.2),
)
val sci = { v: Any? -> "%.3e".format(v) }
fun row(label: String, p: PolyhedralObject): List<Any?> {
    val d = p.canonicalDiagnostics()
    return listOf(
        label, d.tangencyRadiusSpread, d.tangencyCentroidOffset, d.maxFacePlanarityError,
        d.dihedralAngleMin,
    )
}

val metrics = Table(listOf(
    "embedding", "tangency radius spread", "tangency centroid offset", "max planarity err",
    "min dihedral",
))
    .rows(panels.map { (label, p) -> row(label, p) })
    .format("tangency radius spread", sci)
    .format("tangency centroid offset", sci)
    .format("max planarity err", sci)
    .format("min dihedral") { "%.3f".format(it) }

markdown("kkc", """
    ${metrics.h}

    canonicalize(): converged = ${iterated.converged} · reason = ${iterated.reason}

    relaxWithCeres(): ${solved.summary.md}
""")
:::endfold
```

![Three shaded solids in a row above a table. The left, kkC as built, is a compact spiky ball of triangles. The middle, canonicalize(), is not a solid at all — it has collapsed to a thin diagonal sliver a few pixels wide, essentially a line. The right, relaxWithCeres(), is a clean stellated form with deep triangular spikes. The table gives tangency radius spread 3.386e-01, 1.930e+00 and 5.104e-08 for the three, and minimum dihedral angle 0.955, 0.003 and 0.340; canonicalize() reports converged = false with reason max iterations, while relaxWithCeres converges in 12 iterations](assets/polytope_examples/kkc_canonical.png)

`canonicalize()` reports `converged = false`, and more iterations would not help: its result is
worse than its input, collapsed to a sliver with a minimum dihedral angle of 0.003 and a tangency
spread up from `3.4e-01` to `1.9e+00`. Tangency is measured to the line through an edge, so a
shrinking edge satisfies its constraint for free, and the iteration finds that collapse instead of
the answer. When `canonicalize()` reports `converged = false`, try `relaxWithCeres` with
`RelaxationPlan.canonicalConditions()`, as in the right panel.

??? aside "Why relaxWithCeres reaches kkC"

    `relax` minimises the same formula and collapses the same way on other solids (on `kkT` it
    reports a small spread for a solid with a near-zero edge), so `canonicalConditions()` carries a
    one-sided floor under edge length, `MinEdgeLengthConstraint`, which is zero on a healthy solid.
    The Ceres path also carries each face's plane as a free unit normal plus an offset, and adds a
    gauge term for the free rotation. [`dev_docs/CERES.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/CERES.md) works through all three.

### `realize(...)`: coordinates from the combinatorics alone

`realize` builds coordinates from the topology alone. `SpectralRealization` uses the three
eigenvectors of the vertex graph Laplacian with the smallest non-zero eigenvalues, optionally
projected to the sphere. For a vertex-transitive polyhedron that is already the symmetric solid;
otherwise it is a good starting point for `canonicalize()`. This example takes the gyro-cube's
topology without its coordinates and compares the two routes to its canonical form:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/spectral.png" wait="4.0" capture="box" open-outputs="true" w="1240" h="840" padding="14"}
:::static
// Throw the coordinates away and keep only the incidence structure.
val topologyOnly: PolytopeTopology = conway("gC").polyhedron.topology

val spectral = topologyOnly.realize(SpectralRealization())
val recovered = spectral.canonicalize()

// The same combinatorics reached the other way, for comparison.
val fromOperator = conway("gC").polyhedron.canonicalize()

:::fold render the two routes and their agreement
val gauge = RigidGauge()
threeGrid(
    "1 · spectral only" to spectral.normalize(gauge).toTriMesh(),
    "2 · canonicalize()" to recovered.polyhedron.normalize(gauge).toTriMesh(),
    "3 · canonicalize()" to fromOperator.polyhedron.normalize(gauge).toTriMesh(),
    channel = "spectral3d", name = "routes", columns = 3, height = 330, cellWidth = 380,
    view = MeshView(wireframe = false, distance = 3.2),
)
val dev = recovered.polyhedron.symmetryDeviation()
val spectralOnly = spectral.canonicalDiagnostics()
val sci = { v: Any? -> "%.3e".format(v) }
val routes = Table(listOf("route", "tangency spread", "dihedral mean (rad)"))
    .rows(listOf(
        listOf("1 · spectral only", spectralOnly.tangencyRadiusSpread, spectralOnly.dihedralAngleMean),
        listOf("2 · from combinatorics alone",
            recovered.finalDiagnostics.tangencyRadiusSpread, recovered.finalDiagnostics.dihedralAngleMean),
        listOf("3 · from the operator's coordinates",
            fromOperator.finalDiagnostics.tangencyRadiusSpread, fromOperator.finalDiagnostics.dihedralAngleMean),
    ))
    .format("tangency spread", sci)
    .format("dihedral mean (rad)") { "%.4f".format(it) }

markdown("spectral", """
    ${routes.h}

    {muted:2 and 3 agree on the dihedral mean to} ${"%.2e".format(
        Math.abs(recovered.finalDiagnostics.dihedralAngleMean -
            fromOperator.finalDiagnostics.dihedralAngleMean))} rad

    {muted:symmetry readout of the recovered solid}

    vertex-radius spread ${sci(dev.vertexRadiusSpread)} ·
    edge-length spread ${sci(dev.edgeLengthSpread)} ·
    face-distance spread ${sci(dev.faceDistanceSpread)}

    combinatorial classes — vertices ${dev.orbits.vertexClassSizes.md} ·
    edges ${dev.orbits.edgeClassSizes.md} · faces ${dev.orbits.faceClassSizes.md} ·
    fully transitive = ${dev.orbits.fullyTransitive}
""")
:::endfold
```

![Three shaded solids of the same pentagonal icositetrahedron. The first, built from the graph Laplacian alone with no coordinates supplied, is already close to the right shape but slightly uneven. The second and third are canonical and indistinguishable from one another. Below them, the tangency spread and dihedral mean for each of the three, the agreement between the two canonical routes to within about 1e-9 radians, and the symmetry readout with the combinatorial class sizes](assets/polytope_examples/spectral.png)

Panels 2 and 3 start from different coordinates, one from the graph alone and one from the
operator's placement, and reach the same solid, agreeing on the mean dihedral angle to around
`1e-9` radians.

Panel 1, from the graph alone, already has a tangency spread of `2.0e-02` against the flat cube's
`3.3e-01`, and is close to the right dihedral angle. That is why `canonicalize()` reseeds from it
when an attempt fails.

The symmetry readout comes from `combinatorialOrbits()`, which groups elements by repeatedly
colouring them by their incidences. Each class contains one or more true symmetry orbits; where a
class is coarser than its orbits, the `symmetry-orbit` constraint reports a nonzero residual.
`fullyTransitive = false` is correct here: the pentagonal icositetrahedron is face-transitive but
not vertex-transitive, as the class sizes show.

## Rank 4: the same core, one dimension up

`PolytopeTopology` works at any rank. `Polytope4` uses it for four-dimensional polytopes, which the
Conway operators do not reach: the 5-cell, the tesseract, the 16-cell and the 24-cell, with
`Realization4`, an abstract `dual()`, and projections for viewing.

You do not declare a 4-polytope's faces; they are derived. `convexPolytope4(coordinates,
facetNormals)` takes vertex coordinates and outward facet normals. Each normal's hyperplane picks
out a cell, each pair of cells sharing a two-dimensional set of vertices gives a polygon, each
polygon is ordered by angle in its own plane, and the edges are consecutive polygon vertices.

An edge of a 4-polytope belongs to three or more polygons, so `validate(checkManifold = true)`
rejects a tesseract. `Polytope4` refuses a rank-3 topology. `PolyhedralObject` accepts any rank, but
the Conway operators require rank 3 and `triangulate`, FLine conversion and face normals require a
3D realization. This example prints the four seeds' derived counts and draws a projected tesseract:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/rank4.png" wait="3.0" capture="box" open-outputs="true" w="1180" h="760" padding="14"}
:::static
val seeds = listOf("5-cell" to fiveCell(), "tesseract" to tesseract(),
    "16-cell" to sixteenCell(), "24-cell" to twentyFourCell())

// Viewing is a Projection4 above realization, not part of the geometry.
val projection = RotatedProjection4(
    a = 0, b = 3, angle = 0.30, then = PerspectiveProjection4(eye = 4.2))

val counts = Table(listOf(
    "seed", "V", "E", "F", "C", "V-E+F-C", "polygons per cell", "closed 2-manifold?",
))
    .rows(seeds.map { (name, p) ->
        val s = p.summary()
        val manifold = runCatching { p.topology.validate(checkManifold = true).isValid }.getOrDefault(false)
        listOf(name, s.vertices, s.edges, s.polygons, s.cells, s.eulerCharacteristic,
            s.cellPolygonCounts, manifold)
    })
    // A 4-polytope is not a surface, so the manifold check should answer false.
    .expect("V-E+F-C", because = "the Euler relation for a convex 4-polytope") { it == 0 }
    .expect("closed 2-manifold?", because = "a 4-polytope is not a surface") { it == false }

:::fold draw the tesseract's projected wireframe and print the four seeds' derived counts
val lines = tesseract().toEdgeFLines(projection) { _, line ->
    line.stroked = true
    line.thickness = 2.0
    line.color = vec(0.30, 0.82, 0.96, 0.85)
}
threeLines(lines, channel = "rank4", name = "tesseract, rotated perspective projection",
    height = 430, autoFrame = true, editable = false)
displayOpen("rank4")

markdown("counts", counts.h)
:::endfold
```

![An orbitable wireframe of the tesseract under a rotated perspective projection — a cube inside a cube with the eight connecting edges, visibly mid-rotation so the inner cell is turning out. Below it a table of the four rank-4 seeds with their vertex, edge, polygon and cell counts, all showing V minus E plus F minus C equal to zero, the polygons-per-cell histogram, and every one reporting false for closed 2-manifold](assets/polytope_examples/rank4.png)

Every row satisfies `V − E + F − C = 0`, the Euler relation for a convex 4-polytope, and every row
reports `false` for the closed-2-manifold check, since a 4-polytope is not a surface.

There is no `triangulate()` for rank 4: a projected 4-polytope's polygons do not bound a surface,
so `toEdgeFLines` and `toCellEdgeFLines` give you a wireframe. `OrthographicProjection4` maps the
tesseract's two cubes exactly onto each other; use a rotated or perspective projection to see both.

## Into Field: one FLine per face

`toFaceFLines()` produces one closed, outward-wound `FLine` per face, with the face's id, rank and
attributes attached as line properties. Field's ordinary `filled`, `stroked`, `hoverColor` and
`onClick` then work per face. This example truncates four vertices of an ambo-ed cube and colours
each face by its lineage role:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/face_flines.png" wait="3.0" capture="box" open-outputs="true" w="1240" h="700" padding="14"}
:::static
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

In a box this is the live `threeLines(...)` viewer: you can orbit it, hover a face and, with
`onClick`, pick one and read its provenance. The [loadable demo](#a-loadable-demo) at the foot of
this page does that.

This example prints what the same transform recorded and what each line carries:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/face_fline_properties.png" wait="2.0" capture="box" open-outputs="true" w="1020" h="480" padding="14"}
:::static
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

Both operations are recorded. A uniform operator's `selectedSources` is `null`, meaning every
element it applies to; a selective one records its set, so the chain can be re-run exactly.

### Triangles, derived last, that still know where they came from

`triangulate()` projects each face into 2D, triangulates it with Field's `FloatEarcut`, and fixes
any triangle wound the wrong way. It duplicates polygon corners between faces, because `TriMesh`
stores normals per vertex and each side of a crease needs its own flat normal. This example
compares the polytope's counts with the render mesh's:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/triangulation.png" wait="2.5" capture="box" open-outputs="true" w="1060" h="560" padding="14"}
:::static
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

From the mesh alone, the truncated cube looks like loose polygons with a boundary. The topology
knows it is closed, and `sourceFace` / `sourceVertex` map each triangle and render vertex back to
it.

`EarcutFaceTriangulator` is the default; it handles concave faces and faces with holes.
`FanFaceTriangulator` is for convex faces only.

## A face can have holes

A face is an outer cycle plus zero or more inner rings: `faceCycle` returns the outer one,
`faceHoles` the inner rings, and `faceRings` all of them.

An object with a holed face is no longer a closed manifold, so operations that need one refuse it
with a message. This example builds `cubeWithWindow`, a cube with a square hole in one face, and
lists what it reports and refuses:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/face_with_a_hole.png" wait="2.5" capture="box" open-outputs="true" w="1000" h="620" padding="14"}
:::static
val solid = cubeWithWindow(side = 0.45f)
val topology = solid.topology
val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
val geometry = solid.triangulate()
val rimTriangles = (0 until geometry.triangleCount).count { geometry.sourceFace(it) == windowed.id }
val manifold = topology.validate(checkManifold = true)

// Everything a holed face refuses, and the message it refuses with.
val refusals = listOf<Pair<String, () -> Any>>(
    "topology.dual()" to { topology.dual() },
    "solid.bevel()" to { solid.bevel() },
    "solid.toOff()" to { solid.toOff() },
    "solid.triangulate(FanFaceTriangulator)" to { solid.triangulate(FanFaceTriangulator) },
).map { (name, call) ->
    name to (runCatching { call() }.exceptionOrNull()?.message ?: "no exception — unexpected")
}

:::fold the boundary, the two characteristics, and the refusals
markdown("window", """
#### One face, two boundary rings

| | value |
|---|---|
| outer cycle (`faceCycle`) | ${topology.faceCycle(windowed.id).size} vertices |
| inner rings (`faceHoles`) | ${topology.faceHoles(windowed.id).map { it.size }} |
| all rings (`faceRings`) | ${topology.faceRings(windowed.id).size} |
| &chi; counting elements | ${topology.eulerCharacteristic()} |
| &chi; counting cells | ${topology.cellularEulerCharacteristic()} |
| closed manifold | ${manifold.isValid}, with ${manifold.issues.size} one-sided window edges |
| triangles cut from that face | $rimTriangles — a solid square is 2 |

#### What a holed face refuses, by name

| call | message |
|---|---|
${refusals.joinToString("\n") { (call, message) -> "| `$call` | $message |" }}
""", width = 940)
:::endfold
```

![A two-part table. The first lists the windowed face's outer cycle of four vertices, one inner ring of four, two rings total, Euler characteristic 2 counting elements and 1 counting cells, closed manifold false with four one-sided window edges, and eight triangles cut from that face against two for a solid square. The second lists four refused calls — dual, bevel, toOff and triangulate with FanFaceTriangulator — each with the exception message naming the face and its hole count](assets/polytope_examples/face_with_a_hole.png)

The two Euler characteristics answer different questions. Counting elements gives 2, because a cube
with a window has a cube's elements. Counting cells gives 1, because a face with one hole
contributes `1 - 1`: a sphere with a window punched in it is a disk, and χ of a disk is 1.

### The hole, in pixels, beside the same face without one

This example builds one square face by hand, once with an inner ring and once without, and renders
both with back-face culling on, so a triangle wound the wrong way would be missing. The folded part
shows how to declare a hole with `PolytopeTopologyBuilder.addFaceHole`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/face_hole_counterfactual.png" wait="3.0" capture="box" open-outputs="true" w="880" h="480" padding="14"}
:::static
val hole = 0.45f

:::fold one square face, built by hand, optionally with one inner ring
// This is the whole of the hole API from a caller's side: record the ring's edges as
// incidences of the same face, then declare the ring with addFaceHole. Winding is a
// convention — outer counter-clockwise about the normal, inner clockwise — and nothing
// downstream leans on it, because the tessellator decides which ring is outer geometrically.
fun plate(withRing: Boolean): PolyhedralObject {
    val outer = listOf(Vec3(-1f, -1f, 0f), Vec3(1f, -1f, 0f), Vec3(1f, 1f, 0f), Vec3(-1f, 1f, 0f))
    val inner = listOf(
        Vec3(-hole, -hole, 0f), Vec3(-hole, hole, 0f), Vec3(hole, hole, 0f), Vec3(hole, -hole, 0f),
    )
    // buildList, not `outer + inner`: a box has afield.geometry.path in scope, where
    // `List<Vec3> + List<Vec3>` is element-wise vector addition and returns four points.
    val points = buildList { addAll(outer); if (withRing) addAll(inner) }
    val rings = if (withRing) listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)) else listOf(listOf(0, 1, 2, 3))

    val builder = PolytopeTopologyBuilder(rank = 3)
    val vertices = points.map { builder.addElement(0) }
    val face = builder.addElement(2)
    for (ring in rings) for (i in ring.indices) {
        val edge = builder.addElement(1)
        builder.addIncidence(vertices[ring[i]], edge)
        builder.addIncidence(vertices[ring[(i + 1) % ring.size]], edge)
        builder.addIncidence(edge, face)
    }
    builder.setFaceCycle(face, rings.first().map { vertices[it] })
    rings.drop(1).forEach { ring -> builder.addFaceHole(face, ring.map { vertices[it] }) }
    builder.addIncidence(face, builder.addElement(3))
    return PolyhedralObject(builder.buildValidated(), Realization3(points))
}
:::endfold

val view = MeshView(
    yawDegrees = 0.0, pitchDegrees = 0.0, distance = 2.2,
    width = 300, height = 260, wireframe = true, cullBackFaces = true,
)
val cells = listOf(true, false).map { withRing ->
    val plate = plate(withRing)
    val t = plate.topology
    val face = t.elements(2).single()
    ThreeMeshPanel(
        label = if (withRing) "addFaceHole(face, ring)" else "the same plate, no ring",
        mesh = plate.toTriMesh(),
        facts = "${t.elements(0).size}v ${t.elements(1).size}e · ${t.faceRings(face.id).size} ring(s)" +
            " · ${plate.triangulate().triangleCount} triangles",
    )
}
threeGrid(cells, channel = "plates", name = "plates", columns = 2, height = 260, view = view)
```

![Two wireframe renders side by side. The left plate, built with addFaceHole, is a square annulus — a square frame with an empty square window at its centre, triangulated into eight triangles around the rim, reported as 8 vertices, 8 edges and 2 rings. The right plate is the identical outline with the ring dropped: a solid filled square of 2 triangles, 4 vertices, 4 edges and 1 ring](assets/polytope_examples/face_hole_counterfactual.png)

The holed face is cut into eight triangles around an empty window; without the ring it is two.

`relax` and `canonicalize` use outer rings only, so hole vertices are not constrained.

## The registered value printers

Polytope values have registered printers, so a topology, a transform result or a triangulation
shows a readable summary when you `viz` it or leave it as a box's last expression. This example
shows a selective kis and its triangulation:

```diffstatic_runner {data-lang=kotlin screenshot="assets/polytope_examples/value_printers.png" wait="2.5" capture="box" open-outputs="true" w="1100" h="720" padding="14" scale="3"}
:::static
var seed = cube()
seed.topology.elements(2).take(2).forEach { seed = seed.withAttribute(it.id, "lift", 0.4) }
val result = seed.kis(ElementSelectors.attributeEquals("lift", 0.4), ElementParameters.attribute("lift"))

// Registered printers, not bespoke HTML: each of these reduces to a data class in Summaries.kt.
viz("inspection", listOf(result, result.polyhedron.triangulate()))
```

![Field's reflective value visualizer showing a PolytopeTransformSummary — requested and primitive operations (kis), the output PolyhedralObjectSummary with a PolytopeTopologySummary of rank 3 reporting element counts 10, 20, 12, 1, face degree counts of eight triangles and four squares, Euler characteristic 2, faceHoleCount 0, cellularEulerCharacteristic 2, valid true and closedManifold true, a Realization3Summary of ten vertices and the lift attribute key, then a TransformDeploymentSummary naming the kis operation with a selected count of two — followed by a TriangulatedGeometrySummary of 40 render vertices, 16 triangles, 12 source polygon faces, 10 source vertex elements and flat normals present](assets/polytope_examples/value_printers.png)

Click a `…` link in a live box to expand a nested value. `println(result.summary())` prints the
same facts as text. Useful last expressions:

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

Each of these starting points is a document you can open, edit and re-run:

- [`polytope_conway_demo`](agent_transcripts.md#polytope_conway_demo): one compact box that marks two
  opposite cube faces with different heights, applies `kis` only there, and sends the polygon faces
  to the on-canvas viewer. Hover a face, click it, and a provenance card reports the output face id,
  the transform role, the deployed height, the source elements and the 3D hit position.
- [`polytope_conway_gallery`](agent_transcripts.md#polytope_conway_gallery): small boxes for a Conway-expression
  gallery you can retype, a topology-only
  dual shown before a policy supplies coordinates, a non-uniform parameter field painted
  onto the output faces, and a realization before/after that leaves the diagnostics as its final
  value.
- [`polytope_canonical_gallery`](agent_transcripts.md#polytope_canonical_gallery): one box that evaluates a Conway
  expression, canonicalizes it, and draws the realized and canonical forms side by side with the
  diagnostics underneath. Change `val expression = "gD"` to `"ktC"`, `"bD"` or `"sD"` and re-run.
  Set it to `"kkC"` and the box reports that `canonicalize()` did not converge.
- [`polytope_holes_and_chains`](agent_transcripts.md#polytope_holes_and_chains): the `cubeWithWindow` seed with its
  rings, Euler characteristics and refusals, as clickable polygon FLines; the hand-built plate with
  and without its hole; and every named composite chained off one `ambo`, with what each chain
  asked for beside what ran.

## Limitations

- A face carries an outer cycle and any number of disjoint inner rings. A hole ring that touches its
  outer boundary, nested rings, and star/self-intersecting polygons are not represented; Earcut's
  winding and filling rules would be ambiguous for them.
- Transform parameters are maps of `String` to `Double` in the deployment records. There are no typed
  parameter descriptors, domains, units or composition yet.
- There is no Wythoff construction, so the canonicalizer finds symmetry numerically instead of
  being given it. For hard cases such as `kkC`, use `relaxWithCeres`; `kkT`, `kkO` and `kkI`
  reach a spread of about `1e-4` even there.
- `relax(...)` builds its Jacobian by finite differences over `3·V` parameters, so it suits
  polyhedra with tens of vertices, not large meshes.
- Normals are flat only. Picking a face works through the `FLine` path (`onClick`), not through
  `triangulate()`.
- Rank 4 has seeds, projections and a wireframe adapter, but no Conway operators. Ranks above 4
  are allowed by the core but untested.

## See also

- [Polytopes reference](reference/polytopes.md) — every public declaration in
  `afield.geometry.polytope`, with the KDoc that lives beside it: the operator algebra and its
  composition rules, the realization verbs and their diagnostics, the attribute and selector algebra,
  and the `FLine`/`TriMesh` adapters.
- [Solvers reference](reference/solvers.md) — the Ceres constraint vocabulary behind `relax` and
  `CeresRealization`, plus the expression VM for residuals the vocabulary cannot say.
- [Meshes & 3D Geometry](mesh_examples.md) — `TriMesh`, `MeshView`, `showMesh` and the Three.js viewer
  every gallery on this page is rendered by.
- [FLine Examples](fline_examples.md) — `threeLines(...)`, `onClick`, `hoverColor` and the rest of
  the output surface `toFaceFLines()` plugs into.
- [Voronoi, Contours & Clusters](spatial_geometry_examples.md) — the other geometry family that
  hands back structure rather than pixels.
- [Agent Transcript Gallery](agent_transcripts.md) — how the loadable demos above are stored and
  replayed.
