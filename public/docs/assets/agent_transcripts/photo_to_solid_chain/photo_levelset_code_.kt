import afield.examples.chain.PhotoToSolidChain
import afield.manifold.Solid
import afield.manifold.toSolid

// Two independent constructions of the same object: the grid triangulation, and a marched
// level set of the same depth field. Compared against EACH OTHER, not against a constant.
val mount = PhotoToSolidChain.mount()
val edge = 2.0 / 128
Solid.scope {
    // plaqueVolume, not volume: the field describes the plaque, and `volume` is the plaque
    // UNION the frame. Comparing against the wrong one of those reads as a 7% disagreement.
    val marched = PhotoToSolidChain.marchedPlaque(mount.depth, mount.footprintZ, edge, parallel = false)
    val apart = kotlin.math.abs(marched.volume - mount.plaqueVolume) / mount.plaqueVolume
    println("grid %.9f, marched %.9f — %.3f%% apart at edgeLength %.4f"
        .format(mount.plaqueVolume, marched.volume, apart * 100, edge))
    println("two constructions that share no code, agreeing to about one marching cell")
}

// Solid.levelSet's KDoc says parallel = true is not reproducible. Eight runs each way, in
// three regimes. `floor` erodes the plaque into islands, which is the only thing that
// changes between them — the field, the bounds and the edge length are identical.
val regimes = listOf(
    "the plaque itself" to -0.12,
    "eroded a little" to 0.10,
    "eroded to islands" to 0.14,
)
val rows = regimes.map { (label, floor) ->
    label to PhotoToSolidChain.reproducibilityCensus(mount.depth, mount.footprintZ, 2.0 / 64, floor)
}

// A table, not a paragraph: the whole point is that one column is constant and the other
// is not. An answer counts as the same only if triangles, components AND the volume to
// twelve places all agree, so this cannot report reproducibility that is not there.
markdown(buildString {
    appendLine("| regime | components | `parallel = false` | `parallel = true` |")
    appendLine("| --- | --- | --- | --- |")
    rows.forEach { (label, c) ->
        appendLine("| $label (floor %+.2f) | %d | **%s** distinct | **%s** distinct |"
            .format(c.floor, c.parts, c.sequentialRatio(), c.parallelRatio()))
    }
    appendLine()
    appendLine("8 runs each way per regime, edgeLength ${"%.4f".format(2.0 / 64)}, one depth field.")
    appendLine("Distinct answers are counted on (triangles, components, volume to 1e-12).")
}, width = 760)
