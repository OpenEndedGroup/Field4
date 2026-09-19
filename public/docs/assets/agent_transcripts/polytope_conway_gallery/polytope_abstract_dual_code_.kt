import afield.stdlib.polytope.*

// Take the cube's coordinates away. The topology is untouched, and every combinatorial
// question about the dual is still decidable.
val abstractCube = PolyhedralObject(cube().topology)
val abstract = abstractCube.transform(ConwayOperator.DUAL).polyhedron

// Ask the abstract object for geometry anyway, and keep whatever it actually says.
// These are real caught exceptions; nothing here paraphrases an error message.
val flineRefusal = runCatching { abstract.toFaceFLines() }
    .fold({ "no exception — unexpected" }, { "${it::class.simpleName}: ${it.message}" })
val triangleRefusal = runCatching { abstract.triangulate() }
    .fold({ "no exception — unexpected" }, { "${it::class.simpleName}: ${it.message}" })

// ***** CHANGE THIS *****  Swap DualCentroidRealization for any RealizationPolicy3 —
// `RealizationPolicy3 { _, _ -> null }` keeps the object abstract forever, which is a
// perfectly legal thing for a polytope to be.
val realized = cube().dual(DualCentroidRealization).polyhedron

markdown("dual", """
    #### The dual of `cube()` with and without an embedding

    | | abstract | after a realization policy |
    |---|---|---|
    | `realization` | `${abstract.realization}` | `Realization3(${realized.realization?.size} vertices)` |
    | counts | ${abstract.topology.elements(0).size}v ${abstract.topology.elements(1).size}e ${abstract.topology.elements(2).size}f | ${realized.topology.elements(0).size}v ${realized.topology.elements(1).size}e ${realized.topology.elements(2).size}f |
    | closed manifold | ${abstract.topology.validate(checkManifold = true).isValid} | ${realized.topology.validate(checkManifold = true).isValid} |
    | isomorphic to `octahedron()` | ${abstract.topology.isCombinatoriallyIsomorphicTo(octahedron().topology)} | ${realized.topology.isCombinatoriallyIsomorphicTo(octahedron().topology)} |
    | `toFaceFLines()` | `$flineRefusal` | ${realized.toFaceFLines().size} FLines |
    | `triangulate()` | `$triangleRefusal` | ${realized.triangulate().triangleCount} triangles |

    The left column is a complete, valid, checkable octahedron. It has nowhere to be.
""".trimIndent(), width = 900)

// The registered value printer reduces this to a public data class from Summaries.kt.
abstract.topology
