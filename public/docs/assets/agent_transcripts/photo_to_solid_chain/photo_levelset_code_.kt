import afield.examples.chain.PhotoToSolidChain
import afield.geometry.solid.manifold.Solid

// Two independent constructions of the same object: the grid triangulation, and a marched
// level set of the same depth field. Compared against EACH OTHER, not against a constant.
val mount = PhotoToSolidChain.mount()
val edge = 2.0 / 128
Solid.scope {
    // plaqueVolume, not volume: the field describes the plaque, and `volume` is the plaque
    // UNION the frame. Comparing against the wrong one of those reads as a 7% disagreement.
    val marched = PhotoToSolidChain.marchedPlaque(mount.depth, mount.footprintZ, edge)
    val apart = kotlin.math.abs(marched.volume - mount.plaqueVolume) / mount.plaqueVolume
    markdown(
        buildString {
            appendLine(
                "grid volume %.9f, marched %.9f — **%.3f%%** apart at edgeLength %.4f"
                    .format(mount.plaqueVolume, marched.volume, apart * 100, edge),
            )
            appendLine()
            appendLine("Two constructions that share no code, agreeing to about one marching cell.")
        },
        width = 760,
    )
}
