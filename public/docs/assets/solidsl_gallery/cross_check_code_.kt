import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Solid
import afield.lang.solidsl.*

/*
---- the shape, written once -------------------------------------------------------
Everything below reads THIS. Change a number and both evaluations follow, because the
shape is a value rather than a closure — which is the whole of what afield.lang.solidsl is.
*/
val boreRadius = 0.30 // the two side bores. Past ~0.55 they break into the slot;
// watch `genus` and the agreement react rather than the
// picture silently becoming wrong.
val slotWidth = 0.85
val quality = 192 // circular segments. This is the knob that sets the floor the
// two evaluations agree to — see the note further down.

val part =
    (cuboid(4.0, 2.6, 0.7) + cylinder(1.4, 0.8, segments = quality)) -
        cylinder(3.0, 0.42, segments = quality) -
        unionOf(
            [-1.45, 1.45].map { cylinder(3.0, boreRadius, segments = quality).at(it, 0.0, 0.0) },
        ) -
        Profile.rect(2.9, slotWidth).offset(0.22, segments = quality).extruded(3.0).at(0.0, 1.05, -1.5)

// -------------------------------------------------------------------------------------

Solid.scope {

    // A closure cannot do any of the next four lines. `sdfUnion(sdfSphere(1.0), …)` prints as
    // "Function1<Vector3f, Float>", compares by identity, and cannot be asked how big it is.
    println(part)
    println()
    println("nodes           ${part.nodeCount()}")
    println("bounds          ${part.bounds()}")
    println("GLSL            ${part.toGlsl().statementCount} statements, step scale ${part.toGlsl().stepScale}")
    println("exact CSG path  ${part.exactRefusal()?.why ?: "available for every node"}")
    println("field notes     ${part.fieldNotes().size} place(s) where the distance is only sign-correct")
    part.fieldNotes().forEach { println("                - $it") }
    println()

    if (!ManifoldLibrary.isAvailable) {
        // Half the demonstration still works: the field lowering needs nothing installed.
        println(ManifoldLibrary.describe())
        println("Only one of the two evaluations is available, so there is nothing to cross-check.")
        showMesh(
            part.marchedMesh(SolidQuality(edgeLengthFraction = 1.0 / 110.0)),
            title = "afield.lang.solidsl through marching cubes",
            caption =
                "Install Manifold (brew install manifold) and re-run to get the exact CSG " +
                    "evaluation of the same expression beside it, and the agreement between them.",
            view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 720, height = 460),
        )
        return@scope
    }

    /*
    ---- and now the same expression, twice -----------------------------------------------
    Side A: the distance field, sampled on a grid by Field's own marching cubes, in Kotlin.
    Side B: Manifold's exact boolean kernel, in C++, through Panama/FFM.
    */
    val q = SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 110.0)
    val agreement = part.crossCheck(q)

    // isExact says whether native CSG succeeded without a field fallback. Compare it
    // independently of the volume/triangle-count check at the two grid resolutions.
    println("---- is the comparison independent? ----")
    val coarse = part.buildSolid(SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 40.0))
    val fine = part.buildSolid(SolidQuality(segments = quality, edgeLengthFraction = 1.0 / 110.0))
    val unmoved = coarse.solid.volume == fine.solid.volume && coarse.solid.triangleCount == fine.solid.triangleCount
    println("side B at h/40:  volume ${coarse.solid.volume.f9}, ${coarse.solid.triangleCount} triangles")
    println("side B at h/110: volume ${fine.solid.volume.f9}, ${fine.solid.triangleCount} triangles")
    println(
        if (coarse.isExact && fine.isExact) "side B is exact CSG at both resolutions: an independent evaluation"
        else "side B uses field fallback: agreement is self-consistency, not independent cross-validation",
    )
    println(
        if (unmoved) "Volume and triangle count are unchanged under 2.75x refinement."
        else "Volume or triangle count changed under 2.75x refinement.",
    )
    println()

    println("---- the measurement ----")
    println(agreement.report())

    /*
    The floor is not zero, and pretending otherwise would be the interesting lie. Manifold's
    circle is an inscribed n-gon; the field's is a circle. The deficit between them is
    computable from the segment count alone, and for a cylinder it is exact to every digit.
    */
    println("faceting floor at $quality segments: ${"%.3e".format(facetingDeficit(quality))}")
    println("  (a cylinder is a right prism over that n-gon, so its own volume deficit IS this")
    println("   number. Drop `quality` to 24 and watch the agreement above degrade to match.)")
    println()

    // A third opinion, on the marched surface, from code that shares nothing with either side.
    val marched = part.marchedMesh(q)
    println(
        "Field's own topology on the marched surface: watertight ${marched.isWatertight()}, " +
            "genus ${marched.genus()}, signed volume ${marched.signedVolume().f5}",
    )

    val reference = fine.solid
    showMesh(
        reference.toTriMesh(normals = true),
        channel = "exact",
        title = if (fine.isExact) "B — Manifold's exact CSG" else "B — native solid with field fallback",
        caption =
            "${reference.triangleCount} triangles · volume ${reference.volume.f5} · " +
                "genus ${reference.genus} · native solid guaranteed closed",
        view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 700, height = 440),
    )
    showMesh(
        marched,
        channel = "marched",
        title = "A — the same expression, marched from its distance field",
        caption =
            "${marched.triangleCount} triangles · volume " +
                "${marched.signedVolume().f5} · the two disagree by " +
                "${"%.3e".format(agreement.relativeVolumeError)} relative; largest sampled surface deviation " +
                "${agreement.deviation.f4} model units",
        view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 700, height = 440),
    )
    // Mesh viewers keep copied arrays; native solids close on every exit.
    Unit
}
